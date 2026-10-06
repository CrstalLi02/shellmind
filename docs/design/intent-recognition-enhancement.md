# ShellMind Intent Recognition Enhancement — Technical Design

> Based on ShellMind context memory and intent recognition architecture, adapted to ShellMind's Spring Boot DDD + Google ADK stack.

---

## 1. Background and Goals

### 1.1 Current Gaps

ShellMind currently has the following capability gaps:

| Dimension | Current State | Problem |
|------|------|------|
| **System Prompt** | Static YAML `instruction` field | Cannot inject environment info, history, or user intent at runtime |
| **Message history** | ADK `InMemoryRunner` in-memory, no trimming | Context grows with turns and may exceed the model context window |
| **Intent recognition** | None | The agent cannot perceive user intent early or prepare related context |
| **Intent enhancement** | None | Signals in user input (service names, file paths, error codes) are unused |
| **Session persistence** | Memory only; lost on restart | Conversation history cannot survive restarts |
| **Context awareness** | ThreadLocal terminal session binding | Terminal binding only; no terminal state or command-history awareness |

### 1.2 Design Goals

1. **Dynamic prompt construction**: Upgrade the system prompt from a static YAML blob to a runtime-assembled prompt
2. **Context memory management**: Provider-Reducer pipeline to keep context within budget
3. **Intent recognition**: Two-layer classifier chain (rules + LLM) for SSH operations
4. **Intent enhancement**: Signal extraction → server context search → prompt injection
5. **Session persistence**: MySQL persistence + Redis cache so restarts do not drop history

### 1.3 ShellMind Design Patterns

Core ideas:

- **Provider-Reducer pipeline**: Decouple context collection (Provider) from message trimming (Reducer)
- **Three-layer classifier chain (reference)**: Rules (fast, coarse) → model (medium) → LLM (slow, accurate), escalating as needed
- **"Signal extraction → code/server search → LLM decision" enhancement**: Keywords extract information only; they do not decide
- **Milestone system**: Key-event memory independent of message trimming

---

## 2. Overall Architecture

### 2.1 Architecture Overview

```
User input: "nginx is returning 502, help me troubleshoot"
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│  AIAgentReActServiceCase.chatStream()                   │
│                                                         │
│    ├─ ① IIntentService.classify()              Intent recognition
│    │     ├─ RuleIntentClassifier  (< 1ms, coarse)
│    │     └─ LLMIntentClassifier   (100-500ms, accurate)
│    │                                                        │
│    ├─ ② IIntentEnhancerService.enhance()       Intent enhancement
│    │     ├─ SignalExtractor      → service / path / error
│    │     └─ ContextSearch        → status / config / logs
│    │                                                        │
│    ├─ ③ IChatContextService.buildContext()     Context management
│    │     ├─ TerminalStateProvider → OS / user / cwd
│    │     ├─ TaskProvider          → current task
│    │     ├─ MilestoneProvider     → key events
│    │     ├─ ToolResultProvider    → tool result summaries
│    │     └─ HybridReducer         → trim to token budget
│    │                                                        │
│    ├─ ④ IPromptService.buildEnrichedMessage()  Dynamic prompt (domain service)
│    │     ├─ TerminalState collection (ISshTerminalService)
│    │     ├─ MilestoneTracker (record and fetch key events)
│    │     └─ DynamicPromptBuilder (base instruction + env + intent + context)
│    │                                                        │
│    └─ ⑤ AiCallNode → runner.runAsync()         ADK call
│                                                         │
│        LLM response → tool calls → results → promptService.detectAndRecordMilestone() → stream
└─────────────────────────────────────────────────────────┘
```

### 2.2 New Module Layout

```
shellmind-server-domain/
├── agent/
│   ├── service/
│   │   ├── IChatContextService.java              Context management domain service
│   │   ├── IIntentService.java                   Intent recognition domain service
│   │   ├── IIntentEnhancerService.java           Intent enhancement domain service
│   │   ├── IPromptService.java                   Prompt construction domain service
│   │   ├── armory/                               Agent assembly (Google ADK)
│   │   ├── context/                              Context memory implementations
│   │   │   ├── ChatContextService.java           Domain service implementation
│   │   │   ├── provider/
│   │   │   │   ├── ContextProvider.java          Provider interface
│   │   │   │   ├── TerminalStateProvider.java    Terminal state (OS, user, cwd)
│   │   │   │   ├── TaskProvider.java             Current task
│   │   │   │   ├── MilestoneProvider.java        Milestone events
│   │   │   │   └── ToolResultProvider.java       Tool result summaries
│   │   │   └── reducer/
│   │   │       ├── MessageReducer.java           Reducer interface
│   │   │       ├── PriorityReducer.java          Priority-based trim
│   │   │       ├── SlidingWindowReducer.java     Sliding-window trim
│   │   │       └── HybridReducer.java            Hybrid trim (default)
│   │   ├── intent/                               Intent recognition implementations
│   │   │   ├── IntentService.java                Domain service implementation
│   │   │   ├── ContextTracker.java               Conversation context tracker (internal)
│   │   │   └── classifier/
│   │   │       ├── IntentClassifier.java         Classifier interface
│   │   │       ├── RuleIntentClassifier.java     Layer 1: rule classification
│   │   │       └── LLMIntentClassifier.java      Layer 2: LLM classification
│   │   ├── enhance/                              Intent enhancement implementations
│   │   │   ├── IntentEnhancerService.java        Domain service implementation
│   │   │   └── extractor/
│   │   │       ├── SignalExtractor.java          Signal extraction (internal)
│   │   │       └── ContextSearch.java            Server context search (internal)
│   │   ├── prompt/                               Prompt construction implementations
│   │   │   ├── PromptService.java                Domain service implementation
│   │   │   └── builder/
│   │   │       ├── DynamicPromptBuilder.java     Dynamic prompt assembler (internal)
│   │   │       └── MilestoneTracker.java         Milestone tracker (internal)
│   │   └── valobj/
│   │       ├── prompt/
│   │       │   ├── PromptContextVO.java          Prompt context value object
│   │       │   └── MilestoneVO.java              Milestone
│   │       ├── IntentResult.java                 Intent classification result
│   │       ├── ExtractedSignals.java             Extracted signals
│   │       ├── SearchContext.java                Search context
│   │       └── ConversationContext.java          Conversation context
│   └── adapter/repository/
│       └── ChatMessageEntity.java                Chat message entity
└── adapter/repository/
    └── IChatHistoryRepository.java               Chat history persistence gateway
```

