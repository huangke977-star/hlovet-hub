import { AiProvider } from "./dto/ai.dto";

export interface AiChatMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

export interface AiProviderUsage {
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
}

export interface AiProviderCompletion {
  text: string;
  usage: AiProviderUsage;
}

export interface AiProviderEmbedding {
  embeddings: number[][];
  usage: AiProviderUsage;
}

export interface AiProviderTranscription {
  text: string;
  usage: AiProviderUsage;
}

export interface AiProviderImage {
  url: string | null;
  base64: string | null;
  revisedPrompt: string | null;
  usage: AiProviderUsage;
}

export interface AiProviderModel {
  id: string;
  label: string;
}

export interface AiProviderRequest {
  provider: AiProvider;
  baseUrl: string;
  apiKey: string;
  model: string;
  maxOutputTokens: number;
  timeoutSeconds: number;
  messages: AiChatMessage[];
}

export class AiProviderClientError extends Error {
  constructor(message: string, public readonly retryable = false) {
    super(message);
    this.name = "AiProviderClientError";
  }
}

export async function completeWithProvider(input: AiProviderRequest): Promise<AiProviderCompletion> {
  if (input.provider === "openai" || input.provider === "openai-compatible" || input.provider === "deepseek" || input.provider === "custom") {
    return completeOpenAiCompatible(input);
  }
  if (input.provider === "anthropic") {
    return completeAnthropic(input);
  }
  return completeGoogle(input);
}

export async function listModelsWithProvider(input: Pick<AiProviderRequest, "provider" | "baseUrl" | "apiKey" | "timeoutSeconds">): Promise<AiProviderModel[]> {
  if (input.provider === "anthropic") {
    throw new AiProviderClientError("Anthropic 暂不提供标准的模型列表接口，请手动填写模型名称。\nAnthropic does not expose a standard model-list endpoint; enter the model name manually.");
  }
  const response = input.provider === "google"
    ? await getJson(appendGoogleModelsEndpoint(input.baseUrl), {}, input.timeoutSeconds, input.apiKey)
    : await getJson(appendEndpoint(input.baseUrl, "models"), { Authorization: `Bearer ${input.apiKey}` }, input.timeoutSeconds);
  const models = input.provider === "google" ? readGoogleModels(response) : readOpenAiModels(response);
  if (!models.length) throw new AiProviderClientError("AI 服务没有返回可用模型。\nThe AI service returned no usable models.");
  return models;
}

export async function createEmbeddingsWithProvider(input: {
  provider: AiProvider;
  baseUrl: string;
  apiKey: string;
  model: string;
  texts: string[];
  timeoutSeconds: number;
}): Promise<AiProviderEmbedding> {
  if (!(input.provider === "openai" || input.provider === "openai-compatible" || input.provider === "deepseek" || input.provider === "custom")) {
    throw new AiProviderClientError("当前供应商未提供标准 Embedding 接口，请改用 OpenAI 兼容供应商或配置专用接口。\nThis provider does not expose a standard embeddings endpoint.");
  }
  const response = await postJson(
    appendEndpoint(input.baseUrl, "embeddings"),
    { model: input.model, input: input.texts },
    { Authorization: `Bearer ${input.apiKey}` },
    input.timeoutSeconds,
  );
  const items = Array.isArray(response.data) ? response.data : [];
  const embeddings = items
    .map((item) => isRecord(item) && Array.isArray(item.embedding) ? item.embedding.filter((value): value is number => typeof value === "number" && Number.isFinite(value)) : null)
    .filter((item): item is number[] => Boolean(item?.length));
  if (embeddings.length !== input.texts.length) throw new AiProviderClientError("Embedding 服务返回的向量数量不完整。\nThe embeddings service returned an incomplete vector set.");
  return { embeddings, usage: readUsage(response, { prompt: ["usage", "prompt_tokens"], completion: [], total: ["usage", "total_tokens"] }) };
}

export async function transcribeWithProvider(input: {
  provider: AiProvider;
  baseUrl: string;
  apiKey: string;
  model: string;
  file: Buffer;
  filename: string;
  mimeType: string;
  timeoutSeconds: number;
}): Promise<AiProviderTranscription> {
  if (!(input.provider === "openai" || input.provider === "openai-compatible" || input.provider === "deepseek" || input.provider === "custom")) {
    throw new AiProviderClientError("当前供应商未提供标准语音转文字接口，请配置 OpenAI 兼容的 audio/transcriptions 接口。\nThis provider does not expose a standard transcription endpoint.");
  }
  const form = new FormData();
  form.append("model", input.model);
  form.append("file", new Blob([input.file as unknown as BlobPart], { type: input.mimeType || "application/octet-stream" }), input.filename || "audio.bin");
  const response = await postForm(appendEndpoint(input.baseUrl, "audio/transcriptions"), form, { Authorization: `Bearer ${input.apiKey}` }, input.timeoutSeconds);
  const text = typeof response.text === "string" ? response.text.trim() : "";
  if (!text) throw new AiProviderClientError("语音服务返回了空内容。\nThe transcription service returned empty content.");
  return { text, usage: readUsage(response, { prompt: ["usage", "prompt_tokens"], completion: ["usage", "completion_tokens"], total: ["usage", "total_tokens"] }) };
}

