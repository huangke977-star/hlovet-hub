import {
  BadGatewayException,
  BadRequestException,
  ForbiddenException,
  GatewayTimeoutException,
  Injectable,
  HttpException,
  HttpStatus,
  ServiceUnavailableException,
} from "@nestjs/common";
import { createHash, randomBytes } from "node:crypto";
import { AiConfiguration, Prisma } from "../generated/prisma/client";
import { AuthenticatedUser } from "../auth/auth.types";
import { ArticlesService } from "../articles/articles.service";
import { PrismaService } from "../prisma/prisma.service";
import { RedisService } from "../redis/redis.service";
import { SecretCryptoService } from "../security/secret-crypto.service";
import { AiArticleOperation, AiChatDto, AiProvider, AiQualityFeedbackDto, AiToolInputDto, AiToolName, ArticleAssistantDto, ListAiModelsDto, UpdateAiConfigurationDto } from "./dto/ai.dto";
import {
  AiChatMessage,
  AiProviderClientError,
  AiProviderCompletion,
  completeWithProvider,
  listModelsWithProvider,
} from "./ai-provider.client";
import { AiKnowledgeService } from "./ai-knowledge.service";
import { getAiPricingPresets } from "./ai-pricing";
import os from "node:os";

const AI_TOOL_DEFINITIONS = [
  { name: "get_my_summary", label: "我的积分与成长", labelEn: "My points and growth", description: "读取当前账号的积分、经验、等级和最近账本记录。", descriptionEn: "Read this account's points, experience, level, and recent ledger.", readOnly: true, requiresConfirmation: false },
  { name: "list_my_tasks", label: "我的待办", labelEn: "My tasks", description: "整理当前账号未读的通知和待处理入口，不会自动修改状态。", descriptionEn: "Summarize this account's unread notifications and pending entries without changing them.", readOnly: true, requiresConfirmation: false },
  { name: "list_my_subscriptions", label: "我的订阅", labelEn: "My subscriptions", description: "读取当前账号订阅的作者、专题和合集概况。", descriptionEn: "Read this account's author, topic, and collection subscriptions.", readOnly: true, requiresConfirmation: false },
  { name: "list_my_earnings", label: "我的收益", labelEn: "My earnings", description: "读取当前账号资源兑换产生的待结算、已结算和退款收益。", descriptionEn: "Read pending, settled, and refunded resource earnings for this account.", readOnly: true, requiresConfirmation: false },
  { name: "list_my_articles", label: "我的文章状态", labelEn: "My article status", description: "读取当前账号文章的草稿、发布和审核状态。", descriptionEn: "Read this account's draft, publication, and moderation states.", readOnly: true, requiresConfirmation: false },
  { name: "search_visible_articles", label: "搜索可见文章", labelEn: "Search visible articles", description: "只搜索当前账号有权限查看的站内文章。", descriptionEn: "Search only site articles visible to this account.", readOnly: true, requiresConfirmation: false },
  { name: "list_visible_articles", label: "权限范围内文章", labelEn: "Visible articles", description: "列出当前账号有权限查看的近期站内文章。", descriptionEn: "List recent site articles visible to this account.", readOnly: true, requiresConfirmation: false },
  { name: "list_recommended_articles", label: "个性化推荐文章", labelEn: "Recommended articles", description: "根据当前账号的兴趣和反馈，列出有权限查看的推荐文章。", descriptionEn: "List recommended articles visible to this account based on interests and feedback.", readOnly: true, requiresConfirmation: false },
  { name: "get_article_context", label: "读取文章上下文", labelEn: "Read article context", description: "读取当前账号有权限查看的文章正文并作为问答来源。", descriptionEn: "Read a visible article as a source for questions and answers.", readOnly: true, requiresConfirmation: false },
  { name: "summarize_topic", label: "总结专题", labelEn: "Summarize topic", description: "只总结当前账号有权限查看的专题文章。", descriptionEn: "Summarize only topic articles visible to this account.", readOnly: true, requiresConfirmation: false },
  { name: "summarize_collection", label: "总结合集", labelEn: "Summarize collection", description: "只总结当前账号有权限查看的合集文章。", descriptionEn: "Summarize only collection articles visible to this account.", readOnly: true, requiresConfirmation: false },
  { name: "get_admin_overview", label: "解释后台概况", labelEn: "Explain admin overview", description: "读取管理员可见的运营概况，只返回汇总数据。", descriptionEn: "Read permitted operational aggregates for an administrator.", readOnly: true, requiresConfirmation: false },
  { name: "create_article_draft", label: "创建文章草稿", labelEn: "Create article draft", description: "根据文章描述生成标题、摘要、分类、标签和正文，预览后必须确认才会写入。", descriptionEn: "Generate a title, summary, taxonomy, and body from a description; confirmation is required before writing.", readOnly: false, requiresConfirmation: true },
] as const;

type AiSource = { type: "article"; id: number; slug: string; title: string; author: string };

export interface ResourceRecommendation {
  cpuCores: number;
  totalMemoryMiB: number;
  freeMemoryMiB: number;
  globalConcurrency: number;
  userConcurrency: number;
  maxGlobalConcurrency: number;
  maxUserConcurrency: number;
}

export interface AiCompletionOptions {
  userId: number;
  operation: string;
  messages: AiChatMessage[];
  allowDisabled?: boolean;
  capability?: string;
  sourceCount?: number;
  qualityScore?: number | null;
  qualityFlags?: string[];
}

class AiGatewayError extends Error {
  constructor(
    public readonly code: "concurrency" | "quota",
    message: string,
  ) {
    super(message);
  }
}

