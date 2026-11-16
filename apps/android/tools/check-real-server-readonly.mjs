#!/usr/bin/env node

const DEFAULT_SITE_URL = "https://5200918.xyz";
const DEFAULT_TIMEOUT_MS = 15_000;
const DEFAULT_PAGE_SIZE = 20;

/**
 * 解析真实服务器只读检查的命令行参数。
 * @returns {{siteUrl: string, apiUrl: string, timeoutMs: number, pageSize: number, json: boolean}}
 */
function parseArguments() {
  const args = process.argv.slice(2);
  let siteUrl = DEFAULT_SITE_URL;
  let timeoutMs = DEFAULT_TIMEOUT_MS;
  let pageSize = DEFAULT_PAGE_SIZE;
  let json = false;

  // 读取站点地址、超时和分页大小，确保脚本可以复用于隔离测试服务器。
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === "--json") {
      json = true;
      continue;
    }
    if (argument === "--base-url") {
      siteUrl = args[index + 1] ?? "";
      index += 1;
      continue;
    }
    if (argument.startsWith("--base-url=")) {
      siteUrl = argument.slice("--base-url=".length);
      continue;
    }
    if (argument === "--timeout-ms") {
      timeoutMs = Number(args[index + 1]);
      index += 1;
      continue;
    }
    if (argument.startsWith("--timeout-ms=")) {
      timeoutMs = Number(argument.slice("--timeout-ms=".length));
      continue;
    }
    if (argument === "--page-size") {
      pageSize = Number(args[index + 1]);
      index += 1;
      continue;
    }
    if (argument.startsWith("--page-size=")) {
      pageSize = Number(argument.slice("--page-size=".length));
      continue;
    }
    throw new Error(`未知参数：${argument}`);
  }

  // 允许传入站点根地址或已经包含 /api 的地址，避免人工拼接时重复路径。
  const normalizedUrl = normalizeBaseUrl(siteUrl);
  const hasApiSuffix = normalizedUrl.endsWith("/api");
  const siteBaseUrl = hasApiSuffix ? normalizedUrl.slice(0, -4) : normalizedUrl;
  const apiBaseUrl = hasApiSuffix ? normalizedUrl : `${normalizedUrl}/api`;

  // 限制参数范围，避免错误配置导致请求数量或等待时间异常。
  if (!Number.isFinite(timeoutMs) || timeoutMs < 1_000) {
    throw new Error("--timeout-ms 必须是不小于 1000 的数值。");
  }
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 50) {
    throw new Error("--page-size 必须是 1 到 50 之间的整数。");
  }

  return { siteUrl: siteBaseUrl, apiUrl: apiBaseUrl, timeoutMs, pageSize, json };
}

/**
 * 规范化服务器根地址并拒绝无法作为 URL 解析的输入。
 * @param {string} value 命令行传入的地址。
 * @returns {string} 不带末尾斜杠的绝对地址。
 */
function normalizeBaseUrl(value) {
  const trimmed = String(value).trim().replace(/\/+$/, "");
  if (!trimmed) throw new Error("--base-url 不能为空。");
  const parsed = new URL(trimmed);
  if (!/^https?:$/.test(parsed.protocol)) throw new Error("--base-url 只支持 http 或 https。");
  return parsed.toString().replace(/\/+$/, "");
}

/**
 * 发起一个不带认证信息的 GET 请求，并保留响应文本用于结构检查。
 * @param {string} url 完整请求地址。
 * @param {number} timeoutMs 单次请求超时时间。
 * @returns {Promise<{status: number, ok: boolean, contentType: string, text: string, latencyMs: number}>}
 */