export async function ocrWithProvider(input: {
  provider: AiProvider;
  baseUrl: string;
  apiKey: string;
  model: string;
  imageDataUrl: string;
  prompt: string;
  timeoutSeconds: number;
}): Promise<AiProviderCompletion> {
  if (!(input.provider === "openai" || input.provider === "openai-compatible" || input.provider === "deepseek" || input.provider === "custom" || input.provider === "google")) {
    throw new AiProviderClientError("当前供应商未提供图片理解接口。\nThis provider does not expose a vision endpoint.");
  }
  if (input.provider === "google") {
    throw new AiProviderClientError("Google 图片识别适配将在配置 Gemini 视觉模型后启用，请先使用 OpenAI 兼容视觉接口。\nConfigure a Gemini vision model or use an OpenAI-compatible vision endpoint.");
  }
  const response = await postJson(
    appendEndpoint(input.baseUrl, "chat/completions"),
    {
      model: input.model,
      messages: [{ role: "user", content: [{ type: "text", text: input.prompt }, { type: "image_url", image_url: { url: input.imageDataUrl } }] }],
      max_tokens: 4000,
    },
    { Authorization: `Bearer ${input.apiKey}` },
    input.timeoutSeconds,
  );
  const text = readString(response, ["choices", 0, "message", "content"]);
  if (!text) throw new AiProviderClientError("图片识别服务返回了空内容。\nThe vision service returned empty content.");
  return { text, usage: readUsage(response, { prompt: ["usage", "prompt_tokens"], completion: ["usage", "completion_tokens"], total: ["usage", "total_tokens"] }) };
}

export async function generateImageWithProvider(input: {
  provider: AiProvider;
  baseUrl: string;
  apiKey: string;
  model: string;
  prompt: string;
  size: string;
  timeoutSeconds: number;
}): Promise<AiProviderImage> {
  if (!(input.provider === "openai" || input.provider === "openai-compatible" || input.provider === "custom" || input.provider === "google")) {
    throw new AiProviderClientError("当前供应商未提供标准图片生成接口。\nThis provider does not expose a standard image generation endpoint.");
  }
  if (input.provider === "google") {
    throw new AiProviderClientError("Google 图片生成需要 Imagen 专用适配，请先使用 OpenAI 兼容图片生成接口。\nGoogle image generation requires an Imagen-specific adapter.");
  }
  const response = await postJson(
    appendEndpoint(input.baseUrl, "images/generations"),
    { model: input.model, prompt: input.prompt, size: input.size, n: 1 },
    { Authorization: `Bearer ${input.apiKey}` },
    input.timeoutSeconds,
  );
  const first = Array.isArray(response.data) && isRecord(response.data[0]) ? response.data[0] : null;
  const url = first && typeof first.url === "string" ? first.url : null;
  const base64 = first && typeof first.b64_json === "string" ? first.b64_json : null;
  if (!url && !base64) throw new AiProviderClientError("图片生成服务没有返回图片。\nThe image generation service returned no image.");
  return { url, base64, revisedPrompt: first && typeof first.revised_prompt === "string" ? first.revised_prompt : null, usage: readUsage(response, { prompt: ["usage", "prompt_tokens"], completion: ["usage", "completion_tokens"], total: ["usage", "total_tokens"] }) };
}

async function completeOpenAiCompatible(input: AiProviderRequest): Promise<AiProviderCompletion> {
  const response = await postJson(
    appendEndpoint(input.baseUrl, "chat/completions"),
    {
      model: input.model,
      messages: input.messages,
      max_tokens: input.maxOutputTokens,
    },
    { Authorization: `Bearer ${input.apiKey}` },
    input.timeoutSeconds,
  );
  const text = readString(response, ["choices", 0, "message", "content"]);
  if (!text) throw new AiProviderClientError("AI 服务返回了空内容。");
  const usage = readUsage(response, {
    prompt: ["usage", "prompt_tokens"],
    completion: ["usage", "completion_tokens"],
    total: ["usage", "total_tokens"],
  });
  return { text, usage };
}

