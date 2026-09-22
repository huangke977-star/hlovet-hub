import { BadRequestException, Injectable } from "@nestjs/common";
import { Prisma } from "../generated/prisma/client";
import { PrismaService } from "../prisma/prisma.service";

const BUNDLE_FORMAT = "hlovet-configuration";
const BUNDLE_VERSION = 1;

const SITE_SETTING_FIELDS = [
  "siteName", "browserTitle", "logoPath", "pwaIconPath", "defaultBackgroundUrl", "defaultThemeId",
  "defaultAccent", "defaultSurface", "defaultForeground", "defaultMuted", "defaultCardAlpha", "defaultGlassBlur",
  "defaultGlassTint", "defaultGlassTintAlpha", "registrationOpen", "defaultRoleCode", "installPageEnabled",
  "apkHistoryEnabled", "apkAutoCleanupEnabled", "apkRetentionCount", "defaultArticleVisibility", "articleImageMaxSizeMb",
  "commentsEnabled", "reportsEnabled", "notifyArticleLiked", "notifyArticleFavorited", "notifyArticleCommented",
  "notifyCommentReplied", "notifyAuthorSubscribed", "notifySubscriptionPublished", "notifyFriendRequest",
  "notifyCommentReport", "notifySystem", "templateArticleLiked", "templateArticleFavorited", "templateArticleCommented",
  "templateCommentReplied", "templateAuthorSubscribed", "templateSubscriptionPublished", "templateFriendRequest",
  "templateCommentReportHandled", "templateCommentAuthorModerated", "templateArticleLikedEn", "templateArticleFavoritedEn",
  "templateArticleCommentedEn", "templateCommentRepliedEn", "templateAuthorSubscribedEn", "templateSubscriptionPublishedEn",
  "templateFriendRequestEn", "templateCommentReportHandledEn", "templateCommentAuthorModeratedEn",
] as const;

const SECURITY_FIELDS = [
  "smtpEnabled", "smtpHost", "smtpPort", "smtpSecure", "smtpUsername", "smtpPasswordEncrypted", "smtpFromName",
  "smtpFromEmail", "registrationEmailVerificationEnabled", "passwordRecoveryEnabled", "untrustedDeviceEmailVerificationEnabled",
  "turnstileSiteKey", "turnstileSecretEncrypted", "turnstileRegistrationEnabled", "turnstileLoginEnabled",
  "turnstileRecoveryEnabled", "loginFailureTurnstileThreshold", "googleOauthManaged", "googleOauthEnabled",
  "googleOauthClientId", "googleOauthClientSecretEncrypted", "googleOauthRedirectUri",
] as const;

const AI_FIELDS = [
  "enabled", "provider", "baseUrl", "model", "apiKeyEncrypted", "fallbackEnabled", "fallbackProvider", "fallbackBaseUrl", "fallbackModel", "fallbackApiKeyEncrypted", "globalConcurrency", "userConcurrency", "maxOutputTokens",
  "requestTimeoutSeconds", "dailyRequestLimit", "billingCurrency", "inputCostPerMillionMicros", "outputCostPerMillionMicros", "fallbackInputCostPerMillionMicros", "fallbackOutputCostPerMillionMicros",
  "ragEnabled", "ragTopK", "contextMaxMessages", "contextSummaryThreshold", "contextRetentionDays", "qualityEvaluationEnabled",
] as const;

const CAPABILITY_FIELDS = [
  "capability", "enabled", "provider", "baseUrl", "model", "apiKeyEncrypted", "fallbackEnabled", "fallbackProvider", "fallbackBaseUrl", "fallbackModel", "fallbackApiKeyEncrypted", "globalConcurrency", "userConcurrency",
  "requestTimeoutSeconds", "dailyRequestLimit", "monthlyBudgetMicros", "billingCurrency", "inputCostPerMillionMicros",
  "outputCostPerMillionMicros", "fallbackInputCostPerMillionMicros", "fallbackOutputCostPerMillionMicros", "unitCostMicros", "unitName", "maxInputBytes", "metadata",
] as const;

