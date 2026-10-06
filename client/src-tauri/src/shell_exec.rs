//! Shell Execution Module for ShellMind
//! Provides safe shell command execution with platform-specific handling

use serde::{Deserialize, Serialize};
#[cfg(windows)]
use std::io::Read;
#[cfg(windows)]
use encoding_rs::GBK;
#[cfg(windows)]
use encoding_rs_io::DecodeReaderBytesBuilder;

/// Decode bytes to String with platform-aware encoding.
/// On Windows, tries GBK first (common for cmd/PowerShell), falls back to UTF-8 lossy.
/// On other platforms, uses UTF-8 lossy directly.
#[cfg(windows)]
fn decode_output_bytes(buf: &[u8]) -> String {
    let mut decoder = DecodeReaderBytesBuilder::new()
        .encoding(Some(GBK))
        .build(buf);
    let mut decoded = String::new();
    if decoder.read_to_string(&mut decoded).is_ok() {
        decoded
    } else {
        String::from_utf8_lossy(buf).to_string()
    }
}

#[cfg(not(windows))]
fn decode_output_bytes(buf: &[u8]) -> String {
    String::from_utf8_lossy(buf).to_string()
}

/// Resolve the user's login shell path ($SHELL, falling back to the platform default)
fn get_user_shell() -> String {
    #[cfg(any(target_os = "android", target_os = "ios"))]
    {
        // Android: /system/bin/sh is always available
        // iOS: no user shell inside the App Sandbox; use /bin/sh
        std::env::var("SHELL")
            .unwrap_or_else(|_| "/bin/sh".to_string())
    }
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    {
        #[cfg(not(windows))]
        {
            std::env::var("SHELL")
                .unwrap_or_else(|_| {
                    if cfg!(target_os = "macos") {
                        "/bin/zsh".to_string()
                    } else {
                        "/bin/bash".to_string()
                    }
                })
        }
        #[cfg(windows)]
        {
            "cmd.exe".to_string()
        }
    }
}
use std::collections::HashMap;
use std::io::{BufRead, BufReader};
use std::process::{Command, Stdio};
#[cfg(windows)]
use std::os::windows::process::CommandExt;
use std::sync::Mutex;
use std::time::Instant;
use tauri::Emitter;

/// Get the shell argument flag for the current platform.
/// - Windows: `/C`
/// - Android: `-c` (\system/bin/sh only supports -c)
/// - macOS/Linux: `-lic` (login + interactive + command)
fn get_shell_arg() -> &'static str {
    #[cfg(any(target_os = "android", target_os = "ios"))]
    { "-c" }
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    {
        if cfg!(target_os = "windows") { "/C" } else { "-lic" }
    }
}

// ─── Streaming Process Registry ──────────────────────────────────────────
// Global registry of running streaming processes, keyed by session ID.
// Used for Ctrl+C support: kill a running process by session ID.
lazy_static::lazy_static! {
    pub static ref STREAMING_PROCESSES: Mutex<HashMap<String, u32>> = Mutex::new(HashMap::new());
}
#[derive(Debug, Clone, Serialize)]
pub struct StreamEvent {
    /// Session ID for correlating events
    pub session_id: String,
    /// Event kind: "stdout" | "stderr" | "done" | "error"
    pub kind: String,
    /// Text content (for stdout/stderr) or message (for error)
    pub data: String,
    /// Exit code (only for "done" events)
    pub exit_code: Option<i32>,
    /// Duration in ms (only for "done" events)
    pub duration_ms: Option<u64>,
}

/// Result of shell execution
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ShellResult {
    /// Standard output
    pub stdout: String,
    /// Standard error
    pub stderr: String,
    /// Exit code
    pub exit_code: i32,
    /// Whether execution was successful (exit_code == 0)
    pub success: bool,
    /// Execution duration in milliseconds
    pub duration_ms: u64,
    /// Whether the command was auto-backgrounded (persistent server detected)
    pub backgrounded: bool,
    /// The final command that was executed (may differ from input if backgrounded)
    pub executed_command: String,
}

