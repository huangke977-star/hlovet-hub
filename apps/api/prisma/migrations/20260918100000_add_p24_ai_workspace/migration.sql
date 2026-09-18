CREATE TABLE `ai_conversations` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `user_id` INTEGER NOT NULL,
  `title` VARCHAR(160) NOT NULL DEFAULT '新对话',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`id`),
  INDEX `ai_conversations_user_id_updated_at_idx` (`user_id`, `updated_at`),
  CONSTRAINT `ai_conversations_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `ai_conversation_messages` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `conversation_id` INTEGER NOT NULL,
  `role` VARCHAR(16) NOT NULL,
  `content` LONGTEXT NOT NULL,
  `sources` JSON NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  INDEX `ai_conversation_messages_conversation_id_created_at_idx` (`conversation_id`, `created_at`),
  CONSTRAINT `ai_conversation_messages_conversation_id_fkey` FOREIGN KEY (`conversation_id`) REFERENCES `ai_conversations` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE `ai_tool_invocations` (
  `id` INTEGER NOT NULL AUTO_INCREMENT,
  `user_id` INTEGER NOT NULL,
  `conversation_id` INTEGER NULL,
  `tool_name` VARCHAR(80) NOT NULL,
  `input` JSON NULL,
  `output` JSON NULL,
  `status` VARCHAR(24) NOT NULL,
  `requires_confirmation` BOOLEAN NOT NULL DEFAULT false,
  `confirmation_token_hash` CHAR(64) NULL,
  `confirmation_expires_at` DATETIME(3) NULL,
  `confirmed_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `completed_at` DATETIME(3) NULL,
  PRIMARY KEY (`id`),
  UNIQUE INDEX `ai_tool_invocations_confirmation_token_hash_key` (`confirmation_token_hash`),
  INDEX `ai_tool_invocations_user_id_created_at_idx` (`user_id`, `created_at`),
  INDEX `ai_tool_invocations_conversation_id_created_at_idx` (`conversation_id`, `created_at`),
  CONSTRAINT `ai_tool_invocations_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `ai_tool_invocations_conversation_id_fkey` FOREIGN KEY (`conversation_id`) REFERENCES `ai_conversations` (`id`) ON DELETE SET NULL ON UPDATE CASCADE
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
