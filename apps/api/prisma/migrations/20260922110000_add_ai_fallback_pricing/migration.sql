ALTER TABLE `ai_configurations`
  ADD COLUMN `fallback_input_cost_per_million_micros` INT NOT NULL DEFAULT 0,
  ADD COLUMN `fallback_output_cost_per_million_micros` INT NOT NULL DEFAULT 0;

ALTER TABLE `ai_capability_configurations`
  ADD COLUMN `fallback_input_cost_per_million_micros` INT NOT NULL DEFAULT 0,
  ADD COLUMN `fallback_output_cost_per_million_micros` INT NOT NULL DEFAULT 0;