---

## 3. Phase 1: Dynamic Prompt Construction

### 3.1 Design Notes

The system prompt currently comes entirely from the static YAML `instruction` text and cannot inject environment info or history at runtime. This phase introduces the `IPromptService` domain service to assemble a complete prompt before each LLM call. To stay DDD-compliant, the case layer (`AiCallNode`) depends only on `IPromptService`, not on internal components such as `DynamicPromptBuilder` or `MilestoneTracker`.

### 3.2 Domain Model (Value Objects)

```java
@Data
@Builder
public class PromptContextVO {
    private String osInfo;
    private String currentUser;
    private String currentDirectory;
    private String serverInfo;
    private List<String> recentCommands;
    private List<MilestoneVO> milestoneVOS;
    // Extended in later phases
    // private Map<String, String> serviceStatus;
    // private Map<String, String> fileContents;
    // private Map<String, String> recentLogs;
}

@Data
@Builder
public class MilestoneVO {
    public enum Type { TASK_CHANGE, TASK_COMPLETE, USER_CORRECTION, ERROR, DECISION }
    private Type type;
    private String content;
    private long timestamp;
}
```

### 3.3 Domain Service

#### IPromptService interface

Unified facade exposed to the case layer:

```java
package com.shellmind.domain.agent.service;

public interface IPromptService {
    String buildEnrichedMessage(String userMessage, String sessionId,
                                String terminalSessionId, List<String> recentCommands);
    void detectAndRecordMilestone(String sessionId, String role, String content);
    List<MilestoneVO> getRecentMilestones(String sessionId, int limit);
    void clearMilestones(String sessionId);
}
```

#### PromptService implementation

Composes internal components:

```java
@Service
public class PromptService implements IPromptService {
    @Resource private DynamicPromptBuilder dynamicPromptBuilder;
    @Resource private MilestoneTracker milestoneTracker;
    @Resource private ISshTerminalService sshTerminalService;

    @Override
    public String buildEnrichedMessage(String userMessage, String sessionId,
                                       String terminalSessionId, List<String> recentCommands) {
        // 1. Collect environment info from the SSH terminal
        // 2. Load events from milestoneTracker
        // 3. Build PromptContextVO
        // 4. Call dynamicPromptBuilder.buildMessagePrefix() to generate the prefix
        // 5. Concatenate and return
        ...
    }

    // Other methods delegate to tracker
}
```

### 3.4 Dynamic Assembler (DynamicPromptBuilder)

```java
@Component
public class DynamicPromptBuilder {

    /**
     * Build a user-message prefix from dynamic context (injected into the user message).
     * Used when ADK cannot mutate the system instruction at runtime.
     */
    public String buildMessagePrefix(PromptContextVO ctx) {
        StringBuilder sb = new StringBuilder();
        // Append [System environment]
        // Append [Recently executed commands]
        // Append [Key events] (Milestones)
        ...
        return sb.toString();
    }
}
```

### 3.5 AiCallNode Changes (Case Layer)

Inject only `IPromptService` in `AiCallNode`:

```java
// AiCallNode.java change points
@Resource
private IPromptService promptService;

private String buildEnrichedMessage(String userMessage, DynamicContext dynamicContext) {
    // Record a milestone for the user message
    promptService.detectAndRecordMilestone(dynamicContext.getSessionId(), "user", userMessage);

    // Delegate enriched-message construction to the domain service
    return promptService.buildEnrichedMessage(
            userMessage,
            dynamicContext.getSessionId(),
            dynamicContext.getTerminalSessionId(),
            dynamicContext.getRecentCommands()
    );
}

// On tool execution result callback:
promptService.detectAndRecordMilestone(dynamicContext.getSessionId(), "tool", resultContent);
```

---

## 4. Phase 2: Context Memory Management

### 4.1 Design Notes

Use ShellMind's **Provider-Reducer pipeline** to decouple context collection from message trimming. Providers collect context dimensions; Reducers trim messages to the token budget.

### 4.2 ContextProvider Interface

```java
package com.shellmind.domain.agent.service.context.provider;

import java.util.Map;

public interface ContextProvider {
    String getName();
    int getOrder();
    boolean enabled();
    Map<String, Object> provide(String sessionId, String userId);
}
```

### 4.3 Four Provider Implementations

#### TerminalStateProvider (order=10)

Provides current terminal system environment:

```java
@Component
public class TerminalStateProvider implements ContextProvider {
    @Resource
    private ISshTerminalService sshTerminalService;

    @Override public String getName() { return "terminal-state"; }
    @Override public int getOrder() { return 10; }
    @Override public boolean enabled() { return true; }

    @Override
    public Map<String, Object> provide(String sessionId, String userId) {
        Map<String, Object> result = new HashMap<>();
        String osInfo = safeExec(sessionId, "uname -srm");
        String user   = safeExec(sessionId, "whoami");
        String pwd    = safeExec(sessionId, "pwd");
        String uptime = safeExec(sessionId, "uptime -p 2>/dev/null || uptime");

        result.put("osInfo", osInfo);
        result.put("currentUser", user);
        result.put("currentDirectory", pwd);
        result.put("uptime", uptime);
        return result;
    }

    private String safeExec(String sessionId, String cmd) {
        try { return sshTerminalService.executeCommand(sessionId, cmd).trim(); }
        catch (Exception e) { return ""; }
    }
}
```

#### TaskProvider (order=20)

Extracts the current conversation task description:

```java
@Component
public class TaskProvider implements ContextProvider {
    @Override public String getName() { return "task"; }
    @Override public int getOrder() { return 20; }
    @Override public boolean enabled() { return true; }

    @Override
    public Map<String, Object> provide(String sessionId, String userId) {
        Map<String, Object> result = new HashMap<>();
        // Use the first user message in DynamicContext.messageHistory as the task description
        List<Map<String, Object>> history = messageHistoryCache.get(sessionId);
        if (history != null) {
            history.stream()
                .filter(m -> "user".equals(m.get("role")))
                .findFirst()
                .ifPresent(m -> result.put("taskDescription", m.get("content")));
        }
        return result;
    }
}
```

