#!/usr/bin/env bash
# ==============================================================================
# ShellMind one-command startup (macOS / Linux)
# Flow: clear caches -> check server -> detect/build jar -> start backend -> start desktop
# Usage: bash scripts/dev.sh [--clean] [--rebuild] [--no-server] [--no-client]
#   --clean      Deep clean (remove node_modules/.vite, tsbuildinfo, then npm install)
#   --rebuild    Force rebuild the server jar
#   --no-server  Skip backend (use if it is already running elsewhere)
#   --no-client  Skip desktop (backend only)
# ==============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLIENT_DIR="$ROOT_DIR/client"
SERVER_DIR="$ROOT_DIR/server"

# Load root .env if present so the backend can read SHELLMIND_* settings
if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT_DIR/.env"
  set +a
fi
PORT=8091
LOG_FILE="${TMPDIR:-/tmp}/shellmind-server-dev.log"
PID_FILE="${TMPDIR:-/tmp}/shellmind-server-dev.pid"

CLEAN=0; REBUILD=0; START_SERVER=1; START_CLIENT=1
for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=1 ;;
    --rebuild) REBUILD=1 ;;
    --no-server) START_SERVER=0 ;;
    --no-client) START_CLIENT=0 ;;
    *) echo "Unknown argument: $arg" ;;
  esac
done

