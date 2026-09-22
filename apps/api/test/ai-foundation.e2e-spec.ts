import { AiKnowledgeService } from "../src/ai/ai-knowledge.service";

function createKnowledgeHarness() {
  const prisma = {
    aiKnowledgeDocument: {
      count: jest.fn(),
      findMany: jest.fn(),
      findUnique: jest.fn(),
      upsert: jest.fn(),
      update: jest.fn(),
      delete: jest.fn(),
    },
    aiKnowledgeChunk: {
      count: jest.fn(),
      findMany: jest.fn(),
      deleteMany: jest.fn(),
      createMany: jest.fn(),
    },
    article: { findMany: jest.fn() },
    $transaction: jest.fn(),
  };
  prisma.$transaction.mockImplementation(async (callback: (value: typeof prisma) => Promise<unknown>) => callback(prisma));
  const articles = { getAiReadableContext: jest.fn() };
  const capabilities = { embedTexts: jest.fn() };
  return { prisma, articles, capabilities, service: new AiKnowledgeService(prisma as never, articles as never, capabilities as never) };
}

describe("AI knowledge foundation", () => {
  it("creates keyword-ready chunks without calling an external embedding service", async () => {
    const harness = createKnowledgeHarness();
    harness.prisma.article.findMany.mockResolvedValue([{ id: 7, slug: "keyword-only", title: "关键词文章", summary: "摘要", content: "数据库与权限说明。", contentFormat: "markdown", category: "技术", tags: "数据库", updatedAt: new Date() }]);
    harness.prisma.aiKnowledgeDocument.upsert.mockResolvedValue({ id: 11 });
    harness.prisma.aiKnowledgeDocument.update.mockResolvedValue({});
    harness.prisma.aiKnowledgeChunk.deleteMany.mockResolvedValue({});
    harness.prisma.aiKnowledgeChunk.createMany.mockResolvedValue({});
    harness.capabilities.embedTexts.mockRejectedValue(new Error("embedding is not configured"));
    harness.prisma.aiKnowledgeDocument.count.mockResolvedValue(1);
    harness.prisma.aiKnowledgeChunk.count.mockResolvedValue(1);

    const result = await harness.service.reindex(1);

    expect(result).toMatchObject({ scanned: 1, indexed: 0, keywordReady: 1, pending: 0, failed: 0 });
    expect(harness.capabilities.embedTexts).toHaveBeenCalledTimes(1);
    expect(harness.prisma.aiKnowledgeChunk.createMany).toHaveBeenCalledWith(expect.objectContaining({ data: [expect.objectContaining({ embeddingJson: null, embeddingModel: null })] }));
    expect(harness.prisma.aiKnowledgeDocument.update).toHaveBeenCalledWith(expect.objectContaining({ data: expect.objectContaining({ status: "keyword_ready", vectorCount: 0 }) }));
  });

  it("uses keyword retrieval and rechecks article visibility before returning sources", async () => {
    const harness = createKnowledgeHarness();
    harness.prisma.aiKnowledgeChunk.findMany.mockResolvedValue([{
      id: 21,
      content: "权限过滤后的正文片段",
      embeddingJson: null,
      updatedAt: new Date(),
      document: { status: "keyword_ready", sourceId: 7, title: "权限文章", slug: "visible-article" },
    }]);
    harness.capabilities.embedTexts.mockRejectedValue(new Error("embedding is not configured"));
    harness.articles.getAiReadableContext.mockResolvedValue({ id: 7, slug: "visible-article", title: "权限文章", summary: "摘要", content: "正文", author: { username: "author", nickname: "作者" } });

    const result = await harness.service.search({ id: 9 } as never, "权限过滤");

    expect(result.mode).toBe("keyword");
    expect(result.sources).toEqual([{ type: "article", id: 7, slug: "visible-article", title: "权限文章", author: "作者" }]);
    expect(harness.articles.getAiReadableContext).toHaveBeenCalledWith({ id: 9 }, { id: 7 });
    expect(result.text).toContain("权限过滤后的正文片段");
  });

  it("keeps keyword mode when query embeddings exist but matching chunks do not have vectors", async () => {
    const harness = createKnowledgeHarness();
    harness.prisma.aiKnowledgeChunk.findMany.mockResolvedValue([{
      id: 22,
      content: "数据库权限说明",
      embeddingJson: null,
      updatedAt: new Date(),
      document: { status: "keyword_ready", sourceId: 8, title: "数据库文章", slug: "keyword-with-query-vector" },
    }]);
    harness.capabilities.embedTexts.mockResolvedValue({ embeddings: [[0.1, 0.2]], model: "configured-embedding" });
    harness.articles.getAiReadableContext.mockResolvedValue({ id: 8, slug: "keyword-with-query-vector", title: "数据库文章", summary: "摘要", content: "正文", author: { username: "author", nickname: "作者" } });

    const result = await harness.service.search({ id: 9 } as never, "数据库权限");

    expect(result.mode).toBe("keyword");
    expect(result.sources).toHaveLength(1);
  });

  it("omits chunks that fail the second article visibility check", async () => {
    const harness = createKnowledgeHarness();
    harness.prisma.aiKnowledgeChunk.findMany.mockResolvedValue([
      { id: 23, content: "权限文章一", embeddingJson: null, updatedAt: new Date(), document: { status: "keyword_ready", sourceId: 9, title: "权限文章一", slug: "hidden" } },
      { id: 24, content: "权限文章二", embeddingJson: null, updatedAt: new Date(), document: { status: "keyword_ready", sourceId: 10, title: "权限文章二", slug: "visible" } },
    ]);
    harness.capabilities.embedTexts.mockRejectedValue(new Error("embedding is not configured"));
    harness.articles.getAiReadableContext.mockImplementation(async (_user: unknown, input: { id: number }) => {
      if (input.id === 9) throw new Error("not visible");
      return { id: 10, slug: "visible", title: "权限文章二", summary: "摘要", content: "正文", author: { username: "author", nickname: "作者" } };
    });

    const result = await harness.service.search({ id: 9 } as never, "权限文章");

    expect(result.sources).toEqual([{ type: "article", id: 10, slug: "visible", title: "权限文章二", author: "作者" }]);
    expect(result.text).not.toContain("权限文章一");
  });
});
