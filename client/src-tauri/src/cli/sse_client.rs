//! SSE client
//!
//! Talks to shellmind-server's `/api/v1/chat_stream` endpoint
//! and parses a JSON event stream (non-standard SSE: one JSON object per line).

use crate::_cli_app::{AppEvent, ReActEvent};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::time::Duration;
use tokio::sync::mpsc;

/// SSE client configuration
const REQUEST_TIMEOUT: Duration = Duration::from_secs(120);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

/// Create-session request (camelCase field names match CreateSessionRequestDTO)
#[derive(Debug, Serialize)]
struct CreateSessionRequest {
    #[serde(rename = "agentId")]
    agent_id: String,
    #[serde(rename = "userId")]
    user_id: String,
}

/// Create-session response
#[derive(Debug, Deserialize)]
struct ApiResponse<T> {
    code: String,
    #[serde(default)]
    info: String,
    #[serde(default)]
    data: Option<T>,
}

#[derive(Debug, Default, Deserialize)]
struct CreateSessionData {
    #[serde(rename = "sessionId")]
    session_id: String,
}

/// Chat request (camelCase field names match ChatRequestDTO)
#[derive(Debug, Serialize)]
struct ChatStreamRequest {
    #[serde(rename = "agentId")]
    agent_id: String,
    #[serde(rename = "userId")]
    user_id: String,
    #[serde(rename = "sessionId")]
    session_id: String,
    message: String,
    /// User model config ID. If omitted, the server falls back to the code-agent.yml default (k3@8777),
    /// which typically returns 401, so the CLI must send the user model configured in the GUI
    #[serde(rename = "modelId", skip_serializing_if = "Option::is_none")]
    model_id: Option<i64>,
    #[serde(rename = "terminalSessionId", skip_serializing_if = "Option::is_none")]
    terminal_session_id: Option<String>,
    #[serde(rename = "projectContext", skip_serializing_if = "Option::is_none")]
    project_context: Option<ProjectContext>,
}

#[derive(Debug, Serialize)]
pub struct ProjectContext {
    #[serde(rename = "name")]
    name: String,
    #[serde(rename = "rootPath")]
    root_path: String,
}

/// Local command result payload (camelCase field names match CommandResult)
///
/// Note: the backend status field is an enum (SUCCESS/ERROR/TIMEOUT/CANCELLED/DISCONNECTED);
/// Jackson can deserialize String → Enum.
#[derive(Debug, Serialize)]
struct CommandResult {
    #[serde(rename = "cmdId")]
    cmd_id: String,
    #[serde(rename = "sessionId")]
    session_id: String,
    #[serde(rename = "status")]
    status_str: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    output: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
    #[serde(rename = "exitCode", skip_serializing_if = "Option::is_none")]
    exit_code: Option<i32>,
    #[serde(rename = "durationMs", skip_serializing_if = "Option::is_none")]
    duration_ms: Option<u64>,
    success: bool,
}

pub struct SseClient {
    client: Client,
    server_url: String,
    token: Option<String>,
    model_id: Option<i64>,
    event_tx: mpsc::UnboundedSender<AppEvent>,
}

impl SseClient {
    pub fn new(
        server_url: String,
        token: Option<String>,
        model_id: Option<i64>,
        event_tx: mpsc::UnboundedSender<AppEvent>,
    ) -> Self {
        let client = Client::builder()
            .timeout(REQUEST_TIMEOUT)
            .connect_timeout(CONNECT_TIMEOUT)
            .build()
            .expect("Failed to create HTTP client");

        Self { client, server_url, token, model_id, event_tx }
    }

    /// Attach an Authorization header (local runtime token check)
    fn authorized(&self, req: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
        match &self.token {
            Some(token) => req.bearer_auth(token),
            None => req,
        }
    }

    /// Create a session
    pub async fn create_session(&self, agent_id: &str, user_id: &str) -> Result<String, String> {
        let url = format!("{}/api/v1/create_session", self.server_url);
        let req = CreateSessionRequest {
            agent_id: agent_id.to_string(),
            user_id: user_id.to_string(),
        };

        let resp = self
            .authorized(self.client.post(&url))
            .json(&req)
            .send()
            .await
            .map_err(|e| format!("Failed to connect to the server: {} (start the ShellMind desktop app first)", e))?;

        if resp.status() == reqwest::StatusCode::UNAUTHORIZED {
            return Err("Local runtime token check failed (401). Start the ShellMind desktop app, or pass --token.".to_string());
        }

        let body: ApiResponse<CreateSessionData> = resp
            .json()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        if body.code == "0000" && body.data.is_some() {
            Ok(body.data.unwrap().session_id)
        } else {
            Err(format!("Failed to create session: {}", body.info))
        }
    }

