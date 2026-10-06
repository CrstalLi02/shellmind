use once_cell::sync::Lazy;
use serde::Serialize;
use std::{
    fs::{self, OpenOptions},
    io::{BufRead, BufReader},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{atomic::{AtomicU64, Ordering}, Mutex},
    thread,
    time::{Duration, Instant},
};
use tauri::Manager;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentRuntimeStatus {
    pub running: bool,
    pub port: u16,
    pub pid: Option<u32>,
    pub token: Option<String>,
    pub jar_path: Option<String>,
    pub java_path: Option<String>,
    pub java_version: Option<String>,
    pub last_error: Option<String>,
    pub workspace: Option<String>,
    /// Startup stage: init / resolve / prepare / spawn / booting / ready / error
    pub stage: String,
    /// Human-readable stage description for the startup progress UI
    pub stage_message: String,
}

struct RuntimeState {
    child: Option<Child>,
    status: AgentRuntimeStatus,
    generation: u64,
    starting: bool,
}

static GENERATION: AtomicU64 = AtomicU64::new(1);

/// Runtime connection file path (~/.shellmind/runtime.json), read by shellmind-cli
fn runtime_info_path() -> Option<PathBuf> {
    dirs::home_dir().map(|home| home.join(".shellmind").join("runtime.json"))
}

/// Persist connection info (port + token) after the embedded server is ready so the CLI can connect
fn persist_runtime_info(port: u16, token: &str, pid: u32) {
    let Some(path) = runtime_info_path() else { return };
    if port == 0 || token.is_empty() {
        return;
    }
    let payload = serde_json::json!({
        "port": port,
        "token": token,
        "pid": pid,
        "startedAt": std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0),
    });
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let tmp = path.with_extension("json.tmp");
    if fs::write(&tmp, payload.to_string()).is_ok() {
        let _ = fs::rename(&tmp, &path);
    }
}

/// Remove connection info after the server stops so the CLI does not hit a stale port
fn remove_runtime_info() {
    if let Some(path) = runtime_info_path() {
        let _ = fs::remove_file(path);
    }
}
static STATE: Lazy<Mutex<RuntimeState>> = Lazy::new(|| {
    Mutex::new(RuntimeState {
        child: None,
        status: default_status(None),
        generation: 0,
        starting: false,
    })
});

fn default_status(last_error: Option<String>) -> AgentRuntimeStatus {
    AgentRuntimeStatus {
        running: false,
        port: 0,
        pid: None,
        token: None,
        jar_path: None,
        java_path: None,
        java_version: None,
        last_error,
        workspace: None,
        stage: "init".into(),
        stage_message: "Waiting to start".into(),
    }
}

/// Update the current startup stage for the frontend progress UI
fn set_stage(state: &mut RuntimeState, stage: &str, message: &str) {
    state.status.stage = stage.to_string();
    state.status.stage_message = message.to_string();
}

/// Record a startup failure: set stage to error and store a readable message
fn fail_stage(state: &mut RuntimeState, message: &str) {
    state.status.running = false;
    state.starting = false;
    state.status.last_error = Some(message.to_string());
    set_stage(state, "error", message);
}

