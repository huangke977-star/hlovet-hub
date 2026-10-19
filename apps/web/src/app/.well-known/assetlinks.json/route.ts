export const dynamic = "force-dynamic";

type AndroidAppLinkConfig = {
  packageName: string;
  fingerprints: string[];
};

const ANDROID_PACKAGE_NAME_PATTERN = /^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/;
const ANDROID_FINGERPRINT_PATTERN = /^(?:[A-F0-9]{2}:){31}[A-F0-9]{2}$/;

/** 将环境变量中的签名指纹规范化为 Android assetlinks 要求的格式。 */
function normalizeFingerprint(value: unknown): string | null {
  // 兼容带冒号、带短横线和连续 64 位十六进制字符的常见导出格式。
  if (typeof value !== "string") return null;
  const compact = value.trim().replace(/[:-]/g, "").toUpperCase();
  if (!/^[0-9A-F]{64}$/.test(compact)) return null;
  const fingerprint = compact.match(/.{2}/g)?.join(":") ?? "";
  return ANDROID_FINGERPRINT_PATTERN.test(fingerprint) ? fingerprint : null;
}

/** 将单个环境配置项转换为受控的包名和签名组合。 */
function addAndroidAppLink(
  target: Map<string, Set<string>>,
  packageName: unknown,
  fingerprints: unknown,
) {
  // 过滤无效包名，避免错误配置生成无法被 Android 接受的声明。
  if (
    typeof packageName !== "string" ||
    !ANDROID_PACKAGE_NAME_PATTERN.test(packageName.trim())
  )
    return;
  const values = Array.isArray(fingerprints)
    ? fingerprints
    : typeof fingerprints === "string"
      ? fingerprints.split(",")
      : [];
  const normalized = values
    .map(normalizeFingerprint)
    .filter((value): value is string => value !== null);
  if (normalized.length === 0) return;
  const packageKey = packageName.trim();
  const packageFingerprints = target.get(packageKey) ?? new Set<string>();
  normalized.forEach((fingerprint) => packageFingerprints.add(fingerprint));
  target.set(packageKey, packageFingerprints);
}

/** 读取多包配置并合并旧环境变量，保证 TWA 到原生包迁移期间可并存。 */
function configuredAndroidAppLinks(): AndroidAppLinkConfig[] {
  const links = new Map<string, Set<string>>();
  const configured = process.env.ANDROID_APP_LINKS_JSON?.trim();

  // JSON 配置用于同时声明旧 TWA、正式原生包和受控测试包。
  if (configured) {
    try {
      const entries: unknown = JSON.parse(configured);
      if (Array.isArray(entries)) {
        entries.forEach((entry) => {
          if (!entry || typeof entry !== "object") return;
          const record = entry as Record<string, unknown>;
          addAndroidAppLink(
            links,
            record.packageName ?? record.package_name,
            record.fingerprints ??
              record.sha256CertFingerprints ??
              record.sha256_cert_fingerprints,
          );
        });
      }
    } catch {
      // 配置错误时保留兼容变量，避免一次格式错误让已有声明全部消失。
    }
  }

  // 旧变量继续作为单包配置入口，已有部署无需立即迁移。
  addAndroidAppLink(
    links,
    process.env.ANDROID_APP_PACKAGE_NAME?.trim() ||
      "xyz.hlovet.portal.prototype",
    process.env.ANDROID_APP_SHA256_CERT_FINGERPRINT ?? "",
  );

  return Array.from(links, ([packageName, fingerprints]) => ({
    packageName,
    fingerprints: Array.from(fingerprints),
  }));
}

/** 返回当前域名的 Android App Links 与登录凭据关联声明。 */
export async function GET() {
  return Response.json(
    configuredAndroidAppLinks().flatMap(({ packageName, fingerprints }) =>
      fingerprints.map((fingerprint) => ({
        relation: [
          "delegate_permission/common.get_login_creds",
          "delegate_permission/common.handle_all_urls",
        ],
        target: {
          namespace: "android_app",
          package_name: packageName,
          sha256_cert_fingerprints: [fingerprint],
        },
      })),
    ),
    {
      headers: {
        "Cache-Control": "public, max-age=300, must-revalidate",
      },
    },
  );
}