    /// Send a message and consume the SSE stream
    pub async fn chat_stream(
        &self,
        agent_id: &str,
        user_id: &str,
        session_id: &str,
        message: &str,
        project_context: Option<ProjectContext>,
    ) -> Result<(), String> {
        let url = format!("{}/api/v1/chat_stream", self.server_url);
        let req = ChatStreamRequest {
            agent_id: agent_id.to_string(),
            user_id: user_id.to_string(),
            session_id: session_id.to_string(),
            message: message.to_string(),
            model_id: self.model_id,
            terminal_session_id: None,
            project_context,
        };

        let resp = self
            .authorized(self.client.post(&url))
            .json(&req)
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            return Err(format!("HTTP {}", resp.status()));
        }

        // Read the streaming response
        let mut stream = resp.bytes_stream();

        use futures::StreamExt;
        let mut buffer = String::new();

        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|e| format!("Failed to read stream: {}", e))?;
            buffer += &String::from_utf8_lossy(&chunk);

            // Split on newlines and parse JSON events
            let lines: Vec<String> = buffer.split('\n').map(|s| s.to_string()).collect();
            // The last fragment may be incomplete; keep it
            buffer = lines.last().cloned().unwrap_or_default();

            for line in &lines[..lines.len().saturating_sub(1)] {
                let trimmed = line.trim();
                if trimmed.is_empty() {
                    continue;
                }

                match serde_json::from_str::<ReActEvent>(trimmed) {
                    Ok(event) => {
                        // Ignore heartbeat events
                        if event.event == "heartbeat" {
                            continue;
                        }

                        // execute_local_command → run locally in CLI mode
                        if event.event == "execute_local_command" {
                            self.handle_local_command(&event, session_id);
                            continue;
                        }

                        // Forward the event to the UI
                        let _ = self.event_tx.send(AppEvent::SseEvent(event));
                    }
                    Err(_) => {
                        // Ignore non-JSON lines (HTTP chunk boundaries)
                    }
                }
            }
        }

        // Handle any leftover data
        if !buffer.trim().is_empty() {
            if let Ok(event) = serde_json::from_str::<ReActEvent>(buffer.trim()) {
                if event.event != "heartbeat" {
                    let _ = self.event_tx.send(AppEvent::SseEvent(event));
                }
            }
        }

        let _ = self.event_tx.send(AppEvent::Done);
        Ok(())
    }

    /// Handle local command execution (CLI mode)
    fn handle_local_command(&self, event: &ReActEvent, session_id: &str) {
        let cmd_id = event.cmd_id.clone().unwrap_or_default();
        let command = event.command.clone().unwrap_or_default();
        let cwd = event.cwd.clone();

        if cmd_id.is_empty() || command.is_empty() {
            return;
        }

        // Notify the UI
        let _ = self.event_tx.send(AppEvent::SseEvent(event.clone()));

        // Run the local command on a background thread
        let client = self.client.clone();
        let server_url = self.server_url.clone();
        let token = self.token.clone();
        let sid = session_id.to_string();

        tokio::spawn(async move {
            let start = std::time::Instant::now();

            // Execute with std::process::Command (no Tauri dependency)
            let result = tokio::task::spawn_blocking(move || {
                execute_local_command_internal(
                    &command,
                    cwd.as_deref(),
                    60000,
                )
            })
            .await;

            let duration_ms = start.elapsed().as_millis() as u64;

            let cmd_result = match result {
                Ok(Ok(shell_result)) => CommandResult {
                    cmd_id: cmd_id.clone(),
                    session_id: sid.clone(),
                    status_str: if shell_result.success { "SUCCESS".to_string() } else { "ERROR".to_string() },
                    output: Some(format!("{}{}", shell_result.stdout,
                        if shell_result.stderr.is_empty() { String::new() } else { format!("\n{}", shell_result.stderr) })),
                    error: None,
                    exit_code: Some(shell_result.exit_code),
                    duration_ms: Some(duration_ms),
                    success: shell_result.success,
                },
                Ok(Err(e)) => CommandResult {
                    cmd_id: cmd_id.clone(),
                    session_id: sid.clone(),
                    status_str: "ERROR".to_string(),
                    output: None,
                    error: Some(e),
                    exit_code: None,
                    duration_ms: Some(duration_ms),
                    success: false,
                },
                Err(e) => CommandResult {
                    cmd_id: cmd_id.clone(),
                    session_id: sid.clone(),
                    status_str: "ERROR".to_string(),
                    output: None,
                    error: Some(format!("Task error: {}", e)),
                    exit_code: None,
                    duration_ms: Some(duration_ms),
                    success: false,
                },
            };

            // Send the result back to the server
            let url = format!("{}/api/v1/tool_result", server_url);
            let req = client.post(&url);
            let req = if let Some(token) = &token { req.bearer_auth(token) } else { req };
            let _ = req.json(&cmd_result).send().await;
        });
    }
}

