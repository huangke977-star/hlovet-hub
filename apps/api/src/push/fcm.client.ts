import { createSign } from "node:crypto";

interface FcmAccessTokenResponse {
  access_token?: string;
  expires_in?: number;
}

export interface FcmMessagePayload {
  title: string;
  body: string;
  titleEn?: string;
  bodyEn?: string;
  locale?: "zh-CN" | "en-US";
  category?: "system" | "subscription" | "interaction";
  url: string;
  tag: string;
  notificationId?: number;
  messageId?: number;
  type?: string;
}

export type FcmSendResult = "sent" | "invalid" | "skipped";

/** Optional Firebase Cloud Messaging HTTP v1 client.
 *
 * The service account is read only from server environment variables. No
 * Firebase secret is shipped to the web client or to the Android APK.
 */
export class FcmClient {
  private readonly projectId = process.env.FCM_PROJECT_ID?.trim() || "";
  private readonly clientEmail = process.env.FCM_CLIENT_EMAIL?.trim() || "";
  private readonly privateKey = (process.env.FCM_PRIVATE_KEY?.trim() || "").replace(/\\n/g, "\n");
  private cachedAccessToken: string | null = null;
  private cachedAccessTokenExpiresAt = 0;

  get enabled(): boolean {
    return Boolean(this.projectId && this.clientEmail && this.privateKey);
  }

  async send(token: string, payload: FcmMessagePayload): Promise<FcmSendResult> {
    if (!this.enabled || !token.trim()) return "skipped";
    const accessToken = await this.getAccessToken();
    const response = await fetch(
      `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(this.projectId)}/messages:send`,
      {
        method: "POST",
        headers: {
          Accept: "application/json",
          Authorization: `Bearer ${accessToken}`,
          "content-type": "application/json",
        },
        body: JSON.stringify({
          message: {
            token,
            // FCM data messages require every value to be a string.
            data: Object.fromEntries(
              Object.entries({
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
              })
                .filter((entry): entry is [string, string | number] => entry[1] !== undefined && entry[1] !== null)
                .map(([key, value]) => [key, String(value)]),
            ),
            android: {
              priority: "HIGH",
              ttl: "300s",
            },
          },
        }),
        signal: AbortSignal.timeout(8_000),
      },
    );
    if (response.ok) return "sent";
    if (response.status === 400 || response.status === 404) return "invalid";
    throw new Error(`FCM request failed with HTTP ${response.status}`);
  }

  private async getAccessToken(): Promise<string> {
    if (this.cachedAccessToken && this.cachedAccessTokenExpiresAt > Date.now() + 60_000) {
      return this.cachedAccessToken;
    }
    const now = Math.floor(Date.now() / 1000);
    const header = this.base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
    const claim = this.base64Url(JSON.stringify({
      iss: this.clientEmail,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token",
      iat: now,
      exp: now + 3_600,
    }));
    const unsigned = `${header}.${claim}`;
    const signature = createSign("RSA-SHA256").update(unsigned).sign(this.privateKey);
    const assertion = `${unsigned}.${this.base64Url(signature)}`;
    const response = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }),
      signal: AbortSignal.timeout(8_000),
    });
    if (!response.ok) throw new Error(`FCM OAuth request failed with HTTP ${response.status}`);
    const data = await response.json() as FcmAccessTokenResponse;
    if (!data.access_token) throw new Error("FCM OAuth response did not include an access token");
    this.cachedAccessToken = data.access_token;
    this.cachedAccessTokenExpiresAt = Date.now() + Math.max(60, Number(data.expires_in || 3_600) - 60) * 1_000;
    return data.access_token;
  }

  private base64Url(value: string | Buffer): string {
    return Buffer.from(value).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
  }
}
