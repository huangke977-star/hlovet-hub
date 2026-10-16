import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const toolsDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(toolsDir, "..", "..", "..");
const webLibRoot = path.join(repoRoot, "apps", "web", "src", "lib");
const androidRoot = path.join(
  repoRoot,
  "apps",
  "android",
  "prototype",
  "src",
  "main",
  "java",
  "xyz",
  "hlovet",
  "portal",
  "prototype",
);

const moduleMappings = {
  "account-privacy-api.ts": ["NativePrivacyApi.kt", "NativeAdminDeletedUsersApi.kt"],
  "admin-api.ts": ["NativeAdminUsersApi.kt", "NativeRoleApi.kt"],
  "ai-api.ts": ["NativeAiApi.kt", "NativeAdminAiApi.kt"],
  "analytics-api.ts": ["NativeAdminDataApi.kt"],
  "android-release-api.ts": ["NativeAdminAndroidApi.kt", "NativeInstallApi.kt"],
  "anonymous-topics-api.ts": ["NativeAnonymousTopicApi.kt"],
  "announcements-api.ts": ["NativePortalApi.kt", "NativeCommunityApi.kt", "NativeAdminAnnouncementsApi.kt"],
  "article-api.ts": ["NativeArticleApi.kt", "NativeTemplateApi.kt", "NativeReportsApi.kt", "NativeAdminViolationsApi.kt"],
  "audit-api.ts": ["NativeAdminDataApi.kt"],
  "auth-api.ts": ["NativeAuthApi.kt", "NativeProfileApi.kt", "NativePrivacyApi.kt", "NativeIntegrationsApi.kt"],
  "background-api.ts": ["NativeSiteSettingsApi.kt", "NativeAdminSettingsApi.kt", "PreviewApi.kt"],
  "cache-admin-api.ts": ["NativeAdminCacheApi.kt"],
  "discovery-api.ts": ["NativeDiscoveryApi.kt", "NativeTopicApi.kt", "NativeResourceApi.kt", "NativeProfileApi.kt"],
  "distribution-api.ts": ["NativeDistributionApi.kt"],
  "feedback-api.ts": ["NativeRecommendationFeedbackApi.kt", "NativeCommunityApi.kt"],
  "integrations-api.ts": ["NativeIntegrationsApi.kt", "NativeAdminIntegrationsApi.kt"],
  "moderation-api.ts": ["NativeReportsApi.kt"],
  "portal-api.ts": ["NativePortalApi.kt"],
  "push-api.ts": ["NativeFirebasePush.kt"],
  "reputation-api.ts": ["NativeResourceApi.kt"],
  "resource-api.ts": ["NativeResourceApi.kt"],
  "search-api.ts": ["NativeSearchApi.kt"],
  "security-api.ts": ["NativeAuthApi.kt", "NativePrivacyApi.kt", "NativeAdminSecurityApi.kt"],
  "site-settings-api.ts": ["NativeSiteSettingsApi.kt", "NativeAdminSettingsApi.kt", "NativeAuthApi.kt", "NativeInstallApi.kt", "PreviewApi.kt"],
  "social-api.ts": ["NativeSocialApi.kt", "NativeGroupApi.kt", "NativeCommunityApi.kt", "NativeFriendsApi.kt"],
  "suggestions-api.ts": ["NativeDiscoveryApi.kt", "NativeCommunityApi.kt"],
  "system-status-api.ts": ["NativeSystemStatusApi.kt", "NativeAdminBackupApi.kt", "NativeAdminOperationsApi.kt", "NativeAdminStorageApi.kt"],
};

function read(filePath) {
  return fs.readFileSync(filePath, "utf8");
}

function normalize(raw) {
  let value = raw
    .replace(/\\["']/g, "")
    .replace(/\$\{[^}]+(?:query|Query|params|Params|page|Page|search|Search|sort|Sort|status|Status|batch|Batch|filter|Filter)[^}]*\}/g, "")
    .replace(/\$\{[^}]+\}/g, "{param}")
    .replace(/\$[A-Za-z_][A-Za-z0-9_]*/g, "{param}")
    .replace(/\b(?:encodeURIComponent|encode)\([^)]*\)/g, "{param}")
    .replace(/\b(?:page|pageSize|sort|q|search|status|targetType|batch|frequency|token|id|slug|username|articleId|collectionId|topicId|userId|conversationId|messageId)=[^&]+/g, "{query}")
    .replace(/\?.*$/, "")
    .replace(/\/{2,}/g, "/")
    .replace(/(?<!\/)\{param\}$/, "")
    .replace(/\/$/, "");
  return value || "/";
}

