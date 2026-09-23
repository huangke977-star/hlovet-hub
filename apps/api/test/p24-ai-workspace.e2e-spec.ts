import { AiService } from "../src/ai/ai.service";
import { AiCapabilitiesService } from "../src/ai/ai-capabilities.service";
import { createHash } from "node:crypto";
import type { AuthenticatedUser } from "../src/auth/auth.types";

const user: AuthenticatedUser = {
  id: 9,
  username: "reader",
  nickname: "Reader",
  email: "reader@example.com",
  status: "active",
  isSuperAdmin: false,
  isAdministrator: false,
  avatarUrl: null,
  profileBio: "",
  createdAt: new Date("2026-09-18T00:00:00.000Z"),
  appearance: { themeId: "cloud-blue", customAccent: "#1814f0", customSurface: "#fff", customForeground: "#111", customMuted: "#666", cardAlpha: 50, glassBlur: 18, glassTint: "#fff", glassTintAlpha: 0 },
  role: { code: "qi_refining", name: "练气", level: 10 },
};

function createHarness(knowledge: { search: jest.Mock } = { search: jest.fn() }) {
  const prisma = {
    user: { findUnique: jest.fn() },
    userReputationLedger: { findMany: jest.fn() },
    userSubscription: { findMany: jest.fn() },
    articleTopicSubscription: { findMany: jest.fn() },
    articleCollectionSubscription: { findMany: jest.fn() },
    articleTagSubscription: { findMany: jest.fn() },
    articleResourceExchange: { findMany: jest.fn() },
    article: { findMany: jest.fn() },
    aiConfiguration: { upsert: jest.fn() },
    articleTaxonomy: { findMany: jest.fn() },
    aiConversation: { findFirst: jest.fn() },
    aiConversationMessage: { findFirst: jest.fn() },
    aiQualityFeedback: { findFirst: jest.fn(), update: jest.fn(), create: jest.fn() },
    aiToolInvocation: { create: jest.fn(), findFirst: jest.fn(), update: jest.fn() },
  };
  const crypto = { isConfigured: jest.fn(() => true), encrypt: jest.fn(), decrypt: jest.fn() };
  const redis = { tryAcquireCounter: jest.fn(), releaseCounter: jest.fn() };
  const articles = {
    searchAiReadableArticles: jest.fn(),
    listAiRecommendedArticles: jest.fn(),
    getAiReadableContext: jest.fn(),
    create: jest.fn(),
  };
  return { prisma, articles, knowledge, service: new AiService(prisma as never, crypto as never, redis as never, articles as never, knowledge as never) };
}

function createMediaHarness() {
  const prisma = {
    aiCapabilityConfiguration: { upsert: jest.fn() },
    aiMediaTask: { findMany: jest.fn(), findFirst: jest.fn() },
  };
  const crypto = { decrypt: jest.fn(() => "secret") };
  const redis = { tryAcquireCounter: jest.fn(), releaseCounter: jest.fn() };
  return { prisma, service: new AiCapabilitiesService(prisma as never, crypto as never, redis as never) };
}

