import { Body, Controller, DefaultValuePipe, Get, Param, ParseIntPipe, Patch, Post, Query, UseGuards } from "@nestjs/common";
import { JwtAuthGuard } from "../auth/guards/jwt-auth.guard";
import { SuperAdminGuard } from "../auth/guards/super-admin.guard";
import { UserManagementGuard } from "../auth/guards/user-management.guard";
import { CurrentUser } from "../auth/current-user.decorator";
import { AuthenticatedUser } from "../auth/auth.types";
import { AiChatDto, AiToolConfirmationDto, AiToolInputDto, ArticleAssistantDto, ListAiModelsDto, UpdateAiConfigurationDto } from "./dto/ai.dto";
import { AiService } from "./ai.service";

@Controller("ai/admin")
@UseGuards(JwtAuthGuard, UserManagementGuard, SuperAdminGuard)
export class AiController {
  constructor(private readonly ai: AiService) {}

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
}

@Controller("ai")
@UseGuards(JwtAuthGuard)
export class ArticleAiController {
  constructor(private readonly ai: AiService) {}

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

  @Post("tools/:name")
  executeTool(@CurrentUser() user: AuthenticatedUser, @Param("name") name: string, @Body() dto: AiToolInputDto) {
    return this.ai.executeTool(user, name, dto);
  }

  @Post("tool-invocations/:id/confirm")
  confirmTool(@CurrentUser() user: AuthenticatedUser, @Param("id", ParseIntPipe) id: number, @Body() dto: AiToolConfirmationDto) {
    return this.ai.confirmTool(user, id, dto.confirmationToken, dto.selectedSuggestedTags ?? []);
  }
}
