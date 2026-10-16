@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class NativeAiToolDialogState(
    val tool: NativeAiTool,
    val output: Any? = null,
    val loading: Boolean = true,
)

private data class NativeAiPendingDraft(
    val invocationId: Int,
    val confirmationToken: String,
    val preview: NativeAiDraftPreview,
    val selectedSuggestedTags: List<String> = emptyList(),
)

internal fun shouldFollowNativeAiMessages(lastVisibleIndex: Int?, totalItems: Int): Boolean =
    totalItems <= 0 || lastVisibleIndex == null || lastVisibleIndex >= totalItems - 1

@Composable
internal fun NativeAiScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onOpenSource: (NativeAiSource) -> Unit,
) {
    val accessToken = session?.accessToken
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messageListState = rememberLazyListState()
    var loading by remember(accessToken) { mutableStateOf(true) }
    var sending by remember(accessToken) { mutableStateOf(false) }
    var toolRunning by remember(accessToken) { mutableStateOf("") }
    var error by remember(accessToken) { mutableStateOf<String?>(null) }
    var notice by remember(accessToken) { mutableStateOf<String?>(null) }
    var conversations by remember(accessToken) { mutableStateOf<List<NativeAiConversationSummary>>(emptyList()) }
    var tools by remember(accessToken) { mutableStateOf<List<NativeAiTool>>(emptyList()) }
    var conversationId by remember(accessToken) { mutableStateOf<Int?>(null) }
    var messages by remember(accessToken) { mutableStateOf<List<NativeAiMessage>>(emptyList()) }
    var followLatestMessages by remember(accessToken) { mutableStateOf(true) }
    var pendingMessageScroll by remember(accessToken) { mutableStateOf<Boolean?>(null) }
    var draft by remember(accessToken) { mutableStateOf("") }
    var toolDialog by remember(accessToken) { mutableStateOf<NativeAiToolDialogState?>(null) }
    var draftDialogOpen by remember(accessToken) { mutableStateOf(false) }
    var draftDescription by remember(accessToken) { mutableStateOf("") }
    var pendingDraft by remember(accessToken) { mutableStateOf<NativeAiPendingDraft?>(null) }
    var mediaCapabilities by remember(accessToken) { mutableStateOf<List<NativeAiMediaCapability>>(emptyList()) }
    var mediaTasks by remember(accessToken) { mutableStateOf<List<NativeAiMediaTaskSummary>>(emptyList()) }
    var mediaDialogKind by remember(accessToken) { mutableStateOf<String?>(null) }
    var mediaDialogTask by remember(accessToken) { mutableStateOf<NativeAiMediaTaskDetail?>(null) }
    var mediaDialogLoading by remember(accessToken) { mutableStateOf(false) }
    var mediaBusy by remember(accessToken) { mutableStateOf(false) }
    var mediaPrompt by remember(accessToken) { mutableStateOf("") }
    var mediaSize by remember(accessToken) { mutableStateOf("1024x1024") }
    var mediaImage by remember(accessToken) { mutableStateOf<Bitmap?>(null) }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val token = accessToken
        val kind = mediaDialogKind
        if (uri == null || token.isNullOrBlank() || kind !in setOf("ocr", "transcription")) return@rememberLauncherForActivityResult
        val maxBytes = mediaCapabilities.firstOrNull { it.capability == kind }?.maxInputBytes?.takeIf { it > 0 }
            ?: if (kind == "ocr") 10L * 1024L * 1024L else 20L * 1024L * 1024L
        scope.launch {
            mediaBusy = true
            error = null
            try {
                val file = withContext(Dispatchers.IO) { readNativeUploadFile(context, uri, maxBytes) }
                val result = withContext(Dispatchers.IO) {
                    if (kind == "ocr") NativeAiApi.processOcr(token, file, mediaPrompt) else NativeAiApi.processTranscription(token, file)
                }
                mediaDialogTask = result
                mediaTasks = withContext(Dispatchers.IO) { NativeAiApi.listMediaTasks(token) }
                notice = if (kind == "ocr") {
                    nativePhrase("图片文字识别已完成。", "Image text recognition completed.")
                } else {
                    nativePhrase("语音转文字已完成。", "Audio transcription completed.")
                }
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("媒体任务执行失败。", "The media task could not be completed.")
            } finally {
                mediaBusy = false
            }
        }
    }

    fun closeMediaDialog() {
        mediaDialogKind = null
        mediaDialogTask = null
        mediaDialogLoading = false
        mediaBusy = false
        mediaPrompt = ""
        mediaImage = null
    }

    fun openMedia(kind: String) {
        mediaDialogKind = kind
        mediaDialogTask = null
        mediaDialogLoading = false
        mediaPrompt = ""
        mediaSize = "1024x1024"
        mediaImage = null
    }

    fun runImageGeneration() {
        val token = accessToken ?: return
        val prompt = mediaPrompt.trim()
        if (prompt.isBlank() || mediaBusy) return
        scope.launch {
            mediaBusy = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) { NativeAiApi.generateImage(token, prompt, mediaSize) }
                mediaDialogTask = result
                mediaTasks = withContext(Dispatchers.IO) { NativeAiApi.listMediaTasks(token) }
                notice = nativePhrase("图片生成已完成。", "Image generation completed.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("图片生成失败。", "Image generation failed.")
            } finally {
                mediaBusy = false
            }
        }
    }

    fun openMediaTask(task: NativeAiMediaTaskSummary) {
        val token = accessToken ?: return
        mediaDialogKind = task.capability
        mediaDialogTask = null
        mediaDialogLoading = true
        mediaImage = null
        scope.launch {
            try {
                mediaDialogTask = withContext(Dispatchers.IO) { NativeAiApi.getMediaTask(token, task.id) }
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("媒体任务读取失败。", "The media task could not be loaded.")
                closeMediaDialog()
            } finally {
                mediaDialogLoading = false
            }
        }
    }

    fun openMediaResultUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            error = nativePhrase(
                "设备上没有可以打开该结果的应用。",
                "No app on this device can open the result.",
            )
        }
    }

    fun newConversation() {
        conversationId = null
        messages = emptyList()
        followLatestMessages = true
        pendingMessageScroll = null
        pendingDraft = null
        toolDialog = null
        draftDescription = ""
        draftDialogOpen = false
        notice = nativePhrase("已开始新对话。", "A new conversation has started.")
    }

    fun loadConversation(id: Int) {
        val token = accessToken ?: return
        scope.launch {
            error = null
            try {
                val conversation = withContext(Dispatchers.IO) { NativeAiApi.getConversation(token, id) }
                conversationId = conversation.id
                messages = conversation.messages
                followLatestMessages = true
                pendingMessageScroll = true
                pendingDraft = null
                toolDialog = null
                draftDialogOpen = false
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("AI 对话读取失败。", "The AI conversation could not be loaded.")
            }
        }
    }

    fun sendMessage() {
        val token = accessToken ?: return
        val content = draft.trim()
        if (content.isBlank() || sending) return
        val visibleLastIndex = messageListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        val shouldFollow = shouldFollowNativeAiMessages(visibleLastIndex, messageListState.layoutInfo.totalItemsCount)
        followLatestMessages = shouldFollow
        pendingMessageScroll = shouldFollow
        draft = ""
        sending = true
        error = null
        messages = messages + NativeAiMessage(null, "user", content, emptyList(), null, "")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    NativeAiApi.chat(token, conversationId, content, session?.locale ?: "zh-CN")
                }
                conversationId = result.conversationId
                messages = messages + NativeAiMessage(
                    id = result.messageId,
                    role = "assistant",
                    content = result.text,
                    sources = result.sources,
                    qualityRating = null,
                    createdAt = "",
                )
                conversations = withContext(Dispatchers.IO) { NativeAiApi.listConversations(token) }
                notice = if (result.durationMs > 0) {
                    nativePhrase(
                        "回答已生成，用时 ${result.durationMs} ms。",
                        "The answer was generated in ${result.durationMs} ms.",
                    )
                } else {
                    nativePhrase("回答已生成。", "The answer is ready.")
                }
            } catch (exception: Exception) {
                messages = messages.dropLast(1)
                error = exception.message ?: nativePhrase("AI 请求失败。", "The AI request failed.")
            } finally {
                sending = false
            }
        }
    }

    fun rateMessage(message: NativeAiMessage, rating: Int) {
        val token = accessToken ?: return
        val id = message.id ?: return
        val currentConversationId = conversationId ?: return
        val previous = message.qualityRating
        messages = messages.map { if (it.id == id) it.copy(qualityRating = rating) else it }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeAiApi.sendQualityFeedback(token, currentConversationId, id, rating) }
                notice = nativePhrase(
                    "感谢反馈，已记录这条回答的质量评价。",
                    "Thanks. Your rating for this answer was recorded.",
                )
            } catch (exception: Exception) {
                messages = messages.map { if (it.id == id) it.copy(qualityRating = previous) else it }
                error = exception.message ?: nativePhrase("质量反馈提交失败。", "The quality rating could not be submitted.")
            }
        }
    }

    fun runTool(tool: NativeAiTool) {
        val token = accessToken ?: return
        if (toolRunning.isNotBlank()) return
        if (tool.name == "create_article_draft") {
            pendingDraft = null
            draftDescription = ""
            draftDialogOpen = true
            return
        }
        val input = if (tool.name == "search_visible_articles") {
            mapOf("query" to draft.trim().ifBlank { nativePhrase("最新文章", "latest articles") })
        } else {
            emptyMap()
        }
        toolDialog = NativeAiToolDialogState(tool = tool, loading = true)
        toolRunning = tool.name
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    NativeAiApi.executeTool(token, tool.name, input, conversationId)
                }
                toolDialog = NativeAiToolDialogState(tool = tool, output = result.output, loading = false)
                notice = nativePhrase("${tool.label}已完成。", "${tool.label} completed.")
            } catch (exception: Exception) {
                toolDialog = null
                error = exception.message ?: nativePhrase("AI 工具执行失败。", "The AI tool could not be completed.")
            } finally {
                toolRunning = ""
            }
        }
    }

    fun prepareDraft() {
        val token = accessToken ?: return
        if (draftDescription.trim().isBlank() || toolRunning.isNotBlank()) return
        val tool = tools.firstOrNull { it.name == "create_article_draft" }
        if (tool == null) {
            error = nativePhrase(
                "当前没有可用的文章草稿工具。",
                "The article draft tool is not available.",
            )
            return
        }
        toolRunning = tool.name
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    NativeAiApi.executeTool(
                        token,
                        tool.name,
                        mapOf("description" to draftDescription.trim(), "locale" to (session?.locale ?: "zh-CN")),
                        conversationId,
                    )
                }
                if (result.preview == null || result.confirmationToken.isNullOrBlank()) {
                    throw IllegalStateException(
                        nativePhrase(
                            "AI 没有返回可确认的草稿预览。",
                            "AI did not return a draft preview that can be confirmed.",
                        ),
                    )
                }
                pendingDraft = NativeAiPendingDraft(result.invocationId, result.confirmationToken, result.preview)
                notice = nativePhrase(
                    "草稿预览已准备，请确认后才会写入文章。",
                    "The draft preview is ready. Confirm it before the article is created.",
                )
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("草稿准备失败。", "The draft preview could not be prepared.")
            } finally {
                toolRunning = ""
            }
        }
    }

    fun confirmDraft() {
        val token = accessToken ?: return
        val draftToConfirm = pendingDraft ?: return
        if (toolRunning.isNotBlank()) return
        toolRunning = "confirm"
        scope.launch {
            try {
                val article = withContext(Dispatchers.IO) {
                    NativeAiApi.confirmDraft(
                        token,
                        draftToConfirm.invocationId,
                        draftToConfirm.confirmationToken,
                        draftToConfirm.selectedSuggestedTags,
                    )
                }
                pendingDraft = null
                draftDialogOpen = false
                draftDescription = ""
                conversations = withContext(Dispatchers.IO) { NativeAiApi.listConversations(token) }
                notice = nativePhrase(
                    "文章草稿“${article.title}”已创建。",
                    "The article draft \"${article.title}\" was created.",
                )
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("草稿确认失败。", "The article draft could not be created.")
            } finally {
                toolRunning = ""
            }
        }
    }

    LaunchedEffect(accessToken) {
        val token = accessToken
        if (token.isNullOrBlank()) {
            loading = false
            error = nativePhrase("请先登录后使用 AI 助手。", "Sign in to use the AI assistant.")
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val loaded = withContext(Dispatchers.IO) {
                NativeAiApi.listConversations(token) to NativeAiApi.listTools(token)
            }
            conversations = loaded.first
            tools = loaded.second
            mediaCapabilities = withContext(Dispatchers.IO) {
                runCatching { NativeAiApi.listMediaCapabilities(token) }.getOrDefault(emptyList())
            }
            mediaTasks = withContext(Dispatchers.IO) {
                runCatching { NativeAiApi.listMediaTasks(token) }.getOrDefault(emptyList())
            }
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase("AI 助手加载失败。", "The AI assistant could not be loaded.")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(mediaDialogTask?.summary?.id, mediaDialogTask?.summary?.hasStoredImage, accessToken) {
        mediaImage = null
        val token = accessToken
        val task = mediaDialogTask
        if (token.isNullOrBlank() || task?.summary?.capability != "image_generation" || !task.summary.hasStoredImage) return@LaunchedEffect
        mediaImage = withContext(Dispatchers.IO) {
            NativeOfflineCache.loadMediaFile(
                context = context,
                url = nativeMediaUrl("/api/ai/media-tasks/${task.summary.id}/image"),
                accessToken = token,
                cacheOwner = "ai-image-${NativeOfflineOwner.forSession(session)}",
                originalName = "ai-result.png",
                mimeType = "image/png",
            )?.let { file -> BitmapFactory.decodeFile(file.absolutePath) }
        }
    }

    LaunchedEffect(messageListState) {
        snapshotFlow {
            val layout = messageListState.layoutInfo
            shouldFollowNativeAiMessages(
                layout.visibleItemsInfo.lastOrNull()?.index,
                layout.totalItemsCount,
            )
        }.collect { atBottom ->
            followLatestMessages = atBottom
        }
    }

    LaunchedEffect(messages.size, sending) {
        val shouldScroll = pendingMessageScroll ?: followLatestMessages
        pendingMessageScroll = null
        if (messages.isNotEmpty() && shouldScroll) {
            messageListState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(nativePhrase("AI 助手", "AI assistant"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(nativePhrase("权限过滤已启用", "Permission filtering enabled"), color = HlovetUi.muted, fontSize = 11.sp)
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"))
                }
            },
            actions = {
                IconButton(onClick = ::newConversation) {
                    Icon(Icons.Filled.Add, contentDescription = nativePhrase("新对话", "New conversation"), tint = HlovetUi.accent)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        if (loading) {
            NativeAiStatusCard(nativePhrase("正在加载 AI 助手…", "Loading AI assistant..."), null)
        } else if (session == null) {
            NativeAiStatusCard(
                nativePhrase("请先登录", "Sign in required"),
                nativePhrase("登录后才能使用站内 AI 助手。", "Sign in to use the site AI assistant."),
            )
        } else {
            if (!error.isNullOrBlank()) {
                Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            if (!notice.isNullOrBlank()) {
                Text(notice!!, color = HlovetUi.accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            if (conversations.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp, max = 56.dp),
                ) {
                    item {
                        AssistChip(
                            onClick = ::newConversation,
                            label = { Text(nativePhrase("新对话", "New conversation"), fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(15.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = HlovetUi.accentSoft),
                        )
                    }
                    items(conversations) { conversation ->
                        FilterChip(
                            selected = conversation.id == conversationId,
                            onClick = { loadConversation(conversation.id) },
                            label = { Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) },
                            modifier = Modifier.widthIn(max = 190.dp),
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = HlovetUi.accentSoft),
                        )
                    }
                }
            }
            GlassCard(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth(),
                shape = HlovetUi.rowShape,
            ) {
                Column {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Tune, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(nativePhrase("受控工具", "Controlled tools"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            nativePhrase("只读取权限范围内的数据", "Only data within your permissions is read"),
                            color = HlovetUi.muted,
                            fontSize = 11.sp,
                        )
                    }
                    LazyRow(
                        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(tools) { tool ->
                            AssistChip(
                                onClick = { runTool(tool) },
                                enabled = toolRunning.isBlank(),
                                label = { Text(tool.label, fontSize = 11.sp, maxLines = 1) },
                                leadingIcon = {
                                    Icon(if (tool.readOnly) Icons.Filled.Search else Icons.Filled.Create, contentDescription = null, modifier = Modifier.size(14.dp))
                                },
                            )
                        }
                    }
                }
            }
            NativeAiMediaSection(
                capabilities = mediaCapabilities,
                tasks = mediaTasks,
                onOpen = ::openMedia,
                onOpenTask = ::openMediaTask,
            )
            LazyColumn(
                state = messageListState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        GlassCard(Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
                            Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(30.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    nativePhrase("问问站内内容或你的个人数据", "Ask about site content or your personal data"),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    nativePhrase(
                                        "例如：总结当前文章、查询我的积分、列出我的订阅。",
                                        "For example: summarize an article, check my points, or list my subscriptions.",
                                    ),
                                    color = HlovetUi.muted,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 5.dp),
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(messages) { _, message ->
                        NativeAiMessageBubble(message = message, onRate = ::rateMessage, onOpenSource = onOpenSource)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    enabled = !sending,
                    minLines = 1,
                    maxLines = 4,
                    placeholder = { Text(nativePhrase("输入站内问题…", "Ask about this site..."), fontSize = 13.sp) },
                    colors = nativeAiFieldColors(),
                )
                IconButton(
                    onClick = ::sendMessage,
                    enabled = draft.trim().isNotBlank() && !sending,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = nativePhrase("发送", "Send"),
                        tint = if (draft.trim().isNotBlank() && !sending) HlovetUi.accent else HlovetUi.muted,
                    )
                }
            }
        }
    }

    toolDialog?.let { state ->
        HlovetAlertDialog(
            onDismissRequest = { if (!state.loading) toolDialog = null },
            title = { Text(state.tool.label, fontSize = 17.sp) },
            text = {
                Box(Modifier.heightIn(min = 80.dp, max = 440.dp).verticalScroll(rememberScrollState())) {
                    if (state.loading) {
                        Text(nativePhrase("正在读取结果…", "Loading result..."), color = HlovetUi.muted, fontSize = 13.sp)
                    } else {
                        NativeAiValue(value = state.output, depth = 0)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { toolDialog = null }, enabled = !state.loading) {
                    Text(nativePhrase("关闭", "Close"))
                }
            },
        )
    }

    if (draftDialogOpen) {
        HlovetAlertDialog(
            onDismissRequest = { if (toolRunning != "confirm") draftDialogOpen = false },
            title = { Text(nativePhrase("准备文章草稿", "Prepare article draft"), fontSize = 17.sp) },
            text = {
                Column(Modifier.heightIn(min = 120.dp, max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (pendingDraft == null) {
                        Text(
                            nativePhrase(
                                "描述主题、受众和重点，AI 会先生成预览。确认后才会写入文章。",
                                "Describe the topic, audience, and key points. AI creates a preview before saving anything.",
                            ),
                            color = HlovetUi.muted,
                            fontSize = 12.sp,
                        )
                        OutlinedTextField(
                            value = draftDescription,
                            onValueChange = { draftDescription = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 6,
                            maxLines = 12,
                            label = { Text(nativePhrase("文章描述", "Article brief")) },
                            colors = nativeAiFieldColors(),
                        )
                    } else {
                        val preview = pendingDraft!!.preview
                        Text(
                            nativePhrase("草稿预览，确认后才会写入文章", "Draft preview. Confirm before creating the article."),
                            color = HlovetUi.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        NativeAiDraftField(nativePhrase("标题", "Title"), preview.title)
                        NativeAiDraftField(nativePhrase("摘要", "Summary"), preview.summary)
                        NativeAiDraftField(
                            nativePhrase("分类", "Category"),
                            preview.category.ifBlank { nativePhrase("未分类", "Uncategorized") },
                        )
                        NativeAiDraftField(
                            nativePhrase("已配置标签", "Configured tags"),
                            preview.tags.ifBlank { nativePhrase("无", "None") },
                        )
                        if (preview.suggestedTags.isNotEmpty()) {
                            Text(
                                nativePhrase("可选的新标签（默认不保存）", "Optional new tags (not saved by default)"),
                                color = HlovetUi.muted,
                                fontSize = 12.sp,
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(preview.suggestedTags) { tag ->
                                    val selected = pendingDraft!!.selectedSuggestedTags.contains(tag)
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            val current = pendingDraft
                                            if (current != null) {
                                                val next = if (selected) current.selectedSuggestedTags - tag else current.selectedSuggestedTags + tag
                                                pendingDraft = current.copy(selectedSuggestedTags = next)
                                            }
                                        },
                                        label = { Text("#$tag", fontSize = 11.sp) },
                                        leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(13.dp)) }) else null,
                                    )
                                }
                            }
                        }
                        NativeAiDraftField(nativePhrase("正文", "Body"), preview.content)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { draftDialogOpen = false }, enabled = toolRunning != "confirm") {
                    Text(nativePhrase("关闭", "Close"))
                }
            },
            confirmButton = {
                if (pendingDraft == null) {
                    TextButton(onClick = ::prepareDraft, enabled = draftDescription.trim().isNotBlank() && toolRunning.isBlank()) {
                        Text(
                            if (toolRunning == "create_article_draft") {
                                nativePhrase("生成中…", "Generating...")
                            } else {
                                nativePhrase("生成预览", "Generate preview")
                            },
                        )
                    }
                } else {
                    TextButton(onClick = ::confirmDraft, enabled = toolRunning.isBlank()) {
                        Text(
                            if (toolRunning == "confirm") nativePhrase("创建中…", "Creating...")
                            else nativePhrase("确认创建", "Confirm and create"),
                        )
                    }
                }
            },
        )
    }

    mediaDialogKind?.let { kind ->
        val task = mediaDialogTask
        val label = nativeAiMediaLabel(kind)
        HlovetAlertDialog(
            onDismissRequest = { if (!mediaBusy) closeMediaDialog() },
            title = { Text(label, fontSize = 17.sp) },
            text = {
                Box(
                    Modifier
                        .heightIn(min = 110.dp, max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    when {
                        mediaDialogLoading -> Text(
                            nativePhrase("正在读取任务…", "Loading task..."),
                            color = HlovetUi.muted,
                            fontSize = 13.sp,
                        )
                        task != null -> NativeAiMediaTaskResult(
                            task = task,
                            image = mediaImage,
                            onOpenUrl = ::openMediaResultUrl,
                        )
                        kind == "image_generation" -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    nativePhrase(
                                        "描述希望生成的图片，结果会先保存到当前账号的媒体任务中。",
                                        "Describe the image to generate. The result is saved to your media tasks first.",
                                    ),
                                    color = HlovetUi.muted,
                                    fontSize = 12.sp,
                                )
                                OutlinedTextField(
                                    value = mediaPrompt,
                                    onValueChange = { mediaPrompt = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 5,
                                    maxLines = 10,
                                    label = { Text(nativePhrase("图片描述", "Image prompt")) },
                                    colors = nativeAiFieldColors(),
                                )
                                Text(nativePhrase("尺寸", "Size"), color = HlovetUi.muted, fontSize = 11.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("1024x1024", "1024x1792", "1792x1024").forEach { size ->
                                        FilterChip(
                                            selected = mediaSize == size,
                                            onClick = { mediaSize = size },
                                            label = { Text(size.replace("x", " × "), fontSize = 11.sp) },
                                        )
                                    }
                                }
                            }
                        }
                        else -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val capability = mediaCapabilities.firstOrNull { it.capability == kind }
                                Text(
                                    if (kind == "ocr") {
                                        nativePhrase("选择图片后提取其中的文字。", "Choose an image to extract its text.")
                                    } else {
                                        nativePhrase("选择音频文件后转换为文字。", "Choose an audio file to transcribe it.")
                                    },
                                    color = HlovetUi.muted,
                                    fontSize = 12.sp,
                                )
                                if (kind == "ocr") {
                                    OutlinedTextField(
                                        value = mediaPrompt,
                                        onValueChange = { mediaPrompt = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                        maxLines = 4,
                                        label = { Text(nativePhrase("识别提示（可选）", "Recognition hint (optional)")) },
                                        colors = nativeAiFieldColors(),
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        mediaPicker.launch(arrayOf(if (kind == "ocr") "image/*" else "audio/*"))
                                    },
                                    enabled = !mediaBusy,
                                ) {
                                    Icon(
                                        if (kind == "ocr") Icons.Filled.Image else Icons.Filled.Mic,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        if (kind == "ocr") nativePhrase("选择图片", "Choose image")
                                        else nativePhrase("选择音频", "Choose audio"),
                                    )
                                }
                                capability?.let {
                                    Text(
                                        nativePhrase(
                                            "单文件上限：${nativeAiFormatBytes(it.maxInputBytes)}",
                                            "File limit: ${nativeAiFormatBytes(it.maxInputBytes)}",
                                        ),
                                        color = HlovetUi.muted,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = ::closeMediaDialog, enabled = !mediaBusy) {
                    Text(nativePhrase("关闭", "Close"))
                }
            },
            confirmButton = {
                when {
                    task != null -> TextButton(onClick = ::closeMediaDialog) { Text(nativePhrase("完成", "Done")) }
                    kind == "image_generation" -> TextButton(
                        onClick = ::runImageGeneration,
                        enabled = mediaPrompt.trim().isNotBlank() && !mediaBusy && (mediaCapabilities.firstOrNull { it.capability == kind }?.available == true),
                    ) {
                        Text(if (mediaBusy) nativePhrase("生成中…", "Generating...") else nativePhrase("开始生成", "Generate"))
                    }
                    else -> TextButton(onClick = ::closeMediaDialog) {
                        Text(nativePhrase("选择文件后处理", "Choose a file to process"))
                    }
                }
            },
        )
    }
}

@Composable
private fun NativeAiMediaSection(
    capabilities: List<NativeAiMediaCapability>,
    tasks: List<NativeAiMediaTaskSummary>,
    onOpen: (String) -> Unit,
    onOpenTask: (NativeAiMediaTaskSummary) -> Unit,
) {
    val capabilityMap = capabilities.associateBy { it.capability }
    GlassCard(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth(),
        shape = HlovetUi.rowShape,
    ) {
        Column(Modifier.padding(bottom = 8.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(nativePhrase("媒体工具", "Media tools"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text(
                    nativePhrase("未配置的能力保持关闭", "Unconfigured capabilities stay disabled"),
                    color = HlovetUi.muted,
                    fontSize = 11.sp,
                )
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                listOf("ocr", "transcription", "image_generation").forEach { kind ->
                    val capability = capabilityMap[kind]
                    item {
                        AssistChip(
                            onClick = { onOpen(kind) },
                            enabled = capability?.available == true,
                            label = { Text(nativeAiMediaLabel(kind), fontSize = 11.sp, maxLines = 1) },
                            leadingIcon = {
                                Icon(
                                    when (kind) {
                                        "ocr" -> Icons.Filled.Image
                                        "transcription" -> Icons.Filled.Mic
                                        else -> Icons.Filled.AutoAwesome
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                }
            }
            if (tasks.isNotEmpty()) {
                Text(
                    nativePhrase("最近任务", "Recent tasks"),
                    color = HlovetUi.muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                tasks.take(4).forEach { task ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenTask(task) }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when (task.capability) {
                                "ocr" -> Icons.Filled.Image
                                "transcription" -> Icons.Filled.Mic
                                else -> Icons.Filled.AutoAwesome
                            },
                            contentDescription = null,
                            tint = HlovetUi.muted,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Text(nativeAiMediaLabel(task.capability), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(task.createdAt.replace('T', ' ').take(16), color = HlovetUi.muted, fontSize = 10.sp)
                        }
                        Text(nativeAiMediaStatus(task.status), color = nativeAiMediaStatusColor(task.status), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeAiMediaTaskResult(
    task: NativeAiMediaTaskDetail,
    image: Bitmap?,
    onOpenUrl: (String) -> Unit,
) {
    val summary = task.summary
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(nativeAiMediaStatus(summary.status), color = nativeAiMediaStatusColor(summary.status), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (!summary.errorSummary.isNullOrBlank()) {
            Text(summary.errorSummary, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        if (!task.revisedPrompt.isNullOrBlank()) {
            Text(nativePhrase("优化后的描述", "Revised prompt"), color = HlovetUi.muted, fontSize = 11.sp)
            Text(task.revisedPrompt, fontSize = 12.sp)
        }
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = nativePhrase("AI 生成图片", "AI-generated image"),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
            )
        } else if (summary.capability == "image_generation" && !summary.resultUrl.isNullOrBlank()) {
            TextButton(onClick = { onOpenUrl(summary.resultUrl!!) }) {
                Text(nativePhrase("打开图片结果", "Open image result"))
            }
        }
        if (!task.resultText.isNullOrBlank()) {
            Text(nativePhrase("处理结果", "Result"), color = HlovetUi.muted, fontSize = 11.sp)
            GlassCard(Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
                Text(task.resultText, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
            }
        } else if (summary.capability != "image_generation" && summary.status == "completed") {
            Text(
                nativePhrase("服务没有返回文字结果。", "The service did not return text."),
                color = HlovetUi.muted,
                fontSize = 12.sp,
            )
        }
    }
}

private fun nativeAiMediaLabel(capability: String): String = when (capability) {
    "ocr" -> nativePhrase("图片 OCR", "Image OCR")
    "transcription" -> nativePhrase("语音转文字", "Audio transcription")
    "image_generation" -> nativePhrase("图片生成", "Image generation")
    else -> capability
}

private fun nativeAiMediaStatus(status: String): String = when (status) {
    "queued" -> nativePhrase("排队中", "Queued")
    "processing" -> nativePhrase("处理中", "Processing")
    "completed" -> nativePhrase("已完成", "Completed")
    "failed" -> nativePhrase("失败", "Failed")
    else -> status.ifBlank { nativePhrase("未知", "Unknown") }
}

private fun nativeAiMediaStatusColor(status: String): Color = when (status) {
    "completed" -> HlovetUi.accent
    "failed" -> Color(0xFFB42318)
    "processing", "queued" -> HlovetUi.secondaryAccent
    else -> HlovetUi.muted
}

private fun nativeAiFormatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun NativeAiMessageBubble(
    message: NativeAiMessage,
    onRate: (NativeAiMessage, Int) -> Unit,
    onOpenSource: (NativeAiSource) -> Unit,
) {
    val own = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
    ) {
        GlassCard(
            modifier = Modifier.widthIn(max = 340.dp),
            shape = if (own) HlovetUi.outgoingMessageShape else HlovetUi.incomingMessageShape,
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (own) Icons.Filled.Create else Icons.Filled.AutoAwesome, contentDescription = null, tint = if (own) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (own) nativePhrase("你", "You") else "AI",
                        color = HlovetUi.muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(message.content, fontSize = 13.sp, lineHeight = 20.sp)
                if (message.sources.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(nativePhrase("引用来源", "Sources"), color = HlovetUi.muted, fontSize = 11.sp)
                        message.sources.forEachIndexed { index, source ->
                            Text(
                                "[${index + 1}] ${source.title}",
                                color = HlovetUi.accent,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { onOpenSource(source) },
                            )
                        }
                    }
                }
                if (!own && message.id != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            nativePhrase("这条回答有帮助吗？", "Was this answer helpful?"),
                            color = HlovetUi.muted,
                            fontSize = 10.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onRate(message, 1) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Filled.ThumbUp,
                                contentDescription = nativePhrase("有帮助", "Helpful"),
                                tint = if (message.qualityRating == 1) HlovetUi.accent else HlovetUi.muted,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        IconButton(onClick = { onRate(message, -1) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Filled.ThumbDown,
                                contentDescription = nativePhrase("没帮助", "Not helpful"),
                                tint = if (message.qualityRating == -1) MaterialTheme.colorScheme.error else HlovetUi.muted,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeAiDraftField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = HlovetUi.muted, fontSize = 11.sp)
        Text(value.ifBlank { "-" }, fontSize = 12.sp)
    }
}

@Composable
private fun NativeAiValue(value: Any?, depth: Int) {
    if (depth > 3) {
        Text(nativePhrase("内容较深，已折叠", "Nested content collapsed"), color = HlovetUi.muted, fontSize = 12.sp)
        return
    }
    when (value) {
        null, JSONObject.NULL -> Text(nativePhrase("暂无记录", "No records"), color = HlovetUi.muted, fontSize = 12.sp)
        is JSONObject -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val keys = value.keys().asSequence().toList()
                if (keys.isEmpty()) Text(nativePhrase("暂无记录", "No records"), color = HlovetUi.muted, fontSize = 12.sp)
                keys.forEach { key ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(nativeAiFieldLabel(key), color = HlovetUi.muted, fontSize = 11.sp)
                        NativeAiValue(value.opt(key), depth + 1)
                    }
                }
            }
        }
        is JSONArray -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (value.length() == 0) Text(nativePhrase("暂无记录", "No records"), color = HlovetUi.muted, fontSize = 12.sp)
                for (index in 0 until value.length()) {
                    GlassCard(Modifier.fillMaxWidth(), shape = HlovetUi.rowShape) {
                        Column(Modifier.padding(8.dp)) {
                            Text("${index + 1}", color = HlovetUi.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            NativeAiValue(value.opt(index), depth + 1)
                        }
                    }
                }
            }
        }
        is String -> Text(value.ifBlank { "-" }, fontSize = 12.sp)
        is Number, is Boolean -> Text(value.toString(), fontSize = 12.sp)
        else -> Text(value.toString(), fontSize = 12.sp)
    }
}

@Composable
private fun NativeAiStatusCard(title: String, detail: String?) {
    GlassCard(Modifier.padding(horizontal = 20.dp, vertical = 18.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (!detail.isNullOrBlank()) Text(detail, color = HlovetUi.muted, fontSize = 12.sp)
        }
    }
}

private fun nativeAiFieldLabel(key: String): String = mapOf(
    "account" to nativePhrase("账号信息", "Account"),
    "recentLedger" to nativePhrase("近期记录", "Recent activity"),
    "count" to nativePhrase("数量", "Count"),
    "items" to nativePhrase("列表", "Items"),
    "authors" to nativePhrase("作者订阅", "Author subscriptions"),
    "topics" to nativePhrase("专题订阅", "Topic subscriptions"),
    "collections" to nativePhrase("合集订阅", "Collection subscriptions"),
    "tags" to nativePhrase("标签订阅", "Tag subscriptions"),
    "summary" to nativePhrase("汇总", "Summary"),
    "gross" to nativePhrase("总收益", "Gross earnings"),
    "pending" to nativePhrase("待结算", "Pending"),
    "settled" to nativePhrase("已结算", "Settled"),
    "refunded" to nativePhrase("已退款", "Refunded"),
    "title" to nativePhrase("标题", "Title"),
    "status" to nativePhrase("状态", "Status"),
    "updatedAt" to nativePhrase("更新时间", "Updated"),
    "publishedAt" to nativePhrase("发布时间", "Published"),
).getOrDefault(key, key)

@Composable
private fun nativeAiFieldColors() = HlovetUi.fieldColors()