@Injectable()
export class AiService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly crypto: SecretCryptoService,
    private readonly redis: RedisService,
    private readonly articles?: ArticlesService,
    private readonly knowledge?: AiKnowledgeService,
  ) {}

  async getAdminConfiguration() {
    const config = await this.getConfiguration();
    return this.toAdminResponse(config);
  }

  async updateConfiguration(dto: UpdateAiConfigurationDto) {
    const current = await this.getConfiguration();
    const recommendation = this.getResourceRecommendation();
    if (dto.globalConcurrency > recommendation.maxGlobalConcurrency) {
      throw new BadRequestException(`全站并发不能超过当前服务器保护上限 ${recommendation.maxGlobalConcurrency}。\nGlobal concurrency cannot exceed the server protection limit of ${recommendation.maxGlobalConcurrency}.`);
    }
    if (dto.userConcurrency > recommendation.maxUserConcurrency || dto.userConcurrency > dto.globalConcurrency) {
      throw new BadRequestException("单用户并发不能超过服务器保护上限或全站并发。\nPer-user concurrency cannot exceed the server protection limit or global concurrency.");
    }
    if (dto.enabled && (!dto.model?.trim() || !dto.baseUrl?.trim() || (!current.apiKeyEncrypted && !dto.apiKey?.trim()))) {
      throw new BadRequestException("启用 AI 前请完整填写接口地址、模型和 API Key。\nConfigure the base URL, model, and API key before enabling AI.");
    }
    if (dto.clearApiKey && dto.enabled && !dto.apiKey?.trim()) {
      throw new BadRequestException("启用 AI 时不能清空 API Key。\nAn API key cannot be cleared while AI is enabled.");
    }
    const fallbackEnabled = dto.fallbackEnabled ?? current.fallbackEnabled;
    const fallbackProvider = dto.fallbackProvider ?? (current.fallbackProvider as AiProvider | null);
    const fallbackBaseUrl = dto.fallbackBaseUrl === undefined ? current.fallbackBaseUrl : dto.fallbackBaseUrl.trim();
    const fallbackModel = dto.fallbackModel === undefined ? current.fallbackModel : dto.fallbackModel.trim();
    const fallbackApiKey = dto.clearFallbackApiKey ? null : dto.fallbackApiKey?.trim() || this.decryptApiKey(current.fallbackApiKeyEncrypted);
    if (fallbackEnabled && (!fallbackProvider || !fallbackBaseUrl || !fallbackModel || !fallbackApiKey)) {
      throw new BadRequestException("启用备用模型前请完整填写备用供应商、接口地址、模型和 API Key。\nConfigure the fallback provider, base URL, model, and API key before enabling fallback.");
    }

    let apiKeyEncrypted = current.apiKeyEncrypted;
    if (dto.clearApiKey) apiKeyEncrypted = null;
    else if (dto.apiKey?.trim()) apiKeyEncrypted = this.crypto.encrypt(dto.apiKey.trim());
    let fallbackApiKeyEncrypted = current.fallbackApiKeyEncrypted;
    if (dto.clearFallbackApiKey) fallbackApiKeyEncrypted = null;
    else if (dto.fallbackApiKey?.trim()) fallbackApiKeyEncrypted = this.crypto.encrypt(dto.fallbackApiKey.trim());

    const saved = await this.prisma.aiConfiguration.update({
      where: { id: 1 },
      data: {
        enabled: dto.enabled,
        provider: dto.provider,
        baseUrl: dto.baseUrl?.trim() || null,
        model: dto.model?.trim() || null,
        apiKeyEncrypted,
        fallbackApiKeyEncrypted,
        ...(dto.fallbackEnabled === undefined ? {} : { fallbackEnabled: dto.fallbackEnabled }),
        ...(dto.fallbackProvider === undefined ? {} : { fallbackProvider: dto.fallbackProvider.trim() || null }),
        ...(dto.fallbackBaseUrl === undefined ? {} : { fallbackBaseUrl: dto.fallbackBaseUrl.trim() || null }),
        ...(dto.fallbackModel === undefined ? {} : { fallbackModel: dto.fallbackModel.trim() || null }),
        globalConcurrency: dto.globalConcurrency,
        userConcurrency: dto.userConcurrency,
        maxOutputTokens: dto.maxOutputTokens,
        requestTimeoutSeconds: dto.requestTimeoutSeconds,
        dailyRequestLimit: dto.dailyRequestLimit,
        billingCurrency: dto.billingCurrency,
        inputCostPerMillionMicros: dto.inputCostPerMillionMicros,
        ...(dto.cachedInputCostPerMillionMicros === undefined ? {} : { cachedInputCostPerMillionMicros: dto.cachedInputCostPerMillionMicros }),
        outputCostPerMillionMicros: dto.outputCostPerMillionMicros,
        ...(dto.fallbackInputCostPerMillionMicros === undefined ? {} : { fallbackInputCostPerMillionMicros: dto.fallbackInputCostPerMillionMicros }),
        ...(dto.fallbackCachedInputCostPerMillionMicros === undefined ? {} : { fallbackCachedInputCostPerMillionMicros: dto.fallbackCachedInputCostPerMillionMicros }),
        ...(dto.fallbackOutputCostPerMillionMicros === undefined ? {} : { fallbackOutputCostPerMillionMicros: dto.fallbackOutputCostPerMillionMicros }),
        ...(dto.ragEnabled === undefined ? {} : { ragEnabled: dto.ragEnabled }),
        ...(dto.ragTopK === undefined ? {} : { ragTopK: dto.ragTopK }),
        ...(dto.contextMaxMessages === undefined ? {} : { contextMaxMessages: dto.contextMaxMessages }),
        ...(dto.contextSummaryThreshold === undefined ? {} : { contextSummaryThreshold: dto.contextSummaryThreshold }),
        ...(dto.contextRetentionDays === undefined ? {} : { contextRetentionDays: dto.contextRetentionDays }),
        ...(dto.qualityEvaluationEnabled === undefined ? {} : { qualityEvaluationEnabled: dto.qualityEvaluationEnabled }),
      },
    });
    // A shorter retention window must take effect immediately. Summaries are
    // derived context too, so an older one cannot remain available to a model.
    if (dto.contextRetentionDays !== undefined && dto.contextRetentionDays !== current.contextRetentionDays) {
      await this.prisma.aiConversation.updateMany({
        data: { contextSummary: null, summaryMessageCount: 0, summaryUpdatedAt: null },
      });
    }
    return this.toAdminResponse(saved);
  }

  async testConnection(userId: number) {
    const startedAt = Date.now();
    const result = await this.execute({
      userId,
      operation: "test_connection",
      allowDisabled: true,
      messages: [{ role: "user", content: "Reply with OK only." }],
    });
    return {
      success: true,
      provider: result.provider,
      model: result.model,
      durationMs: Date.now() - startedAt,
      usage: result.usage,
    };
  }

  async listModels(dto: ListAiModelsDto) {
    const mainConfig = await this.getConfiguration();
    const capabilityConfig = dto.capability
      ? await this.prisma.aiCapabilityConfiguration.findUnique({ where: { capability: dto.capability } })
      : null;
    const config = capabilityConfig ?? mainConfig;
    const encryptedApiKey = dto.credential === "fallback" ? config.fallbackApiKeyEncrypted : config.apiKeyEncrypted;
    const apiKey = dto.apiKey?.trim() || this.decryptApiKey(encryptedApiKey);
    if (!apiKey) throw new BadRequestException("请先填写 API Key，或保留已保存的 API Key。\nEnter an API key or keep the saved key.");
    const baseUrl = dto.baseUrl?.trim() || (dto.credential === "fallback" ? config.fallbackBaseUrl?.trim() : config.baseUrl?.trim());
    if (!baseUrl) throw new BadRequestException("请先填写接口地址。\nEnter the AI base URL first.");
    try {
      const models = await listModelsWithProvider({ provider: dto.provider, baseUrl, apiKey, timeoutSeconds: config.requestTimeoutSeconds });
      return { provider: dto.provider, models, fetchedAt: new Date().toISOString() };
    } catch (error) {
      if (error instanceof AiProviderClientError) throw new BadGatewayException(error.message);
      throw error;
    }
  }

  async complete(options: AiCompletionOptions) {
    return this.execute(options);
  }

  async articleAssistant(userId: number, dto: ArticleAssistantDto) {
    const title = dto.title?.trim() ?? "";
    const content = dto.content?.trim() ?? "";
    const selectedText = dto.selectedText?.trim() ?? "";
    if (!title && !content && !selectedText) {
      throw new BadRequestException("请先填写标题或正文，再使用文章助手。\nAdd a title or article content before using the writing assistant.");
    }

    const locale = dto.locale === "en-US" ? "en-US" : "zh-CN";
    const bodyOperations: AiArticleOperation[] = ["polish", "rewrite", "expand", "shorten", "correct", "format"];
    // A selected passage is the complete source for body edits, so avoid sending the full article twice.
    const useSelectedText = Boolean(selectedText) && bodyOperations.includes(dto.operation);
    const result = await this.complete({
      userId,
      operation: `article_assistant_${dto.operation}`,
      messages: [
        {
          role: "system",
          content: locale === "en-US"
            ? "You are a careful article writing assistant. Preserve facts from the supplied material, do not invent sources or claims, and return only the requested result without commentary or code fences."
            : "你是一个谨慎的文章创作助手。必须保留用户材料中的事实，不得编造来源或结论，只返回请求的结果，不要加解释，也不要使用代码围栏。",
        },
        {
          role: "user",
          content: this.buildArticleAssistantPrompt(dto.operation, { title, content: useSelectedText ? "" : content, selectedText: useSelectedText ? selectedText : "", category: dto.category?.trim() ?? "", tags: dto.tags?.trim() ?? "" }, locale),
        },
      ],
    });
    return {
      text: result.text.trim(),
      operation: dto.operation,
      provider: result.provider,
      model: result.model,
      durationMs: result.durationMs,
      usage: result.usage,
    };
  }

  listTools() {
    return AI_TOOL_DEFINITIONS.map((tool) => ({ ...tool }));
  }

  async listConversations(userId: number) {
    const items = await this.prisma.aiConversation.findMany({
      where: { userId },
      orderBy: [{ updatedAt: "desc" }, { id: "desc" }],
      take: 30,
      select: { id: true, title: true, createdAt: true, updatedAt: true, _count: { select: { messages: true } } },
    });
    return { items: items.map((item) => ({ ...item, createdAt: item.createdAt.toISOString(), updatedAt: item.updatedAt.toISOString() })) };
  }

  async getConversation(userId: number, id: number) {
    const conversation = await this.prisma.aiConversation.findFirst({
      where: { id, userId },
      include: { messages: { orderBy: [{ createdAt: "asc" }, { id: "asc" }], take: 80 } },
    });
    if (!conversation) throw new BadRequestException("AI 对话不存在或不属于当前账号。\nThe AI conversation does not exist or does not belong to this account.");
    const feedback = await this.prisma.aiQualityFeedback.findMany({ where: { userId, conversationId: conversation.id, messageId: { not: null } }, select: { messageId: true, rating: true } });
    const ratings = new Map(feedback.filter((item): item is { messageId: number; rating: number } => item.messageId !== null).map((item) => [item.messageId, item.rating as -1 | 1]));
    return {
      id: conversation.id,
      title: conversation.title,
      createdAt: conversation.createdAt.toISOString(),
      updatedAt: conversation.updatedAt.toISOString(),
      messages: conversation.messages.map((message) => ({ id: message.id, role: message.role, content: message.content, sources: message.sources, qualityRating: ratings.get(message.id) ?? null, createdAt: message.createdAt.toISOString() })),
    };
  }

  async chat(user: AuthenticatedUser, dto: AiChatDto) {
    const message = dto.message.trim();
    if (!message) throw new BadRequestException("问题不能为空。\nThe question cannot be empty.");
    const conversation = dto.conversationId
      ? await this.prisma.aiConversation.findFirst({ where: { id: dto.conversationId, userId: user.id } })
      : await this.prisma.aiConversation.create({ data: { userId: user.id, title: message.slice(0, 60) } });
    if (!conversation) throw new BadRequestException("AI 对话不存在或不属于当前账号。\nThe AI conversation does not exist or does not belong to this account.");

    const config = await this.getConfiguration();
    if (!config.enabled) throw new BadRequestException("AI 功能尚未启用。\nAI features are not enabled.");
    await this.refreshConversationSummaryIfNeeded(conversation.id, user.id, config);
    const contextSince = this.contextSince(config.contextRetentionDays);
    const conversationState = await this.prisma.aiConversation.findUnique({ where: { id: conversation.id }, select: { contextSummary: true, summaryUpdatedAt: true } });
    const history = await this.prisma.aiConversationMessage.findMany({
      where: { conversationId: conversation.id, createdAt: { gte: contextSince } },
      orderBy: [{ createdAt: "desc" }, { id: "desc" }],
      take: Math.max(4, Math.min(80, config.contextMaxMessages)),
      select: { role: true, content: true, sources: true },
    });
    const locale = dto.locale === "en-US" ? "en-US" : "zh-CN";
    const siteQuestion = this.isSiteRelatedQuestion(message, dto) || history.some((item) => item.role === "user" && this.isSiteRelatedQuestion(item.content));
    const refusal = locale === "en-US"
      ? "I am the site's assistant and can help with content and data that this account is allowed to access. I do not answer unrelated general questions here."
      : "我是本站助手，只处理当前账号有权限访问的站内内容和数据。与本站无关的通用问题不在当前助手范围内。";
    if (!siteQuestion) {
      const turn = await this.persistChatTurn(conversation.id, message, refusal, []);
      return { conversationId: conversation.id, messageId: turn.assistantMessageId, title: conversation.title, text: refusal, sources: [], provider: config.provider as AiProvider, model: config.model ?? "", durationMs: 0, usage: { promptTokens: null, cachedPromptTokens: null, completionTokens: null, totalTokens: null } };
    }
    const context = await this.buildChatContext(user, dto, history);
    const contextQuality = this.evaluateContextQuality(context.text, context.sources.length);
    const system = locale === "en-US"
      ? "You are the site's permission-aware assistant. The supplied context is the complete set of data this account may use for this request. Answer site questions flexibly and directly from it, including follow-up questions. Never invent or infer hidden content, credentials, tokens, or administrator-only data. If the permitted context has no matching data, say so clearly. Do not answer unrelated general questions. Cite numbered sources when you use them."
      : "你是本站的权限感知助手。提供的上下文是当前账号本次请求可以使用的完整数据。请根据上下文灵活、直接地回答站内问题，包括连续追问；不得编造或推断隐藏内容、凭据、令牌或仅管理员可见的数据。如果权限范围内没有匹配数据，要明确说明。不要回答与本站无关的通用问题。使用来源时请标注对应的来源编号。";
    const contextMessage = locale === "en-US" ? `Readable site context:\n${context.text}` : `当前账号可读取的站内上下文：\n${context.text}`;
    const result = await this.complete({
      userId: user.id,
      operation: "p24_chat",
      capability: "chat",
      sourceCount: context.sources.length,
      qualityScore: config.qualityEvaluationEnabled ? contextQuality.score : null,
      qualityFlags: config.qualityEvaluationEnabled ? contextQuality.flags : [],
      messages: [
        { role: "system", content: system },
        { role: "system", content: contextMessage },
        ...(conversationState?.contextSummary && conversationState.summaryUpdatedAt && conversationState.summaryUpdatedAt >= contextSince ? [{ role: "system" as const, content: locale === "en-US" ? `Conversation summary:\n${conversationState.contextSummary}` : `此前对话摘要：\n${conversationState.contextSummary}` }] : []),
        ...this.compactHistory(history, config.contextMaxMessages),
        { role: "user", content: message },
      ],
    });
    const turn = await this.persistChatTurn(conversation.id, message, result.text.trim(), context.sources);
    return {
      conversationId: conversation.id,
      messageId: turn.assistantMessageId,
      title: conversation.title,
      text: result.text.trim(),
      sources: context.sources,
      provider: result.provider,
      model: result.model,
      durationMs: result.durationMs,
      usage: result.usage,
    };
  }

  async recordQualityFeedback(userId: number, dto: AiQualityFeedbackDto) {
    const message = await this.prisma.aiConversationMessage.findFirst({
      where: {
        id: dto.messageId,
        conversationId: dto.conversationId,
        role: "assistant",
        conversation: { userId },
      },
      select: { id: true, conversationId: true },
    });
    if (!message) throw new BadRequestException("只能评价当前账号自己的 AI 回复。\nYou can only rate your own AI replies.");

    const existing = await this.prisma.aiQualityFeedback.findFirst({ where: { userId, messageId: message.id } });
    const saved = existing
      ? await this.prisma.aiQualityFeedback.update({ where: { id: existing.id }, data: { conversationId: message.conversationId, rating: dto.rating, note: dto.note?.trim() || null } })
      : await this.prisma.aiQualityFeedback.create({ data: { userId, conversationId: message.conversationId, messageId: message.id, rating: dto.rating, note: dto.note?.trim() || null } });
    return { id: saved.id, conversationId: saved.conversationId, messageId: saved.messageId, rating: saved.rating as -1 | 1 };
  }

  async executeTool(user: AuthenticatedUser, name: string, dto: AiToolInputDto) {
    const definition = AI_TOOL_DEFINITIONS.find((tool) => tool.name === name);
    if (!definition) throw new BadRequestException("不支持的 AI 工具。\nThis AI tool is not supported.");
    const toolName = definition.name as AiToolName;
    const input = dto.input ?? {};
    const conversationId = await this.ownedConversationId(user.id, dto.conversationId);
    if (toolName === "create_article_draft") return this.prepareDraftConfirmation(user, input, conversationId);
    const output = await this.executeReadOnlyTool(user, toolName, input);
    const invocation = await this.prisma.aiToolInvocation.create({
      data: { userId: user.id, conversationId, toolName, input: input as Prisma.InputJsonValue, output: output as Prisma.InputJsonValue, status: "success", completedAt: new Date() },
      select: { id: true, createdAt: true },
    });
    return { invocationId: invocation.id, tool: definition, status: "success", output, createdAt: invocation.createdAt.toISOString() };
  }

  async confirmTool(user: AuthenticatedUser, invocationId: number, confirmationToken: string, selectedSuggestedTags: string[] = []) {
    const invocation = await this.prisma.aiToolInvocation.findFirst({ where: { id: invocationId, userId: user.id, toolName: "create_article_draft", status: "awaiting_confirmation" } });
    if (!invocation || !invocation.confirmationTokenHash || !invocation.confirmationExpiresAt || invocation.confirmationExpiresAt.getTime() < Date.now() || !this.sameSecret(confirmationToken, invocation.confirmationTokenHash)) {
      throw new BadRequestException("确认令牌无效或已过期，请重新准备草稿。\nThe confirmation token is invalid or expired; prepare the draft again.");
    }
    const input = this.readObject(invocation.input);
    const title = this.readString(input.title, 120);
    const content = this.readString(input.content, 60000);
    if (!title || !content) throw new BadRequestException("草稿标题和正文不能为空。\nA draft title and body are required.");
    const candidateSuggestedTags = this.readStringArray(input.suggestedTags, 6, 80);
    const allowedSuggestedTags = new Set(candidateSuggestedTags);
    const confirmedSuggestedTags = [...new Set(selectedSuggestedTags.map((tag) => tag.trim()).filter((tag) => allowedSuggestedTags.has(tag)))].slice(0, 6);
    const configuredTags = this.readString(input.tags, 500);
    const finalTags = [...new Set([...configuredTags.split(","), ...confirmedSuggestedTags].map((tag) => tag.trim()).filter(Boolean))].slice(0, 12).join(",");
    try {
      const article = this.articles ? await this.articles.create(user, {
        title,
        content,
        summary: this.readString(input.summary, 300),
        category: this.readString(input.category, 80),
        tags: finalTags,
        contentFormat: this.readString(input.contentFormat, 10) === "html" ? "html" : "markdown",
        status: "draft",
      }) : null;
      if (!article) throw new ServiceUnavailableException("文章服务暂不可用。\nThe article service is unavailable.");
      await this.prisma.aiToolInvocation.update({ where: { id: invocation.id }, data: { status: "success", confirmedAt: new Date(), completedAt: new Date(), confirmationTokenHash: null, output: { articleId: article.id, slug: article.slug, title: article.title } } });
      return { success: true, invocationId: invocation.id, article: { id: article.id, slug: article.slug, title: article.title } };
    } catch (error) {
      await this.prisma.aiToolInvocation.update({ where: { id: invocation.id }, data: { status: "failed", completedAt: new Date(), confirmationTokenHash: null } }).catch(() => undefined);
      throw error;
    }
  }

  async getAdminToolInvocations(limit = 50) {
    const items = await this.prisma.aiToolInvocation.findMany({
      take: Math.max(1, Math.min(100, Math.floor(limit))),
      orderBy: [{ createdAt: "desc" }, { id: "desc" }],
      include: { user: { select: { username: true, nickname: true } } },
    });
    return { items: items.map((item) => ({ id: item.id, toolName: item.toolName, status: item.status, requiresConfirmation: item.requiresConfirmation, username: item.user.username, nickname: item.user.nickname, createdAt: item.createdAt.toISOString(), completedAt: item.completedAt?.toISOString() ?? null })) };
  }

  private async buildChatContext(user: AuthenticatedUser, dto: AiChatDto, history: Array<{ sources: Prisma.JsonValue | null }>): Promise<{ text: string; sources: AiSource[] }> {
    const contexts: Array<{ article: Awaited<ReturnType<ArticlesService["getAiReadableContext"]>>; source: AiSource }> = [];
    const accountText = await this.buildAccountContext(user, dto.message);
    const configuration = await this.getConfiguration();
    const ragContext = !dto.articleId && !dto.articleSlug && !dto.topicSlug && !dto.collectionId && configuration.ragEnabled && this.knowledge
      ? await this.knowledge.search(user, dto.message, configuration.ragTopK).catch(() => ({ text: "", sources: [], mode: "none" as const }))
      : { text: "", sources: [], mode: "none" as const };
    if (this.articles && (dto.articleId || dto.articleSlug)) {
      const article = await this.articles.getAiReadableContext(user, { id: dto.articleId, slug: dto.articleSlug });
      contexts.push({ article, source: { type: "article", id: article.id, slug: article.slug, title: article.title, author: article.author.nickname || article.author.username } });
    } else if (this.articles && dto.topicSlug) {
      contexts.push(...await this.groupArticleContexts(user, "topic", dto.topicSlug));
    } else if (this.articles && dto.collectionId) {
      contexts.push(...await this.groupArticleContexts(user, "collection", dto.collectionId));
    } else if (this.articles) {
      const priorSources = this.readHistorySources(history);
      const priorContexts: typeof contexts = [];
      for (const source of priorSources.slice(0, 5)) {
        try {
          const article = await this.articles.getAiReadableContext(user, { id: source.id });
          priorContexts.push({ article, source: { type: "article", id: article.id, slug: article.slug, title: article.title, author: article.author.nickname || article.author.username } });
        } catch {
          // The article may no longer be visible; do not reuse stale history as permission.
        }
      }
      const directMatches = await this.articles.searchAiReadableArticles(user, dto.message, 5);
      const broadRequest = /文章|专题|合集|推荐|最新|新增|可见|查看|阅读|内容|哪些|站内|article|topic|collection|recommend|latest|new|visible|readable|content/i.test(dto.message);
      const latestMatches = broadRequest || (directMatches.length === 0 && priorContexts.length === 0)
        ? await this.articles.searchAiReadableArticles(user, "", 8)
        : [];
      const recommendedMatches = broadRequest && typeof this.articles.listAiRecommendedArticles === "function"
        ? await this.articles.listAiRecommendedArticles(user, 6)
        : [];
      const seen = new Set<number>();
      const matches = [...priorContexts.map(({ article }) => article), ...directMatches, ...latestMatches, ...recommendedMatches].filter((match) => {
        if (seen.has(match.id)) return false;
        seen.add(match.id);
        return true;
      }).slice(0, broadRequest ? 10 : 5);
      for (const match of matches) {
        try {
          const article = await this.articles.getAiReadableContext(user, { id: match.id });
          contexts.push({ article, source: { type: "article", id: article.id, slug: article.slug, title: article.title, author: article.author.nickname || article.author.username } });
        } catch {
          // Search and recommendation results still pass through the article read guard.
        }
      }
    }
    if (!contexts.length) {
      if (ragContext.text && ragContext.sources.length) {
        return { text: [accountText, ragContext.text].filter(Boolean).join("\n\n"), sources: ragContext.sources };
      }
      return {
        text: accountText || "当前权限范围内没有可用的已发布站内文章。\nNo readable published site articles are available within the current permission scope.",
        sources: [],
      };
    }
    let used = 0;
    const parts = contexts.map(({ article }, index) => {
      const remaining = Math.max(0, 22000 - used);
      const content = article.content.slice(0, remaining);
      used += content.length;
      return `[${index + 1}] ${article.title}\n作者：${article.author.nickname || article.author.username}\n摘要：${article.summary || "无"}\n正文：\n${content}`;
    });
    const sources = [...ragContext.sources, ...contexts.map(({ source }) => source)].filter((source, index, items) => items.findIndex((item) => item.id === source.id) === index);
    return { text: [accountText, ragContext.text, parts.join("\n\n")].filter(Boolean).join("\n\n"), sources };
  }

  private async buildAccountContext(user: AuthenticatedUser, message: string): Promise<string> {
    const requests: Array<{ label: string; task: Promise<unknown> }> = [];
    if (/(我的|账号|账户|积分|成长|经验|等级|points|profile|account|level)/i.test(message)) {
      requests.push({ label: "当前账号概况", task: this.executeReadOnlyTool(user, "get_my_summary", {}) });
    }
    if (/(我的)?(待办|任务|通知|未读|提醒|task|notification|todo)/i.test(message)) {
      requests.push({ label: "当前账号待办与通知", task: this.executeReadOnlyTool(user, "list_my_tasks", {}) });
    }
    if (/(我的)?(订阅|关注|作者|专题|合集|标签订阅|subscription|follow)/i.test(message)) {
      requests.push({ label: "当前账号订阅", task: this.executeReadOnlyTool(user, "list_my_subscriptions", {}) });
    }
    if (/(我的)?(收益|收入|兑换|结算|退款|earning|income|settlement)/i.test(message)) {
      requests.push({ label: "当前账号收益", task: this.executeReadOnlyTool(user, "list_my_earnings", {}) });
    }
    if (/(我的)?(文章|草稿|发布状态|审核状态|my articles|draft|published|review)/i.test(message)) {
      requests.push({ label: "当前账号文章状态", task: this.executeReadOnlyTool(user, "list_my_articles", {}) });
    }
    if (/(后台|管理员|运营概况|审计|admin|overview|audit)/i.test(message)) {
      requests.push({ label: "权限范围内的后台概况", task: this.executeReadOnlyTool(user, "get_admin_overview", {}) });
    }
    if (!requests.length) return "";
    const sections: string[] = [];
    for (const request of requests) {
      try {
        const output = await request.task;
        sections.push(`${request.label}:\n${JSON.stringify(output, null, 2)}`);
      } catch {
        // A tool may be unavailable or forbidden; omitting it keeps the model from seeing unauthorized data.
      }
    }
    return sections.join("\n\n");
  }

  private async persistChatTurn(conversationId: number, message: string, response: string, sources: AiSource[]) {
    const [userMessage, assistantMessage] = await this.prisma.$transaction([
      this.prisma.aiConversationMessage.create({ data: { conversationId, role: "user", content: message } }),
      this.prisma.aiConversationMessage.create({ data: { conversationId, role: "assistant", content: response, sources: sources as unknown as Prisma.InputJsonValue } }),
      this.prisma.aiConversation.update({ where: { id: conversationId }, data: { updatedAt: new Date() } }),
    ]);
    return { userMessageId: userMessage.id, assistantMessageId: assistantMessage.id };
  }

  private async refreshConversationSummaryIfNeeded(conversationId: number, userId: number, config: AiConfiguration) {
    const conversation = await this.prisma.aiConversation.findUnique({ where: { id: conversationId }, select: { contextSummary: true, summaryMessageCount: true, summaryUpdatedAt: true } });
    if (!conversation) return;
    const contextSince = this.contextSince(config.contextRetentionDays);
    if (conversation.contextSummary && (!conversation.summaryUpdatedAt || conversation.summaryUpdatedAt < contextSince)) {
      await this.prisma.aiConversation.update({ where: { id: conversationId }, data: { contextSummary: null, summaryMessageCount: 0, summaryUpdatedAt: null } });
      conversation.contextSummary = null;
      conversation.summaryMessageCount = 0;
    }
    const messageCount = await this.prisma.aiConversationMessage.count({ where: { conversationId, createdAt: { gte: contextSince } } });
    if (messageCount < config.contextSummaryThreshold || messageCount <= conversation.summaryMessageCount + 8) return;
    const messages = await this.prisma.aiConversationMessage.findMany({ where: { conversationId, createdAt: { gte: contextSince } }, orderBy: [{ createdAt: "asc" }, { id: "asc" }], take: Math.min(80, messageCount), select: { role: true, content: true } });
    const material = messages.map((item) => `${item.role === "assistant" ? "AI" : "用户"}: ${item.content.slice(0, 3000)}`).join("\n\n");
    try {
      const result = await this.complete({
        userId,
        operation: "context_compaction",
        capability: "chat",
        messages: [
          { role: "system", content: "请把以下站内 AI 对话压缩成一份准确、简洁的上下文摘要。保留用户目标、已确认事实、已引用文章和未完成事项；不要添加原文没有的信息。只返回摘要正文。" },
          { role: "user", content: `${conversation.contextSummary ? `已有摘要：\n${conversation.contextSummary}\n\n` : ""}对话记录：\n${material}` },
        ],
      });
      await this.prisma.aiConversation.update({ where: { id: conversationId }, data: { contextSummary: result.text.trim().slice(0, 12000), summaryMessageCount: messageCount, summaryUpdatedAt: new Date() } });
    } catch {
      // Context compression is an enhancement; a failed summary must not block a normal chat request.
    }
  }

  private evaluateContextQuality(context: string, sourceCount: number): { score: number; flags: string[] } {
    const flags: string[] = [];
    if (sourceCount === 0) flags.push("no_sources");
    if (context.length > 18000) flags.push("large_context");
    if (context.includes("没有可用的已发布站内文章") || context.includes("No readable published site articles")) flags.push("empty_scope");
    const score = Math.max(0, Math.min(100, 70 + Math.min(25, sourceCount * 5) - flags.filter((flag) => flag !== "no_sources").length * 10 - (sourceCount === 0 ? 20 : 0)));
    return { score, flags };
  }

  private isSiteRelatedQuestion(message: string, dto?: Pick<AiChatDto, "articleId" | "articleSlug" | "topicSlug" | "collectionId">): boolean {
    if (dto && (dto.articleId || dto.articleSlug || dto.topicSlug || dto.collectionId)) return true;
    return /(本站|站内|文章|专题|合集|评论|回复|作者|用户|订阅|收藏|待办|任务|通知|聊天|群聊|积分|收益|资源|反馈|举报|阅读文章|推荐文章|搜索文章|登录|密码|邮箱|通行密钥|双因素|账号|账户|草稿|发布|发现|数据与隐私|我的数据|个人数据|能做什么|你是谁|ai\s*助手|site|site content|article|topic|collection|comment|reply|author|subscription|bookmark|task|notification|group chat|points|earnings|resource|feedback|report|read article|recommended articles|search articles|login|password|email|passkey|totp|account|draft|publish|discover|privacy|my data|what can you do|who are you)/i.test(message);
  }

  private compactHistory(history: Array<{ role: string; content: string }>, maxMessages = 24): AiChatMessage[] {
    const maxCharacters = 12_000;
    let remaining = maxCharacters;
    const selected: AiChatMessage[] = [];
    for (const item of history.slice(0, Math.max(4, maxMessages)).reverse()) {
      if (remaining <= 0) break;
      const content = item.content.trim();
      if (!content) continue;
      const clipped = content.length > remaining ? `${content.slice(0, Math.max(0, remaining - 12))}\n[已截断]` : content;
      selected.unshift({ role: item.role === "assistant" ? "assistant" : "user", content: clipped });
      remaining -= clipped.length;
    }
    return selected;
  }

  private contextSince(retentionDays: number | null | undefined): Date {
    const days = Math.max(1, Math.min(3650, Math.floor(retentionDays || 365)));
    return new Date(Date.now() - days * 24 * 60 * 60 * 1000);
  }

  private readHistorySources(history: Array<{ sources: Prisma.JsonValue | null }>): AiSource[] {
    const sources: AiSource[] = [];
    const seen = new Set<number>();
    for (const item of history) {
      if (!Array.isArray(item.sources)) continue;
      for (const value of item.sources) {
        if (!value || typeof value !== "object" || Array.isArray(value)) continue;
        const source = value as Record<string, unknown>;
        const id = typeof source.id === "number" ? source.id : Number(source.id);
        if (!Number.isInteger(id) || id <= 0 || seen.has(id)) continue;
        if (typeof source.slug !== "string" || typeof source.title !== "string" || typeof source.author !== "string") continue;
        seen.add(id);
        sources.push({ type: "article", id, slug: source.slug, title: source.title, author: source.author });
      }
    }
    return sources;
  }

  private async executeReadOnlyTool(user: AuthenticatedUser, name: Exclude<AiToolName, "create_article_draft">, input: Record<string, unknown>) {
    if (name === "get_my_summary") {
      const [account, ledger] = await Promise.all([
        this.prisma.user.findUnique({ where: { id: user.id }, select: { username: true, nickname: true, experience: true, points: true, status: true, role: { select: { code: true, name: true, level: true } } } }),
        this.prisma.userReputationLedger.findMany({ where: { userId: user.id }, orderBy: [{ createdAt: "desc" }, { id: "desc" }], take: 10, select: { description: true, experienceDelta: true, pointDelta: true, pendingPointDelta: true, createdAt: true } }),
      ]);
      return { account, recentLedger: ledger.map((item) => ({ ...item, createdAt: item.createdAt.toISOString() })) };
    }
    if (name === "list_my_tasks") {
      const items = await this.prisma.userNotification.findMany({ where: { userId: user.id, readAt: null }, orderBy: [{ createdAt: "desc" }, { id: "desc" }], take: 50, select: { id: true, type: true, channel: true, title: true, body: true, bodyEn: true, actionUrl: true, createdAt: true } });
      return { count: items.length, items: items.map((item) => ({ ...item, createdAt: item.createdAt.toISOString() })) };
    }
    if (name === "list_my_subscriptions") {
      const [authors, topics, collections, tags] = await Promise.all([
        this.prisma.userSubscription.findMany({ where: { subscriberId: user.id }, orderBy: { createdAt: "desc" }, take: 50, select: { frequency: true, notifyNewArticles: true, author: { select: { username: true, nickname: true } } } }),
        this.prisma.articleTopicSubscription.findMany({ where: { userId: user.id }, orderBy: { createdAt: "desc" }, take: 50, select: { frequency: true, topic: { select: { title: true, slug: true } } } }),
        this.prisma.articleCollectionSubscription.findMany({ where: { userId: user.id }, orderBy: { createdAt: "desc" }, take: 50, select: { collection: { select: { name: true, id: true } } } }),
        this.prisma.articleTagSubscription.findMany({ where: { userId: user.id }, orderBy: { createdAt: "desc" }, take: 50, select: { tag: true, frequency: true } }),
      ]);
      return { authors, topics, collections, tags };
    }
    if (name === "list_my_earnings") {
      const items = await this.prisma.articleResourceExchange.findMany({ where: { authorId: user.id }, orderBy: [{ createdAt: "desc" }, { id: "desc" }], take: 100, select: { pointCost: true, deliveryStatus: true, sellerSettledAt: true, refundedAt: true, createdAt: true, article: { select: { title: true, slug: true } } } });
      return { summary: items.reduce((summary, item) => { summary.gross += item.pointCost; if (item.refundedAt) summary.refunded += item.pointCost; else if (item.sellerSettledAt) summary.settled += item.pointCost; else summary.pending += item.pointCost; return summary; }, { gross: 0, pending: 0, settled: 0, refunded: 0 }), items: items.map((item) => ({ ...item, createdAt: item.createdAt.toISOString(), sellerSettledAt: item.sellerSettledAt?.toISOString() ?? null, refundedAt: item.refundedAt?.toISOString() ?? null })) };
    }
    if (name === "list_my_articles") {
      const articles = await this.prisma.article.findMany({ where: { authorId: user.id, status: { not: "deleted" } }, orderBy: [{ updatedAt: "desc" }, { id: "desc" }], take: 100, select: { id: true, title: true, slug: true, status: true, visibility: true, publishedAt: true, updatedAt: true, viewCount: true, commentCount: true } });
      return { items: articles.map((item) => ({ ...item, publishedAt: item.publishedAt?.toISOString() ?? null, updatedAt: item.updatedAt.toISOString() })) };
    }
    if (name === "search_visible_articles") {
      if (!this.articles) throw new ServiceUnavailableException("文章服务暂不可用。\nThe article service is unavailable.");
      return { query: this.readString(input.query, 100), items: await this.articles.searchAiReadableArticles(user, this.readString(input.query, 100), this.readNumber(input.limit, 8)) };
    }
    if (name === "list_visible_articles") {
      if (!this.articles) throw new ServiceUnavailableException("文章服务暂不可用。\nThe article service is unavailable.");
      return { items: await this.articles.searchAiReadableArticles(user, "", this.readNumber(input.limit, 12)) };
    }
    if (name === "list_recommended_articles") {
      if (!this.articles) throw new ServiceUnavailableException("文章服务暂不可用。\nThe article service is unavailable.");
      return { items: await this.articles.listAiRecommendedArticles(user, this.readNumber(input.limit, 8)) };
    }
    if (name === "summarize_topic" || name === "summarize_collection") {
      if (!this.articles) throw new ServiceUnavailableException("文章服务暂不可用。\nThe article service is unavailable.");
      const key = name === "summarize_topic" ? this.readString(input.topicSlug, 120) : this.readNumber(input.collectionId, 0);
      const contexts = await this.groupArticleContexts(user, name === "summarize_topic" ? "topic" : "collection", key);
      return { group: name === "summarize_topic" ? { type: "topic", slug: key } : { type: "collection", id: key }, articles: contexts.map(({ article }) => article) };
    }
    if (name === "get_admin_overview") {
      if (!user.isSuperAdmin && !user.isAdministrator) throw new ForbiddenException("只有管理员可以查看后台概况。\nOnly administrators can view the admin overview.");
      const startOfDay = new Date();
      startOfDay.setHours(0, 0, 0, 0);
      const [activeUsers, publishedArticles, openAlerts, todayAuditLogs] = await Promise.all([
        this.prisma.user.count({ where: { status: "active" } }),
        this.prisma.article.count({ where: { status: "published" } }),
        this.prisma.operationalAlert.count({ where: { status: "open" } }),
        this.prisma.auditLog.count({ where: { createdAt: { gte: startOfDay } } }),
      ]);
      return { activeUsers, publishedArticles, openAlerts, todayAuditLogs, generatedAt: new Date().toISOString() };
    }
    if (!this.articles) throw new ServiceUnavailableException("文章服务暂不可用。\nThe article service is unavailable.");
    const articleId = typeof input.articleId === "number" && input.articleId > 0 ? Math.floor(input.articleId) : undefined;
    const article = await this.articles.getAiReadableContext(user, { id: articleId, slug: this.readString(input.articleSlug, 180) || undefined });
    return { article, source: { type: "article", id: article.id, slug: article.slug, title: article.title, author: article.author.nickname || article.author.username } };
  }

  private async prepareDraftConfirmation(user: AuthenticatedUser, input: Record<string, unknown>, conversationId: number | null) {
    const description = this.readString(input.description, 4000);
    if (!description) throw new BadRequestException("请先填写文章描述。\nPlease provide an article description first.");
    const locale = this.readString(input.locale, 10) === "en-US" ? "en-US" : "zh-CN";
    const taxonomies = await this.prisma.articleTaxonomy.findMany({
      where: { enabled: true },
      orderBy: [{ kind: "asc" }, { sortOrder: "asc" }, { id: "asc" }],
      select: { kind: true, name: true },
    });
    const categories = taxonomies.filter((item) => item.kind === "category").map((item) => item.name);
    const tags = taxonomies.filter((item) => item.kind === "tag").map((item) => item.name);
    const taxonomyInstruction = locale === "en-US"
      ? `Use an exact category from this configured list, or an empty string if the list is empty: ${categories.join(", ") || "(none)"}. Prefer exact tags from this configured list: ${tags.join(", ") || "(none)"}. You may suggest other short tags, but they must be returned in the tags field and will require explicit confirmation before they are saved.`
      : `分类必须从以下已配置分类中原样选择；如果列表为空则返回空字符串：${categories.join("、") || "（暂无）"}。标签优先从以下已配置标签中原样选择：${tags.join("、") || "（暂无）"}。可以建议少量其它简短标签，但它们必须放在 tags 字段中，保存前会要求用户明确确认。`;
    const result = await this.complete({
      userId: user.id,
      operation: "create_article_draft_preview",
      messages: [
        {
          role: "system",
          content: locale === "en-US"
            ? `You are the site's article writing assistant. Based on the user's description, create a useful draft. Return only one valid JSON object with exactly these string fields: title, summary, category, tags, content. Use Markdown in content, put tags in one comma-separated string, and do not wrap the JSON in a markdown code fence. Do not add explanations outside the JSON. ${taxonomyInstruction}`
            : `你是站内文章写作助手。请根据用户的文章描述生成可用草稿。只能返回一个合法 JSON 对象，且必须包含这 5 个字符串字段：title、summary、category、tags、content。content 使用 Markdown，tags 使用逗号分隔的字符串，不要使用 Markdown 代码围栏包裹 JSON，也不要在 JSON 外补充解释。${taxonomyInstruction}`,
        },
        { role: "user", content: locale === "en-US" ? `Article description:\n${description}` : `文章描述：\n${description}` },
      ],
    });
    const generated = this.parseDraftPreview(result.text, locale);
    const configuredCategory = categories.find((category) => category === generated.category) ?? categories[0] ?? "";
    const configuredTagSet = new Set(tags);
    const generatedTags = generated.tags.split(",").map((tag) => tag.trim()).filter(Boolean);
    const configuredTags = [...new Set(generatedTags.filter((tag) => configuredTagSet.has(tag)))].slice(0, 6);
    const suggestedTags = [...new Set(generatedTags.filter((tag) => !configuredTagSet.has(tag)))].slice(0, 6);
    const preview = { ...generated, category: configuredCategory, tags: configuredTags.join(", "), suggestedTags };
    const generatedInput = { description, title: preview.title, summary: preview.summary, category: preview.category, tags: preview.tags, suggestedTags, content: preview.content, contentFormat: "markdown" };
    const token = randomBytes(32).toString("base64url");
    const expiresAt = new Date(Date.now() + 10 * 60 * 1000);
    const invocation = await this.prisma.aiToolInvocation.create({ data: { userId: user.id, conversationId, toolName: "create_article_draft", input: generatedInput as Prisma.InputJsonValue, status: "awaiting_confirmation", requiresConfirmation: true, confirmationTokenHash: createHash("sha256").update(token).digest("hex"), confirmationExpiresAt: expiresAt } });
    return { invocationId: invocation.id, status: "awaiting_confirmation", requiresConfirmation: true, confirmationToken: token, expiresAt: expiresAt.toISOString(), preview };
  }

  private parseDraftPreview(text: string, locale: "zh-CN" | "en-US") {
    const fenced = text.match(/```(?:json)?\s*([\s\S]*?)\s*```/i)?.[1];
    const source = fenced || text;
    const start = source.indexOf("{");
    const end = source.lastIndexOf("}");
    let parsed: Record<string, unknown> = {};
    if (start >= 0 && end > start) {
      try {
        const value: unknown = JSON.parse(source.slice(start, end + 1));
        if (value && typeof value === "object" && !Array.isArray(value)) parsed = value as Record<string, unknown>;
      } catch {
        // Fall back to a readable body when a provider ignores the JSON-only instruction.
      }
    }
    const content = this.readString(parsed.content, 60000) || text.trim().slice(0, 60000);
    const firstLine = content.split(/\r?\n/).map((line) => line.replace(/^\s*#+\s*/, "").trim()).find(Boolean) || "";
    return {
      title: this.readString(parsed.title, 120) || firstLine.slice(0, 120) || (locale === "en-US" ? "AI generated draft" : "AI 生成文章"),
      summary: this.readString(parsed.summary, 300) || content.replace(/[#*_`>-]/g, "").replace(/\s+/g, " ").slice(0, 300),
      category: this.readString(parsed.category, 80),
      tags: Array.isArray(parsed.tags) ? parsed.tags.filter((tag): tag is string => typeof tag === "string").map((tag) => tag.trim()).filter(Boolean).slice(0, 6).join(", ") : this.readString(parsed.tags, 500),
      content,
    };
  }

  private async groupArticleContexts(user: AuthenticatedUser, kind: "topic" | "collection", key: string | number) {
    if (!this.articles) throw new ServiceUnavailableException("文章服务暂不可用。\nThe article service is unavailable.");
    let articleIds: number[];
    if (kind === "topic") {
      const topic = await this.prisma.articleTopic.findUnique({ where: { slug: String(key) }, select: { id: true, title: true, slug: true, description: true, status: true, visibility: true, allowedRoles: { select: { role: { select: { code: true } } } } } });
      if (!topic || topic.status !== "active") throw new BadRequestException("专题不存在或暂不可见。\nThe topic does not exist or is not visible.");
      this.assertGroupVisible(user, topic.visibility, undefined, topic.allowedRoles.map(({ role }) => role.code));
      articleIds = (await this.prisma.articleTopicItem.findMany({ where: { topicId: topic.id }, orderBy: [{ sortOrder: "asc" }, { createdAt: "asc" }], take: 30, select: { articleId: true } })).map(({ articleId }) => articleId);
    } else {
      const collection = await this.prisma.articleCollection.findUnique({ where: { id: Number(key) }, select: { id: true, name: true, description: true, ownerId: true, visibility: true } });
      if (!collection) throw new BadRequestException("合集不存在或暂不可见。\nThe collection does not exist or is not visible.");
      this.assertGroupVisible(user, collection.visibility, collection.ownerId, []);
      articleIds = (await this.prisma.articleCollectionItem.findMany({ where: { collectionId: collection.id }, orderBy: [{ sortOrder: "asc" }, { createdAt: "asc" }], take: 30, select: { articleId: true } })).map(({ articleId }) => articleId);
    }
    const contexts: Array<{ article: Awaited<ReturnType<ArticlesService["getAiReadableContext"]>>; source: AiSource }> = [];
    for (const articleId of articleIds) {
      try {
        const article = await this.articles.getAiReadableContext(user, { id: articleId });
        contexts.push({ article, source: { type: "article", id: article.id, slug: article.slug, title: article.title, author: article.author.nickname || article.author.username } });
      } catch {
        // Group membership does not override the article's own visibility rule.
      }
    }
    return contexts;
  }

  private assertGroupVisible(user: AuthenticatedUser, visibility: string, ownerId: number | undefined, roleCodes: string[]): void {
    if (visibility === "public") return;
    if (visibility === "authenticated") return;
    if (ownerId === user.id || user.isSuperAdmin) return;
    if (visibility === "role_restricted" && roleCodes.includes(user.role.code)) return;
    throw new ForbiddenException("当前账号没有查看该专题或合集的权限。\nThis account cannot view this topic or collection.");
  }

  private async ownedConversationId(userId: number, conversationId?: number): Promise<number | null> {
    if (!conversationId) return null;
    const conversation = await this.prisma.aiConversation.findFirst({ where: { id: conversationId, userId }, select: { id: true } });
    if (!conversation) throw new BadRequestException("AI 对话不存在或不属于当前账号。\nThe AI conversation does not exist or does not belong to this account.");
    return conversation.id;
  }

  private sameSecret(raw: string, hash: string): boolean {
    return createHash("sha256").update(raw).digest("hex") === hash;
  }

  private readObject(value: Prisma.JsonValue | null): Record<string, unknown> {
    return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
  }

  private readString(value: unknown, maxLength: number): string {
    return typeof value === "string" ? value.trim().slice(0, maxLength) : "";
  }

  private readStringArray(value: unknown, maxItems: number, maxLength: number): string[] {
    if (!Array.isArray(value)) return [];
    return [...new Set(value.filter((item): item is string => typeof item === "string").map((item) => item.trim().slice(0, maxLength)).filter(Boolean))].slice(0, maxItems);
  }

  private readNumber(value: unknown, fallback: number): number {
    const numeric = typeof value === "number" ? value : Number(value);
    if (!Number.isFinite(numeric) || numeric <= 0) return fallback;
    return Math.max(1, Math.min(12, Math.floor(numeric)));
  }

  async getAdminInvocationOverview(limit = 30) {
    const safeLimit = Math.max(1, Math.min(100, Math.floor(limit)));
    const startOfDay = new Date();
    startOfDay.setHours(0, 0, 0, 0);
    const [logs, today] = await Promise.all([
      this.prisma.aiInvocationLog.findMany({
        take: safeLimit,
        orderBy: [{ createdAt: "desc" }, { id: "desc" }],
        include: { user: { select: { username: true, nickname: true } } },
      }),
      this.prisma.aiInvocationLog.aggregate({
        where: { createdAt: { gte: startOfDay } },
        _count: { _all: true },
        _sum: { promptTokens: true, cachedPromptTokens: true, totalTokens: true, estimatedCostMicros: true },
      }),
    ]);
    return {
      today: {
        requests: today._count._all,
        totalTokens: today._sum.totalTokens ?? 0,
        cachedPromptTokens: today._sum.cachedPromptTokens ?? 0,
        cacheHitRate: this.cacheHitRate(today._sum.promptTokens, today._sum.cachedPromptTokens),
        estimatedCostMicros: today._sum.estimatedCostMicros ?? 0,
      },
      logs: logs.map((log) => ({
        id: log.id,
        operation: log.operation,
        provider: log.provider as AiProvider,
        model: log.model,
        status: log.status,
        promptTokens: log.promptTokens,
        cachedPromptTokens: log.cachedPromptTokens,
        completionTokens: log.completionTokens,
        totalTokens: log.totalTokens,
        durationMs: log.durationMs,
        estimatedCostMicros: log.estimatedCostMicros,
        errorSummary: log.errorSummary,
        username: log.user?.username ?? null,
        nickname: log.user?.nickname ?? null,
        createdAt: log.createdAt.toISOString(),
      })),
    };
  }

  getResourceRecommendation(): ResourceRecommendation {
    const cpuCores = Math.max(1, Math.min(16, typeof os.availableParallelism === "function" ? os.availableParallelism() : os.cpus().length));
    const totalMemoryMiB = Math.max(1, Math.round(os.totalmem() / 1024 / 1024));
    const freeMemoryMiB = Math.max(0, Math.round(os.freemem() / 1024 / 1024));
    const effectiveMemoryMiB = Math.min(totalMemoryMiB, freeMemoryMiB + 512);
    const memoryConcurrency = Math.max(1, Math.floor(Math.max(128, effectiveMemoryMiB - 512) / 128));
    const globalConcurrency = Math.max(1, Math.min(4, cpuCores, memoryConcurrency));
    const maxGlobalConcurrency = Math.max(1, Math.min(8, cpuCores * 2, totalMemoryMiB < 1024 ? 2 : 8));
    const maxUserConcurrency = Math.max(1, Math.min(4, maxGlobalConcurrency));
    return {
      cpuCores,
      totalMemoryMiB,
      freeMemoryMiB,
      globalConcurrency,
      userConcurrency: 1,
      maxGlobalConcurrency,
      maxUserConcurrency,
    };
  }

  private async execute(options: AiCompletionOptions) {
    const config = await this.getConfiguration();
    if (!config.enabled && !options.allowDisabled) {
      throw new BadRequestException("AI 功能尚未启用。\nAI features are not enabled.");
    }
    const apiKey = this.readApiKey(config);
    if (!config.baseUrl?.trim() || !config.model?.trim() || !apiKey) {
      throw new BadRequestException("AI 配置不完整，请先填写接口地址、模型和 API Key。\nComplete the AI base URL, model, and API key first.");
    }
    if (!options.messages.length || options.messages.some((message) => !message.content.trim())) {
      throw new BadRequestException("AI 请求内容不能为空。\nAI request content cannot be empty.");
    }
    if (options.messages.some((message) => message.content.length > 60_000) || JSON.stringify(options.messages).length > 120_000) {
      throw new BadRequestException("AI 请求内容过长，请分段处理。\nAI request content is too large; split it into smaller requests.");
    }

    const globalKey = "ai:concurrency:global";
    const userKey = `ai:concurrency:user:${options.userId}`;
    const quotaKey = `ai:quota:global:${this.utcDayKey()}`;
    const leaseSeconds = config.requestTimeoutSeconds + 30;
    let globalAcquired = false;
    let userAcquired = false;
    let activeConfig = config;
    let primaryFailureRecorded = false;
    const startedAt = Date.now();
    try {
      globalAcquired = await this.redis.tryAcquireCounter(globalKey, config.globalConcurrency, leaseSeconds);
      if (!globalAcquired) throw new AiGatewayError("concurrency", "当前 AI 请求较多，请稍后重试。\nAI concurrency is currently full.");
      userAcquired = await this.redis.tryAcquireCounter(userKey, config.userConcurrency, leaseSeconds);
      if (!userAcquired) throw new AiGatewayError("concurrency", "当前账号的 AI 请求较多，请稍后重试。\nYour AI concurrency limit has been reached.");
      if (config.dailyRequestLimit > 0) {
        const quotaReserved = await this.redis.tryAcquireCounter(quotaKey, config.dailyRequestLimit, this.secondsUntilUtcDayEnd());
        if (!quotaReserved) throw new AiGatewayError("quota", "今日 AI 请求次数已用完。\nThe daily AI request limit has been reached.");
      }

      let result: AiProviderCompletion;
      try {
        result = await completeWithProvider({
          provider: config.provider as AiProvider,
          baseUrl: config.baseUrl,
          apiKey,
          model: config.model,
          maxOutputTokens: config.maxOutputTokens,
          timeoutSeconds: config.requestTimeoutSeconds,
          messages: options.messages,
        });
      } catch (primaryError) {
        const fallback = this.getFallbackRuntime(config, primaryError);
        if (!fallback) throw primaryError;
        await this.recordInvocation(config, options, "failed", null, Date.now() - startedAt, `主模型调用失败，已切换备用模型：${this.errorMessage(primaryError)}`);
        primaryFailureRecorded = true;
        activeConfig = {
          ...config,
          provider: fallback.provider,
          baseUrl: fallback.baseUrl,
          model: fallback.model,
          inputCostPerMillionMicros: config.fallbackInputCostPerMillionMicros,
          cachedInputCostPerMillionMicros: config.fallbackCachedInputCostPerMillionMicros,
          outputCostPerMillionMicros: config.fallbackOutputCostPerMillionMicros,
        };
        result = await completeWithProvider({ ...fallback, maxOutputTokens: config.maxOutputTokens, timeoutSeconds: config.requestTimeoutSeconds, messages: options.messages });
      }
      await this.recordInvocation(activeConfig, options, "success", result, Date.now() - startedAt);
      return { ...result, provider: activeConfig.provider as AiProvider, model: activeConfig.model ?? "", durationMs: Date.now() - startedAt };
    } catch (error) {
      const failure = this.toFailure(error);
      await this.recordInvocation(primaryFailureRecorded ? activeConfig : config, options, failure.status, null, Date.now() - startedAt, failure.summary);
      throw this.toHttpException(failure);
    } finally {
      if (userAcquired) await this.releaseCounterSafely(userKey);
      if (globalAcquired) await this.releaseCounterSafely(globalKey);
    }
  }

  private buildArticleAssistantPrompt(
    operation: AiArticleOperation,
    input: { title: string; content: string; selectedText: string; category: string; tags: string },
    locale: "zh-CN" | "en-US",
  ): string {
    const material = [
      `TITLE:\n${input.title || "(empty)"}`,
      `CATEGORY:\n${input.category || "(empty)"}`,
      `TAGS:\n${input.tags || "(empty)"}`,
      `SELECTED TEXT:\n${input.selectedText || "(none)"}`,
      `ARTICLE BODY:\n${input.content || "(empty)"}`,
    ].join("\n\n");
    const instructions: Record<AiArticleOperation, string> = locale === "en-US"
      ? {
        title: "Suggest one concise, accurate article title. Return one line only.",
        outline: "Create a practical outline for this article. Use Markdown headings and bullet points.",
        summary: "Write a concise summary in 2 to 4 sentences.",
        taxonomy: "Suggest one category and up to six short tags. Return exactly two lines: Category: ... and Tags: tag1, tag2.",
        polish: "Polish the selected text if present, otherwise the article body. Keep the meaning and return the complete revised text.",
        rewrite: "Rewrite the selected text if present, otherwise the article body, with clearer structure and natural language. Keep the meaning.",
        expand: "Expand the selected text if present, otherwise the article body, with useful detail. Do not add unsupported facts.",
        shorten: "Shorten the selected text if present, otherwise the article body, while preserving the key meaning.",
        correct: "Correct grammar, spelling, punctuation, and Markdown structure in the selected text if present, otherwise the article body.",
        format: "Improve the Markdown structure of the article. Return Markdown only, using headings, lists, quotes, and code blocks where appropriate.",
      }
      : {
        title: "为文章拟定一个准确、简洁的标题，只返回一行。",
        outline: "为文章整理实用的提纲，使用 Markdown 标题和项目符号。",
        summary: "写一段 2 到 4 句的简洁摘要。",
        taxonomy: "建议一个分类和最多六个简短标签。严格只返回两行：分类：... 和 标签：标签1, 标签2。",
        polish: "如果有选中文字就润色选中文字，否则润色全文。保持原意，返回完整的修改后文本。",
        rewrite: "如果有选中文字就改写选中文字，否则改写全文，让结构更清晰、语言更自然，并保持原意。",
        expand: "如果有选中文字就扩写选中文字，否则扩写全文，补充有用细节，不得添加材料中没有依据的事实。",
        shorten: "如果有选中文字就缩写选中文字，否则缩写全文，保留关键含义。",
        correct: "如果有选中文字就纠正选中文字，否则纠正全文的语法、错别字、标点和 Markdown 结构。",
        format: "优化文章的 Markdown 结构，只返回 Markdown；恰当使用标题、列表、引用和代码块。",
      };
    return locale === "en-US"
      ? `${instructions[operation]}\n\nUse only the following material:\n${material}`
      : `${instructions[operation]}\n\n请仅根据以下材料处理：\n${material}`;
  }

  private async releaseCounterSafely(key: string): Promise<void> {
    try {
      await this.redis.releaseCounter(key);
    } catch {
      // Lease expiry remains the fallback when Redis is interrupted during release.
    }
  }

  private readApiKey(config: AiConfiguration): string | null {
    return this.decryptApiKey(config.apiKeyEncrypted);
  }

  private getFallbackRuntime(config: AiConfiguration, error: unknown): { provider: AiProvider; baseUrl: string; apiKey: string; model: string } | null {
    if (!config.fallbackEnabled || !(error instanceof AiProviderClientError) || !error.retryable) return null;
    const apiKey = this.decryptApiKey(config.fallbackApiKeyEncrypted);
    if (!config.fallbackProvider || !config.fallbackBaseUrl?.trim() || !config.fallbackModel?.trim() || !apiKey) return null;
    return { provider: config.fallbackProvider as AiProvider, baseUrl: config.fallbackBaseUrl, apiKey, model: config.fallbackModel };
  }

  private errorMessage(error: unknown): string {
    return error instanceof Error ? error.message : "AI provider request failed.";
  }

  private decryptApiKey(apiKeyEncrypted: string | null): string | null {
    if (!apiKeyEncrypted) return null;
    try {
      return this.crypto.decrypt(apiKeyEncrypted);
    } catch {
      return null;
    }
  }

  private toFailure(error: unknown): { status: "failed" | "rejected"; summary: string; code?: AiGatewayError["code"] } {
    if (error instanceof AiGatewayError) return { status: "rejected", summary: error.message, code: error.code };
    if (error instanceof AiProviderClientError) return { status: "failed", summary: error.message };
    return { status: "failed", summary: "AI 服务暂时不可用，请稍后重试。" };
  }

  private toHttpException(failure: { summary: string; code?: AiGatewayError["code"] }) {
    if (failure.code === "concurrency") return new ServiceUnavailableException(failure.summary);
    if (failure.code === "quota") return new HttpException(failure.summary, HttpStatus.TOO_MANY_REQUESTS);
    if (failure.summary.includes("超时")) return new GatewayTimeoutException(failure.summary);
    return new BadGatewayException(failure.summary);
  }

  private async recordInvocation(
    config: AiConfiguration,
    options: AiCompletionOptions,
    status: "success" | "failed" | "rejected",
    result: AiProviderCompletion | null,
    durationMs: number,
    errorSummary?: string,
  ) {
    try {
      const usage = result?.usage ?? { promptTokens: null, cachedPromptTokens: null, completionTokens: null, totalTokens: null };
      await this.prisma.aiInvocationLog.create({
        data: {
          userId: options.userId,
          operation: options.operation.slice(0, 64),
          provider: config.provider,
          model: config.model ?? "",
          status,
          promptTokens: usage.promptTokens,
          cachedPromptTokens: usage.cachedPromptTokens,
          completionTokens: usage.completionTokens,
          totalTokens: usage.totalTokens,
          durationMs: Math.max(0, Math.round(durationMs)),
          estimatedCostMicros: this.estimateCost(config, usage),
          capability: options.capability ?? "chat",
          sourceCount: options.sourceCount ?? 0,
          qualityScore: options.qualityScore ?? null,
          qualityFlags: options.qualityFlags?.length ? options.qualityFlags : undefined,
          errorSummary: errorSummary?.slice(0, 255) ?? null,
        },
      });
    } catch {
      // Logging must never turn a successful provider response into an error.
    }
  }

  private estimateCost(config: AiConfiguration, usage: { promptTokens: number | null; cachedPromptTokens: number | null; completionTokens: number | null }): number | null {
    if (usage.promptTokens === null && usage.completionTokens === null) return null;
    const cached = Math.min(usage.cachedPromptTokens ?? 0, usage.promptTokens ?? usage.cachedPromptTokens ?? 0);
    const regularInput = Math.max(0, (usage.promptTokens ?? 0) - cached);
    const input = (regularInput * (config.inputCostPerMillionMicros ?? 0) + cached * (config.cachedInputCostPerMillionMicros ?? 0)) / 1_000_000;
    const output = ((usage.completionTokens ?? 0) * (config.outputCostPerMillionMicros ?? 0)) / 1_000_000;
    return Math.max(0, Math.round(input + output));
  }

  private cacheHitRate(promptTokens: number | null | undefined, cachedPromptTokens: number | null | undefined): number {
    const prompt = promptTokens ?? 0;
    return prompt > 0 ? Math.round(Math.min(cachedPromptTokens ?? 0, prompt) / prompt * 100) : 0;
  }

  private utcDayKey(): string {
    return new Date().toISOString().slice(0, 10);
  }

  private secondsUntilUtcDayEnd(): number {
    const now = new Date();
    const tomorrow = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1));
    return Math.max(60, Math.ceil((tomorrow.getTime() - now.getTime()) / 1000));
  }

  private async getConfiguration(): Promise<AiConfiguration> {
    return this.prisma.aiConfiguration.upsert({
      where: { id: 1 },
      create: { id: 1 },
      update: {},
    });
  }

  private toAdminResponse(config: AiConfiguration) {
    return {
      enabled: config.enabled,
      provider: config.provider as AiProvider,
      baseUrl: config.baseUrl ?? "",
      model: config.model ?? "",
      apiKeyConfigured: Boolean(config.apiKeyEncrypted),
      fallbackEnabled: config.fallbackEnabled,
      fallbackProvider: (config.fallbackProvider as AiProvider | null) ?? null,
      fallbackBaseUrl: config.fallbackBaseUrl ?? "",
      fallbackModel: config.fallbackModel ?? "",
      fallbackApiKeyConfigured: Boolean(config.fallbackApiKeyEncrypted),
      globalConcurrency: config.globalConcurrency,
      userConcurrency: config.userConcurrency,
      maxOutputTokens: config.maxOutputTokens,
      requestTimeoutSeconds: config.requestTimeoutSeconds,
      dailyRequestLimit: config.dailyRequestLimit,
      ragEnabled: config.ragEnabled,
      ragTopK: config.ragTopK,
      contextMaxMessages: config.contextMaxMessages,
      contextSummaryThreshold: config.contextSummaryThreshold,
      contextRetentionDays: config.contextRetentionDays,
      qualityEvaluationEnabled: config.qualityEvaluationEnabled,
      billingCurrency: config.billingCurrency ?? "USD",
      inputCostPerMillionMicros: config.inputCostPerMillionMicros ?? 0,
      cachedInputCostPerMillionMicros: config.cachedInputCostPerMillionMicros ?? 0,
      outputCostPerMillionMicros: config.outputCostPerMillionMicros ?? 0,
      fallbackInputCostPerMillionMicros: config.fallbackInputCostPerMillionMicros ?? 0,
      fallbackCachedInputCostPerMillionMicros: config.fallbackCachedInputCostPerMillionMicros ?? 0,
      fallbackOutputCostPerMillionMicros: config.fallbackOutputCostPerMillionMicros ?? 0,
      pricingPresets: getAiPricingPresets(),
      recommendation: this.getResourceRecommendation(),
      encryptionConfigured: this.crypto.isConfigured(),
      updatedAt: config.updatedAt.toISOString(),
    };
  }
}