#[tauri::command]
pub fn start_agent_runtime(
    app: tauri::AppHandle,
    workspace: Option<String>,
) -> Result<AgentRuntimeStatus, String> {
    eprintln!("[AgentRuntime] start requested workspace={workspace:?}");
    let mut state = STATE.lock().map_err(|_| "agent runtime state poisoned")?;

    if let Some(child) = state.child.as_mut() {
        if let Ok(Some(_)) = child.try_wait() {
            state.child = None;
            state.status.running = false;
            state.status.last_error = Some("Agent process has exited".into());
        }
    }
    if state.status.running {
        return Ok(state.status.clone());
    }
    if state.starting {
        return Ok(state.status.clone());
    }

    state.starting = true;
    set_stage(&mut state, "resolve", "Checking runtime (Java / agent package)…");

    let jar_path = match resolve_jar_path(&app) {
        Ok(path) => path,
        Err(error) => {
            fail_stage(&mut state, &format!("Agent package not found: {error}"));
            return Err(state.status.last_error.clone().unwrap_or_default());
        }
    };
    let java_runtime = match resolve_java_runtime(&app) {
        Ok(runtime) => runtime,
        Err(error) => {
            fail_stage(&mut state, &error);
            return Err(error);
        }
    };
    let workspace = workspace
        .filter(|value| !value.trim().is_empty())
        .map(PathBuf::from)
        .filter(|value| value.is_dir())
        .or_else(|| dirs::home_dir());
    let workspace = match workspace {
        Some(dir) => dir,
        None => {
            fail_stage(&mut state, "Could not determine the local workspace directory");
            return Err(state.status.last_error.clone().unwrap_or_default());
        }
    };

    set_stage(&mut state, "prepare", "Preparing data directory and log files…");
    let data_dir = match app.path().app_data_dir() {
        Ok(dir) => dir,
        Err(error) => {
            let message = format!("Could not locate the app data directory: {}", error);
            fail_stage(&mut state, &message);
            return Err(message);
        }
    };
    if let Err(error) = fs::create_dir_all(&data_dir) {
        let message = format!("Failed to create the app data directory: {}", error);
        fail_stage(&mut state, &message);
        return Err(message);
    }
    let log_dir = data_dir.join("logs");
    if let Err(error) = fs::create_dir_all(&log_dir) {
        let message = format!("Failed to create the log directory: {}", error);
        fail_stage(&mut state, &message);
        return Err(message);
    }
    let log_file = match OpenOptions::new()
        .create(true)
        .append(true)
        .open(log_dir.join("agent.log"))
    {
        Ok(file) => file,
        Err(error) => {
            let message = format!("Failed to open the agent log: {}", error);
            fail_stage(&mut state, &message);
            return Err(message);
        }
    };

    let token = generate_token();
    let generation = GENERATION.fetch_add(1, Ordering::SeqCst);

    // Port conflict check: pre-allocate a free port and pass it to the server so a random port is not already taken
    let assigned_port = match find_available_port() {
        Some(port) => port,
        None => {
            fail_stage(&mut state, "Port conflict check failed: no free port found, check the local network environment");
            return Err(state.status.last_error.clone().unwrap_or_default());
        }
    };
    eprintln!("[AgentRuntime] assigned port={assigned_port}");

    // H2 JDBC URLs need forward slashes; normalize Windows paths to avoid backslash escaping
    let database_path = data_dir
        .join("shellmind")
        .to_string_lossy()
        .replace('\\', "/");
    let database_url = format!(
        "jdbc:h2:file:{};MODE=MySQL;AUTO_SERVER=TRUE",
        database_path
    );
    let mut command = Command::new(&java_runtime.path);
    command
        .arg(format!("-Dspring.datasource.url={}", database_url))
        .arg("-jar")
        .arg(&jar_path)
        .arg("--spring.profiles.active=local")
        .arg(format!("--server.port={}", assigned_port))
        .arg(format!("--shellmind.local.token={}", token))
        .arg(format!("--shellmind.local.workspace={}", workspace.display()))
        .env("SHELLMIND_WORKSPACE", &workspace)
        .current_dir(&data_dir)
        .stdout(Stdio::piped())
        .stderr(Stdio::from(match log_file.try_clone() {
            Ok(file) => file,
            Err(error) => {
                let message = format!("Failed to duplicate the log handle: {}", error);
                fail_stage(&mut state, &message);
                return Err(message);
            }
        }))
        .stdin(Stdio::null());
    // Avoid a console window popping up on Windows
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000); // CREATE_NO_WINDOW
    }

    set_stage(&mut state, "spawn", "Starting the local agent process…");
    let mut child = match command.spawn() {
        Ok(child) => child,
        Err(error) => {
            let message = format!("Failed to start shellmind-server.jar: {}", error);
            fail_stage(&mut state, &message);
            return Err(message);
        }
    };
    let stdout = match child.stdout.take() {
        Some(stdout) => stdout,
        None => {
            fail_stage(&mut state, "Could not read agent process output; startup aborted");
            return Err(state.status.last_error.clone().unwrap_or_default());
        }
    };
    let pid = child.id();
    eprintln!("[AgentRuntime] spawned pid={pid} java={}", java_runtime.path.display());

    state.child = Some(child);
    state.generation = generation;
    state.status = AgentRuntimeStatus {
        running: true,
        port: 0,
        pid: Some(pid),
        token: Some(token),
        jar_path: Some(jar_path.display().to_string()),
        java_path: Some(java_runtime.path.display().to_string()),
        java_version: java_runtime.version,
        last_error: None,
        workspace: Some(workspace.display().to_string()),
        stage: "booting".into(),
        stage_message: "Agent service is starting; first load may take 5–15 seconds…".into(),
    };

    thread::spawn(move || consume_runtime_output(stdout, generation));
    Ok(state.status.clone())
}

