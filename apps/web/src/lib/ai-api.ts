import { authHeaders, requestJson } from "./auth-api";

export type AiProvider = "openai-compatible" | "anthropic" | "google";

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
  globalConcurrency: number;
  userConcurrency: number;
  maxOutputTokens: number;
  requestTimeoutSeconds: number;
  dailyRequestLimit: number;
  billingCurrency: "USD" | "CNY";
  inputCostPerMillionMicros: number;
  outputCostPerMillionMicros: number;
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
  globalConcurrency: number;
  userConcurrency: number;
  maxOutputTokens: number;
  requestTimeoutSeconds: number;
  dailyRequestLimit: number;
  billingCurrency: "USD" | "CNY";
  inputCostPerMillionMicros: number;
  outputCostPerMillionMicros: number;
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
  usage: { promptTokens: number | null; completionTokens: number | null; totalTokens: number | null };
}

export interface AiInvocationLog {
  id: number;
  operation: string;
  provider: AiProvider;
  model: string;
  status: string;
  promptTokens: number | null;
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
  today: { requests: number; totalTokens: number; estimatedCostMicros: number };
  logs: AiInvocationLog[];
}

export function testAiAdminConnection(token: string) {
  return requestJson<AiConnectionTestResult>("/ai/admin/test-connection", { method: "POST", headers: authHeaders(token) });
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
  usage: { promptTokens: number | null; completionTokens: number | null; totalTokens: number | null };
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
  description: string;
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
  title: string;
  text: string;
  sources: AiSource[];
  provider: AiProvider;
  model: string;
  durationMs: number;
  usage: { promptTokens: number | null; completionTokens: number | null; totalTokens: number | null };
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
  messages: Array<{ id: number; role: "user" | "assistant"; content: string; sources: AiSource[] | null; createdAt: string }>;
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

export interface AiToolExecutionResult {
  invocationId: number;
  status: string;
  output?: unknown;
  requiresConfirmation?: boolean;
  confirmationToken?: string;
  expiresAt?: string;
  preview?: { title: string; summary: string; category: string; tags: string; content: string };
}

export function executeAiTool(accessToken: string, name: string, input?: Record<string, unknown>, conversationId?: number) {
  return requestJson<AiToolExecutionResult>(`/ai/tools/${encodeURIComponent(name)}`, { method: "POST", headers: { ...authHeaders(accessToken), "Content-Type": "application/json" }, body: JSON.stringify({ input, conversationId }) });
}

export function confirmAiTool(accessToken: string, invocationId: number, confirmationToken: string) {
  return requestJson<{ success: true; invocationId: number; article: { id: number; slug: string; title: string } }>(`/ai/tool-invocations/${invocationId}/confirm`, { method: "POST", headers: { ...authHeaders(accessToken), "Content-Type": "application/json" }, body: JSON.stringify({ confirmationToken }) });
}