#### MilestoneProvider (order=30)

Provides key events that survive trimming:

```java
@Component
public class MilestoneProvider implements ContextProvider {
    @Resource
    private MilestoneTracker milestoneTracker;

    @Override public String getName() { return "milestoneVO"; }
    @Override public int getOrder() { return 30; }
    @Override public boolean enabled() { return true; }

    @Override
    public Map<String, Object> provide(String sessionId, String userId) {
        Map<String, Object> result = new HashMap<>();
        List<MilestoneVO> milestoneVOS = milestoneTracker.getRecent(sessionId, 10);
        result.put("milestoneVOS", milestoneVOS);
        return result;
    }
}
```

#### ToolResultProvider (order=40)

Lazy-summary strategy for tool execution results:

```java
@Component
public class ToolResultProvider implements ContextProvider {
    private final Map<String, List<ToolResultEntry>> results = new ConcurrentHashMap<>();
    private final Map<String, String> summaryCache = new ConcurrentHashMap<>();

    @Override public String getName() { return "tool-result"; }
    @Override public int getOrder() { return 40; }
    @Override public boolean enabled() { return true; }

    @Override
    public Map<String, Object> provide(String sessionId, String userId) {
        Map<String, Object> result = new HashMap<>();
        List<ToolResultEntry> entries = results.getOrDefault(sessionId, Collections.emptyList());
        if (entries.isEmpty()) return result;

        // Lazy summary: return cache if present, otherwise regenerate
        String summary = summaryCache.computeIfAbsent(sessionId, id -> generateSummary(entries));
        result.put("toolResultSummary", summary);
        return result;
    }

    public void pushResult(String sessionId, ToolResultEntry entry) {
        results.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(entry);
        summaryCache.remove(sessionId);  // Invalidate summary cache
    }

    private String generateSummary(List<ToolResultEntry> entries) {
        // Concatenate a few results; template-compress many results
        if (entries.size() <= 5) {
            return entries.stream()
                .map(e -> e.getToolName() + ": " + truncate(e.getResult(), 100))
                .collect(Collectors.joining("\n"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Recently executed ").append(entries.size()).append(" tool calls:\n");
        // Keep the last 5 in detail plus a summary
        List<ToolResultEntry> recent = entries.subList(entries.size() - 5, entries.size());
        for (ToolResultEntry e : recent) {
            sb.append("- ").append(e.getToolName()).append(": ")
              .append(truncate(e.getResult(), 80)).append("\n");
        }
        return sb.toString();
    }
}
```

### 4.4 MessageReducer Trim Strategies

#### Interface

```java
package com.shellmind.domain.agent.service.context.reducer;

import java.util.List;
import java.util.Map;

public interface MessageReducer {
    List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget);
}
```

#### PriorityReducer — priority-based trim

```java
@Component
public class PriorityReducer implements MessageReducer {

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        // Infer priority for each message
        List<PrioritizedMessage> prioritized = messages.stream()
            .map(m -> new PrioritizedMessage(m, inferPriority(m)))
            .collect(Collectors.toList());

        // Always keep at least the last 2 messages
        int minKeep = Math.min(2, prioritized.size());
        List<PrioritizedMessage> kept = new ArrayList<>(prioritized.subList(
            prioritized.size() - minKeep, prioritized.size()));

        // Drop from lowest priority until the token budget is met
        int usedTokens = estimateTokens(kept);
        for (int i = prioritized.size() - minKeep - 1; i >= 0; i--) {
            PrioritizedMessage pm = prioritized.get(i);
            int msgTokens = estimateToken(pm.getMessage());
            if (usedTokens + msgTokens <= tokenBudget) {
                kept.add(0, pm);
                usedTokens += msgTokens;
            }
        }

        return kept.stream().map(PrioritizedMessage::getMessage).collect(Collectors.toList());
    }

    private Priority inferPriority(Map<String, Object> message) {
        String role = (String) message.get("role");
        String content = String.valueOf(message.get("content"));

        if ("tool".equals(role) && containsAny(content, "error", "failed", "exception", "permission denied")) {
            return Priority.CRITICAL;
        }
        if ("user".equals(role) && containsAny(content, "/", ".conf", ".yml", ".properties")) {
            return Priority.HIGH;
        }
        if ("system".equals(role)) {
            return Priority.HIGH;
        }
        if ("assistant".equals(role) && content.length() > 5000) {
            return Priority.LOW;
        }
        return Priority.MEDIUM;
    }

    enum Priority { CRITICAL, HIGH, MEDIUM, LOW }
}
```

#### SlidingWindowReducer — sliding-window trim

```java
@Component
public class SlidingWindowReducer implements MessageReducer {
    private static final int DEFAULT_WINDOW_SIZE = 20;

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        List<Map<String, Object>> window = new ArrayList<>();
        int usedTokens = 0;

        // Add newest-first until token budget or window size is exceeded
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            int msgTokens = estimateToken(msg);
            if (window.size() >= DEFAULT_WINDOW_SIZE || usedTokens + msgTokens > tokenBudget) break;
            window.add(0, msg);
            usedTokens += msgTokens;
        }
        return window;
    }
}
```

#### HybridReducer — hybrid trim (default)

```java
@Component
public class HybridReducer implements MessageReducer {
    @Resource private PriorityReducer priorityReducer;
    @Resource private SlidingWindowReducer slidingReducer;

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        Set<Integer> priorityKeep = indexSet(priorityReducer.reduce(messages, tokenBudget), messages);
        Set<Integer> slidingKeep  = indexSet(slidingReducer.reduce(messages, tokenBudget), messages);

        // Intersection
        Set<Integer> keepIndices = new HashSet<>(priorityKeep);
        keepIndices.retainAll(slidingKeep);

        // Always keep at least the last 2 messages
        int minKeep = Math.min(2, messages.size());
        for (int i = messages.size() - minKeep; i < messages.size(); i++) {
            keepIndices.add(i);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (keepIndices.contains(i)) result.add(messages.get(i));
        }
        return result;
    }

    private Set<Integer> indexSet(List<Map<String, Object>> subset, List<Map<String, Object>> all) {
        Set<Integer> indices = new HashSet<>();
        for (Map<String, Object> msg : subset) {
            int idx = all.indexOf(msg);
            if (idx >= 0) indices.add(idx);
        }
        return indices;
    }
}
```