/// Detect if a command is a "persistent server" that will never exit on its own.
/// These commands need to be run in the background automatically.
fn is_persistent_server(command: &str) -> bool {
    let cmd_lower = command.to_lowercase();
    let cmd_trimmed = cmd_lower.trim();

    // Explicit background operator already present — treat as persistent, don't add another &
    if cmd_trimmed.ends_with('&') {
        return true;
    }

    // ─── Exact prefix patterns (most reliable) ───────────────────
    // We require a space or end-of-string after the keyword to avoid
    // matching quick-exit commands like "python --version".
    let patterns = [
        // Python HTTP servers
        ("python3 -m http.server", "python3 -m http.server"),
        ("python -m http.server",   "python -m http.server"),
        ("python3 -m SimpleHTTPServer", "python3 -m SimpleHTTPServer"),
        ("python -m SimpleHTTPServer",  "python -m SimpleHTTPServer"),
        // Python dev servers
        ("python3 manage.py runserver", "python3 manage.py runserver"),
        ("python manage.py runserver",  "python manage.py runserver"),
        ("flask run",              "flask run"),
        ("fastapi dev",            "fastapi dev"),
        ("uvicorn ",              "uvicorn "),
        ("django-admin runserver", "django-admin runserver"),
        // Node HTTP servers
        ("npx serve",             "npx serve"),
        ("npx http-server",       "npx http-server"),
        ("npx http2",             "npx http2"),
        ("http-server",           "http-server"),
        ("serve -s",              "serve -s"),
        // Node dev servers
        ("vite",                  "vite"),
        ("next dev",              "next dev"),
        ("next start",            "next start"),
        ("next build",            "next build"),
        ("nuxt dev",              "nuxt dev"),
        ("nuxt start",            "nuxt start"),
        ("nuxt build",            "nuxt build"),
        ("webpack serve",         "webpack serve"),
        ("webpack-dev-server",    "webpack-dev-server"),
        ("rollup -c -w",          "rollup -c -w"),
        ("esbuild --serve",       "esbuild --serve"),
        // Bun dev
        ("bun --bun dev",         "bun --bun dev"),
        ("bun dev",               "bun dev"),
        ("bun run dev",           "bun run dev"),
        // Go dev servers
        ("air",                   "air"),
        ("fresh",                 "fresh"),
        ("realize start",         "realize start"),
        // Rust dev servers
        ("cargo run --watch",     "cargo run --watch"),
        // Docker
        ("docker run",            "docker run"),
        ("docker-compose up",     "docker-compose up"),
        ("docker compose up",     "docker compose up"),
        // Misc servers
        ("redis-server",          "redis-server"),
        ("mongod",                "mongod"),
        ("postgres -D",           "postgres -D"),
        ("nginx",                 "nginx"),
        // Watch/maintainer loops
        ("nodemon",               "nodemon"),
        ("node-dev",              "node-dev"),
        ("ts-node-dev",           "ts-node-dev"),
        ("concurrently",          "concurrently"),
        ("live-server",           "live-server"),
        ("browser-sync start",    "browser-sync start"),
        ("parcel watch",          "parcel watch"),
        ("snowpack dev",          "snowpack dev"),
        // Interactive commands that would block forever
        ("top",                   "top"),
        ("htop",                  "htop"),
        ("vmstat",                "vmstat"),
        ("iostat",                "iostat"),
        ("watch ",                "watch "),
        ("tail -f",               "tail -f"),
        ("tail --follow",         "tail --follow"),
        // Shell REPLs
        ("python3",               "python3"),
        ("python",                "python"),
        ("node -i",               "node -i"),
        ("node --interactive",    "node --interactive"),
        ("ruby -i",               "ruby -i"),
        ("lua",                   "lua"),
        ("perl -de",              "perl -de"),
        ("php -a",                "php -a"),
        ("bash -i",               "bash -i"),
        ("zsh -i",                "zsh -i"),
        // Interactive network tools
        ("telnet",                "telnet"),
        ("ftp",                   "ftp"),
        ("nc -l",                 "nc -l"),
        ("nc -lvnp",              "nc -lvnp"),
        ("socat -",               "socat -"),
    ];

    for (prefix, _display) in &patterns {
        if cmd_trimmed.starts_with(prefix) {
            return true;
        }
    }

    // ─── Heuristics: watch / serve / dev flags ───────────────────
    // Only for package managers that run dev servers
    let dev_prefixes = [
        "npm run dev", "npm run serve", "npm run start",
        "pnpm run dev", "pnpm run serve", "pnpm run start",
        "yarn dev", "yarn serve", "yarn start",
        "bun run dev", "bun run serve",
        "deno task dev", "deno task serve",
    ];
    for prefix in &dev_prefixes {
        if cmd_trimmed.starts_with(prefix) {
            return true;
        }
    }

    // Flag-based heuristics (must NOT match quick-exit commands)
    if cmd_trimmed.contains(" --watch") || cmd_trimmed.ends_with(" -w") {
        return true;
    }
    if cmd_trimmed.contains(" --serve") && !cmd_trimmed.contains(" --server") {
        return true;
    }

    false
}

