import { Type } from "class-transformer";
import { IsBoolean, IsIn, IsInt, IsObject, IsOptional, IsString, Max, MaxLength, Min } from "class-validator";

export const AI_PROVIDERS = ["openai-compatible", "deepseek", "custom", "anthropic", "google"] as const;
export type AiProvider = (typeof AI_PROVIDERS)[number];

export const AI_ARTICLE_OPERATIONS = [
  "title",
  "outline",
  "summary",
  "taxonomy",
  "polish",
  "rewrite",
  "expand",
  "shorten",
  "correct",
  "format",
] as const;
export type AiArticleOperation = (typeof AI_ARTICLE_OPERATIONS)[number];

export const AI_TOOL_NAMES = [
  "get_my_summary",
  "list_my_tasks",
  "list_my_subscriptions",
  "list_my_earnings",
  "list_my_articles",
  "search_visible_articles",
  "get_article_context",
  "summarize_topic",
  "summarize_collection",
  "get_admin_overview",
  "create_article_draft",
] as const;
export type AiToolName = (typeof AI_TOOL_NAMES)[number];

export class UpdateAiConfigurationDto {
  @IsBoolean()
  enabled!: boolean;

  @IsIn(AI_PROVIDERS)
  provider!: AiProvider;

  @IsOptional()
  @IsString()
  @MaxLength(512)
  baseUrl?: string;

  @IsOptional()
  @IsString()
  @MaxLength(160)
  model?: string;

  @IsOptional()
  @IsString()
  @MaxLength(1000)
  apiKey?: string;

  @IsOptional()
  @IsBoolean()
  clearApiKey?: boolean;

  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(8)
  globalConcurrency!: number;

  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(8)
  userConcurrency!: number;

  @Type(() => Number)
  @IsInt()
  @Min(256)
  @Max(8000)
  maxOutputTokens!: number;

  @Type(() => Number)
  @IsInt()
  @Min(10)
  @Max(300)
  requestTimeoutSeconds!: number;

  @Type(() => Number)
  @IsInt()
  @Min(0)
  @Max(100000)
  dailyRequestLimit!: number;

  @IsIn(["USD", "CNY"])
  billingCurrency!: "USD" | "CNY";

  @Type(() => Number)
  @IsInt()
  @Min(0)
  @Max(1000000000)
  inputCostPerMillionMicros!: number;

  @Type(() => Number)
  @IsInt()
  @Min(0)
  @Max(1000000000)
  outputCostPerMillionMicros!: number;
}

export class ArticleAssistantDto {
  @IsIn(AI_ARTICLE_OPERATIONS)
  operation!: AiArticleOperation;

  @IsOptional()
  @IsString()
  @MaxLength(120)
  title?: string;

  @IsOptional()
  @IsString()
  @MaxLength(59000)
  content?: string;

  @IsOptional()
  @IsString()
  @MaxLength(30000)
  selectedText?: string;

  @IsOptional()
  @IsString()
  @MaxLength(80)
  category?: string;

  @IsOptional()
  @IsString()
  @MaxLength(500)
  tags?: string;

  @IsOptional()
  @IsIn(["zh-CN", "en-US"])
  locale?: "zh-CN" | "en-US";
}

export class AiChatDto {
  @IsOptional()
  @IsInt()
  @Min(1)
  conversationId?: number;

  @IsString()
  @MaxLength(8000)
  message!: string;

  @IsOptional()
  @IsInt()
  @Min(1)
  articleId?: number;

  @IsOptional()
  @IsString()
  @MaxLength(180)
  articleSlug?: string;

  @IsOptional()
  @IsString()
  @MaxLength(120)
  topicSlug?: string;

  @IsOptional()
  @IsInt()
  @Min(1)
  collectionId?: number;

  @IsOptional()
  @IsIn(["zh-CN", "en-US"])
  locale?: "zh-CN" | "en-US";
}

export class AiToolInputDto {
  @IsOptional()
  @IsObject()
  input?: Record<string, unknown>;

  @IsOptional()
  @IsInt()
  @Min(1)
  conversationId?: number;
}

export class AiToolConfirmationDto {
  @IsString()
  @MaxLength(160)
  confirmationToken!: string;
}
