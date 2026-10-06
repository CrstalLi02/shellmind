-- Incremental migration 001: core memory (in-session learning + cross-session recall)
-- For databases initialized before this table existed. New databases get it from init/01-schema.sql.
USE `shellmind`;

CREATE TABLE IF NOT EXISTS `core_memory` (
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
