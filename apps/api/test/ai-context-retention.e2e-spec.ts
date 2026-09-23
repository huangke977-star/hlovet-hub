import { AiService } from "../src/ai/ai.service";

function createHarness() {
  const prisma = {
    aiConfiguration: { upsert: jest.fn() },
    aiConversation: { findFirst: jest.fn(), findUnique: jest.fn(), update: jest.fn(), updateMany: jest.fn() },
    aiConversationMessage: { findMany: jest.fn(), count: jest.fn() },
  };
  const crypto = { decrypt: jest.fn(), isConfigured: jest.fn(() => true) };
  const redis = { tryAcquireCounter: jest.fn(), releaseCounter: jest.fn() };
  const service = new AiService(prisma as never, crypto as never, redis as never);
  return { prisma, service };
}

describe("AI context retention", () => {
  afterEach(() => {
    jest.useRealTimers();
  });

  it("limits chat history to the configured retention window", async () => {
    jest.useFakeTimers().setSystemTime(new Date("2026-09-22T12:00:00.000Z"));
    const harness = createHarness();
    harness.prisma.aiConfiguration.upsert.mockResolvedValue({
      enabled: true,
      provider: "custom",
      model: "test-model",
      contextRetentionDays: 7,
      contextMaxMessages: 24,
      contextSummaryThreshold: 32,
      ragEnabled: false,
      ragTopK: 6,
    });
    harness.prisma.aiConversation.findFirst.mockResolvedValue({ id: 41, userId: 9, title: "站内问题" });
    harness.prisma.aiConversation.findUnique.mockResolvedValue({ contextSummary: null, summaryMessageCount: 0, summaryUpdatedAt: null });
    harness.prisma.aiConversationMessage.count.mockResolvedValue(1);
    harness.prisma.aiConversationMessage.findMany.mockResolvedValue([]);
    jest.spyOn(harness.service, "complete").mockResolvedValue({ text: "回答", provider: "custom", model: "test-model", durationMs: 1, usage: { promptTokens: 1, cachedPromptTokens: null, completionTokens: 1, totalTokens: 2 } });
    jest.spyOn(harness.service as never, "buildChatContext" as never).mockResolvedValue({ text: "站内上下文", sources: [] } as never);
    jest.spyOn(harness.service as never, "persistChatTurn" as never).mockResolvedValue({ assistantMessageId: 2 } as never);

    await harness.service.chat({ id: 9 } as never, { conversationId: 41, message: "这篇文章讲了什么" });

    const historyQuery = harness.prisma.aiConversationMessage.findMany.mock.calls[0][0] as { where: { conversationId: number; createdAt: { gte: Date } } };
    expect(historyQuery.where.conversationId).toBe(41);
    expect(historyQuery.where.createdAt.gte.toISOString()).toBe("2026-09-15T12:00:00.000Z");
    expect(harness.prisma.aiConversationMessage.count).toHaveBeenCalledWith(expect.objectContaining({ where: { conversationId: 41, createdAt: { gte: expect.any(Date) } } }));
  });
});
