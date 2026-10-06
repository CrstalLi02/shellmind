//! ShellMind CLI — terminal AI chat entry point
//!
//! Run `shellmind` in a terminal for a Claude Code / OpenCode-style TUI.
//! Skips the Tauri GUI and starts a ratatui TUI that talks to shellmind-server over HTTP SSE.

use clap::Parser;

// Pull in src/cli/ submodules via #[path]
// Use the _cli_ prefix to avoid colliding with module names in lib.rs
#[path = "../cli/app.rs"]
mod _cli_app;
#[path = "../cli/sse_client.rs"]
mod _cli_sse;
#[path = "../cli/tui.rs"]
mod _cli_tui;

use _cli_app::CliArgs;
use _cli_tui::run_cli;

fn main() {
    let args = CliArgs::parse();

    // Set up the tokio runtime
    let rt = tokio::runtime::Runtime::new().expect("Failed to create tokio runtime");
    rt.block_on(run_cli(args));
}
