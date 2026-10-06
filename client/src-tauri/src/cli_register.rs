//! CLI command registration
//!
//! Creates a symlink `/usr/local/bin/shellmind-cli → app_path` on PATH
//! so `shellmind-cli` can be run from a terminal.
//! Mirrors VS Code's `Shell Command: Install 'code' command in PATH`.

use std::fs;
use std::os::unix::fs::symlink;
use std::path::PathBuf;
use std::process::Command;

/// Symlink target path on macOS
const SYMLINK_PATH: &str = "/usr/local/bin/shellmind-cli";

/// Install the CLI command on PATH
///
/// 1. Locate the sibling shellmind-cli binary (standalone CLI TUI entry)
/// 2. Try creating the symlink directly when /usr/local/bin is writable
/// 3. If permission is denied, prompt for admin rights via osascript
#[tauri::command]
pub fn install_cli_command() -> Result<String, String> {
    // current_exe is the Tauri GUI binary (shellmind-client);
    // the CLI binary (shellmind-cli) lives in the same directory
    let gui_path = std::env::current_exe().map_err(|e| format!("Failed to get app path: {}", e))?;
    let cli_path = gui_path.parent()
        .ok_or_else(|| "Could not get parent directory".to_string())?
        .join("shellmind-cli");

    // Ensure the CLI binary exists
    if !cli_path.exists() {
        return Err(format!("CLI binary not found: {}", cli_path.display()));
    }

    let app_path = cli_path;

    // If the symlink already points at the same file, treat it as success
    if let Ok(existing_target) = fs::read_link(SYMLINK_PATH) {
        if existing_target == app_path {
            return Ok("shellmind-cli is already installed".to_string());
        }
        // Points at a different file; remove the old symlink first
        fs::remove_file(SYMLINK_PATH).map_err(|e| format!("Failed to remove old symlink: {}", e))?;
    }

    // Try writing directly (/usr/local/bin is writable on some macOS setups)
    if try_direct_install(&app_path) {
        return Ok("shellmind-cli installed on PATH".to_string());
    }

    // Direct write failed; request admin rights via osascript
    install_with_elevation(&app_path)
}

/// Remove the CLI command from PATH
#[tauri::command]
pub fn uninstall_cli_command() -> Result<String, String> {
    let symlink_path = PathBuf::from(SYMLINK_PATH);

    if !symlink_path.exists() && !symlink_path.is_symlink() {
        return Ok("shellmind-cli is not installed".to_string());
    }

    // Try deleting directly
    if try_direct_uninstall() {
        return Ok("shellmind-cli removed from PATH".to_string());
    }

    // Direct delete failed; request admin rights via osascript
    uninstall_with_elevation()
}

/// Check whether the CLI command is installed
#[tauri::command]
pub fn check_cli_installed() -> bool {
    let symlink_path = PathBuf::from(SYMLINK_PATH);
    symlink_path.is_symlink() || symlink_path.exists()
}

// ── Internals ──

/// Try creating the symlink without sudo
fn try_direct_install(app_path: &PathBuf) -> bool {
    // Remove an existing file/symlink first (it may point at an older build)
    if PathBuf::from(SYMLINK_PATH).exists() || PathBuf::from(SYMLINK_PATH).is_symlink() {
        if fs::remove_file(SYMLINK_PATH).is_err() {
            return false; // could not delete; likely no write permission
        }
    }

    symlink(app_path, SYMLINK_PATH).is_ok()
}

/// Create the symlink via a macOS admin prompt (osascript)
fn install_with_elevation(app_path: &PathBuf) -> Result<String, String> {
    // Force-create/replace the symlink with ln -sf
    let script = format!(
        "do shell script \"ln -sf '{}' {}\" with administrator privileges",
        app_path.display(),
        SYMLINK_PATH
    );

    let output = Command::new("osascript")
        .args(["-e", &script])
        .output()
        .map_err(|e| format!("Failed to run osascript: {}", e))?;

    if output.status.success() {
        Ok("shellmind-cli installed on PATH".to_string())
    } else {
        let stderr = String::from_utf8_lossy(&output.stderr);
        Err(format!("Install failed (admin permission required): {}", stderr.trim()))
    }
}

/// Try deleting the symlink without sudo
fn try_direct_uninstall() -> bool {
    fs::remove_file(SYMLINK_PATH).is_ok()
}

/// Delete the symlink via a macOS admin prompt (osascript)
fn uninstall_with_elevation() -> Result<String, String> {
    let script = format!(
        "do shell script \"rm -f {}\" with administrator privileges",
        SYMLINK_PATH
    );

    let output = Command::new("osascript")
        .args(["-e", &script])
        .output()
        .map_err(|e| format!("Failed to run osascript: {}", e))?;

    if output.status.success() {
        Ok("shellmind-cli removed from PATH".to_string())
    } else {
        let stderr = String::from_utf8_lossy(&output.stderr);
        Err(format!("Uninstall failed (admin permission required): {}", stderr.trim()))
    }
}
