package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal data class NativeAiSource(
    val id: Int,
    val slug: String,
    val title: String,
    val author: String,
)

internal data class NativeAiMessage(
    val id: Int?,
    val role: String,
    val content: String,
    val sources: List<NativeAiSource>,
    val qualityRating: Int?,
    val createdAt: String,
)

internal data class NativeAiConversationSummary(
    val id: Int,
    val title: String,
    val messageCount: Int,
    val updatedAt: String,
)

internal data class NativeAiConversation(
    val id: Int,
    val title: String,
    val messages: List<NativeAiMessage>,
)

internal data class NativeAiTool(
    val name: String,
    val label: String,
    val labelEn: String,
    val description: String,
    val descriptionEn: String,
    val readOnly: Boolean,
    val requiresConfirmation: Boolean,
)

internal data class NativeAiChatResult(
    val conversationId: Int,
    val messageId: Int,
    val title: String,
    val text: String,
    val sources: List<NativeAiSource>,
    val durationMs: Int,
)

internal data class NativeAiArticleAssistantResult(
    val text: String,
    val operation: String,
    val provider: String,
    val model: String,
    val durationMs: Int,
    val promptTokens: Int?,
    val cachedPromptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
)

internal data class NativeAiDraftPreview(
    val title: String,
    val summary: String,
    val category: String,
    val tags: String,
    val suggestedTags: List<String>,
    val content: String,
)

internal data class NativeAiToolResult(
    val invocationId: Int,
    val status: String,
    val output: Any?,
    val confirmationToken: String?,
    val preview: NativeAiDraftPreview?,
)

internal data class NativeAiConfirmedArticle(
    val id: Int,
    val slug: String,
    val title: String,
)

internal data class NativeAiMediaCapability(
    val capability: String,
    val label: String,
    val available: Boolean,
    val maxInputBytes: Long,
    val unitName: String,
)

internal data class NativeAiMediaTaskSummary(
    val id: Int,
    val capability: String,
    val status: String,
    val prompt: String?,
    val inputMimeType: String?,
    val inputBytes: Long?,
    val resultPreview: String?,
    val resultUrl: String?,
    val hasStoredImage: Boolean,
    val errorSummary: String?,
    val createdAt: String,
    val startedAt: String?,
    val completedAt: String?,
)

internal data class NativeAiMediaTaskDetail(
    val summary: NativeAiMediaTaskSummary,
    val resultText: String?,
    val revisedPrompt: String?,
)