#[tauri::command]
pub fn stop_agent_runtime() -> Result<AgentRuntimeStatus, String> {
    let mut state = STATE.lock().map_err(|_| "agent runtime state poisoned")?;

    if let Some(child) = state.child.take() {
        terminate_child(child);
    }
    remove_runtime_info();

    state.generation = GENERATION.fetch_add(1, Ordering::SeqCst);
    state.starting = false;
    state.status = AgentRuntimeStatus {
        running: false,
        port: 0,
        pid: None,
        token: None,
        jar_path: state.status.jar_path.clone(),
        java_path: state.status.java_path.clone(),
        java_version: state.status.java_version.clone(),
        last_error: None,
        workspace: None,
        stage: "init".into(),
        stage_message: "Stopped".into(),
    };

    Ok(state.status.clone())
}

fn terminate_child(mut child: Child) {
    #[cfg(unix)]
    unsafe {
        libc::kill(child.id() as libc::pid_t, libc::SIGTERM);
    }
    #[cfg(not(unix))]
    let _ = child.kill();

    let deadline = Instant::now() + Duration::from_secs(3);
    while Instant::now() < deadline {
        if let Ok(Some(_)) = child.try_wait() {
            return;
        }
        thread::sleep(Duration::from_millis(50));
    }

    let _ = child.kill();
    let _ = child.wait();
}

#[tauri::command]
pub fn get_agent_runtime_status() -> AgentRuntimeStatus {
    let mut guard = match STATE.lock() {
        Ok(guard) => guard,
        Err(_) => return default_status(Some("agent runtime state poisoned".into())),
    };

    if let Some(child) = guard.child.as_mut() {
        if let Ok(Some(exit_status)) = child.try_wait() {
            guard.child = None;
            guard.status.running = false;
            guard.starting = false;
            guard.status.last_error = Some(format!("Agent process has exited: {}", exit_status));
            set_stage(&mut guard, "error", "Agent process exited unexpectedly");
            remove_runtime_info();
        }
    }
    guard.status.clone()
}

struct JavaRuntime {
    path: PathBuf,
    version: Option<String>,
}

fn consume_runtime_output(stdout: impl std::io::Read, generation: u64) {
    let reader = BufReader::new(stdout);
    for line in reader.lines() {
        let line = match line {
            Ok(value) => value,
            Err(_) => break,
        };

        if let Ok(value) = serde_json::from_str::<serde_json::Value>(&line) {
            if value.get("event").and_then(serde_json::Value::as_str) == Some("ready") {
                let port = value
                    .get("port")
                    .and_then(serde_json::Value::as_u64)
                    .unwrap_or(0) as u16;
                let token = value
                    .get("token")
                    .and_then(serde_json::Value::as_str)
                    .map(str::to_string);
                let pid = value
                    .get("pid")
                    .and_then(serde_json::Value::as_u64)
                    .map(|value| value as u32);

                if let Ok(mut state) = STATE.lock() {
                    if state.generation == generation {
                        state.status.port = port;
                        state.status.pid = pid;
                        state.status.token = token.clone();
                        state.starting = false;
                        set_stage(&mut state, "ready", "Agent service is ready");
                        if let Some(ref token_value) = token {
                            persist_runtime_info(port, token_value, pid.unwrap_or(0));
                        }
                    }
                }
            }
        }
    }

    if let Ok(mut state) = STATE.lock() {
        if state.generation == generation {
            state.status.running = false;
            state.starting = false;
            // If the process exits after ready (a crash other than a user stop), show a clear message
            let was_ready = state.status.stage == "ready";
            state.status.last_error = Some(if was_ready {
                "Agent process has exited".into()
            } else {
                "Agent service failed to start: the process exited before initialization finished; see agent.log in the log directory".into()
            });
            set_stage(&mut state, "error", "Agent process has exited");
            remove_runtime_info();
        }
    }
}

