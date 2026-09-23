import { ConfigurationTransferService } from "../src/system-status/configuration-transfer.service";

function createPrisma() {
  const delegate = () => ({ findUnique: jest.fn(), findMany: jest.fn(), update: jest.fn().mockResolvedValue({}), create: jest.fn().mockResolvedValue({}), upsert: jest.fn().mockResolvedValue({}) });
  const prisma = {
    siteSetting: delegate(),
    securityConfiguration: delegate(),
    aiConfiguration: delegate(),
    aiCapabilityConfiguration: delegate(),
    backupConfiguration: delegate(),
    storageManagementConfiguration: delegate(),
    articleTaxonomy: delegate(),
    moderationSetting: delegate(),
    auditRetentionPolicy: delegate(),
    $transaction: jest.fn(),
  };
  prisma.$transaction.mockImplementation(async (callback: (value: typeof prisma) => Promise<unknown>) => callback(prisma));
  return prisma;
}

describe("ConfigurationTransferService", () => {
  it("exports only configuration data and preserves encrypted values as ciphertext", async () => {
    const prisma = createPrisma();
    prisma.siteSetting.findUnique.mockResolvedValue({ id: 1, siteName: "Test" });
    prisma.securityConfiguration.findUnique.mockResolvedValue({ id: 1, smtpPasswordEncrypted: "v1.encrypted" });
    prisma.aiConfiguration.findUnique.mockResolvedValue({ id: 1, enabled: false, apiKeyEncrypted: "v1.ai", cachedInputCostPerMillionMicros: 100000, fallbackInputCostPerMillionMicros: 2000000, fallbackCachedInputCostPerMillionMicros: 50000 });
    prisma.aiCapabilityConfiguration.findMany.mockResolvedValue([{ capability: "embedding", enabled: false, apiKeyEncrypted: "v1.embedding", cachedInputCostPerMillionMicros: 200000, fallbackOutputCostPerMillionMicros: 3000000, fallbackCachedInputCostPerMillionMicros: 100000 }]);
    prisma.backupConfiguration.findUnique.mockResolvedValue({ id: 1, ossAccessKeyIdEncrypted: "v1.oss" });
    prisma.storageManagementConfiguration.findUnique.mockResolvedValue(null);
    prisma.articleTaxonomy.findMany.mockResolvedValue([]);
    prisma.moderationSetting.findUnique.mockResolvedValue(null);
    prisma.auditRetentionPolicy.findUnique.mockResolvedValue(null);

    const bundle = await new ConfigurationTransferService(prisma as never).exportBundle();

    expect(bundle.format).toBe("hlovet-configuration");
    expect(bundle.data).not.toHaveProperty("users");
    expect(bundle.data).not.toHaveProperty("articles");
    expect(bundle.data.securityConfiguration).toMatchObject({ smtpPasswordEncrypted: "v1.encrypted" });
    expect(bundle.data.aiConfiguration).toMatchObject({ cachedInputCostPerMillionMicros: 100000, fallbackInputCostPerMillionMicros: 2000000, fallbackCachedInputCostPerMillionMicros: 50000 });
    expect(bundle.data.aiCapabilities).toEqual([{ capability: "embedding", enabled: false, apiKeyEncrypted: "v1.embedding", cachedInputCostPerMillionMicros: 200000, fallbackOutputCostPerMillionMicros: 3000000, fallbackCachedInputCostPerMillionMicros: 100000 }]);
  });

  it("imports singleton policies and capability/taxonomy rows without importing content", async () => {
    const prisma = createPrisma();
    const service = new ConfigurationTransferService(prisma as never);
    const result = await service.importBundle({
      format: "hlovet-configuration",
      version: 1,
      generatedAt: new Date().toISOString(),
      requires: { preserveBackupEncryptionKey: true },
      scope: { includes: [], excludes: [] },
      data: {
        siteSetting: { siteName: "Imported" },
        aiCapabilities: [{ capability: "embedding", enabled: false }],
        articleTaxonomies: [{ kind: "category", name: "技术", color: "#123456", sortOrder: 1, enabled: true }],
        users: [{ id: 99 }],
        articles: [{ id: 99 }],
      },
    });

    expect(result).toMatchObject({ imported: true, importedCapabilities: 1, importedTaxonomies: 1 });
    expect(prisma.siteSetting.update).toHaveBeenCalled();
    expect(prisma.aiCapabilityConfiguration.upsert).toHaveBeenCalledWith(expect.objectContaining({ where: { capability: "embedding" } }));
    expect(prisma.articleTaxonomy.upsert).toHaveBeenCalledWith(expect.objectContaining({ where: { kind_name: { kind: "category", name: "技术" } } }));
  });

  it("rejects bundles from another product or version", async () => {
    const prisma = createPrisma();
    await expect(new ConfigurationTransferService(prisma as never).importBundle({ format: "other", version: 1, data: {} })).rejects.toThrow("配置包格式");
  });
});
