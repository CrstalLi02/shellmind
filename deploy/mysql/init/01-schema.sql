# ShellMind MySQL schema (first-start init for docker-entrypoint-initdb.d)
# Existing databases: apply scripts under deploy/mysql/migrations/ instead.

CREATE DATABASE IF NOT EXISTS `shellmind` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `shellmind`;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL COMMENT 'Session ID',
  `role` varchar(20) NOT NULL COMMENT 'Role: user/assistant/tool/system',
  `content` text COMMENT 'Message body',
  `tool_name` varchar(100) DEFAULT NULL COMMENT 'Tool name',
  `tool_call_id` varchar(100) DEFAULT NULL COMMENT 'Tool call ID',
  `priority` varchar(20) DEFAULT 'MEDIUM' COMMENT 'Priority: CRITICAL/HIGH/MEDIUM/LOW',
  `token_count` int DEFAULT '0' COMMENT 'Estimated token count',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session_time` (`session_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Chat messages';

DROP TABLE IF EXISTS `chat_milestone`;
CREATE TABLE `chat_milestone` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL COMMENT 'Session ID',
  `type` varchar(30) NOT NULL COMMENT 'Type: TASK_CHANGE/ERROR/DECISION/...',
  `content` text COMMENT 'Summary',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_session_time` (`session_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Chat milestones';