fn generate_token() -> String {
    let mut bytes = [0u8; 32];
    getrandom::getrandom(&mut bytes).expect("failed to generate agent token");
    bytes.iter().map(|byte| format!("{:02x}", byte)).collect()
}

/// Probe a free loopback port: bind, then release it immediately.
/// That port is then passed to Spring Boot (--server.port) to avoid colliding with
/// another process and failing to start the web server.
fn find_available_port() -> Option<u16> {
    for _ in 0..10 {
        if let Ok(socket) = std::net::TcpListener::bind("127.0.0.1:0") {
            let port = socket.local_addr().ok()?.port();
            drop(socket);
            return Some(port);
        }
    }
    None
}

fn resolve_jar_path(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    if let Some(path) = std::env::var_os("SHELLMIND_SERVER_JAR").map(PathBuf::from) {
        return validate_jar(path);
    }

    if let Ok(resource_dir) = app.path().resource_dir() {
        let path = resource_dir.join("agent/shellmind-server.jar");
        if path.is_file() {
            return validate_jar(path);
        }
    }

    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            let candidates = [
                parent.join("shellmind-server.jar"),
                // Windows (NSIS): resources sit beside the exe in the install directory
                parent.join("agent/shellmind-server.jar"),
                parent.join("resources/agent/shellmind-server.jar"),
                // macOS (.app): under Contents/Resources
                parent.join("../Resources/agent/shellmind-server.jar"),
            ];
            for candidate in candidates {
                if candidate.is_file() {
                    return validate_jar(candidate);
                }
            }
        }
    }

    let development_jar = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../server/shellmind-server-app/target/shellmind-server.jar");
    validate_jar(development_jar)
}

fn resolve_java_runtime(app: &tauri::AppHandle) -> Result<JavaRuntime, String> {
    if let Some(path) = std::env::var_os("SHELLMIND_JAVA").map(PathBuf::from) {
        return inspect_java(path);
    }

    if let Ok(path) = which::which(java_binary_name()) {
        if let Ok(runtime) = inspect_java(path) {
            return Ok(runtime);
        }
    }

    if let Ok(resource_dir) = app.path().resource_dir() {
        let path = resource_dir
            .join("agent/runtime/bin")
            .join(java_binary_name());
        if path.is_file() {
            return inspect_java(path);
        }
    }

    let development_runtime = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../resources/agent/runtime/bin")
        .join(java_binary_name());
    if development_runtime.is_file() {
        return inspect_java(development_runtime);
    }

    if let Ok(path) = which::which(java_binary_name()) {
        return inspect_java(path);
    }

    Err("No usable Java 17 runtime found. Bundle agent/runtime or set SHELLMIND_JAVA and rebuild.".into())
}

fn validate_jar(path: PathBuf) -> Result<PathBuf, String> {
    let path = fs::canonicalize(path)
        .map_err(|error| format!("shellmind-server.jar not found: {}", error))?;
    if !path.is_file() {
        return Err(format!("Path is not a file: {}", path.display()));
    }
    Ok(path)
}

fn java_binary_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "java.exe"
    } else {
        "java"
    }
}

fn inspect_java(path: PathBuf) -> Result<JavaRuntime, String> {
    let mut command = Command::new(&path);
    command.arg("-version");
    // Avoid a console window from java -version on Windows
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000); // CREATE_NO_WINDOW
    }
    let output = command
        .output()
        .map_err(|error| format!("Failed to start Java runtime {}: {}", path.display(), error))?;
    let text = format!(
        "{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    if !output.status.success() {
        return Err(format!("Java runtime check failed: {}", path.display()));
    }

    let version = text
        .split("version \"")
        .nth(1)
        .and_then(|rest| rest.split('"').next())
        .map(str::to_string);
    let major = version.as_deref().and_then(parse_java_major);
    if major.map(|value| value < 17).unwrap_or(false) {
        return Err(format!(
            "ShellMind-Study requires Java 17 or later, current version is {}",
            version.unwrap_or_else(|| "unknown version".into())
        ));
    }
    Ok(JavaRuntime { path, version })
}

fn parse_java_major(version: &str) -> Option<u32> {
    let normalized = version.strip_prefix("1.").unwrap_or(version);
    normalized
        .split(['.', '-'])
        .next()
        .and_then(|value| value.parse().ok())
}