### 4.5 MilestoneTracker — Milestone System

Keyword patterns below use English equivalents in this design document. Production matchers that must also catch Chinese user phrasing should use Unicode escapes (`\\uXXXX`) so source files stay CJK-free.

```java
@Component
public class MilestoneTracker {
    private static final int MAX_MILESTONES = 50;
    private final Map<String, LinkedList<MilestoneVO>> milestoneVOS = new ConcurrentHashMap<>();

    public void detectAndRecord(String sessionId, String role, String content) {
        MilestoneVO.Type type = null;

        if ("user".equals(role)) {
            if (matches(content, "wrong|not like that|change it|try another approach|different way|that's incorrect")) {
                type = MilestoneVO.Type.TASK_CHANGE;
            } else if (matches(content, "done|finished|that's all|ok that's it")) {
                type = MilestoneVO.Type.TASK_COMPLETE;
            } else if (matches(content, "don't|stop|never mind")) {
                type = MilestoneVO.Type.USER_CORRECTION;
            }
        }

        if ("tool".equals(role)) {
            if (matches(content, "(?i)error|failed|exception|permission denied|not found|refused")) {
                type = MilestoneVO.Type.ERROR;
            }
        }

        if (type != null) {
            push(sessionId, MilestoneVO.builder()
                .type(type)
                .content(truncate(content, 200))
                .timestamp(System.currentTimeMillis())
                .build());
        }
    }

    private void push(String sessionId, MilestoneVO milestoneVO) {
        LinkedList<MilestoneVO> list = milestoneVOS.computeIfAbsent(
            sessionId, k -> new LinkedList<>());
        synchronized (list) {
            list.addLast(milestoneVO);
            while (list.size() > MAX_MILESTONES) list.removeFirst();
        }
    }

    public List<MilestoneVO> getRecent(String sessionId, int limit) {
        LinkedList<MilestoneVO> list = milestoneVOS.getOrDefault(sessionId, new LinkedList<>());
        synchronized (list) {
            int from = Math.max(0, list.size() - limit);
            return new ArrayList<>(list.subList(from, list.size()));
        }
    }

    public void clear(String sessionId) {
        milestoneVOS.remove(sessionId);
    }

    private boolean matches(String content, String regex) {
        return content != null && content.matches(".*(" + regex + ").*");
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
```

### 4.6 IChatContextService and ChatContextService

Extract a unified `IChatContextService` interface and orchestrate Providers and Reducers in `ChatContextService`:

```java
package com.shellmind.domain.agent.service;

public interface IChatContextService {
    PromptContextVO buildPromptContext(String sessionId, String userId, String terminalSessionId);
    List<Map<String, Object>> trimHistory(List<Map<String, Object>> history, int tokenBudget);
}
```

```java
package com.shellmind.domain.agent.service.context;

import com.shellmind.domain.agent.service.IChatContextService;
import com.shellmind.domain.agent.service.IPromptService;
@Service
public class ChatContextService implements IChatContextService {
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 8000;

    @Resource
    private List<ContextProvider> providers;
    @Resource
    private HybridReducer hybridReducer;
    @Resource
    private IPromptService promptService;
    @Resource
    private ISshTerminalService sshTerminalService;

    @PostConstruct
    public void init() {
        providers.sort(Comparator.comparingInt(ContextProvider::getOrder));
    }

    @Override
    public PromptContextVO buildPromptContext(String sessionId, String userId, String terminalSessionId) {
        // Lombok Builder: assemble via chained calls or a temporary map
        Map<String, Object> finalCtx = new HashMap<>();

        for (ContextProvider provider : providers) {
            if (!provider.enabled()) continue;
            Map<String, Object> ctx = provider.provide(sessionId, userId);
            finalCtx.putAll(ctx);
        }

        return PromptContextVO.builder()
                .osInfo((String) finalCtx.get("osInfo"))
                .currentUser((String) finalCtx.get("currentUser"))
                .currentDirectory((String) finalCtx.get("currentDirectory"))
                .serverInfo((String) finalCtx.get("serverInfo"))
                .milestoneVOS((List<MilestoneVO>) finalCtx.get("milestoneVOS"))
                // Extended in later phases
                // .serviceStatus((Map<String, String>) finalCtx.get("serviceStatus"))
                // .fileContents((Map<String, String>) finalCtx.get("fileContents"))
                // .recentLogs((Map<String, String>) finalCtx.get("recentLogs"))
                .build();
    }

    @Override
    public List<Map<String, Object>> trimHistory(List<Map<String, Object>> history, int tokenBudget) {
        if (history == null || history.isEmpty()) return Collections.emptyList();
        return hybridReducer.reduce(history, tokenBudget > 0 ? tokenBudget : DEFAULT_MAX_CONTEXT_TOKENS);
    }
}
```

---

## 5. Phase 3: Intent Recognition

### 5.1 Design Notes

Use a **two-layer classifier chain** (rules + LLM) for backend operations. Skip ShellMind's frontend "model classifier" layer and go rules → LLM to reduce latency.

### 5.2 Intent Types

```java
public enum IntentType {
    DIAGNOSE("Diagnose a problem"),
    CONFIGURE("Change configuration"),
    DEPLOY("Deploy or roll back"),
    MONITOR("Monitor or inspect"),
    SECURITY("Security related"),
    BACKUP("Backup or restore"),
    EXECUTE("Execute directly"),
    EXPLAIN("Explain"),
    SEARCH("Search"),
    CHAT("Casual chat"),
    CONTINUE("Continue"),
    UNKNOWN("unknown");

    private final String label;
    IntentType(String label) { this.label = label; }
    public String getLabel() { return label; }
}
```

### 5.3 IntentResult Value Object

```java
@Data
@Builder
public class IntentResult {
    private IntentType intent;
    private double confidence;
    private Map<String, String> entities;
    private String rawResponse;
}
```

### 5.4 IIntentService and IntentService

Unified intent-classification domain service; classifier orchestration stays internal:

```java
package com.shellmind.domain.agent.service;

public interface IIntentService {
    IntentResult classify(String sessionId, String userId, String message);
}
```

