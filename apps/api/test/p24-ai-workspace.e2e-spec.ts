import { AiService } from "../src/ai/ai.service";
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

function createHarness() {
  const prisma = {
    user: { findUnique: jest.fn() },
    userReputationLedger: { findMany: jest.fn() },
    userSubscription: { findMany: jest.fn() },
    articleTopicSubscription: { findMany: jest.fn() },
    articleCollectionSubscription: { findMany: jest.fn() },
    articleTagSubscription: { findMany: jest.fn() },
    articleResourceExchange: { findMany: jest.fn() },
    article: { findMany: jest.fn() },
    aiConversation: { findFirst: jest.fn() },
    aiToolInvocation: { create: jest.fn(), findFirst: jest.fn(), update: jest.fn() },
  };
  const crypto = { isConfigured: jest.fn(() => true), encrypt: jest.fn(), decrypt: jest.fn() };
  const redis = { tryAcquireCounter: jest.fn(), releaseCounter: jest.fn() };
  const articles = {
    searchAiReadableArticles: jest.fn(),
    getAiReadableContext: jest.fn(),
    create: jest.fn(),
  };
  return { prisma, articles, service: new AiService(prisma as never, crypto as never, redis as never, articles as never) };
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

  it("requires a short-lived confirmation before creating an article draft", async () => {
    const harness = createHarness();
    harness.prisma.aiToolInvocation.create.mockResolvedValue({ id: 43 });

    const prepared = await harness.service.executeTool(user, "create_article_draft", { input: { title: "待确认草稿", content: "正文" } }) as { status: string; confirmationToken?: string };

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
});
