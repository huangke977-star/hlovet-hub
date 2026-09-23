ALTER TABLE `ai_configurations`
  ADD COLUMN `cached_input_cost_per_million_micros` INT NOT NULL DEFAULT 0,
  ADD COLUMN `fallback_cached_input_cost_per_million_micros` INT NOT NULL DEFAULT 0;

ALTER TABLE `ai_invocation_logs`
  ADD COLUMN `cached_prompt_tokens` INT NULL;

ALTER TABLE `ai_capability_configurations`
  ADD COLUMN `cached_input_cost_per_million_micros` INT NOT NULL DEFAULT 0,
  ADD COLUMN `fallback_cached_input_cost_per_million_micros` INT NOT NULL DEFAULT 0;

ALTER TABLE `ai_usage_logs`
  ADD COLUMN `cached_input_units` INT NULL;
