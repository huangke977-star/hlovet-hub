import {
  BadGatewayException,
  BadRequestException,
  Injectable,
  NotFoundException,
  ServiceUnavailableException,
} from "@nestjs/common";
import os from "node:os";
import { AiCapabilityConfiguration } from "../generated/prisma/client";
import { AuthenticatedUser } from "../auth/auth.types";
import { PrismaService } from "../prisma/prisma.service";
import { RedisService } from "../redis/redis.service";
import { SecretCryptoService } from "../security/secret-crypto.service";
import {
  AiCapability,
  AI_CAPABILITIES,
  AiProvider,
  AiMediaPromptDto,
  UpdateAiCapabilityConfigurationDto,
} from "./dto/ai.dto";
import {
  AiProviderClientError,
  createEmbeddingsWithProvider,
  generateImageWithProvider,
  ocrWithProvider,
  transcribeWithProvider,
} from "./ai-provider.client";
import { getAiPricingPresets } from "./ai-pricing";

const CAPABILITY_LABELS: Record<AiCapability, { zh: string; en: string; unit: string; maxInputBytes: number }> = {
  embedding: { zh: "向量 Embedding", en: "Embeddings", unit: "tokens", maxInputBytes: 2 * 1024 * 1024 },
  ocr: { zh: "图片 OCR", en: "Image OCR", unit: "image", maxInputBytes: 10 * 1024 * 1024 },
  transcription: { zh: "语音转文字", en: "Transcription", unit: "minute", maxInputBytes: 20 * 1024 * 1024 },
  image_generation: { zh: "图片生成", en: "Image generation", unit: "image", maxInputBytes: 0 },
};

type CapabilityResponse = {
  capability: AiCapability;
  label: { zh: string; en: string };
  enabled: boolean;
  provider: AiProvider;
  baseUrl: string;
  model: string;
  apiKeyConfigured: boolean;
  fallbackEnabled: boolean;
  fallbackProvider: AiProvider | null;
  fallbackBaseUrl: string;
  fallbackModel: string;
  fallbackApiKeyConfigured: boolean;
  globalConcurrency: number;
  userConcurrency: number;
  requestTimeoutSeconds: number;
  dailyRequestLimit: number;
  monthlyBudgetMicros: number;
  billingCurrency: "USD" | "CNY";
  inputCostPerMillionMicros: number;
  cachedInputCostPerMillionMicros: number;
  outputCostPerMillionMicros: number;
  fallbackInputCostPerMillionMicros: number;
  fallbackCachedInputCostPerMillionMicros: number;
  fallbackOutputCostPerMillionMicros: number;
  unitCostMicros: number;
  unitName: string;
  maxInputBytes: number;
  officialPricingNote: string;
  pricingPresets: ReturnType<typeof getAiPricingPresets>;
  updatedAt: string;
};

type CapabilityRuntime = { provider: AiProvider; baseUrl: string; model: string; apiKey: string; fallback: boolean };
type CapabilityExecution<T> = { result: T; runtime: CapabilityRuntime; primaryFailureSummary?: string; primaryDurationMs?: number };
type CapabilityFallbackError = Error & { primaryFailureSummary?: string; primaryDurationMs?: number; fallbackRuntime?: CapabilityRuntime };

