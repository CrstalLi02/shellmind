// Learn more about Tauri commands at https://tauri.app/develop/calling-rust/
mod cli_register;
mod local_pty;
mod shell_exec;
mod local_http_server;
mod agent_runtime;

use tauri::Manager;

#[tauri::command]
fn greet(name: &str) -> String {
    format!("Hello, {}! You've been greeted from Rust!", name)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            // Start the local HTTP server (used by Spring Boot LocalExecuteAdkTool)
            local_http_server::start_local_server(app.handle().clone());
            if let Err(error) = agent_runtime::start_agent_runtime(app.handle().clone(), None) {
                eprintln!("[AgentRuntime] startup failed: {error}");
            }
            #[cfg(unix)]
            {
                let app_handle = app.handle().clone();
                tauri::async_runtime::spawn(async move {
                    let mut sigterm = tokio::signal::unix::signal(
                        tokio::signal::unix::SignalKind::terminate(),
                    )
                    .expect("failed to register SIGTERM handler");
                    sigterm.recv().await;
                    app_handle.exit(0);
                });
            }
            Ok(())
        })
        .on_window_event(|_window, event| {
            if matches!(event, tauri::WindowEvent::Destroyed) {
                let _ = agent_runtime::stop_agent_runtime();
            }
        })
        .invoke_handler(tauri::generate_handler![
            greet,
            // Local PTY terminal
            local_pty::spawn_local_pty,
            local_pty::write_to_pty,
            local_pty::resize_local_pty,
            local_pty::kill_local_pty,
            local_pty::list_local_ptys,
            // Command execution
            shell_exec::execute_shell_cmd,
            shell_exec::check_backgroundable,
            shell_exec::get_shell_info_cmd,
            shell_exec::spawn_stream_shell,
            shell_exec::kill_stream_shell,
            shell_exec::list_stream_shells,
            // Local server info
            local_http_server::get_local_server_port,
            // Local agent runtime
            agent_runtime::start_agent_runtime,
            agent_runtime::stop_agent_runtime,
            agent_runtime::get_agent_runtime_status,
            // CLI command registration
            cli_register::install_cli_command,
            cli_register::uninstall_cli_command,
            cli_register::check_cli_installed,
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|_app_handle, event| {
            if matches!(event, tauri::RunEvent::Exit) {
                let _ = agent_runtime::stop_agent_runtime();
            }
        });
}
