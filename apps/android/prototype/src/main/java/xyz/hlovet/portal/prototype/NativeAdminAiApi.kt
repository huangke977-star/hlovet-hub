package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class NativeAiPricingPreset(
    val provider: String,
    val model: String,
    val label: String,
    val currency: String,
    val input: Long,
    val cachedInput: Long,
    val output: Long,
    val note: String,
)

internal data class NativeAiResourceRecommendation(
    val cpuCores: Int,
    val totalMemoryMiB: Int,
    val freeMemoryMiB: Int,
    val globalConcurrency: Int,
    val userConcurrency: Int,
    val maxGlobalConcurrency: Int,
    val maxUserConcurrency: Int,
)

internal data class NativeAiAdminConfig(
    val enabled: Boolean,
    val provider: String,
    val baseUrl: String,
    val model: String,
    val apiKeyConfigured: Boolean,
    val fallbackEnabled: Boolean,
    val fallbackProvider: String,
    val fallbackBaseUrl: String,
    val fallbackModel: String,
    val fallbackApiKeyConfigured: Boolean,
    val globalConcurrency: Int,
    val userConcurrency: Int,
    val maxOutputTokens: Int,
    val requestTimeoutSeconds: Int,
    val dailyRequestLimit: Int,
    val billingCurrency: String,
    val inputCost: Long,
    val cachedInputCost: Long,
    val outputCost: Long,
    val fallbackInputCost: Long,
    val fallbackCachedInputCost: Long,
    val fallbackOutputCost: Long,
    val ragEnabled: Boolean,
    val ragTopK: Int,
    val contextMaxMessages: Int,
    val contextSummaryThreshold: Int,
    val contextRetentionDays: Int,
    val qualityEvaluationEnabled: Boolean,
    val pricingPresets: List<NativeAiPricingPreset>,
    val recommendation: NativeAiResourceRecommendation,
    val encryptionConfigured: Boolean,
    val updatedAt: String,
)

internal data class NativeAiCapability(
    val capability: String,
    val labelZh: String,
    val labelEn: String,
    val enabled: Boolean,
    val provider: String,
    val baseUrl: String,
    val model: String,
    val apiKeyConfigured: Boolean,
    val fallbackEnabled: Boolean,
    val fallbackProvider: String,
    val fallbackBaseUrl: String,
    val fallbackModel: String,
    val fallbackApiKeyConfigured: Boolean,
    val globalConcurrency: Int,
    val userConcurrency: Int,
    val requestTimeoutSeconds: Int,
    val dailyRequestLimit: Int,
    val monthlyBudget: Long,
    val billingCurrency: String,
    val inputCost: Long,
    val cachedInputCost: Long,
    val outputCost: Long,
    val fallbackInputCost: Long,
    val fallbackCachedInputCost: Long,
    val fallbackOutputCost: Long,
    val unitCost: Long,
    val unitName: String,
    val maxInputBytes: Long,
    val officialPricingNote: String,
    val pricingPresets: List<NativeAiPricingPreset>,
    val updatedAt: String,
)

