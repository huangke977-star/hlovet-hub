ALTER TABLE `ai_configurations`
  ADD COLUMN `rag_enabled` BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN `rag_top_k` INTEGER NOT NULL DEFAULT 6,
  ADD COLUMN `context_max_messages` INTEGER NOT NULL DEFAULT 24,
  ADD COLUMN `context_summary_threshold` INTEGER NOT NULL DEFAULT 32,
  ADD COLUMN `context_retention_days` INTEGER NOT NULL DEFAULT 365,
  ADD COLUMN `quality_evaluation_enabled` BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE `ai_invocation_logs`
  ADD COLUMN `capability` VARCHAR(32) NOT NULL DEFAULT 'chat',
  ADD COLUMN `source_count` INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN `quality_score` INTEGER NULL,
  ADD COLUMN `quality_flags` JSON NULL;

ALTER TABLE `ai_conversations`
  ADD COLUMN `context_summary` LONGTEXT NULL,
  ADD COLUMN `summary_message_count` INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN `summary_updated_at` DATETIME(3) NULL;

CREATE TABLE `ai_capability_configurations` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `capability` VARCHAR(32) NOT NULL,
  `enabled` BOOLEAN NOT NULL DEFAULT false,
  `provider` VARCHAR(32) NOT NULL DEFAULT 'openai-compatible',
  `base_url` VARCHAR(512) NULL,
  `model` VARCHAR(160) NULL,
  `api_key_encrypted` TEXT NULL,
  `global_concurrency` INTEGER NOT NULL DEFAULT 1,
  `user_concurrency` INTEGER NOT NULL DEFAULT 1,
  `request_timeout_seconds` INTEGER NOT NULL DEFAULT 60,
  `daily_request_limit` INTEGER NOT NULL DEFAULT 0,
  `monthly_budget_micros` INTEGER NOT NULL DEFAULT 0,
  `billing_currency` VARCHAR(8) NOT NULL DEFAULT 'USD',
  `input_cost_per_million_micros` INTEGER NOT NULL DEFAULT 0,
  `output_cost_per_million_micros` INTEGER NOT NULL DEFAULT 0,
  `unit_cost_micros` INTEGER NOT NULL DEFAULT 0,
  `unit_name` VARCHAR(32) NOT NULL DEFAULT 'request',
  `max_input_bytes` INTEGER NOT NULL DEFAULT 10485760,
  `metadata` JSON NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE INDEX `ai_capability_configurations_capability_key` (`capability`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `ai_usage_logs` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `user_id` INTEGER NULL,
  `capability` VARCHAR(32) NOT NULL,
  `operation` VARCHAR(64) NOT NULL,
  `provider` VARCHAR(32) NOT NULL,
  `model` VARCHAR(160) NOT NULL,
  `status` VARCHAR(24) NOT NULL,
  `input_units` INTEGER NULL,
  `output_units` INTEGER NULL,
  `total_tokens` INTEGER NULL,
  `duration_ms` INTEGER NOT NULL,
  `estimated_cost_micros` INTEGER NULL,
  `error_summary` VARCHAR(255) NULL,
  `metadata` JSON NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  INDEX `ai_usage_logs_capability_created_at_idx` (`capability`, `created_at`),
  INDEX `ai_usage_logs_user_id_created_at_idx` (`user_id`, `created_at`),
  INDEX `ai_usage_logs_status_created_at_idx` (`status`, `created_at`),
  CONSTRAINT `ai_usage_logs_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL ON UPDATE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `ai_knowledge_documents` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `source_type` VARCHAR(32) NOT NULL,
  `source_id` INTEGER NOT NULL,
  `slug` VARCHAR(180) NOT NULL DEFAULT '',
  `title` VARCHAR(180) NOT NULL,
  `content_hash` CHAR(64) NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'pending',
  `chunk_count` INTEGER NOT NULL DEFAULT 0,
  `vector_count` INTEGER NOT NULL DEFAULT 0,
  `embedding_model` VARCHAR(160) NULL,
  `embedding_dimension` INTEGER NULL,
  `error_summary` VARCHAR(500) NULL,
  `last_indexed_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE INDEX `ai_knowledge_documents_source_type_source_id_key` (`source_type`, `source_id`),
  INDEX `ai_knowledge_documents_status_updated_at_idx` (`status`, `updated_at`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `ai_knowledge_chunks` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `document_id` INTEGER NOT NULL,
  `sequence` INTEGER NOT NULL,
  `content` LONGTEXT NOT NULL,
  `content_hash` CHAR(64) NOT NULL,
  `embedding_json` LONGTEXT NULL,
  `embedding_model` VARCHAR(160) NULL,
  `embedding_dimension` INTEGER NULL,
  `token_count` INTEGER NULL,
  `metadata` JSON NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE INDEX `ai_knowledge_chunks_document_id_sequence_key` (`document_id`, `sequence`),
  INDEX `ai_knowledge_chunks_document_id_updated_at_idx` (`document_id`, `updated_at`),
  CONSTRAINT `ai_knowledge_chunks_document_id_fkey` FOREIGN KEY (`document_id`) REFERENCES `ai_knowledge_documents` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `ai_media_tasks` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `user_id` INTEGER NOT NULL,
  `capability` VARCHAR(32) NOT NULL,
  `status` VARCHAR(24) NOT NULL DEFAULT 'queued',
  `provider` VARCHAR(32) NOT NULL,
  `model` VARCHAR(160) NOT NULL,
  `prompt` TEXT NULL,
  `input_path` VARCHAR(512) NULL,
  `input_mime_type` VARCHAR(127) NULL,
  `input_bytes` INTEGER NULL,
  `result_text` LONGTEXT NULL,
  `result_url` VARCHAR(2000) NULL,
  `result_metadata` JSON NULL,
  `error_summary` VARCHAR(500) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `started_at` DATETIME(3) NULL,
  `completed_at` DATETIME(3) NULL,
  PRIMARY KEY (`id`),
  INDEX `ai_media_tasks_user_id_created_at_idx` (`user_id`, `created_at`),
  INDEX `ai_media_tasks_capability_status_created_at_idx` (`capability`, `status`, `created_at`),
  CONSTRAINT `ai_media_tasks_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `ai_quality_feedback` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `user_id` INTEGER NOT NULL,
  `conversation_id` INTEGER NULL,
  `message_id` INTEGER NULL,
  `rating` INTEGER NOT NULL,
  `note` VARCHAR(500) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  INDEX `ai_quality_feedback_conversation_id_created_at_idx` (`conversation_id`, `created_at`),
  INDEX `ai_quality_feedback_rating_created_at_idx` (`rating`, `created_at`),
  CONSTRAINT `ai_quality_feedback_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `ai_quality_feedback_conversation_id_fkey` FOREIGN KEY (`conversation_id`) REFERENCES `ai_conversations` (`id`) ON DELETE SET NULL ON UPDATE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