#[allow(dead_code)]
/// Wrap a command for background execution.
/// Uses `setsid` so the process is fully detached and survives shell exit.
fn wrap_background_command(command: &str) -> String {
    format!("setsid {} >/dev/null 2>&1 &", command.trim_end_matches('&').trim())
}

/// Execute a shell command with proper platform handling
pub fn execute_shell_internal(
    command: &str,
    cwd: Option<&str>,
    timeout_ms: u64,
    auto_background: bool,
) -> Result<ShellResult, String> {
    let start = Instant::now();

    // Validate up front so both the foreground and background paths are covered
    validate_command(command)?;

    // Check if this is a persistent server that needs backgrounding
    let is_persistent = auto_background && is_persistent_server(command);

    if is_persistent {
        // Strip any trailing & the user already added to avoid "nohup cmd & &"
        let clean_cmd = command.trim_end().trim_end_matches('&').trim().to_string();

        // Use nohup to detach the process from the terminal, then run via bash.
        // nohup ignores SIGHUP so the process survives even after bash exits.
        // bash itself finishes immediately after spawning the nohup subprocess.
        let nohup_cmd = if cfg!(target_os = "windows") {
            format!("start /B {}", clean_cmd)
        } else if cfg!(any(target_os = "android", target_os = "ios")) {
            // Android (Toybox) / iOS: simple background, no nohup
            format!("{} >/dev/null 2>&1 &", clean_cmd)
        } else {
            format!("nohup {} >/dev/null 2>&1 &", clean_cmd)
        };

        // Validate the original command (not the nohup wrapper)
        validate_command(&clean_cmd)?;

        let shell_path = get_user_shell();
        let shell_arg = get_shell_arg();

        let mut cmd = Command::new(&shell_path);
        #[cfg(windows)]
        cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
        cmd.arg(shell_arg)
            .arg(&nohup_cmd)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());

        if let Some(dir) = cwd {
            let path = std::path::Path::new(dir);
            if path.exists() && path.is_dir() {
                cmd.current_dir(path);
            }
        }

        // spawn() and immediately drop the handle - we don't wait for the child.
        // nohup ensures the process survives after bash exits.
        match cmd.spawn() {
            Ok(_child) => {
                return Ok(ShellResult {
                    stdout: String::new(),
                    stderr: String::new(),
                    exit_code: 0,
                    success: true,
                    duration_ms: start.elapsed().as_millis() as u64,
                    backgrounded: true,
                    executed_command: clean_cmd,
                });
            }
            Err(e) => {
                return Err(format!("Failed to spawn command: {}", e));
            }
        }
    }

    // Non-persistent: run in a thread with timeout. If the command doesn't
    // finish within timeout_ms, return an error immediately so the UI never blocks.
    use std::sync::mpsc;
    use std::thread;

    let (tx, rx) = mpsc::channel();

    // Clone data needed inside the thread to avoid lifetime issues
    let cmd_str = command.to_string();
    let cwd_str = cwd.map(|s| s.to_string());

    let _join_handle = thread::spawn(move || {
        let shell_path = get_user_shell();
        let shell_arg = get_shell_arg();

        let mut cmd = Command::new(&shell_path);
        #[cfg(windows)]
        cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
        cmd.arg(shell_arg)
            .arg(&cmd_str)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        if let Some(ref dir) = cwd_str {
            let path = std::path::Path::new(dir);
            if path.exists() && path.is_dir() {
                cmd.current_dir(path);
            }
        }

        match cmd.output() {
            Ok(o) => {
                let stdout = decode_output_bytes(&o.stdout);
                let stderr = filter_shell_noise(&decode_output_bytes(&o.stderr));
                let exit_code = o.status.code().unwrap_or(-1);
                tx.send(Ok((stdout, stderr, exit_code))).ok();
            }
            Err(e) => {
                tx.send(Err(format!("Failed to execute command: {}", e))).ok();
            }
        }
    });

    let (stdout, stderr, exit_code) = match rx.recv_timeout(std::time::Duration::from_millis(timeout_ms.max(5000))) {
        Ok(Ok(r)) => r,
        Ok(Err(e)) => return Err(e),
        Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
            return Err(format!(
                "Command timed out after {}ms (killed). If you need more time, increase timeout.",
                timeout_ms.max(5000)
            ));
        }
        Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
            return Err("Command thread terminated unexpectedly".to_string());
        }
    };

    Ok(ShellResult {
        stdout,
        stderr,
        exit_code,
        success: exit_code == 0,
        duration_ms: start.elapsed().as_millis() as u64,
        backgrounded: false,
        executed_command: command.to_string(),
    })
}