/// Resolve the model config ID to use:
/// 1. Explicit --model-id argument
/// 2. First user model from GET /api/v1/model/list (same model the GUI chat uses)
/// Returns None on failure (the server falls back to a default that is usually unavailable).
pub async fn resolve_model_id(
    server_url: &str,
    token: Option<&str>,
    explicit: Option<&str>,
) -> Option<i64> {
    if let Some(value) = explicit {
        return value.trim().parse::<i64>().ok();
    }

    let client = Client::builder()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(10))
        .build()
        .ok()?;
    let mut req = client.get(format!("{}/api/v1/model/list", server_url));
    if let Some(token) = token {
        req = req.bearer_auth(token);
    }
    let resp = req.send().await.ok()?;
    let body: ApiResponse<Vec<serde_json::Value>> = resp.json().await.ok()?;
    if body.code != "0000" {
        return None;
    }
    let list = body.data?;
    list.first()?.get("id")?.as_i64()
}

/// Build project context
pub fn build_project_context(workdir: &Option<String>) -> Option<ProjectContext> {
    workdir.as_ref().and_then(|dir| {
        let path = std::path::PathBuf::from(dir);
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_default();
        if name.is_empty() {
            None
        } else {
            Some(ProjectContext {
                name,
                root_path: dir.clone(),
            })
        }
    })
}

/// Local command execution (pure Rust, no Tauri)
/// 
/// Used in CLI mode to run execute_local_command events from the server.
/// Uses std::process::Command, matching the core logic in shell_exec.
fn execute_local_command_internal(
    command: &str,
    cwd: Option<&str>,
    _timeout_ms: u64,
) -> Result<LocalCommandResult, String> {
    use std::process::{Command, Stdio};
    use std::time::Instant;

    let start = Instant::now();

    // Resolve the shell
    let shell = std::env::var("SHELL")
        .unwrap_or_else(|_| if cfg!(target_os = "macos") { "/bin/zsh".to_string() } else { "/bin/bash".to_string() });

    let mut cmd = Command::new(&shell);
    cmd.arg("-lic")
        .arg(command)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    if let Some(dir) = cwd {
        let path = std::path::Path::new(dir);
        if path.exists() && path.is_dir() {
            cmd.current_dir(path);
        }
    }

    let output = cmd.output()
        .map_err(|e| format!("Failed to execute: {}", e))?;

    let stdout = strip_ansi_codes(&String::from_utf8_lossy(&output.stdout));
    let stderr = strip_ansi_codes(&String::from_utf8_lossy(&output.stderr));
    let exit_code = output.status.code().unwrap_or(-1);

    Ok(LocalCommandResult {
        stdout,
        stderr,
        exit_code,
        success: exit_code == 0,
        _duration_ms: start.elapsed().as_millis() as u64,
    })
}

/// Local command execution result
struct LocalCommandResult {
    stdout: String,
    stderr: String,
    exit_code: i32,
    success: bool,
    _duration_ms: u64,
}

/// Strip ANSI escape sequences (color codes, etc.)
fn strip_ansi_codes(s: &str) -> String {
    // Match ESC[...m ANSI sequences
    let mut result = String::with_capacity(s.len());
    let mut chars = s.chars().peekable();
    
    while let Some(ch) = chars.next() {
        if ch == '\x1b' {
            // On ESC, check whether this is a CSI sequence ESC[...m
            if chars.peek() == Some(&'[') {
                chars.next(); // skip '['
                // Skip parameters until 'm'
                while let Some(&c) = chars.peek() {
                    chars.next();
                    if c == 'm' {
                        break;
                    }
                }
                continue;
            }
        }
        result.push(ch);
    }
    
    result
}
