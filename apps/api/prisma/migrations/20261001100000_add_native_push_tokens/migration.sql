-- CreateTable
CREATE TABLE `native_push_tokens` (
    `id` INTEGER NOT NULL AUTO_INCREMENT,
    `user_id` INTEGER NOT NULL,
    `token_hash` CHAR(64) NOT NULL,
    `token` VARCHAR(4096) NOT NULL,
    `platform` VARCHAR(24) NOT NULL DEFAULT 'android',
    `app_id` VARCHAR(191) NOT NULL DEFAULT '',
    `device_id` VARCHAR(191) NOT NULL DEFAULT '',
    `user_agent` VARCHAR(500) NOT NULL DEFAULT '',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL,

    UNIQUE INDEX `native_push_tokens_token_hash_key`(`token_hash`),
    INDEX `native_push_tokens_user_id_updated_at_idx`(`user_id`, `updated_at`),
    PRIMARY KEY (`id`)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- AddForeignKey
ALTER TABLE `native_push_tokens` ADD CONSTRAINT `native_push_tokens_user_id_fkey` FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE ON UPDATE CASCADE;
