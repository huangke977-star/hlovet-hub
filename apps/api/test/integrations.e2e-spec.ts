import { createHash } from "node:crypto";
import { IntegrationsService } from "../src/integrations/integrations.service";

const actor = { id: 7, username: "owner", nickname: "Owner", email: "owner@example.com" } as never;

/** 为集成服务准备隔离依赖；本文件不代表真实数据库或外部投递验收。 */
function createHarness() {
  // 将持久化与限流调用交给可观察替身，以便断言授权条件而不发出外部请求。
  const prisma = {
    externalWebhookEndpoint: { findMany: jest.fn(), create: jest.fn(), findUnique: jest.fn(), update: jest.fn(), delete: jest.fn() },
    externalWebhookDelivery: { create: jest.fn(), findUnique: jest.fn(), update: jest.fn(), findMany: jest.fn() },
    readOnlyApiToken: { create: jest.fn(), findMany: jest.fn(), findUnique: jest.fn(), update: jest.fn(), updateMany: jest.fn() },
    externalNotificationChannel: { findMany: jest.fn() },
    article: { findMany: jest.fn() },
    user: { findUnique: jest.fn() },
  };
  const redis = { incr: jest.fn(async () => 1), expire: jest.fn(async () => 1) };
  const crypto = { encrypt: jest.fn((value: string) => `encrypted:${value}`), decrypt: jest.fn(() => "webhook-secret") };
  return { prisma, redis, crypto, service: new IntegrationsService(prisma as never, redis as never, crypto as never) };
}

describe("P19 integrations", () => {
  // 空白名称不应产生难以管理的真实凭据。
  it("rejects a whitespace-only token name before persisting", async () => {
    const harness = createHarness();
    await expect(harness.service.createToken(actor, { name: "   ", scopes: ["read_articles"] })).rejects.toThrow("Token name");
    expect(harness.prisma.readOnlyApiToken.create).not.toHaveBeenCalled();
  });

  // 搜索与权限必须取交集，分页不能先选出无权查看的文章。
  it("intersects article visibility with search and applies the limit in the same query", async () => {
    const harness = createHarness();
    harness.prisma.readOnlyApiToken.findUnique.mockResolvedValue({ id: 1, userId: 7, revokedAt: null, expiresAt: null, scopes: ["read_articles"], user: { id: 7, status: "active", isSuperAdmin: false, role: { code: "reader" } } });
    harness.prisma.article.findMany.mockResolvedValue([]);
    await harness.service.listReadOnlyArticles("Bearer lvt_fixture", "needle", 3);
    expect(harness.prisma.article.findMany).toHaveBeenCalledWith(expect.objectContaining({
      take: 3,
      where: {
        status: "published",
        OR: [
          { visibility: "public" }, { visibility: "authenticated" },
          { visibility: "private", authorId: 7 },
          { visibility: "role_restricted", allowedRoles: { some: { role: { code: "reader" } } } },
        ],
        AND: [{ OR: [{ title: { contains: "needle" } }, { summary: { contains: "needle" } }] }],
      },
    }));
  });

  // 超管仍只能读取已发布内容，不得将草稿混入集成列表。
  it("retains the published constraint for a current super-admin", async () => {
    const harness = createHarness();
    harness.prisma.readOnlyApiToken.findUnique.mockResolvedValue({ id: 1, userId: 7, revokedAt: null, expiresAt: null, scopes: ["read_articles"], user: { id: 7, status: "active", isSuperAdmin: true, role: { code: "admin" } } });
    harness.prisma.article.findMany.mockResolvedValue([]);
    await harness.service.listReadOnlyArticles("Bearer lvt_fixture");
    expect(harness.prisma.article.findMany).toHaveBeenCalledWith(expect.objectContaining({ where: { status: "published" } }));
  });

  // 撤销和过期检查必须先于文章查询，不泄漏元数据。
  it.each([{ revokedAt: new Date(), expiresAt: null }, { revokedAt: null, expiresAt: new Date(0) }])("rejects inactive token before reading articles: %j", async (state) => {
    const harness = createHarness();
    harness.prisma.readOnlyApiToken.findUnique.mockResolvedValue({ id: 1, ...state, scopes: ["read_articles"], user: { status: "active" } });
    await expect(harness.service.listReadOnlyArticles("Bearer lvt_fixture")).rejects.toThrow("invalid or expired");
    expect(harness.prisma.article.findMany).not.toHaveBeenCalled();
  });

  it("stores only a hash for read-only API tokens and enforces their scope", async () => {
    const harness = createHarness();
    harness.prisma.readOnlyApiToken.create.mockImplementation(async ({ data }: { data: Record<string, unknown> }) => ({ id: 1, ...data, createdAt: new Date(), lastUsedAt: null, revokedAt: null }));

    const created = await harness.service.createToken(actor, { name: "reader", scopes: ["read_articles"] });
    expect(created.token).toMatch(/^lvt_/);
    expect(harness.prisma.readOnlyApiToken.create).toHaveBeenCalledWith(expect.objectContaining({ data: expect.objectContaining({ tokenHash: createHash("sha256").update(created.token).digest("hex"), scopes: ["read_articles"] }) }));
    expect(JSON.stringify(harness.prisma.readOnlyApiToken.create.mock.calls[0][0])).not.toContain(created.token);

    harness.prisma.readOnlyApiToken.findUnique.mockResolvedValue({ id: 1, revokedAt: null, expiresAt: null, scopes: ["read_articles"], user: { id: 7, username: "owner", nickname: "Owner", email: "owner@example.com", status: "active" } });
    harness.prisma.readOnlyApiToken.update.mockResolvedValue({});
    await expect(harness.service.authenticateReadOnlyToken(`Bearer ${created.token}`, "read_articles")).resolves.toBeTruthy();
    await expect(harness.service.authenticateReadOnlyToken(`Bearer ${created.token}`, "read_profile")).rejects.toThrow("scope");
  });

  it("creates idempotent signed webhook deliveries with redacted payloads", async () => {
    const harness = createHarness();
    harness.prisma.externalWebhookEndpoint.findMany.mockResolvedValue([{ id: 4, enabled: true, events: ["article.published"] }]);
    harness.prisma.externalWebhookDelivery.create.mockResolvedValue({ id: 9 });
    const serviceWithPrivate = harness.service as unknown as { deliverOne: (id: number) => Promise<void> };
    const deliverOne = jest.spyOn(serviceWithPrivate, "deliverOne").mockResolvedValue(undefined);

    await harness.service.emit("article.published", { articleId: 12, password: "do-not-send", title: "Public title" }, "event-1");

    expect(harness.prisma.externalWebhookDelivery.create).toHaveBeenCalledWith(expect.objectContaining({ data: expect.objectContaining({ eventId: "event-1", idempotencyKey: "article.published:event-1", payload: expect.objectContaining({ data: { articleId: 12, password: "[REDACTED]", title: "Public title" } }) }) }));
    expect(deliverOne).toHaveBeenCalledWith(9);
  });
});