/// Filter shell initialization noise from stderr.
/// When using `-lic` (login+interactive shell), shell rc files may produce
/// warnings that are not from the user's actual command.
/// - zsh: "command not found: compdef/compinit" (completion scripts load before compinit)
/// - bash: "bash: compgen: command not found" or similar
/// - Windows cmd.exe: no such noise (uses /C, not -lic)
fn filter_shell_noise(stderr: &str) -> String {
    stderr
        .lines()
        .filter(|line| {
            let l = line.trim();
            // Skip empty lines from noise filtering
            if l.is_empty() {
                return true; // keep empty lines (they may be intentional)
            }
            // zsh completion system noise
            if l.contains("command not found: compdef")
                || l.contains("command not found: compinit")
                || l.contains("command not found: _") && l.contains("compdef")
            {
                return false;
            }
            // bash completion noise
            if l.contains("bash: compgen: command not found")
                || l.contains("bash: complete: command not found")
            {
                return false;
            }
            // Generic: shell rc file errors that are clearly init noise
            // Pattern: <shell>:<line_number>: <something> not found (from sourcing rc files)
            // But be careful not to filter real command errors from user commands
            true
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// Basic command validation for safety
fn validate_command(command: &str) -> Result<(), String> {
    let dangerous_patterns = [
        // Dangerous file operations
        "rm -rf /",
        "rm -rf /*",
        "rm -rf ..",
        // Fork bombs
        ":(){ :|:& };:",
        // Network attacks (basic)
        // Note: We allow wget/curl for development convenience
    ];
    
    let cmd_lower = command.to_lowercase();
    for pattern in dangerous_patterns {
        if cmd_lower.contains(&pattern.to_lowercase()) {
            return Err(format!("Command contains dangerous pattern: {}", pattern));
        }
    }
    
    // Check for null bytes (injection attempt)
    if command.contains('\0') {
        return Err("Command contains null byte (possible injection)".to_string());
    }
    
    Ok(())
}

#[allow(dead_code)]
/// Execute command with streaming support (returns spawn handle for real-time output)
#[cfg(not(target_os = "windows"))]
pub fn spawn_shell(
    command: &str,
    cwd: Option<&str>,
) -> Result<std::process::Child, String> {
    validate_command(command)?;
    
    let shell = get_user_shell();
    let mut cmd = Command::new(&shell);
    // Android / iOS: /bin/sh only supports -c, not -lic
    #[cfg(any(target_os = "android", target_os = "ios"))]
    cmd.arg("-c");
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    cmd.arg("-lic");
    cmd.arg(command)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    
    if let Some(dir) = cwd {
        let path = std::path::Path::new(dir);
        if path.exists() && path.is_dir() {
            cmd.current_dir(path);
        }
    }
    
    cmd.spawn().map_err(|e| format!("Failed to spawn: {}", e))
}

#[cfg(target_os = "windows")]
#[allow(dead_code)]
pub fn spawn_shell(
    command: &str,
    cwd: Option<&str>,
) -> Result<std::process::Child, String> {
    validate_command(command)?;
    
    let mut cmd = Command::new("cmd.exe");
    #[cfg(windows)]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
    cmd.arg("/C")
        .arg(command)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    
    if let Some(dir) = cwd {
        let path = std::path::Path::new(dir);
        if path.exists() && path.is_dir() {
            cmd.current_dir(path);
        }
    }
    
    cmd.spawn().map_err(|e| format!("Failed to spawn: {}", e))
}

/// Platform-specific shell information
pub fn get_shell_info() -> serde_json::Value {
    let hostname = std::env::var("HOSTNAME")
        .or_else(|_| std::process::Command::new("hostname").output().map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()))
        .unwrap_or_else(|_| "localhost".to_string());

    #[cfg(target_os = "android")]
    {
        serde_json::json!({
            "platform": "android",
            "shell": get_user_shell(),
            "home": std::env::var("HOME").ok(),
            "hostname": hostname,
            "cwd": std::env::current_dir().ok().map(|p| p.to_string_lossy().to_string()),
        })
    }
    #[cfg(target_os = "ios")]
    {
        serde_json::json!({
            "platform": "ios",
            "shell": get_user_shell(),
            "home": std::env::var("HOME").ok(),
            "hostname": hostname,
            "cwd": std::env::current_dir().ok().map(|p| p.to_string_lossy().to_string()),
        })
    }
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    {
        #[cfg(target_os = "windows")]
        {
            serde_json::json!({
                "platform": "windows",
                "shell": "cmd.exe",
                "home": std::env::var("USERPROFILE").ok(),
                "hostname": hostname,
                "cwd": std::env::current_dir().ok().map(|p| p.to_string_lossy().to_string()),
            })
        }
        #[cfg(target_os = "macos")]
        {
            serde_json::json!({
                "platform": "macos",
                "shell": get_user_shell(),
                "home": std::env::var("HOME").ok(),
                "hostname": hostname,
                "cwd": std::env::current_dir().ok().map(|p| p.to_string_lossy().to_string()),
            })
        }
        #[cfg(target_os = "linux")]
        {
            serde_json::json!({
                "platform": "linux",
                "shell": get_user_shell(),
                "home": std::env::var("HOME").ok(),
                "hostname": hostname,
                "cwd": std::env::current_dir().ok().map(|p| p.to_string_lossy().to_string()),
            })
        }
    }
}

// ─── Tauri Commands ───────────────────────────────────────────────────────

/// Execute a shell command (Tauri command)
/// # Arguments
/// * `command` - The shell command to execute
/// * `cwd` - Working directory (optional, defaults to project root)
/// * `timeout_ms` - Timeout in milliseconds (default 30000)
/// * `auto_background` - If true, auto-background persistent servers (http/dev servers)
/// * `session_id` - Optional session ID for abort-aware process tracking (registered in STREAMING_PROCESSES)
///
/// Uses `spawn_blocking` to run the blocking shell execution off the main thread,
/// preventing UI freezes (spinning cursor) during long-running commands.
#[tauri::command]
pub async fn execute_shell_cmd(
    command: String,
    cwd: Option<String>,
    timeout_ms: Option<u64>,
    auto_background: Option<bool>,
    session_id: Option<String>,
) -> Result<ShellResult, String> {
    // If session_id is provided, use abort-aware execution with PID tracking
    if let Some(sid) = session_id {
        return execute_shell_with_tracking(
            &command,
            cwd.as_deref(),
            timeout_ms.unwrap_or(30000),
            auto_background.unwrap_or(false),
            sid,
        ).await;
    }

    tokio::task::spawn_blocking(move || {
        execute_shell_internal(
            &command,
            cwd.as_deref(),
            timeout_ms.unwrap_or(30000),
            auto_background.unwrap_or(false),
        )
    })
    .await
    .map_err(|e| format!("Task join error: {}", e))?
}

/// Execute a shell command with PID tracking, so it can be killed via `kill_stream_shell`.
/// This is the abort-aware version of `execute_shell_internal`.
pub async fn execute_shell_with_tracking(
    command: &str,
    cwd: Option<&str>,
    timeout_ms: u64,
    auto_background: bool,
    session_id: String,
) -> Result<ShellResult, String> {
    use std::sync::mpsc;
    use std::thread;

    // Validate up front so both the foreground and background paths are covered
    validate_command(command)?;

    // Persistent server backgrounding — same as execute_shell_internal
    let is_persistent = auto_background && is_persistent_server(command);
    if is_persistent {
        let clean_cmd = command.trim_end().trim_end_matches('&').trim().to_string();
        let nohup_cmd = if cfg!(target_os = "windows") {
            format!("start /B {}", clean_cmd)
        } else if cfg!(any(target_os = "android", target_os = "ios")) {
            // Android (Toybox) / iOS: simple background, no nohup
            format!("{} >/dev/null 2>&1 &", clean_cmd)
        } else {
            format!("nohup {} >/dev/null 2>&1 &", clean_cmd)
        };
        validate_command(&clean_cmd)?;
        let shell_path = get_user_shell();
        let shell_arg = get_shell_arg();
        let mut cmd = Command::new(&shell_path);
        #[cfg(windows)]
        cmd.creation_flags(0x08000000);
        cmd.arg(shell_arg)
            .arg(&nohup_cmd)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        if let Some(dir) = cwd {
            let path = std::path::Path::new(dir);
            if path.exists() && path.is_dir() {
                cmd.current_dir(path);
            }
        }
        match cmd.spawn() {
            Ok(_child) => Ok(ShellResult {
                stdout: String::new(),
                stderr: String::new(),
                exit_code: 0,
                success: true,
                duration_ms: 0,
                backgrounded: true,
                executed_command: clean_cmd,
            }),
            Err(e) => Err(format!("Failed to spawn command: {}", e)),
        }
    } else {
        // Non-persistent: spawn child process, register PID for kill support, wait with timeout
        let (pid_tx, pid_rx) = mpsc::channel::<u32>();
        let (result_tx, result_rx) = mpsc::channel::<Result<(String, String, i32), String>>();
        let cmd_str = command.to_string();
        let cwd_str = cwd.map(|s| s.to_string());

        let _join_handle = thread::spawn(move || {
            let shell_path = get_user_shell();
            let shell_arg = get_shell_arg();
            let mut cmd = Command::new(&shell_path);
            #[cfg(windows)]
            cmd.creation_flags(0x08000000);
            cmd.arg(shell_arg)
                .arg(&cmd_str)
                .stdout(Stdio::piped())
                .stderr(Stdio::piped());
            if let Some(ref dir) = cwd_str {
                let path = std::path::Path::new(dir);
                if path.exists() && path.is_dir() {
                    cmd.current_dir(path);
                }
            }

            match cmd.spawn() {
                Ok(child) => {
                    let pid = child.id();
                    let _ = pid_tx.send(pid);
                    match child.wait_with_output() {
                        Ok(o) => {
                            let stdout = decode_output_bytes(&o.stdout);
                            let stderr = filter_shell_noise(&decode_output_bytes(&o.stderr));
                            let exit_code = o.status.code().unwrap_or(-1);
                            result_tx.send(Ok((stdout, stderr, exit_code))).ok();
                        }
                        Err(e) => {
                            result_tx.send(Err(format!("Failed to wait for output: {}", e))).ok();
                        }
                    }
                }
                Err(e) => {
                    result_tx.send(Err(format!("Failed to spawn: {}", e))).ok();
                }
            }
        });

        // Receive the PID from the spawned thread and register it
        let pid = pid_rx.recv_timeout(std::time::Duration::from_secs(5))
            .map_err(|_| "Failed to get process PID".to_string())?;
        {
            let mut procs = STREAMING_PROCESSES.lock().unwrap();
            procs.insert(session_id.clone(), pid);
        }

        let start = std::time::Instant::now();
        let timeout_dur = std::time::Duration::from_millis(timeout_ms.max(5000));

        // Wait for result with timeout
        let result = match result_rx.recv_timeout(timeout_dur) {
            Ok(Ok((stdout, stderr, exit_code))) => Ok(ShellResult {
                stdout,
                stderr,
                exit_code,
                success: exit_code == 0,
                duration_ms: start.elapsed().as_millis() as u64,
                backgrounded: false,
                executed_command: command.to_string(),
            }),
            Ok(Err(e)) => Err(e),
            Err(mpsc::RecvTimeoutError::Timeout) => {
                // Timeout — kill the process
                let _ = kill_process_by_pid(pid);
                Err(format!(
                    "Command timed out after {}ms (killed). If you need more time, increase timeout.",
                    timeout_ms.max(5000)
                ))
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                Err("Command thread terminated unexpectedly".to_string())
            }
        };

        // Clean up PID registration
        {
            let mut procs = STREAMING_PROCESSES.lock().unwrap();
            procs.remove(&session_id);
        }

        result
    }
}

/// Kill a process by PID (used for timeout cleanup)
fn kill_process_by_pid(pid: u32) -> Result<(), String> {
    #[cfg(unix)]
    {
        use std::process::Command as StdCommand;
        let _ = StdCommand::new("kill")
            .arg("-TERM")
            .arg(pid.to_string())
            .output();
        // Give it 2 seconds, then force kill
        let pid_str = pid.to_string();
        std::thread::spawn(move || {
            std::thread::sleep(std::time::Duration::from_secs(2));
            let _ = StdCommand::new("kill")
                .arg("-KILL")
                .arg(&pid_str)
                .output();
        });
    }
    #[cfg(windows)]
    {
        use std::process::Command as StdCommand;
        let _ = StdCommand::new("taskkill")
            .args(["/PID", &pid.to_string(), "/F"])
            .output();
    }
    Ok(())
}

/// Check if a command would be auto-backgrounded (for UI hints)
#[tauri::command]
pub fn check_backgroundable(command: String) -> bool {
    is_persistent_server(&command)
}

/// Get shell/platform information (Tauri command)
#[tauri::command]
pub fn get_shell_info_cmd() -> serde_json::Value {
    get_shell_info()
}

// ─── Streaming Shell Execution ────────────────────────────────────────────

/// Spawn a shell command with streaming output via Tauri events.
/// Lines from stdout/stderr are emitted in real-time as `shell-stream` events.
/// When the process exits, a `done` event is emitted with exit code and duration.
#[tauri::command]
pub fn spawn_stream_shell(
    app_handle: tauri::AppHandle,
    session_id: String,
    command: String,
    cwd: Option<String>,
) -> Result<(), String> {
    validate_command(&command)?;

    let shell_path = get_user_shell();
    let shell_arg = get_shell_arg();

    let mut cmd = Command::new(&shell_path);
    #[cfg(windows)]
    cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
    cmd.arg(shell_arg)
        .arg(&command)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .stdin(Stdio::null());

    if let Some(ref dir) = cwd {
        let path = std::path::Path::new(dir);
        if path.exists() && path.is_dir() {
            cmd.current_dir(path);
        }
    }

    let mut child = cmd.spawn().map_err(|e| format!("Failed to spawn: {}", e))?;

    // Register PID for Ctrl+C support
    let pid = child.id();
    {
        let mut procs = STREAMING_PROCESSES.lock().unwrap();
        procs.insert(session_id.clone(), pid);
    }

    let sid = session_id.clone();
    let app = app_handle.clone();
    let _cmd_for_done = command.clone();
    let start = Instant::now();

    // Read stdout in a thread (platform-aware encoding)
    let sid_out = sid.clone();
    let app_out = app_handle.clone();
    if let Some(stdout) = child.stdout.take() {
        std::thread::spawn(move || {
            #[cfg(windows)]
            {
                // Windows: read bytes, decode as GBK→UTF-8
                let decoder = DecodeReaderBytesBuilder::new()
                    .encoding(Some(GBK))
                    .build(stdout);
                let reader = BufReader::new(decoder);
                let mut buffer = String::new();
                let mut last_emit = Instant::now();

                for line in reader.lines() {
                    match line {
                        Ok(text) => {
                            buffer.push_str(&text);
                            buffer.push('\n');

                            if buffer.len() > 4096 || last_emit.elapsed().as_millis() > 50 {
                                let event = StreamEvent {
                                    session_id: sid_out.clone(),
                                    kind: "stdout".into(),
                                    data: buffer.clone(),
                                    exit_code: None,
                                    duration_ms: None,
                                };
                                let _ = app_out.emit("shell-stream", &event);
                                buffer.clear();
                                last_emit = Instant::now();
                            }
                        }
                        Err(_) => break,
                    }
                }
                if !buffer.is_empty() {
                    let event = StreamEvent {
                        session_id: sid_out.clone(),
                        kind: "stdout".into(),
                        data: buffer,
                        exit_code: None,
                        duration_ms: None,
                    };
                    let _ = app_out.emit("shell-stream", &event);
                }
            }
            #[cfg(not(windows))]
            {
                // Non-Windows: standard UTF-8 line reading
                let reader = BufReader::new(stdout);
                let mut buffer = String::new();
                let mut last_emit = Instant::now();

                for line in reader.lines() {
                    match line {
                        Ok(text) => {
                            buffer.push_str(&text);
                            buffer.push('\n');

                            if buffer.len() > 4096 || last_emit.elapsed().as_millis() > 50 {
                                let event = StreamEvent {
                                    session_id: sid_out.clone(),
                                    kind: "stdout".into(),
                                    data: buffer.clone(),
                                    exit_code: None,
                                    duration_ms: None,
                                };
                                let _ = app_out.emit("shell-stream", &event);
                                buffer.clear();
                                last_emit = Instant::now();
                            }
                        }
                        Err(_) => break,
                    }
                }
                if !buffer.is_empty() {
                    let event = StreamEvent {
                        session_id: sid_out.clone(),
                        kind: "stdout".into(),
                        data: buffer,
                        exit_code: None,
                        duration_ms: None,
                    };
                    let _ = app_out.emit("shell-stream", &event);
                }
            }
        });
    }

    // Read stderr in a thread (platform-aware encoding + noise filtering)
    let sid_err = sid.clone();
    let app_err = app_handle.clone();
    if let Some(stderr) = child.stderr.take() {
        std::thread::spawn(move || {
            #[cfg(windows)]
            {
                // Windows: read bytes, decode as GBK→UTF-8
                let decoder = DecodeReaderBytesBuilder::new()
                    .encoding(Some(GBK))
                    .build(stderr);
                let reader = BufReader::new(decoder);
                let mut buffer = String::new();
                let mut last_emit = Instant::now();

                for line in reader.lines() {
                    match line {
                        Ok(text) => {
                            // Filter shell initialization noise
                            if text.contains("command not found: compdef")
                                || text.contains("command not found: compinit")
                                || text.contains("bash: compgen: command not found")
                                || text.contains("bash: complete: command not found")
                            {
                                continue;
                            }

                            buffer.push_str(&text);
                            buffer.push('\n');

                            if buffer.len() > 4096 || last_emit.elapsed().as_millis() > 50 {
                                let event = StreamEvent {
                                    session_id: sid_err.clone(),
                                    kind: "stderr".into(),
                                    data: buffer.clone(),
                                    exit_code: None,
                                    duration_ms: None,
                                };
                                let _ = app_err.emit("shell-stream", &event);
                                buffer.clear();
                                last_emit = Instant::now();
                            }
                        }
                        Err(_) => break,
                    }
                }
                if !buffer.is_empty() {
                    let event = StreamEvent {
                        session_id: sid_err.clone(),
                        kind: "stderr".into(),
                        data: buffer,
                        exit_code: None,
                        duration_ms: None,
                    };
                    let _ = app_err.emit("shell-stream", &event);
                }
            }
            #[cfg(not(windows))]
            {
                // Non-Windows: standard UTF-8 line reading
                let reader = BufReader::new(stderr);
                let mut buffer = String::new();
                let mut last_emit = Instant::now();

                for line in reader.lines() {
                    match line {
                        Ok(text) => {
                            // Filter shell initialization noise
                            if text.contains("command not found: compdef")
                                || text.contains("command not found: compinit")
                                || text.contains("bash: compgen: command not found")
                                || text.contains("bash: complete: command not found")
                            {
                                continue;
                            }

                            buffer.push_str(&text);
                            buffer.push('\n');

                            if buffer.len() > 4096 || last_emit.elapsed().as_millis() > 50 {
                                let event = StreamEvent {
                                    session_id: sid_err.clone(),
                                    kind: "stderr".into(),
                                    data: buffer.clone(),
                                    exit_code: None,
                                    duration_ms: None,
                                };
                                let _ = app_err.emit("shell-stream", &event);
                                buffer.clear();
                                last_emit = Instant::now();
                            }
                        }
                        Err(_) => break,
                    }
                }
                if !buffer.is_empty() {
                    let event = StreamEvent {
                        session_id: sid_err.clone(),
                        kind: "stderr".into(),
                        data: buffer,
                        exit_code: None,
                        duration_ms: None,
                    };
                    let _ = app_err.emit("shell-stream", &event);
                }
            }
        });
    }

    // Wait for process to finish in a thread, then emit "done"
    std::thread::spawn(move || {
        let status = child.wait();
        let elapsed = start.elapsed().as_millis() as u64;

        // Remove from registry
        {
            let mut procs = STREAMING_PROCESSES.lock().unwrap();
            procs.remove(&sid);
        }

        let (exit_code, success) = match status {
            Ok(s) => (s.code().unwrap_or(-1), s.success()),
            Err(_e) => (-1, false),
        };

        let event = StreamEvent {
            session_id: sid.clone(),
            kind: "done".into(),
            data: if success {
                String::new()
            } else {
                format!("Command exited with code {}", exit_code)
            },
            exit_code: Some(exit_code),
            duration_ms: Some(elapsed),
        };
        let _ = app.emit("shell-stream", &event);
    });

    Ok(())
}

/// Kill a running streaming process by session ID.
#[tauri::command]
pub fn kill_stream_shell(session_id: String) -> Result<(), String> {
    let mut procs = STREAMING_PROCESSES.lock().unwrap();
    if let Some(pid) = procs.remove(&session_id) {
        // Send SIGTERM on Unix, TerminateProcess on Windows
        #[cfg(unix)]
        {
            use std::process::Command as StdCommand;
            // Try SIGTERM first, then SIGKILL after 2s
            let _ = StdCommand::new("kill")
                .arg("-TERM")
                .arg(pid.to_string())
                .output();
            // Give it 2 seconds, then force kill
            let pid_str = pid.to_string();
            std::thread::spawn(move || {
                std::thread::sleep(std::time::Duration::from_secs(2));
                let _ = StdCommand::new("kill")
                    .arg("-KILL")
                    .arg(&pid_str)
                    .output();
            });
        }
        #[cfg(windows)]
        {
            use std::process::Command as StdCommand;
            let _ = StdCommand::new("taskkill")
                .args(["/PID", &pid.to_string(), "/F"])
                .output();
        }
        Ok(())
    } else {
        Err(format!("No running process for session: {}", session_id))
    }
}

/// List all currently running streaming processes.
#[tauri::command]
pub fn list_stream_shells() -> Vec<serde_json::Value> {
    let procs = STREAMING_PROCESSES.lock().unwrap();
    procs
        .iter()
        .map(|(sid, pid)| {
            serde_json::json!({
                "session_id": sid,
                "pid": pid,
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_simple_command() {
        let result = execute_shell_internal("echo hello", None, 5000, false).unwrap();
        assert!(result.success);
        assert!(result.stdout.contains("hello"));
    }
    
    #[test]
    fn test_pwd() {
        let result = execute_shell_internal("pwd", None, 5000, false).unwrap();
        assert!(result.success);
        assert!(!result.stdout.is_empty());
    }
    
    #[test]
    fn test_dangerous_command_rejected() {
        let result = execute_shell_internal("rm -rf /", None, 5000, false);
        assert!(result.is_err());
    }
}