async function completeAnthropic(input: AiProviderRequest): Promise<AiProviderCompletion> {
  const system = input.messages.filter((message) => message.role === "system").map((message) => message.content).join("\n");
  const messages = input.messages.filter((message) => message.role !== "system");
  const response = await postJson(
    appendAnthropicEndpoint(input.baseUrl),
    {
      model: input.model,
      max_tokens: input.maxOutputTokens,
      ...(system ? { system } : {}),
      messages,
    },
    { "x-api-key": input.apiKey, "anthropic-version": "2023-06-01" },
    input.timeoutSeconds,
  );
  const text = readTextParts(response, ["content"]);
  if (!text) throw new AiProviderClientError("AI 服务返回了空内容。");
  const usage = readUsage(response, {
    prompt: ["usage", "input_tokens"],
    completion: ["usage", "output_tokens"],
    total: [],
  });
  return { text, usage: withTotal(usage) };
}

async function completeGoogle(input: AiProviderRequest): Promise<AiProviderCompletion> {
  const response = await postJson(
    appendGoogleEndpoint(input.baseUrl, input.model),
    {
      contents: input.messages.filter((message) => message.role !== "system").map((message) => ({
        role: message.role === "assistant" ? "model" : "user",
        parts: [{ text: message.content }],
      })),
      systemInstruction: input.messages.some((message) => message.role === "system")
        ? { parts: [{ text: input.messages.filter((message) => message.role === "system").map((message) => message.content).join("\n") }] }
        : undefined,
      generationConfig: { maxOutputTokens: input.maxOutputTokens },
    },
    {},
    input.timeoutSeconds,
    input.apiKey,
  );
  const text = readTextParts(response, ["candidates", 0, "content", "parts"]);
  if (!text) throw new AiProviderClientError("AI 服务返回了空内容。");
  const usage = readUsage(response, {
    prompt: ["usageMetadata", "promptTokenCount"],
    completion: ["usageMetadata", "candidatesTokenCount"],
    total: ["usageMetadata", "totalTokenCount"],
  });
  return { text, usage };
}

async function postJson(
  endpoint: string,
  body: Record<string, unknown>,
  headers: Record<string, string>,
  timeoutSeconds: number,
  apiKey?: string,
): Promise<Record<string, unknown>> {
  let url: URL;
  try {
    url = new URL(endpoint);
  } catch {
    throw new AiProviderClientError("AI 接口地址无效。");
  }
  if (apiKey) url.searchParams.set("key", apiKey);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutSeconds * 1000);
  try {
    const response = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json", ...headers },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new AiProviderClientError(`AI 服务请求失败（HTTP ${response.status}）。`, response.status === 429 || response.status >= 500);
    }
    try {
      const parsed: unknown = await response.json();
      if (!isRecord(parsed)) throw new Error();
      return parsed;
    } catch {
      throw new AiProviderClientError("AI 服务返回了无效响应。");
    }
  } catch (error) {
    if (error instanceof AiProviderClientError) throw error;
    if (error instanceof Error && error.name === "AbortError") {
      throw new AiProviderClientError("AI 请求超时，请稍后重试。", true);
    }
    throw new AiProviderClientError("AI 服务暂时无法连接。", true);
  } finally {
    clearTimeout(timeout);
  }
}

async function postForm(
  endpoint: string,
  body: FormData,
  headers: Record<string, string>,
  timeoutSeconds: number,
): Promise<Record<string, unknown>> {
  let url: URL;
  try {
    url = new URL(endpoint);
  } catch {
    throw new AiProviderClientError("AI 接口地址无效。\nThe AI base URL is invalid.");
  }
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutSeconds * 1000);
  try {
    const response = await fetch(url, { method: "POST", headers: { Accept: "application/json", ...headers }, body, signal: controller.signal });
    if (!response.ok) throw new AiProviderClientError(`AI 服务请求失败（HTTP ${response.status}）。`, response.status === 429 || response.status >= 500);
    const parsed: unknown = await response.json();
    if (!isRecord(parsed)) throw new AiProviderClientError("AI 服务返回了无效响应。\nThe AI service returned an invalid response.");
    return parsed;
  } catch (error) {
    if (error instanceof AiProviderClientError) throw error;
    if (error instanceof Error && error.name === "AbortError") throw new AiProviderClientError("AI 请求超时，请稍后重试。\nThe AI request timed out.", true);
    throw new AiProviderClientError("AI 服务暂时无法连接。\nThe AI service is temporarily unavailable.", true);
  } finally {
    clearTimeout(timeout);
  }
}

