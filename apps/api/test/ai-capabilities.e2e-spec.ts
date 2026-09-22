import { AiCapabilitiesService } from "../src/ai/ai-capabilities.service";

function createHarness() {
  const prisma = {
    aiCapabilityConfiguration: {
      upsert: jest.fn(),
      update: jest.fn(),
    },
    aiUsageLog: {
      create: jest.fn(async () => undefined),
      aggregate: jest.fn(),
    },
  };
  const crypto = {
    encrypt: jest.fn((value: string) => `encrypted:${value}`),
    decrypt: jest.fn((value: string) => value.replace(/^encrypted:/, "")),
  };
  const redis = {
    tryAcquireCounter: jest.fn(async () => true),
    releaseCounter: jest.fn(async () => undefined),
  };
  return { prisma, crypto, redis, service: new AiCapabilitiesService(prisma as never, crypto as never, redis as never) };
}

function embeddingConfig(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    capability: "embedding",
    enabled: true,
    provider: "openai-compatible",
    baseUrl: "https://primary.example/v1",
    model: "primary-embedding",
    apiKeyEncrypted: "encrypted:primary-key",
    fallbackEnabled: true,
    fallbackProvider: "deepseek",
    fallbackBaseUrl: "https://fallback.example/v1",
    fallbackModel: "fallback-embedding",
    fallbackApiKeyEncrypted: "encrypted:fallback-key",
    globalConcurrency: 1,
    userConcurrency: 1,
    requestTimeoutSeconds: 60,
    dailyRequestLimit: 0,
    monthlyBudgetMicros: 0,
    billingCurrency: "USD",
    inputCostPerMillionMicros: 0,
    outputCostPerMillionMicros: 0,
    fallbackInputCostPerMillionMicros: 2_000_000,
    fallbackOutputCostPerMillionMicros: 3_000_000,
    unitCostMicros: 0,
    unitName: "tokens",
    maxInputBytes: 2 * 1024 * 1024,
    metadata: null,
    createdAt: new Date("2026-09-10T00:00:00.000Z"),
    updatedAt: new Date("2026-09-10T00:00:00.000Z"),
    ...overrides,
  };
}

describe("AI capability fallback", () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("keeps fallback settings when an older client omits fallback fields", async () => {
    const harness = createHarness();
    const current = embeddingConfig();
    harness.prisma.aiCapabilityConfiguration.upsert.mockResolvedValue(current);
    harness.prisma.aiCapabilityConfiguration.update.mockImplementation(async ({ data }: { data: Record<string, unknown> }) => ({ ...current, ...data }));

    const result = await harness.service.updateConfiguration("embedding", {
      enabled: false,
      provider: "openai-compatible",
      baseUrl: "",
      model: "",
      globalConcurrency: 1,
      userConcurrency: 1,
      requestTimeoutSeconds: 60,
      dailyRequestLimit: 0,
      monthlyBudgetMicros: 0,
      billingCurrency: "USD",
      inputCostPerMillionMicros: 0,
      outputCostPerMillionMicros: 0,
      unitCostMicros: 0,
      unitName: "tokens",
      maxInputBytes: 2 * 1024 * 1024,
    });

    const data = harness.prisma.aiCapabilityConfiguration.update.mock.calls[0][0].data as Record<string, unknown>;
    expect(data).not.toHaveProperty("fallbackProvider");
    expect(data).not.toHaveProperty("fallbackBaseUrl");
    expect(data).not.toHaveProperty("fallbackModel");
    expect(result).toMatchObject({ fallbackEnabled: true, fallbackProvider: "deepseek", fallbackModel: "fallback-embedding" });
  });

  it("records the primary failure and fallback success for embedding calls", async () => {
    const harness = createHarness();
    harness.prisma.aiCapabilityConfiguration.upsert.mockResolvedValue(embeddingConfig());
    jest.spyOn(global, "fetch")
      .mockResolvedValueOnce({ ok: false, status: 503 } as Response)
      .mockResolvedValueOnce({ ok: true, json: async () => ({ data: [{ embedding: [0.1, 0.2] }], usage: { prompt_tokens: 2, total_tokens: 2 } }) } as Response);

    const result = await harness.service.embedTexts(["hello"]);

    expect(result).toMatchObject({ model: "fallback-embedding", embeddings: [[0.1, 0.2]] });
    expect(harness.prisma.aiUsageLog.create).toHaveBeenCalledTimes(2);
    const usageCalls = harness.prisma.aiUsageLog.create.mock.calls as unknown as Array<[{ data: Record<string, unknown> }] >;
    expect(usageCalls[0][0].data).toMatchObject({ status: "failed", provider: "openai-compatible", model: "primary-embedding" });
    expect(usageCalls[1][0].data).toMatchObject({ status: "success", provider: "deepseek", model: "fallback-embedding", estimatedCostMicros: 4, metadata: { fallback: true } });
  });

  it("does not make an external request when an embedding capability is disabled", async () => {
    const harness = createHarness();
    harness.prisma.aiCapabilityConfiguration.upsert.mockResolvedValue(embeddingConfig({ enabled: false, apiKeyEncrypted: null }));
    const fetchSpy = jest.spyOn(global, "fetch");

    await expect(harness.service.embedTexts(["local keyword search only"])).rejects.toThrow("尚未配置");

    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
