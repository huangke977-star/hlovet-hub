import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const toolsDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(toolsDir, "..", "..", "..");
const webAppRoot = path.join(repoRoot, "apps", "web", "src", "app");
const mainActivityPath = path.join(
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
  "MainActivity.kt",
);
const adminHomePath = path.join(
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
  "NativeAdminHomeScreen.kt",
);

const developmentOnlyRoutes = new Set([
  "article-body-test",
  "codex-editor-test",
  "debug-editor",
  "task-debug",
  "task-preview-debug",
  "verification-code-block",
]);

function walk(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const fullPath = path.join(directory, entry.name);
    return entry.isDirectory() ? walk(fullPath) : [fullPath];
  });
}

function toPortalRoute(pagePath) {
  const relativeDirectory = path.relative(webAppRoot, path.dirname(pagePath));
  if (!relativeDirectory) return "/";
  const segments = relativeDirectory.split(path.sep).filter(Boolean);
  if (segments.some((segment) => segment.startsWith("__") || developmentOnlyRoutes.has(segment))) return null;
  const routeSegments = segments
    .filter((segment) => !/^\([^/]+\)$/.test(segment))
    .map((segment) => segment.replace(/^\[\[\.\.\.(.+)\]\]$/, "{$1}").replace(/^\.\.\.(.+)$/, "{$1}").replace(/^\[(.+)\]$/, "{$1}"));
  return `/${routeSegments.join("/")}` || "/";
}

function routeIsMapped(route, source) {
  // These callbacks are consumed from the Android App Link intent rather than
  // through openPortalUrl, so keep them explicit instead of treating the web
  // fallback page as an ordinary native route.
  if (route === "/native-auth/google") return source.includes("NativeAuthenticationLink.Google");

  const dynamicMarker = route.indexOf("{");
  if (dynamicMarker >= 0) {
    const prefix = route.slice(0, dynamicMarker);
    return source.includes(`path.startsWith(\"${prefix}\")`);
  }

  const escapedRoute = route.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const exactBranch = new RegExp(`path\\s*==\\s*\"${escapedRoute}\"`);
  const whenBranch = new RegExp(`\"${escapedRoute}\"\\s*(?:,|->)`);
  return exactBranch.test(source) || whenBranch.test(source);
}

const pageRoutes = walk(webAppRoot)
  .filter((filePath) => path.basename(filePath) === "page.tsx")
  .map(toPortalRoute)
  .filter(Boolean);
const routes = [...new Set(pageRoutes)].sort();
const androidSource = fs.readFileSync(mainActivityPath, "utf8");
const missing = routes.filter((route) => !routeIsMapped(route, androidSource));

const adminHomeSource = fs.readFileSync(adminHomePath, "utf8");
const adminEntryPaths = [...adminHomeSource.matchAll(/NativeAdminEntry\("([^\"]+)"/g)].map((match) => match[1]);
const missingAdminEntries = adminEntryPaths.filter((route) => !routeIsMapped(route, androidSource));

if (missing.length || missingAdminEntries.length) {
  console.error(`route parity failed: ${missing.length} unmapped PC routes`);
  for (const route of missing) console.error(`- ${route}`);
  if (missingAdminEntries.length) {
    console.error(`admin entry parity failed: ${missingAdminEntries.length} unmapped admin entries`);
    for (const route of missingAdminEntries) console.error(`- ${route}`);
  }
  process.exitCode = 1;
} else {
  console.log(`route parity passed: ${routes.length} PC routes have MainActivity mappings`);
  console.log(`admin entry parity passed: ${adminEntryPaths.length} NativeAdminHome entries have MainActivity mappings`);
}