```java
package com.shellmind.domain.agent.service.intent;

import com.shellmind.domain.agent.service.IIntentService;

@Service
public class IntentService implements IIntentService {
    @Resource private RuleIntentClassifier ruleClassifier;
    @Resource private LLMIntentClassifier llmClassifier;
    @Resource private ContextTracker contextTracker;

    private final Cache<String, IntentResult> cache = Caffeine.newBuilder()
        .maximumSize(200)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build();

    @Override
    public IntentResult classify(String sessionId, String userId, String message) {
        String cacheKey = sessionId + ":" + hashMessage(message);
        IntentResult cached = cache.getIfPresent(cacheKey);
        if (cached != null) return cached;

        ConversationContext context = contextTracker.getContext(sessionId);

        // Layer 1: rule classification (< 1ms)
        IntentResult ruleResult = ruleClassifier.classify(message, context);
        if (ruleResult.getConfidence() >= 0.8) {
            recordAndCache(sessionId, cacheKey, ruleResult);
            return ruleResult;
        }

        // Layer 2: LLM classification (100-500ms)
        IntentResult llmResult = llmClassifier.classify(message, context);
        IntentResult finalResult = llmResult.getConfidence() >= 0.5 ? llmResult : ruleResult;

        recordAndCache(sessionId, cacheKey, finalResult);
        return finalResult;
    }

    private void recordAndCache(String sessionId, String cacheKey, IntentResult result) {
        contextTracker.updateContext(sessionId, result);
        cache.put(cacheKey, result);
    }

    private String hashMessage(String message) {
        return Integer.toHexString(message.hashCode());
    }
}
```

### 5.5 RuleIntentClassifier

Keywords and patterns below are English equivalents of the original Chinese examples so this design source stays CJK-free. Production classifiers that must catch Chinese ops phrasing should store those keywords as Unicode escapes.

```java
@Component
public class RuleIntentClassifier implements IntentClassifier {

    private static final List<IntentRule> RULES = List.of(
        rule(IntentType.DIAGNOSE,
            List.of("down", "hung", "crash", "502", "503", "504", "OOM", "full", "high", "abnormal",
                    "error", "alert", "timeout", "crash", "panic", "fatal"),
            List.of("why.*(?:down|error|fail|unreachable)", "troubleshoot.*issue", "analyze.*cause"),
            Map.of(List.of("fix", "resolve"), 0.1)),

        rule(IntentType.CONFIGURE,
            List.of("config", "configuration", "change config", "parameter", "adjust", "set", "tune"),
            List.of("change.*(?:conf|cfg|yml|properties|xml|json)", "set.*parameter"),
            Map.of()),

        rule(IntentType.DEPLOY,
            List.of("deploy", "release", "rollback", "go live", "update version", "restart service"),
            List.of("(?:release|deploy).*version", "rollback.*version"),
            Map.of()),

        rule(IntentType.MONITOR,
            List.of("check", "monitor", "log", "cpu", "memory", "disk", "network", "traffic",
                    "load", "process", "port", "connections"),
            List.of("(?:check|show|view).*(?:status|usage)", "tail.*log"),
            Map.of()),

        rule(IntentType.SECURITY,
            List.of("firewall", "iptables", "permission", "ssh", "key",
                    "certificate", "ssl", "tls", "security", "vulnerability", "CVE"),
            List.of("(?:open|close).*port", "configure.*(?:ssl|certificate|key)"),
            Map.of()),

        rule(IntentType.BACKUP,
            List.of("backup", "restore", "export", "import", "migrate"),
            List.of("backup.*(?:database|file|config)", "restore.*data"),
            Map.of()),

        rule(IntentType.EXPLAIN,
            List.of("what does it mean", "how to understand", "explain", "describe", "what is", "how to"),
            List.of("this command.*(?:mean|does|used for)"),
            Map.of()),

        rule(IntentType.SEARCH,
            List.of("find", "search", "grep", "locate", "look up", "which process", "which file"),
            List.of("(?:find|search).*(?:file|process|port)"),
            Map.of())
    );

    @Override
    public IntentResult classify(String message, ConversationContext context) {
        String lowerMsg = message.toLowerCase();
        IntentResult best = IntentResult.builder()
            .intent(IntentType.UNKNOWN).confidence(0.0).entities(Map.of()).build();

        for (IntentRule rule : RULES) {
            double score = 0.0;

            // Keyword match (max 0.6)
            long hits = rule.getKeywords().stream()
                .filter(lowerMsg::contains).count();
            score += Math.min(0.6, hits * 0.2);

            // Regex match (extra +0.2)
            boolean patternHit = rule.getPatterns().stream()
                .anyMatch(p -> Pattern.matches(".*" + p + ".*", message));
            if (patternHit) score += 0.2;

            // Context boost: +0.1 if a recent intent matches
            if (context.getRecentIntents().stream()
                .anyMatch(h -> h.getIntent() == rule.getIntent())) {
                score += 0.1;
            }

            score = Math.min(1.0, score);

            if (score > best.getConfidence()) {
                best = IntentResult.builder()
                    .intent(rule.getIntent())
                    .confidence(score)
                    .entities(extractEntities(message, rule.getIntent()))
                    .build();
            }
        }
        return best;
    }

    private Map<String, String> extractEntities(String message, IntentType intent) {
        Map<String, String> entities = new HashMap<>();
        // Extract service name
        List<String> services = List.of("nginx", "redis", "mysql", "postgres", "docker",
            "kafka", "rabbitmq", "elasticsearch", "tomcat", "spring");
        services.stream().filter(message.toLowerCase()::contains)
            .forEach(svc -> entities.put("service", svc));
        return entities;
    }

    private static IntentRule rule(IntentType intent, List<String> keywords,
                                   List<String> patterns, Map<List<String>, Double> contextBoost) {
        IntentRule r = new IntentRule();
        r.setIntent(intent);
        r.setKeywords(keywords);
        r.setPatterns(patterns);
        r.setContextBoost(contextBoost);
        return r;
    }
}
```

### 5.6 LLMIntentClassifier

