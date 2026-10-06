CREATE TABLE IF NOT EXISTS chat_session (
    id VARCHAR(64) PRIMARY KEY,
    agent_id VARCHAR(64),
    user_id VARCHAR(64),
    title VARCHAR(255),
    message_count INT DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content CLOB,
    tool_name VARCHAR(128),
    tool_call_id VARCHAR(64),
    priority VARCHAR(32) DEFAULT 'LOW',
    token_count INT DEFAULT 0,
    created_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chat_message_session ON chat_message(session_id);

CREATE TABLE IF NOT EXISTS chat_milestone (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    type VARCHAR(32),
    content CLOB,
    created_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chat_milestone_session ON chat_milestone(session_id);

CREATE TABLE IF NOT EXISTS core_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64),
    scope VARCHAR(32),
    category VARCHAR(64),
    title VARCHAR(255),
    keywords VARCHAR(512),
    content CLOB,
    priority INT DEFAULT 0,
    source_session_id VARCHAR(64),
    use_count INT DEFAULT 0,
    created_at TIMESTAMP,
    last_used_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_core_memory_user ON core_memory(user_id);

CREATE TABLE IF NOT EXISTS ssh_connection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id VARCHAR(64) NOT NULL UNIQUE,
    connection_name VARCHAR(255),
    host VARCHAR(255),
    port INT DEFAULT 22,
    username VARCHAR(128),
    auth_type VARCHAR(16),
    password VARCHAR(512),
    private_key CLOB,
    encrypted INT DEFAULT 0,
    status VARCHAR(32) DEFAULT 'disconnected',
    user_id VARCHAR(64),
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    deleted INT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ssh_connection_user ON ssh_connection(user_id);

CREATE TABLE IF NOT EXISTS ssh_connection_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id VARCHAR(64) NOT NULL UNIQUE,
    connect_timeout INT DEFAULT 10000,
    keepalive_interval INT DEFAULT 30,
    startup_command VARCHAR(512),
    compression INT DEFAULT 0,
    strict_host_key_check INT DEFAULT 1,
    known_hosts CLOB,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ssh_connection_config_connection ON ssh_connection_config(connection_id);

CREATE TABLE IF NOT EXISTS model_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    base_url VARCHAR(255) NOT NULL,
    api_key VARCHAR(512) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    completions_path VARCHAR(255) NOT NULL DEFAULT 'v1/chat/completions',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agent_run (
    run_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64),
    agent_id VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    stop_reason VARCHAR(64),
    round INT DEFAULT 0,
    total_tool_calls INT DEFAULT 0,
    total_tokens BIGINT DEFAULT 0,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    cancelled BOOLEAN DEFAULT FALSE,
    cancel_reason VARCHAR(255),
    context_compressed BOOLEAN DEFAULT FALSE,
    max_rounds INT DEFAULT 0,
    max_tool_calls_per_round INT DEFAULT 0,
    max_ai_retries INT DEFAULT 0,
    idle_timeout_ms BIGINT DEFAULT 0,
    max_token_budget INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_agent_run_session ON agent_run(session_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_status ON agent_run(status);

CREATE TABLE IF NOT EXISTS code_patch (
    patch_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64),
    session_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64),
    file_path VARCHAR(1024) NOT NULL,
    backup_path VARCHAR(1024),
    unified_diff CLOB,
    status VARCHAR(32) NOT NULL DEFAULT 'APPLIED',
    lines_added INT DEFAULT 0,
    lines_removed INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_code_patch_session ON code_patch(session_id);
CREATE INDEX IF NOT EXISTS idx_code_patch_run ON code_patch(run_id);
CREATE INDEX IF NOT EXISTS idx_code_patch_status ON code_patch(status);

CREATE TABLE IF NOT EXISTS long_term_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64),
    memory_type VARCHAR(50) NOT NULL,
    memory_key VARCHAR(128) NOT NULL,
    content CLOB NOT NULL,
    keywords VARCHAR(512),
    source_role VARCHAR(20),
    confidence DECIMAL(5,2) DEFAULT 0.50,
    hit_count INT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_long_term_memory_user_type_key UNIQUE (user_id, memory_type, memory_key)
);
CREATE INDEX IF NOT EXISTS idx_long_term_memory_user_time ON long_term_memory(user_id, updated_at);