async function get(url, timeoutMs) {
  const startedAt = performance.now();
  try {
    // 只发送 GET 和 no-store，避免脚本改变业务状态或复用过期缓存。
    const response = await fetch(url, {
      method: "GET",
      cache: "no-store",
      redirect: "follow",
      headers: { Accept: "application/json, text/html, application/xml, text/xml, */*" },
      signal: AbortSignal.timeout(timeoutMs),
    });
    const text = await response.text();
    return {
      status: response.status,
      ok: response.ok,
      contentType: response.headers.get("content-type") ?? "",
      text,
      latencyMs: elapsed(startedAt),
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return { status: 0, ok: false, contentType: "", text: message, latencyMs: elapsed(startedAt) };
  }
}

/**
 * 解析 JSON 响应，给出可读的结构错误信息。
 * @param {{status: number, ok: boolean, contentType: string, text: string, latencyMs: number}} response HTTP 响应。
 * @returns {unknown} JSON 结果。
 */
function parseJson(response) {
  try {
    return JSON.parse(response.text);
  } catch {
    throw new Error(`HTTP ${response.status || "请求失败"} 未返回合法 JSON。`);
  }
}

/**
 * 校验公开 JSON 接口并返回统一的检查结果。
 * @param {string} id 检查标识。
 * @param {string} label 中文名称。
 * @param {string} url 请求地址。
 * @param {(payload: unknown) => void} validate 可选的响应结构校验。
 * @param {number} timeoutMs 单次请求超时时间。
 * @returns {Promise<CheckResult>} 检查结果。
 */
async function checkJson(id, label, url, validate, timeoutMs) {
  const response = await get(url, timeoutMs);
  if (!response.ok) return failed(id, label, url, `HTTP ${response.status || "请求失败"}`, response.latencyMs);
  try {
    const payload = parseJson(response);
    validate?.(payload);
    return passed(id, label, url, `HTTP ${response.status}`, response.latencyMs);
  } catch (error) {
    return failed(id, label, url, error instanceof Error ? error.message : String(error), response.latencyMs);
  }
}

/**
 * 校验公开文本资源并返回统一的检查结果。
 * @param {string} id 检查标识。
 * @param {string} label 中文名称。
 * @param {string} url 请求地址。
 * @param {(text: string, contentType: string) => void} validate 文本校验。
 * @param {number} timeoutMs 单次请求超时时间。
 * @returns {Promise<CheckResult>} 检查结果。
 */
async function checkText(id, label, url, validate, timeoutMs) {
  const response = await get(url, timeoutMs);
  if (!response.ok) return failed(id, label, url, `HTTP ${response.status || "请求失败"}`, response.latencyMs);
  try {
    validate(response.text, response.contentType);
    return passed(id, label, url, `HTTP ${response.status}`, response.latencyMs);
  } catch (error) {
    return failed(id, label, url, error instanceof Error ? error.message : String(error), response.latencyMs);
  }
}

/**
 * 从公开列表响应中读取条目数组，兼容当前接口的 items 结构。
 * @param {unknown} payload 接口响应。
 * @param {string} resource 资源名称。
 * @returns {Array<Record<string, unknown>>} 列表条目。
 */
function readItems(payload, resource) {
  if (!payload || typeof payload !== "object" || !Array.isArray(payload.items)) {
    throw new Error(`${resource} 列表缺少 items 数组。`);
  }
  return payload.items.filter((item) => item && typeof item === "object");
}

/**
 * 执行站点探针、PWA 资源和公开内容的真实服务器只读检查。
 * @param {{siteUrl: string, apiUrl: string, timeoutMs: number, pageSize: number, json: boolean}} options 检查参数。
 * @returns {Promise<ReadonlyCheckReport>} 完整检查报告。
 */
async function run(options) {
  const checks = [];

  // 先检查健康、就绪和公开分发资源，确认后续动态列表请求具备基础条件。
  checks.push(await checkJson("api-health", "API 存活", `${options.apiUrl}/health`, (payload) => {
    if (!payload || typeof payload !== "object" || payload.status !== "ok") throw new Error("status 不是 ok。");
  }, options.timeoutMs));
  checks.push(await checkJson("api-readiness", "API 就绪", `${options.apiUrl}/health/ready`, (payload) => {
    if (!payload || typeof payload !== "object" || payload.status !== "ok") throw new Error("就绪状态不是 ok。");
  }, options.timeoutMs));
  checks.push(await checkJson("sitemap", "站点地图接口", `${options.apiUrl}/distribution/sitemap`, (payload) => {
    if (!Array.isArray(payload)) throw new Error("站点地图不是数组。");
  }, options.timeoutMs));
  checks.push(await checkText("rss", "站点 RSS", `${options.apiUrl}/distribution/feeds/site.rss`, (text) => {
    if (!/<rss[\s>]/i.test(text)) throw new Error("响应不包含 RSS 根节点。");
  }, options.timeoutMs));
  checks.push(await checkJson("manifest", "中文 Manifest", `${options.siteUrl}/manifest.webmanifest`, validateManifest, options.timeoutMs));
  checks.push(await checkJson("english-manifest", "英文 Manifest", `${options.siteUrl}/en/manifest.webmanifest`, validateManifest, options.timeoutMs));
  checks.push(await checkText("service-worker", "Service Worker", `${options.siteUrl}/sw.js`, (text) => {
    if (!text.trim()) throw new Error("Service Worker 内容为空。");
  }, options.timeoutMs));
  checks.push(await checkJson("assetlinks", "Android App Links", `${options.siteUrl}/.well-known/assetlinks.json`, (payload) => {
    if (!Array.isArray(payload)) throw new Error("assetlinks 不是数组。");
  }, options.timeoutMs));

  // 从真实服务器动态读取文章列表，避免依赖过期的固定文章 ID。
  const articleList = await get(`${options.apiUrl}/articles?page=1&pageSize=${options.pageSize}`, options.timeoutMs);
  const articleItems = await recordDynamicListCheck(checks, "articles", "公开文章列表", articleList, options.pageSize);
  const article = articleItems.find((item) => typeof item.slug === "string" && item.slug.length > 0);
  if (article && typeof article.slug === "string") {
    const encodedSlug = encodeURIComponent(article.slug);
    checks.push(await checkJson("article-detail", "文章详情", `${options.apiUrl}/articles/${encodedSlug}`, (payload) => {
      if (!payload || typeof payload !== "object" || typeof payload.slug !== "string") throw new Error("文章详情缺少 slug。");
    }, options.timeoutMs));
    checks.push(await checkJson("article-comments", "文章评论读取", `${options.apiUrl}/articles/${encodedSlug}/comments?pageSize=20`, (payload) => {
      if (!payload || typeof payload !== "object" || !Array.isArray(payload.items)) throw new Error("评论响应缺少 items 数组。");
    }, options.timeoutMs));
  } else {
    checks.push(skipped("article-detail", "文章详情", "公开文章列表为空，未找到可读取 slug。"));
    checks.push(skipped("article-comments", "文章评论读取", "公开文章列表为空，未找到可读取 slug。"));
  }

  // 从真实服务器动态读取专题和合集，再检查其公开详情。
  const topicList = await get(`${options.apiUrl}/discovery/topics?page=1&pageSize=${options.pageSize}`, options.timeoutMs);
  const topicItems = await recordDynamicListCheck(checks, "topics", "公开专题列表", topicList, options.pageSize);
  const topic = topicItems.find((item) => typeof item.slug === "string" && item.slug.length > 0);
  if (topic && typeof topic.slug === "string") {
    checks.push(await checkJson("topic-detail", "专题详情", `${options.apiUrl}/discovery/topics/public/${encodeURIComponent(topic.slug)}`, (payload) => {
      if (!payload || typeof payload !== "object" || typeof payload.title !== "string") throw new Error("专题详情缺少 title。");
    }, options.timeoutMs));
  } else {
    checks.push(skipped("topic-detail", "专题详情", "公开专题列表为空，未找到可读取 slug。"));
  }

  const collectionList = await get(`${options.apiUrl}/discovery/collections?page=1&pageSize=${options.pageSize}`, options.timeoutMs);
  const collectionItems = await recordDynamicListCheck(checks, "collections", "公开合集列表", collectionList, options.pageSize);
  const collection = collectionItems.find((item) => Number.isInteger(item.id));
  if (collection && Number.isInteger(collection.id)) {
    checks.push(await checkJson("collection-detail", "合集详情", `${options.apiUrl}/discovery/collections/public/${collection.id}`, (payload) => {
      if (!payload || typeof payload !== "object" || typeof payload.name !== "string") throw new Error("合集详情缺少 name。");
    }, options.timeoutMs));
    checks.push(await checkText("collection-rss", "合集 RSS", `${options.apiUrl}/distribution/feeds/collections/${collection.id}.rss`, (text) => {
      if (!/<rss[\s>]/i.test(text)) throw new Error("响应不包含 RSS 根节点。");
    }, options.timeoutMs));
  } else {
    checks.push(skipped("collection-detail", "合集详情", "公开合集列表为空，未找到可读取 id。"));
    checks.push(skipped("collection-rss", "合集 RSS", "公开合集列表为空，未找到可读取 id。"));
  }

  return {
    generatedAt: new Date().toISOString(),
    siteUrl: options.siteUrl,
    apiUrl: options.apiUrl,
    checks,
    passed: checks.every((check) => check.status !== "failed"),
  };
}

/**
 * 记录动态列表请求的状态并返回列表条目。
 * @param {CheckResult[]} checks 当前检查结果集合。
 * @param {string} id 检查标识。
 * @param {string} label 中文名称。
 * @param {{status: number, ok: boolean, contentType: string, text: string, latencyMs: number}} response HTTP 响应。
 * @param {number} pageSize 请求的分页大小。
 * @returns {Promise<Array<Record<string, unknown>>>} 动态列表条目。
 */
async function recordDynamicListCheck(checks, id, label, response, pageSize) {
  if (!response.ok) {
    checks.push(failed(id, label, "动态列表", `HTTP ${response.status || "请求失败"}`, response.latencyMs));
    return [];
  }
  try {
    const items = readItems(parseJson(response), label);
    checks.push(passed(id, label, "动态列表", `HTTP ${response.status}，读取 ${items.length}/${pageSize} 条`, response.latencyMs));
    return items;
  } catch (error) {
    checks.push(failed(id, label, "动态列表", error instanceof Error ? error.message : String(error), response.latencyMs));
    return [];
  }
}

/**
 * 校验 Manifest 至少包含浏览器安装所需的基本字段。
 * @param {unknown} payload Manifest JSON。
 * @returns {void}
 */
function validateManifest(payload) {
  if (!payload || typeof payload !== "object" || typeof payload.name !== "string" || !Array.isArray(payload.icons)) {
    throw new Error("Manifest 缺少 name 或 icons。");
  }
}

/**
 * 计算请求耗时并保留一位小数。
 * @param {number} startedAt performance.now() 的开始时间。
 * @returns {number} 毫秒耗时。
 */
function elapsed(startedAt) {
  return Math.round((performance.now() - startedAt) * 10) / 10;
}

/**
 * 创建通过结果。
 * @param {string} id 检查标识。
 * @param {string} label 中文名称。
 * @param {string} url 请求地址。
 * @param {string} detail 结果说明。
 * @param {number} latencyMs 请求耗时。
 * @returns {CheckResult} 通过结果。
 */
function passed(id, label, url, detail, latencyMs) {
  return { id, label, url, status: "passed", detail, latencyMs };
}

/**
 * 创建失败结果。
 * @param {string} id 检查标识。
 * @param {string} label 中文名称。
 * @param {string} url 请求地址。
 * @param {string} detail 失败说明。
 * @param {number} latencyMs 请求耗时。
 * @returns {CheckResult} 失败结果。
 */
function failed(id, label, url, detail, latencyMs) {
  return { id, label, url, status: "failed", detail, latencyMs };
}

/**
 * 创建因真实数据为空而跳过的结果。
 * @param {string} id 检查标识。
 * @param {string} label 中文名称。
 * @param {string} detail 跳过原因。
 * @returns {CheckResult} 跳过结果。
 */
function skipped(id, label, detail) {
  return { id, label, url: "", status: "skipped", detail, latencyMs: 0 };
}

/**
 * 输出命令行报告，方便人工核对每一个真实服务器请求。
 * @param {ReadonlyCheckReport} report 只读检查报告。
 * @returns {void}
 */
function printReport(report) {
  console.log(`真实服务器只读检查：${report.siteUrl}`);
  console.log(`生成时间：${report.generatedAt}`);
  for (const check of report.checks) {
    const status = check.status === "passed" ? "通过" : check.status === "skipped" ? "跳过" : "失败";
    const latency = check.latencyMs ? ` ${check.latencyMs} ms` : "";
    console.log(`[${status}] ${check.label}：${check.detail}${latency}`);
  }
  const failedCount = report.checks.filter((check) => check.status === "failed").length;
  const skippedCount = report.checks.filter((check) => check.status === "skipped").length;
  console.log(`汇总：${report.checks.length - failedCount - skippedCount} 通过，${skippedCount} 跳过，${failedCount} 失败。`);
}

/**
 * 执行脚本入口并使用失败码表示真实服务器检查未通过。
 * @returns {Promise<void>} 执行完成。
 */
async function main() {
  try {
    // 先读取参数，确保错误配置不会发起任何请求。
    const options = parseArguments();

    // 所有请求均在 run 内使用公开 GET，脚本不会携带账号令牌或执行业务写入。
    const report = await run(options);
    if (options.json) {
      console.log(JSON.stringify(report, null, 2));
    } else {
      printReport(report);
    }
    if (!report.passed) process.exitCode = 1;
  } catch (error) {
    console.error(`[real-server-readonly] ${error instanceof Error ? error.message : String(error)}`);
    process.exitCode = 2;
  }
}

/** @typedef {{id: string, label: string, url: string, status: "passed"|"failed"|"skipped", detail: string, latencyMs: number}} CheckResult */
/** @typedef {{generatedAt: string, siteUrl: string, apiUrl: string, checks: CheckResult[], passed: boolean}} ReadonlyCheckReport */

await main();
