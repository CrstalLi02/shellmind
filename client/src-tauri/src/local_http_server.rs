//! Local HTTP Server — axum server embedded in Tauri
//!
//! Exposes HTTP APIs for Spring Boot (LocalExecuteAdkTool) to run local commands.
//! Runs inside the Tauri process and shares the same PTY/shell execution path.
//!
//! Port: 17890 by default, overridable via SHELLMIND_LOCAL_PORT
//!
//! API:
//!   POST /exec        — one-shot command execution
//!   POST /stream      — streaming command execution (SSE)
//!   GET  /shell-info  — shell/platform info
//!   GET  /sessions    — list active PTY sessions
//!   POST /kill        — kill a streaming command

use std::net::SocketAddr;
use std::sync::OnceLock;
use axum::{extract::State, response::{sse::{Event, KeepAlive, Sse}, IntoResponse, Json}, routing::{get, post}, Router};
use serde::{Deserialize, Serialize};

use tokio::sync::mpsc;
use futures::stream::Stream;

use crate::shell_exec::{self, ShellResult, StreamEvent};

/// HTTP server port
fn server_port() -> u16 {
    std::env::var("SHELLMIND_LOCAL_PORT")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(17890)
}

/// Server state
#[derive(Clone)]
struct ServerState {
    app_handle: tauri::AppHandle,
}

/// /exec request body
#[derive(Debug, Deserialize)]
struct ExecRequest {
    command: String,
    #[serde(default)]
    cwd: Option<String>,
    #[serde(default = "default_timeout")]
    timeout_ms: u64,
    #[serde(default)]
    auto_background: bool,
}

fn default_timeout() -> u64 {
    30000
}

/// /stream request body
#[derive(Debug, Deserialize)]
struct StreamRequest {
    command: String,
    #[serde(default)]
    cwd: Option<String>,
    session_id: String,
}

/// /kill request body
#[derive(Debug, Deserialize)]
struct KillRequest {
    session_id: String,
}

/// Start the local HTTP server (called from Tauri setup)
pub fn start_local_server(app_handle: tauri::AppHandle) {
    let port = server_port();
    let addr: SocketAddr = ([127, 0, 0, 1], port).into();

    let state = ServerState {
        app_handle: app_handle.clone(),
    };

    let app = Router::new()
        .route("/exec", post(handle_exec))
        .route("/stream", post(handle_stream))
        .route("/kill", post(handle_kill))
        .route("/shell-info", get(handle_shell_info))
        .route("/sessions", get(handle_sessions))
        .route("/health", get(handle_health))
        .with_state(state);

    tauri::async_runtime::spawn(async move {
        let listener = tokio::net::TcpListener::bind(addr)
            .await
            .expect("Failed to bind local HTTP server");
        log::info!("Local HTTP server listening on http://{}", addr);
        axum::serve(listener, app)
            .await
            .expect("Local HTTP server error");
    });
}

/// POST /exec — one-shot command execution
async fn handle_exec(
    State(state): State<ServerState>,
    Json(req): Json<ExecRequest>,
) -> impl IntoResponse {
    let command = req.command.clone();
    let cwd = req.cwd.clone();
    let timeout_ms = req.timeout_ms;
    let auto_background = req.auto_background;

    let result = tokio::task::spawn_blocking(move || {
        shell_exec::execute_shell_internal(&command, cwd.as_deref(), timeout_ms, auto_background)
    })
    .await;

    match result {
        Ok(Ok(shell_result)) => Json(serde_json::json!(shell_result)),
        Ok(Err(e)) => Json(serde_json::json!({
            "error": e,
            "success": false,
        })),
        Err(e) => Json(serde_json::json!({
            "error": format!("Task join error: {}", e),
            "success": false,
        })),
    }
}

/// POST /stream — streaming command execution (SSE)
async fn handle_stream(
    State(state): State<ServerState>,
    Json(req): Json<StreamRequest>,
) -> Sse<impl Stream<Item = Result<Event, axum::Error>>> {
    let (tx, mut rx) = mpsc::channel::<StreamEvent>(100);
    let session_id = req.session_id.clone();
    let command = req.command.clone();
    let cwd = req.cwd.clone();
    let _app_handle = state.app_handle.clone();

    // Run the command on a background thread and send events over the channel
    tokio::task::spawn_blocking(move || {
        let sid = session_id.clone();

        // Run as a one-shot command first, then emit stdout/stderr as separate lines
        let result = shell_exec::execute_shell_internal(
            &command,
            cwd.as_deref(),
            120000, // streaming default: 2-minute timeout
            false,
        );

        match result {
            Ok(shell_result) => {
                if !shell_result.stdout.is_empty() {
                    let _ = tx.blocking_send(StreamEvent {
                        session_id: sid.clone(),
                        kind: "stdout".into(),
                        data: shell_result.stdout,
                        exit_code: None,
                        duration_ms: None,
                    });
                }
                if !shell_result.stderr.is_empty() {
                    let _ = tx.blocking_send(StreamEvent {
                        session_id: sid.clone(),
                        kind: "stderr".into(),
                        data: shell_result.stderr,
                        exit_code: None,
                        duration_ms: None,
                    });
                }
                let _ = tx.blocking_send(StreamEvent {
                    session_id: sid,
                    kind: "done".into(),
                    data: String::new(),
                    exit_code: Some(shell_result.exit_code),
                    duration_ms: Some(shell_result.duration_ms),
                });
            }
            Err(e) => {
                let _ = tx.blocking_send(StreamEvent {
                    session_id: sid,
                    kind: "error".into(),
                    data: e,
                    exit_code: None,
                    duration_ms: None,
                });
            }
        }
    });

    // Convert the channel into an SSE stream
    let stream = async_stream::stream! {
        while let Some(event) = rx.recv().await {
            let json = serde_json::to_string(&event).unwrap_or_default();
            yield Ok(Event::default().event(event.kind).data(json));
        }
    };

    Sse::new(stream).keep_alive(KeepAlive::default())
}

/// POST /kill — kill a streaming command
async fn handle_kill(
    State(_state): State<ServerState>,
    Json(req): Json<KillRequest>,
) -> impl IntoResponse {
    match shell_exec::kill_stream_shell(req.session_id) {
        Ok(()) => Json(serde_json::json!({"success": true})),
        Err(e) => Json(serde_json::json!({"success": false, "error": e})),
    }
}

/// GET /shell-info — shell/platform info
async fn handle_shell_info() -> impl IntoResponse {
    Json(shell_exec::get_shell_info())
}

/// GET /sessions — list active PTY sessions
async fn handle_sessions() -> impl IntoResponse {
    let sessions = crate::local_pty::list_local_ptys().await;
    Json(serde_json::json!({"sessions": sessions}))
}

/// GET /health — health check
async fn handle_health() -> impl IntoResponse {
    Json(serde_json::json!({
        "status": "ok",
        "service": "shellmind-local-server",
        "version": "1.0.0",
    }))
}

/// Return the server port (for the frontend)
#[tauri::command]
pub fn get_local_server_port() -> u16 {
    server_port()
}

/// Return the server URL
static SERVER_URL: OnceLock<String> = OnceLock::new();

pub fn get_server_url() -> &'static str {
    SERVER_URL.get_or_init(|| format!("http://127.0.0.1:{}", server_port()))
}