GREEN='\033[32m'; YELLOW='\033[33m'; RED='\033[31m'; CYAN='\033[36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC} $1"; }
ok()    { echo -e "${GREEN}[ OK ]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
fail()  { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }

echo "=============================================="
echo "  ShellMind one-command start (dev)"
echo "  Client dir: $CLIENT_DIR"
echo "=============================================="

# ---------- 1. Cache cleanup ----------
info "Clearing frontend cache..."
rm -rf "$CLIENT_DIR/node_modules/.vite"
rm -f  "$CLIENT_DIR/tsconfig.tsbuildinfo" "$CLIENT_DIR/src-tauri/tsconfig.tsbuildinfo" 2>/dev/null || true
# Kill leftover processes on 8091 (backend) and 5173 (Vite)
for CLEAN_PORT in 8091 5173; do
  if command -v lsof >/dev/null 2>&1; then
    OLD_PIDS=$(lsof -ti tcp:"$CLEAN_PORT" 2>/dev/null || true)
    if [ -n "$OLD_PIDS" ]; then
      warn "Port $CLEAN_PORT is in use (PID: $OLD_PIDS); stopping old process..."
      echo "$OLD_PIDS" | xargs kill -9 2>/dev/null || true
      sleep 1
    fi
  fi
done
ok "Cache cleared"

if [ "$CLEAN" -eq 1 ]; then
  info "Deep clean: reinstalling dependencies (npm install)..."
  (cd "$CLIENT_DIR" && npm install)
  ok "Dependencies installed"
fi

# ---------- 2. Check server ----------
[ -f "$SERVER_DIR/pom.xml" ] || fail "Server project not found: $SERVER_DIR (is the repo complete?)"
ok "Found server project: $SERVER_DIR"

# ---------- 3. Tooling ----------
command -v node >/dev/null 2>&1 || fail "node not found. Install Node.js (>=18) first."
command -v npm  >/dev/null 2>&1 || fail "npm not found"

# ---------- 4. Java runtime (prefer system Java 17+, else bundled JRE) ----------
BUNDLED_JRE="$CLIENT_DIR/resources/agent/runtime"
JAVA_BIN=""
if command -v java >/dev/null 2>&1; then
  JAVA_BIN="java"
elif [ -x "$BUNDLED_JRE/bin/java" ]; then
  JAVA_BIN="$BUNDLED_JRE/bin/java"
  export PATH="$BUNDLED_JRE/bin:$PATH"
  ok "Using bundled JRE: $BUNDLED_JRE"
else
  fail "Java not found (need 17+).
  Install Temurin 17: https://adoptium.net/
  Or run npm run agent:runtime in the client project to download a bundled JRE."
fi

JAVA_VER_OUTPUT="$("$JAVA_BIN" -version 2>&1 | head -1)"
if echo "$JAVA_VER_OUTPUT" | grep -q '"1\.'; then
  fail "Java is too old ($JAVA_VER_OUTPUT). Spring Boot 3.4 needs Java 17+."
fi
ok "Java: $JAVA_VER_OUTPUT"

# ---------- 5. Detect whether the jar exists / needs rebuild ----------
JAR_FILE="$SERVER_DIR/shellmind-server-app/target/shellmind-server.jar"
NEED_BUILD=0

if [ "$REBUILD" -eq 1 ]; then
  NEED_BUILD=1
  info "--rebuild set; forcing jar rebuild"
elif [ ! -f "$JAR_FILE" ]; then
  NEED_BUILD=1
  info "Jar not found; a build is required"
else
  NEWER_SRC=$(find "$SERVER_DIR" \
    \( -name "*.java" -o -name "pom.xml" -o -name "*.yml" \) \
    -newer "$JAR_FILE" -not -path "*/target/*" -print -quit 2>/dev/null || true)
  if [ -n "$NEWER_SRC" ]; then
    NEED_BUILD=1
    info "Jar is stale (newer source: ${NEWER_SRC#$SERVER_DIR/}); rebuilding"
  else
    ok "Jar is up to date: $JAR_FILE"
  fi
fi

if [ "$NEED_BUILD" -eq 1 ]; then
  if ! command -v mvn >/dev/null 2>&1; then
    if [ -f "$JAR_FILE" ]; then
      warn "mvn not found; skipping build and using existing jar: $JAR_FILE"
    else
      fail "mvn not found and no jar is present. Install Maven: https://maven.apache.org/"
    fi
  else
    info "Building server jar (mvn -DskipTests package; first run can be slow)..."
    (cd "$SERVER_DIR" && mvn -q -DskipTests package) || fail "Server build failed; check shellmind-server"
    ok "Server build complete: $JAR_FILE"
  fi
fi

# ---------- 6. Start backend ----------
if [ "$START_SERVER" -eq 1 ]; then
  info "Starting backend (profile=local, port=$PORT). Spring Boot usually takes 40-60s..."
  info "Log file: $LOG_FILE"
  (cd "$SERVER_DIR" && nohup "$JAVA_BIN" -jar "$JAR_FILE" \
      --spring.profiles.active=local --server.port="$PORT" \
      > "$LOG_FILE" 2>&1 & echo $! > "$PID_FILE")

  READY=0
  for i in $(seq 1 120); do
    if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
      exec 3>&- 3<&- 2>/dev/null || true
      READY=1; break
    fi
    if ! kill -0 "$(cat "$PID_FILE" 2>/dev/null)" 2>/dev/null; then
      echo ""
      fail "Backend process exited. Recent logs:
$(tail -30 "$LOG_FILE")"
    fi
    sleep 1
  done

  [ "$READY" -eq 1 ] || fail "Timed out waiting for backend (120s). See logs: $LOG_FILE"
  ok "Backend ready: http://localhost:$PORT (PID: $(cat "$PID_FILE"))"
else
  warn "Skipping backend (--no-server)"
fi

# ---------- 7. Start desktop (Tauri) ----------
if [ ! -d "$CLIENT_DIR/node_modules" ]; then
  info "node_modules missing; installing dependencies..."
  (cd "$CLIENT_DIR" && npm install)
  ok "Dependencies installed"
fi

command -v cargo >/dev/null 2>&1 || fail "cargo (Rust) not found; the desktop app needs a Rust toolchain.
  Install: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh"

if [ "$START_CLIENT" -eq 1 ]; then
  info "Starting desktop app (Tauri dev). The ShellMind window should open shortly..."
  cleanup() {
    SERVER_PID="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
      info "Stopping backend (PID: $SERVER_PID)..."
      kill "$SERVER_PID" 2>/dev/null || true
    fi
  }
  trap cleanup EXIT INT TERM
  cd "$CLIENT_DIR"
  npm run tauri dev
  ok "Desktop app exited"
else
  ok "Done (--no-client; desktop app was not started)"
fi