async function getJson(
  endpoint: string,
  headers: Record<string, string>,
  timeoutSeconds: number,
  apiKey?: string,
): Promise<Record<string, unknown>> {
  let url: URL;
  try {
    url = new URL(endpoint);
  } catch {
    throw new AiProviderClientError("AI 接口地址无效。\nThe AI base URL is invalid.");
  }
  if (apiKey) url.searchParams.set("key", apiKey);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutSeconds * 1000);
  try {
    const response = await fetch(url, { headers: { Accept: "application/json", ...headers }, signal: controller.signal });
    if (!response.ok) throw new AiProviderClientError(`AI 模型列表请求失败（HTTP ${response.status}）。`, response.status === 429 || response.status >= 500);
    const parsed: unknown = await response.json();
    if (!isRecord(parsed)) throw new AiProviderClientError("AI 服务返回了无效的模型列表。\nThe AI service returned an invalid model list.");
    return parsed;
  } catch (error) {
    if (error instanceof AiProviderClientError) throw error;
    if (error instanceof Error && error.name === "AbortError") throw new AiProviderClientError("AI 模型列表请求超时，请稍后重试。\nThe model-list request timed out.", true);
    throw new AiProviderClientError("AI 模型列表暂时无法读取。\nThe model list is temporarily unavailable.", true);
  } finally {
    clearTimeout(timeout);
  }
}

function appendEndpoint(baseUrl: string, suffix: string): string {
  const base = baseUrl.trim().replace(/\/+$/, "");
  return base.endsWith(`/${suffix}`) ? base : `${base}/${suffix}`;
}

function appendAnthropicEndpoint(baseUrl: string): string {
  const base = baseUrl.trim().replace(/\/+$/, "");
  if (base.endsWith("/messages")) return base;
  return base.endsWith("/v1") ? `${base}/messages` : `${base}/v1/messages`;
}

function appendGoogleEndpoint(baseUrl: string, model: string): string {
  const base = baseUrl.trim().replace(/\/+$/, "");
  if (base.includes(":generateContent")) return base;
  return `${base}/models/${encodeURIComponent(model)}:generateContent`;
}

function appendGoogleModelsEndpoint(baseUrl: string): string {
  const base = baseUrl.trim().replace(/\/+$/, "");
  if (base.endsWith("/models")) return base;
  return `${base}/models`;
}

function readOpenAiModels(value: Record<string, unknown>): AiProviderModel[] {
  const items = Array.isArray(value.data) ? value.data : Array.isArray(value.models) ? value.models : [];
  return items.map((item) => {
    if (!isRecord(item) || typeof item.id !== "string") return null;
    const id = item.id.trim();
    return id ? { id, label: id } : null;
  }).filter((item): item is AiProviderModel => Boolean(item));
}

function readGoogleModels(value: Record<string, unknown>): AiProviderModel[] {
  const items = Array.isArray(value.models) ? value.models : [];
  return items.map((item) => {
    if (!isRecord(item) || typeof item.name !== "string") return null;
    const methods = Array.isArray(item.supportedGenerationMethods) ? item.supportedGenerationMethods : [];
    if (methods.length && !methods.includes("generateContent")) return null;
    const id = item.name.replace(/^models\//, "").trim();
    if (!id) return null;
    const label = typeof item.displayName === "string" && item.displayName.trim() ? `${item.displayName.trim()} (${id})` : id;
    return { id, label };
  }).filter((item): item is AiProviderModel => Boolean(item));
}

function readString(value: unknown, path: Array<string | number>): string | null {
  let current = value;
  for (const key of path) {
    if (typeof key === "number" && Array.isArray(current)) current = current[key];
    else if (typeof key === "string" && isRecord(current)) current = current[key];
    else return null;
  }
  return typeof current === "string" ? current.trim() : null;
}

function readTextParts(value: unknown, path: Array<string | number>): string | null {
  let current: unknown = value;
  for (const key of path) {
    if (typeof key === "number" && Array.isArray(current)) current = current[key];
    else if (typeof key === "string" && isRecord(current)) current = current[key];
    else return null;
  }
  if (!Array.isArray(current)) return null;
  const text = current.map((part) => isRecord(part) && typeof part.text === "string" ? part.text : "").filter(Boolean).join("").trim();
  return text || null;
}

function readUsage(value: unknown, paths: { prompt: Array<string | number>; completion: Array<string | number>; total: Array<string | number> }): AiProviderUsage {
  return {
    promptTokens: paths.prompt.length ? readNumber(value, paths.prompt) : null,
    completionTokens: paths.completion.length ? readNumber(value, paths.completion) : null,
    totalTokens: paths.total.length ? readNumber(value, paths.total) : null,
  };
}

function withTotal(usage: AiProviderUsage): AiProviderUsage {
  return { ...usage, totalTokens: usage.totalTokens ?? (usage.promptTokens !== null && usage.completionTokens !== null ? usage.promptTokens + usage.completionTokens : null) };
}

function readNumber(value: unknown, path: Array<string | number>): number | null {
  let current: unknown = value;
  for (const key of path) {
    if (typeof key === "number" && Array.isArray(current)) current = current[key];
    else if (typeof key === "string" && isRecord(current)) current = current[key];
    else return null;
  }
  return typeof current === "number" && Number.isFinite(current) ? Math.max(0, Math.round(current)) : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
