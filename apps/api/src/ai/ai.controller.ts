import { BadRequestException, Body, Controller, DefaultValuePipe, Get, Param, ParseIntPipe, Patch, Post, Query, Res, StreamableFile, UploadedFile, UseGuards, UseInterceptors } from "@nestjs/common";
import { FileInterceptor } from "@nestjs/platform-express";
import type { Response } from "express";
import { JwtAuthGuard } from "../auth/guards/jwt-auth.guard";
import { SuperAdminGuard } from "../auth/guards/super-admin.guard";
import { UserManagementGuard } from "../auth/guards/user-management.guard";
import { CurrentUser } from "../auth/current-user.decorator";
import { AuthenticatedUser } from "../auth/auth.types";
import { AI_CAPABILITIES, AiCapability, AiChatDto, AiMediaPromptDto, AiQualityFeedbackDto, AiToolConfirmationDto, AiToolInputDto, ArticleAssistantDto, ListAiModelsDto, UpdateAiCapabilityConfigurationDto, UpdateAiConfigurationDto } from "./dto/ai.dto";
import { AiService } from "./ai.service";
import { AiCapabilitiesService } from "./ai-capabilities.service";
import { AiKnowledgeService } from "./ai-knowledge.service";

@Controller("ai/admin")
@UseGuards(JwtAuthGuard, UserManagementGuard, SuperAdminGuard)
export class AiController {
  constructor(private readonly ai: AiService, private readonly capabilities: AiCapabilitiesService, private readonly knowledge: AiKnowledgeService) {}

  @Get("configuration")
  getConfiguration() {
    return this.ai.getAdminConfiguration();
  }

  @Patch("configuration")
  updateConfiguration(@Body() dto: UpdateAiConfigurationDto) {
    return this.ai.updateConfiguration(dto);
  }

  @Post("test-connection")
  testConnection(@CurrentUser() user: AuthenticatedUser) {
    return this.ai.testConnection(user.id);
  }

  @Post("models")
  listModels(@Body() dto: ListAiModelsDto) {
    return this.ai.listModels(dto);
  }

  @Get("invocations")
  getInvocations(@Query("limit", new DefaultValuePipe(30), ParseIntPipe) limit: number) {
    return this.ai.getAdminInvocationOverview(limit);
  }

  @Get("tools")
  getToolInvocations(@Query("limit", new DefaultValuePipe(50), ParseIntPipe) limit: number) {
    return this.ai.getAdminToolInvocations(limit);
  }

  @Get("capabilities")
  getCapabilities() {
    return this.capabilities.listConfigurations();
  }

  @Patch("capabilities/:capability")
  updateCapability(@Param("capability") capability: string, @Body() dto: UpdateAiCapabilityConfigurationDto) {
    return this.capabilities.updateConfiguration(this.readCapability(capability), dto);
  }

  @Post("capabilities/:capability/test")
  testCapability(@Param("capability") capability: string) {
    return this.capabilities.testConfiguration(this.readCapability(capability));
  }

  @Get("usage")
  getUsage(@Query("days", new DefaultValuePipe(30), ParseIntPipe) days: number) {
    return this.capabilities.usageOverview(days);
  }

  @Get("knowledge")
  getKnowledgeOverview() {
    return this.knowledge.getOverview();
  }

  @Get("knowledge/documents")
  getKnowledgeDocuments(@Query("limit", new DefaultValuePipe(100), ParseIntPipe) limit: number) {
    return this.knowledge.listDocuments(limit);
  }

  @Post("knowledge/documents/:id/reindex")
  reindexKnowledgeDocument(@Param("id", ParseIntPipe) id: number) {
    return this.knowledge.reindexDocument(id);
  }

  @Post("knowledge/reindex")
  reindexKnowledge(@Query("limit", new DefaultValuePipe(50), ParseIntPipe) limit: number) {
    return this.knowledge.reindex(limit);
  }

  private readCapability(value: string): AiCapability {
    if (!AI_CAPABILITIES.includes(value as AiCapability)) throw new BadRequestException("不支持的 AI 能力。\nUnsupported AI capability.");
    return value as AiCapability;
  }
}

@Controller("ai")
@UseGuards(JwtAuthGuard)
export class ArticleAiController {
  constructor(private readonly ai: AiService, private readonly capabilities: AiCapabilitiesService, private readonly knowledge: AiKnowledgeService) {}