internal data class NativeAiModelOption(val id: String, val label: String)
internal data class NativeAiInvocation(
    val id: Int, val operation: String, val provider: String, val model: String, val status: String,
    val promptTokens: Long?, val cachedPromptTokens: Long?, val completionTokens: Long?, val totalTokens: Long?,
    val durationMs: Int, val cost: Long?, val error: String, val username: String, val nickname: String, val createdAt: String,
)
internal data class NativeAiInvocations(
    val todayRequests: Int, val todayTokens: Long, val todayCached: Long, val cacheHitRate: Double, val todayCost: Long,
    val logs: List<NativeAiInvocation>,
)
internal data class NativeAiToolAudit(val id: Int, val toolName: String, val status: String, val requiresConfirmation: Boolean, val username: String, val nickname: String, val createdAt: String, val completedAt: String)
internal data class NativeAiUsage(
    val days: Int, val requests: Int, val tokens: Long, val cachedInputUnits: Long, val cost: Long,
    val capabilityRows: List<NativeAiUsageRow>, val feedbackTotal: Int, val helpful: Int, val unhelpful: Int, val averageRating: Double?,
)
internal data class NativeAiUsageRow(val capability: String, val requests: Int, val success: Int, val failed: Int, val successRate: Double, val tokens: Long, val cached: Long, val cost: Long)
internal data class NativeAiKnowledge(val documents: Int, val chunks: Int, val ready: Int, val keywordReady: Int, val pendingEmbedding: Int, val failed: Int, val vectors: Int, val semanticAvailable: Boolean, val keywordAvailable: Boolean)
internal data class NativeAiKnowledgeDocument(val id: Int, val title: String, val status: String, val chunks: Int, val vectors: Int, val embeddingModel: String, val error: String, val updatedAt: String, val lastIndexedAt: String = "")
internal data class NativeAiKnowledgeReindexResult(val scanned: Int, val indexed: Int, val keywordReady: Int, val pending: Int, val failed: Int, val overview: NativeAiKnowledge?)
internal data class NativeAiDocumentReindexResult(val id: Int, val removed: Boolean, val status: String)

