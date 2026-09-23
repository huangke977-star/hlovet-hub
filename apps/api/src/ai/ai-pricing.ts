import { AiProvider } from "./dto/ai.dto";

export interface AiPricingPreset {
  provider: AiProvider;
  model: string;
  label: string;
  billingCurrency: "USD" | "CNY";
  inputCostPerMillionMicros: number;
  cachedInputCostPerMillionMicros: number;
  outputCostPerMillionMicros: number;
  note: string;
}

// These are deliberately small, explicit presets. They are a convenience for
// the admin form, not a billing authority; provider pricing and cache tiers
// can change without an application release.
const PRESETS: AiPricingPreset[] = [
  { provider: "deepseek", model: "deepseek-chat", label: "DeepSeek Chat", billingCurrency: "CNY", inputCostPerMillionMicros: 2_000_000, cachedInputCostPerMillionMicros: 0, outputCostPerMillionMicros: 8_000_000, note: "官方公开价参考；缓存命中价需按当前模型账单填写。" },
  { provider: "deepseek", model: "deepseek-reasoner", label: "DeepSeek Reasoner", billingCurrency: "CNY", inputCostPerMillionMicros: 4_000_000, cachedInputCostPerMillionMicros: 0, outputCostPerMillionMicros: 16_000_000, note: "官方公开价参考；缓存命中价需按当前模型账单填写。" },
  { provider: "openai", model: "gpt-4o-mini", label: "GPT-4o mini", billingCurrency: "USD", inputCostPerMillionMicros: 150_000, cachedInputCostPerMillionMicros: 0, outputCostPerMillionMicros: 600_000, note: "官方公开价参考；缓存命中价需按当前模型账单填写。" },
  { provider: "openai", model: "gpt-4o", label: "GPT-4o", billingCurrency: "USD", inputCostPerMillionMicros: 2_500_000, cachedInputCostPerMillionMicros: 0, outputCostPerMillionMicros: 10_000_000, note: "官方公开价参考；缓存命中价需按当前模型账单填写。" },
  { provider: "openai", model: "o3-mini", label: "o3-mini", billingCurrency: "USD", inputCostPerMillionMicros: 1_100_000, cachedInputCostPerMillionMicros: 0, outputCostPerMillionMicros: 4_400_000, note: "官方公开价参考；缓存命中价需按当前模型账单填写。" },
  { provider: "custom", model: "gpt-4o-mini", label: "GPT-4o mini（兼容接口）", billingCurrency: "USD", inputCostPerMillionMicros: 150_000, cachedInputCostPerMillionMicros: 0, outputCostPerMillionMicros: 600_000, note: "官方公开价参考；兼容接口缓存价需按实际供应商账单填写。" },
  { provider: "custom", model: "gpt-4o", label: "GPT-4o（兼容接口）", billingCurrency: "USD", inputCostPerMillionMicros: 2_500_000, cachedInputCostPerMillionMicros: 0, outputCostPerMillionMicros: 10_000_000, note: "官方公开价参考；兼容接口缓存价需按实际供应商账单填写。" },
  { provider: "custom", model: "o3-mini", label: "o3-mini（兼容接口）", billingCurrency: "USD", inputCostPerMillionMicros: 1_100_000, cachedInputCostPerMillionMicros: 0, outputCostPerMillionMicros: 4_400_000, note: "官方公开价参考；兼容接口缓存价需按实际供应商账单填写。" },
];

export function getAiPricingPresets(): AiPricingPreset[] {
  return PRESETS.map((preset) => ({ ...preset }));
}
