import { BadRequestException, Injectable, OnModuleDestroy, OnModuleInit, Optional } from "@nestjs/common";
import { createHash } from "node:crypto";
import webpush from "web-push";
import { AuthenticatedUser } from "../auth/auth.types";
import { PrismaService } from "../prisma/prisma.service";
import type { ChatMessageResponse } from "../social/social.types";
import { NativePushTokenDto, PushSubscriptionDto } from "./dto/push.dto";
import { FcmClient } from "./fcm.client";
import { IntegrationsService } from "../integrations/integrations.service";
import { BrowserPushPayload, PushConfigResponse, PushStatusResponse } from "./push.types";

@Injectable()
export class PushService implements OnModuleInit, OnModuleDestroy {
  private readonly fcm = new FcmClient();
  private readonly publicKey = process.env.VAPID_PUBLIC_KEY?.trim() || "";
  private readonly privateKey = process.env.VAPID_PRIVATE_KEY?.trim() || "";
  private readonly subject = process.env.VAPID_SUBJECT?.trim() || "";
  private readonly browserEnabled = Boolean(this.publicKey && this.privateKey && this.subject);
  private readonly nativeEnabled = this.fcm.enabled;
  private readonly enabled = this.browserEnabled || this.nativeEnabled;
  private pollTimer: NodeJS.Timeout | null = null;
  private polling = false;

  constructor(private readonly prisma: PrismaService, @Optional() private readonly integrations?: IntegrationsService) {}

  onModuleInit(): void {
    if (this.browserEnabled) webpush.setVapidDetails(this.subject, this.publicKey, this.privateKey);
    if (!this.enabled) return;
    this.pollTimer = setInterval(() => void this.deliverPendingNotifications(), 12_000);
    this.pollTimer.unref();
    setTimeout(() => void this.deliverPendingNotifications(), 2_000).unref();
  }

  onModuleDestroy(): void {
    if (this.pollTimer) clearInterval(this.pollTimer);
  }

  getConfig(): PushConfigResponse {
    return {
      enabled: this.browserEnabled,
      publicKey: this.browserEnabled ? this.publicKey : null,
      nativeEnabled: this.nativeEnabled,
    };
  }

  async getStatus(user: AuthenticatedUser): Promise<PushStatusResponse> {
    const nativeSubscriptionCount = await this.nativeTokenCount(user.id);
    return {
      ...this.getConfig(),
      subscriptionCount: await this.prisma.pushSubscription.count({ where: { userId: user.id } }),
      nativeSubscriptionCount,
    };
  }

  async subscribe(
    user: AuthenticatedUser,
    dto: PushSubscriptionDto,
    userAgent: string,
  ): Promise<PushStatusResponse> {
    this.assertConfigured();
    this.assertEndpoint(dto.endpoint);
    const endpointHash = this.endpointHash(dto.endpoint);
    await this.prisma.pushSubscription.upsert({
      where: { endpointHash },
      create: {
        userId: user.id,
        endpointHash,
        endpoint: dto.endpoint,
        p256dh: dto.keys.p256dh,
        auth: dto.keys.auth,
        userAgent: userAgent.slice(0, 500),
      },
      update: {
        userId: user.id,
        endpoint: dto.endpoint,
        p256dh: dto.keys.p256dh,
        auth: dto.keys.auth,
        userAgent: userAgent.slice(0, 500),
      },
    });
    return this.getStatus(user);
  }

  async unsubscribe(user: AuthenticatedUser, dto: PushSubscriptionDto): Promise<PushStatusResponse> {
    await this.prisma.pushSubscription.deleteMany({
      where: { userId: user.id, endpointHash: this.endpointHash(dto.endpoint) },
    });
    return this.getStatus(user);
  }

  async registerNativeToken(user: AuthenticatedUser, dto: NativePushTokenDto, userAgent: string): Promise<PushStatusResponse> {
    const token = dto.token.trim();
    const nativePushTokens = this.nativePushTokensClient();
    if (!nativePushTokens) throw new BadRequestException("Android 原生推送存储尚未就绪。");
    await nativePushTokens.upsert({
      where: { tokenHash: this.endpointHash(token) },
      create: {
        userId: user.id,
        tokenHash: this.endpointHash(token),
        token,
        platform: dto.platform || "android",
        appId: dto.appId?.trim() || "",
        deviceId: dto.deviceId?.trim() || "",
        userAgent: userAgent.slice(0, 500),
      },
      update: {
        userId: user.id,
        token,
        platform: dto.platform || "android",
        appId: dto.appId?.trim() || "",
        deviceId: dto.deviceId?.trim() || "",
        userAgent: userAgent.slice(0, 500),
      },
    });
    return this.getStatus(user);
  }

