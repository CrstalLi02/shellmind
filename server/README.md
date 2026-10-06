# ShellMind Server

Spring Boot 4 + Google ADK 1.10 + Spring AI 2.0, DDD multi-module server. See the [root README](../README.md) and [architecture](../docs/architecture.md).

## Modules

| Module | Responsibility |
|--------|----------------|
| `shellmind-server-app` | Boot, `application-*.yml`, agent YAML (`agent/code-agent.yml`), MyBatis mappers, H2 schema |
| `shellmind-server-trigger` | HTTP / SSE, prefix `/api/v1` |
| `shellmind-server-api` | DTOs and API contracts |
| `shellmind-server-case` | ReAct engine (Root → TaskBreakdown → AiCall ⇄ ToolCall → LoopDecision → UserFeedback) |
| `shellmind-server-domain` | Agent assembly, sub-agents, intent, context, memory, SSH, coding tools |
| `shellmind-server-infrastructure` | Repositories, DAO/PO, encryption |
| `shellmind-server-types` | Shared enums, exceptions, constants |

## HTTP APIs

| Controller | Prefix | Purpose |
|------------|--------|---------|
| `AgentServiceController` | `/api/v1/` | Agent list, `chat_stream`, run history |
| `SshAgentController` | `/api/v1/ssh/agent` | SSH-scoped chat (legacy; merged into AgentService) |
| `SshConnectionController` | `/api/v1/ssh` | SSH connection CRUD |
| `SshTerminalController` | `/api/v1/ssh/terminal` | Remote terminal |
| `SshFileController` | `/api/v1/ssh/file` | Remote files |
| `ModelConfigController` | `/api/v1/model` | User model configs |
| `CodePatchController` | `/api/v1/patch` | Code-change audit and rollback |
| `PermissionResolveController` | `/api/v1/permission` | High-risk action confirm |
| `RuntimeController` | `/api/v1/runtime` | Runtime status |
| `FileParseController` | `/api/v1/file` | File parse |

## Build and run

```bash
mvn -B verify                                   # compile + unit tests
java -jar shellmind-server-app/target/shellmind-server.jar --spring.profiles.active=local
```

Profiles, env vars, and databases: [Development](../docs/development.md) and [Deployment](../docs/deployment.md).