```java
@Component
public class LLMIntentClassifier implements IntentClassifier {
    @Resource
    private ChatModel chatModel;

    private static final String CLASSIFY_PROMPT_TEMPLATE = """
        You are an intent classifier for SSH operations. Analyze the user input and return a JSON intent classification.

        ## Intent types
        - DIAGNOSE: Diagnose a problem (service down, errors, incident troubleshooting)
        - CONFIGURE: Change configuration (edit config files, tune parameters)
        - DEPLOY: Deploy operations (deploy, release, rollback)
        - MONITOR: Monitor or inspect (logs, status, resource usage)
        - SECURITY: Security (firewall, permissions, certificates)
        - BACKUP: Backup and restore
        - EXECUTE: Execute directly (run a command for me)
        - EXPLAIN: Explain (what does this command mean)
        - SEARCH: Search (find a file, inspect a process)
        - CHAT: Casual chat
        - CONTINUE: Continue the previous task
        - UNKNOWN: Cannot determine

        ## Output format (JSON only, no extra text)
        {"intent":"TYPE","confidence":0.0-1.0,"entities":{"key":"value"}}

        ## Examples
        Input: "nginx is returning 502, help me check"
        Output: {"intent":"DIAGNOSE","confidence":0.95,"entities":{"service":"nginx","error":"502"}}

        Input: "help me change redis maxmemory config"
        Output: {"intent":"CONFIGURE","confidence":0.9,"entities":{"service":"redis","config":"maxmemory"}}

        Input: "check server disk usage"
        Output: {"intent":"MONITOR","confidence":0.9,"entities":{"resource":"disk"}}

        Input: "what does this command mean: awk '{print $1}' access.log"
        Output: {"intent":"EXPLAIN","confidence":0.95,"entities":{"command":"awk"}}

        ## Conversation context
        Recent intents: %s

        ## Analyze the following input
        Input: "%s"
        Output:
        """;

    @Override
    public IntentResult classify(String message, ConversationContext context) {
        String recentIntents = context.getRecentIntents().stream()
            .map(h -> h.getIntent().name())
            .collect(Collectors.joining(", "));

        String prompt = String.format(CLASSIFY_PROMPT_TEMPLATE,
            recentIntents.isEmpty() ? "none" : recentIntents, message);

        try {
            String response = chatModel.call(prompt).getResult().getOutput().getContent();
            return parseResponse(response);
        } catch (Exception e) {
            return IntentResult.builder()
                .intent(IntentType.UNKNOWN).confidence(0.0).entities(Map.of()).build();
        }
    }

    private IntentResult parseResponse(String response) {
        try {
            // Extract the JSON portion
            String json = response.replaceAll("(?s).*?(\\{.*}).*", "$1");
            Map<String, Object> parsed = new ObjectMapper().readValue(json, Map.class);

            IntentType intent = IntentType.valueOf(
                String.valueOf(parsed.get("intent")).toUpperCase());
            double confidence = parsed.containsKey("confidence")
                ? Double.parseDouble(String.valueOf(parsed.get("confidence"))) : 0.5;
            Map<String, String> entities = parsed.containsKey("entities")
                ? (Map<String, String>) parsed.get("entities") : Map.of();

            return IntentResult.builder()
                .intent(intent).confidence(confidence)
                .entities(entities).rawResponse(response).build();
        } catch (Exception e) {
            return IntentResult.builder()
                .intent(IntentType.UNKNOWN).confidence(0.0)
                .entities(Map.of()).rawResponse(response).build();
        }
    }
}
```

### 5.7 ContextTracker

```java
@Component
public class ContextTracker {
    private static final int WINDOW_SIZE = 10;
    private final Map<String, ConversationContext> contexts = new ConcurrentHashMap<>();

    public ConversationContext getContext(String sessionId) {
        return contexts.computeIfAbsent(sessionId, id -> ConversationContext.builder()
            .recentIntents(new LinkedList<>())
            .turnCount(0)
            .sessionStartTime(System.currentTimeMillis())
            .build());
    }

    public void updateContext(String sessionId, IntentResult result) {
        ConversationContext ctx = getContext(sessionId);
        ctx.getRecentIntents().addLast(IntentHistoryEntry.builder()
            .intent(result.getIntent())
            .confidence(result.getConfidence())
            .timestamp(System.currentTimeMillis())
            .build());
        if (ctx.getRecentIntents().size() > WINDOW_SIZE) {
            ctx.getRecentIntents().removeFirst();
        }
        ctx.setTurnCount(ctx.getTurnCount() + 1);
        ctx.setLastIntent(result.getIntent());
    }

    public void clear(String sessionId) {
        contexts.remove(sessionId);
    }
}
```

---

## 6. Phase 4: Intent Enhancement — Signal Extraction and Context Injection

### 6.1 Design Notes

Extract structured signals from user input (service names, file paths, error codes), search related context on the target server (service status, config files, logs), and inject it into the prompt so the LLM can decide with more information.

Core idea: **keywords extract information only; they do not decide**. `SignalExtractor` extracts signals, `ContextSearch` looks up context, and the LLM makes the understanding decision.

### 6.2 ExtractedSignals Value Object

```java
@Data
@Builder
public class ExtractedSignals {
    private List<String> serverHosts;
    private List<String> filePaths;
    private List<String> serviceNames;
    private List<String> commandHints;
    private List<String> errorPatterns;
    private List<String> logKeywords;
}
```

### 6.3 SignalExtractor

