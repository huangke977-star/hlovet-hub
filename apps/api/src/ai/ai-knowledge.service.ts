import { BadRequestException, Injectable } from "@nestjs/common";
import { createHash } from "node:crypto";
import { Prisma } from "../generated/prisma/client";
import { AuthenticatedUser } from "../auth/auth.types";
import { ArticlesService } from "../articles/articles.service";
import { PrismaService } from "../prisma/prisma.service";
import { AiCapabilitiesService } from "./ai-capabilities.service";

export type KnowledgeSource = { type: "article"; id: number; slug: string; title: string; author: string };

@Injectable()
export class AiKnowledgeService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly articles: ArticlesService,
    private readonly capabilities: AiCapabilitiesService,
  ) {}

  async getOverview() {
    const [documents, chunks, ready, vectors] = await Promise.all([
      this.prisma.aiKnowledgeDocument.count(),
      this.prisma.aiKnowledgeChunk.count(),
      this.prisma.aiKnowledgeDocument.count({ where: { status: "ready" } }),
      this.prisma.aiKnowledgeChunk.count({ where: { embeddingJson: { not: null } } }),
    ]);
    return { documents, chunks, ready, vectors, semanticSearchAvailable: vectors > 0 };
  }

  async listDocuments(limit = 100) {
    const items = await this.prisma.aiKnowledgeDocument.findMany({
      take: Math.max(1, Math.min(200, Math.floor(limit))),
      orderBy: [{ updatedAt: "desc" }, { id: "desc" }],
      select: { id: true, sourceType: true, sourceId: true, slug: true, title: true, status: true, chunkCount: true, vectorCount: true, embeddingModel: true, errorSummary: true, lastIndexedAt: true, updatedAt: true },
    });
    return { items: items.map((item) => ({ ...item, lastIndexedAt: item.lastIndexedAt?.toISOString() ?? null, updatedAt: item.updatedAt.toISOString() })) };
  }

  async reindexDocument(id: number) {
    const document = await this.prisma.aiKnowledgeDocument.findUnique({ where: { id } });
    if (!document || document.sourceType !== "article") throw new BadRequestException("知识库文档不存在。\nThe knowledge document does not exist.");
    const article = await this.prisma.article.findUnique({ where: { id: document.sourceId }, select: { id: true, slug: true, title: true, summary: true, content: true, contentFormat: true, category: true, tags: true, updatedAt: true, status: true } });
    if (!article || article.status !== "published") {
      await this.prisma.aiKnowledgeDocument.delete({ where: { id: document.id } });
      return { id, removed: true, status: "removed" };
    }
    const result = await this.indexArticle(article);
    return { id, removed: false, status: result.status };
  }

  async reindex(limit = 50) {
    const safeLimit = Math.max(1, Math.min(500, Math.floor(limit)));
    const articles = await this.prisma.article.findMany({ where: { status: "published" }, orderBy: [{ updatedAt: "desc" }, { id: "desc" }], take: safeLimit, select: { id: true, slug: true, title: true, summary: true, content: true, contentFormat: true, category: true, tags: true, updatedAt: true } });
    let indexed = 0;
    let pending = 0;
    let failed = 0;
    for (const article of articles) {
      const result = await this.indexArticle(article);
      if (result.status === "ready") indexed += 1;
      else if (result.status === "pending_embedding") pending += 1;
      else failed += 1;
    }
    return { scanned: articles.length, indexed, pending, failed, overview: await this.getOverview() };
  }

  async search(user: AuthenticatedUser, query: string, limit = 6): Promise<{ text: string; sources: KnowledgeSource[]; mode: "semantic" | "keyword" | "none" }> {
    const normalizedQuery = query.trim();
    if (!normalizedQuery) return { text: "", sources: [], mode: "none" };
    const terms = this.terms(normalizedQuery);
    const candidates = await this.prisma.aiKnowledgeChunk.findMany({
      where: { document: { status: { in: ["ready", "pending_embedding"] } } },
      include: { document: true },
      orderBy: [{ updatedAt: "desc" }, { id: "desc" }],
      take: 500,
    });
    if (!candidates.length) return { text: "", sources: [], mode: "none" };
    let queryVector: number[] | null = null;
    try {
      queryVector = (await this.capabilities.embedTexts([normalizedQuery])).embeddings[0] ?? null;
    } catch {
      // Keyword fallback is intentional until an Embedding API is configured.
    }
    const scored = candidates.map((candidate) => {
      const keywordScore = this.keywordScore(candidate.document.title, candidate.content, terms);
      const vector = this.parseVector(candidate.embeddingJson);
      const vectorScore = queryVector && vector ? this.cosine(queryVector, vector) : 0;
      return { candidate, score: queryVector ? vectorScore * 0.75 + keywordScore * 0.25 : keywordScore };
    }).filter((item) => item.score > 0).sort((a, b) => b.score - a.score);
    const chosen = new Map<number, typeof scored[number]>();
    for (const item of scored) {
      if (chosen.has(item.candidate.document.sourceId)) continue;
      chosen.set(item.candidate.document.sourceId, item);
      if (chosen.size >= Math.max(1, Math.min(12, limit))) break;
    }
    const sources: KnowledgeSource[] = [];
    const parts: string[] = [];
    for (const item of chosen.values()) {
      try {
        const article = await this.articles.getAiReadableContext(user, { id: item.candidate.document.sourceId });
        const source = { type: "article" as const, id: article.id, slug: article.slug, title: article.title, author: article.author.nickname || article.author.username };
        sources.push(source);
        parts.push(`[${sources.length}] ${article.title}\n作者：${source.author}\n摘要：${article.summary || "无"}\n相关内容：\n${item.candidate.content.slice(0, 6000)}`);
      } catch {
        // The document may be private or deleted. Permission is checked again before it reaches the model.
      }
    }
    return { text: parts.join("\n\n"), sources, mode: queryVector ? "semantic" : parts.length ? "keyword" : "none" };
  }

  private async indexArticle(article: { id: number; slug: string; title: string; summary: string; content: string; contentFormat: string; category: string; tags: string }) {
    const sourceText = [article.title, article.summary, `分类：${article.category}`, `标签：${article.tags}`, article.content].filter(Boolean).join("\n\n");
    const contentHash = createHash("sha256").update(sourceText).digest("hex");
    const document = await this.prisma.aiKnowledgeDocument.upsert({ where: { sourceType_sourceId: { sourceType: "article", sourceId: article.id } }, create: { sourceType: "article", sourceId: article.id, slug: article.slug, title: article.title, contentHash, status: "pending" }, update: { slug: article.slug, title: article.title, contentHash, status: "pending", errorSummary: null } });
    const chunks = this.splitText(sourceText);
    let embeddings: number[][] = [];
    let embeddingModel: string | null = null;
    let status = "pending_embedding";
    let errorSummary: string | null = null;
    try {
      const result = await this.capabilities.embedTexts(chunks.map((chunk) => chunk.content));
      embeddings = result.embeddings;
      embeddingModel = result.model;
      status = "ready";
    } catch (error) {
      errorSummary = error instanceof Error ? error.message.slice(0, 500) : "Embedding service is not configured.";
    }
    await this.prisma.$transaction(async (tx) => {
      await tx.aiKnowledgeChunk.deleteMany({ where: { documentId: document.id } });
      if (chunks.length) {
        await tx.aiKnowledgeChunk.createMany({ data: chunks.map((chunk, index) => ({ documentId: document.id, sequence: index, content: chunk.content, contentHash: createHash("sha256").update(chunk.content).digest("hex"), embeddingJson: embeddings[index] ? JSON.stringify(embeddings[index]) : null, embeddingModel, embeddingDimension: embeddings[index]?.length ?? null, tokenCount: Math.ceil(chunk.content.length / 2), metadata: { articleId: article.id, heading: chunk.heading } as Prisma.InputJsonValue })) });
      }
      await tx.aiKnowledgeDocument.update({ where: { id: document.id }, data: { status, chunkCount: chunks.length, vectorCount: embeddings.length, embeddingModel, embeddingDimension: embeddings[0]?.length ?? null, errorSummary, lastIndexedAt: status === "ready" ? new Date() : null } });
    });
    return { status };
  }

  private splitText(value: string): Array<{ content: string; heading: string }> {
    const clean = value.replace(/<[^>]+>/g, " ").replace(/\r/g, "").replace(/[ \t]+\n/g, "\n").trim();
    const max = 1800;
    const overlap = 180;
    const chunks: Array<{ content: string; heading: string }> = [];
    let start = 0;
    while (start < clean.length) {
      let end = Math.min(clean.length, start + max);
      if (end < clean.length) {
        const breakAt = Math.max(clean.lastIndexOf("\n", end), clean.lastIndexOf("。", end), clean.lastIndexOf(".", end));
        if (breakAt > start + 600) end = breakAt + 1;
      }
      const content = clean.slice(start, end).trim();
      if (content) chunks.push({ content, heading: content.split("\n").find((line) => /^#{1,6}\s/.test(line))?.replace(/^#{1,6}\s*/, "") ?? "" });
      if (end >= clean.length) break;
      start = Math.max(start + 1, end - overlap);
    }
    return chunks.length ? chunks : [{ content: clean || "（空文章）", heading: "" }];
  }

  private terms(query: string): string[] {
    const compact = query.toLowerCase().replace(/[^\p{L}\p{N}_-]+/gu, " ").trim();
    const words = compact.split(/\s+/).filter((word) => word.length >= 2);
    const characters = [...compact.replace(/\s/g, "")].filter((char) => /[\u3400-\u9fff]/u.test(char));
    return [...new Set([...words, ...characters])].slice(0, 24);
  }

  private keywordScore(title: string, content: string, terms: string[]): number {
    const haystack = `${title}\n${content}`.toLowerCase();
    return terms.reduce((score, term) => score + (haystack.includes(term) ? (title.toLowerCase().includes(term) ? 2 : 1) : 0), 0);
  }

  private parseVector(value: string | null): number[] | null {
    if (!value) return null;
    try {
      const parsed: unknown = JSON.parse(value);
      return Array.isArray(parsed) && parsed.every((item) => typeof item === "number") ? parsed : null;
    } catch {
      return null;
    }
  }

  private cosine(left: number[], right: number[]): number {
    if (left.length !== right.length || !left.length) return 0;
    let dot = 0;
    let leftNorm = 0;
    let rightNorm = 0;
    for (let index = 0; index < left.length; index += 1) {
      dot += left[index] * right[index];
      leftNorm += left[index] ** 2;
      rightNorm += right[index] ** 2;
    }
    return leftNorm && rightNorm ? dot / Math.sqrt(leftNorm * rightNorm) : 0;
  }
}
