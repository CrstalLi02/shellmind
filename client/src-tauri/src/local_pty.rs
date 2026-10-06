//! Local PTY Terminal Module for ShellMind
//!
//! Provides real PTY terminal sessions (similar to the IDEA/WebStorm embedded terminal).
//! Uses portable-pty to spawn a login + interactive shell with the full user environment (PATH, nvm, pyenv, etc.).
//!
//! Tauri Commands:
//! - spawn_local_pty: create a PTY session
//! - write_to_pty: write data (keystrokes/commands)
//! - resize_local_pty: resize the terminal
//! - kill_local_pty: close a PTY session
//! - list_local_ptys: list active sessions
//!
//! Events (→ frontend):
//! - "local-pty-output": { session_id, data } — PTY output
//! - "local-pty-exit": { session_id, exit_code } — PTY exit

use std::collections::HashMap;
use std::io::{Read, Write};
use tauri::Emitter;

use portable_pty::{native_pty_system, CommandBuilder, PtySize, MasterPty, Child};

#[cfg(windows)]
use encoding_rs::GBK;
#[cfg(windows)]
use encoding_rs_io::DecodeReaderBytesBuilder;

lazy_static::lazy_static! {
    /// Registry of active PTY sessions
    pub static ref PTY_SESSIONS: tokio::sync::Mutex<HashMap<String, PtySession>> =
        tokio::sync::Mutex::new(HashMap::new());
}

/// PTY session: holds the master PTY, slave handle (keeps the shell alive), writer, and child process
pub struct PtySession {
    master: Box<dyn MasterPty + Send>,
    #[allow(dead_code)]
    slave: Box<dyn portable_pty::SlavePty + Send>,
    writer: Box<dyn std::io::Write + Send>,
    child: Box<dyn Child + Send>,
}

