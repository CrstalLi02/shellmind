# Architecture

## Overview

```
┌──────────────────────── desktop client/ (Tauri process) ────────────────────────┐
│  React UI (src/)          Rust core (src-tauri/)                                  │
│  · chat / files / terminal · agent_runtime: spawn and watch the bundled jar     │
│  · SSE parse api/agent.ts  · local_http_server: HTTP API for local commands     │
│                            · local_pty / shell_exec: local terminal + intercept │
│                            · shellmind-cli: CLI / TUI client                    │
└───────────────┬───────────────────────────────▲─────────────────────────────────┘
                │ HTTP + SSE  /api/v1/*         │ local command callback
┌───────────────▼───────────────────────────────┴─────────────────────────────────┐
│                         server server/ (Spring Boot)                              │
│  trigger → case (use cases / ReAct) → domain (ports) ← infrastructure (adapters)│
└───────────────┬─────────────────────────────────────────────────────────────────┘
                │ JSch                      │ OpenAI-compatible API
            remote hosts                    LLM providers
```

A desktop build bundles the server jar and a JRE. `agent_runtime` starts it with the `local` profile (H2). You can also point the app at a separately deployed backend (`prod` + MySQL).

## Server: hexagonal modular monolith

The server is a single deployable. The domain sits in the middle and does not depend on external tech. ADK, Spring AI, JSch, MyBatis, local processes, and SSE are adapters that implement domain ports.

```
            driving side                                         driven side
  ┌───────────────────────────┐                          ┌──────────────────────────────┐
  │ trigger                   │      ┌──────────────┐    │ infrastructure               │
  │ · HTTP /api/v1/**         │ ───▶ │ case         │    │ · AdkAgentRuntime (ADK)      │
  │ · SseClientChannel        │      │ application  │    │ · SpringAiLlmClient          │
  └───────────────────────────┘      └──────┬───────┘    │ · JdkProcessRunner           │
                                            ▼            │ · SSH ports (JSch)           │
                                    ┌──────────────┐     │ · repositories (MyBatis)     │
                                    │ domain       │ ◀── │ · thin ADK tool adapters     │
                                    │ model + ports│     └──────────────────────────────┘
                                    └──────────────┘
```

| Module | Role | Allowed dependencies |
|--------|------|----------------------|
| `shellmind-server-trigger` | Driving adapter: HTTP, SSE | case, api, types; domain ports (`ClientChannel` only) |
| `shellmind-server-api` | Public DTOs and contracts | — |
| `shellmind-server-case` | Application services (one per use case), ReAct engine, DTO ↔ domain mapping | domain, api, types |
| `shellmind-server-domain` | Domain model, services, ports (`adapter/port`), repository interfaces | types |
| `shellmind-server-infrastructure` | Driven adapters, agent armory, ADK tool adapters | domain, types |
| `shellmind-server-app` | Boot, config, agent YAML, MyBatis, H2 schema, architecture tests | all modules |
| `shellmind-server-types` | Shared enums, exceptions, strategy-tree framework | — |

### Bounded contexts (`com.shellmind.domain.*`)

| Context | Contents |
|---------|----------|
| `agent` | Runs (`AgentRunEntity`), run-context registry, sub-agents, intent, budgets, command dispatch, tools |
| `conversation` | Session history, context assembly and compression |
| `memory` | Core memory, long-term memory, milestones |
| `coding` | Local edits, patch audit (`CodePatchEntity` state machine), build checks, repo index |
| `policy` | Permission levels, circuit breaker, output redaction |
| `llm` | User model configs, LLM client port |
| `ssh` | SSH connections, remote terminal and files |
| `shared` | Shared kernel: `RunContext`, tool-progress port |

Dependencies between contexts are one-way (acyclic), enforced by architecture tests.

### Ports

