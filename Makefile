# Common ShellMind commands. Run `make help` for all targets.
SHELL := /bin/bash
.DEFAULT_GOAL := help

SERVER_DIR := server
CLIENT_DIR := client
COMPOSE    := docker compose --env-file .env -f deploy/docker-compose.yml

.PHONY: help
help: ## Show this help
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# ---------- Development ----------
.PHONY: setup
setup: ## Install frontend deps and create .env
	@test -f .env || (cp .env.example .env && echo "Created .env — fill in your secrets")
	cd $(CLIENT_DIR) && npm ci

.PHONY: dev
dev: ## Start backend + desktop (scripts/dev.sh)
	bash scripts/dev.sh

.PHONY: dev-server
dev-server: ## Start backend only (local profile, H2)
	bash scripts/dev.sh --no-client

# ---------- Build and test ----------
.PHONY: build
build: build-server build-web ## Build server jar and frontend assets

.PHONY: build-server
build-server: ## Build server jar
	cd $(SERVER_DIR) && mvn -B -DskipTests package

.PHONY: build-web
build-web: ## Typecheck and build frontend
	cd $(CLIENT_DIR) && npm run build

.PHONY: build-desktop
build-desktop: ## Package desktop app (bundled server jar + JRE)
	cd $(CLIENT_DIR) && npm run tauri:build

.PHONY: test
test: test-server test-desktop ## Run all tests

.PHONY: test-server
test-server: ## Server unit tests
	cd $(SERVER_DIR) && mvn -B test

.PHONY: test-desktop
test-desktop: ## Rust unit tests
	cd $(CLIENT_DIR)/src-tauri && cargo test --lib

.PHONY: check
check: ## CI-equivalent: server tests + frontend build + Rust check
	cd $(SERVER_DIR) && mvn -B verify
	cd $(CLIENT_DIR) && npm run build
	cd $(CLIENT_DIR)/src-tauri && cargo check --all-targets

# ---------- Deploy ----------
.PHONY: up
up: ## Start MySQL + server with docker compose
	$(COMPOSE) up -d --build

.PHONY: down
down: ## Stop containers (keep volumes)
	$(COMPOSE) down

.PHONY: logs
logs: ## Tail server logs
	$(COMPOSE) logs -f server

# ---------- Clean ----------
.PHONY: clean
clean: ## Remove build artifacts
	cd $(SERVER_DIR) && mvn -q clean
	rm -rf $(CLIENT_DIR)/dist $(CLIENT_DIR)/src-tauri/target
