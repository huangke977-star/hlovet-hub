import { ArticleStatus, ArticleVisibility, Prisma, ResourceDeliveryStatus } from "../src/generated/prisma/client";
import { ArticlesService } from "../src/articles/articles.service";
import { AuthenticatedUser } from "../src/auth/auth.types";
import { parseArticleContent, sanitizeArticleHtml } from "../src/articles/article-resources";
import { PrismaService } from "../src/prisma/prisma.service";
import { RedisService } from "../src/redis/redis.service";
import { ReputationService } from "../src/reputation/reputation.service";
import { SiteSettingsService } from "../src/site-settings/site-settings.service";
import { ChatAttachmentsService } from "../src/social/chat-attachments.service";
import { ForbiddenException } from "@nestjs/common";

const sourceContent = [
  "公开正文。",
  "",
  ":::resource{points=10}",
  "第一段需要兑换的内容。",
  ":::",
  "",
  "中间公开正文。",
  "",
  ":::resource{points=5}",
  "第二段需要兑换的内容。",
  ":::",
].join("\n");

const parsedContent = parseArticleContent(sourceContent);
const firstBlock = parsedContent.blocks[0];
const secondBlock = parsedContent.blocks[1];

const viewer: AuthenticatedUser = {
  id: 7,
  username: "reader",
  nickname: "读者",
  email: "reader@example.com",
  status: "active",
  isSuperAdmin: false,
  avatarUrl: null,
  profileBio: "",
  createdAt: new Date(),
  appearance: {
    themeId: "cloud-blue",
    customAccent: "#1814f0",
    customSurface: "#dfc8c8",
    customForeground: "#2b2530",
    customMuted: "#665867",
    cardAlpha: 50,
    glassBlur: 18,
    glassTint: "#fff3f6",
    glassTintAlpha: 0,
  },
  role: { code: "qi_refining", name: "练气", level: 10 },
};

function resourceArticle() {
  return {
    id: 12,
    authorId: 8,
    title: "积分资源文章",
    slug: "resource-article",
    summary: "兑换前可以看到这段摘要。",
    content: sourceContent,
    coverPath: null,
    category: "资源",
    tags: "资源",
    titleColor: "",
    visibility: ArticleVisibility.public,
    status: ArticleStatus.published,
    isPinned: false,
    pinOrder: 0,
    publishedAt: new Date("2026-08-16T00:00:00.000Z"),
    blockedReason: null,
    viewCount: 1,
    likeCount: 0,
    favoriteCount: 0,
    commentCount: 0,
    isPointResource: false,
    pointCost: 0,
    searchText: "",
    searchPinyin: "",
    createdAt: new Date("2026-08-16T00:00:00.000Z"),
    updatedAt: new Date("2026-08-16T00:00:00.000Z"),
    author: {
      id: 8,
      nickname: "作者",
      username: "author",
      avatarStoredName: null,
      isSuperAdmin: false,
      role: { code: "foundation_building", name: "筑基", level: 20 },
    },
    allowedRoles: [],
    images: [],
    likes: [],
    favorites: [],
    collectionItems: [],
    topicItems: [],
    comments: [],
  };
}

function createService(prisma: object, reputation: object = {}) {
  return new ArticlesService(
    prisma as PrismaService,
    {} as SiteSettingsService,
    {} as RedisService,
    reputation as ReputationService,
  );
}

/** 为附件访问边界构建服务；prisma 提供文章和兑换记录，storage 用于核对拒绝前不访问文件。 */
function createAttachmentService(prisma: object, storage: object) {
  // 文件存储使用受控替身，只验证权限查询与文件访问顺序，不读取真实磁盘。
  return new ArticlesService(prisma as PrismaService, {} as SiteSettingsService, {} as RedisService,
    {} as ReputationService, undefined, storage as ChatAttachmentsService);
}