internal object NativeAiApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun listConversations(accessToken: String): List<NativeAiConversationSummary> {
        val response = requestJson("GET", "/ai/conversations", accessToken)
            as? JSONObject ?: throw IllegalStateException("AI 对话列表格式无法识别。")
        val items = response.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(
                    NativeAiConversationSummary(
                        id = item.optInt("id"),
                        title = item.optString("title").ifBlank { "新对话" },
                        messageCount = item.optJSONObject("_count")?.optInt("messages") ?: 0,
                        updatedAt = item.optString("updatedAt"),
                    ),
                )
            }
        }
    }

    fun getConversation(accessToken: String, id: Int): NativeAiConversation {
        val response = requestJson("GET", "/ai/conversations/$id", accessToken)
            as? JSONObject ?: throw IllegalStateException("AI 对话格式无法识别。")
        val messages = response.optJSONArray("messages") ?: JSONArray()
        return NativeAiConversation(
            id = response.optInt("id", id),
            title = response.optString("title").ifBlank { "新对话" },
            messages = messages.toMessages(),
        )
    }

    fun listTools(accessToken: String): List<NativeAiTool> {
        val response = requestJson("GET", "/ai/tools", accessToken) as? JSONArray
            ?: throw IllegalStateException("AI 工具列表格式无法识别。")
        return buildList {
            for (index in 0 until response.length()) {
                val item = response.optJSONObject(index) ?: continue
                add(
                    NativeAiTool(
                        name = item.optString("name"),
                        label = item.optString("label").ifBlank { item.optString("name") },
                        labelEn = item.optString("labelEn").ifBlank { item.optString("label") },
                        description = item.optString("description"),
                        descriptionEn = item.optString("descriptionEn").ifBlank { item.optString("description") },
                        readOnly = item.optBoolean("readOnly"),
                        requiresConfirmation = item.optBoolean("requiresConfirmation"),
                    ),
                )
            }
        }.filter { it.name.isNotBlank() }
    }

    fun chat(
        accessToken: String,
        conversationId: Int?,
        message: String,
        locale: String = "zh-CN",
    ): NativeAiChatResult {
        val payload = JSONObject()
            .put("message", message)
            .put("locale", if (locale == "en-US") "en-US" else "zh-CN")
        if (conversationId != null) payload.put("conversationId", conversationId)
        val response = requestJson("POST", "/ai/chat", accessToken, payload) as? JSONObject
            ?: throw IllegalStateException("AI 回答格式无法识别。")
        return NativeAiChatResult(
            conversationId = response.optInt("conversationId"),
            messageId = response.optInt("messageId"),
            title = response.optString("title").ifBlank { "新对话" },
            text = response.optString("text"),
            sources = response.optJSONArray("sources").toSources(),
            durationMs = response.optInt("durationMs"),
        )
    }

    fun articleAssistant(
        accessToken: String,
        operation: String,
        title: String,
        content: String,
        selectedText: String,
        category: String,
        tags: String,
        locale: String = "zh-CN",
    ): NativeAiArticleAssistantResult {
        val response = requestJson(
            "POST",
            "/ai/article-assistant",
            accessToken,
            JSONObject()
                .put("operation", operation)
                .put("title", title)
                .put("content", content)
                .put("selectedText", selectedText)
                .put("category", category)
                .put("tags", tags)
                .put("locale", if (locale == "en-US") "en-US" else "zh-CN"),
        ) as? JSONObject ?: throw IllegalStateException("文章助手结果格式无法识别。")
        val usage = response.optJSONObject("usage")
        return NativeAiArticleAssistantResult(
            text = response.optString("text"),
            operation = response.optString("operation").ifBlank { operation },
            provider = response.optString("provider"),
            model = response.optString("model"),
            durationMs = response.optInt("durationMs"),
            promptTokens = usage?.optionalInt("promptTokens"),
            cachedPromptTokens = usage?.optionalInt("cachedPromptTokens"),
            completionTokens = usage?.optionalInt("completionTokens"),
            totalTokens = usage?.optionalInt("totalTokens"),
        )
    }

    fun sendQualityFeedback(accessToken: String, conversationId: Int, messageId: Int, rating: Int) {
        requestJson(
            "POST",
            "/ai/quality-feedback",
            accessToken,
            JSONObject()
                .put("conversationId", conversationId)
                .put("messageId", messageId)
                .put("rating", rating.coerceIn(-1, 1)),
        )
    }

    fun executeTool(
        accessToken: String,
        name: String,
        input: Map<String, Any?> = emptyMap(),
        conversationId: Int? = null,
    ): NativeAiToolResult {
        val payload = JSONObject().put("input", JSONObject(input))
        if (conversationId != null) payload.put("conversationId", conversationId)
        val response = requestJson("POST", "/ai/tools/${java.net.URLEncoder.encode(name, "UTF-8")}", accessToken, payload) as? JSONObject
            ?: throw IllegalStateException("AI 工具结果格式无法识别。")
        return NativeAiToolResult(
            invocationId = response.optInt("invocationId"),
            status = response.optString("status"),
            output = response.opt("output").takeUnless { it == JSONObject.NULL },
            confirmationToken = response.optString("confirmationToken").takeIf { it.isNotBlank() },
            preview = response.optJSONObject("preview")?.toDraftPreview(),
        )
    }

    fun confirmDraft(
        accessToken: String,
        invocationId: Int,
        confirmationToken: String,
        selectedSuggestedTags: List<String>,
    ): NativeAiConfirmedArticle {
        val response = requestJson(
            "POST",
            "/ai/tool-invocations/$invocationId/confirm",
            accessToken,
            JSONObject()
                .put("confirmationToken", confirmationToken)
                .put("selectedSuggestedTags", JSONArray(selectedSuggestedTags)),
        ) as? JSONObject ?: throw IllegalStateException("文章草稿确认结果格式无法识别。")
        val article = response.optJSONObject("article")
            ?: throw IllegalStateException("文章草稿创建结果缺少文章信息。")
        return NativeAiConfirmedArticle(
            id = article.optInt("id"),
            slug = article.optString("slug"),
            title = article.optString("title").ifBlank { "未命名文章" },
        )
    }

    fun listMediaCapabilities(accessToken: String): List<NativeAiMediaCapability> {
        val response = requestJson("GET", "/ai/media-capabilities", accessToken) as? JSONObject
            ?: throw IllegalStateException("AI 媒体能力格式无法识别。")
        val items = response.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val capability = item.optString("capability")
                if (capability.isBlank()) continue
                add(
                    NativeAiMediaCapability(
                        capability = capability,
                        label = item.optJSONObject("label")?.optString("zh").orEmpty().ifBlank { capability },
                        available = item.optBoolean("available"),
                        maxInputBytes = item.optLong("maxInputBytes"),
                        unitName = item.optString("unitName"),
                    ),
                )
            }
        }
    }

    fun listMediaTasks(accessToken: String): List<NativeAiMediaTaskSummary> {
        val response = requestJson("GET", "/ai/media-tasks", accessToken) as? JSONObject
            ?: throw IllegalStateException("AI 媒体任务格式无法识别。")
        return response.optJSONArray("items").toMediaTaskSummaries()
    }

    fun getMediaTask(accessToken: String, id: Int): NativeAiMediaTaskDetail {
        val response = requestJson("GET", "/ai/media-tasks/$id", accessToken) as? JSONObject
            ?: throw IllegalStateException("AI 媒体任务详情格式无法识别。")
        return response.toMediaTaskDetail()
    }

    fun processOcr(accessToken: String, file: NativeUploadFile, prompt: String?): NativeAiMediaTaskDetail {
        val response = requestMultipart(
            path = "/ai/media/ocr",
            accessToken = accessToken,
            file = file,
            fields = prompt?.trim()?.takeIf { it.isNotBlank() }?.let { mapOf("prompt" to it) }.orEmpty(),
        )
        return response.toMediaTaskDetail()
    }

    fun processTranscription(accessToken: String, file: NativeUploadFile): NativeAiMediaTaskDetail {
        return requestMultipart("/ai/media/transcription", accessToken, file).toMediaTaskDetail()
    }

    fun generateImage(accessToken: String, prompt: String, size: String): NativeAiMediaTaskDetail {
        val response = requestJson(
            "POST",
            "/ai/media/image",
            accessToken,
            JSONObject().put("prompt", prompt.trim()).put("size", size),
        ) as? JSONObject ?: throw IllegalStateException("AI 图片任务格式无法识别。")
        return response.toMediaTaskDetail()
    }

    private fun JSONArray?.toSources(): List<NativeAiSource> = buildList {
        if (this@toSources == null) return@buildList
        for (index in 0 until this@toSources!!.length()) {
            val item = this@toSources!!.optJSONObject(index) ?: continue
            val id = item.optInt("id")
            val slug = item.optString("slug")
            if (id > 0 && slug.isNotBlank()) {
                add(
                    NativeAiSource(
                        id = id,
                        slug = slug,
                        title = item.optString("title").ifBlank { "站内文章" },
                        author = item.optString("author"),
                    ),
                )
            }
        }
    }

    private fun JSONArray.toMessages(): List<NativeAiMessage> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(
                NativeAiMessage(
                    id = item.optInt("id").takeIf { it > 0 },
                    role = item.optString("role", "assistant"),
                    content = item.optString("content"),
                    sources = item.optJSONArray("sources").toSources(),
                    qualityRating = item.optInt("qualityRating").takeIf { it == -1 || it == 1 },
                    createdAt = item.optString("createdAt"),
                ),
            )
        }
    }

    private fun JSONObject.toDraftPreview(): NativeAiDraftPreview = NativeAiDraftPreview(
        title = optString("title"),
        summary = optString("summary"),
        category = optString("category"),
        tags = optString("tags"),
        suggestedTags = optJSONArray("suggestedTags").toStringList(),
        content = optString("content"),
    )

    private fun JSONObject.optionalInt(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONArray?.toStringList(): List<String> = buildList {
        if (this@toStringList == null) return@buildList
        for (index in 0 until this@toStringList!!.length()) {
            this@toStringList!!.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun JSONArray?.toMediaTaskSummaries(): List<NativeAiMediaTaskSummary> = buildList {
        if (this@toMediaTaskSummaries == null) return@buildList
        for (index in 0 until this@toMediaTaskSummaries!!.length()) {
            this@toMediaTaskSummaries!!.optJSONObject(index)?.toMediaTaskSummary()?.let(::add)
        }
    }

    private fun JSONObject.toMediaTaskSummary(): NativeAiMediaTaskSummary = NativeAiMediaTaskSummary(
        id = optInt("id"),
        capability = optString("capability"),
        status = optString("status"),
        prompt = optString("prompt").takeIf { it.isNotBlank() },
        inputMimeType = optString("inputMimeType").takeIf { it.isNotBlank() },
        inputBytes = if (has("inputBytes") && !isNull("inputBytes")) optLong("inputBytes") else null,
        resultPreview = optString("resultPreview").takeIf { it.isNotBlank() },
        resultUrl = optString("resultUrl").takeIf { it.isNotBlank() },
        hasStoredImage = optBoolean("hasStoredImage"),
        errorSummary = optString("errorSummary").takeIf { it.isNotBlank() },
        createdAt = optString("createdAt"),
        startedAt = optString("startedAt").takeIf { it.isNotBlank() },
        completedAt = optString("completedAt").takeIf { it.isNotBlank() },
    )

    private fun JSONObject.toMediaTaskDetail(): NativeAiMediaTaskDetail = NativeAiMediaTaskDetail(
        summary = toMediaTaskSummary(),
        resultText = optString("resultText").takeIf { it.isNotBlank() },
        revisedPrompt = optString("revisedPrompt").takeIf { it.isNotBlank() },
    )

    private fun requestJson(method: String, path: String, accessToken: String, body: JSONObject? = null): Any {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            doInput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(parseError(text, status))
            if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestMultipart(
        path: String,
        accessToken: String,
        file: NativeUploadFile,
        fields: Map<String, String> = emptyMap(),
    ): JSONObject {
        val boundary = "----HlovetAndroidAi${System.currentTimeMillis()}"
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            connection.outputStream.buffered().use { output ->
                fields.forEach { (name, value) ->
                    output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                    output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.write(value.toByteArray(Charsets.UTF_8))
                    output.write("\r\n".toByteArray(Charsets.UTF_8))
                }
                val safeName = file.name.replace("\"", "").replace("\r", "").replace("\n", "")
                output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                output.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n".toByteArray(Charsets.UTF_8))
                output.write("Content-Type: ${file.mimeType.ifBlank { "application/octet-stream" }}\r\n\r\n".toByteArray(Charsets.UTF_8))
                output.write(file.bytes)
                output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(parseError(text, status))
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseError(body: String, status: Int): String {
        val message = runCatching {
            val value: Any = if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
            when (value) {
                is JSONObject -> when (val raw = value.opt("message")) {
                    is JSONArray -> raw.valuesAsStrings().joinToString("；")
                    else -> raw?.toString().orEmpty()
                }
                is JSONArray -> value.valuesAsStrings().joinToString("；")
                else -> value.toString()
            }
        }.getOrNull().orEmpty()
        return message.ifBlank { "AI 请求失败（HTTP $status）。" }
    }

    private fun JSONArray.valuesAsStrings(): List<String> = buildList {
        for (index in 0 until length()) add(opt(index).toString())
    }
}
