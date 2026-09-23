import { AiService } from "../src/ai/ai.service";

function createHarness() {
  const prisma = {
    aiConfiguration: {
      upsert: jest.fn(),
      update: jest.fn(),
    },
    aiInvocationLog: {
      create: jest.fn(),
      findMany: jest.fn(),
      aggregate: jest.fn(),
    },
  };
  const crypto = {
    isConfigured: jest.fn(() => true),
    encrypt: jest.fn((value: string) => `encrypted:${value}`),
    decrypt: jest.fn((value: string) => value.replace(/^encrypted:/, "")),
  };
  const redis = {
    tryAcquireCounter: jest.fn(async () => true),
    releaseCounter: jest.fn(async () => undefined),
  };
  return { prisma, crypto, redis, service: new AiService(prisma as never, crypto as never, redis as never) };
}

describe("P23 AI configuration", () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("returns a resource-based recommendation and safe defaults", async () => {
    const harness = createHarness();
    harness.prisma.aiConfiguration.upsert.mockResolvedValue({
      id: 1,
      enabled: false,
      provider: "openai-compatible",
      baseUrl: null,
      model: null,
      apiKeyEncrypted: null,
      globalConcurrency: 2,
      userConcurrency: 1,
      maxOutputTokens: 2000,
      requestTimeoutSeconds: 60,
      dailyRequestLimit: 0,
      billingCurrency: "USD",
      inputCostPerMillionMicros: 0,
      outputCostPerMillionMicros: 0,
      fallbackInputCostPerMillionMicros: 2_000_000,
      fallbackCachedInputCostPerMillionMicros: 500_000,
      fallbackOutputCostPerMillionMicros: 3_000_000,
      updatedAt: new Date("2026-09-10T00:00:00.000Z"),
    });

    const result = await harness.service.getAdminConfiguration();

    expect(result.globalConcurrency).toBe(2);
    expect(result.userConcurrency).toBe(1);
    expect(result.recommendation.maxGlobalConcurrency).toBeGreaterThanOrEqual(1);
    expect(result.apiKeyConfigured).toBe(false);
  });

  it("encrypts a new API key and does not return it", async () => {
    const harness = createHarness();
    const current = {
      id: 1,
      enabled: false,
      provider: "openai-compatible",
      baseUrl: null,
      model: null,
      apiKeyEncrypted: null,
      globalConcurrency: 2,
      userConcurrency: 1,
      maxOutputTokens: 2000,
      requestTimeoutSeconds: 60,
      dailyRequestLimit: 0,
      billingCurrency: "USD",
      inputCostPerMillionMicros: 0,
      outputCostPerMillionMicros: 0,
      fallbackInputCostPerMillionMicros: 2_000_000,
      fallbackCachedInputCostPerMillionMicros: 500_000,
      fallbackOutputCostPerMillionMicros: 3_000_000,
      updatedAt: new Date("2026-09-10T00:00:00.000Z"),
    };
    harness.prisma.aiConfiguration.upsert.mockResolvedValue(current);
    harness.prisma.aiConfiguration.update.mockImplementation(async ({ data }: { data: Record<string, unknown> }) => ({ ...current, ...data, apiKeyEncrypted: data.apiKeyEncrypted, updatedAt: new Date("2026-09-10T00:01:00.000Z") }));

    const result = await harness.service.updateConfiguration({
      enabled: false,
      provider: "openai-compatible",
      baseUrl: "https://api.example.com/v1",
      model: "example-model",
      apiKey: "secret-key",
      globalConcurrency: 2,
      userConcurrency: 1,
      maxOutputTokens: 2000,
      requestTimeoutSeconds: 60,
      dailyRequestLimit: 0,
      billingCurrency: "USD",
      inputCostPerMillionMicros: 0,
      outputCostPerMillionMicros: 0,
    });

    expect(harness.crypto.encrypt).toHaveBeenCalledWith("secret-key");
    expect(harness.prisma.aiConfiguration.update).toHaveBeenCalledWith(expect.objectContaining({ data: expect.objectContaining({ apiKeyEncrypted: "encrypted:secret-key" }) }));
    expect(result).not.toHaveProperty("apiKey");
    expect(result.apiKeyConfigured).toBe(true);
  });

  it("keeps fallback settings when an older client omits fallback fields", async () => {
    const harness = createHarness();
    const current = {
      id: 1,
      enabled: false,
      provider: "openai-compatible",
      baseUrl: null,
      model: null,
      apiKeyEncrypted: null,
      fallbackEnabled: true,
      fallbackProvider: "deepseek",
      fallbackBaseUrl: "https://fallback.example/v1",
      fallbackModel: "fallback-model",
      fallbackApiKeyEncrypted: "encrypted:fallback-key",
      globalConcurrency: 2,
      userConcurrency: 1,
      maxOutputTokens: 2000,
      requestTimeoutSeconds: 60,
      dailyRequestLimit: 0,
      billingCurrency: "USD",
      inputCostPerMillionMicros: 0,
      outputCostPerMillionMicros: 0,
      fallbackInputCostPerMillionMicros: 2_000_000,
      fallbackCachedInputCostPerMillionMicros: 500_000,
      fallbackOutputCostPerMillionMicros: 3_000_000,
      updatedAt: new Date("2026-09-10T00:00:00.000Z"),
    };
    harness.prisma.aiConfiguration.upsert.mockResolvedValue(current);
    harness.prisma.aiConfiguration.update.mockImplementation(async ({ data }: { data: Record<string, unknown> }) => ({ ...current, ...data, updatedAt: new Date("2026-09-10T00:01:00.000Z") }));

    const result = await harness.service.updateConfiguration({
      enabled: false,
      provider: "openai-compatible",
      baseUrl: "",
      model: "",
      globalConcurrency: 2,
      userConcurrency: 1,
      maxOutputTokens: 2000,
      requestTimeoutSeconds: 60,
      dailyRequestLimit: 0,
      billingCurrency: "USD",
      inputCostPerMillionMicros: 0,
      outputCostPerMillionMicros: 0,
    });

    const data = harness.prisma.aiConfiguration.update.mock.calls[0][0].data as Record<string, unknown>;
    expect(data).not.toHaveProperty("fallbackProvider");
    expect(data).not.toHaveProperty("fallbackBaseUrl");
    expect(data).not.toHaveProperty("fallbackModel");
    expect(result).toMatchObject({ fallbackEnabled: true, fallbackProvider: "deepseek", fallbackModel: "fallback-model" });
  });

  it("rejects an enabled configuration without connection details", async () => {
    const harness = createHarness();
    harness.prisma.aiConfiguration.upsert.mockResolvedValue({ apiKeyEncrypted: null });

    await expect(harness.service.updateConfiguration({
      enabled: true,
      provider: "openai-compatible",
      baseUrl: "",
      model: "",
      globalConcurrency: 2,
      userConcurrency: 1,
      maxOutputTokens: 2000,
      requestTimeoutSeconds: 60,
      dailyRequestLimit: 0,
      billingCurrency: "USD",
      inputCostPerMillionMicros: 0,
      outputCostPerMillionMicros: 0,
    })).rejects.toThrow("完整填写");
  });

  it("uses the saved fallback key when loading fallback models", async () => {
    const harness = createHarness();
    harness.prisma.aiConfiguration.upsert.mockResolvedValue({
      id: 1,
      apiKeyEncrypted: "encrypted:primary-key",
      fallbackApiKeyEncrypted: "encrypted:fallback-key",
      fallbackBaseUrl: "https://fallback.example/v1",
      requestTimeoutSeconds: 60,
    });
    const fetchSpy = jest.spyOn(global, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({ data: [{ id: "fallback-model" }] }),
    } as Response);

    const result = await harness.service.listModels({ provider: "deepseek", credential: "fallback" });

    expect(result.models).toEqual([{ id: "fallback-model", label: "fallback-model" }]);
    expect(fetchSpy.mock.calls[0][0]).toEqual(new URL("https://fallback.example/v1/models"));
    expect(fetchSpy.mock.calls[0][1]).toEqual(expect.objectContaining({ headers: expect.objectContaining({ Authorization: "Bearer fallback-key" }) }));
  });

  it("switches to the configured fallback model only for a transient provider failure", async () => {
    const harness = createHarness();
    harness.prisma.aiConfiguration.upsert.mockResolvedValue({
      id: 1,
      enabled: true,
      provider: "openai-compatible",
      baseUrl: "https://primary.example/v1",
      model: "primary-model",
      apiKeyEncrypted: "encrypted:primary-key",
      fallbackEnabled: true,
      fallbackProvider: "deepseek",
      fallbackBaseUrl: "https://fallback.example/v1",
      fallbackModel: "fallback-model",
      fallbackApiKeyEncrypted: "encrypted:fallback-key",
      globalConcurrency: 2,
      userConcurrency: 1,
      maxOutputTokens: 2000,
      requestTimeoutSeconds: 60,
      dailyRequestLimit: 0,
      billingCurrency: "USD",
      inputCostPerMillionMicros: 0,
      outputCostPerMillionMicros: 0,
      fallbackInputCostPerMillionMicros: 2_000_000,
      fallbackCachedInputCostPerMillionMicros: 500_000,
      fallbackOutputCostPerMillionMicros: 3_000_000,
      updatedAt: new Date("2026-09-10T00:00:00.000Z"),
    });
    const fetchSpy = jest.spyOn(global, "fetch")
      .mockResolvedValueOnce({ ok: false, status: 503 } as Response)
      .mockResolvedValueOnce({ ok: true, json: async () => ({ choices: [{ message: { content: "备用回答" } }], usage: { prompt_tokens: 2, prompt_tokens_details: { cached_tokens: 1 }, completion_tokens: 1, total_tokens: 3 } }) } as Response);

    const result = await harness.service.complete({ userId: 9, operation: "fallback_test", messages: [{ role: "user", content: "ping" }] });

    expect(result).toMatchObject({ text: "备用回答", provider: "deepseek", model: "fallback-model" });
    expect(fetchSpy).toHaveBeenCalledTimes(2);
    expect(fetchSpy.mock.calls[1][0]).toEqual(new URL("https://fallback.example/v1/chat/completions"));
    expect(harness.prisma.aiInvocationLog.create).toHaveBeenCalledWith(expect.objectContaining({ data: expect.objectContaining({ status: "failed", provider: "openai-compatible" }) }));
    expect(harness.prisma.aiInvocationLog.create).toHaveBeenCalledWith(expect.objectContaining({ data: expect.objectContaining({ status: "success", provider: "deepseek", model: "fallback-model", cachedPromptTokens: 1, estimatedCostMicros: 6 }) }));
  });

  it("calls an OpenAI-compatible endpoint and stores only safe usage metadata", async () => {
    const harness = createHarness();
    harness.prisma.aiConfiguration.upsert.mockResolvedValue({
      id: 1,
      enabled: true,
      provider: "openai-compatible",
      baseUrl: "https://api.example.com/v1",
      model: "example-model",
      apiKeyEncrypted: "encrypted:secret-key",
      globalConcurrency: 2,
      userConcurrency: 1,
      maxOutputTokens: 2000,
      requestTimeoutSeconds: 60,
      dailyRequestLimit: 0,
      billingCurrency: "USD",
      inputCostPerMillionMicros: 1000000,
      cachedInputCostPerMillionMicros: 100000,
      outputCostPerMillionMicros: 2000000,
      fallbackInputCostPerMillionMicros: 2_000_000,
      fallbackOutputCostPerMillionMicros: 3_000_000,
      updatedAt: new Date("2026-09-10T00:00:00.000Z"),
    });
    const fetchSpy = jest.spyOn(global, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({ choices: [{ message: { content: "OK" } }], usage: { prompt_tokens: 100, prompt_tokens_details: { cached_tokens: 40 }, completion_tokens: 50, total_tokens: 150 } }),
    } as Response);

    const result = await harness.service.complete({
      userId: 9,
      operation: "article_outline",
      messages: [{ role: "user", content: "sensitive prompt body" }],
    });

    expect(fetchSpy).toHaveBeenCalledWith(new URL("https://api.example.com/v1/chat/completions"), expect.objectContaining({ method: "POST" }));
    expect(harness.prisma.aiInvocationLog.create).toHaveBeenCalledWith(expect.objectContaining({
      data: expect.objectContaining({ operation: "article_outline", promptTokens: 100, cachedPromptTokens: 40, totalTokens: 150, estimatedCostMicros: 164 }),
    }));
    expect(harness.prisma.aiInvocationLog.create.mock.calls[0][0].data).not.toHaveProperty("messages");
    expect(result.text).toBe("OK");
    expect(result.usage).toMatchObject({ promptTokens: 100, cachedPromptTokens: 40, completionTokens: 50, totalTokens: 150 });
    expect(harness.redis.releaseCounter).toHaveBeenCalledTimes(2);
  });

  it.each(["deepseek", "custom"] as const)("routes the %s provider through the OpenAI-compatible endpoint", async (provider) => {
    const harness = createHarness();
    const baseUrl = provider === "deepseek" ? "https://api.deepseek.com/v1" : "https://third-party.example/v1";
    harness.prisma.aiConfiguration.upsert.mockResolvedValue({
      id: 1,
      enabled: true,
      provider,
      baseUrl,
      model: provider === "deepseek" ? "deepseek-chat" : "custom-model",
      apiKeyEncrypted: "encrypted:secret-key",
      globalConcurrency: 2,
      userConcurrency: 1,
      maxOutputTokens: 2000,
      requestTimeoutSeconds: 60,
      dailyRequestLimit: 0,
      billingCurrency: "USD",
      inputCostPerMillionMicros: 0,
      outputCostPerMillionMicros: 0,
      updatedAt: new Date("2026-09-10T00:00:00.000Z"),
    });
    const fetchSpy = jest.spyOn(global, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({ choices: [{ message: { content: "OK" } }] }),
    } as Response);

    await harness.service.complete({ userId: 9, operation: "provider_test", messages: [{ role: "user", content: "ping" }] });

    expect(fetchSpy).toHaveBeenCalledWith(new URL(`${baseUrl}/chat/completions`), expect.objectContaining({ method: "POST" }));
  });

  it("rejects a request when the daily quota is full without contacting the provider", async () => {
    const harness = createHarness();
    harness.prisma.aiConfiguration.upsert.mockResolvedValue({
      id: 1,
      enabled: true,
      provider: "openai-compatible",
      baseUrl: "https://api.example.com/v1",
      model: "example-model",
      apiKeyEncrypted: "encrypted:secret-key",
      globalConcurrency: 2,
      userConcurrency: 1,
      maxOutputTokens: 2000,
      requestTimeoutSeconds: 60,
      dailyRequestLimit: 1,
      billingCurrency: "USD",
      inputCostPerMillionMicros: 0,
      outputCostPerMillionMicros: 0,
      updatedAt: new Date("2026-09-10T00:00:00.000Z"),
    });
    harness.redis.tryAcquireCounter.mockResolvedValueOnce(true).mockResolvedValueOnce(true).mockResolvedValueOnce(false);
    const fetchSpy = jest.spyOn(global, "fetch");

    await expect(harness.service.complete({ userId: 9, operation: "article_outline", messages: [{ role: "user", content: "private" }] })).rejects.toThrow("今日 AI 请求次数已用完");

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(harness.prisma.aiInvocationLog.create).toHaveBeenCalledWith(expect.objectContaining({ data: expect.objectContaining({ status: "rejected" }) }));
    expect(harness.redis.releaseCounter).toHaveBeenCalledTimes(2);
  });

  it("generates an article assistant result through the protected gateway", async () => {
    const harness = createHarness();
    harness.prisma.aiConfiguration.upsert.mockResolvedValue({
      id: 1,
      enabled: true,
      provider: "openai-compatible",
      baseUrl: "https://api.example.com/v1",
      model: "example-model",
      apiKeyEncrypted: "encrypted:secret-key",
      globalConcurrency: 2,
      userConcurrency: 1,
      maxOutputTokens: 2000,
      requestTimeoutSeconds: 60,
      dailyRequestLimit: 0,
      billingCurrency: "USD",
      inputCostPerMillionMicros: 0,
      outputCostPerMillionMicros: 0,
      updatedAt: new Date("2026-09-10T00:00:00.000Z"),
    });
    jest.spyOn(global, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({ choices: [{ message: { content: "一篇更清晰的文章标题" } }] }),
    } as Response);

    const result = await harness.service.articleAssistant(9, {
      operation: "title",
      title: "原始标题",
      content: "文章正文",
      locale: "zh-CN",
    });

    expect(result).toMatchObject({ operation: "title", text: "一篇更清晰的文章标题" });
    expect(harness.prisma.aiInvocationLog.create).toHaveBeenCalledWith(expect.objectContaining({
      data: expect.objectContaining({ operation: "article_assistant_title", status: "success" }),
    }));
  });

  it("uses selected text for body edits without duplicating the full article", async () => {
    const harness = createHarness();
    harness.prisma.aiConfiguration.upsert.mockResolvedValue({
      id: 1,
      enabled: true,
      provider: "openai-compatible",
      baseUrl: "https://api.example.com/v1",
      model: "example-model",
      apiKeyEncrypted: "encrypted:secret-key",
      globalConcurrency: 2,
      userConcurrency: 1,
      maxOutputTokens: 2000,
      requestTimeoutSeconds: 60,
      dailyRequestLimit: 0,
      billingCurrency: "USD",
      inputCostPerMillionMicros: 0,
      outputCostPerMillionMicros: 0,
      updatedAt: new Date("2026-09-10T00:00:00.000Z"),
    });
    const fetchSpy = jest.spyOn(global, "fetch").mockResolvedValue({
      ok: true,
      json: async () => ({ choices: [{ message: { content: "润色后的段落" } }] }),
    } as Response);

    await harness.service.articleAssistant(9, {
      operation: "polish",
      title: "文章标题",
      content: "整篇正文不应该再次进入正文材料",
      selectedText: "当前选中的段落",
      locale: "zh-CN",
    });

    const requestBody = JSON.parse(String(fetchSpy.mock.calls[0][1]?.body));
    expect(requestBody.messages[1].content).toContain("当前选中的段落");
    expect(requestBody.messages[1].content).not.toContain("整篇正文不应该再次进入正文材料");
  });
});
