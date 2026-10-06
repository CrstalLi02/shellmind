# ShellMind Client

Tauri 2 + React 19 + Vite desktop app, plus `shellmind-cli` (CLI / TUI). See the [root README](../README.md).

| Path | Role |
|------|------|
| `src/` | React UI: chat, file workspace, terminal, settings |
| `src-tauri/src/` | Rust: bundled backend daemon (`agent_runtime`), local-command HTTP (`local_http_server`), PTY, command exec and intercept (`shell_exec`) |
| `src-tauri/src/bin/shellmind-cli.rs` + `src-tauri/src/cli/` | CLI client |
| `scripts/prepare-runtime.mjs` | Download a bundled JRE at package time |
| `resources/agent/` | Bundled server jar and JRE (build output, not committed) |

```bash
npm ci
npm run tauri dev       # frontend; start the backend separately, or use make dev from the repo root
npm run tauri:build     # package (builds ../server and bundles jar + JRE)
```
