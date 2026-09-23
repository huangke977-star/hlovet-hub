import { authHeaders, requestBlob, requestJson } from "./auth-api";

export type AiProvider = "openai" | "openai-compatible" | "deepseek" | "custom" | "anthropic" | "google";

export interface AiPricingPreset {
  provider: AiProvider;
  model: string;
  label: string;
  billingCurrency: "USD" | "CNY";
  inputCostPerMillionMicros: number;
  cachedInputCostPerMillionMicros: number;
  outputCostPerMillionMicros: number;
  note: string;
}

export interface AiResourceRecommendation {
  cpuCores: number;
  totalMemoryMiB: number;
  freeMemoryMiB: number;
  globalConcurrency: number;
  userConcurrency: number;
  maxGlobalConcurrency: number;
  maxUserConcurrency: number;
}

export interface AiAdminConfiguration {
  enabled: boolean;
  provider: AiProvider;
  baseUrl: string;
  model: string;
  apiKeyConfigured: boolean;
  fallbackEnabled: boolean;
  fallbackProvider: AiProvider | null;
  fallbackBaseUrl: string;
  fallbackModel: string;
  fallbackApiKeyConfigured: boolean;
  globalConcurrency: number;
  userConcurrency: number;
  maxOutputTokens: number;
  requestTimeoutSeconds: number;
  dailyRequestLimit: number;
  billingCurrency: "USD" | "CNY";
  inputCostPerMillionMicros: number;
  cachedInputCostPerMillionMicros: number;
  outputCostPerMillionMicros: number;
  fallbackInputCostPerMillionMicros: number;
  fallbackCachedInputCostPerMillionMicros: number;
  fallbackOutputCostPerMillionMicros: number;
  pricingPresets: AiPricingPreset[];
  ragEnabled: boolean;
  ragTopK: number;
  contextMaxMessages: number;
  contextSummaryThreshold: number;
  contextRetentionDays: number;
  qualityEvaluationEnabled: boolean;
  recommendation: AiResourceRecommendation;
  encryptionConfigured: boolean;
  updatedAt: string;
}

export interface AiAdminConfigurationUpdate {
  enabled: boolean;
  provider: AiProvider;
  baseUrl: string;
  model: string;
  apiKey?: string;
  clearApiKey?: boolean;
  fallbackEnabled?: boolean;
  fallbackProvider?: AiProvider;
  fallbackBaseUrl?: string;
  fallbackModel?: string;
  fallbackApiKey?: string;
  clearFallbackApiKey?: boolean;
  globalConcurrency: number;
  userConcurrency: number;
  maxOutputTokens: number;
  requestTimeoutSeconds: number;
  dailyRequestLimit: number;
  billingCurrency: "USD" | "CNY";
  inputCostPerMillionMicros: number;
  cachedInputCostPerMillionMicros?: number;
  outputCostPerMillionMicros: number;
  fallbackInputCostPerMillionMicros: number;
  fallbackCachedInputCostPerMillionMicros?: number;
  fallbackOutputCostPerMillionMicros: number;
  ragEnabled?: boolean;
  ragTopK?: number;
  contextMaxMessages?: number;
  contextSummaryThreshold?: number;
  contextRetentionDays?: number;
  qualityEvaluationEnabled?: boolean;
}

export function getAiAdminConfiguration(token: string) {
  return requestJson<AiAdminConfiguration>("/ai/admin/configuration", { headers: authHeaders(token), cache: "no-store" });
}

export function updateAiAdminConfiguration(token: string, input: AiAdminConfigurationUpdate) {
  return requestJson<AiAdminConfiguration>("/ai/admin/configuration", { method: "PATCH", headers: authHeaders(token), body: JSON.stringify(input) });
}

export interface AiConnectionTestResult {
  success: true;
  provider: AiProvider;
  model: string;
  durationMs: number;
  usage: { promptTokens: number | null; cachedPromptTokens: number | null; completionTokens: number | null; totalTokens: number | null };
}

export interface AiInvocationLog {
  id: number;
  operation: string;
  provider: AiProvider;
  model: string;
  status: string;
  promptTokens: number | null;
  cachedPromptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  durationMs: number;
  estimatedCostMicros: number | null;
  errorSummary: string | null;
  username: string | null;
  nickname: string | null;
  createdAt: string;
}

export interface AiInvocationOverview {
  today: { requests: number; totalTokens: number; cachedPromptTokens: number; cacheHitRate: number; estimatedCostMicros: number };
  logs: AiInvocationLog[];
}

