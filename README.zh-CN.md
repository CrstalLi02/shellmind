<div align="center">

# 🧠 ShellMind

**会写代码、也会管服务器的 AI 助手 —— 一个桌面应用，搞定本地编码与远程运维。**

[![CI](https://github.com/CrstalLi02/shellmind/actions/workflows/ci.yml/badge.svg)](https://github.com/CrstalLi02/shellmind/actions/workflows/ci.yml)
![Java 17](https://img.shields.io/badge/Java-17-orange)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F)
![Google ADK](https://img.shields.io/badge/Google%20ADK-1.10-4285F4)
![Tauri 2](https://img.shields.io/badge/Tauri-2-24C8DB)
![React 19](https://img.shields.io/badge/React-19-61DAFB)
![Rust](https://img.shields.io/badge/Rust-stable-B7410E)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

[English](README.md) · **简体中文**

</div>

---

用一句话告诉 ShellMind 你想做什么（中文、英文都行）：

> “帮我看看线上那台机器为什么 CPU 飙高，顺便把本地项目里的超时配置改成 30 秒，跑一下测试。”

它会自己拆解任务、派出子代理并行探查、在 SSH 终端里排查问题、改你的本地代码、编译并跑测试——
**每一个危险命令都会先问你，每一处代码改动都能一键回滚。**

## ✨ 为什么是 ShellMind

| | |
|---|---|
| 🤖 **真正的智能体，而不是聊天框** | 基于 ReAct 循环与 Google ADK，22 个内置工具覆盖本地/远程命令、文件读写、搜索、编译、单测、Lint 与项目校验，自己规划、自己执行、自己验证。 |
| 🌲 **多智能体协作** | 复杂任务由规划器拆成带依赖的 DAG，子代理按依赖**并发执行**；探查 / 验证类子代理强制只读。 |
| 🖥️ **本地 + 远程一体化** | 同一个对话里既能改本机项目，也能操作绑定的 SSH 终端与远程文件（SFTP），不用在终端、IDE 和聊天窗口之间来回切换。 |
| 🛡️ **安全是默认值** | 命令分级拦截 + 高危操作弹窗确认 + 断路器；工具输出先脱敏再进入上下文与数据库；SSH 密码 AES-GCM 加密存储；智能体只能操作当前会话绑定的终端。 |
| ↩️ **每次改动都可审计、可回滚** | 智能体写入的每个文件都会生成带 diff 的修改记录，可批准、拒绝或整次运行一键回滚。 |
| 🧠 **越用越懂你** | 核心记忆、长期记忆、里程碑三层记忆体系，自动提炼你的偏好、环境与排查结论，按需召回。 |
| 🌏 **中英双语** | 意图识别、任务模式判断、安全启发式同时理解中文与英文输入，智能体用你使用的语言回复。 |
| 🔌 **任意模型** | 兼容 OpenAI 协议的模型都能接入，桌面端可为每个用户单独配置模型与密钥。 |
| 📦 **开箱即用** | 桌面安装包内置后端与 JRE、自带 H2 数据库，双击即用；也可以 Docker Compose 一键部署为团队共享服务。 |

## 🏗️ 架构亮点

```
┌──────────────────────── 桌面端（Tauri 2 · Rust + React 19）─────────────────────────┐
│  对话 / 文件工作区 / 多标签终端 / SFTP        内置后端守护 · 本地 PTY · 命令拦截 · CLI/TUI │
└───────────────────────────────┬──────────────────────────────▲───────────────────────┘
                     HTTP + SSE │                              │ 本地命令回调
┌───────────────────────────────▼──────────────────────────────┴───────────────────────┐
│                       服务端（Spring Boot 4 · 六边形架构）                             │
│  trigger ──▶ case（用例 · ReAct 引擎）──▶ domain（8 个限界上下文 + 端口）◀── infrastructure │
└───────────────┬─────────────────────────────────────────────┬──────────────────────────┘
                │ JSch                                        │ OpenAI 兼容协议
            远程服务器                                       大模型服务
```

- **六边形架构 + 模块化单体**：领域层零框架依赖，ADK、Spring AI、JSch、MyBatis、本地进程全部以适配器实现领域端口——换智能体框架、换存储，领域逻辑一行不动。
- **架构即代码**：7 条 ArchUnit 规则随每次构建执行（领域纯净、依赖只向内、上下文无环），架构不会随时间腐化。
- **显式运行上下文**：不依赖 ThreadLocal，子代理跨线程并发也不会串会话。
- **聚合根守护业务规则**：代码修改的状态机（已应用 → 已批准 / 已拒绝 / 已回滚）收敛在聚合内，非法状态转换直接拒绝。
- **全链路可测**：领域单元测试 + 架构约束测试 + 中英关键词测试 + Rust 单测，CI 一条命令跑完。

👉 详见 [架构说明](docs/architecture.md)（英文）

## 🚀 快速开始

前置依赖：JDK 17、Maven 3.9+、Node.js 22、Rust（stable）。

```bash
git clone https://github.com/CrstalLi02/shellmind.git && cd shellmind
make setup        # 生成 .env 并安装前端依赖；随后在 .env 中填写模型 API Key
make dev          # 启动后端（内置 H2，无需 MySQL）+ 桌面端
```

只启动后端：`make dev-server`。Windows 使用 `scripts\dev.bat`。

### 打包与部署

| 命令 | 说明 |
|------|------|
| `make build-desktop` | 打包桌面安装包（内置后端 jar 与 JRE） |
| `make up` / `make down` | Docker Compose 启动 / 停止 MySQL + 后端 |
| `make test` | 后端单元测试 + 架构测试 + Rust 单元测试 |
| `make check` | 与 CI 一致的完整检查 |

| 运行模式 | 数据库 | 场景 |
|----------|--------|------|
| `local` | H2 文件库 `~/.shellmind/data` | 桌面端内置后端、本地开发 |
| `dev` | MySQL | 联调共享数据库 |
| `prod` | MySQL | 容器部署（`deploy/docker-compose.yml`） |

全部配置项见 [.env.example](.env.example)，配置文件中不包含任何密钥。

## 🧰 技术栈

**服务端**：Java 17 · Spring Boot 4 · Google ADK · Spring AI · MyBatis · H2 / MySQL · JSch · ArchUnit
**桌面端**：Tauri 2 · Rust · React 19 · TypeScript · Vite · xterm.js · Monaco
**工程化**：Maven 多模块 · GitHub Actions · Docker Compose · Makefile

## 📁 仓库结构

```
shellmind/
├── server/        # 服务端：六边形架构的模块化单体（Maven 多模块）
├── client/        # 桌面端：Tauri 2 + React 19；内含 shellmind-cli 命令行
├── deploy/        # docker-compose、MySQL 初始化脚本与增量迁移
├── docs/          # 架构、开发、部署文档与示例智能体配置（英文）
├── scripts/       # 一键启动脚本
└── Makefile       # 常用命令入口（make help）
```

## 🙏 致谢

服务端最初基于 [小傅哥（fuzhengwei）](https://github.com/fuzhengwei) 的 AI Agent 教学项目演进而来，感谢原作者的开源工作。

## 📄 License

[Apache License 2.0](LICENSE)