DROP TABLE IF EXISTS `chat_session`;
CREATE TABLE `chat_session` (
  `id` varchar(64) NOT NULL COMMENT 'Session ID',
  `agent_id` varchar(64) NOT NULL COMMENT 'Agent ID',
  `user_id` varchar(64) NOT NULL COMMENT 'User ID',
  `title` varchar(200) DEFAULT NULL COMMENT 'Session title',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `message_count` int DEFAULT '0' COMMENT 'Message count',
  PRIMARY KEY (`id`),
  KEY `idx_user_agent` (`user_id`,`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Chat sessions';

DROP TABLE IF EXISTS `ssh_connection`;
CREATE TABLE `ssh_connection` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `connection_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Connection UUID',
  `connection_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Display name',
  `host` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Host',
  `port` int NOT NULL DEFAULT '22' COMMENT 'Port',
  `username` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Username',
  `auth_type` tinyint NOT NULL DEFAULT '1' COMMENT 'Auth type: 1=password, 2=private key',
  `password` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Password (encrypted)',
  `private_key` longtext COLLATE utf8mb4_unicode_ci COMMENT 'Private key (encrypted)',
  `encrypted` tinyint NOT NULL DEFAULT '1' COMMENT 'Encrypted: 0=no, 1=yes',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT 'Status: 0=disconnected, 1=connected, 2=connecting, 3=failed',
  `user_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT 'User ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created at',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT 'Soft delete: 0=no, 1=yes',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_connection_id` (`connection_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SSH connection configs';

DROP TABLE IF EXISTS `ssh_connection_config`;
CREATE TABLE `ssh_connection_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `connection_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Related connection ID',
  `connect_timeout` int NOT NULL DEFAULT '10' COMMENT 'Connect timeout (seconds)',
  `keepalive_interval` int NOT NULL DEFAULT '60' COMMENT 'Keepalive interval (seconds)',
  `startup_command` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Command to run after connect',
  `compression` tinyint NOT NULL DEFAULT '0' COMMENT 'Compression: 0=no, 1=yes',
  `strict_host_key_check` tinyint NOT NULL DEFAULT '1' COMMENT 'Strict host-key check: 0=no, 1=yes',
  `known_hosts` longtext COLLATE utf8mb4_unicode_ci COMMENT 'Known hosts',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated at',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_connection_id` (`connection_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SSH connection advanced settings';

DROP TABLE IF EXISTS `ssh_session_log`;
CREATE TABLE `ssh_session_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `session_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Session UUID',
  `connection_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Related connection ID',
  `user_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default' COMMENT 'User ID',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT 'Session status: 0=open, 1=closed',
  `remote_addr` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Remote address',
  `start_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Started at',
  `end_time` datetime DEFAULT NULL COMMENT 'Ended at',
  `error_msg` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Error message',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session_id` (`session_id`),
  KEY `idx_connection_id` (`connection_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_start_time` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SSH session records';

DROP TABLE IF EXISTS `core_memory`;
CREATE TABLE `core_memory` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT 'User ID',
  `scope` varchar(20) NOT NULL COMMENT 'Scope: user/session',
  `category` varchar(30) NOT NULL COMMENT 'Category: Rule/Preference/Decision/Correction/Fact',
  `title` varchar(200) NOT NULL COMMENT 'Title',
  `keywords` varchar(500) DEFAULT NULL COMMENT 'Keywords (comma-separated)',
  `content` text COMMENT 'Body',
  `priority` int NOT NULL DEFAULT 3 COMMENT 'Priority 1-5 (higher is more important)',
  `source_session_id` varchar(64) DEFAULT NULL COMMENT 'Source session ID',
  `use_count` int NOT NULL DEFAULT 1 COMMENT 'Use count',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `last_used_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_priority` (`user_id`, `priority`),
  KEY `idx_user_last_used` (`user_id`, `last_used_at`),
  KEY `idx_keywords` (`keywords`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Core memory (in-session learning + cross-session recall)';

CREATE TABLE IF NOT EXISTS `model_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL COMMENT 'Display name',
  `base_url` varchar(255) NOT NULL COMMENT 'API base URL',
  `api_key` varchar(512) NOT NULL COMMENT 'API key (stored encrypted)',
  `model_name` varchar(128) NOT NULL COMMENT 'Model id',
  `completions_path` varchar(255) NOT NULL DEFAULT 'v1/chat/completions' COMMENT 'Completions path',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='User LLM configs';

CREATE TABLE IF NOT EXISTS `agent_run` (
  `run_id` varchar(64) NOT NULL,
  `session_id` varchar(64) NOT NULL,
  `user_id` varchar(64) DEFAULT NULL,
  `agent_id` varchar(64) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'RUNNING',
  `stop_reason` varchar(64) DEFAULT NULL,
  `round` int NOT NULL DEFAULT 0,
  `total_tool_calls` int NOT NULL DEFAULT 0,
  `total_tokens` bigint NOT NULL DEFAULT 0,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `cancelled` tinyint(1) NOT NULL DEFAULT 0,
  `cancel_reason` varchar(255) DEFAULT NULL,
  `context_compressed` tinyint(1) NOT NULL DEFAULT 0,
  `max_rounds` int NOT NULL DEFAULT 0,
  `max_tool_calls_per_round` int NOT NULL DEFAULT 0,
  `max_ai_retries` int NOT NULL DEFAULT 0,
  `idle_timeout_ms` bigint NOT NULL DEFAULT 0,
  `max_token_budget` int NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`run_id`),
  KEY `idx_agent_run_session` (`session_id`),
  KEY `idx_agent_run_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent run state';

CREATE TABLE IF NOT EXISTS `code_patch` (
  `patch_id` varchar(64) NOT NULL,
  `run_id` varchar(64) DEFAULT NULL,
  `session_id` varchar(64) NOT NULL,
  `user_id` varchar(64) DEFAULT NULL,
  `file_path` varchar(1024) NOT NULL,
  `backup_path` varchar(1024) DEFAULT NULL,
  `unified_diff` mediumtext,
  `status` varchar(32) NOT NULL DEFAULT 'APPLIED',
  `lines_added` int NOT NULL DEFAULT 0,
  `lines_removed` int NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`patch_id`),
  KEY `idx_code_patch_session` (`session_id`),
  KEY `idx_code_patch_run` (`run_id`),
  KEY `idx_code_patch_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Code-change audit';

CREATE TABLE IF NOT EXISTS `long_term_memory` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL COMMENT 'User ID',
  `session_id` varchar(64) DEFAULT NULL COMMENT 'Source session ID',
  `memory_type` varchar(50) NOT NULL COMMENT 'Memory type',
  `memory_key` varchar(128) NOT NULL COMMENT 'Dedupe key',
  `content` text NOT NULL COMMENT 'Body',
  `keywords` varchar(512) DEFAULT NULL COMMENT 'Keywords',
  `source_role` varchar(20) DEFAULT NULL COMMENT 'Source role',
  `confidence` decimal(5,2) DEFAULT '0.50' COMMENT 'Confidence',
  `hit_count` int DEFAULT '1' COMMENT 'Hit/update count',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_type_key` (`user_id`,`memory_type`,`memory_key`),
  KEY `idx_user_time` (`user_id`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Long-term memory';

SET FOREIGN_KEY_CHECKS = 1;