export function testAiAdminConnection(token: string) {
  return requestJson<AiConnectionTestResult>("/ai/admin/test-connection", { method: "POST", headers: authHeaders(token) });
}

export interface AiModelOption {
  id: string;
  label: string;
}

export function listAiAdminModels(token: string, input: { provider: AiProvider; capability?: AiCapability; baseUrl: string; apiKey?: string; credential?: "primary" | "fallback" }) {
  return requestJson<{ provider: AiProvider; models: AiModelOption[]; fetchedAt: string }>("/ai/admin/models", {
    method: "POST",
    headers: { ...authHeaders(token), "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

export function getAiAdminInvocations(token: string) {
  return requestJson<AiInvocationOverview>("/ai/admin/invocations?limit=30", { headers: authHeaders(token), cache: "no-store" });
}

export interface AiToolInvocationAudit {
  id: number;
  toolName: string;
  status: string;
  requiresConfirmation: boolean;
  username: string;
  nickname: string | null;
  createdAt: string;
  completedAt: string | null;
}

export function getAiAdminToolInvocations(token: string) {
  return requestJson<{ items: AiToolInvocationAudit[] }>("/ai/admin/tools?limit=50", { headers: authHeaders(token), cache: "no-store" });
}

export type AiCapability = "embedding" | "ocr" | "transcription" | "image_generation";

export interface AiCapabilityConfiguration {
  capability: AiCapability;
  label: { zh: string; en: string };
  enabled: boolean;
  provider: AiProvider;
  baseUrl: string;
  model: string;
  apiKeyConfigured: boolean;
  fallbackEnabled: boolean;
  fallbackProvider: AiProvider | null;
  fallbackBaseUrl: string;
  fallbackModel: string;
  fallbackApiKeyConfigured: boolean;
  globalConcurrency: number;
  userConcurrency: number;
  requestTimeoutSeconds: number;
  dailyRequestLimit: number;
  monthlyBudgetMicros: number;
  billingCurrency: "USD" | "CNY";
  inputCostPerMillionMicros: number;
  cachedInputCostPerMillionMicros: number;
  outputCostPerMillionMicros: number;
  fallbackInputCostPerMillionMicros: number;
  fallbackCachedInputCostPerMillionMicros: number;
  fallbackOutputCostPerMillionMicros: number;
  unitCostMicros: number;
  unitName: string;
  maxInputBytes: number;
  officialPricingNote: string;
  pricingPresets: AiPricingPreset[];
  updatedAt: string;
}

export interface AiCapabilityConfigurationUpdate {
  enabled: boolean;
  provider: AiProvider;
  baseUrl: string;
  model: string;
  apiKey?: string;
  clearApiKey?: boolean;
  fallbackEnabled?: boolean;
  fallbackProvider?: AiProvider;
  fallbackBaseUrl?: string;
  fallbackModel?: string;
  fallbackApiKey?: string;
  clearFallbackApiKey?: boolean;
  globalConcurrency: number;
  userConcurrency: number;
  requestTimeoutSeconds: number;
  dailyRequestLimit: number;
  monthlyBudgetMicros: number;
  billingCurrency: "USD" | "CNY";
  inputCostPerMillionMicros: number;
  cachedInputCostPerMillionMicros?: number;
  outputCostPerMillionMicros: number;
  fallbackInputCostPerMillionMicros: number;
  fallbackCachedInputCostPerMillionMicros?: number;
  fallbackOutputCostPerMillionMicros: number;
  unitCostMicros: number;
  unitName: string;
  maxInputBytes: number;
}

export function getAiAdminCapabilities(token: string) {
  return requestJson<{ items: AiCapabilityConfiguration[] }>("/ai/admin/capabilities", { headers: authHeaders(token), cache: "no-store" });
}

export function updateAiAdminCapability(token: string, capability: AiCapability, input: AiCapabilityConfigurationUpdate) {
  return requestJson<AiCapabilityConfiguration>(`/ai/admin/capabilities/${capability}`, { method: "PATCH", headers: { ...authHeaders(token), "Content-Type": "application/json" }, body: JSON.stringify(input) });
}

export function testAiAdminCapability(token: string, capability: AiCapability) {
  return requestJson<{ success: true; capability: AiCapability; durationMs: number; dimension?: number; message?: string }>(`/ai/admin/capabilities/${capability}/test`, { method: "POST", headers: authHeaders(token) });
}

export interface AiUsageOverview {
  days: number;
  total: { requests: number; tokens: number; cachedInputUnits: number; estimatedCostMicros: number };
  byCapability: Array<{ capability: string; requests: number; success: number; failed: number; successRate: number; failureRate: number; tokens: number; cachedInputUnits: number; estimatedCostMicros: number }>;
  quality: { feedbackTotal: number; helpful: number; unhelpful: number; averageRating: number | null };
  recent: Array<Record<string, unknown>>;
}

export function getAiAdminUsage(token: string, days = 30) {
  return requestJson<AiUsageOverview>(`/ai/admin/usage?days=${days}`, { headers: authHeaders(token), cache: "no-store" });
}

export function getAiAdminKnowledge(token: string) {
  return requestJson<{ documents: number; chunks: number; ready: number; keywordReady: number; pendingEmbedding: number; failed: number; vectors: number; semanticSearchAvailable: boolean; keywordSearchAvailable: boolean }>("/ai/admin/knowledge", { headers: authHeaders(token), cache: "no-store" });
}

export interface AiKnowledgeDocument {
  id: number;
  sourceType: string;
  sourceId: number;
  slug: string;
  title: string;
  status: string;
  chunkCount: number;
  vectorCount: number;
  embeddingModel: string | null;
  errorSummary: string | null;
  lastIndexedAt: string | null;
  updatedAt: string;
}

export function getAiAdminKnowledgeDocuments(token: string) {
  return requestJson<{ items: AiKnowledgeDocument[] }>("/ai/admin/knowledge/documents?limit=100", { headers: authHeaders(token), cache: "no-store" });
}

export function reindexAiKnowledgeDocument(token: string, id: number) {
  return requestJson<{ id: number; removed: boolean; status: string }>(`/ai/admin/knowledge/documents/${id}/reindex`, { method: "POST", headers: authHeaders(token) });
}

export function reindexAiKnowledge(token: string, limit = 50) {
  return requestJson<{ scanned: number; indexed: number; keywordReady: number; pending: number; failed: number }>(`/ai/admin/knowledge/reindex?limit=${limit}`, { method: "POST", headers: authHeaders(token) });
}

export type ArticleAssistantOperation = "title" | "outline" | "summary" | "taxonomy" | "polish" | "rewrite" | "expand" | "shorten" | "correct" | "format";

export interface ArticleAssistantInput {
  operation: ArticleAssistantOperation;
  title?: string;
  content?: string;
  selectedText?: string;
  category?: string;
  tags?: string;
  locale?: "zh-CN" | "en-US";
}

export interface ArticleAssistantResult {
  text: string;
  operation: ArticleAssistantOperation;
  provider: AiProvider;
  model: string;
  durationMs: number;
  usage: { promptTokens: number | null; cachedPromptTokens: number | null; completionTokens: number | null; totalTokens: number | null };
}

export function runArticleAssistant(accessToken: string, input: ArticleAssistantInput) {
  return requestJson<ArticleAssistantResult>("/ai/article-assistant", {
    method: "POST",
    headers: { ...authHeaders(accessToken), "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

export interface AiToolDefinition {
  name: string;
  label: string;
  labelEn?: string;
  description: string;
  descriptionEn?: string;
  readOnly: boolean;
  requiresConfirmation: boolean;
}

export interface AiSource {
  type: "article";
  id: number;
  slug: string;
  title: string;
  author: string;
}

export interface AiChatResult {
  conversationId: number;
  messageId: number;
  title: string;
  text: string;
  sources: AiSource[];
  provider: AiProvider;
  model: string;
  durationMs: number;
  usage: { promptTokens: number | null; cachedPromptTokens: number | null; completionTokens: number | null; totalTokens: number | null };
}

export interface AiConversationListItem {
  id: number;
  title: string;
  createdAt: string;
  updatedAt: string;
  _count: { messages: number };
}

export interface AiConversation {
  id: number;
  title: string;
  createdAt: string;
  updatedAt: string;
  messages: Array<{ id: number; role: "user" | "assistant"; content: string; sources: AiSource[] | null; qualityRating: -1 | 1 | null; createdAt: string }>;
}

export function listAiTools(accessToken: string) {
  return requestJson<AiToolDefinition[]>("/ai/tools", { headers: authHeaders(accessToken), cache: "no-store" });
}

export function listAiConversations(accessToken: string) {
  return requestJson<{ items: AiConversationListItem[] }>("/ai/conversations", { headers: authHeaders(accessToken), cache: "no-store" });
}

export function getAiConversation(accessToken: string, id: number) {
  return requestJson<AiConversation>(`/ai/conversations/${id}`, { headers: authHeaders(accessToken), cache: "no-store" });
}

export function runAiChat(accessToken: string, input: { conversationId?: number; message: string; articleId?: number; articleSlug?: string; locale?: "zh-CN" | "en-US" }) {
  return requestJson<AiChatResult>("/ai/chat", { method: "POST", headers: { ...authHeaders(accessToken), "Content-Type": "application/json" }, body: JSON.stringify(input) });
}

export function sendAiQualityFeedback(accessToken: string, input: { conversationId: number; messageId: number; rating: -1 | 1; note?: string }) {
  return requestJson<{ id: number; conversationId: number; messageId: number; rating: -1 | 1 }>("/ai/quality-feedback", {
    method: "POST",
    headers: { ...authHeaders(accessToken), "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

export interface AiToolExecutionResult {
  invocationId: number;
  status: string;
  output?: unknown;
  requiresConfirmation?: boolean;
  confirmationToken?: string;
  expiresAt?: string;
  preview?: { title: string; summary: string; category: string; tags: string; suggestedTags: string[]; content: string };
}

export function executeAiTool(accessToken: string, name: string, input?: Record<string, unknown>, conversationId?: number) {
  return requestJson<AiToolExecutionResult>(`/ai/tools/${encodeURIComponent(name)}`, { method: "POST", headers: { ...authHeaders(accessToken), "Content-Type": "application/json" }, body: JSON.stringify({ input, conversationId }) });
}

export function confirmAiTool(accessToken: string, invocationId: number, confirmationToken: string, selectedSuggestedTags: string[] = []) {
  return requestJson<{ success: true; invocationId: number; article: { id: number; slug: string; title: string } }>(`/ai/tool-invocations/${invocationId}/confirm`, { method: "POST", headers: { ...authHeaders(accessToken), "Content-Type": "application/json" }, body: JSON.stringify({ confirmationToken, selectedSuggestedTags }) });
}

export function runAiOcr(accessToken: string, file: File, prompt?: string) {
  const form = new FormData();
  form.append("file", file);
  if (prompt?.trim()) form.append("prompt", prompt.trim());
  return requestJson<AiMediaTaskDetail & { text: string }>("/ai/media/ocr", { method: "POST", headers: authHeaders(accessToken), body: form });
}

export function runAiTranscription(accessToken: string, file: File) {
  const form = new FormData();
  form.append("file", file);
  return requestJson<AiMediaTaskDetail & { text: string }>("/ai/media/transcription", { method: "POST", headers: authHeaders(accessToken), body: form });
}

export function runAiImageGeneration(accessToken: string, prompt: string, size = "1024x1024") {
  return requestJson<AiMediaTaskDetail>("/ai/media/image", { method: "POST", headers: { ...authHeaders(accessToken), "Content-Type": "application/json" }, body: JSON.stringify({ prompt, size }) });
}

export interface AiUserMediaCapability {
  capability: "ocr" | "transcription" | "image_generation";
  label: { zh: string; en: string };
  available: boolean;
  maxInputBytes: number;
  unitName: string;
}

export interface AiMediaTaskSummary {
  id: number;
  capability: "ocr" | "transcription" | "image_generation";
  status: "queued" | "processing" | "completed" | "failed";
  prompt: string | null;
  inputMimeType: string | null;
  inputBytes: number | null;
  resultPreview: string | null;
  resultUrl: string | null;
  hasStoredImage: boolean;
  errorSummary: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
}

export interface AiMediaTaskDetail extends AiMediaTaskSummary {
  resultText: string | null;
  revisedPrompt: string | null;
}

export function getAiUserMediaCapabilities(accessToken: string) {
  return requestJson<{ items: AiUserMediaCapability[] }>("/ai/media-capabilities", { headers: authHeaders(accessToken), cache: "no-store" });
}

export function listAiMediaTasks(accessToken: string) {
  return requestJson<{ items: AiMediaTaskSummary[] }>("/ai/media-tasks", { headers: authHeaders(accessToken), cache: "no-store" });
}

export function getAiMediaTask(accessToken: string, id: number) {
  return requestJson<AiMediaTaskDetail>(`/ai/media-tasks/${id}`, { headers: authHeaders(accessToken), cache: "no-store" });
}

export function downloadAiMediaTaskImage(accessToken: string, id: number) {
  return requestBlob(`/ai/media-tasks/${id}/image`, { headers: authHeaders(accessToken), cache: "force-cache" });
}
