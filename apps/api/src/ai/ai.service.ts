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
import { AiArticleOperation, AiChatDto, AiProvider, AiToolInputDto, AiToolName, ArticleAssistantDto, ListAiModelsDto, UpdateAiConfigurationDto } from "./dto/ai.dto";
import {
  AiChatMessage,
  AiProviderClientError,
  AiProviderCompletion,
  completeWithProvider,
  listModelsWithProvider,
} from "./ai-provider.client";
import os from "node:os";

const AI_TOOL_DEFINITIONS = [
  { name: "get_my_summary", label: "我的积分与成长", description: "读取当前账号的积分、经验、等级和最近账本记录。", readOnly: true, requiresConfirmation: false },
  { name: "list_my_tasks", label: "我的待办", description: "整理当前账号未读的通知和待处理入口，不会自动修改状态。", readOnly: true, requiresConfirmation: false },
  { name: "list_my_subscriptions", label: "我的订阅", description: "读取当前账号订阅的作者、专题和合集概况。", readOnly: true, requiresConfirmation: false },
  { name: "list_my_earnings", label: "我的收益", description: "读取当前账号资源兑换产生的待结算、已结算和退款收益。", readOnly: true, requiresConfirmation: false },
  { name: "list_my_articles", label: "我的文章状态", description: "读取当前账号文章的草稿、发布和审核状态。", readOnly: true, requiresConfirmation: false },
  { name: "search_visible_articles", label: "搜索可见文章", description: "只搜索当前账号有权限查看的站内文章。", readOnly: true, requiresConfirmation: false },
  { name: "list_visible_articles", label: "权限范围内文章", description: "列出当前账号有权限查看的近期站内文章。", readOnly: true, requiresConfirmation: false },
  { name: "list_recommended_articles", label: "个性化推荐文章", description: "根据当前账号的兴趣和反馈，列出有权限查看的推荐文章。", readOnly: true, requiresConfirmation: false },
  { name: "get_article_context", label: "读取文章上下文", description: "读取当前账号有权限查看的文章正文并作为问答来源。", readOnly: true, requiresConfirmation: false },
  { name: "summarize_topic", label: "总结专题", description: "只总结当前账号有权限查看的专题文章。", readOnly: true, requiresConfirmation: false },
  { name: "summarize_collection", label: "总结合集", description: "只总结当前账号有权限查看的合集文章。", readOnly: true, requiresConfirmation: false },
  { name: "get_admin_overview", label: "解释后台概况", description: "读取管理员可见的运营概况，只返回汇总数据。", readOnly: true, requiresConfirmation: false },
  { name: "create_article_draft", label: "创建文章草稿", description: "准备创建文章草稿，必须经过确认后才会写入。", readOnly: false, requiresConfirmation: true },
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

    let apiKeyEncrypted = current.apiKeyEncrypted;
    if (dto.clearApiKey) apiKeyEncrypted = null;
    else if (dto.apiKey?.trim()) apiKeyEncrypted = this.crypto.encrypt(dto.apiKey.trim());

    const saved = await this.prisma.aiConfiguration.update({
      where: { id: 1 },
      data: {
        enabled: dto.enabled,
        provider: dto.provider,
        baseUrl: dto.baseUrl?.trim() || null,
        model: dto.model?.trim() || null,
        apiKeyEncrypted,
        globalConcurrency: dto.globalConcurrency,
        userConcurrency: dto.userConcurrency,
        maxOutputTokens: dto.maxOutputTokens,
        requestTimeoutSeconds: dto.requestTimeoutSeconds,
        dailyRequestLimit: dto.dailyRequestLimit,
        billingCurrency: dto.billingCurrency,
        inputCostPerMillionMicros: dto.inputCostPerMillionMicros,
        outputCostPerMillionMicros: dto.outputCostPerMillionMicros,
      },
    });
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
    const config = await this.getConfiguration();
    const apiKey = dto.apiKey?.trim() || this.readApiKey(config);
    if (!apiKey) throw new BadRequestException("请先填写 API Key，或保留已保存的 API Key。\nEnter an API key or keep the saved key.");
    const baseUrl = dto.baseUrl?.trim() || config.baseUrl?.trim();
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
    return {
      id: conversation.id,
      title: conversation.title,
      createdAt: conversation.createdAt.toISOString(),
      updatedAt: conversation.updatedAt.toISOString(),
      messages: conversation.messages.map((message) => ({ id: message.id, role: message.role, content: message.content, sources: message.sources, createdAt: message.createdAt.toISOString() })),
    };
  }

  async chat(user: AuthenticatedUser, dto: AiChatDto) {
    const message = dto.message.trim();
    if (!message) throw new BadRequestException("问题不能为空。\nThe question cannot be empty.");
    const conversation = dto.conversationId
      ? await this.prisma.aiConversation.findFirst({ where: { id: dto.conversationId, userId: user.id } })
      : await this.prisma.aiConversation.create({ data: { userId: user.id, title: message.slice(0, 60) } });
    if (!conversation) throw new BadRequestException("AI 对话不存在或不属于当前账号。\nThe AI conversation does not exist or does not belong to this account.");

    const history = await this.prisma.aiConversationMessage.findMany({
      where: { conversationId: conversation.id },
      orderBy: [{ createdAt: "desc" }, { id: "desc" }],
      take: 24,
      select: { role: true, content: true, sources: true },
    });
    const locale = dto.locale === "en-US" ? "en-US" : "zh-CN";
    const config = await this.getConfiguration();
    if (!config.enabled) throw new BadRequestException("AI 功能尚未启用。\nAI features are not enabled.");
    const siteQuestion = this.isSiteRelatedQuestion(message, dto) || history.some((item) => item.role === "user" && this.isSiteRelatedQuestion(item.content));
    const refusal = locale === "en-US"
      ? "I am the site's assistant and can help with content and data that this account is allowed to access. I do not answer unrelated general questions here."
      : "我是本站助手，只处理当前账号有权限访问的站内内容和数据。与本站无关的通用问题不在当前助手范围内。";
    if (!siteQuestion) {
      await this.persistChatTurn(conversation.id, message, refusal, []);
      return { conversationId: conversation.id, title: conversation.title, text: refusal, sources: [], provider: config.provider as AiProvider, model: config.model ?? "", durationMs: 0, usage: { promptTokens: null, completionTokens: null, totalTokens: null } };
    }
    const context = await this.buildChatContext(user, dto, history);
    const system = locale === "en-US"
      ? "You are the site's permission-aware assistant. The supplied context is the complete set of data this account may use for this request. Answer site questions flexibly and directly from it, including follow-up questions. Never invent or infer hidden content, credentials, tokens, or administrator-only data. If the permitted context has no matching data, say so clearly. Do not answer unrelated general questions. Cite numbered sources when you use them."
      : "你是本站的权限感知助手。提供的上下文是当前账号本次请求可以使用的完整数据。请根据上下文灵活、直接地回答站内问题，包括连续追问；不得编造或推断隐藏内容、凭据、令牌或仅管理员可见的数据。如果权限范围内没有匹配数据，要明确说明。不要回答与本站无关的通用问题。使用来源时请标注对应的来源编号。";
    const contextMessage = locale === "en-US" ? `Readable site context:\n${context.text}` : `当前账号可读取的站内上下文：\n${context.text}`;
    const result = await this.complete({
      userId: user.id,
      operation: "p24_chat",
      messages: [
        { role: "system", content: system },
        { role: "system", content: contextMessage },
        ...this.compactHistory(history),
        { role: "user", content: message },
      ],
    });
    await this.persistChatTurn(conversation.id, message, result.text.trim(), context.sources);
    return {
      conversationId: conversation.id,
      title: conversation.title,
      text: result.text.trim(),
      sources: context.sources,
      provider: result.provider,
      model: result.model,
      durationMs: result.durationMs,
      usage: result.usage,
    };
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

  async confirmTool(user: AuthenticatedUser, invocationId: number, confirmationToken: string) {
    const invocation = await this.prisma.aiToolInvocation.findFirst({ where: { id: invocationId, userId: user.id, toolName: "create_article_draft", status: "awaiting_confirmation" } });
    if (!invocation || !invocation.confirmationTokenHash || !invocation.confirmationExpiresAt || invocation.confirmationExpiresAt.getTime() < Date.now() || !this.sameSecret(confirmationToken, invocation.confirmationTokenHash)) {
      throw new BadRequestException("确认令牌无效或已过期，请重新准备草稿。\nThe confirmation token is invalid or expired; prepare the draft again.");
    }
    const input = this.readObject(invocation.input);
    const title = this.readString(input.title, 120);
    const content = this.readString(input.content, 60000);
    if (!title || !content) throw new BadRequestException("草稿标题和正文不能为空。\nA draft title and body are required.");
    try {
      const article = this.articles ? await this.articles.create(user, {
        title,
        content,
        summary: this.readString(input.summary, 300),
        category: this.readString(input.category, 80),
        tags: this.readString(input.tags, 500),
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
    return { text: [accountText, parts.join("\n\n")].filter(Boolean).join("\n\n"), sources: contexts.map(({ source }) => source) };
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
    await this.prisma.$transaction([
      this.prisma.aiConversationMessage.create({ data: { conversationId, role: "user", content: message } }),
      this.prisma.aiConversationMessage.create({ data: { conversationId, role: "assistant", content: response, sources: sources as unknown as Prisma.InputJsonValue } }),
      this.prisma.aiConversation.update({ where: { id: conversationId }, data: { updatedAt: new Date() } }),
    ]);
  }

  private isSiteRelatedQuestion(message: string, dto?: Pick<AiChatDto, "articleId" | "articleSlug" | "topicSlug" | "collectionId">): boolean {
    if (dto && (dto.articleId || dto.articleSlug || dto.topicSlug || dto.collectionId)) return true;
    return /(本站|站内|文章|专题|合集|评论|回复|作者|用户|订阅|收藏|待办|任务|通知|聊天|群聊|积分|收益|资源|反馈|举报|阅读文章|推荐文章|搜索文章|登录|密码|邮箱|通行密钥|双因素|账号|账户|草稿|发布|发现|数据与隐私|我的数据|个人数据|能做什么|你是谁|ai\s*助手|site|site content|article|topic|collection|comment|reply|author|subscription|bookmark|task|notification|group chat|points|earnings|resource|feedback|report|read article|recommended articles|search articles|login|password|email|passkey|totp|account|draft|publish|discover|privacy|my data|what can you do|who are you)/i.test(message);
  }

  private compactHistory(history: Array<{ role: string; content: string }>): AiChatMessage[] {
    const maxCharacters = 12_000;
    let remaining = maxCharacters;
    const selected: AiChatMessage[] = [];
    for (const item of history.slice().reverse()) {
      if (remaining <= 0) break;
      const content = item.content.trim();
      if (!content) continue;
      const clipped = content.length > remaining ? `${content.slice(0, Math.max(0, remaining - 12))}\n[已截断]` : content;
      selected.unshift({ role: item.role === "assistant" ? "assistant" : "user", content: clipped });
      remaining -= clipped.length;
    }
    return selected;
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
    const title = this.readString(input.title, 120);
    const content = this.readString(input.content, 60000);
    if (!title || !content) throw new BadRequestException("准备草稿需要标题和正文。\nA title and body are required to prepare a draft.");
    const token = randomBytes(32).toString("base64url");
    const expiresAt = new Date(Date.now() + 10 * 60 * 1000);
    const invocation = await this.prisma.aiToolInvocation.create({ data: { userId: user.id, conversationId, toolName: "create_article_draft", input: input as Prisma.InputJsonValue, status: "awaiting_confirmation", requiresConfirmation: true, confirmationTokenHash: createHash("sha256").update(token).digest("hex"), confirmationExpiresAt: expiresAt } });
    return { invocationId: invocation.id, status: "awaiting_confirmation", requiresConfirmation: true, confirmationToken: token, expiresAt: expiresAt.toISOString(), preview: { title, summary: this.readString(input.summary, 300), category: this.readString(input.category, 80), tags: this.readString(input.tags, 500), content } };
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
        _sum: { totalTokens: true, estimatedCostMicros: true },
      }),
    ]);
    return {
      today: {
        requests: today._count._all,
        totalTokens: today._sum.totalTokens ?? 0,
        estimatedCostMicros: today._sum.estimatedCostMicros ?? 0,
      },
      logs: logs.map((log) => ({
        id: log.id,
        operation: log.operation,
        provider: log.provider as AiProvider,
        model: log.model,
        status: log.status,
        promptTokens: log.promptTokens,
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

      const result = await completeWithProvider({
        provider: config.provider as AiProvider,
        baseUrl: config.baseUrl,
        apiKey,
        model: config.model,
        maxOutputTokens: config.maxOutputTokens,
        timeoutSeconds: config.requestTimeoutSeconds,
        messages: options.messages,
      });
      await this.recordInvocation(config, options, "success", result, Date.now() - startedAt);
      return {
        ...result,
        provider: config.provider as AiProvider,
        model: config.model,
        durationMs: Date.now() - startedAt,
      };
    } catch (error) {
      const failure = this.toFailure(error);
      await this.recordInvocation(config, options, failure.status, null, Date.now() - startedAt, failure.summary);
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
    if (!config.apiKeyEncrypted) return null;
    try {
      return this.crypto.decrypt(config.apiKeyEncrypted);
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
      const usage = result?.usage ?? { promptTokens: null, completionTokens: null, totalTokens: null };
      await this.prisma.aiInvocationLog.create({
        data: {
          userId: options.userId,
          operation: options.operation.slice(0, 64),
          provider: config.provider,
          model: config.model ?? "",
          status,
          promptTokens: usage.promptTokens,
          completionTokens: usage.completionTokens,
          totalTokens: usage.totalTokens,
          durationMs: Math.max(0, Math.round(durationMs)),
          estimatedCostMicros: this.estimateCost(config, usage),
          errorSummary: errorSummary?.slice(0, 255) ?? null,
        },
      });
    } catch {
      // Logging must never turn a successful provider response into an error.
    }
  }

  private estimateCost(config: AiConfiguration, usage: { promptTokens: number | null; completionTokens: number | null }): number | null {
    if (usage.promptTokens === null && usage.completionTokens === null) return null;
    const input = ((usage.promptTokens ?? 0) * (config.inputCostPerMillionMicros ?? 0)) / 1_000_000;
    const output = ((usage.completionTokens ?? 0) * (config.outputCostPerMillionMicros ?? 0)) / 1_000_000;
    return Math.max(0, Math.round(input + output));
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
      globalConcurrency: config.globalConcurrency,
      userConcurrency: config.userConcurrency,
      maxOutputTokens: config.maxOutputTokens,
      requestTimeoutSeconds: config.requestTimeoutSeconds,
      dailyRequestLimit: config.dailyRequestLimit,
      billingCurrency: config.billingCurrency ?? "USD",
      inputCostPerMillionMicros: config.inputCostPerMillionMicros ?? 0,
      outputCostPerMillionMicros: config.outputCostPerMillionMicros ?? 0,
      recommendation: this.getResourceRecommendation(),
      encryptionConfigured: this.crypto.isConfigured(),
      updatedAt: config.updatedAt.toISOString(),
    };
  }
}