internal object NativeAdminAiApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"
    private val baseUrl: String get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun getConfig(token: String): NativeAiAdminConfig = requestObject("/ai/admin/configuration", token).toConfig()
    fun updateConfig(token: String, config: NativeAiAdminConfig, apiKey: String, clearApiKey: Boolean, fallbackKey: String, clearFallbackKey: Boolean): NativeAiAdminConfig = requestObject("/ai/admin/configuration", token, "PATCH", config.toJson(apiKey, clearApiKey, fallbackKey, clearFallbackKey)).toConfig()
    fun testConnection(token: String): String = requestObject("/ai/admin/test-connection", token, "POST", JSONObject()).optString("message").ifBlank { "AI 连接测试成功。" }
    fun listModels(token: String, provider: String, baseUrlValue: String, apiKey: String, capability: String? = null, fallback: Boolean = false): List<NativeAiModelOption> = requestObject("/ai/admin/models", token, "POST", JSONObject().apply { put("provider", provider); put("baseUrl", baseUrlValue); if (apiKey.isNotBlank()) put("apiKey", apiKey); if (capability != null) put("capability", capability); if (fallback) put("credential", "fallback") }).optJSONArray("models").toModels()
    fun invocations(token: String): NativeAiInvocations = requestObject("/ai/admin/invocations?limit=30", token).toInvocations()
    fun tools(token: String): List<NativeAiToolAudit> = requestObject("/ai/admin/tools?limit=50", token).optJSONArray("items").toTools()
    fun capabilities(token: String): List<NativeAiCapability> = requestObject("/ai/admin/capabilities", token).optJSONArray("items").toCapabilities()
    fun updateCapability(token: String, item: NativeAiCapability, apiKey: String, clearApiKey: Boolean, fallbackKey: String, clearFallbackKey: Boolean): NativeAiCapability = requestObject("/ai/admin/capabilities/${item.capability}", token, "PATCH", item.toJson(apiKey, clearApiKey, fallbackKey, clearFallbackKey)).toCapability()
    fun testCapability(token: String, capability: String): String = requestObject("/ai/admin/capabilities/$capability/test", token, "POST", JSONObject()).optString("message").ifBlank { "能力测试成功。" }
    fun usage(token: String, days: Int = 30): NativeAiUsage = requestObject("/ai/admin/usage?days=$days", token).toUsage()
    fun knowledge(token: String): NativeAiKnowledge = requestObject("/ai/admin/knowledge", token).toKnowledge()
    fun documents(token: String): List<NativeAiKnowledgeDocument> = requestObject("/ai/admin/knowledge/documents?limit=100", token).optJSONArray("items").toDocuments()
    fun reindex(token: String, limit: Int = 50): NativeAiKnowledgeReindexResult = requestObject("/ai/admin/knowledge/reindex?limit=$limit", token, "POST", JSONObject()).let {
        NativeAiKnowledgeReindexResult(it.optInt("scanned"), it.optInt("indexed"), it.optInt("keywordReady"), it.optInt("pending"), it.optInt("failed"), it.optJSONObject("overview")?.toKnowledge())
    }
    fun reindexDocument(token: String, id: Int): NativeAiDocumentReindexResult = requestObject("/ai/admin/knowledge/documents/$id/reindex", token, "POST", JSONObject()).let {
        NativeAiDocumentReindexResult(it.optInt("id", id), it.optBoolean("removed"), it.optString("status"))
    }

    private fun NativeAiAdminConfig.toJson(apiKey: String, clear: Boolean, fallbackKey: String, clearFallback: Boolean) = JSONObject().apply {
        put("enabled", enabled); put("provider", provider); put("baseUrl", baseUrl); put("model", model); put("fallbackEnabled", fallbackEnabled); put("fallbackProvider", fallbackProvider); put("fallbackBaseUrl", fallbackBaseUrl); put("fallbackModel", fallbackModel)
        put("globalConcurrency", globalConcurrency); put("userConcurrency", userConcurrency); put("maxOutputTokens", maxOutputTokens); put("requestTimeoutSeconds", requestTimeoutSeconds); put("dailyRequestLimit", dailyRequestLimit); put("billingCurrency", billingCurrency)
        put("inputCostPerMillionMicros", inputCost); put("cachedInputCostPerMillionMicros", cachedInputCost); put("outputCostPerMillionMicros", outputCost); put("fallbackInputCostPerMillionMicros", fallbackInputCost); put("fallbackCachedInputCostPerMillionMicros", fallbackCachedInputCost); put("fallbackOutputCostPerMillionMicros", fallbackOutputCost)
        put("ragEnabled", ragEnabled); put("ragTopK", ragTopK); put("contextMaxMessages", contextMaxMessages); put("contextSummaryThreshold", contextSummaryThreshold); put("contextRetentionDays", contextRetentionDays); put("qualityEvaluationEnabled", qualityEvaluationEnabled)
        if (apiKey.isNotBlank()) put("apiKey", apiKey); if (clear) put("clearApiKey", true); if (fallbackKey.isNotBlank()) put("fallbackApiKey", fallbackKey); if (clearFallback) put("clearFallbackApiKey", true)
    }

    private fun NativeAiCapability.toJson(apiKey: String, clear: Boolean, fallbackKey: String, clearFallback: Boolean) = JSONObject().apply {
        put("enabled", enabled); put("provider", provider); put("baseUrl", baseUrl); put("model", model); put("fallbackEnabled", fallbackEnabled); put("fallbackProvider", fallbackProvider); put("fallbackBaseUrl", fallbackBaseUrl); put("fallbackModel", fallbackModel); put("globalConcurrency", globalConcurrency); put("userConcurrency", userConcurrency); put("requestTimeoutSeconds", requestTimeoutSeconds); put("dailyRequestLimit", dailyRequestLimit); put("monthlyBudgetMicros", monthlyBudget); put("billingCurrency", billingCurrency); put("inputCostPerMillionMicros", inputCost); put("cachedInputCostPerMillionMicros", cachedInputCost); put("outputCostPerMillionMicros", outputCost); put("fallbackInputCostPerMillionMicros", fallbackInputCost); put("fallbackCachedInputCostPerMillionMicros", fallbackCachedInputCost); put("fallbackOutputCostPerMillionMicros", fallbackOutputCost); put("unitCostMicros", unitCost); put("unitName", unitName); put("maxInputBytes", maxInputBytes); if (apiKey.isNotBlank()) put("apiKey", apiKey); if (clear) put("clearApiKey", true); if (fallbackKey.isNotBlank()) put("fallbackApiKey", fallbackKey); if (clearFallback) put("clearFallbackApiKey", true)
    }

    private fun requestObject(path: String, token: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 12_000; readTimeout = 40_000; setRequestProperty("Accept", "application/json"); setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT); if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") } }
        return try { if (body != null) connection.outputStream.bufferedWriter().use { it.write(body.toString()) }; val status = connection.responseCode; val stream = if (status in 200..299) connection.inputStream else connection.errorStream; val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty(); if (status !in 200..299) { val msg = runCatching { JSONObject(raw).optString("message") }.getOrNull().orEmpty(); throw IllegalStateException(msg.ifBlank { "AI 管理请求失败（HTTP $status）。" }) }; if (raw.isBlank()) JSONObject() else JSONObject(raw) } finally { connection.disconnect() }
    }

    private fun JSONObject.toConfig(): NativeAiAdminConfig {
        val rec = optJSONObject("recommendation") ?: JSONObject(); return NativeAiAdminConfig(optBoolean("enabled"), optString("provider", "custom"), optString("baseUrl"), optString("model"), optBoolean("apiKeyConfigured"), optBoolean("fallbackEnabled"), optString("fallbackProvider", "custom"), optString("fallbackBaseUrl"), optString("fallbackModel"), optBoolean("fallbackApiKeyConfigured"), optInt("globalConcurrency", 1), optInt("userConcurrency", 1), optInt("maxOutputTokens", 2048), optInt("requestTimeoutSeconds", 60), optInt("dailyRequestLimit"), optString("billingCurrency", "USD"), optLong("inputCostPerMillionMicros"), optLong("cachedInputCostPerMillionMicros"), optLong("outputCostPerMillionMicros"), optLong("fallbackInputCostPerMillionMicros"), optLong("fallbackCachedInputCostPerMillionMicros"), optLong("fallbackOutputCostPerMillionMicros"), optBoolean("ragEnabled"), optInt("ragTopK", 6), optInt("contextMaxMessages", 24), optInt("contextSummaryThreshold", 32), optInt("contextRetentionDays", 365), optBoolean("qualityEvaluationEnabled"), optJSONArray("pricingPresets").toPresets(), NativeAiResourceRecommendation(rec.optInt("cpuCores"), rec.optInt("totalMemoryMiB"), rec.optInt("freeMemoryMiB"), rec.optInt("globalConcurrency"), rec.optInt("userConcurrency"), rec.optInt("maxGlobalConcurrency"), rec.optInt("maxUserConcurrency")), optBoolean("encryptionConfigured"), optString("updatedAt"))
    }

    private fun JSONObject.toCapability(): NativeAiCapability = toCapabilityInternal()
    private fun JSONObject.toCapabilityInternal(): NativeAiCapability { val label = optJSONObject("label") ?: JSONObject(); return NativeAiCapability(optString("capability"), label.optString("zh"), label.optString("en"), optBoolean("enabled"), optString("provider", "custom"), optString("baseUrl"), optString("model"), optBoolean("apiKeyConfigured"), optBoolean("fallbackEnabled"), optString("fallbackProvider", "custom"), optString("fallbackBaseUrl"), optString("fallbackModel"), optBoolean("fallbackApiKeyConfigured"), optInt("globalConcurrency", 1), optInt("userConcurrency", 1), optInt("requestTimeoutSeconds", 60), optInt("dailyRequestLimit"), optLong("monthlyBudgetMicros"), optString("billingCurrency", "USD"), optLong("inputCostPerMillionMicros"), optLong("cachedInputCostPerMillionMicros"), optLong("outputCostPerMillionMicros"), optLong("fallbackInputCostPerMillionMicros"), optLong("fallbackCachedInputCostPerMillionMicros"), optLong("fallbackOutputCostPerMillionMicros"), optLong("unitCostMicros"), optString("unitName"), optLong("maxInputBytes"), optString("officialPricingNote"), optJSONArray("pricingPresets").toPresets(), optString("updatedAt")) }
    private fun JSONArray?.toCapabilities(): List<NativeAiCapability> = buildList { val array = this@toCapabilities ?: return@buildList; for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(it.toCapabilityInternal()) } }
    private fun JSONArray?.toModels(): List<NativeAiModelOption> = buildList { val array = this@toModels ?: return@buildList; for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(NativeAiModelOption(it.optString("id"), it.optString("label").ifBlank { it.optString("id") })) } }
    private fun JSONArray?.toPresets(): List<NativeAiPricingPreset> = buildList { val array = this@toPresets ?: return@buildList; for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(NativeAiPricingPreset(it.optString("provider"), it.optString("model"), it.optString("label"), it.optString("billingCurrency", "USD"), it.optLong("inputCostPerMillionMicros"), it.optLong("cachedInputCostPerMillionMicros"), it.optLong("outputCostPerMillionMicros"), it.optString("note"))) } }
    private fun JSONObject.toInvocations(): NativeAiInvocations { val today = optJSONObject("today") ?: JSONObject(); return NativeAiInvocations(today.optInt("requests"), today.optLong("totalTokens"), today.optLong("cachedPromptTokens"), today.optDouble("cacheHitRate"), today.optLong("estimatedCostMicros"), optJSONArray("logs").toInvocationList()) }
    private fun JSONArray?.toInvocationList(): List<NativeAiInvocation> = buildList { val a = this@toInvocationList ?: return@buildList; for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(NativeAiInvocation(it.optInt("id"), it.optString("operation"), it.optString("provider"), it.optString("model"), it.optString("status"), it.longOrNull("promptTokens"), it.longOrNull("cachedPromptTokens"), it.longOrNull("completionTokens"), it.longOrNull("totalTokens"), it.optInt("durationMs"), it.longOrNull("estimatedCostMicros"), it.optString("errorSummary"), it.optString("username"), it.optString("nickname"), it.optString("createdAt"))) } }
    private fun JSONArray?.toTools(): List<NativeAiToolAudit> = buildList { val a = this@toTools ?: return@buildList; for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(NativeAiToolAudit(it.optInt("id"), it.optString("toolName"), it.optString("status"), it.optBoolean("requiresConfirmation"), it.optString("username"), it.optString("nickname"), it.optString("createdAt"), it.optString("completedAt"))) } }
    private fun JSONObject.toUsage(): NativeAiUsage { val total = optJSONObject("total") ?: JSONObject(); val quality = optJSONObject("quality") ?: JSONObject(); return NativeAiUsage(optInt("days", 30), total.optInt("requests"), total.optLong("tokens"), total.optLong("cachedInputUnits"), total.optLong("estimatedCostMicros"), optJSONArray("byCapability").toUsageRows(), quality.optInt("feedbackTotal"), quality.optInt("helpful"), quality.optInt("unhelpful"), quality.optDouble("averageRating").takeUnless { it.isNaN() }) }
    private fun JSONArray?.toUsageRows(): List<NativeAiUsageRow> = buildList { val a = this@toUsageRows ?: return@buildList; for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(NativeAiUsageRow(it.optString("capability"), it.optInt("requests"), it.optInt("success"), it.optInt("failed"), it.optDouble("successRate"), it.optLong("tokens"), it.optLong("cachedInputUnits"), it.optLong("estimatedCostMicros"))) } }
    private fun JSONObject.toKnowledge() = NativeAiKnowledge(optInt("documents"), optInt("chunks"), optInt("ready"), optInt("keywordReady"), optInt("pendingEmbedding"), optInt("failed"), optInt("vectors"), optBoolean("semanticSearchAvailable"), optBoolean("keywordSearchAvailable"))
    private fun JSONArray?.toDocuments(): List<NativeAiKnowledgeDocument> = buildList { val a = this@toDocuments ?: return@buildList; for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(NativeAiKnowledgeDocument(it.optInt("id"), it.optString("title"), it.optString("status"), it.optInt("chunkCount"), it.optInt("vectorCount"), it.textOrEmpty("embeddingModel"), it.textOrEmpty("errorSummary"), it.textOrEmpty("updatedAt"), it.textOrEmpty("lastIndexedAt"))) } }
    private fun JSONObject.textOrEmpty(key: String): String = if (isNull(key)) "" else optString(key)
    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
}
