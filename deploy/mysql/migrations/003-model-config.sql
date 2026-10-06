-- Incremental migration 003: user LLM model configs
-- For databases initialized before this table existed. New databases get it from init/01-schema.sql.
USE `shellmind`;

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
