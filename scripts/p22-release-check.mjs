#!/usr/bin/env node

const webBase = (
  process.env.P22_WEB_BASE_URL || "http://localhost:3000"
).replace(/\/$/, "");
const apiBase = (
  process.env.P22_API_BASE_URL ||
  (new URL(webBase).port === "3000"
    ? "http://localhost:3001"
    : `${webBase}/api`)
).replace(/\/$/, "");
const checks = [
  ["api-health", `${apiBase}/health`],
  ["api-readiness", `${apiBase}/health/ready`],
  ["sitemap", `${apiBase}/distribution/sitemap`],
  ["rss", `${apiBase}/distribution/feeds/site.rss`],
  ["home", `${webBase}/`],
  ["english-home", `${webBase}/en`],
  ["manifest", `${webBase}/manifest.webmanifest`],
  ["english-manifest", `${webBase}/en/manifest.webmanifest`],
  ["service-worker", `${webBase}/sw.js`],
];

const androidPackageNamePattern = /^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/;
const androidFingerprintPattern = /^(?:[A-F0-9]{2}:){31}[A-F0-9]{2}$/;

/** 将发布检查参数中的 Android 签名指纹转换为统一格式。 */
function normalizeFingerprint(value) {
  const compact = String(value || "")
    .trim()
    .replace(/[:-]/g, "")
    .toUpperCase();
  if (!/^[0-9A-F]{64}$/.test(compact)) return "";
  const fingerprint = compact.match(/.{2}/g)?.join(":") || "";
  return androidFingerprintPattern.test(fingerprint) ? fingerprint : "";
}

/** 校验 assetlinks 的声明结构，并按需核对目标包名与签名。 */
function validateAssetLinks(payload) {
  if (!Array.isArray(payload) || payload.length === 0) {
    return { valid: false, error: "assetlinks 没有 Android 声明" };
  }

  const entries = payload.map((entry) => {
    const target = entry && typeof entry === "object" ? entry.target : null;
    const fingerprints =
      target && Array.isArray(target.sha256_cert_fingerprints)
        ? target.sha256_cert_fingerprints
            .map(normalizeFingerprint)
            .filter(Boolean)
        : [];
    const relations =
      entry && Array.isArray(entry.relation) ? entry.relation : [];
    const packageName =
      target && typeof target.package_name === "string"
        ? target.package_name.trim()
        : "";
    return {
      packageName,
      fingerprints,
      valid:
        target?.namespace === "android_app" &&
        androidPackageNamePattern.test(packageName) &&
        fingerprints.length > 0 &&
        relations.includes("delegate_permission/common.get_login_creds") &&
        relations.includes("delegate_permission/common.handle_all_urls"),
    };
  });

  const expectedPackage = (process.env.P22_ANDROID_PACKAGE_NAME || "").trim();
  const expectedFingerprint = normalizeFingerprint(
    process.env.P22_ANDROID_CERT_FINGERPRINT,
  );
  const errors = [];
  if (entries.some((entry) => !entry.valid))
    errors.push("存在格式不完整的 Android 声明");
  if (
    expectedPackage &&
    !entries.some(
      (entry) => entry.valid && entry.packageName === expectedPackage,
    )
  ) {
    errors.push(`未找到期望包名 ${expectedPackage}`);
  }
  if (
    expectedFingerprint &&
    !entries.some(
      (entry) =>
        entry.valid && entry.fingerprints.includes(expectedFingerprint),
    )
  ) {
    errors.push("未找到期望签名指纹");
  }

  return {
    valid: errors.length === 0,
    error: errors.join("；"),
    statementCount: entries.filter((entry) => entry.valid).length,
    packages: [
      ...new Set(
        entries
          .filter((entry) => entry.valid)
          .map((entry) => entry.packageName),
      ),
    ],
  };
}

const results = [];
for (const [id, url] of checks) {
  const started = performance.now();
  try {
    const response = await fetch(url, {
      cache: "no-store",
      signal: AbortSignal.timeout(15_000),
    });
    const contentType = response.headers.get("content-type") || "";
    await response.arrayBuffer();
    results.push({
      id,
      url,
      status: response.status,
      ok: response.ok,
      contentType,
      latencyMs: Math.round((performance.now() - started) * 10) / 10,
    });
  } catch (error) {
    results.push({
      id,
      url,
      status: null,
      ok: false,
      error: error instanceof Error ? error.message : String(error),
      latencyMs: Math.round((performance.now() - started) * 10) / 10,
    });
  }
}

// assetlinks 是系统 App Links 和 Android Passkey 关联的必要发布契约，单独读取并校验 JSON 内容。
const assetLinksUrl = `${webBase}/.well-known/assetlinks.json`;
try {
  const started = performance.now();
  const response = await fetch(assetLinksUrl, {
    cache: "no-store",
    signal: AbortSignal.timeout(15_000),
  });
  const validation = response.ok
    ? validateAssetLinks(await response.json())
    : { valid: false, error: "HTTP 请求失败" };
  results.push({
    id: "assetlinks",
    url: assetLinksUrl,
    status: response.status,
    ok: response.ok && validation.valid,
    contentType: response.headers.get("content-type") || "",
    latencyMs: Math.round((performance.now() - started) * 10) / 10,
    ...validation,
  });
} catch (error) {
  results.push({
    id: "assetlinks",
    url: assetLinksUrl,
    status: null,
    ok: false,
    error: error instanceof Error ? error.message : String(error),
  });
}

const output = {
  generatedAt: new Date().toISOString(),
  webBase,
  apiBase,
  checks: results,
  passed: results.every((item) => item.ok),
};
console.log(JSON.stringify(output, null, 2));
if (!output.passed) process.exitCode = 1;