  async unregisterNativeToken(user: AuthenticatedUser, dto: NativePushTokenDto): Promise<PushStatusResponse> {
    const nativePushTokens = this.nativePushTokensClient();
    if (nativePushTokens) {
      await nativePushTokens.deleteMany({
        where: { userId: user.id, tokenHash: this.endpointHash(dto.token.trim()) },
      });
    }
    return this.getStatus(user);
  }

  async sendChatMessage(senderId: number, recipientId: number, message: ChatMessageResponse): Promise<void> {
    if (!this.enabled || senderId === recipientId) return;
    const state = await this.prisma.conversationParticipantState.findUnique({
      where: { conversationId_userId: { conversationId: message.conversationId, userId: recipientId } },
      select: { muted: true },
    });
    if (state?.muted) return;
    await this.sendToUser(recipientId, {
      title: message.sender.nickname || message.sender.username,
      body: message.body || (message.attachments.length > 1 ? `发来 ${message.attachments.length} 个附件` : "发来一个附件"),
      titleEn: message.sender.nickname || message.sender.username,
      bodyEn: message.body || (message.attachments.length > 1 ? `sent ${message.attachments.length} attachments` : "sent an attachment"),
      category: "interaction",
      url: `/messages?conversation=${message.conversationId}`,
      tag: `chat-${message.conversationId}`,
      dedupeKey: `chat-message-${message.id}-${recipientId}`,
      messageId: message.id,
      type: "chat_message",
    });
    await this.integrations?.deliverExternalNotification(recipientId, "interaction", { type: "chat_message", messageId: message.id, conversationId: message.conversationId, body: message.body || "[attachment]", url: `/messages?conversation=${message.conversationId}` });
  }

  private async deliverPendingNotifications(): Promise<void> {
    if (this.polling || !this.enabled) return;
    this.polling = true;
    try {
      const notifications = await this.prisma.userNotification.findMany({
        where: { pushDeliveredAt: null },
        orderBy: [{ id: "asc" }],
        take: 50,
        select: { id: true, userId: true, channel: true, title: true, body: true, bodyEn: true, actionUrl: true, type: true, messageId: true, message: { select: { id: true } }, user: { select: { preferredLocale: true } } },
      });
      for (const notification of notifications) {
        const state = await this.prisma.userNotificationChannelState.findUnique({
          where: { userId_channel: { userId: notification.userId, channel: notification.channel } },
          select: { pushEnabled: true },
        });
        if (state?.pushEnabled !== false) {
          let notificationUrl = notification.actionUrl || "/";
          if (notification.type === "mention_received" && !notification.message && this.isChatMentionUrl(notification.actionUrl)) {
            const url = new URL(notificationUrl, "https://push.local");
            url.searchParams.set("messageDeleted", "1");
            notificationUrl = `${url.pathname}${url.search}${url.hash}`;
          }
          await this.sendToUser(notification.userId, {
            title: notification.title,
            titleEn: this.englishPushTitle(notification.type, notification.title),
            body: notification.body,
            bodyEn: notification.bodyEn || notification.body,
            locale: notification.user.preferredLocale === "en-US" ? "en-US" : "zh-CN",
            category: notification.channel === "subscription" ? "subscription" : notification.channel === "interaction" ? "interaction" : "system",
            url: notificationUrl,
            tag: `notification-${notification.id}`,
            dedupeKey: notification.messageId
              ? `chat-message-${notification.messageId}-${notification.userId}`
              : `notification-${notification.id}`,
            notificationId: notification.id,
            messageId: notification.messageId ?? undefined,
            type: notification.type,
          });
        }
        await this.integrations?.deliverExternalNotification(notification.userId, String(notification.channel), { notificationId: notification.id, type: notification.type, title: notification.title, body: notification.body, actionUrl: notification.actionUrl });
        await this.prisma.userNotification.update({
          where: { id: notification.id },
          data: { pushDeliveredAt: new Date() },
        });
      }
    } catch {
      // The next polling cycle retries records that were not marked as delivered.
    } finally {
      this.polling = false;
    }
  }

  private async sendToUser(userId: number, payload: BrowserPushPayload): Promise<void> {
    if (this.browserEnabled) {
      const configuredIcon = payload.icon || await this.configuredPwaIcon();
      const subscriptions = await this.prisma.pushSubscription.findMany({
        where: { userId },
        select: { id: true, endpoint: true, p256dh: true, auth: true },
      });
      await Promise.all(subscriptions.map(async (subscription) => {
        try {
          await webpush.sendNotification({
            endpoint: subscription.endpoint,
            keys: { p256dh: subscription.p256dh, auth: subscription.auth },
          }, JSON.stringify({
            ...payload,
            recipientUserId: userId,
            icon: configuredIcon,
            badge: payload.badge || "/favicon-48x48.png",
          }), { TTL: 300, urgency: "normal" });
        } catch (error) {
          const statusCode = typeof error === "object" && error && "statusCode" in error
            ? Number((error as { statusCode?: unknown }).statusCode)
            : 0;
          if (statusCode === 404 || statusCode === 410) {
            await this.prisma.pushSubscription.delete({ where: { id: subscription.id } }).catch(() => undefined);
          }
        }
      }));
    }
    await this.sendNativeToUser(userId, payload);
  }