```java
@Component
public class SignalExtractor {

    private static final List<String> KNOWN_SERVICES = List.of(
        "nginx", "apache", "httpd", "redis", "mysql", "mariadb", "postgres", "postgresql",
        "mongodb", "kafka", "rabbitmq", "docker", "containerd", "kubernetes", "kubelet",
        "jenkins", "gitlab", "elasticsearch", "kibana", "logstash", "prometheus", "grafana",
        "zookeeper", "etcd", "consul", "nacos", "tomcat", "spring", "node", "php-fpm",
        "sshd", "firewalld", "iptables", "crond", "rsyslogd"
    );

    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
        "(?:/[\\w.-]+)+\\.(?:conf|cfg|yml|yaml|properties|xml|json|log|sh|service|ini|cnf)");

    private static final Pattern IP_PATTERN = Pattern.compile(
        "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    public ExtractedSignals extract(String message) {
        String lower = message.toLowerCase();
        return ExtractedSignals.builder()
            .serverHosts(extract(IP_PATTERN, message))
            .filePaths(extract(FILE_PATH_PATTERN, message))
            .serviceNames(KNOWN_SERVICES.stream().filter(lower::contains).collect(Collectors.toList()))
            .commandHints(extractCommandHints(lower))
            .errorPatterns(extractErrorPatterns(message))
            .logKeywords(extractLogKeywords(message))
            .build();
    }

    private List<String> extract(Pattern pattern, String text) {
        List<String> results = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) results.add(matcher.group());
        return results;
    }

    private List<String> extractCommandHints(String lower) {
        List<String> hints = new ArrayList<>();
        List<String> cmds = List.of("systemctl", "service", "journalctl", "tail", "grep",
            "awk", "sed", "find", "curl", "wget", "ping", "telnet", "netstat", "ss",
            "top", "htop", "free", "df", "du", "ps", "kill", "lsof", "iptables",
            "docker", "kubectl", "apt", "yum", "rpm");
        cmds.stream().filter(lower::contains).forEach(hints::add);
        return hints;
    }

    private List<String> extractErrorPatterns(String message) {
        List<String> patterns = new ArrayList<>();
        List<Pattern> errorPatterns = List.of(
            Pattern.compile("(?i)(?:HTTP\\s*)?5\\d{2}"),
            Pattern.compile("(?i)\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d+"),
            Pattern.compile("(?i)(?:error|exception|fatal|panic|oom|segfault)[\\s:]?.{0,50}"),
            Pattern.compile("(?i)connection\\s+(?:refused|timed\\s*out|reset)"),
            Pattern.compile("(?i)permission\\s+denied"),
            Pattern.compile("(?i)no\\s+such\\s+file"),
            Pattern.compile("(?i)disk\\s+(?:full|space)"),
            Pattern.compile("(?i)port\\s+\\d+")
        );
        for (Pattern p : errorPatterns) {
            Matcher m = p.matcher(message);
            while (m.find()) patterns.add(m.group());
        }
        return patterns;
    }

    private List<String> extractLogKeywords(String message) {
        List<String> keywords = new ArrayList<>();
        List<String> logLevels = List.of("error", "warn", "warning", "fatal", "critical",
            "exception", "timeout", "refused", "denied", "failed", "oom");
        String lower = message.toLowerCase();
        logLevels.stream().filter(lower::contains).forEach(keywords::add);
        return keywords;
    }
}
```

### 6.4 ContextSearch

```java
@Component
public class ContextSearch {
    @Resource
    private ISshTerminalService sshTerminalService;

    public SearchContext searchBySignals(String terminalSessionId, ExtractedSignals signals) {
        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            return SearchContext.builder().build();
        }

        SearchContext.SearchContextBuilder builder = SearchContext.builder();

        // 1. Query service status
        if (!signals.getServiceNames().isEmpty()) {
            Map<String, String> statusMap = new LinkedHashMap<>();
            for (String svc : signals.getServiceNames()) {
                String status = safeExec(terminalSessionId,
                    "systemctl is-active " + svc + " 2>/dev/null || " +
                    "service " + svc + " status 2>&1 | head -3");
                statusMap.put(svc, status);
            }
            builder.serviceStatus(statusMap);
        }

        // 2. Read related config files (first 50 lines)
        if (!signals.getFilePaths().isEmpty()) {
            Map<String, String> contentMap = new LinkedHashMap<>();
            for (String path : signals.getFilePaths()) {
                String content = safeExec(terminalSessionId,
                    "head -50 " + path + " 2>/dev/null");
                if (!content.isEmpty() && !content.contains("No such file")) {
                    contentMap.put(path, content);
                }
            }
            builder.fileContents(contentMap);
        }

        // 3. Search recent logs
        if (!signals.getErrorPatterns().isEmpty() || !signals.getLogKeywords().isEmpty()) {
            Map<String, String> logMap = new LinkedHashMap<>();
            for (String svc : signals.getServiceNames()) {
                String logs = safeExec(terminalSessionId,
                    "journalctl -u " + svc + " --no-pager -n 30 --since '1 hour ago' 2>/dev/null || " +
                    "tail -30 /var/log/" + svc + "/*.log 2>/dev/null || " +
                    "tail -30 /var/log/" + svc + ".log 2>/dev/null");
                if (!logs.isEmpty()) logMap.put(svc, logs);
            }
            // If no service was named but error patterns exist, search system logs
            if (logMap.isEmpty() && !signals.getErrorPatterns().isEmpty()) {
                String sysLogs = safeExec(terminalSessionId,
                    "dmesg --time-format iso -T 2>/dev/null | tail -30 || dmesg | tail -30");
                if (!sysLogs.isEmpty()) logMap.put("system", sysLogs);
            }
            builder.recentLogs(logMap);
        }

        return builder.build();
    }

    private String safeExec(String sessionId, String cmd) {
        try {
            String result = sshTerminalService.executeCommand(sessionId, cmd);
            return result != null ? result.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
```

### 6.5 IIntentEnhancerService and IntentEnhancerService

Expose an interface for the case layer:

```java
package com.shellmind.domain.agent.service;

public interface IIntentEnhancerService {
    SearchContext enhance(String terminalSessionId, String userMessage);
}
```

```java
package com.shellmind.domain.agent.service.enhance;

import com.shellmind.domain.agent.service.IIntentEnhancerService;

@Service
public class IntentEnhancerService implements IIntentEnhancerService {
    @Resource private SignalExtractor signalExtractor;
    @Resource private ContextSearch contextSearch;

    @Override
    public SearchContext enhance(String terminalSessionId, String userMessage) {
        // Step 1: signal extraction
        ExtractedSignals signals = signalExtractor.extract(userMessage);

        boolean hasSignals = !signals.getServiceNames().isEmpty()
            || !signals.getFilePaths().isEmpty()
            || !signals.getErrorPatterns().isEmpty()
            || !signals.getServerHosts().isEmpty();

        if (!hasSignals) {
            return SearchContext.builder().build();
        }

        // Step 2: look up server context from signals
        return contextSearch.searchBySignals(terminalSessionId, signals);
    }
}
```

### 6.6 SearchContext Value Object

