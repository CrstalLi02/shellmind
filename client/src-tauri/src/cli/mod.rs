//! CLI module entry
//!
//! Core module for ShellMind's terminal AI chat mode.

mod app;
mod sse_client;
mod tui;

pub use app::{App, AppEvent, CliArgs, ReActEvent};
pub use sse_client::build_project_context;
pub use tui::run_cli;