function extractWebEndpoints(source) {
  const values = [];
  const literalPattern = /(["'`])([\/][^"'`]*?)\1/g;
  for (const match of source.matchAll(literalPattern)) {
    const raw = match[2];
    if (raw.startsWith("/")) values.push(normalize(raw));
  }
  return new Set(values);
}

function extractAndroidEndpoints(source) {
  const values = [];
  const literalPattern = /(["'`])([\/][^"'`]*?)\1/g;
  for (const match of source.matchAll(literalPattern)) {
    const raw = match[2];
    if (raw.startsWith("/")) values.push(normalize(raw));
  }
  const baseUrlPattern = /(["'`])\$baseUrl([^"'`]*?)\1/g;
  for (const match of source.matchAll(baseUrlPattern)) {
    const raw = match[2];
    if (raw.startsWith("/")) values.push(normalize(raw));
  }
  return new Set(values);
}

function formatSet(values) {
  return [...values].sort((left, right) => left.localeCompare(right));
}

function isExtractionArtifact(endpoint) {
  return endpoint === "/" ||
    endpoint === "/api" ||
    endpoint === "/{param}" ||
    endpoint.includes("${") ||
    endpoint.includes(")");
}

const knownImplementationAliases = [
  [
    (endpoint) => endpoint === "/account-privacy/admin/users/{param}/totp/reset",
    "NativeAdminUsersApi builds the user id into the same TOTP reset endpoint.",
  ],
  [
    (endpoint) => endpoint === "/discovery/recommendations/feedback/{param}/{param}",
    "NativeRecommendationFeedbackApi builds the target type and id into the same restore endpoint.",
  ],
  [
    (endpoint) => endpoint === "/anonymous-topics/admin",
    "NativeAnonymousTopicApi uses the same admin list endpoint with its query string built in the dynamic path template.",
  ],
  [
    (endpoint) => endpoint === "/articles/admin/article-reports" || endpoint === "/articles/admin/article-reports/summary" || endpoint === "/articles/admin/comment-reports" || endpoint === "/articles/admin/comment-reports/summary",
    "NativeReportsApi uses the unified moderation queue and summary, which covers the PC article/comment report lists and counts in one source-aware API.",
  ],
  [
    (endpoint) => endpoint === "/ai/media-tasks/{param}/image",
    "NativeAiScreen downloads the authenticated result URL directly for stored images.",
  ],
  [
    (endpoint) => endpoint === "/ai/tools/{param}",
    "NativeAiApi executes encoded tool names through the same endpoint; the static extractor cannot see the encoded template.",
  ],
  [
    (endpoint) => endpoint === "/articles/{param}/images",
    "NativeWriteScreen uses the article attachment endpoint for image uploads and inserts the returned image URL.",
  ],
  [
    (endpoint) => endpoint === "/auth/me/password",
    "Android uses the verified sensitive-action endpoint /account-privacy/me/password for the same password-change flow.",
  ],
  [
    (endpoint) => endpoint === "/articles/mine/reports" || endpoint === "/articles/mine/reports/{param}/preview",
    "Android uses the current moderation report endpoints; NativeReportsApi keeps the same list and preview behavior.",
  ],
  [
    (endpoint) => endpoint === "/distribution/email",
    "The Android subscription screen reads and updates email delivery through NativeDiscoveryApi.",
  ],
  [
    (endpoint) => endpoint === "/profiles/public" || endpoint === "/profiles/visible" || endpoint === "/social/profiles/{param}",
    "NativeProfileApi resolves public and authenticated profile data through its profile-path helper.",
  ],
  [
    (endpoint) => endpoint === "/push/config" || endpoint === "/push/status" || endpoint === "/push/subscriptions",
    "Android uses native push registration and channel settings instead of the browser push endpoints.",
  ],
  [
    (endpoint) => endpoint === "/social/group-invitations",
    "NativeGroupApi reads invitations together with join approvals from /social/group-approvals.",
  ],
  [
    (endpoint) => endpoint === "/discovery/tags/subscribe",
    "The PC helper is exported without a user-facing entry; Android keeps the existing-tag frequency controls and does not add an orphan action.",
  ],
];

const discoveredWebModules = fs.readdirSync(webLibRoot).filter((name) => name.endsWith("-api.ts")).sort();
const mappedWebModules = Object.keys(moduleMappings).sort();
const unmappedWebModules = discoveredWebModules.filter((name) => !moduleMappings[name]);
const staleWebModules = mappedWebModules.filter((name) => !discoveredWebModules.includes(name));
const missingAndroidMappings = Object.entries(moduleMappings).flatMap(([webFile, androidFiles]) =>
  androidFiles.filter((androidFile) => !fs.existsSync(path.join(androidRoot, androidFile))).map((androidFile) => `${webFile} -> ${androidFile}`),
);
if (unmappedWebModules.length || staleWebModules.length || missingAndroidMappings.length) {
  console.error("API module mapping coverage failed:");
  for (const name of unmappedWebModules) console.error(`- unmapped PC API module: ${name}`);
  for (const name of staleWebModules) console.error(`- stale PC API mapping: ${name}`);
  for (const name of missingAndroidMappings) console.error(`- missing Android mapping file: ${name}`);
  process.exitCode = 1;
}

function implementationAlias(endpoint) {
  return knownImplementationAliases.find(([matches]) => matches(endpoint))?.[1] ?? null;
}

let exactCount = 0;
let aliasedCount = 0;
let skippedCount = 0;
const skippedCandidates = [];
const missing = [];
let checkedModules = 0;
for (const [webFile, androidFiles] of Object.entries(moduleMappings)) {
  const webPath = path.join(webLibRoot, webFile);
  if (!fs.existsSync(webPath)) continue;
  checkedModules += 1;
  const webEndpoints = extractWebEndpoints(read(webPath));
  const nativeEndpoints = new Set();
  for (const androidFile of androidFiles) {
    const androidPath = path.join(androidRoot, androidFile);
    if (!fs.existsSync(androidPath)) continue;
    for (const endpoint of extractAndroidEndpoints(read(androidPath))) nativeEndpoints.add(endpoint);
  }
  for (const endpoint of webEndpoints) {
    if (isExtractionArtifact(endpoint)) {
      skippedCount += 1;
      skippedCandidates.push({ webFile, endpoint });
      continue;
    }
    // Keep the mapping boundary meaningful: an endpoint must be present in one
    // of the Android files explicitly assigned to this PC module. Matching any
    // native file would let an admin or unrelated screen mask a user feature.
    if (nativeEndpoints.has(endpoint)) {
      exactCount += 1;
      continue;
    }
    const alias = implementationAlias(endpoint);
    if (alias) {
      aliasedCount += 1;
      continue;
    }
    missing.push({ webFile, endpoint });
  }
}

if (missing.length) {
  console.log(`\nConfirmed API candidates requiring review: ${missing.length}`);
  for (const item of missing.sort((left, right) => `${left.webFile}:${left.endpoint}`.localeCompare(`${right.webFile}:${right.endpoint}`))) {
    console.log(`- ${item.webFile}: ${item.endpoint}`);
  }
  process.exitCode = 1;
}
console.log(`\nAPI parity audit checked ${checkedModules}/${discoveredWebModules.length} current PC modules: ${exactCount} exact/cross-module matches, ${aliasedCount} intentional aliases, ${skippedCount} dynamic/parser-only candidates, ${missing.length} confirmed candidates.`);
if (process.argv.includes("--verbose")) {
  for (const item of skippedCandidates.sort((left, right) => `${left.webFile}:${left.endpoint}`.localeCompare(`${right.webFile}:${right.endpoint}`))) {
    console.log(`- dynamic/parser-only: ${item.webFile}: ${item.endpoint}`);
  }
}
