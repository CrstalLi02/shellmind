<div align="center">

# 🧠 ShellMind

**The AI agent that codes on your laptop *and* runs your servers — in one desktop app.**

[![CI](https://github.com/CrstalLi02/shellmind/actions/workflows/ci.yml/badge.svg)](https://github.com/CrstalLi02/shellmind/actions/workflows/ci.yml)
![Java 17](https://img.shields.io/badge/Java-17-orange)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F)
![Google ADK](https://img.shields.io/badge/Google%20ADK-1.10-4285F4)
![Tauri 2](https://img.shields.io/badge/Tauri-2-24C8DB)
![React 19](https://img.shields.io/badge/React-19-61DAFB)
![Rust](https://img.shields.io/badge/Rust-stable-B7410E)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

**English** · [简体中文](README.zh-CN.md)

</div>

---

Tell ShellMind what you want:

> *"Find out why CPU is spiking on the prod box, then bump the timeout in my local project to 30s and run the tests."*

It breaks the job down, sends sub-agents to investigate in parallel, troubleshoots over your SSH terminal, edits your local code, compiles, and runs the tests —
**asking before every risky command, and keeping every file change one click away from rollback.**

## ✨ Why ShellMind

| | |
|---|---|
| 🤖 **A real agent, not a chat box** | A ReAct loop on Google ADK with 22 built-in tools — local & remote commands, file read/write, search, compile, unit tests, lint and project verification. It plans, acts, and checks its own work. |
| 🌲 **Multi-agent orchestration** | Complex requests become a dependency DAG that sub-agents execute **concurrently**. Explore and verification agents are enforced read-only. |
| 🖥️ **Local + remote in one place** | Edit your local project and drive a bound SSH terminal and remote files (SFTP) from the same conversation — no more juggling terminal, IDE and chat. |
| 🛡️ **Safe by default** | Tiered command interception, confirmation prompts for high-risk actions, a circuit breaker, secret redaction before anything reaches context or the database, AES-GCM–encrypted SSH credentials, and agents can only touch the terminal bound to their session. |
| ↩️ **Every change is audited and reversible** | Each file the agent writes becomes a patch record with a diff — approve it, reject it, or roll back an entire run. |
| 🧠 **Gets to know you** | Three memory tiers (core, long-term, milestones) distill your preferences, environment and past fixes, and recall them when relevant. |
| 🌏 **Bilingual** | Chinese and English input are both first-class: intent detection, task modes and safety heuristics understand both, and the agent replies in your language. |
| 🔌 **Bring any model** | Works with any OpenAI-compatible endpoint; each user can configure their own model and key in the app. |
| 📦 **Zero-setup** | The desktop installer bundles the backend, a JRE and an embedded H2 database. Or deploy it for your team with one Docker Compose command. |

## 🏗️ Architecture highlights

```
┌──────────────────────── Desktop (Tauri 2 · Rust + React 19) ─────────────────────────┐
│  Chat · file workspace · tabbed terminals · SFTP     bundled backend · PTY · CLI/TUI  │
└────────────────────────────────┬──────────────────────────────▲───────────────────────┘
                      HTTP + SSE │                              │ local command callback
┌────────────────────────────────▼──────────────────────────────┴───────────────────────┐
│                     Server (Spring Boot 4 · hexagonal architecture)                     │
│  trigger ──▶ case (use cases · ReAct engine) ──▶ domain (8 contexts + ports) ◀── infra  │
└────────────────┬──────────────────────────────────────────────┬────────────────────────┘
                 │ JSch                                         │ OpenAI-compatible API
           Remote servers                                     LLM providers
```

- **Hexagonal modular monolith** — the domain has zero framework dependencies. ADK, Spring AI, JSch, MyBatis and local processes are all adapters behind domain ports, so you can swap the agent framework or the database without touching business logic.
- **Architecture as code** — 7 ArchUnit rules run on every build (pure domain, inward-only dependencies, acyclic bounded contexts), so the design can't quietly rot.
- **Explicit run context** — no ThreadLocals; concurrent sub-agents on other threads never leak across sessions.
- **Aggregates guard the rules** — the patch lifecycle (applied → approved / rejected / rolled back) lives inside the aggregate; illegal transitions are refused.
- **Tested end to end** — domain unit tests, architecture tests, bilingual keyword tests and Rust tests all run in CI with one command.

👉 Read the full [architecture guide](docs/architecture.md).

## 🚀 Quick start

Prerequisites: **JDK 17**, **Maven 3.9+**, **Node.js 22**, **Rust (stable)**.

```bash
git clone https://github.com/CrstalLi02/shellmind.git && cd shellmind
make setup        # create .env and install frontend deps; then put your model API key in .env
make dev          # start the backend (embedded H2, no MySQL needed) + the desktop app
```

Backend only: `make dev-server`. On Windows use `scripts\dev.bat`.

### Build & deploy

| Command | What it does |
|---------|--------------|
| `make build-desktop` | Desktop installer (bundled server jar + JRE) |
| `make up` / `make down` | Start / stop MySQL + server via Docker Compose |
| `make test` | Server unit + architecture tests, Rust unit tests |
| `make check` | Full check, same as CI |

| Profile | Database | Use it for |
|---------|----------|------------|
| `local` | H2 file DB at `~/.shellmind/data` | Bundled desktop backend, local dev |
| `dev` | MySQL | Shared integration database |
| `prod` | MySQL | Container deploy (`deploy/docker-compose.yml`) |

Every setting lives in [.env.example](.env.example) — no secrets are committed to config files.

## 🧰 Tech stack

**Server:** Java 17 · Spring Boot 4 · Google ADK · Spring AI · MyBatis · H2 / MySQL · JSch · ArchUnit
**Desktop:** Tauri 2 · Rust · React 19 · TypeScript · Vite · xterm.js · Monaco
**Tooling:** Maven multi-module · GitHub Actions · Docker Compose · Makefile

## 📁 Repository layout

```
shellmind/
├── server/        # hexagonal modular monolith (Maven multi-module)
├── client/        # Tauri 2 + React 19 desktop app, plus shellmind-cli
├── deploy/        # docker compose, MySQL init scripts and migrations
├── docs/          # architecture, development, deployment, example agents
├── scripts/       # one-command startup scripts
└── Makefile       # common commands (make help)
```

## 📚 Documentation

- [Architecture](docs/architecture.md) — hexagonal layers, bounded contexts & ports, request path, sub-agents, memory
- [Development](docs/development.md) — local setup, configuration, tests, conventions
- [Deployment](docs/deployment.md) — containers, database migrations, secret management
- [Example agent configs](docs/examples/agent/)

## 🙏 Acknowledgements

The server grew out of the open-source AI agent teaching project by [fuzhengwei (小傅哥)](https://github.com/fuzhengwei). Many thanks for the original work.

## 📄 License

[Apache License 2.0](LICENSE)