  private async sendNativeToUser(userId: number, payload: BrowserPushPayload): Promise<void> {
    if (!this.nativeEnabled) return;
    const nativePushTokens = this.nativePushTokensClient();
    if (!nativePushTokens) return;
    let tokens: Array<{ id: number; token: string }>;
    try {
      tokens = await nativePushTokens.findMany({
        where: { userId, platform: "android" },
        select: { id: true, token: true },
      });
    } catch {
      // Keep browser delivery working while an older deployment is waiting for the native-token migration.
      return;
    }
    await Promise.all(tokens.map(async (row: { id: number; token: string }) => {
      try {
        const result = await this.fcm.send(row.token, {
          title: payload.title,
          body: payload.body,
          titleEn: payload.titleEn,
          bodyEn: payload.bodyEn,
          locale: payload.locale,
          category: payload.category,
          url: payload.url,
          tag: payload.tag,
          notificationId: payload.notificationId,
          messageId: payload.messageId,
          type: payload.type,
        });
        if (result === "invalid") await nativePushTokens.delete({ where: { id: row.id } }).catch(() => undefined);
      } catch {
        // A transient FCM failure must not prevent the browser channel or the next retry cycle.
      }
    }));
  }

  private isChatMentionUrl(actionUrl: string | null): boolean {
    if (!actionUrl) return false;
    try {
      const url = new URL(actionUrl, "https://push.local");
      const conversationId = Number(url.searchParams.get("conversation"));
      return Number.isInteger(conversationId) && conversationId > 0;
    } catch {
      return false;
    }
  }

  private englishPushTitle(type: string, fallback: string): string {
    const titles: Record<string, string> = {
      friend_request_received: "New friend request",
      friend_request_accepted: "Friend request accepted",
      comment_report_resolved: "Comment report handled",
      comment_report_rejected: "Comment report rejected",
      article_report_received: "New article report",
      article_report_resolved: "Article report handled",
      article_report_rejected: "Article report rejected",
      article_liked: "Your article was liked",
      article_favorited: "Your article was saved",
      article_commented: "New article comment",
      comment_replied: "New comment reply",
      mention_received: "You were mentioned",
      author_subscribed: "New subscriber",
      subscription_published: "New content from a subscription",
      announcement_published: "New announcement",
      suggestion_updated: "Suggestion updated",
      feedback_updated: "Feedback updated",
      article_scheduled_publish: "Article published on schedule",
      article_scheduled_publish_failed: "Scheduled publication failed",
      article_scheduled_unpublish: "Article taken offline on schedule",
    };
    return titles[type] || fallback;
  }

  private async configuredPwaIcon(): Promise<string> {
    const settings = await this.prisma.siteSetting.findUnique({
      where: { id: 1 },
      select: { pwaIconPath: true, updatedAt: true },
    });
    const path = settings?.pwaIconPath?.trim() || "/pwa-logo.png";
    if (!settings?.updatedAt) return path;
    return `${path}${path.includes("?") ? "&" : "?"}v=${encodeURIComponent(settings.updatedAt.toISOString())}`;
  }

  private assertConfigured(): void {
    if (!this.browserEnabled) throw new BadRequestException("浏览器推送尚未在服务器上配置。");
  }

  private assertEndpoint(endpoint: string): void {
    try {
      if (new URL(endpoint).protocol !== "https:") throw new Error("Invalid protocol");
    } catch {
      throw new BadRequestException("浏览器推送订阅地址无效。");
    }
  }

  private endpointHash(endpoint: string): string {
    return createHash("sha256").update(endpoint).digest("hex");
  }

  private nativePushTokensClient(): {
    upsert: (args: unknown) => Promise<unknown>;
    deleteMany: (args: unknown) => Promise<unknown>;
    findMany: (args: unknown) => Promise<Array<{ id: number; token: string }>>;
    delete: (args: unknown) => Promise<unknown>;
    count: (args: unknown) => Promise<number>;
  } | null {
    return (this.prisma as unknown as { nativePushToken?: ReturnType<PushService["nativePushTokensClient"]> }).nativePushToken ?? null;
  }

  private async nativeTokenCount(userId: number): Promise<number> {
    const nativePushTokens = this.nativePushTokensClient();
    if (!nativePushTokens) return 0;
    try {
      return await nativePushTokens.count({ where: { userId } });
    } catch {
      // The API can be upgraded before the optional native-token table is migrated.
      return 0;
    }
  }
}