function readerStateDelegates(unlockedKeys: string[] = []) {
  return {
    articleReadLater: { findUnique: jest.fn(async () => null) },
    articleReadingHistory: { findUnique: jest.fn(async () => null) },
    articleResourceExchange: {
      findMany: jest.fn(async () => unlockedKeys.map((blockKey) => ({ blockKey }))),
    },
  };
}

type ExchangeCreateArgs = {
  data: {
    articleId: number;
    buyerId: number;
    authorId: number;
    blockKey: string;
    pointCost: number;
    sellerAvailableAt: Date;
  };
};

describe("article resource blocks", () => {
  // HTML 与历史 Markdown 资源块必须共享同一附件下载和缩略图解锁边界。
  it.each(["html", "markdown"] as const)("protects locked %s attachment downloads and thumbnails", async (format) => {
    // 相同附件仅出现在积分块中；尚未兑换时不能先访问存储，也不能靠缩略图旁路。
    const link = '<p>Protected image</p><img src="/articles/attachments/301/download">';
    const content = format === "html" ? `<resource-block data-points="5">${link}</resource-block>` : `:::resource{points=5}\n${link}\n:::`;
    const article = { ...resourceArticle(), content, contentFormat: format };
    const findMany = jest.fn(async () => [] as Array<{ blockKey: string }>);
    const prisma = { articleAttachment: { findUnique: jest.fn(async () => ({ id: 301, storedName: "protected.png", kind: "image", mimeType: "image/png", originalName: "protected.png", sizeBytes: 110, article })) }, articleResourceExchange: { findMany } };
    const storage = { getStoredFile: jest.fn(async () => ({ filePath: "/fixture/protected.png" })), getStoredThumbnail: jest.fn(async () => ({ filePath: "/fixture/thumb.webp", sizeBytes: 20 })) };
    const service = createAttachmentService(prisma, storage);
    await expect(service.getArticleAttachment(301, null)).rejects.toThrow(ForbiddenException);
    await expect(service.getArticleAttachmentThumbnail(301, null)).rejects.toThrow(ForbiddenException);
    await expect(service.getArticleAttachment(301, viewer)).rejects.toThrow(ForbiddenException);
    expect(storage.getStoredFile).not.toHaveBeenCalled();
    expect(storage.getStoredThumbnail).not.toHaveBeenCalled();

    // 作者不需兑换，读者只接受本站本篇对应资源块的兑换记录。
    await expect(service.getArticleAttachment(301, { ...viewer, id: article.authorId })).resolves.toMatchObject({ mimeType: "image/png" });
    const key = parseArticleContent(content, format).blocks[0].key;
    findMany.mockResolvedValue([{ blockKey: key }]);
    await expect(service.getArticleAttachment(301, viewer)).resolves.toMatchObject({ originalName: "protected.png" });
    await expect(service.getArticleAttachmentThumbnail(301, viewer)).resolves.toMatchObject({ sizeBytes: 20 });
    expect(findMany).toHaveBeenCalledWith({ where: { articleId: article.id, buyerId: viewer.id, blockKey: { in: [key] }, deliveryStatus: { in: [ResourceDeliveryStatus.unlocked, ResourceDeliveryStatus.downloaded] } }, select: { blockKey: true } });
    // 旧资源块或其它块的兑换不能解锁当前附件，即使底层返回了不匹配记录也拒绝。
    findMany.mockResolvedValue([{ blockKey: "unrelated-resource-key" }]);
    await expect(service.getArticleAttachment(301, viewer)).rejects.toThrow(ForbiddenException);
  });

  // 四种交付状态必须在正文解锁、原文件及缩略图入口保持一致，失败与退款不可旁路。
  it.each(Object.values(ResourceDeliveryStatus))("uses the same content and attachment authorization for %s delivery", async (status) => {
    // 替身按实际查询中的状态白名单筛选，遗漏筛选时会暴露记录，从而使测试明确失败。
    const content = '<resource-block data-points="1"><p>Protected image</p><img src="/articles/attachments/301/download"></resource-block>';
    const article = { ...resourceArticle(), content, contentFormat: "html" };
    const key = parseArticleContent(content, "html").blocks[0].key;
    const findMany = jest.fn(async (args: { where: { deliveryStatus?: { in: ResourceDeliveryStatus[] } } }) =>
      !args.where.deliveryStatus || args.where.deliveryStatus.in.includes(status) ? [{ blockKey: key }] : []);
    const prisma = {
      ...readerStateDelegates(),
      articleAttachment: { findUnique: jest.fn(async () => ({ storedName: "protected.png", kind: "image", mimeType: "image/png", originalName: "protected.png", sizeBytes: 110, article })) },
      articleResourceExchange: { findMany },
    };
    const storage = { getStoredFile: jest.fn(async () => ({ filePath: "/fixture/protected.png" })), getStoredThumbnail: jest.fn(async () => ({ filePath: "/fixture/thumb.webp", sizeBytes: 20 })) };
    const service = createAttachmentService(prisma, storage);
    const active = status === ResourceDeliveryStatus.unlocked || status === ResourceDeliveryStatus.downloaded;

    // 阅读状态只产出有效交付的键；下载拒绝必须发生在读取实际存储之前。
    const state = await (service as unknown as { getReaderState: (articleId: number, userId: number) => Promise<{ unlockedResourceKeys: Set<string> }> }).getReaderState(article.id, viewer.id);
    expect(state.unlockedResourceKeys.has(key)).toBe(active);
    if (active) {
      await expect(service.getArticleAttachment(301, viewer)).resolves.toMatchObject({ originalName: "protected.png" });
      await expect(service.getArticleAttachmentThumbnail(301, viewer)).resolves.toMatchObject({ sizeBytes: 20 });
    } else {
      await expect(service.getArticleAttachment(301, viewer)).rejects.toThrow(ForbiddenException);
      await expect(service.getArticleAttachmentThumbnail(301, viewer)).rejects.toThrow(ForbiddenException);
      expect(storage.getStoredFile).not.toHaveBeenCalled();
      expect(storage.getStoredThumbnail).not.toHaveBeenCalled();
    }
    expect(findMany).toHaveBeenCalledTimes(3);
    for (const [args] of findMany.mock.calls) {
      expect(args.where).toMatchObject({ articleId: article.id, buyerId: viewer.id, deliveryStatus: { in: [ResourceDeliveryStatus.unlocked, ResourceDeliveryStatus.downloaded] } });
    }
  });

  // 异常兑换不允许通过原兑换接口免费恢复权限，也不能再次扣分或新建交付。
  it.each([ResourceDeliveryStatus.failed, ResourceDeliveryStatus.refunded])("rejects repeat redemption of %s delivery without transferring points", async (status) => {
    // 在事务内读取原状态，业务错误直接向上返回，任何后续写操作均不应触发。
    const create = jest.fn();
    const transferResourcePoints = jest.fn();
    const findUnique = jest.fn(async () => ({ id: 8, deliveryStatus: status }));
    const prisma = {
      article: { findUnique: jest.fn(async () => resourceArticle()) },
      ...readerStateDelegates(),
      $transaction: jest.fn(async (callback: (transaction: object) => Promise<void>) => callback({ articleResourceExchange: { findUnique, create } })),
    };
    await expect(createService(prisma, { transferResourcePoints }).redeemResource(12, viewer, { blockKey: firstBlock.key }))
      .rejects.toThrow(status === ResourceDeliveryStatus.refunded ? "该资源已退款，不能再次兑换。" : "资源交付失败，请在兑换记录中重试。");
    expect(findUnique).toHaveBeenCalledWith({ where: { articleId_buyerId_blockKey: { articleId: 12, buyerId: viewer.id, blockKey: firstBlock.key } }, select: { id: true, deliveryStatus: true } });
    expect(create).not.toHaveBeenCalled();
    expect(transferResourcePoints).not.toHaveBeenCalled();
  });

  // 同一附件由作者在公开段落引用时可以公开下载，不因它也在积分块中出现而误拦截。
  it("allows a public attachment reference without querying resource exchanges", async () => {
    // 公开链接与锁定链接指向同一个准确编号，只有公开引用决定匿名可访问性。
    const article = { ...resourceArticle(), contentFormat: "html", content: '<p><a href="/articles/attachments/301/download">Public file</a></p><resource-block data-points="5"><a href="/articles/attachments/301/download">Same file</a></resource-block>' };
    const findMany = jest.fn();
    const prisma = { articleAttachment: { findUnique: jest.fn(async () => ({ storedName: "public.txt", kind: "file", mimeType: "text/plain", originalName: "public.txt", sizeBytes: 3, article })) }, articleResourceExchange: { findMany } };
    const storage = { getStoredFile: jest.fn(async () => ({ filePath: "/fixture/public.txt" })) };
    await expect(createAttachmentService(prisma, storage).getArticleAttachment(301, null)).resolves.toMatchObject({ originalName: "public.txt" });
    expect(findMany).not.toHaveBeenCalled();
  });

  // 常规媒体附件保存后仍是播放器，扩展白名单不能同时放行脚本、事件或自动播放。
  it("keeps safe audio video and source nodes without active HTML attributes", () => {
    // 同时覆盖直接 src、source 子节点和危险协议，确保白名单约束作用于新媒体标签。
    const sanitized = sanitizeArticleHtml([
      '<p><audio controls preload="none" src="/articles/attachments/3/download" onerror="alert(1)" autoplay></audio></p>',
      '<p><video controls playsinline poster="https://example.com/cover.png"><source src="/articles/attachments/4/download" type="video/mp4"></video></p>',
      '<audio src="javascript:alert(1)"></audio><video src="data:text/html,bad"></video>',
    ].join(""));
    // 媒体结构和安全资源路径保留，主动执行与危险来源属性被剔除。
    expect(sanitized).toContain('<audio controls preload="none" src="/articles/attachments/3/download">');
    expect(sanitized).toContain('<video controls playsinline poster="https://example.com/cover.png">');
    expect(sanitized).toContain('<source src="/articles/attachments/4/download" type="video/mp4"');
    expect(sanitized).not.toMatch(/onerror|autoplay|javascript:|data:text/);
  });
  it("sanitizes HTML content before it can be rendered", () => {
    const sanitized = sanitizeArticleHtml('<p>公开内容</p><script>alert("x")</script><p onclick="alert(1)">安全内容</p>');
    expect(sanitized).toContain("公开内容");
    expect(sanitized).toContain("安全内容");
    expect(sanitized).not.toContain("script");
    expect(sanitized).not.toContain("onclick");
  });

  it("preserves article formatting nodes while filtering unsafe attributes", () => {
    const sanitized = sanitizeArticleHtml([
      '<p><span style="font-size: 14px">正文</span></p>',
      '<ul data-type="taskList"><li class="article-task-item" data-type="taskItem" data-checked="false"><label><input type="checkbox" checked><span></span></label><div><p>任务</p></div></li></ul>',
      '<p><a href="javascript:alert(1)" onclick="alert(1)">危险链接</a><a href="https://example.com">安全链接</a></p>',
    ].join(""));

    expect(sanitized).toContain('<span style="font-size:14px">正文</span>');
    expect(sanitized).toContain('data-type="taskList"');
    expect(sanitized).toContain('class="article-task-item"');
    expect(sanitized).toContain('data-checked="false"');
    expect(sanitized).toContain('type="checkbox"');
    expect(sanitized).toContain('href="https://example.com"');
    expect(sanitized).not.toContain("javascript:");
    expect(sanitized).not.toContain("onclick");
  });

  it("keeps escaped HTML inside code blocks as code instead of executable markup", () => {
    const sanitized = sanitizeArticleHtml('<pre><code>&lt;h1&gt;标题&lt;/h1&gt;</code></pre>');

    expect(sanitized).toBe('<pre><code>&lt;h1&gt;标题&lt;/h1&gt;</code></pre>');
    expect(sanitized).not.toContain("<h1>");
  });

  it("parses HTML resource blocks and keeps ordinary HTML segments", () => {
    const parsed = parseArticleContent(
      '<p>公开正文。</p><resource-block data-points="10"><p>需要兑换的内容。</p></resource-block><p>后续正文。</p>',
      "html",
    );
    expect(parsed.blocks).toHaveLength(1);
    expect(parsed.blocks[0]).toMatchObject({ pointCost: 10, content: "<p>需要兑换的内容。</p>" });
    expect(parsed.segments).toEqual([
      { type: "html", content: "<p>公开正文。</p>" },
      { type: "resource", key: parsed.blocks[0]!.key, pointCost: 10, content: "<p>需要兑换的内容。</p>" },
      { type: "html", content: "<p>后续正文。</p>" },
    ]);
  });

  it("rejects invalid HTML resource point values", () => {
    expect(() => parseArticleContent('<resource-block data-points="0"><p>secret</p></resource-block>', "html"))
      .toThrow("资源块积分必须是 1 到 10000 的整数。");
  });

  it("derives the resource flag and lowest exchange cost from resource-block content", () => {
    const service = createService({});
    const summary = (service as unknown as {
      resourceSummary: (content: string) => { isPointResource: boolean; pointCost: number };
    }).resourceSummary(sourceContent);
    expect(summary).toEqual({ isPointResource: true, pointCost: 5 });
    expect((service as unknown as { resourceSummary: (content: string) => { isPointResource: boolean; pointCost: number } }).resourceSummary("公开内容"))
      .toEqual({ isPointResource: false, pointCost: 0 });
  });

  it("does not return locked resource bodies to a visitor", async () => {
    // 访客响应不能通过编辑器附件列表旁路获取锁定资源的名称或地址。
    const article = Object.assign(resourceArticle(), { attachments: [{ id: 301, kind: "file", originalName: "secret.txt", mimeType: "text/plain", sizeBytes: 13, createdAt: new Date() }] });
    const prisma = {
      article: { findUnique: jest.fn(async () => article) },
      $transaction: jest.fn(
        async (callback: (transaction: object) => Promise<void>) =>
          callback({
            articleView: { create: jest.fn(async () => ({ id: 1 })) },
            article: { update: jest.fn(async () => article) },
          }),
      ),
    };
    const service = createService(prisma);

    // 沿用真实公开阅读序列化和资源块权限，不给访客返回文章编辑权限专属附件元数据。
    await expect(service.getPublicBySlug(article.slug, "visitor-key")).resolves.toMatchObject({
      attachments: [],
      content: "公开正文。\n\n中间公开正文。",
      summary: article.summary,
      contentSegments: [
        { type: "markdown", content: "公开正文。" },
        { type: "resource", key: firstBlock.key, pointCost: 10, unlocked: false },
        { type: "markdown", content: "中间公开正文。" },
        { type: "resource", key: secondBlock.key, pointCost: 5, unlocked: false },
      ],
      resource: {
        enabled: true,
        blocks: [
          { key: firstBlock.key, pointCost: 10, unlocked: false },
          { key: secondBlock.key, pointCost: 5, unlocked: false },
        ],
      },
    });
  });

  it("redeems one block and leaves the other block locked", async () => {
    const article = resourceArticle();
    const createExchange = jest.fn(async (args: ExchangeCreateArgs) => {
      void args.data;
      return { id: 1 };
    });
    const transferResourcePoints = jest.fn(async () => undefined);
    const prisma = {
      article: { findUnique: jest.fn(async () => article) },
      ...readerStateDelegates([firstBlock.key]),
      $transaction: jest.fn(
        async (callback: (transaction: object) => Promise<void>) =>
          callback({
            articleResourceExchange: {
              findUnique: jest.fn(async () => null),
              create: createExchange,
            },
          }),
      ),
    };
    const service = createService(prisma, { transferResourcePoints });

    await expect(
      service.redeemResource(article.id, viewer, { blockKey: firstBlock.key }),
    ).resolves.toMatchObject({
      content: "公开正文。\n\n中间公开正文。",
      contentSegments: [
        { type: "markdown", content: "公开正文。" },
        { type: "resource", key: firstBlock.key, pointCost: 10, unlocked: true, content: firstBlock.content },
        { type: "markdown", content: "中间公开正文。" },
        { type: "resource", key: secondBlock.key, pointCost: 5, unlocked: false },
      ],
    });
    expect(createExchange).toHaveBeenCalledWith({
      data: expect.objectContaining({
        articleId: article.id,
        buyerId: viewer.id,
        authorId: article.authorId,
        blockKey: firstBlock.key,
        pointCost: 10,
        sellerAvailableAt: expect.any(Date),
      }),
    });
    const availableAt = createExchange.mock.calls[0]![0].data.sellerAvailableAt;
    expect(availableAt.getTime()).toBeGreaterThan(Date.now() + 71 * 60 * 60 * 1000);
    expect(availableAt.getTime()).toBeLessThan(Date.now() + 73 * 60 * 60 * 1000);
    expect(transferResourcePoints).toHaveBeenCalledWith(expect.anything(), {
      buyerId: viewer.id,
      authorId: article.authorId,
      articleId: article.id,
      blockKey: firstBlock.key,
      pointCost: 10,
    });
  });

  it("does not charge a block twice after it has already been exchanged", async () => {
    const article = resourceArticle();
    const createExchange = jest.fn(async (args: ExchangeCreateArgs) => {
      void args.data;
      return { id: 1 };
    });
    const transferResourcePoints = jest.fn(async () => undefined);
    const prisma = {
      article: { findUnique: jest.fn(async () => article) },
      ...readerStateDelegates([secondBlock.key]),
      $transaction: jest.fn(
        async (callback: (transaction: object) => Promise<void>) =>
          callback({
            articleResourceExchange: {
              findUnique: jest.fn(async () => ({ id: 8 })),
              create: createExchange,
            },
          }),
      ),
    };
    const service = createService(prisma, { transferResourcePoints });

    const response = await service.redeemResource(article.id, viewer, { blockKey: secondBlock.key });
    expect(response.contentSegments.find((segment) => segment.type === "resource" && segment.key === secondBlock.key))
      .toMatchObject({ type: "resource", key: secondBlock.key, unlocked: true, content: secondBlock.content });
    expect(createExchange).not.toHaveBeenCalled();
    expect(transferResourcePoints).not.toHaveBeenCalled();
  });

  it("turns a concurrent duplicate exchange into a stable business error", async () => {
    const article = resourceArticle();
    const duplicate = new Prisma.PrismaClientKnownRequestError("Unique constraint failed", {
      code: "P2002",
      clientVersion: "test",
    });
    const transferResourcePoints = jest.fn(async () => undefined);
    const prisma = {
      article: { findUnique: jest.fn(async () => article) },
      ...readerStateDelegates([]),
      $transaction: jest.fn(async (callback: (transaction: object) => Promise<void>) => callback({
        articleResourceExchange: {
          findUnique: jest.fn(async () => null),
          create: jest.fn(async () => { throw duplicate; }),
        },
      })),
    };
    const service = createService(prisma, { transferResourcePoints });

    await expect(service.redeemResource(article.id, viewer, { blockKey: firstBlock.key }))
      .rejects.toThrow("该资源已经兑换，无需重复支付。");
    expect(transferResourcePoints).not.toHaveBeenCalled();
  });

  it("rejects malformed resource syntax before an article can be saved", () => {
    expect(() => parseArticleContent(":::resource{points=0}\nsecret\n:::"))
      .toThrow("资源块积分必须是 1 到 10000 的整数。");
    expect(() => parseArticleContent(":::resource{points=10}\nsecret"))
      .toThrow("资源块缺少结束标记 :::。");
  });
});