```java
@Data
@Builder
public class SearchContext {
    @Builder.Default
    private Map<String, String> serviceStatus = Map.of();
    @Builder.Default
    private Map<String, String> fileContents = Map.of();
    @Builder.Default
    private Map<String, String> recentLogs = Map.of();
}
```

---

## 7. Phase 5: Session Persistence

### 7.1 Database Tables

```sql
-- Session metadata
CREATE TABLE `chat_session` (
    `id`            VARCHAR(64)     NOT NULL COMMENT 'Session ID',
    `agent_id`      VARCHAR(64)     NOT NULL COMMENT 'Agent ID',
    `user_id`       VARCHAR(64)     NOT NULL COMMENT 'User ID',
    `title`         VARCHAR(200)    DEFAULT NULL COMMENT 'Session title',
    `created_at`    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `message_count` INT             DEFAULT 0 COMMENT 'Message count',
    PRIMARY KEY (`id`),
    INDEX `idx_user_agent` (`user_id`, `agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Chat session';

-- Chat messages
CREATE TABLE `chat_message` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `session_id`    VARCHAR(64)     NOT NULL COMMENT 'Session ID',
    `role`          VARCHAR(20)     NOT NULL COMMENT 'Role: user/assistant/tool/system',
    `content`       TEXT            COMMENT 'Message content',
    `tool_name`     VARCHAR(100)    DEFAULT NULL COMMENT 'Tool name',
    `tool_call_id`  VARCHAR(100)    DEFAULT NULL COMMENT 'Tool call ID',
    `priority`      VARCHAR(20)     DEFAULT 'MEDIUM' COMMENT 'Priority: CRITICAL/HIGH/MEDIUM/LOW',
    `token_count`   INT             DEFAULT 0 COMMENT 'Estimated token count',
    `created_at`    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_session_time` (`session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Chat message';

-- Milestone events
CREATE TABLE `chat_milestone` (
    `id`            BIGINT          NOT NULL AUTO_INCREMENT,
    `session_id`    VARCHAR(64)     NOT NULL COMMENT 'Session ID',
    `type`          VARCHAR(30)     NOT NULL COMMENT 'Type: TASK_CHANGE/ERROR/DECISION/...',
    `content`       TEXT            COMMENT 'Content summary',
    `created_at`    TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_session_time` (`session_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Chat milestone';
```

### 7.2 Gateway Interface

```java
public interface IChatHistoryGateway {
    void saveMessage(String sessionId, ChatMessageEntity message);
    List<ChatMessageEntity> getRecentMessages(String sessionId, int limit);
    List<ChatMessageEntity> getMessagesWithBudget(String sessionId, int tokenBudget);
    void saveMilestone(String sessionId, MilestoneVO milestoneVO);
    List<MilestoneVO> getRecentMilestones(String sessionId, int limit);
}
```

---

## 8. Implementation Plan

### 8.1 Phased Priority

| Phase | Scope | Depends on | Estimate |
|------|------|------|-----------|
| **Phase 1** | Dynamic prompt construction | None | 2 days |
| **Phase 2** | Context memory management | Phase 1 | 4 days |
| **Phase 5** | Session persistence | Phase 2 | 3 days |
| **Phase 3** | Intent recognition | Phase 1 | 3 days |
| **Phase 4** | Intent enhancement | Phase 3 | 3 days |

### 8.2 Suggested Rollout Order

```
Phase 1 (dynamic prompt)
    ↓
Phase 2 (context memory)
    ↓
Phase 5 (session persistence)
    ↓
Phase 3 (intent recognition)
    ↓
Phase 4 (intent enhancement)
```

Rationale:

1. Phase 1 is the smallest change with the most direct payoff, and it is the foundation for everything else
2. Phase 2 addresses the core pain (context overflow); Phase 5 is a necessary complement
3. Phases 3 and 4 are experience enhancements and should stack after the foundation is stable

### 8.3 Key Files to Change

| File | Change |
|------|---------|
| `IPromptService.java` / `PromptService.java` | **[Phase 1]** Unified domain service for prompt and context construction |
| `DynamicPromptBuilder.java` | Low-level component that assembles the dynamic prompt |
| `MilestoneTracker.java` | Detect and store milestone events |
| `IChatContextService.java` / `ChatContextService.java` | **[Phase 2]** Context management domain service wrapping Providers and Reducers |
| `IIntentService.java` / `IntentService.java` | **[Phase 3]** Intent classification domain service wrapping the two-layer classifiers |
| `IIntentEnhancerService.java` / `IntentEnhancerService.java` | **[Phase 4]** Intent enhancement domain service wrapping signal extraction and search |
| `AiCallNode.java` | **[Phase 1]** Inject `IPromptService` and dynamically inject environment and intent context |
| `AIAgentReActServiceCase.java` | Call domain services (`IIntentService`, `IIntentEnhancerService`) at the chatStream entry for recognition and enhancement |
| `DefaultReActFactory.java` | Add intent result and search-context fields on DynamicContext |
| `ChatService.java` | Integrate session persistence |
| `SshExecuteAdkTool.java` | Push tool results to MilestoneTracker and ToolResultProvider |
| `application-dev.yml` | Feature flags for intent recognition and context management |

---

## 9. Key Adaptation Differences vs ShellMind Frontend

| Dimension | ShellMind frontend | ShellMind server adaptation |
|------|----------|-------------|
| Runtime | Tauri frontend process | Spring Boot backend, multi-user concurrent |
| LLM calls | Direct HTTP API | Spring AI → ADK bridge |
| Context sources | Editor files, cursor, tabs | SSH terminal state, command history, service status |
| Signal extraction | Code file paths, symbol names | Server addresses, file paths, service names, error codes |
| Session storage | Tauri filesystem (local JSON) | MySQL + Redis (server-side persistence) |
| Concurrency | Single user, single process | Multi-user, multi-thread (ConcurrentHashMap + Redis) |
| Intent types | code_edit / debug / refactor | DIAGNOSE / CONFIGURE / DEPLOY / MONITOR |
| Provider mapping | FileProvider (editor files) | TerminalStateProvider (terminal state, system info) |
| Classifier layers | Three (rules → model → LLM) | Two (rules → LLM); skip the middle layer on the backend to cut latency |
| Cache | In-memory Map | Caffeine local cache + Redis distributed cache |