  @Post("article-assistant")
  articleAssistant(@CurrentUser() user: AuthenticatedUser, @Body() dto: ArticleAssistantDto) {
    return this.ai.articleAssistant(user.id, dto);
  }

  @Get("tools")
  listTools() {
    return this.ai.listTools();
  }

  @Get("conversations")
  listConversations(@CurrentUser() user: AuthenticatedUser) {
    return this.ai.listConversations(user.id);
  }

  @Get("conversations/:id")
  getConversation(@CurrentUser() user: AuthenticatedUser, @Param("id", ParseIntPipe) id: number) {
    return this.ai.getConversation(user.id, id);
  }

  @Post("chat")
  chat(@CurrentUser() user: AuthenticatedUser, @Body() dto: AiChatDto) {
    return this.ai.chat(user, dto);
  }

  @Post("quality-feedback")
  recordQualityFeedback(@CurrentUser() user: AuthenticatedUser, @Body() dto: AiQualityFeedbackDto) {
    return this.ai.recordQualityFeedback(user.id, dto);
  }

  @Post("tools/:name")
  executeTool(@CurrentUser() user: AuthenticatedUser, @Param("name") name: string, @Body() dto: AiToolInputDto) {
    return this.ai.executeTool(user, name, dto);
  }

  @Post("tool-invocations/:id/confirm")
  confirmTool(@CurrentUser() user: AuthenticatedUser, @Param("id", ParseIntPipe) id: number, @Body() dto: AiToolConfirmationDto) {
    return this.ai.confirmTool(user, id, dto.confirmationToken, dto.selectedSuggestedTags ?? []);
  }

  @Get("media-tasks")
  listMediaTasks(@CurrentUser() user: AuthenticatedUser) {
    return this.capabilities.listMediaTasks(user.id);
  }

  @Get("media-capabilities")
  getMediaCapabilities() {
    return this.capabilities.getUserMediaCapabilities();
  }

  @Get("media-tasks/:id")
  getMediaTask(@CurrentUser() user: AuthenticatedUser, @Param("id", ParseIntPipe) id: number) {
    return this.capabilities.getMediaTask(user.id, id);
  }

  @Get("media-tasks/:id/image")
  async getMediaTaskImage(@CurrentUser() user: AuthenticatedUser, @Param("id", ParseIntPipe) id: number, @Res({ passthrough: true }) response: Response): Promise<StreamableFile> {
    const image = await this.capabilities.getMediaTaskImage(user.id, id);
    response.set({ "Cache-Control": "private, max-age=86400", "Content-Type": image.mimeType, "Content-Length": String(image.buffer.length), "X-Content-Type-Options": "nosniff" });
    return new StreamableFile(image.buffer);
  }

  @Post("media/ocr")
  @UseInterceptors(FileInterceptor("file", { limits: { fileSize: 10 * 1024 * 1024 } }))
  ocr(@CurrentUser() user: AuthenticatedUser, @UploadedFile() file: { buffer: Buffer; originalname: string; mimetype: string; size: number } | undefined, @Body("prompt") prompt?: string) {
    if (!file?.buffer) throw new BadRequestException("请选择图片文件。\nChoose an image file.");
    return this.capabilities.processOcr(user, file, prompt);
  }

  @Post("media/transcription")
  @UseInterceptors(FileInterceptor("file", { limits: { fileSize: 20 * 1024 * 1024 } }))
  transcription(@CurrentUser() user: AuthenticatedUser, @UploadedFile() file: { buffer: Buffer; originalname: string; mimetype: string; size: number } | undefined) {
    if (!file?.buffer) throw new BadRequestException("请选择音频文件。\nChoose an audio file.");
    return this.capabilities.processTranscription(user, file);
  }

  @Post("media/image")
  image(@CurrentUser() user: AuthenticatedUser, @Body() dto: AiMediaPromptDto) {
    return this.capabilities.generateImage(user, dto);
  }

  @Get("knowledge/search")
  searchKnowledge(@CurrentUser() user: AuthenticatedUser, @Query("q") query: string, @Query("limit", new DefaultValuePipe(6), ParseIntPipe) limit: number) {
    return this.knowledge.search(user, query ?? "", limit);
  }
}