| Port | Context | Implementation |
|------|---------|----------------|
| `AgentRuntime` | agent | `infrastructure.agent.runtime.AdkAgentRuntime` |
| `ClientChannel` | agent | `SseClientChannel`; `DiscardingClientChannel` when there is no client |
| `LocalCommandExecutor` | agent | `LocalProcessCommandExecutor` / `RemoteCommandExecutor` |
| `LlmClient` / `ModelEndpointProbe` | llm | `SpringAiLlmClient` / `RestClientModelEndpointProbe` |
| `ProcessRunner` | coding | `JdkProcessRunner` |
| `ISshSessionPort` / `ITerminalSessionPort` / `ISshFilePort` | ssh | `infrastructure.adapter.port.*` (JSch) |
| `ToolProgressNotifier` | shared | `ClientToolProgressNotifier` |
| `I*Repository` | each context | `infrastructure.adapter.repository.*` (MyBatis) |

### Run context

Each run builds an immutable `RunContext` (session, user, agent, run id, read-only flag, workspace, bound terminal) and registers it with `AgentRunRegistry` keyed by session id. ADK tool adapters read the session id from `ToolContext` and look up `RunContext` — no ThreadLocal, so child threads and sub-agents keep the right context.

### Architecture constraints

`ArchitectureTest` (ArchUnit) runs on `mvn package` / `mvn verify`:

- domain must not depend on ADK, GenAI, Spring AI, OpenAI SDK, RxJava, Spring Web/HTTP, JSch, MyBatis, Servlet, or process creation
- domain must not depend on case, trigger, or infrastructure
- case must not depend on Web, ADK, Spring AI, trigger, or infrastructure
- trigger enters the system only through case (except domain ports) and must not depend on infrastructure
- infrastructure must not depend on case or trigger
- no cycles among domain bounded contexts

## Chat request path

`POST /api/v1/chat_stream` (SSE) → `AIAgentReActServiceCase`, then the strategy tree:

```
RootNode → TaskBreakdownNode → AiCallNode ⇄ ToolCallNode
                                   │            │
                                   └──→ LoopDecisionNode ──→ UserFeedbackNode (end)
```

- **RootNode** — init session, restore last 50 messages
- **TaskBreakdownNode** — split complex tasks (rules + a lighter model)
- **AiCallNode** — register run context, inject dynamic prompt (env, memory, milestones), stream via `AgentRuntime`
- **ToolCallNode** — run tools, permission confirm, redact/truncate, persist, extract long-term memory
- **LoopDecisionNode** — step/tool budgets and diminishing returns
- **UserFeedbackNode** — summary of results and changes

## Agent assembly

On startup, `agent/code-agent.yml` is assembled (`infrastructure.agent.armory`):

```
RootNode → AiApiNode → ChatModelNode → AgentNode → AgentWorkflowNode → RunnerNode
```

`AgentNode` registers tools on the main agent: local/remote commands, local/remote files, compile/test checks, sub-agents. ADK adapters in `infrastructure.agent.tool` only adapt parameters and resolve run context; logic lives in domain tool services.

### Sub-agents

| Tool | Role |
|------|------|
| `launchSubAgent` | One worker: EXPLORE (read-only) / VERIFICATION (no file writes) / GENERAL (full tools) |
| `planAndDispatchSubAgents` | Planner JSON → parse/validate (allowlist, deps exist, acyclic) → DAG dispatch, max concurrency 4 |

Sub-agents run in child sessions. `RunContext` is derived with `forChildSession`. EXPLORE / VERIFICATION are forced read-only. Nested planning is forbidden. Implementation: `domain/agent/service/subagent/`.

## Memory and context

| Mechanism | What it stores | Table |
|-----------|----------------|-------|
| Core memory | Distilled rules, preferences, corrections | `core_memory` |
| Long-term memory | Preferences, OS, software versions, incident notes; keyword recall | `long_term_memory` |
| Milestone | Task switches, errors, other events | `chat_milestone` |
| Session history | Messages and tool results | `chat_message` |

`ContextProvider` implementations under `context/provider` merge into `PromptContextVO` by order; `DynamicPromptBuilder` renders them into the prompt.

## Security boundaries

- Remote commands: `PermissionGuard` / `ToolExecutionPolicyGuard`; high-risk actions need UI confirmation; the agent only uses the terminal bound to the current session
- Code edits: each write is a `CodePatchEntity` that can be approved, rejected, or rolled back
- Local commands: `shell_exec::validate_command` on both foreground and background paths
- Tool output: `SecretRedactor` before context and persistence
- SSH passwords: AES-GCM; key from `SHELLMIND_SECRET_KEY`