const BACKUP_FIELDS = [
  "automaticEnabled", "scheduleTime", "timezone", "localRetentionDays", "remoteRetentionDays", "ossEnabled", "ossRegion",
  "ossEndpoint", "ossBucket", "ossPrefix", "ossAccessKeyIdEncrypted", "ossAccessKeySecretEncrypted", "r2Enabled", "r2AccountId",
  "r2Bucket", "r2Prefix", "r2AccessKeyIdEncrypted", "r2SecretAccessKeyEncrypted",
] as const;

const STORAGE_FIELDS = ["automaticScanEnabled", "scanTime", "timezone", "trashRetentionDays", "warningThresholdPercent"] as const;
const MODERATION_FIELDS = ["deadlineHours", "reminderLeadHours", "automaticRemindersEnabled"] as const;
const AUDIT_FIELDS = ["cleanupEnabled", "businessDays", "securityDays", "serverDays"] as const;

type RecordValue = Record<string, unknown>;
type SingletonDelegate = {
  update: (args: { where: { id: number }; data: RecordValue }) => Promise<unknown>;
  create: (args: { data: RecordValue }) => Promise<unknown>;
};

export interface ConfigurationBundle {
  format: typeof BUNDLE_FORMAT;
  version: typeof BUNDLE_VERSION;
  generatedAt: string;
  requires: { preserveBackupEncryptionKey: true };
  scope: { includes: string[]; excludes: string[] };
  data: Record<string, unknown>;
}

@Injectable()
export class ConfigurationTransferService {
  constructor(private readonly prisma: PrismaService) {}

  async exportBundle(): Promise<ConfigurationBundle> {
    const [siteSetting, securityConfiguration, aiConfiguration, aiCapabilities, backupConfiguration, storageConfiguration, articleTaxonomies, moderationSetting, auditRetentionPolicy] = await Promise.all([
      this.prisma.siteSetting.findUnique({ where: { id: 1 } }),
      this.prisma.securityConfiguration.findUnique({ where: { id: 1 } }),
      this.prisma.aiConfiguration.findUnique({ where: { id: 1 } }),
      this.prisma.aiCapabilityConfiguration.findMany({ orderBy: { capability: "asc" } }),
      this.prisma.backupConfiguration.findUnique({ where: { id: 1 } }),
      this.prisma.storageManagementConfiguration.findUnique({ where: { id: 1 } }),
      this.prisma.articleTaxonomy.findMany({ orderBy: [{ kind: "asc" }, { sortOrder: "asc" }, { id: "asc" }] }),
      this.prisma.moderationSetting.findUnique({ where: { id: 1 } }),
      this.prisma.auditRetentionPolicy.findUnique({ where: { id: 1 } }),
    ]);

    return {
      format: BUNDLE_FORMAT,
      version: BUNDLE_VERSION,
      generatedAt: new Date().toISOString(),
      requires: { preserveBackupEncryptionKey: true },
      scope: {
        includes: ["site settings", "security settings", "AI settings", "AI capability settings", "backup policy", "storage policy", "article taxonomies", "moderation policy", "audit retention policy"],
        excludes: ["users", "articles", "comments", "chat", "sessions", "Redis", "user credentials", "user uploads", "AI conversations", "AI knowledge documents"],
      },
      data: {
        siteSetting: this.pick(siteSetting, SITE_SETTING_FIELDS),
        securityConfiguration: this.pick(securityConfiguration, SECURITY_FIELDS),
        aiConfiguration: this.pick(aiConfiguration, AI_FIELDS),
        aiCapabilities: aiCapabilities.map((item) => this.pick(item, CAPABILITY_FIELDS)),
        backupConfiguration: this.pick(backupConfiguration, BACKUP_FIELDS),
        storageConfiguration: this.pick(storageConfiguration, STORAGE_FIELDS),
        articleTaxonomies: articleTaxonomies.map((item) => this.pick(item, ["kind", "name", "color", "sortOrder", "enabled"])),
        moderationSetting: this.pick(moderationSetting, MODERATION_FIELDS),
        auditRetentionPolicy: this.pick(auditRetentionPolicy, AUDIT_FIELDS),
      },
    };
  }

