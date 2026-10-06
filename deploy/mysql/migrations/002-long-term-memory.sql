-- Incremental migration 002: long-term memory (merged from walissh sections 2-8)
-- For databases initialized before this table existed. New databases get it from init/01-schema.sql.
USE `shellmind`;

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