@Injectable()
export class AiCapabilitiesService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly crypto: SecretCryptoService,
    private readonly redis: RedisService,
  ) {}

  async listConfigurations(): Promise<{ items: CapabilityResponse[] }> {
    const items = await Promise.all(AI_CAPABILITIES.map((capability) => this.getConfiguration(capability)));
    return { items: items.map((item) => this.toResponse(item)) };
  }

  async updateConfiguration(capability: AiCapability, dto: UpdateAiCapabilityConfigurationDto): Promise<CapabilityResponse> {
    const current = await this.getConfiguration(capability);
    const limits = this.resourceLimits();
    if (dto.globalConcurrency > limits.maxGlobalConcurrency || dto.userConcurrency > limits.maxUserConcurrency || dto.userConcurrency > dto.globalConcurrency) {
      throw new BadRequestException(`能力并发不能超过当前服务器保护上限：全站 ${limits.maxGlobalConcurrency}，单用户 ${limits.maxUserConcurrency}。\nCapability concurrency exceeds the current server protection limit.`);
    }
    const apiKey = dto.apiKey?.trim() || (current.apiKeyEncrypted ? this.readApiKey(current) : null);
    if (dto.enabled && (!dto.baseUrl?.trim() || !dto.model?.trim() || !apiKey)) {
      throw new BadRequestException("启用能力前请完整填写接口地址、模型和 API Key。\nConfigure the base URL, model, and API key before enabling this capability.");
    }
    const fallbackEnabled = dto.fallbackEnabled ?? current.fallbackEnabled;
    const fallbackProvider = dto.fallbackProvider ?? (current.fallbackProvider as AiProvider | null);
    const fallbackBaseUrl = dto.fallbackBaseUrl === undefined ? current.fallbackBaseUrl : dto.fallbackBaseUrl.trim();
    const fallbackModel = dto.fallbackModel === undefined ? current.fallbackModel : dto.fallbackModel.trim();
    const fallbackApiKey = dto.clearFallbackApiKey ? null : dto.fallbackApiKey?.trim() || this.readApiKeyValue(current.fallbackApiKeyEncrypted);
    if (fallbackEnabled && (!fallbackProvider || !fallbackBaseUrl || !fallbackModel || !fallbackApiKey)) {
      throw new BadRequestException("启用备用能力前请完整填写备用供应商、接口地址、模型和 API Key。\nConfigure the fallback provider, base URL, model, and API key before enabling fallback.");
    }
    let apiKeyEncrypted = current.apiKeyEncrypted;
    if (dto.clearApiKey) apiKeyEncrypted = null;
    else if (dto.apiKey?.trim()) apiKeyEncrypted = this.crypto.encrypt(dto.apiKey.trim());
    let fallbackApiKeyEncrypted = current.fallbackApiKeyEncrypted;
    if (dto.clearFallbackApiKey) fallbackApiKeyEncrypted = null;
    else if (dto.fallbackApiKey?.trim()) fallbackApiKeyEncrypted = this.crypto.encrypt(dto.fallbackApiKey.trim());
    const saved = await this.prisma.aiCapabilityConfiguration.update({
      where: { capability },
      data: {
        enabled: dto.enabled,
        provider: dto.provider,
        baseUrl: dto.baseUrl?.trim() || null,
        model: dto.model?.trim() || null,
        apiKeyEncrypted,
        fallbackApiKeyEncrypted,
        ...(dto.fallbackEnabled === undefined ? {} : { fallbackEnabled: dto.fallbackEnabled }),
        ...(dto.fallbackProvider === undefined ? {} : { fallbackProvider: dto.fallbackProvider.trim() || null }),
        ...(dto.fallbackBaseUrl === undefined ? {} : { fallbackBaseUrl: dto.fallbackBaseUrl.trim() || null }),
        ...(dto.fallbackModel === undefined ? {} : { fallbackModel: dto.fallbackModel.trim() || null }),
        globalConcurrency: dto.globalConcurrency,
        userConcurrency: dto.userConcurrency,
        requestTimeoutSeconds: dto.requestTimeoutSeconds,
        dailyRequestLimit: dto.dailyRequestLimit,
        monthlyBudgetMicros: dto.monthlyBudgetMicros,
        billingCurrency: dto.billingCurrency,
        inputCostPerMillionMicros: dto.inputCostPerMillionMicros,
        ...(dto.cachedInputCostPerMillionMicros === undefined ? {} : { cachedInputCostPerMillionMicros: dto.cachedInputCostPerMillionMicros }),
        outputCostPerMillionMicros: dto.outputCostPerMillionMicros,
        ...(dto.fallbackInputCostPerMillionMicros === undefined ? {} : { fallbackInputCostPerMillionMicros: dto.fallbackInputCostPerMillionMicros }),
        ...(dto.fallbackCachedInputCostPerMillionMicros === undefined ? {} : { fallbackCachedInputCostPerMillionMicros: dto.fallbackCachedInputCostPerMillionMicros }),
        ...(dto.fallbackOutputCostPerMillionMicros === undefined ? {} : { fallbackOutputCostPerMillionMicros: dto.fallbackOutputCostPerMillionMicros }),
        unitCostMicros: dto.unitCostMicros,
        unitName: dto.unitName.trim().slice(0, 32) || CAPABILITY_LABELS[capability].unit,
        maxInputBytes: dto.maxInputBytes,
      },
    });
    return this.toResponse(saved);
  }

  async testConfiguration(capability: AiCapability) {
    const config = await this.getConfiguration(capability);
    const apiKey = this.readApiKey(config);
    if (!config.baseUrl || !config.model || !apiKey) throw new BadRequestException("请先完整配置该能力，再进行测试。\nComplete the capability configuration before testing.");
    const startedAt = Date.now();
    try {
      if (capability === "embedding") {
        const execution = await this.withProviderFallback(config, (runtime) => createEmbeddingsWithProvider({ provider: runtime.provider, baseUrl: runtime.baseUrl, apiKey: runtime.apiKey, model: runtime.model, texts: ["Lingxi capability connection test"], timeoutSeconds: config.requestTimeoutSeconds }));
        const result = execution.result;
        return { success: true, capability, durationMs: Date.now() - startedAt, dimension: result.embeddings[0]?.length ?? 0, usage: result.usage };
      }
      return { success: true, capability, durationMs: Date.now() - startedAt, message: "配置完整；媒体能力需要通过上传样本执行实际测试。\nConfiguration is complete; upload a sample to run a media test." };
    } catch (error) {
      if (error instanceof AiProviderClientError) throw new BadGatewayException(error.message);
      throw error;
    }
  }

  async embedTexts(texts: string[]): Promise<{ embeddings: number[][]; usage: { promptTokens: number | null; cachedPromptTokens: number | null; completionTokens: number | null; totalTokens: number | null }; model: string }> {
    const config = await this.getConfiguration("embedding");
    const apiKey = this.readApiKey(config);
    if (!config.enabled || !config.baseUrl || !config.model || !apiKey) throw new ServiceUnavailableException("向量服务尚未配置，当前使用关键词检索。\nThe embedding service is not configured; keyword search is active.");
    if (!texts.length) return { embeddings: [], usage: { promptTokens: 0, cachedPromptTokens: 0, completionTokens: 0, totalTokens: 0 }, model: config.model };
    const startedAt = Date.now();
    const execution = await this.withCapabilityLimit(config, null, async () => this.withProviderFallback(config, (runtime) => createEmbeddingsWithProvider({ provider: runtime.provider, baseUrl: runtime.baseUrl, apiKey: runtime.apiKey, model: runtime.model, texts, timeoutSeconds: config.requestTimeoutSeconds })));
    const result = execution.result;
    const runtimeConfig = this.withRuntimeConfig(config, execution.runtime);
    const fallbackMetadata = execution.primaryFailureSummary ? { fallback: true, primaryProvider: config.provider, primaryModel: config.model ?? "" } : undefined;
    if (execution.primaryFailureSummary) {
      await this.recordUsage(config, "embedding", null, null, null, null, "failed", startedAt, `主能力调用失败，已切换备用能力：${execution.primaryFailureSummary}`, execution.primaryDurationMs, fallbackMetadata);
    }
    await this.recordUsage(runtimeConfig, "embedding", null, result.usage.promptTokens ?? texts.join("\n").length, result.usage.completionTokens, result.usage.totalTokens, "success", startedAt, undefined, undefined, fallbackMetadata, result.usage.cachedPromptTokens);
    return { ...result, model: execution.runtime.model };
  }

  async processOcr(user: AuthenticatedUser, file: { buffer: Buffer; originalname: string; mimetype: string; size: number }, prompt?: string) {
    const config = await this.requireMediaConfig("ocr", file.size);
    const task = await this.prisma.aiMediaTask.create({ data: { userId: user.id, capability: "ocr", status: "processing", provider: config.provider, model: config.model ?? "", inputMimeType: file.mimetype, inputBytes: file.size, startedAt: new Date() } });
    const startedAt = Date.now();
    try {
      const dataUrl = `data:${file.mimetype || "application/octet-stream"};base64,${file.buffer.toString("base64")}`;
      const execution = await this.withCapabilityLimit(config, user.id, () => this.withProviderFallback(config, (runtime) => ocrWithProvider({ provider: runtime.provider, baseUrl: runtime.baseUrl, apiKey: runtime.apiKey, model: runtime.model, imageDataUrl: dataUrl, prompt: prompt?.trim() || "请准确提取图片中的文字，保留段落、表格和代码结构；只返回识别结果。", timeoutSeconds: config.requestTimeoutSeconds })));
      const result = execution.result;
      const runtimeConfig = this.withRuntimeConfig(config, execution.runtime);
      const completed = await this.prisma.aiMediaTask.update({ where: { id: task.id }, data: { status: "completed", provider: execution.runtime.provider, model: execution.runtime.model, resultText: result.text, resultMetadata: result.usage as object, completedAt: new Date() } });
      const fallbackMetadata = execution.primaryFailureSummary ? { fallback: true, primaryProvider: config.provider, primaryModel: config.model ?? "" } : undefined;
      if (execution.primaryFailureSummary) {
        await this.recordUsage(config, "ocr", user.id, null, null, null, "failed", startedAt, `主能力调用失败，已切换备用能力：${execution.primaryFailureSummary}`, execution.primaryDurationMs, fallbackMetadata);
      }
      await this.recordUsage(runtimeConfig, "ocr", user.id, file.size, result.usage.completionTokens, result.usage.totalTokens, "success", startedAt, undefined, undefined, fallbackMetadata);
      return { ...this.toMediaTaskDetail(completed), text: result.text, usage: result.usage };
    } catch (error) {
      const message = this.errorMessage(error);
      const fallbackError = this.readFallbackError(error);
      await this.prisma.aiMediaTask.update({ where: { id: task.id }, data: { status: "failed", ...(fallbackError?.fallbackRuntime ? { provider: fallbackError.fallbackRuntime.provider, model: fallbackError.fallbackRuntime.model } : {}), errorSummary: message.slice(0, 500), completedAt: new Date() } }).catch(() => undefined);
      if (fallbackError) await this.recordUsage(config, "ocr", user.id, null, null, null, "failed", startedAt, `主能力调用失败，已切换备用能力：${fallbackError.primaryFailureSummary}`, fallbackError.primaryDurationMs, { fallback: true, primaryProvider: config.provider, primaryModel: config.model ?? "" });
      await this.recordUsage(fallbackError?.fallbackRuntime ? this.withRuntimeConfig(config, fallbackError.fallbackRuntime) : config, "ocr", user.id, file.size, null, null, "failed", startedAt, message, undefined, fallbackError ? { fallback: true, primaryProvider: config.provider, primaryModel: config.model ?? "" } : undefined);
      throw this.toMediaException(error);
    }
  }

  async processTranscription(user: AuthenticatedUser, file: { buffer: Buffer; originalname: string; mimetype: string; size: number }) {
    const config = await this.requireMediaConfig("transcription", file.size);
    const task = await this.prisma.aiMediaTask.create({ data: { userId: user.id, capability: "transcription", status: "processing", provider: config.provider, model: config.model ?? "", inputMimeType: file.mimetype, inputBytes: file.size, startedAt: new Date() } });
    const startedAt = Date.now();
    try {
      const execution = await this.withCapabilityLimit(config, user.id, () => this.withProviderFallback(config, (runtime) => transcribeWithProvider({ provider: runtime.provider, baseUrl: runtime.baseUrl, apiKey: runtime.apiKey, model: runtime.model, file: file.buffer, filename: file.originalname, mimeType: file.mimetype, timeoutSeconds: config.requestTimeoutSeconds })));
      const result = execution.result;
      const runtimeConfig = this.withRuntimeConfig(config, execution.runtime);
      const completed = await this.prisma.aiMediaTask.update({ where: { id: task.id }, data: { status: "completed", provider: execution.runtime.provider, model: execution.runtime.model, resultText: result.text, resultMetadata: result.usage as object, completedAt: new Date() } });
      const fallbackMetadata = execution.primaryFailureSummary ? { fallback: true, primaryProvider: config.provider, primaryModel: config.model ?? "" } : undefined;
      if (execution.primaryFailureSummary) {
        await this.recordUsage(config, "transcription", user.id, null, null, null, "failed", startedAt, `主能力调用失败，已切换备用能力：${execution.primaryFailureSummary}`, execution.primaryDurationMs, fallbackMetadata);
      }
      await this.recordUsage(runtimeConfig, "transcription", user.id, file.size, result.usage.completionTokens, result.usage.totalTokens, "success", startedAt, undefined, undefined, fallbackMetadata);
      return { ...this.toMediaTaskDetail(completed), text: result.text, usage: result.usage };
    } catch (error) {
      const message = this.errorMessage(error);
      const fallbackError = this.readFallbackError(error);
      await this.prisma.aiMediaTask.update({ where: { id: task.id }, data: { status: "failed", ...(fallbackError?.fallbackRuntime ? { provider: fallbackError.fallbackRuntime.provider, model: fallbackError.fallbackRuntime.model } : {}), errorSummary: message.slice(0, 500), completedAt: new Date() } }).catch(() => undefined);
      if (fallbackError) await this.recordUsage(config, "transcription", user.id, null, null, null, "failed", startedAt, `主能力调用失败，已切换备用能力：${fallbackError.primaryFailureSummary}`, fallbackError.primaryDurationMs, { fallback: true, primaryProvider: config.provider, primaryModel: config.model ?? "" });
      await this.recordUsage(fallbackError?.fallbackRuntime ? this.withRuntimeConfig(config, fallbackError.fallbackRuntime) : config, "transcription", user.id, file.size, null, null, "failed", startedAt, message, undefined, fallbackError ? { fallback: true, primaryProvider: config.provider, primaryModel: config.model ?? "" } : undefined);
      throw this.toMediaException(error);
    }
  }

  async generateImage(user: AuthenticatedUser, dto: AiMediaPromptDto) {
    const config = await this.requireMediaConfig("image_generation", 0);
    const prompt = dto.prompt?.trim();
    if (!prompt) throw new BadRequestException("请输入图片描述。\nEnter an image prompt.");
    const task = await this.prisma.aiMediaTask.create({ data: { userId: user.id, capability: "image_generation", status: "processing", provider: config.provider, model: config.model ?? "", prompt, startedAt: new Date() } });
    const startedAt = Date.now();
    try {
      const execution = await this.withCapabilityLimit(config, user.id, () => this.withProviderFallback(config, (runtime) => generateImageWithProvider({ provider: runtime.provider, baseUrl: runtime.baseUrl, apiKey: runtime.apiKey, model: runtime.model, prompt, size: dto.size?.trim() || "1024x1024", timeoutSeconds: config.requestTimeoutSeconds })));
      const result = execution.result;
      const storedImage = result.base64 && this.isStorableImage(result.base64) ? result.base64 : null;
      const runtimeConfig = this.withRuntimeConfig(config, execution.runtime);
      const completed = await this.prisma.aiMediaTask.update({ where: { id: task.id }, data: { status: "completed", provider: execution.runtime.provider, model: execution.runtime.model, resultText: storedImage, resultUrl: result.url, resultMetadata: { revisedPrompt: result.revisedPrompt, base64Available: Boolean(result.base64), storedImage: Boolean(storedImage), imageMimeType: "image/png" }, completedAt: new Date() } });
      const fallbackMetadata = execution.primaryFailureSummary ? { fallback: true, primaryProvider: config.provider, primaryModel: config.model ?? "" } : undefined;
      if (execution.primaryFailureSummary) {
        await this.recordUsage(config, "image_generation", user.id, null, null, null, "failed", startedAt, `主能力调用失败，已切换备用能力：${execution.primaryFailureSummary}`, execution.primaryDurationMs, fallbackMetadata);
      }
      await this.recordUsage(runtimeConfig, "image_generation", user.id, 1, result.usage.completionTokens, result.usage.totalTokens, "success", startedAt, undefined, undefined, fallbackMetadata);
      return { ...this.toMediaTaskDetail(completed), revisedPrompt: result.revisedPrompt, usage: result.usage };
    } catch (error) {
      const message = this.errorMessage(error);
      const fallbackError = this.readFallbackError(error);
      await this.prisma.aiMediaTask.update({ where: { id: task.id }, data: { status: "failed", ...(fallbackError?.fallbackRuntime ? { provider: fallbackError.fallbackRuntime.provider, model: fallbackError.fallbackRuntime.model } : {}), errorSummary: message.slice(0, 500), completedAt: new Date() } }).catch(() => undefined);
      if (fallbackError) await this.recordUsage(config, "image_generation", user.id, null, null, null, "failed", startedAt, `主能力调用失败，已切换备用能力：${fallbackError.primaryFailureSummary}`, fallbackError.primaryDurationMs, { fallback: true, primaryProvider: config.provider, primaryModel: config.model ?? "" });
      await this.recordUsage(fallbackError?.fallbackRuntime ? this.withRuntimeConfig(config, fallbackError.fallbackRuntime) : config, "image_generation", user.id, 1, null, null, "failed", startedAt, message, undefined, fallbackError ? { fallback: true, primaryProvider: config.provider, primaryModel: config.model ?? "" } : undefined);
      throw this.toMediaException(error);
    }
  }

  async listMediaTasks(userId: number) {
    const items = await this.prisma.aiMediaTask.findMany({ where: { userId }, orderBy: [{ createdAt: "desc" }, { id: "desc" }], take: 30 });
    return { items: items.map((item) => this.toMediaTaskSummary(item)) };
  }

  async getMediaTask(userId: number, id: number) {
    const task = await this.prisma.aiMediaTask.findFirst({ where: { id, userId } });
    if (!task) throw new NotFoundException("未找到该 AI 媒体任务。\nAI media task not found.");
    return this.toMediaTaskDetail(task);
  }

  async getMediaTaskImage(userId: number, id: number): Promise<{ buffer: Buffer; mimeType: string }> {
    const task = await this.prisma.aiMediaTask.findFirst({ where: { id, userId, capability: "image_generation", status: "completed" } });
    if (!task?.resultText) throw new NotFoundException("该图片结果不可用或已过期。\nThe generated image is unavailable or has expired.");
    const buffer = Buffer.from(task.resultText, "base64");
    if (!buffer.length) throw new NotFoundException("该图片结果不可用或已损坏。\nThe generated image is unavailable or corrupted.");
    return { buffer, mimeType: this.readImageMimeType(task.resultMetadata) };
  }

  async getUserMediaCapabilities() {
    const capabilities = await Promise.all(["ocr", "transcription", "image_generation"].map(async (capability) => {
      const config = await this.getConfiguration(capability as AiCapability);
      const definition = CAPABILITY_LABELS[capability as AiCapability];
      const available = Boolean(config.enabled && config.baseUrl && config.model && this.readApiKey(config));
      return { capability, label: { zh: definition.zh, en: definition.en }, available, maxInputBytes: config.maxInputBytes, unitName: config.unitName };
    }));
    return { items: capabilities };
  }

  async usageOverview(days = 30) {
    const since = new Date(Date.now() - Math.max(1, Math.min(365, Math.floor(days))) * 24 * 60 * 60 * 1000);
    const [logs, feedback] = await Promise.all([
      this.prisma.aiUsageLog.findMany({ where: { createdAt: { gte: since } }, orderBy: [{ createdAt: "desc" }, { id: "desc" }], take: 2000 }),
      this.prisma.aiQualityFeedback.findMany({ where: { createdAt: { gte: since } }, select: { rating: true } }),
    ]);
    const byCapability = new Map<string, { requests: number; success: number; failed: number; tokens: number; cachedInputUnits: number; estimatedCostMicros: number }>();
    for (const log of logs) {
      const current = byCapability.get(log.capability) ?? { requests: 0, success: 0, failed: 0, tokens: 0, cachedInputUnits: 0, estimatedCostMicros: 0 };
      current.requests += 1;
      if (log.status === "success") current.success += 1;
      else current.failed += 1;
      current.tokens += log.totalTokens ?? 0;
      current.cachedInputUnits += log.cachedInputUnits ?? 0;
      current.estimatedCostMicros += log.estimatedCostMicros ?? 0;
      byCapability.set(log.capability, current);
    }
    const feedbackTotal = feedback.length;
    const feedbackHelpful = feedback.filter((item) => item.rating === 1).length;
    const feedbackUnhelpful = feedback.filter((item) => item.rating === -1).length;
    return {
      days,
      total: { requests: logs.length, tokens: logs.reduce((sum, item) => sum + (item.totalTokens ?? 0), 0), cachedInputUnits: logs.reduce((sum, item) => sum + (item.cachedInputUnits ?? 0), 0), estimatedCostMicros: logs.reduce((sum, item) => sum + (item.estimatedCostMicros ?? 0), 0) },
      byCapability: [...byCapability.entries()].map(([capability, value]) => ({ ...value, capability, successRate: value.requests ? Math.round(value.success / value.requests * 100) : 0, failureRate: value.requests ? Math.round(value.failed / value.requests * 100) : 0 })),
      quality: { feedbackTotal, helpful: feedbackHelpful, unhelpful: feedbackUnhelpful, averageRating: feedbackTotal ? Number((feedback.reduce((sum, item) => sum + item.rating, 0) / feedbackTotal).toFixed(2)) : null },
      recent: logs.slice(0, 100).map((log) => ({ ...log, createdAt: log.createdAt.toISOString() })),
    };
  }

  private async requireMediaConfig(capability: AiCapability, inputBytes: number): Promise<AiCapabilityConfiguration> {
    const config = await this.getConfiguration(capability);
    const apiKey = this.readApiKey(config);
    if (!config.enabled || !config.baseUrl || !config.model || !apiKey) throw new ServiceUnavailableException(`${CAPABILITY_LABELS[capability].zh}尚未配置或启用。\nThis AI capability is not configured or enabled.`);
    if (inputBytes > config.maxInputBytes) throw new BadRequestException(`文件超过该能力的大小限制（${config.maxInputBytes} bytes）。\nThe file exceeds this capability's size limit.`);
    return config;
  }

  private async getConfiguration(capability: AiCapability): Promise<AiCapabilityConfiguration> {
    const defaults = CAPABILITY_LABELS[capability];
    return this.prisma.aiCapabilityConfiguration.upsert({ where: { capability }, create: { capability, unitName: defaults.unit, maxInputBytes: defaults.maxInputBytes }, update: {} });
  }

  private async withCapabilityLimit<T>(config: AiCapabilityConfiguration, userId: number | null, callback: () => Promise<T>): Promise<T> {
    if (config.dailyRequestLimit > 0) {
      const quotaKey = `ai:capability:quota:${config.capability}:${this.utcDayKey()}`;
      const allowed = await this.redis.tryAcquireCounter(quotaKey, config.dailyRequestLimit, this.secondsUntilUtcDayEnd());
      if (!allowed) throw new ServiceUnavailableException("该 AI 能力今日调用额度已用完。\nThe daily limit for this AI capability has been reached.");
    }
    if (config.monthlyBudgetMicros > 0) {
      const monthStart = new Date();
      monthStart.setUTCDate(1);
      monthStart.setUTCHours(0, 0, 0, 0);
      const spent = await this.prisma.aiUsageLog.aggregate({ _sum: { estimatedCostMicros: true }, where: { capability: config.capability, status: "success", createdAt: { gte: monthStart } } });
      if ((spent._sum.estimatedCostMicros ?? 0) >= config.monthlyBudgetMicros) throw new ServiceUnavailableException("该 AI 能力已达到本月费用预算。\nThe monthly budget for this AI capability has been reached.");
    }
    const globalKey = `ai:capability:${config.capability}:global`;
    const userKey = userId === null ? null : `ai:capability:${config.capability}:user:${userId}`;
    const leaseSeconds = config.requestTimeoutSeconds + 30;
    const globalAcquired = await this.redis.tryAcquireCounter(globalKey, config.globalConcurrency, leaseSeconds);
    if (!globalAcquired) throw new ServiceUnavailableException("当前 AI 能力请求较多，请稍后重试。\nThis AI capability is busy; try again later.");
    let userAcquired = false;
    try {
      if (userKey) {
        userAcquired = await this.redis.tryAcquireCounter(userKey, config.userConcurrency, leaseSeconds);
        if (!userAcquired) throw new ServiceUnavailableException("当前账号的 AI 能力请求较多，请稍后重试。\nYour capability concurrency limit has been reached.");
      }
      return await callback();
    } finally {
      if (userAcquired && userKey) await this.redis.releaseCounter(userKey).catch(() => undefined);
      await this.redis.releaseCounter(globalKey).catch(() => undefined);
    }
  }

  private async recordUsage(config: AiCapabilityConfiguration, operation: string, userId: number | null, inputUnits: number | null, outputUnits: number | null, totalTokens: number | null, status: string, startedAt: number, errorSummary?: string, durationMs?: number, metadata?: Record<string, unknown>, cachedInputUnits?: number | null) {
    const estimatedCostMicros = this.estimateCost(config, inputUnits, outputUnits, operation, cachedInputUnits);
    await this.prisma.aiUsageLog.create({ data: { userId, capability: config.capability, operation, provider: config.provider, model: config.model ?? "", status, inputUnits, cachedInputUnits, outputUnits, totalTokens, durationMs: Math.max(0, durationMs ?? Date.now() - startedAt), estimatedCostMicros, errorSummary: errorSummary?.slice(0, 255), metadata: metadata as never } }).catch(() => undefined);
  }

  private estimateCost(config: AiCapabilityConfiguration, inputUnits: number | null, outputUnits: number | null, operation: string, cachedInputUnits: number | null = null): number | null {
    if (inputUnits === null && outputUnits === null) return null;
    const cached = Math.min(cachedInputUnits ?? 0, inputUnits ?? cachedInputUnits ?? 0);
    const regularInput = Math.max(0, (inputUnits ?? 0) - cached);
    const tokenCost = (regularInput * (config.inputCostPerMillionMicros ?? 0) + cached * (config.cachedInputCostPerMillionMicros ?? 0) + (outputUnits ?? 0) * (config.outputCostPerMillionMicros ?? 0)) / 1_000_000;
    const unitCost = operation === "image_generation" || operation === "ocr" || operation === "transcription" ? config.unitCostMicros : 0;
    return Math.max(0, Math.round(tokenCost + unitCost));
  }

  private readApiKey(config: AiCapabilityConfiguration): string | null {
    if (!config.apiKeyEncrypted) return null;
    return this.readApiKeyValue(config.apiKeyEncrypted);
  }

  private readApiKeyValue(value: string | null): string | null {
    if (!value) return null;
    try { return this.crypto.decrypt(value); } catch { return null; }
  }

  private async withProviderFallback<T>(config: AiCapabilityConfiguration, callback: (runtime: CapabilityRuntime) => Promise<T>): Promise<CapabilityExecution<T>> {
    const primary: CapabilityRuntime = { provider: config.provider as AiProvider, baseUrl: config.baseUrl!, model: config.model!, apiKey: this.readApiKey(config)!, fallback: false };
    const primaryStartedAt = Date.now();
    try {
      return { result: await callback(primary), runtime: primary };
    } catch (error) {
      if (!config.fallbackEnabled || !(error instanceof AiProviderClientError) || !error.retryable) throw error;
      const fallbackKey = this.readApiKeyValue(config.fallbackApiKeyEncrypted);
      if (!config.fallbackProvider || !config.fallbackBaseUrl || !config.fallbackModel || !fallbackKey) throw error;
      const fallback: CapabilityRuntime = { provider: config.fallbackProvider as AiProvider, baseUrl: config.fallbackBaseUrl, model: config.fallbackModel, apiKey: fallbackKey, fallback: true };
      const primaryFailureSummary = this.errorMessage(error);
      try {
        return { result: await callback(fallback), runtime: fallback, primaryFailureSummary, primaryDurationMs: Date.now() - primaryStartedAt };
      } catch (fallbackError) {
        const annotated: CapabilityFallbackError = fallbackError instanceof Error ? fallbackError as CapabilityFallbackError : new AiProviderClientError(this.errorMessage(fallbackError));
        annotated.primaryFailureSummary = primaryFailureSummary;
        annotated.primaryDurationMs = Date.now() - primaryStartedAt;
        annotated.fallbackRuntime = fallback;
        throw annotated;
      }
    }
  }

  private readFallbackError(error: unknown): CapabilityFallbackError | null {
    if (!error || typeof error !== "object") return null;
    const candidate = error as CapabilityFallbackError;
    return candidate.primaryFailureSummary ? candidate : null;
  }

  private withRuntimeConfig(config: AiCapabilityConfiguration, runtime: CapabilityRuntime): AiCapabilityConfiguration {
    return {
      ...config,
      provider: runtime.provider,
      baseUrl: runtime.baseUrl,
      model: runtime.model,
      inputCostPerMillionMicros: runtime.fallback ? config.fallbackInputCostPerMillionMicros : config.inputCostPerMillionMicros,
      cachedInputCostPerMillionMicros: runtime.fallback ? config.fallbackCachedInputCostPerMillionMicros : config.cachedInputCostPerMillionMicros,
      outputCostPerMillionMicros: runtime.fallback ? config.fallbackOutputCostPerMillionMicros : config.outputCostPerMillionMicros,
    };
  }

  private resourceLimits() {
    const cpuCores = Math.max(1, os.cpus().length);
    const totalMemoryMiB = Math.round(os.totalmem() / 1024 / 1024);
    return { maxGlobalConcurrency: Math.max(1, Math.min(8, cpuCores * 2, totalMemoryMiB < 1024 ? 2 : 8)), maxUserConcurrency: Math.max(1, Math.min(4, cpuCores * 2)) };
  }

  private toResponse(config: AiCapabilityConfiguration): CapabilityResponse {
    const definition = CAPABILITY_LABELS[config.capability as AiCapability] ?? CAPABILITY_LABELS.embedding;
    return {
      capability: config.capability as AiCapability,
      label: { zh: definition.zh, en: definition.en },
      enabled: config.enabled,
      provider: config.provider as AiProvider,
      baseUrl: config.baseUrl ?? "",
      model: config.model ?? "",
      apiKeyConfigured: Boolean(config.apiKeyEncrypted),
      fallbackEnabled: config.fallbackEnabled,
      fallbackProvider: (config.fallbackProvider as AiProvider | null) ?? null,
      fallbackBaseUrl: config.fallbackBaseUrl ?? "",
      fallbackModel: config.fallbackModel ?? "",
      fallbackApiKeyConfigured: Boolean(config.fallbackApiKeyEncrypted),
      globalConcurrency: config.globalConcurrency,
      userConcurrency: config.userConcurrency,
      requestTimeoutSeconds: config.requestTimeoutSeconds,
      dailyRequestLimit: config.dailyRequestLimit,
      monthlyBudgetMicros: config.monthlyBudgetMicros,
      billingCurrency: config.billingCurrency === "CNY" ? "CNY" : "USD",
      inputCostPerMillionMicros: config.inputCostPerMillionMicros,
      cachedInputCostPerMillionMicros: config.cachedInputCostPerMillionMicros,
      outputCostPerMillionMicros: config.outputCostPerMillionMicros,
      fallbackInputCostPerMillionMicros: config.fallbackInputCostPerMillionMicros,
      fallbackCachedInputCostPerMillionMicros: config.fallbackCachedInputCostPerMillionMicros,
      fallbackOutputCostPerMillionMicros: config.fallbackOutputCostPerMillionMicros,
      unitCostMicros: config.unitCostMicros,
      unitName: config.unitName,
      maxInputBytes: config.maxInputBytes,
      officialPricingNote: "官方价格会随供应商和模型变化；保存前请核对供应商当前报价。\nOfficial prices change by provider and model; verify the current provider price before saving.",
      pricingPresets: getAiPricingPresets(),
      updatedAt: config.updatedAt.toISOString(),
    };
  }

  private toMediaTaskSummary(task: { id: number; capability: string; status: string; prompt: string | null; inputMimeType: string | null; inputBytes: number | null; resultText: string | null; resultUrl: string | null; errorSummary: string | null; createdAt: Date; startedAt: Date | null; completedAt: Date | null }) {
    return {
      id: task.id,
      capability: task.capability,
      status: task.status,
      prompt: task.prompt,
      inputMimeType: task.inputMimeType,
      inputBytes: task.inputBytes,
      resultPreview: task.capability === "image_generation" ? null : task.resultText?.slice(0, 360) ?? null,
      resultUrl: task.resultUrl,
      hasStoredImage: task.capability === "image_generation" && Boolean(task.resultText),
      errorSummary: task.errorSummary,
      createdAt: task.createdAt.toISOString(),
      startedAt: task.startedAt?.toISOString() ?? null,
      completedAt: task.completedAt?.toISOString() ?? null,
    };
  }

  private toMediaTaskDetail(task: { id: number; capability: string; status: string; prompt: string | null; inputMimeType: string | null; inputBytes: number | null; resultText: string | null; resultUrl: string | null; resultMetadata: unknown; errorSummary: string | null; createdAt: Date; startedAt: Date | null; completedAt: Date | null }) {
    return {
      ...this.toMediaTaskSummary(task),
      resultText: task.capability === "image_generation" ? null : task.resultText,
      revisedPrompt: this.readRevisedPrompt(task.resultMetadata),
    };
  }

  private isStorableImage(base64: string): boolean {
    // The persistent result is intentionally capped to protect the small VPS
    // and MySQL from an unexpectedly large provider response.
    return base64.length <= 12 * 1024 * 1024;
  }

  private readImageMimeType(metadata: unknown): string {
    if (metadata && typeof metadata === "object" && !Array.isArray(metadata)) {
      const value = (metadata as Record<string, unknown>).imageMimeType;
      if (typeof value === "string" && /^image\/(png|jpeg|webp)$/i.test(value)) return value;
    }
    return "image/png";
  }

  private readRevisedPrompt(metadata: unknown): string | null {
    if (metadata && typeof metadata === "object" && !Array.isArray(metadata)) {
      const value = (metadata as Record<string, unknown>).revisedPrompt;
      return typeof value === "string" ? value : null;
    }
    return null;
  }

  private toMediaException(error: unknown): Error {
    if (error instanceof AiProviderClientError) return new BadGatewayException(error.message);
    if (error instanceof Error) return error;
    return new BadGatewayException("AI 媒体服务暂时不可用。\nThe AI media service is temporarily unavailable.");
  }

  private errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : "AI media task failed.";
  }

  private utcDayKey(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private secondsUntilUtcDayEnd(): number {
    const now = new Date();
    const tomorrow = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1));
    return Math.max(60, Math.ceil((tomorrow.getTime() - now.getTime()) / 1000));
  }
}
