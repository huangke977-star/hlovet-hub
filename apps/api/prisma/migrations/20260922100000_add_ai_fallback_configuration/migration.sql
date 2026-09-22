ALTER TABLE `ai_configurations`
  ADD COLUMN `fallback_enabled` BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN `fallback_provider` VARCHAR(32) NULL,
  ADD COLUMN `fallback_base_url` VARCHAR(512) NULL,
  ADD COLUMN `fallback_model` VARCHAR(160) NULL,
  ADD COLUMN `fallback_api_key_encrypted` TEXT NULL;

ALTER TABLE `ai_capability_configurations`
  ADD COLUMN `fallback_enabled` BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN `fallback_provider` VARCHAR(32) NULL,
  ADD COLUMN `fallback_base_url` VARCHAR(512) NULL,
  ADD COLUMN `fallback_model` VARCHAR(160) NULL,
  ADD COLUMN `fallback_api_key_encrypted` TEXT NULL;
