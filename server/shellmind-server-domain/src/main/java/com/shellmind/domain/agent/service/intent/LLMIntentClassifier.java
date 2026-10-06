package com.shellmind.domain.agent.service.intent;

import com.shellmind.domain.agent.model.valobj.intent.ConversationContextVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentResultVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.shellmind.domain.llm.adapter.port.LlmClient;
import com.shellmind.domain.llm.model.valobj.LlmRequest;
import com.shellmind.domain.llm.model.valobj.LlmTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
public class LLMIntentClassifier implements IIntentClassifier {

    /** Intent classification needs more deterministic results */
    private static final double FALLBACK_TEMPERATURE = 0.1;

    @Resource
    private LlmClient llmClient;

    private static final String CLASSIFY_PROMPT_TEMPLATE = """
        You are the intent-classification system for a coding and operations assistant. Analyze the user input and return a JSON intent classification.

        ## Intent types
        - DIAGNOSE: Diagnose a problem (service down, errors, exception troubleshooting)
        - CONFIGURE: Configuration change (edit config files, tune parameters)
        - DEPLOY: Deploy operation (deploy, release, rollback)
        - MONITOR: Monitoring lookup (logs, status, resource usage)
        - SECURITY: Security (firewall, permissions, certificates)
        - BACKUP: Backup and restore (back up data, restore data)
        - EXECUTE: Execute a task (write code, change code, fix a bug, complete a feature, actually modify files as requested)
        - EXPLAIN: Explanation (what does this command mean)
        - SEARCH: Search (find a file, look up a process)
        - CHAT: Small talk
        - CONTINUE: Continue the previous task
        - UNKNOWN: Cannot determine

        ## Output format (JSON only, nothing else)
        {"intent":"TYPE","confidence":0.0-1.0,"entities":{"key":"value"}}

        ## Examples
        Input: "nginx is returning 502, please take a look"
        Output: {"intent":"DIAGNOSE","confidence":0.95,"entities":{"service":"nginx","error":"502"}}

        Input: "please change redis maxmemory config"
        Output: {"intent":"CONFIGURE","confidence":0.9,"entities":{"service":"redis","config":"maxmemory"}}

        Input: "check disk usage on the server"
        Output: {"intent":"MONITOR","confidence":0.9,"entities":{"resource":"disk"}}

        Input: "what does this command awk '{print $1}' access.log mean"
        Output: {"intent":"EXPLAIN","confidence":0.95,"entities":{"command":"awk"}}

        ## Classification rules (must follow)
        1. Instructions that contain verbs such as handle / improve / fix / modify / implement / execute / write / refactor / optimize, however short, are requests to actually do work → classify as EXECUTE or the matching execution intent; never classify as CHAT.
        2. When the current input contains pronouns (these / those / the above / it / as before), you must use "Recent conversation" to resolve the referent before choosing intent. Example: the previous turn listed risks and the user says "start improving them" → EXECUTE (implement the list above).
        3. Pure greetings, chit-chat, or talk unrelated to the project → CHAT.

        ## Conversation context
        Recent intents: %s
        %s
        ## Analyze the following input
        Input: "%s"
        Output:
        """;

    @Override
    public IntentResultVO classify(String message, ConversationContextVO context) {
        return classify(message, context, null);
    }

    @Override
    public IntentResultVO classify(String message, ConversationContextVO context, Long modelId) {
        return classify(message, context, modelId, null);
    }

    @Override
    public IntentResultVO classify(String message, ConversationContextVO context, Long modelId,
                                   java.util.List<String> recentUserMessages) {
        String recentIntents = "";
        if (context != null && context.getRecentIntents() != null) {
            recentIntents = context.getRecentIntents().stream()
                .map(h -> h.getIntent().name())
                .collect(Collectors.joining(", "));
        }

        // Recent conversation: lets the LLM resolve instructions like "start improving" / "handle as above"
        String recentDialogue = "";
        if (recentUserMessages != null && !recentUserMessages.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int maxChars = 3000; // keep the classification prompt bounded
            for (String msg : recentUserMessages) {
                if (msg == null || msg.isBlank()) continue;
                String snippet = msg.length() > 400 ? msg.substring(0, 400) + "…(truncated)" : msg;
                sb.append("User: ").append(snippet).append("\n");
                if (sb.length() > maxChars) break;
            }
            if (sb.length() > 0) {
                recentDialogue = "\n## Recent conversation (previous user messages; the current input may refer to them)\n" + sb;
            }
        }

        String prompt = String.format(CLASSIFY_PROMPT_TEMPLATE,
            recentIntents.isEmpty() ? "none" : recentIntents,
            recentDialogue,
            message);

        try {
            Optional<String> response = llmClient.complete(
                    new LlmRequest(LlmTarget.auxiliary(modelId), null, prompt, FALLBACK_TEMPERATURE));
            if (response.isEmpty()) {
                log.warn("Intent classification has no available model (user has not configured one), returning UNKNOWN");
                return IntentResultVO.builder()
                    .intent(IntentTypeEnumVO.UNKNOWN).confidence(0.0).entities(Map.of()).build();
            }
            return parseResponse(response.get());
        } catch (Exception e) {
            log.warn("Intent classification LLM call failed: {}", e.getMessage());
            return IntentResultVO.builder()
                .intent(IntentTypeEnumVO.UNKNOWN).confidence(0.0).entities(Map.of()).build();
        }
    }

    @SuppressWarnings("unchecked")
    private IntentResultVO parseResponse(String response) {
        try {
            // Extract the JSON portion
            String json = response.replaceAll("(?s).*?(\\{.*}).*", "$1");
            Map<String, Object> parsed = new ObjectMapper().readValue(json, Map.class);

            IntentTypeEnumVO intent = IntentTypeEnumVO.valueOf(
                String.valueOf(parsed.get("intent")).toUpperCase());
            double confidence = parsed.containsKey("confidence")
                ? Double.parseDouble(String.valueOf(parsed.get("confidence"))) : 0.5;
            Map<String, String> entities = parsed.containsKey("entities")
                ? (Map<String, String>) parsed.get("entities") : Map.of();

            return IntentResultVO.builder()
                .intent(intent).confidence(confidence)
                .entities(entities).rawResponse(response).build();
        } catch (Exception e) {
            return IntentResultVO.builder()
                .intent(IntentTypeEnumVO.UNKNOWN).confidence(0.0)
                .entities(Map.of()).rawResponse(response).build();
        }
    }
}
