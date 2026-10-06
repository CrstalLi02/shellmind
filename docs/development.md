# Development

## Requirements

| Tool | Version | Use |
|------|---------|-----|
| JDK | 17 | Server |
| Maven | 3.9+ | Server build |
| Node.js | 22 | Frontend build |
| Rust | stable | Desktop (Tauri) and shellmind-cli |

## First run

```bash
make setup     # copy .env.example → .env, then npm ci
# edit .env and set at least SHELLMIND_AI_API_KEY
make dev       # build if needed, then start backend + desktop
```

`scripts/dev.sh` loads the root `.env`. The backend uses the `local` profile (H2 at `~/.shellmind/data`); MySQL is not required.

Flags: `--no-client` backend only, `--no-server` frontend only, `--rebuild` force jar rebuild, `--clean` wipe caches and reinstall deps.

## Configuration

All environment config is injected from env vars. Template: [.env.example](../.env.example). Never put real secrets in committed files.

| Variable | Purpose |
|----------|---------|
| `SHELLMIND_AI_BASE_URL` / `_API_KEY` / `_MODEL` | Main agent model (OpenAI-compatible) |
| `SHELLMIND_INTENT_AI_*` | Intent model (optional); skip LLM classification if the key is empty |
| `SHELLMIND_DB_URL` / `_USERNAME` / `_PASSWORD` | MySQL for `dev` / `prod` |
| `SHELLMIND_SECRET_KEY` | Encryption key for stored SSH passwords |

Users can also set models in **Settings → Models** in the desktop app; those override env defaults.

Agent definition: `server/shellmind-server-app/src/main/resources/agent/code-agent.yml` (`reasoning-effort` controls thinking strength). More layouts: [docs/examples/agent](examples/agent/).

## Tests

```bash
make test-server    # server unit tests (domain / case / infrastructure) + architecture tests
make test-desktop   # Rust unit tests
make check          # CI-equivalent: mvn verify + frontend build + cargo check
```

Put new domain tests in that module’s `src/test/java`. Prefer in-memory fakes (`LongTermMemoryServiceTest`, `SubAgentDispatchServiceTest`) over a Spring context. Domain dependencies are ports — implement a stub (`AgentRuntime` in `SubAgentManagerTest`).

Other tests under `shellmind-server-app` need a real model or external services. The build only runs architecture tests in the `architecture` package.

## Conventions

- Java root package is `com.shellmind`. Layers and dependency direction: [Architecture](architecture.md#server-hexagonal-modular-monolith), enforced by `ArchitectureTest`
- New HTTP APIs: controllers in `trigger`, call application services in `case`, request/response types in `api`; map DTO ↔ domain in the application service
- External capabilities (models, processes, network, SSH, frameworks) get a port in that context’s `adapter/port` and an implementation in `infrastructure`. Persistence uses `adapter/repository`
- New agent tools: domain tool service takes `RunContext`; ADK adapter in `infrastructure.agent.tool` resolves context from `ToolContext`
- State changes live on the aggregate (`CodePatchEntity.approve()`), not as field writes in services
- Schema changes: update `deploy/mysql/init/01-schema.sql` (fresh DB), `server/shellmind-server-app/src/main/resources/schema/shellmind-h2.sql` (`local` profile), and add `deploy/mysql/migrations/NNN-*.sql` (existing DBs)
- Formatting follows root `.editorconfig` and `.gitattributes`
- Run `make check` before you commit