describe("P24 AI workspace", () => {
  it("exposes a fixed tool allowlist and keeps personal summary reads auditable", async () => {
    const harness = createHarness();
    harness.prisma.user.findUnique.mockResolvedValue({ username: "reader", nickname: "Reader", experience: 88, points: 12, status: "active", role: user.role });
    harness.prisma.userReputationLedger.findMany.mockResolvedValue([]);
    harness.prisma.aiToolInvocation.create.mockResolvedValue({ id: 41, createdAt: new Date("2026-09-18T01:00:00.000Z") });

    expect(harness.service.listTools().map((tool) => tool.name)).toEqual(expect.arrayContaining(["get_my_summary", "get_article_context", "create_article_draft"]));
    await expect(harness.service.executeTool(user, "delete_everything", {})).rejects.toThrow("不支持");

    const result = await harness.service.executeTool(user, "get_my_summary", {});

    expect(result.status).toBe("success");
    expect(harness.prisma.aiToolInvocation.create).toHaveBeenCalledWith(expect.objectContaining({ data: expect.objectContaining({ toolName: "get_my_summary", status: "success" }) }));
  });

  it("uses the article service for permission-filtered article context", async () => {
    const harness = createHarness();
    harness.articles.getAiReadableContext.mockResolvedValue({ id: 3, title: "仅可见文章", slug: "visible-only", content: "公开片段", contentFormat: "markdown", summary: "摘要", category: "", tags: [], author: { username: "author", nickname: "Author" }, publishedAt: null });
    harness.prisma.aiToolInvocation.create.mockResolvedValue({ id: 42, createdAt: new Date("2026-09-18T01:00:00.000Z") });

    const result = await harness.service.executeTool(user, "get_article_context", { input: { articleSlug: "visible-only" } }) as { output: { article: { slug: string } } };

    expect(harness.articles.getAiReadableContext).toHaveBeenCalledWith(user, { id: undefined, slug: "visible-only" });
    expect((result.output as { article: { slug: string } }).article.slug).toBe("visible-only");
  });

  it("loads latest readable and recommended articles for broad site-content questions", async () => {
    const knowledge = { search: jest.fn() };
    const harness = createHarness(knowledge);
    harness.prisma.aiConfiguration.upsert.mockResolvedValue({ ragEnabled: false, ragTopK: 6 });
    harness.articles.searchAiReadableArticles.mockImplementation(async (_currentUser, query: string) => query
      ? []
      : [{ id: 3, title: "最新可见文章", slug: "latest-visible", summary: "摘要", category: "", tags: [], publishedAt: null, author: { id: 1, username: "author", nickname: "Author" } }]);
    harness.articles.listAiRecommendedArticles.mockResolvedValue([{ id: 4, title: "推荐可见文章", slug: "recommended-visible", summary: "推荐摘要", category: "", tags: [], publishedAt: null, author: { id: 2, username: "author2", nickname: "Author 2" } }]);
    harness.articles.getAiReadableContext.mockImplementation(async (_currentUser, input: { id?: number }) => ({ id: input.id ?? 0, title: input.id === 4 ? "推荐可见文章" : "最新可见文章", slug: input.id === 4 ? "recommended-visible" : "latest-visible", content: "可读取正文", contentFormat: "markdown", summary: "摘要", category: "", tags: [], author: { username: "author", nickname: "Author" }, publishedAt: null }));

    const buildContext = (harness.service as unknown as { buildChatContext: (currentUser: AuthenticatedUser, dto: { message: string }, history: Array<{ sources: null }>) => Promise<{ text: string; sources: Array<{ id: number }> }> }).buildChatContext.bind(harness.service);
    const result = await buildContext(user, { message: "你能推荐一些适合我的文章，并列出最新内容吗？" }, []);

    expect(harness.articles.searchAiReadableArticles).toHaveBeenCalledWith(user, "", 8);
    expect(harness.articles.listAiRecommendedArticles).toHaveBeenCalledWith(user, 6);
    expect(result.sources.map((source) => source.id)).toEqual([3, 4]);
    expect(result.text).toContain("最新可见文章");
    expect(result.text).toContain("推荐可见文章");
    expect(knowledge.search).not.toHaveBeenCalled();
  });

  it("exposes read-only tools for visible and personalized article lists", async () => {
    const harness = createHarness();
    harness.prisma.aiToolInvocation.create.mockResolvedValue({ id: 45, createdAt: new Date("2026-09-18T01:00:00.000Z") });
    harness.articles.searchAiReadableArticles.mockResolvedValue([{ id: 3, title: "可见文章" }]);
    harness.articles.listAiRecommendedArticles.mockResolvedValue([{ id: 4, title: "推荐文章" }]);

    expect(harness.service.listTools().map((tool) => tool.name)).toEqual(expect.arrayContaining(["list_visible_articles", "list_recommended_articles"]));
    await harness.service.executeTool(user, "list_visible_articles", { input: { limit: 12 } });
    await harness.service.executeTool(user, "list_recommended_articles", { input: { limit: 8 } });

    expect(harness.articles.searchAiReadableArticles).toHaveBeenCalledWith(user, "", 12);
    expect(harness.articles.listAiRecommendedArticles).toHaveBeenCalledWith(user, 8);
  });

  it("requires a short-lived confirmation before creating an article draft", async () => {
    const harness = createHarness();
    harness.prisma.aiToolInvocation.create.mockResolvedValue({ id: 43 });
    harness.prisma.articleTaxonomy.findMany.mockResolvedValue([]);
    jest.spyOn(harness.service, "complete").mockResolvedValue({ text: JSON.stringify({ title: "待确认草稿", summary: "摘要", category: "", tags: "", content: "正文" }), provider: "custom", model: "test", durationMs: 1, usage: { promptTokens: 1, cachedPromptTokens: null, completionTokens: 1, totalTokens: 2 } });

    const prepared = await harness.service.executeTool(user, "create_article_draft", { input: { description: "写一篇待确认文章" } }) as { status: string; confirmationToken?: string };

    expect(prepared.status).toBe("awaiting_confirmation");
    expect(prepared.confirmationToken).toBeTruthy();
    expect(harness.articles.create).not.toHaveBeenCalled();
    expect(harness.prisma.aiToolInvocation.create).toHaveBeenCalledWith(expect.objectContaining({ data: expect.objectContaining({ status: "awaiting_confirmation", requiresConfirmation: true }) }));
  });

  it("creates the draft only after the matching confirmation token is supplied", async () => {
    const harness = createHarness();
    const preparedToken = "confirm-me";
    const hash = createHash("sha256").update(preparedToken).digest("hex");
    harness.prisma.aiToolInvocation.findFirst.mockResolvedValue({ id: 44, confirmationTokenHash: hash, confirmationExpiresAt: new Date(Date.now() + 60_000), input: { title: "确认草稿", content: "正文" } });
    harness.articles.create.mockResolvedValue({ id: 88, slug: "confirmed-draft", title: "确认草稿" });
    harness.prisma.aiToolInvocation.update.mockResolvedValue({});

    const result = await harness.service.confirmTool(user, 44, preparedToken);

    expect(result.article.slug).toBe("confirmed-draft");
    expect(harness.articles.create).toHaveBeenCalledWith(user, expect.objectContaining({ status: "draft", title: "确认草稿", content: "正文" }));
  });

  it("stores feedback only for an assistant message owned by the current user", async () => {
    const harness = createHarness();
    harness.prisma.aiConversationMessage.findFirst.mockResolvedValue({ id: 51, conversationId: 12 });
    harness.prisma.aiQualityFeedback.findFirst.mockResolvedValue(null);
    harness.prisma.aiQualityFeedback.create.mockResolvedValue({ id: 61, conversationId: 12, messageId: 51, rating: 1 });

    const result = await harness.service.recordQualityFeedback(9, { conversationId: 12, messageId: 51, rating: 1 });

    expect(result).toEqual({ id: 61, conversationId: 12, messageId: 51, rating: 1 });
    expect(harness.prisma.aiConversationMessage.findFirst).toHaveBeenCalledWith(expect.objectContaining({ where: expect.objectContaining({ id: 51, conversationId: 12, role: "assistant", conversation: { userId: 9 } }) }));
    expect(harness.prisma.aiQualityFeedback.create).toHaveBeenCalledWith(expect.objectContaining({ data: { userId: 9, conversationId: 12, messageId: 51, rating: 1, note: null } }));
  });

  it("exposes only safe media capability status to users", async () => {
    const harness = createMediaHarness();
    harness.prisma.aiCapabilityConfiguration.upsert.mockImplementation(async ({ where }: { where: { capability: string } }) => ({
      capability: where.capability,
      enabled: false,
      provider: "custom",
      baseUrl: "https://provider.invalid",
      model: "private-model",
      apiKeyEncrypted: "encrypted-secret",
      maxInputBytes: 1024,
      unitName: "image",
    }));

    const result = await harness.service.getUserMediaCapabilities();

    expect(result.items).toHaveLength(3);
    expect(result.items.every((item) => item.available === false)).toBe(true);
    expect(JSON.stringify(result)).not.toContain("encrypted-secret");
    expect(JSON.stringify(result)).not.toContain("private-model");
  });

  it("does not expose stored image data in the media task list", async () => {
    const harness = createMediaHarness();
    const imageData = Buffer.from("private-image").toString("base64");
    harness.prisma.aiMediaTask.findMany.mockResolvedValue([{
      id: 7,
      capability: "image_generation",
      status: "completed",
      prompt: "cover",
      inputMimeType: null,
      inputBytes: null,
      resultText: imageData,
      resultUrl: null,
      errorSummary: null,
      createdAt: new Date("2026-09-18T01:00:00.000Z"),
      startedAt: null,
      completedAt: new Date("2026-09-18T01:00:01.000Z"),
    }]);

    const result = await harness.service.listMediaTasks(9);

    expect(result.items[0]).toMatchObject({ id: 7, hasStoredImage: true, resultPreview: null });
    expect(JSON.stringify(result)).not.toContain(imageData);
  });

  it("keeps media task reads scoped to the owning user", async () => {
    const harness = createMediaHarness();
    harness.prisma.aiMediaTask.findFirst.mockResolvedValue(null);

    await expect(harness.service.getMediaTask(9, 100)).rejects.toThrow("未找到");
    await expect(harness.service.getMediaTaskImage(9, 100)).rejects.toThrow("不可用");
    expect(harness.prisma.aiMediaTask.findFirst).toHaveBeenLastCalledWith({ where: { id: 100, userId: 9, capability: "image_generation", status: "completed" } });
  });
});