/// Resolve the user's login shell path
#[allow(dead_code)]
fn get_user_shell() -> String {
    #[cfg(not(windows))]
    {
        std::env::var("SHELL").unwrap_or_else(|_| {
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

/// Resolve the user's home directory
fn get_home_dir() -> String {
    #[cfg(not(windows))]
    {
        std::env::var("HOME").unwrap_or_else(|_| "/".to_string())
    }
    #[cfg(windows)]
    {
        std::env::var("USERPROFILE").unwrap_or_else(|_| "C:\\".to_string())
    }
}

/// Create a local PTY terminal session
///
/// Use a login + interactive shell to load the full user environment:
/// - .zprofile / .bash_profile (PATH setup)
/// - .zshrc / .bashrc (nvm, rbenv, pyenv, etc.)
#[tauri::command]
pub async fn spawn_local_pty(
    app_handle: tauri::AppHandle,
    session_id: String,
    cwd: Option<String>,
    cols: Option<u16>,
    rows: Option<u16>,
) -> Result<(), String> {
    let pty_system = native_pty_system();

    let cols_val = cols.unwrap_or(80);
    let rows_val = rows.unwrap_or(24);

    let pty_pair = pty_system
        .openpty(PtySize {
            rows: rows_val,
            cols: cols_val,
            pixel_width: 0,
            pixel_height: 0,
        })
        .map_err(|e| format!("Failed to create PTY: {}", e))?;

    #[cfg(not(windows))]
    let shell = get_user_shell();
    let home = get_home_dir();
    let working_dir = cwd.unwrap_or_else(|| home.clone());

    #[cfg(not(windows))]
    {
        let mut cmd = CommandBuilder::new(&shell);
        cmd.arg("-l"); // login shell — load .zprofile/.bash_profile
        cmd.arg("-i"); // interactive shell — load .zshrc/.bashrc
        cmd.cwd(&working_dir);

        cmd.env("HOME", &home);
        cmd.env("TERM", "xterm-256color");
        cmd.env("SHELL", &shell);

        let child = pty_pair.slave.spawn_command(cmd)
            .map_err(|e| format!("Failed to spawn shell: {}", e))?;

        // Keep a slave handle; otherwise the shell receives SIGHUP and exits on drop
        let slave = pty_pair.slave;

        let master = pty_pair.master;
        let reader = master
            .try_clone_reader()
            .map_err(|e| format!("Failed to clone PTY reader: {}", e))?;
        let writer = master
            .take_writer()
            .map_err(|e| format!("Failed to take PTY writer: {}", e))?;

        let session = PtySession { master, slave, writer, child };

        {
            let mut sessions = PTY_SESSIONS.lock().await;
            sessions.insert(session_id.clone(), session);
        }

        // Background thread reads PTY output and pushes it to the frontend via Tauri events
        let sid = session_id.clone();
        let app = app_handle.clone();
        std::thread::spawn(move || {
            let mut reader = reader;
            let mut buf = [0u8; 4096];
            loop {
                match reader.read(&mut buf) {
                    Ok(0) => {
                        // reader EOF — may be transient; confirm whether the child actually exited
                        // Brief wait, then check child status
                        std::thread::sleep(std::time::Duration::from_millis(50));
                        let child_exited = {
                            let mut sessions = PTY_SESSIONS.blocking_lock();
                            if let Some(session) = sessions.get_mut(&sid) {
                                session.child.try_wait().ok().flatten().is_some()
                            } else {
                                true // session is gone; treat as exited
                            }
                        };
                        if child_exited {
                            let exit_code = {
                                let mut sessions = PTY_SESSIONS.blocking_lock();
                                sessions.get_mut(&sid)
                                    .and_then(|s| s.child.try_wait().ok().flatten())
                                    .map(|status| status.exit_code())
                                    .unwrap_or(0)
                            };
                            let _ = app.emit("local-pty-exit", serde_json::json!({
                                "session_id": sid,
                                "exit_code": exit_code,
                            }));
                            break;
                        }
                        // Child is still alive; keep reading (the reader may recover)
                        continue;
                    }
                    Ok(n) => {
                        #[cfg(windows)]
                        let data = {
                            let mut decoder = DecodeReaderBytesBuilder::new()
                                .encoding(Some(GBK))
                                .build(&buf[..n]);
                            let mut decoded = String::new();
                            if decoder.read_to_string(&mut decoded).is_ok() {
                                decoded
                            } else {
                                String::from_utf8_lossy(&buf[..n]).to_string()
                            }
                        };
                        #[cfg(not(windows))]
                        let data = String::from_utf8_lossy(&buf[..n]).to_string();

                        let _ = app.emit("local-pty-output", serde_json::json!({
                            "session_id": sid,
                            "data": data,
                        }));
                    }
                    Err(e) => {
                        if e.kind() != std::io::ErrorKind::Interrupted {
                            // Check whether the child actually exited
                            let child_exited = {
                                let mut sessions = PTY_SESSIONS.blocking_lock();
                                if let Some(session) = sessions.get_mut(&sid) {
                                    session.child.try_wait().ok().flatten().is_some()
                                } else {
                                    true
                                }
                            };
                            if child_exited {
                                let _ = app.emit("local-pty-exit", serde_json::json!({
                                    "session_id": sid,
                                    "exit_code": -1,
                                }));
                                break;
                            }
                            // Child is still alive; keep reading
                            continue;
                        }
                    }
                }
            }

            // Remove the session from the registry
            {
                let mut sessions = PTY_SESSIONS.blocking_lock();
                sessions.remove(&sid);
            }
        });
    }

    #[cfg(windows)]
    {
        // Windows: prefer pwsh → powershell → cmd
        let shell_path = which::which("pwsh.exe")
            .or_else(|_| which::which("powershell.exe"))
            .unwrap_or_else(|_| std::path::PathBuf::from("cmd.exe"));

        let mut cmd = CommandBuilder::new(&shell_path);
        cmd.cwd(&working_dir);
        cmd.env("HOME", &home);
        cmd.env("USERPROFILE", &home);
        cmd.env("TERM", "xterm-256color");

        if shell_path.file_name().and_then(|s| s.to_str())
            .map(|s| s.contains("powershell") || s.contains("pwsh"))
            .unwrap_or(false)
        {
            cmd.env("PSExecutionPolicyPreference", "RemoteSigned");
            cmd.env("PYTHONIOENCODING", "utf-8");
        }

        let child = pty_pair.slave.spawn_command(cmd)
            .map_err(|e| format!("Failed to spawn shell ({}): {}", shell_path.display(), e))?;

        // Keep a slave handle; otherwise the shell exits on drop
        let slave = pty_pair.slave;

        let master = pty_pair.master;
        let reader = master
            .try_clone_reader()
            .map_err(|e| format!("Failed to clone PTY reader: {}", e))?;
        let writer = master
            .take_writer()
            .map_err(|e| format!("Failed to take PTY writer: {}", e))?;

        // Windows: set console encoding to UTF-8
        {
            let _ = writer.write_all(b"chcp 65001 >nul\r\n");
            let _ = writer.flush();
            let _ = writer.write_all(b"[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; $OutputEncoding = [System.Text.Encoding]::UTF8\r\n");
            let _ = writer.flush();
        }

        let session = PtySession { master, slave, writer, child };

        {
            let mut sessions = PTY_SESSIONS.lock().await;
            sessions.insert(session_id.clone(), session);
        }

        let sid = session_id.clone();
        let app = app_handle.clone();
        std::thread::spawn(move || {
            let mut reader = reader;
            let mut buf = [0u8; 4096];
            loop {
                match reader.read(&mut buf) {
                    Ok(0) => {
                        // reader EOF — confirm whether the child actually exited
                        std::thread::sleep(std::time::Duration::from_millis(50));
                        let child_exited = {
                            let mut sessions = PTY_SESSIONS.blocking_lock();
                            if let Some(session) = sessions.get_mut(&sid) {
                                session.child.try_wait().ok().flatten().is_some()
                            } else {
                                true
                            }
                        };
                        if child_exited {
                            let exit_code = {
                                let mut sessions = PTY_SESSIONS.blocking_lock();
                                sessions.get_mut(&sid)
                                    .and_then(|s| s.child.try_wait().ok().flatten())
                                    .map(|status| status.exit_code())
                                    .unwrap_or(0)
                            };
                            let _ = app.emit("local-pty-exit", serde_json::json!({
                                "session_id": sid,
                                "exit_code": exit_code,
                            }));
                            break;
                        }
                        continue;
                    }
                    Ok(n) => {
                        let data = {
                            let mut decoder = DecodeReaderBytesBuilder::new()
                                .encoding(Some(GBK))
                                .build(&buf[..n]);
                            let mut decoded = String::new();
                            if decoder.read_to_string(&mut decoded).is_ok() {
                                decoded
                            } else {
                                String::from_utf8_lossy(&buf[..n]).to_string()
                            }
                        };

                        let _ = app.emit("local-pty-output", serde_json::json!({
                            "session_id": sid,
                            "data": data,
                        }));
                    }
                    Err(e) => {
                        if e.kind() != std::io::ErrorKind::Interrupted {
                            let child_exited = {
                                let mut sessions = PTY_SESSIONS.blocking_lock();
                                if let Some(session) = sessions.get_mut(&sid) {
                                    session.child.try_wait().ok().flatten().is_some()
                                } else {
                                    true
                                }
                            };
                            if child_exited {
                                let _ = app.emit("local-pty-exit", serde_json::json!({
                                    "session_id": sid,
                                    "exit_code": -1,
                                }));
                                break;
                            }
                            continue;
                        }
                    }
                }
            }
            {
                let mut sessions = PTY_SESSIONS.blocking_lock();
                sessions.remove(&sid);
            }
        });
    }

    Ok(())
}

/// Write data to the PTY (keystrokes, AI commands, etc.)
#[tauri::command]
pub async fn write_to_pty(session_id: String, data: String) -> Result<(), String> {
    let mut sessions = PTY_SESSIONS.lock().await;
    let session = sessions
        .get_mut(&session_id)
        .ok_or_else(|| format!("No PTY session found: {}", session_id))?;

    session.writer
        .write_all(data.as_bytes())
        .map_err(|e| format!("Failed to write to PTY: {}", e))?;
    session.writer.flush().ok();

    Ok(())
}

/// Resize the PTY terminal
#[tauri::command]
pub async fn resize_local_pty(session_id: String, cols: u16, rows: u16) -> Result<(), String> {
    let mut sessions = PTY_SESSIONS.lock().await;
    let session = sessions
        .get_mut(&session_id)
        .ok_or_else(|| format!("No PTY session found: {}", session_id))?;

    session.master
        .resize(PtySize {
            rows,
            cols,
            pixel_width: 0,
            pixel_height: 0,
        })
        .map_err(|e| format!("Failed to resize PTY: {}", e))?;

    Ok(())
}

/// Close a PTY session
#[tauri::command]
pub async fn kill_local_pty(session_id: String) -> Result<(), String> {
    let mut sessions = PTY_SESSIONS.lock().await;
    if let Some(mut session) = sessions.remove(&session_id) {
        let _ = session.child.kill();
        drop(session);
    }
    Ok(())
}

/// List all active PTY sessions
#[tauri::command]
pub async fn list_local_ptys() -> Vec<serde_json::Value> {
    let sessions = PTY_SESSIONS.lock().await;
    sessions
        .keys()
        .map(|sid| {
            serde_json::json!({
                "session_id": sid,
            })
        })
        .collect()
}
