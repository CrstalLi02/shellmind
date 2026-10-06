//! App state
//!
//! Core CLI-mode state: messages, session info, input buffer, etc.

use clap::Parser;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;

/// CLI arguments
#[derive(Parser, Debug)]
#[command(name = "shellmind", version, about = "ShellMind — AI-powered terminal ops assistant")]
pub struct CliArgs {
    /// Server URL (default http://localhost:8091)
    #[arg(long, default_value = "http://localhost:8091")]
    pub server: String,

    /// Local runtime token (defaults to ~/.shellmind/runtime.json)
    #[arg(long)]
    pub token: Option<String>,

    /// Model config ID (defaults to the first user model on the server)
    #[arg(long)]
    pub model_id: Option<String>,

    /// Agent ID (default 200000 = unifiedAgent)
    #[arg(long, default_value = "200000")]
    pub agent_id: String,

    /// User ID
    #[arg(long, default_value = "cli-user")]
    pub user_id: String,

    /// One-shot message (exit after completion; non-interactive)
    #[arg(short, long)]
    pub message: Option<String>,

    /// Working directory (passed to the server as project context)
    #[arg(short, long)]
    pub workdir: Option<String>,
}

// ── SSE event types (aligned with frontend ReActEvent) ──

/// Backend SSE event (camelCase field names match ReActEventDTO)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReActEvent {
    pub event: String,
    #[serde(default)]
    pub content: Option<String>,
    #[serde(rename = "toolCallId", default)]
    #[allow(dead_code)]
    pub tool_call_id: Option<String>,
    #[serde(rename = "toolName", default)]
    pub tool_name: Option<String>,
    #[serde(rename = "fullText", default)]
    pub full_text: Option<String>,
    #[serde(default)]
    pub args: Option<String>,
    #[serde(default)]
    pub summary: Option<String>,
    #[serde(default)]
    pub status: Option<String>,
    #[serde(rename = "cmdId", default)]
    pub cmd_id: Option<String>,
    #[serde(default)]
    pub command: Option<String>,
    #[serde(default)]
    pub cwd: Option<String>,
    #[serde(rename = "timeoutMs", default)]
    pub timeout_ms: Option<u64>,
    #[serde(rename = "stepInfo", default)]
    pub step_info: Option<StepInfo>,
    #[serde(rename = "changeSummary", default)]
    pub change_summary: Option<ChangeSummary>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StepInfo {
    #[serde(rename = "currentStep")]
    pub current_step: u32,
    #[serde(rename = "maxSteps")]
    pub max_steps: u32,
    #[serde(rename = "shouldContinue")]
    pub should_continue: bool,
    #[serde(rename = "totalToolCalls")]
    pub total_tool_calls: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChangeSummary {
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub topic: Option<String>,
    #[serde(default)]
    pub created: Vec<ChangeFile>,
    #[serde(default)]
    pub modified: Vec<ChangeFile>,
    #[serde(default)]
    pub deleted: Vec<ChangeFile>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChangeFile {
    pub path: String,
    pub kind: String,
    #[serde(rename = "addedLines", default)]
    pub added_lines: i32,
    #[serde(rename = "removedLines", default)]
    pub removed_lines: i32,
}

// ── Connection resolution ──

/// Resolved server connection info
#[derive(Debug, Clone)]
pub struct Connection {
    pub server_url: String,
    pub token: Option<String>,
}

/// Resolve connection info, in order:
/// 1. SHELLMIND_SERVER / SHELLMIND_TOKEN environment variables
/// 2. ~/.shellmind/runtime.json (written when the Tauri app starts the embedded server)
/// 3. --server / --token CLI flags (default http://localhost:8091)
pub fn resolve_connection(args: &CliArgs) -> Connection {
    let env_server = std::env::var("SHELLMIND_SERVER").ok().filter(|s| !s.trim().is_empty());
    let env_token = std::env::var("SHELLMIND_TOKEN").ok().filter(|s| !s.trim().is_empty());

    if let (Some(url), Some(token)) = (&env_server, &env_token) {
        return Connection { server_url: url.clone(), token: Some(token.clone()) };
    }

    if let Some(runtime) = read_runtime_file() {
        return Connection {
            server_url: env_server.unwrap_or_else(|| runtime.server_url),
            token: env_token.or(Some(runtime.token)),
        };
    }

    // If --token is passed explicitly, connect with the user-provided value
    if let Some(token) = &args.token {
        return Connection { server_url: env_server.unwrap_or_else(|| args.server.clone()), token: Some(token.clone()) };
    }

    Connection { server_url: env_server.unwrap_or_else(|| args.server.clone()), token: env_token.or(args.token.clone()) }
}

struct RuntimeFile {
    server_url: String,
    token: String,
}

/// Read runtime connection info written by the desktop app and verify the process is still alive
fn read_runtime_file() -> Option<RuntimeFile> {
    let path = dirs::home_dir()?.join(".shellmind").join("runtime.json");
    let content = fs::read_to_string(path).ok()?;
    let value: serde_json::Value = serde_json::from_str(&content).ok()?;

    let port = value.get("port").and_then(serde_json::Value::as_u64)? as u16;
    let token = value.get("token").and_then(serde_json::Value::as_str)?.to_string();
    let pid = value.get("pid").and_then(serde_json::Value::as_u64)?;

    if port == 0 || token.is_empty() {
        return None;
    }

    // Process has exited → connection info is stale
    if !is_process_alive(pid as u32) {
        return None;
    }

    Some(RuntimeFile { server_url: format!("http://127.0.0.1:{}", port), token })
}

/// Check whether a local process is still alive
fn is_process_alive(pid: u32) -> bool {
    // kill -0: probe process existence without sending a signal
    std::process::Command::new("kill")
        .arg("-0")
        .arg(pid.to_string())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .map(|status| status.success())
        .unwrap_or(false)
}

/// Extract displayable text from a done event's content.
/// On a normal finish, content is null (text already arrived via text events).
/// On abnormal termination, content is a JSON string with "content"/"error"; parse it as a fallback.
pub fn extract_done_text(content: Option<&str>) -> String {
    let Some(raw) = content else { return String::new() };
    let raw = raw.trim();
    if raw.is_empty() {
        return String::new();
    }
    if let Ok(value) = serde_json::from_str::<serde_json::Value>(raw) {
        let text = value
            .get("content")
            .and_then(serde_json::Value::as_str)
            .or_else(|| value.get("error").and_then(serde_json::Value::as_str))
            .unwrap_or("");
        return text.trim().to_string();
    }
    raw.to_string()
}

// ── Internal app message model ──

/// Chat message
#[derive(Debug, Clone)]
pub enum Message {
    /// User input
    User { text: String },
    /// Assistant text (streamed/accumulated)
    Assistant { text: String, done: bool },
    /// Tool call
    ToolCall {
        tool_name: String,
        args: String,
        tool_call_id: String,
        status: ToolStatus,
    },
    /// Tool result
    ToolResult {
        tool_name: String,
        #[allow(dead_code)]
        tool_call_id: String,
        result: String,
        status: ToolStatus,
    },
    /// Error
    Error { text: String },
    /// System message (round info, etc.)
    System { text: String },
    /// File change summary
    Diff { summary: ChangeSummary },
}

#[derive(Debug, Clone, PartialEq)]
pub enum ToolStatus {
    InProgress,
    Success,
    Failure,
}

/// App event
#[derive(Debug)]
pub enum AppEvent {
    /// Incoming SSE event
    SseEvent(ReActEvent),
    /// User sent a message
    #[allow(dead_code)]
    UserInput(String),
    /// Request completed
    Done,
    /// Error
    Error(String),
}

/// Input history
pub struct InputHistory {
    /// History entries
    entries: Vec<String>,
    /// Current browse index (None = composing new input)
    index: Option<usize>,
    /// Stashed current input (restored when leaving history browse)
    temp_input: String,
    /// Max number of entries
    max_entries: usize,
    /// History file path
    history_file: Option<PathBuf>,
}

impl InputHistory {
    pub fn new(max_entries: usize) -> Self {
        let history_file = Self::get_history_path();
        let entries = history_file.as_ref()
            .and_then(|path| Self::load_from_file(path))
            .unwrap_or_default();

        Self {
            entries,
            index: None,
            temp_input: String::new(),
            max_entries,
            history_file,
        }
    }

    /// Resolve the history file path
    fn get_history_path() -> Option<PathBuf> {
        dirs::home_dir().map(|home| home.join(".shellmind").join("cli_history"))
    }

    /// Load history from disk
    fn load_from_file(path: &PathBuf) -> Option<Vec<String>> {
        if let Ok(content) = fs::read_to_string(path) {
            let entries: Vec<String> = content
                .lines()
                .filter(|l| !l.trim().is_empty())
                .map(|l| l.to_string())
                .collect();
            Some(entries)
        } else {
            None
        }
    }

    /// Save history to disk
    pub fn save(&self) {
        if let Some(ref path) = self.history_file {
            if let Some(parent) = path.parent() {
                let _ = fs::create_dir_all(parent);
            }
            let content = self.entries.join("\n");
            let _ = fs::write(path, content);
        }
    }

    /// Append a new entry
    pub fn add(&mut self, entry: String) {
        if entry.trim().is_empty() {
            return;
        }
        // Skip consecutive duplicate entries
        if self.entries.last() != Some(&entry) {
            self.entries.push(entry);
            if self.entries.len() > self.max_entries {
                self.entries.remove(0);
            }
            self.save();
        }
        self.index = None;
        self.temp_input.clear();
    }

    /// Browse older history (previous input)
    pub fn navigate_up(&mut self, current_input: &str) -> Option<String> {
        if self.entries.is_empty() {
            return None;
        }

        // First Up press: stash the current input
        if self.index.is_none() {
            self.temp_input = current_input.to_string();
            self.index = Some(self.entries.len() - 1);
        } else if let Some(idx) = self.index {
            if idx > 0 {
                self.index = Some(idx - 1);
            }
        }

        self.index.map(|i| self.entries[i].clone())
    }

    /// Browse newer history (next input)
    pub fn navigate_down(&mut self) -> Option<String> {
        if let Some(idx) = self.index {
            if idx + 1 < self.entries.len() {
                self.index = Some(idx + 1);
                Some(self.entries[idx + 1].clone())
            } else {
                // Back at the latest entry; restore the stashed input
                self.index = None;
                Some(self.temp_input.clone())
            }
        } else {
            None
        }
    }

    /// Number of history entries
    #[allow(dead_code)]
    pub fn len(&self) -> usize {
        self.entries.len()
    }
}

/// App state
pub struct App {
    /// Server URL
    pub server_url: String,
    /// Local runtime token
    pub token: Option<String>,
    /// User model config ID (sent with chat requests so the server does not fall back to an unusable default)
    pub model_id: Option<i64>,
    /// Agent ID
    pub agent_id: String,
    /// User ID
    pub user_id: String,
    /// Current session ID
    pub session_id: Option<String>,
    /// Chat messages
    pub messages: Vec<Message>,
    /// Current input buffer (multi-line)
    pub input_lines: Vec<String>,
    /// Cursor position (row, column)
    pub cursor_pos: (usize, usize),
    /// Whether we are waiting for an AI reply
    pub is_streaming: bool,
    /// Whether the app should quit
    pub should_quit: bool,
    /// Current streamed text accumulator
    pub streaming_text: String,
    /// Tool call → step index map
    pub tool_step_map: HashMap<String, usize>,
    /// Step counter
    pub step_counter: usize,
    /// Working directory
    pub workdir: Option<String>,
    /// Manual scroll offset (0 = bottom/latest, >0 = lines above the bottom)
    pub scroll_offset: u16,
    /// Manual scroll mode (streaming output switches back to auto-scroll)
    pub manual_scroll: bool,
    /// Input history
    pub input_history: InputHistory,
    /// Whether the command palette is open
    pub command_palette_open: bool,
    /// Command palette filter text
    pub command_palette_filter: String,
    /// Command palette selection index
    pub command_palette_selection: usize,
}

impl App {
    pub fn new(args: &CliArgs) -> Self {
        // Infer the working directory
        let workdir = args.workdir.clone().or_else(|| {
            std::env::current_dir().ok().map(|p| p.to_string_lossy().to_string())
        });

        Self {
            server_url: args.server.clone(),
            token: args.token.clone(),
            model_id: args.model_id.as_deref().and_then(|v| v.trim().parse::<i64>().ok()),
            agent_id: args.agent_id.clone(),
            user_id: args.user_id.clone(),
            session_id: None,
            messages: Vec::new(),
            input_lines: vec![String::new()],
            cursor_pos: (0, 0),
            is_streaming: false,
            should_quit: false,
            streaming_text: String::new(),
            tool_step_map: HashMap::new(),
            step_counter: 0,
            workdir,
            scroll_offset: 0,
            manual_scroll: false,
            input_history: InputHistory::new(1000),
            command_palette_open: false,
            command_palette_filter: String::new(),
            command_palette_selection: 0,
        }
    }

    /// Current input as a single string (joined lines)
    pub fn get_input_text(&self) -> String {
        self.input_lines.join("\n")
    }

    /// Set input text (used by history navigation)
    pub fn set_input_text(&mut self, text: String) {
        self.input_lines = text.lines().map(|s| s.to_string()).collect();
        if self.input_lines.is_empty() {
            self.input_lines.push(String::new());
        }
        self.cursor_pos = (self.input_lines.len() - 1, self.input_lines.last().unwrap().len());
    }

    /// Clear the input
    pub fn clear_input(&mut self) {
        self.input_lines = vec![String::new()];
        self.cursor_pos = (0, 0);
    }

    /// Insert a character at the cursor (UTF-8 aware)
    pub fn insert_char(&mut self, c: char) {
        let (line, col) = self.cursor_pos;
        if line < self.input_lines.len() {
            let text = &self.input_lines[line];
            let byte_pos = text.char_indices().nth(col).map(|(i, _)| i).unwrap_or(text.len());
            self.input_lines[line].insert(byte_pos, c);
            self.cursor_pos.1 += 1;
        }
    }

    /// Delete the character before the cursor (backspace, UTF-8 aware)
    pub fn backspace(&mut self) -> bool {
        let (line, col) = self.cursor_pos;
        if col > 0 {
            // In-line delete: find the character boundary
            let text = &self.input_lines[line];
            let byte_pos = text.char_indices().nth(col).map(|(i, _)| i).unwrap_or(text.len());
            let prev_byte_pos = text.char_indices().nth(col - 1).map(|(i, _)| i).unwrap_or(0);
            self.input_lines[line].replace_range(prev_byte_pos..byte_pos, "");
            self.cursor_pos.1 -= 1;
            true
        } else if line > 0 {
            // Merge across lines: append this line onto the previous line
            let current_line = self.input_lines.remove(line);
            let prev_len = self.input_lines[line - 1].chars().count();
            self.input_lines[line - 1].push_str(&current_line);
            self.cursor_pos = (line - 1, prev_len);
            true
        } else {
            false
        }
    }

    /// Delete the character after the cursor (Delete key, UTF-8 aware)
    pub fn delete_char(&mut self) {
        let (line, col) = self.cursor_pos;
        if line < self.input_lines.len() {
            let text = &self.input_lines[line];
            let char_count = text.chars().count();
            if col < char_count {
                // In-line delete: find the character boundary
                let byte_pos = text.char_indices().nth(col).map(|(i, _)| i).unwrap_or(text.len());
                let next_byte_pos = text.char_indices().nth(col + 1).map(|(i, _)| i).unwrap_or(text.len());
                self.input_lines[line].replace_range(byte_pos..next_byte_pos, "");
            } else if line + 1 < self.input_lines.len() {
                // Merge across lines: append the next line onto this line
                let next_line = self.input_lines.remove(line + 1);
                self.input_lines[line].push_str(&next_line);
            }
        }
    }

    /// Insert a newline (Shift+Enter, UTF-8 aware)
    pub fn insert_newline(&mut self) {
        let (line, col) = self.cursor_pos;
        if line < self.input_lines.len() {
            let text = &self.input_lines[line];
            let byte_pos = text.char_indices().nth(col).map(|(i, _)| i).unwrap_or(text.len());
            let after = self.input_lines[line].split_off(byte_pos);
            self.input_lines.insert(line + 1, after);
            self.cursor_pos = (line + 1, 0);
        }
    }

    /// Move cursor left
    pub fn cursor_left(&mut self) {
        let (line, col) = self.cursor_pos;
        if col > 0 {
            self.cursor_pos.1 -= 1;
        } else if line > 0 {
            // Jump to the end of the previous line
            let prev_len = self.input_lines[line - 1].chars().count();
            self.cursor_pos = (line - 1, prev_len);
        }
    }

    /// Move cursor right
    pub fn cursor_right(&mut self) {
        let (line, col) = self.cursor_pos;
        let line_len = self.input_lines[line].chars().count();
        if col < line_len {
            self.cursor_pos.1 += 1;
        } else if line + 1 < self.input_lines.len() {
            // Jump to the start of the next line
            self.cursor_pos = (line + 1, 0);
        }
    }

    /// Move cursor up
    pub fn cursor_up(&mut self) {
        let (line, col) = self.cursor_pos;
        if line > 0 {
            let prev_len = self.input_lines[line - 1].chars().count();
            self.cursor_pos = (line - 1, col.min(prev_len));
        }
    }

    /// Move cursor down
    pub fn cursor_down(&mut self) {
        let (line, col) = self.cursor_pos;
        if line + 1 < self.input_lines.len() {
            let next_len = self.input_lines[line + 1].chars().count();
            self.cursor_pos = (line + 1, col.min(next_len));
        }
    }

    /// Move cursor to the start of the line
    pub fn cursor_home(&mut self) {
        self.cursor_pos.1 = 0;
    }

    /// Move cursor to the end of the line
    pub fn cursor_end(&mut self) {
        let line = self.cursor_pos.0;
        if line < self.input_lines.len() {
            self.cursor_pos.1 = self.input_lines[line].chars().count();
        }
    }

    /// Handle an SSE event
    pub fn handle_event(&mut self, event: ReActEvent) {
        match event.event.as_str() {
            "text" => {
                let full_text = event.full_text.or(event.content).unwrap_or_default();
                self.streaming_text = full_text.clone();

                // New message arrived → leave manual scroll and follow the bottom
                if self.manual_scroll {
                    self.manual_scroll = false;
                    self.scroll_offset = 0;
                }

                // Update or create an Assistant message
                if let Some(last) = self.messages.last_mut() {
                    if let Message::Assistant { text, done } = last {
                        if !*done {
                            *text = full_text;
                            return;
                        }
                    }
                }
                // Create a new Assistant message
                self.messages.push(Message::Assistant {
                    text: full_text,
                    done: false,
                });
            }

            "tool_call" => {
                self.step_counter += 1;
                let tool_name = event.tool_name.unwrap_or_default();
                let tool_call_id = event.tool_call_id.unwrap_or_default();
                let args = event.args.unwrap_or_default();

                if !tool_call_id.is_empty() {
                    self.tool_step_map
                        .insert(tool_call_id.clone(), self.step_counter);
                }

                self.messages.push(Message::ToolCall {
                    tool_name,
                    args,
                    tool_call_id,
                    status: ToolStatus::InProgress,
                });
            }

            "tool_result" => {
                let tool_call_id = event.tool_call_id.unwrap_or_default();
                let result = event.content.unwrap_or_default();
                let status = if event.status.as_deref() == Some("error") {
                    ToolStatus::Failure
                } else {
                    ToolStatus::Success
                };

                // Find the matching ToolCall and update it
                let tool_name = self.find_tool_name(&tool_call_id);
                self.messages.push(Message::ToolResult {
                    tool_name,
                    tool_call_id,
                    result,
                    status,
                });
            }

            "tool_progress" => {
                // Tool progress events are ignored for now (progress bar can be added later)
            }

            "round_end" => {
                if let Some(info) = event.step_info {
                    self.messages.push(Message::System {
                        text: format!(
                            "🔄 ReAct step {}/{} · {} tool calls",
                            info.current_step, info.max_steps, info.total_tool_calls
                        ),
                    });
                }
            }

            "done" => {
                // Mark the Assistant message as complete
                if let Some(last) = self.messages.last_mut() {
                    if let Message::Assistant { text: _, done } = last {
                        *done = true;
                    }
                }
                self.is_streaming = false;
                self.streaming_text.clear();

                // Fallback: the run produced no text (e.g. abnormal termination);
                // show the server summary from the done event so the TUI is not silent
                let has_assistant_text = self.messages.iter().any(|m| {
                    matches!(m, Message::Assistant { text, .. } if !text.trim().is_empty())
                });
                if !has_assistant_text {
                    let text = extract_done_text(event.content.as_deref());
                    if !text.is_empty() {
                        self.messages.push(Message::Error { text });
                    }
                }

                // If changeSummary is present, show the file-change summary
                if let Some(cs) = event.change_summary {
                    if !cs.modified.is_empty() || !cs.created.is_empty() || !cs.deleted.is_empty() {
                        self.messages.push(Message::Diff { summary: cs });
                    }
                }
            }

            "error" => {
                let error_msg = event.content.unwrap_or_default();
                self.messages.push(Message::Error { text: error_msg });
                self.is_streaming = false;
                self.streaming_text.clear();
            }

            "warning" => {
                if let Some(w) = event.content {
                    self.messages.push(Message::System { text: format!("⚠️ {}", w) });
                }
            }

            "execute_local_command" => {
                // In CLI mode, run local commands directly (no Tauri invoke)
                let cmd_id = event.cmd_id.unwrap_or_default();
                let command = event.command.unwrap_or_default();
                let _cwd = event.cwd.clone();

                if !cmd_id.is_empty() && !command.is_empty() {
                    self.messages.push(Message::System {
                        text: format!("⚡ Running local command: {}", command),
                    });
                    // Actual execution is handled in sse_client
                }
            }

            "heartbeat" | "status" | "round_start" => {
                // Heartbeat / status / round_start — not shown in the UI
            }

            _ => {
                // Unknown event type; ignore
            }
        }
    }

    /// Look up the tool name by tool_call_id
    fn find_tool_name(&self, tool_call_id: &str) -> String {
        // Search messages in reverse
        for msg in self.messages.iter().rev() {
            if let Message::ToolCall { tool_name, tool_call_id: id, .. } = msg {
                if id == tool_call_id {
                    return tool_name.clone();
                }
            }
        }
        String::new()
    }

    /// User sent a message
    pub fn send_message(&mut self, text: String) {
        // Add to history
        self.input_history.add(text.clone());
        // Add to the message list
        self.messages.push(Message::User { text: text.clone() });
        self.is_streaming = true;
        self.streaming_text.clear();
        self.clear_input();
    }

    /// Slash-command handling
    pub fn handle_slash_command(&mut self, cmd: &str) -> bool {
        let parts: Vec<&str> = cmd.splitn(2, ' ').collect();
        let command = parts[0];

        match command {
            "/quit" | "/exit" | "/q" => {
                self.should_quit = true;
                true
            }
            "/clear" => {
                self.messages.clear();
                self.streaming_text.clear();
                true
            }
            "/help" => {
                self.messages.push(Message::System {
                    text: "ShellMind CLI commands:\n\
                           /help    — show help\n\
                           /quit    — quit\n\
                           /clear   — clear conversation\n\
                           /session — show current session\n\
                           /model   — switch agent\n\
                           /status  — show connection status"
                        .to_string(),
                });
                true
            }
            "/session" => {
                let info = match &self.session_id {
                    Some(id) => format!("Session ID: {}\nAgent: {}", id, self.agent_id),
                    None => "No session yet".to_string(),
                };
                self.messages.push(Message::System { text: info });
                true
            }
            "/status" => {
                self.messages.push(Message::System {
                    text: format!(
                        "Server: {}\nAuth: {}\nModel: {}\nAgent: {}\nUser: {}\nWorkdir: {}\nStreaming: {}\nMessages: {}",
                        self.server_url,
                        if self.token.is_some() { "local token loaded" } else { "no token" },
                        self.model_id.map(|id| id.to_string()).unwrap_or_else(|| "server default".to_string()),
                        self.agent_id,
                        self.user_id,
                        self.workdir.as_deref().unwrap_or("not set"),
                        self.is_streaming,
                        self.messages.len()
                    ),
                });
                true
            }
            "/model" => {
                if let Some(new_id) = parts.get(1) {
                    self.agent_id = new_id.to_string();
                    self.session_id = None; // switching agent requires a new session
                    self.messages.push(Message::System {
                        text: format!("Switched agent to: {}", new_id),
                    });
                } else {
                    self.messages.push(Message::System {
                        text: format!("Current agent: {}", self.agent_id),
                    });
                }
                true
            }
            _ => false, // not a slash command; send as a normal message
        }
    }
}