  async importBundle(input: unknown) {
    const bundle = this.validateBundle(input);
    const data = bundle.data;
    let importedTaxonomies = 0;
    let importedCapabilities = 0;

    await this.prisma.$transaction(async (tx) => {
      await this.upsertSingleton(tx.siteSetting as unknown as SingletonDelegate, data.siteSetting, SITE_SETTING_FIELDS);
      await this.upsertSingleton(tx.securityConfiguration as unknown as SingletonDelegate, data.securityConfiguration, SECURITY_FIELDS);
      await this.upsertSingleton(tx.aiConfiguration as unknown as SingletonDelegate, data.aiConfiguration, AI_FIELDS);
      await this.upsertSingleton(tx.backupConfiguration as unknown as SingletonDelegate, data.backupConfiguration, BACKUP_FIELDS);
      await this.upsertSingleton(tx.storageManagementConfiguration as unknown as SingletonDelegate, data.storageConfiguration, STORAGE_FIELDS);
      await this.upsertSingleton(tx.moderationSetting as unknown as SingletonDelegate, data.moderationSetting, MODERATION_FIELDS);
      await this.upsertSingleton(tx.auditRetentionPolicy as unknown as SingletonDelegate, data.auditRetentionPolicy, AUDIT_FIELDS);

      for (const value of this.readRecords(data.aiCapabilities)) {
        const capability = this.asString(value.capability);
        if (!capability) continue;
        const fields = this.pick(value, CAPABILITY_FIELDS);
        await tx.aiCapabilityConfiguration.upsert({
          where: { capability },
          update: fields as Prisma.AiCapabilityConfigurationUpdateInput,
          create: fields as Prisma.AiCapabilityConfigurationCreateInput,
        });
        importedCapabilities += 1;
      }

      for (const value of this.readRecords(data.articleTaxonomies)) {
        const kind = this.asString(value.kind);
        const name = this.asString(value.name);
        if (!kind || !name) continue;
        const fields = this.pick(value, ["kind", "name", "color", "sortOrder", "enabled"]);
        await tx.articleTaxonomy.upsert({
          where: { kind_name: { kind: kind as never, name } },
          update: fields as Prisma.ArticleTaxonomyUpdateInput,
          create: fields as Prisma.ArticleTaxonomyCreateInput,
        });
        importedTaxonomies += 1;
      }
    });

    return { imported: true, version: bundle.version, importedCapabilities, importedTaxonomies, warning: "导入的是系统配置，不包含用户、文章、聊天、会话和知识库内容。域名相关配置仍需按新服务器调整。" };
  }

  private validateBundle(input: unknown): ConfigurationBundle {
    if (!this.isRecord(input) || input.format !== BUNDLE_FORMAT || input.version !== BUNDLE_VERSION || !this.isRecord(input.data)) {
      throw new BadRequestException("配置包格式不受支持，或不是当前 HLOVET 版本导出的配置包。\nUnsupported configuration bundle.");
    }
    return input as unknown as ConfigurationBundle;
  }

  private async upsertSingleton(delegate: SingletonDelegate, value: unknown, fields: readonly string[]) {
    if (!this.isRecord(value) || !Object.keys(value).length) return;
    const data = this.pick(value, fields);
    try {
      await delegate.update({ where: { id: 1 }, data });
    } catch {
      await delegate.create({ data: { id: 1, ...data } });
    }
  }

  private pick(value: unknown, fields: readonly string[]): RecordValue {
    if (!this.isRecord(value)) return {};
    return Object.fromEntries(fields.filter((field) => value[field] !== undefined).map((field) => [field, value[field]]));
  }

  private readRecords(value: unknown): RecordValue[] {
    return Array.isArray(value) ? value.filter((item): item is RecordValue => this.isRecord(item)) : [];
  }

  private asString(value: unknown): string {
    return typeof value === "string" ? value.trim() : "";
  }

  private isRecord(value: unknown): value is RecordValue {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
  }
}
