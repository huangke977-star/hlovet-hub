@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode

private val nativeAiProviders = listOf("openai", "deepseek", "custom", "anthropic", "google")
private val nativeAiCurrencies = listOf("USD", "CNY")
private val nativeAiDefaultUrls = mapOf("openai" to "https://api.openai.com/v1", "deepseek" to "https://api.deepseek.com/v1", "anthropic" to "https://api.anthropic.com", "google" to "https://generativelanguage.googleapis.com/v1beta", "custom" to "https://api.example.com/v1")

private data class NativeAiFormResult(val message: String, val isError: Boolean = false)
private data class NativeAiModelSource(val provider: String, val baseUrl: String)

internal fun nativeAiProviderBaseUrl(provider: String, currentBaseUrl: String): String =
    if (currentBaseUrl.isBlank() || currentBaseUrl.trimEnd('/') in nativeAiDefaultUrls.values)
        nativeAiDefaultUrls[provider] ?: currentBaseUrl else currentBaseUrl

@Composable
internal fun NativeAdminAiScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    val token = session?.accessToken
    val canAccess = session?.isSuperAdmin == true
    val scope = rememberCoroutineScope()
    var config by remember(token) { mutableStateOf<NativeAiAdminConfig?>(null) }
    var capabilities by remember(token) { mutableStateOf<List<NativeAiCapability>>(emptyList()) }
    var invocations by remember(token) { mutableStateOf<NativeAiInvocations?>(null) }
    var tools by remember(token) { mutableStateOf<List<NativeAiToolAudit>>(emptyList()) }
    var usage by remember(token) { mutableStateOf<NativeAiUsage?>(null) }
    var knowledge by remember(token) { mutableStateOf<NativeAiKnowledge?>(null) }
    var documents by remember(token) { mutableStateOf<List<NativeAiKnowledgeDocument>>(emptyList()) }
    var knowledgeLoading by remember(token) { mutableStateOf(false) }
    var knowledgeBusy by remember(token) { mutableStateOf(false) }
    var indexingDocumentId by remember(token) { mutableStateOf<Int?>(null) }
    var knowledgeResult by remember(token) { mutableStateOf<NativeAiFormResult?>(null) }
    var loading by remember(token) { mutableStateOf(!token.isNullOrBlank() && canAccess) }
    var busy by remember(token) { mutableStateOf(false) }
    var testing by remember(token) { mutableStateOf(false) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var error by remember(token) { mutableStateOf<String?>(null) }
    var notice by remember(token) { mutableStateOf<String?>(null) }
    var primaryKey by remember(token) { mutableStateOf("") }
    var fallbackKey by remember(token) { mutableStateOf("") }
    var clearPrimaryKey by remember(token) { mutableStateOf(false) }
    var clearFallbackKey by remember(token) { mutableStateOf(false) }
    val capabilityKeys = remember(token) { mutableStateMapOf<String, String>() }
    val capabilityFallbackKeys = remember(token) { mutableStateMapOf<String, String>() }
    val capabilityClearKeys = remember(token) { mutableStateMapOf<String, Boolean>() }
    val capabilityClearFallbackKeys = remember(token) { mutableStateMapOf<String, Boolean>() }
    val modelOptions = remember(token) { mutableStateMapOf<String, List<NativeAiModelOption>>() }
    val fallbackModelOptions = remember(token) { mutableStateMapOf<String, List<NativeAiModelOption>>() }
    val formResults = remember(token) { mutableStateMapOf<String, NativeAiFormResult>() }
    var modelsLoading by remember(token) { mutableStateOf<String?>(null) }
    var fallbackModelsLoading by remember(token) { mutableStateOf<String?>(null) }

    suspend fun readKnowledge(accessToken: String) {
        knowledgeLoading = true
        try {
            val snapshot = withContext(Dispatchers.IO) { NativeAdminAiApi.knowledge(accessToken) to NativeAdminAiApi.documents(accessToken) }
            knowledge = snapshot.first
            documents = snapshot.second
        } finally { knowledgeLoading = false }
    }

    fun refreshKnowledge(showNotice: Boolean = true) {
        val accessToken = token ?: return
        if (!canAccess || loading || busy || knowledgeLoading || knowledgeBusy) return
        knowledgeResult = null
        knowledgeLoading = true
        scope.launch {
            try {
                readKnowledge(accessToken)
                if (showNotice) knowledgeResult = NativeAiFormResult(nativePhrase("知识库已刷新。", "Knowledge base refreshed."))
            } catch (exception: Exception) {
                knowledgeResult = NativeAiFormResult(exception.message ?: nativePhrase("知识库读取失败，请重试。", "Failed to load the knowledge base. Please retry."), true)
            }
        }
    }

    fun load() {
        val accessToken = token ?: return
        if (!canAccess) return
        scope.launch {
            loading = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val main = NativeAdminAiApi.getConfig(accessToken)
                    val caps = NativeAdminAiApi.capabilities(accessToken)
                    val logs = NativeAdminAiApi.invocations(accessToken)
                    val audits = NativeAdminAiApi.tools(accessToken)
                    val usageValue = NativeAdminAiApi.usage(accessToken)
                    listOf(main, caps, logs, audits, usageValue)
                }
                @Suppress("UNCHECKED_CAST")
                config = result[0] as NativeAiAdminConfig
                @Suppress("UNCHECKED_CAST")
                capabilities = result[1] as List<NativeAiCapability>
                invocations = result[2] as NativeAiInvocations
                @Suppress("UNCHECKED_CAST")
                tools = result[3] as List<NativeAiToolAudit>
                usage = result[4] as NativeAiUsage
                primaryKey = ""
                fallbackKey = ""
                clearPrimaryKey = false
                clearFallbackKey = false
                capabilityKeys.clear(); capabilityFallbackKeys.clear(); capabilityClearKeys.clear(); capabilityClearFallbackKeys.clear()
                modelOptions.clear(); fallbackModelOptions.clear(); formResults.clear()
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("AI 配置读取失败。", "Failed to load AI configuration.")
            } finally {
                loading = false
            }
            if (config != null) refreshKnowledge(showNotice = false)
        }
    }

    LaunchedEffect(token, canAccess, reloadKey) {
        if (token.isNullOrBlank() || !canAccess) {
            config = null; capabilities = emptyList(); invocations = null; tools = emptyList(); usage = null; knowledge = null; documents = emptyList(); loading = false
        } else load()
    }

    fun saveConfig() {
        val accessToken = token ?: return
        val current = config ?: return
        if (busy || loading || !canAccess) return
        busy = true; error = null; notice = null
        formResults.remove("primary")
        scope.launch {
            try {
                config = withContext(Dispatchers.IO) { NativeAdminAiApi.updateConfig(accessToken, current, primaryKey, clearPrimaryKey, fallbackKey, clearFallbackKey) }
                primaryKey = ""; fallbackKey = ""; clearPrimaryKey = false; clearFallbackKey = false
                formResults["primary"] = NativeAiFormResult(nativePhrase("AI 配置已保存。", "AI configuration saved."))
            } catch (exception: Exception) { formResults["primary"] = NativeAiFormResult(exception.message ?: nativePhrase("AI 配置保存失败。", "Failed to save AI configuration."), true) } finally { busy = false }
        }
    }

    fun testMain() {
        val accessToken = token ?: return
        if (testing || busy || loading || !canAccess) return
        testing = true; error = null; notice = null
        formResults.remove("primary")
        scope.launch { try { formResults["primary"] = NativeAiFormResult(withContext(Dispatchers.IO) { NativeAdminAiApi.testConnection(accessToken) }) } catch (exception: Exception) { formResults["primary"] = NativeAiFormResult(exception.message ?: nativePhrase("AI 连接测试失败。", "AI connection test failed."), true) } finally { testing = false } }
    }

    fun fetchModels(capability: String?, fallback: Boolean) {
        val accessToken = token ?: return
        val currentConfig = config ?: return
        if (!canAccess || busy || loading || modelsLoading != null || fallbackModelsLoading != null) return
        val current = capability?.let { name -> capabilities.firstOrNull { it.capability == name } ?: return }
        val provider = if (current == null) if (fallback) currentConfig.fallbackProvider else currentConfig.provider else if (fallback) current.fallbackProvider else current.provider
        val base = if (current == null) if (fallback) currentConfig.fallbackBaseUrl else currentConfig.baseUrl else if (fallback) current.fallbackBaseUrl else current.baseUrl
        val key = if (current == null) if (fallback) fallbackKey else primaryKey else if (fallback) capabilityFallbackKeys[capability].orEmpty() else capabilityKeys[capability].orEmpty()
        val slot = capability ?: "primary"
        val resultSlot = "$slot-${if (fallback) "fallback-" else ""}models"
        if (base.isBlank()) {
            formResults[resultSlot] = NativeAiFormResult(nativePhrase("请先填写接口地址。", "Enter an API base URL first."), true)
            return
        }
        val source = NativeAiModelSource(provider, base)
        val revision = reloadKey
        if (fallback) fallbackModelsLoading = slot else modelsLoading = slot
        formResults.remove(resultSlot)

        fun sourceStillCurrent(): Boolean {
            if (revision != reloadKey || loading) return false
            val latestConfig = config ?: return false
            val latest = capability?.let { name -> capabilities.firstOrNull { it.capability == name } ?: return false }
            val latestProvider = if (latest == null) if (fallback) latestConfig.fallbackProvider else latestConfig.provider else if (fallback) latest.fallbackProvider else latest.provider
            val latestBase = if (latest == null) if (fallback) latestConfig.fallbackBaseUrl else latestConfig.baseUrl else if (fallback) latest.fallbackBaseUrl else latest.baseUrl
            val latestKey = if (latest == null) if (fallback) fallbackKey else primaryKey else if (fallback) capabilityFallbackKeys[capability].orEmpty() else capabilityKeys[capability].orEmpty()
            return source == NativeAiModelSource(latestProvider, latestBase) && key == latestKey
        }
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { NativeAdminAiApi.listModels(accessToken, provider, base, key, capability, fallback) }
                // A response for a replaced provider or credential must not repopulate its picker.
                if (sourceStillCurrent()) {
                    if (fallback) fallbackModelOptions[slot] = loaded else modelOptions[slot] = loaded
                    loaded.firstOrNull()?.let { first ->
                        if (capability == null) config = config?.let { latest ->
                            if (fallback && latest.fallbackModel.isBlank()) latest.copy(fallbackModel = first.id)
                            else if (!fallback && latest.model.isBlank()) latest.copy(model = first.id) else latest
                        } else capabilities = capabilities.map { latest ->
                            if (latest.capability != capability) latest
                            else if (fallback && latest.fallbackModel.isBlank()) latest.copy(fallbackModel = first.id)
                            else if (!fallback && latest.model.isBlank()) latest.copy(model = first.id) else latest
                        }
                    }
                    formResults[resultSlot] = NativeAiFormResult(if (loaded.isEmpty()) nativePhrase("供应商未返回可选模型。", "The provider returned no selectable models.") else nativePhrase("已获取 ${loaded.size} 个模型。", "Loaded ${loaded.size} model(s)."))
                }
            } catch (exception: Exception) { if (sourceStillCurrent()) formResults[resultSlot] = NativeAiFormResult(exception.message ?: nativePhrase("模型列表获取失败。", "Failed to load model list."), true) } finally { if (fallback) fallbackModelsLoading = null else modelsLoading = null }
        }
    }

    fun saveCapability(item: NativeAiCapability) {
        val accessToken = token ?: return
        if (busy || loading || !canAccess) return
        busy = true; error = null; notice = null
        formResults.remove(item.capability)
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { NativeAdminAiApi.updateCapability(accessToken, item, capabilityKeys[item.capability].orEmpty(), capabilityClearKeys[item.capability] == true, capabilityFallbackKeys[item.capability].orEmpty(), capabilityClearFallbackKeys[item.capability] == true) }
                capabilities = capabilities.map { if (it.capability == saved.capability) saved else it }
                capabilityKeys[item.capability] = ""; capabilityFallbackKeys[item.capability] = ""; capabilityClearKeys[item.capability] = false; capabilityClearFallbackKeys[item.capability] = false
                formResults[item.capability] = NativeAiFormResult("${nativeAiCapabilityLabel(saved)} ${nativePhrase("配置已保存。", "configuration saved.")}")
            } catch (exception: Exception) { formResults[item.capability] = NativeAiFormResult(exception.message ?: nativePhrase("AI 能力配置保存失败。", "Failed to save AI capability configuration."), true) } finally { busy = false }
        }
    }

    fun testCapability(item: NativeAiCapability) {
        val accessToken = token ?: return
        if (testing || busy || loading || !canAccess) return
        testing = true; error = null; notice = null
        formResults.remove(item.capability)
        scope.launch { try { formResults[item.capability] = NativeAiFormResult(withContext(Dispatchers.IO) { NativeAdminAiApi.testCapability(accessToken, item.capability) }) } catch (exception: Exception) { formResults[item.capability] = NativeAiFormResult(exception.message ?: nativePhrase("AI 能力测试失败。", "AI capability test failed."), true) } finally { testing = false } }
    }

    fun reindex(document: NativeAiKnowledgeDocument? = null) {
        val accessToken = token ?: return
        if (!canAccess || busy || loading || knowledgeBusy || knowledgeLoading) return
        knowledgeBusy = true
        indexingDocumentId = document?.id
        knowledgeResult = null
        scope.launch {
            val actionResult = try {
                if (document == null) {
                    val result = withContext(Dispatchers.IO) { NativeAdminAiApi.reindex(accessToken) }
                    result.overview?.let { knowledge = it }
                    NativeAiFormResult(nativePhrase("已扫描 ${result.scanned} 篇，向量 ${result.indexed} 篇，关键词 ${result.keywordReady} 篇，待处理 ${result.pending} 篇，失败 ${result.failed} 篇。", "Scanned ${result.scanned}; vector-ready ${result.indexed}, keyword-ready ${result.keywordReady}, pending ${result.pending}, failed ${result.failed}."), result.failed > 0)
                } else {
                    val result = withContext(Dispatchers.IO) { NativeAdminAiApi.reindexDocument(accessToken, document.id) }
                    if (result.removed) documents = documents.filterNot { it.id == document.id }
                    else documents = documents.map { if (it.id == document.id) it.copy(status = result.status) else it }
                    NativeAiFormResult(
                        if (result.removed) nativePhrase("已移除“${document.title}”的失效索引。", "Removed the stale index for “${document.title}”.")
                        else nativePhrase("“${document.title}”：${nativeAiKnowledgeStatus(result.status)}。", "“${document.title}”: ${nativeAiKnowledgeStatus(result.status)}."),
                        result.status == "failed",
                    )
                }
            } catch (exception: Exception) {
                NativeAiFormResult(exception.message ?: nativePhrase("知识库索引失败。", "Knowledge base indexing failed."), true)
            }
            knowledgeResult = actionResult
            try {
                // Refresh only knowledge state, preserving unsaved model fields and credentials.
                readKnowledge(accessToken)
            } catch (exception: Exception) {
                knowledgeResult = NativeAiFormResult("${actionResult.message}\n${nativePhrase("最新状态读取失败，请点击刷新知识库。", "Could not read the latest state. Refresh the knowledge base.")} ${exception.message.orEmpty()}", true)
            } finally { knowledgeBusy = false; indexingDocumentId = null }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().testTag("ai-config-list"), contentPadding = PaddingValues(bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TopAppBar(title = { Column { Text(nativePhrase("AI 配置", "AI configuration"), fontSize = 20.sp, fontWeight = FontWeight.Bold); Text(nativePhrase("模型、能力、知识库与用量保护", "Models, capabilities, knowledge base, and usage protection"), color = HlovetUi.muted, fontSize = 10.sp) } }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } }, actions = { IconButton(onClick = { reloadKey++ }, enabled = canAccess && !loading && !busy && !testing && !knowledgeBusy && !knowledgeLoading) { Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = HlovetUi.accent) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
        }
        if (!error.isNullOrBlank() || !notice.isNullOrBlank()) item { NativeAiAdminMessage(error ?: notice.orEmpty(), error != null) { error = null; notice = null } }
        if (token.isNullOrBlank()) item { NativeAiAdminMessage(nativePhrase("登录后查看 AI 配置。", "Sign in to view AI configuration."), false, null) }
        else if (!canAccess) item { NativeAiAdminMessage(nativePhrase("AI 配置仅超级管理员可访问。", "Only super administrators can access AI configuration."), true, null) }
        else if (loading && config == null) item { NativeAiAdminMessage(nativePhrase("正在读取 AI 配置…", "Loading AI configuration..."), false, null) }
        else config?.let { current ->
            item {
                NativeAiMainCard(current, primaryKey, fallbackKey, clearPrimaryKey, clearFallbackKey, busy || loading, testing, modelOptions["primary"].orEmpty(), fallbackModelOptions["primary"].orEmpty(), modelsLoading == "primary", fallbackModelsLoading == "primary", modelsLoading == null && fallbackModelsLoading == null, formResults["primary"], formResults["primary-models"], formResults["primary-fallback-models"], onChange = { next ->
                    if (next.provider != current.provider || next.baseUrl != current.baseUrl) { modelOptions.remove("primary"); formResults.remove("primary-models") }
                    if (next.fallbackProvider != current.fallbackProvider || next.fallbackBaseUrl != current.fallbackBaseUrl) { fallbackModelOptions.remove("primary"); formResults.remove("primary-fallback-models") }
                    config = next
                }, onPrimaryKey = { primaryKey = it; modelOptions.remove("primary"); if (it.isNotBlank()) clearPrimaryKey = false }, onFallbackKey = { fallbackKey = it; fallbackModelOptions.remove("primary"); if (it.isNotBlank()) clearFallbackKey = false }, onClearPrimary = { clearPrimaryKey = !clearPrimaryKey; primaryKey = "" }, onClearFallback = { clearFallbackKey = !clearFallbackKey; fallbackKey = "" }, onFetch = ::fetchModels, onTest = ::testMain, onSave = ::saveConfig)
            }
            item { NativeAiRecommendationCard(current.recommendation) }
            capabilities.forEach { capability -> item(key = capability.capability) {
                NativeAiCapabilityCard(capability, capabilityKeys[capability.capability].orEmpty(), capabilityFallbackKeys[capability.capability].orEmpty(), capabilityClearKeys[capability.capability] == true, capabilityClearFallbackKeys[capability.capability] == true, busy || loading, testing, modelOptions[capability.capability].orEmpty(), fallbackModelOptions[capability.capability].orEmpty(), modelsLoading == capability.capability, fallbackModelsLoading == capability.capability, modelsLoading == null && fallbackModelsLoading == null, current.encryptionConfigured, formResults[capability.capability], formResults["${capability.capability}-models"], formResults["${capability.capability}-fallback-models"], onChange = { next ->
                    if (next.provider != capability.provider || next.baseUrl != capability.baseUrl) { modelOptions.remove(capability.capability); formResults.remove("${capability.capability}-models") }
                    if (next.fallbackProvider != capability.fallbackProvider || next.fallbackBaseUrl != capability.fallbackBaseUrl) { fallbackModelOptions.remove(capability.capability); formResults.remove("${capability.capability}-fallback-models") }
                    capabilities = capabilities.map { existing -> if (existing.capability == next.capability) next else existing }
                }, onKeyValue = { capabilityKeys[it.first] = it.second; modelOptions.remove(it.first); if (it.second.isNotBlank()) capabilityClearKeys[it.first] = false }, onFallbackKey = { capabilityFallbackKeys[it.first] = it.second; fallbackModelOptions.remove(it.first); if (it.second.isNotBlank()) capabilityClearFallbackKeys[it.first] = false }, onClear = { capabilityClearKeys[it] = !(capabilityClearKeys[it] == true); capabilityKeys[it] = "" }, onClearFallback = { capabilityClearFallbackKeys[it] = !(capabilityClearFallbackKeys[it] == true); capabilityFallbackKeys[it] = "" }, onFetch = ::fetchModels, onTest = ::testCapability, onSave = ::saveCapability)
            } }
            item { NativeAiUsageCard(usage, invocations, tools) }
            item { NativeAiKnowledgeCard(knowledge, documents, knowledgeLoading, knowledgeBusy, indexingDocumentId, !busy && !loading, knowledgeResult, onRefresh = { refreshKnowledge() }, onReindex = { reindex() }, onReindexDocument = { reindex(it) }) }
        }
    }
}

@Composable
private fun NativeAiMainCard(
    current: NativeAiAdminConfig, primaryKey: String, fallbackKey: String, clearPrimary: Boolean, clearFallback: Boolean, busy: Boolean, testing: Boolean,
    models: List<NativeAiModelOption>, fallbackModels: List<NativeAiModelOption>, modelsLoading: Boolean, fallbackLoading: Boolean,
    canFetchModels: Boolean, result: NativeAiFormResult?, modelResult: NativeAiFormResult?, fallbackModelResult: NativeAiFormResult?,
    onChange: (NativeAiAdminConfig) -> Unit, onPrimaryKey: (String) -> Unit, onFallbackKey: (String) -> Unit, onClearPrimary: () -> Unit, onClearFallback: () -> Unit,
    onFetch: (String?, Boolean) -> Unit, onTest: () -> Unit, onSave: () -> Unit,
) {
    NativeAiAdminCard(nativePhrase("模型连接与全局策略", "Model connection and global policy"), Icons.Filled.AutoAwesome, "ai-primary-card") {
        NativeAiAdminToggle(nativePhrase("启用 AI 功能", "Enable AI"), current.enabled, !busy) { onChange(current.copy(enabled = it)) }
        NativeAiAdminMenu(nativePhrase("供应商", "Provider"), current.provider, nativeAiProviders, !busy) { provider -> onChange(current.copy(provider = provider, baseUrl = nativeAiProviderBaseUrl(provider, current.baseUrl))) }
        NativeAiAdminField(nativePhrase("接口地址", "API base URL"), current.baseUrl, !busy) { onChange(current.copy(baseUrl = it)) }
        NativeAiModelField(nativePhrase("模型", "Model"), current.model, models, !busy, modelsLoading, { onChange(current.copy(model = it)) }, { onFetch(null, false) }, canFetchModels && current.baseUrl.isNotBlank())
        NativeAiFormFeedback(modelResult)
        NativeAiAdminSecret("API Key", primaryKey, current.apiKeyConfigured, clearPrimary, !busy && current.encryptionConfigured, onPrimaryKey, onClearPrimary)
        NativeAiAdminToggle(nativePhrase("启用备用模型", "Enable fallback model"), current.fallbackEnabled, !busy) { onChange(current.copy(fallbackEnabled = it)) }
        if (current.fallbackEnabled) {
            NativeAiAdminMenu(nativePhrase("备用供应商", "Fallback provider"), current.fallbackProvider, nativeAiProviders, !busy) { onChange(current.copy(fallbackProvider = it, fallbackBaseUrl = nativeAiProviderBaseUrl(it, current.fallbackBaseUrl))) }
            NativeAiAdminField(nativePhrase("备用接口地址", "Fallback API base URL"), current.fallbackBaseUrl, !busy) { onChange(current.copy(fallbackBaseUrl = it)) }
            NativeAiModelField(nativePhrase("备用模型", "Fallback model"), current.fallbackModel, fallbackModels, !busy, fallbackLoading, { onChange(current.copy(fallbackModel = it)) }, { onFetch(null, true) }, canFetchModels && current.fallbackBaseUrl.isNotBlank())
            NativeAiFormFeedback(fallbackModelResult)
            NativeAiAdminSecret(nativePhrase("备用 API Key", "Fallback API key"), fallbackKey, current.fallbackApiKeyConfigured, clearFallback, !busy && current.encryptionConfigured, onFallbackKey, onClearFallback)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(nativePhrase("价格 / 百万 tokens", "Price / million tokens"), modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            current.pricingPresets.firstOrNull { it.provider == current.provider && it.model.equals(current.model.trim(), ignoreCase = true) }?.let { preset ->
                TextButton(onClick = { onChange(current.copy(billingCurrency = preset.currency, inputCost = preset.input, cachedInputCost = preset.cachedInput, outputCost = preset.output)) }, enabled = !busy) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
                    Text(nativePhrase("套用价格", "Apply prices"), fontSize = 11.sp)
                }
            }
        }
        NativeAiPriceGrid(current.inputCost, current.cachedInputCost, current.outputCost, current.fallbackInputCost, current.fallbackCachedInputCost, current.fallbackOutputCost, !busy) { key, value -> onChange(when (key) { 0 -> current.copy(inputCost = value); 1 -> current.copy(cachedInputCost = value); 2 -> current.copy(outputCost = value); 3 -> current.copy(fallbackInputCost = value); 4 -> current.copy(fallbackCachedInputCost = value); else -> current.copy(fallbackOutputCost = value) }) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { NativeAiNumber(nativePhrase("全站并发", "Global concurrency"), current.globalConcurrency, 1, current.recommendation.maxGlobalConcurrency, !busy, Modifier.weight(1f)) { onChange(current.copy(globalConcurrency = it)) }; NativeAiNumber(nativePhrase("单用户并发", "Per-user concurrency"), current.userConcurrency, 1, current.recommendation.maxUserConcurrency, !busy, Modifier.weight(1f)) { onChange(current.copy(userConcurrency = it)) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { NativeAiNumber(nativePhrase("最大输出 tokens", "Max output tokens"), current.maxOutputTokens, 256, 8000, !busy, Modifier.weight(1f)) { onChange(current.copy(maxOutputTokens = it)) }; NativeAiNumber(nativePhrase("超时秒数", "Timeout seconds"), current.requestTimeoutSeconds, 10, 300, !busy, Modifier.weight(1f)) { onChange(current.copy(requestTimeoutSeconds = it)) } }
        NativeAiNumber(nativePhrase("每日请求上限", "Daily request limit"), current.dailyRequestLimit, 0, 100000, !busy, Modifier.fillMaxWidth()) { onChange(current.copy(dailyRequestLimit = it)) }
        NativeAiAdminMenu(nativePhrase("计价币种", "Billing currency"), current.billingCurrency, nativeAiCurrencies, !busy) { onChange(current.copy(billingCurrency = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { NativeAiAdminToggle(nativePhrase("启用 RAG 检索", "Enable RAG retrieval"), current.ragEnabled, !busy, Modifier.weight(1f)) { onChange(current.copy(ragEnabled = it)) }; NativeAiAdminToggle(nativePhrase("记录质量指标", "Record quality metrics"), current.qualityEvaluationEnabled, !busy, Modifier.weight(1f)) { onChange(current.copy(qualityEvaluationEnabled = it)) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { NativeAiNumber(nativePhrase("引用片段", "Retrieved chunks"), current.ragTopK, 1, 20, !busy, Modifier.weight(1f)) { onChange(current.copy(ragTopK = it)) }; NativeAiNumber(nativePhrase("最近消息", "Recent messages"), current.contextMaxMessages, 4, 80, !busy, Modifier.weight(1f)) { onChange(current.copy(contextMaxMessages = it)) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { NativeAiNumber(nativePhrase("压缩阈值", "Summary threshold"), current.contextSummaryThreshold, 8, 200, !busy, Modifier.weight(1f)) { onChange(current.copy(contextSummaryThreshold = it)) }; NativeAiNumber(nativePhrase("保留天数", "Retention days"), current.contextRetentionDays, 1, 3650, !busy, Modifier.weight(1f)) { onChange(current.copy(contextRetentionDays = it)) } }
        if (!current.encryptionConfigured) HlovetFormFeedback(nativePhrase("服务端未配置密钥加密，暂不能修改 API Key。", "Server key encryption is not configured; API keys cannot be edited."))
        NativeAiFormFeedback(result)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onTest, enabled = !testing && !busy && current.apiKeyConfigured) { Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(15.dp)); Text(if (testing) nativePhrase("测试中…", "Testing...") else nativePhrase("测试连接", "Test connection"), fontSize = 11.sp) }; TextButton(onClick = onSave, enabled = !busy) { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(15.dp)); Text(if (busy) nativePhrase("保存中…", "Saving...") else nativePhrase("保存配置", "Save configuration"), fontSize = 11.sp) } }
    }
}

@Composable
private fun NativeAiCapabilityCard(
    item: NativeAiCapability, key: String, fallbackKey: String, clear: Boolean, clearFallback: Boolean, busy: Boolean, testing: Boolean,
    models: List<NativeAiModelOption>, fallbackModels: List<NativeAiModelOption>, modelsLoading: Boolean, fallbackLoading: Boolean,
    canFetchModels: Boolean, encryptionConfigured: Boolean, result: NativeAiFormResult?, modelResult: NativeAiFormResult?, fallbackModelResult: NativeAiFormResult?,
    onChange: (NativeAiCapability) -> Unit, onKeyValue: (Pair<String, String>) -> Unit, onFallbackKey: (Pair<String, String>) -> Unit, onClear: (String) -> Unit, onClearFallback: (String) -> Unit, onFetch: (String?, Boolean) -> Unit, onTest: (NativeAiCapability) -> Unit, onSave: (NativeAiCapability) -> Unit,
) {
    NativeAiAdminCard("${nativeAiCapabilityLabel(item)} · ${item.capability}", Icons.Filled.Memory, "ai-capability-${item.capability}") {
        val pricingNotes = item.officialPricingNote.lines()
        Text(if (NativeI18n.locale == "en-US") pricingNotes.getOrElse(1) { pricingNotes.firstOrNull().orEmpty() } else pricingNotes.firstOrNull().orEmpty(), color = HlovetUi.muted, fontSize = 10.sp)
        NativeAiAdminToggle(if (item.enabled) nativePhrase("已启用", "Enabled") else nativePhrase("未启用", "Disabled"), item.enabled, !busy) { onChange(item.copy(enabled = it)) }
        NativeAiAdminMenu(nativePhrase("供应商", "Provider"), item.provider, nativeAiProviders, !busy) { onChange(item.copy(provider = it, baseUrl = nativeAiProviderBaseUrl(it, item.baseUrl))) }
        NativeAiAdminField(nativePhrase("接口地址", "API base URL"), item.baseUrl, !busy) { onChange(item.copy(baseUrl = it)) }
        NativeAiModelField(nativePhrase("模型", "Model"), item.model, models, !busy, modelsLoading, { onChange(item.copy(model = it)) }, { onFetch(item.capability, false) }, canFetchModels && item.baseUrl.isNotBlank())
        NativeAiFormFeedback(modelResult)
        NativeAiAdminSecret("API Key", key, item.apiKeyConfigured, clear, !busy && encryptionConfigured, { onKeyValue(item.capability to it) }, { onClear(item.capability) })
        NativeAiAdminToggle(nativePhrase("启用备用模型", "Enable fallback model"), item.fallbackEnabled, !busy) { onChange(item.copy(fallbackEnabled = it)) }
        if (item.fallbackEnabled) { NativeAiAdminMenu(nativePhrase("备用供应商", "Fallback provider"), item.fallbackProvider, nativeAiProviders, !busy) { onChange(item.copy(fallbackProvider = it, fallbackBaseUrl = nativeAiProviderBaseUrl(it, item.fallbackBaseUrl))) }; NativeAiAdminField(nativePhrase("备用接口地址", "Fallback API base URL"), item.fallbackBaseUrl, !busy) { onChange(item.copy(fallbackBaseUrl = it)) }; NativeAiModelField(nativePhrase("备用模型", "Fallback model"), item.fallbackModel, fallbackModels, !busy, fallbackLoading, { onChange(item.copy(fallbackModel = it)) }, { onFetch(item.capability, true) }, canFetchModels && item.fallbackBaseUrl.isNotBlank()); NativeAiFormFeedback(fallbackModelResult); NativeAiAdminSecret(nativePhrase("备用 API Key", "Fallback API key"), fallbackKey, item.fallbackApiKeyConfigured, clearFallback, !busy && encryptionConfigured, { onFallbackKey(item.capability to it) }, { onClearFallback(item.capability) }) }
        NativeAiPriceGrid(item.inputCost, item.cachedInputCost, item.outputCost, item.fallbackInputCost, item.fallbackCachedInputCost, item.fallbackOutputCost, !busy) { keyIndex, value -> onChange(when (keyIndex) { 0 -> item.copy(inputCost = value); 1 -> item.copy(cachedInputCost = value); 2 -> item.copy(outputCost = value); 3 -> item.copy(fallbackInputCost = value); 4 -> item.copy(fallbackCachedInputCost = value); else -> item.copy(fallbackOutputCost = value) }) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { NativeAiNumber(nativePhrase("全站并发", "Global concurrency"), item.globalConcurrency, 1, 8, !busy, Modifier.weight(1f)) { onChange(item.copy(globalConcurrency = it)) }; NativeAiNumber(nativePhrase("单用户并发", "Per-user concurrency"), item.userConcurrency, 1, 8, !busy, Modifier.weight(1f)) { onChange(item.copy(userConcurrency = it)) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { NativeAiNumber(nativePhrase("每日上限", "Daily limit"), item.dailyRequestLimit, 0, 100000, !busy, Modifier.weight(1f)) { onChange(item.copy(dailyRequestLimit = it)) }; NativeAiNumber(nativePhrase("超时秒数", "Timeout seconds"), item.requestTimeoutSeconds, 10, 300, !busy, Modifier.weight(1f)) { onChange(item.copy(requestTimeoutSeconds = it)) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { NativeAiPriceField(nativePhrase("月度预算", "Monthly budget"), item.monthlyBudget, !busy, Modifier.weight(1f)) { onChange(item.copy(monthlyBudget = it)) }; NativeAiPriceField(nativePhrase("单次价格", "Per-request cost"), item.unitCost, !busy, Modifier.weight(1f)) { onChange(item.copy(unitCost = it)) } }
        NativeAiFormFeedback(result)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { onTest(item) }, enabled = !testing && !busy && item.apiKeyConfigured) { Text(if (testing) nativePhrase("测试中…", "Testing...") else nativePhrase("测试", "Test"), fontSize = 11.sp) }; TextButton(onClick = { onSave(item) }, enabled = !busy) { Text(if (busy) nativePhrase("保存中…", "Saving...") else nativePhrase("保存", "Save"), fontSize = 11.sp) } }
    }
}

@Composable
private fun NativeAiFormFeedback(result: NativeAiFormResult?) {
    HlovetFormFeedback(error = result?.takeIf { it.isError }?.message, notice = result?.takeUnless { it.isError }?.message)
}

@Composable
private fun NativeAiRecommendationCard(recommendation: NativeAiResourceRecommendation) {
    NativeAiAdminCard(nativePhrase("服务器推荐", "Server recommendation"), Icons.Filled.Speed) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { NativeAiStat("CPU", "${recommendation.cpuCores}", Modifier.weight(1f)); NativeAiStat(nativePhrase("内存", "Memory"), "${recommendation.totalMemoryMiB} MiB", Modifier.weight(1f)); NativeAiStat(nativePhrase("全站并发", "Global concurrency"), recommendation.globalConcurrency.toString(), Modifier.weight(1f)); NativeAiStat(nativePhrase("单用户", "Per-user"), recommendation.userConcurrency.toString(), Modifier.weight(1f)) }; Text(nativePhrase("保护上限：全站 ${recommendation.maxGlobalConcurrency}，单用户 ${recommendation.maxUserConcurrency}。", "Protection limits: ${recommendation.maxGlobalConcurrency} global, ${recommendation.maxUserConcurrency} per user."), color = HlovetUi.muted, fontSize = 10.sp) }
}

@Composable
private fun NativeAiUsageCard(usage: NativeAiUsage?, invocations: NativeAiInvocations?, tools: List<NativeAiToolAudit>) {
    var auditView by remember { mutableStateOf<String?>(null) }
    NativeAiAdminCard(nativePhrase("用量、费用与审计", "Usage, cost, and audit"), Icons.Filled.Visibility, "ai-usage-card") {
        if (usage == null) Text(nativePhrase("暂无用量数据。", "No usage data."), color = HlovetUi.muted, fontSize = 12.sp)
        else {
            Text(nativePhrase("近 ${usage.days} 天：${usage.requests} 次请求 · ${usage.tokens} tokens · 缓存 ${usage.cachedInputUnits} · 估算 ${formatNativeAiPrice(usage.cost)}", "Last ${usage.days} days: ${usage.requests} requests · ${usage.tokens} tokens · cache ${usage.cachedInputUnits} · estimated ${formatNativeAiPrice(usage.cost)}"), fontSize = 12.sp)
            Text(nativePhrase("质量反馈：${usage.feedbackTotal} 条，满意 ${usage.helpful}，不满意 ${usage.unhelpful}，平均 ${usage.averageRating?.toString() ?: "暂无"}", "Quality feedback: ${usage.feedbackTotal}, helpful ${usage.helpful}, unhelpful ${usage.unhelpful}, average ${usage.averageRating?.toString() ?: "n/a"}"), color = HlovetUi.muted, fontSize = 11.sp)
            usage.capabilityRows.forEach { row ->
                Text(nativePhrase("${row.capability}：${row.requests} 次，成功率 ${row.successRate.toInt()}%，费用 ${formatNativeAiPrice(row.cost)}", "${row.capability}: ${row.requests} requests, ${row.successRate.toInt()}% success, ${formatNativeAiPrice(row.cost)} cost"), fontSize = 11.sp)
            }
        }
        invocations?.let {
            Text(nativePhrase("今日调用：${it.todayRequests} 次 · ${it.todayTokens} tokens · 缓存命中 ${it.cacheHitRate.toInt()}% · 估算 ${formatNativeAiPrice(it.todayCost)}", "Today: ${it.todayRequests} requests · ${it.todayTokens} tokens · cache hit ${it.cacheHitRate.toInt()}% · estimated ${formatNativeAiPrice(it.todayCost)}"), color = HlovetUi.muted, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { auditView = "invocations" }, enabled = !invocations?.logs.isNullOrEmpty()) { Text(nativePhrase("调用记录（${invocations?.logs?.size ?: 0}）", "Invocations (${invocations?.logs?.size ?: 0})"), fontSize = 11.sp) }
            TextButton(onClick = { auditView = "tools" }, enabled = tools.isNotEmpty()) { Text(nativePhrase("工具审计（${tools.size}）", "Tool audits (${tools.size})"), fontSize = 11.sp) }
        }
    }
    if (auditView != null) HlovetAlertDialog(
        onDismissRequest = { auditView = null },
        title = { Text(if (auditView == "tools") nativePhrase("工具审计", "Tool audits") else nativePhrase("调用记录", "Invocations"), fontSize = 17.sp) },
        confirmButton = { TextButton(onClick = { auditView = null }) { Text(nativePhrase("关闭", "Close"), fontSize = 12.sp) } },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (auditView == "tools") tools.forEach { audit ->
                    Text("${audit.toolName} · ${nativeAiAuditStatus(audit.status)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("${audit.nickname.ifBlank { audit.username }} · @${audit.username} · ${if (audit.requiresConfirmation) nativePhrase("需确认", "Confirmation required") else nativePhrase("只读", "Read-only")}", fontSize = 11.sp)
                    Text(audit.createdAt, color = HlovetUi.muted, fontSize = 11.sp)
                    HorizontalDivider(color = HlovetUi.divider)
                } else invocations?.logs?.forEach { log ->
                    Text("${if (log.operation == "test_connection") nativePhrase("连接测试", "Connection test") else log.operation} · ${nativeAiAuditStatus(log.status)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("${log.provider} · ${log.model}", fontSize = 11.sp)
                    Text("${log.totalTokens ?: "-"} tokens · ${nativePhrase("缓存", "cached")} ${log.cachedPromptTokens ?: "-"} · ${log.durationMs} ms · ${nativePhrase("费用", "cost")} ${log.cost?.let(::formatNativeAiPrice) ?: "-"}", fontSize = 11.sp)
                    Text(log.createdAt, color = HlovetUi.muted, fontSize = 11.sp)
                    if (log.error.isNotBlank()) HlovetFormFeedback(log.error)
                    HorizontalDivider(color = HlovetUi.divider)
                }
            }
        },
    )
}

private fun nativeAiAuditStatus(status: String) = when (status) {
    "success" -> nativePhrase("成功", "Success")
    "awaiting_confirmation" -> nativePhrase("待确认", "Awaiting confirmation")
    "failed" -> nativePhrase("失败", "Failed")
    else -> status
}

@Composable
private fun NativeAiKnowledgeCard(
    knowledge: NativeAiKnowledge?, documents: List<NativeAiKnowledgeDocument>, loading: Boolean, busy: Boolean,
    indexingDocumentId: Int?, enabled: Boolean, result: NativeAiFormResult?, onRefresh: () -> Unit,
    onReindex: () -> Unit, onReindexDocument: (NativeAiKnowledgeDocument) -> Unit,
) {
    var documentsOpen by remember { mutableStateOf(false) }
    NativeAiAdminCard(nativePhrase("RAG 知识库", "RAG knowledge base"), Icons.Filled.Storage, "ai-knowledge-card") {
        Text(if (knowledge == null) nativePhrase("暂无知识库统计", "No knowledge base statistics") else nativePhrase("${knowledge.documents} 篇文章 · ${knowledge.chunks} 个片段 · ${knowledge.vectors} 个向量", "${knowledge.documents} articles · ${knowledge.chunks} chunks · ${knowledge.vectors} vectors"), fontSize = 12.sp)
        knowledge?.let {
            Text(nativePhrase("向量就绪 ${it.ready} · 关键词就绪 ${it.keywordReady} · 待向量 ${it.pendingEmbedding} · 失败 ${it.failed}", "Vector-ready ${it.ready} · keyword-ready ${it.keywordReady} · pending embeddings ${it.pendingEmbedding} · failed ${it.failed}"), fontSize = 11.sp, color = HlovetUi.muted)
            Text(if (it.semanticAvailable) nativePhrase("语义检索可用", "Semantic search available") else if (it.keywordAvailable) nativePhrase("当前使用关键词检索", "Using keyword search") else nativePhrase("暂无可用检索", "No search method available"), color = if (it.semanticAvailable) HlovetUi.accent else HlovetUi.muted, fontSize = 11.sp)
        }
        NativeAiFormFeedback(result)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRefresh, enabled = enabled && !busy && !loading, modifier = Modifier.size(48.dp).testTag("ai-knowledge-refresh").semantics { contentDescription = nativePhrase("刷新知识库", "Refresh knowledge base") }) {
                if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = HlovetUi.accent)
                else Icon(Icons.Filled.Refresh, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(18.dp))
            }
            TextButton(onClick = { documentsOpen = true }) { Text(nativePhrase("索引文档（${documents.size}）", "Documents (${documents.size})"), fontSize = 11.sp) }
            TextButton(onClick = onReindex, enabled = enabled && !busy && !loading, modifier = Modifier.testTag("ai-knowledge-reindex")) {
                if (busy && indexingDocumentId == null) CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = HlovetUi.accent)
                else Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                Text(nativePhrase("建立/刷新索引", "Build/refresh index"), fontSize = 11.sp)
            }
        }
    }
    if (documentsOpen) HlovetAlertDialog(
        onDismissRequest = { documentsOpen = false },
        title = { Text(nativePhrase("索引文档（${documents.size}）", "Documents (${documents.size})"), fontSize = 17.sp) },
        confirmButton = { TextButton(onClick = { documentsOpen = false }) { Text(nativePhrase("关闭", "Close"), fontSize = 12.sp) } },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(max = 112.dp).verticalScroll(rememberScrollState()).testTag("ai-knowledge-feedback")) { NativeAiFormFeedback(result) }
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 360.dp).testTag("ai-knowledge-documents"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (documents.isEmpty()) item { Text(nativePhrase("暂无索引文档。", "No indexed documents."), color = HlovetUi.muted, fontSize = 12.sp) }
                    items(documents, key = { it.id }) { document ->
                        Row(Modifier.fillMaxWidth().testTag("ai-knowledge-document-${document.id}"), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(document.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${nativeAiKnowledgeStatus(document.status)} · ${document.chunks} ${nativePhrase("片段", "chunks")} · ${document.vectors} ${nativePhrase("向量", "vectors")}", fontSize = 11.sp, color = HlovetUi.muted)
                                if (document.embeddingModel.isNotBlank()) Text("Embedding: ${document.embeddingModel}", fontSize = 11.sp, color = HlovetUi.muted)
                                if (document.lastIndexedAt.isNotBlank()) Text("${nativePhrase("索引时间", "Indexed at")}: ${document.lastIndexedAt}", fontSize = 11.sp, color = HlovetUi.muted)
                                if (document.error.isNotBlank()) {
                                    if (document.status == "failed") HlovetFormFeedback(document.error)
                                    else Text("${nativePhrase("向量提示", "Embedding notice")}: ${document.error}", fontSize = 11.sp, color = HlovetUi.muted)
                                }
                            }
                            IconButton(onClick = { onReindexDocument(document) }, enabled = enabled && !busy && !loading,
                                modifier = Modifier.size(48.dp).testTag("ai-reindex-document-${document.id}").semantics { contentDescription = nativePhrase("重建 ${document.title}", "Reindex ${document.title}") }) {
                                if (busy && indexingDocumentId == document.id) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = HlovetUi.accent)
                                else Icon(Icons.Filled.Refresh, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(18.dp))
                            }
                        }
                        HorizontalDivider(color = HlovetUi.divider)
                    }
                }
            }
        },
    )
}

internal fun nativeAiKnowledgeStatus(status: String): String = when (status) {
    "ready" -> nativePhrase("向量可用", "Vector-ready")
    "keyword_ready" -> nativePhrase("关键词可用", "Keyword-ready")
    "pending_embedding" -> nativePhrase("待生成向量", "Pending embeddings")
    "pending" -> nativePhrase("待索引", "Pending indexing")
    "failed" -> nativePhrase("索引失败", "Indexing failed")
    "removed" -> nativePhrase("索引已移除", "Index removed")
    else -> status.ifBlank { nativePhrase("未知状态", "Unknown status") }
}

@Composable
private fun NativeAiAdminCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String = "", content: @Composable () -> Unit) { GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag(tag), shape = HlovetUi.cardShape) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold) }; content() } } }

/** 展示 AI 配置页的普通单行文本字段，并统一输入框外观令牌。 */
@Composable
private fun NativeAiAdminField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) { OutlinedTextField(value, onChange, enabled = enabled, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = HlovetUi.controlShape, label = { Text(label, fontSize = 11.sp) }, textStyle = TextStyle(fontSize = 12.sp), colors = nativeAiFieldColors()) }
/** 展示以微单位保存的整数价格字段，输入为空时保留现有值。 */
@Composable
private fun NativeAiAdminNumberField(label: String, value: Long, enabled: Boolean, onChange: (Long) -> Unit) { OutlinedTextField(value.toString(), { onChange(it.filter(Char::isDigit).toLongOrNull() ?: value) }, enabled = enabled, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = HlovetUi.controlShape, label = { Text(label, fontSize = 9.sp) }, textStyle = TextStyle(fontSize = 10.sp), colors = nativeAiFieldColors()) }
/** 展示受最小值和最大值约束的 AI 并发数字字段。 */
@Composable
private fun NativeAiNumber(label: String, value: Int, min: Int, max: Int, enabled: Boolean, modifier: Modifier, onChange: (Int) -> Unit) { OutlinedTextField(value.toString(), { onChange(it.filter(Char::isDigit).toIntOrNull()?.coerceIn(min, max) ?: value) }, enabled = enabled, modifier = modifier, singleLine = true, shape = HlovetUi.controlShape, label = { Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }, textStyle = TextStyle(fontSize = 12.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = nativeAiFieldColors()) }
@Composable
private fun NativeAiAdminMenu(label: String, value: String, options: List<String>, enabled: Boolean, onChange: (String) -> Unit) {
    NativeAiSelect(label, value, options.map { NativeAiModelOption(it, nativeAiProviderLabel(it)) }, enabled, Modifier.fillMaxWidth(), onChange)
}

private fun nativeAiProviderLabel(provider: String): String = when (provider) {
    "openai" -> "OpenAI"
    "deepseek" -> "DeepSeek"
    "custom" -> nativePhrase("通用第三方", "Generic third party")
    "anthropic" -> "Anthropic"
    "google" -> "Google"
    else -> provider
}

/** 用统一控件令牌展示模型选择器；enabled 控制可用性，modifier 控制宽度，onChange 回传模型标识。 */
@Composable
private fun NativeAiSelect(label: String, value: String, options: List<NativeAiModelOption>, enabled: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    // 与输入框和拉取按钮使用同一固定高度、圆角和边框，避免配置页控件视觉断裂。
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, options) { if (!enabled) expanded = false }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = HlovetUi.muted, fontSize = 11.sp)
        Box {
            Row(
                Modifier.fillMaxWidth().height(HlovetUi.controlHeight).testTag("ai-select-$label")
                    .clip(HlovetUi.controlShape).background(HlovetUi.glassFallback)
                    .border(1.dp, HlovetUi.outline, HlovetUi.controlShape)
                    .clickable(enabled = enabled, role = Role.Button) { expanded = true }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(options.firstOrNull { it.id == value }?.label ?: value.ifBlank { nativePhrase("选择模型", "Select a model") },
                    modifier = Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (enabled) HlovetUi.foreground else HlovetUi.muted)
                Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = HlovetUi.muted, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = HlovetUi.menuShape, containerColor = HlovetUi.menuSurface, tonalElevation = 0.dp) {
                options.forEach { option -> DropdownMenuItem(
                    text = { Text(option.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.semantics { selected = option.id == value },
                    onClick = { expanded = false; onChange(option.id) },
                ) }
                if (options.isEmpty()) DropdownMenuItem(text = { Text(nativePhrase("请先获取模型列表", "Fetch the model list first"), fontSize = 12.sp) }, enabled = false, onClick = {})
            }
        }
    }
}
@Composable
private fun NativeAiAdminToggle(label: String, checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) { Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, fontSize = 11.sp, modifier = Modifier.weight(1f), color = if (enabled) HlovetUi.foreground else HlovetUi.muted); HlovetSwitch(checked, onChange, enabled = enabled) } }
/** 展示 API 密钥字段，查看和清除操作都保持在输入框尾部。 */
@Composable
internal fun NativeAiAdminSecret(label: String, value: String, configured: Boolean, clear: Boolean, enabled: Boolean, onChange: (String) -> Unit, onClear: () -> Unit) {
    var visible by remember(label) { mutableStateOf(false) }
    LaunchedEffect(value, enabled) { if (value.isBlank() || !enabled) visible = false }
    OutlinedTextField(
        value, onChange, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("ai-key-$label"), singleLine = true,
        label = { Text(label, fontSize = 11.sp) },
        placeholder = { Text(if (clear) nativePhrase("保存时清除", "Clear on save") else if (configured) nativePhrase("已配置，留空保持不变", "Configured; leave blank to keep") else nativePhrase("输入 API Key", "Enter API key"), fontSize = 11.sp) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        textStyle = TextStyle(fontSize = 12.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = HlovetUi.controlShape,
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { visible = !visible }, enabled = enabled && value.isNotBlank(), modifier = Modifier.size(48.dp)) {
                    Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) nativePhrase("隐藏 $label", "Hide $label") else nativePhrase("查看 $label", "Show $label"),
                        tint = HlovetUi.muted, modifier = Modifier.size(18.dp))
                }
                if (configured) IconButton(onClick = onClear, enabled = enabled, modifier = Modifier.size(48.dp).semantics { selected = clear }) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = if (clear) nativePhrase("取消清除 $label", "Cancel clearing $label") else nativePhrase("清除已保存 $label", "Clear saved $label"),
                        tint = if (clear) HlovetUi.secondaryAccent else HlovetUi.muted, modifier = Modifier.size(18.dp))
                }
            }
        },
        colors = nativeAiFieldColors(),
    )
}
/** 以统一控件高度并列展示模型下拉框和模型列表拉取入口。 */
@Composable
internal fun NativeAiModelField(label: String, value: String, models: List<NativeAiModelOption>, enabled: Boolean, loading: Boolean, onChange: (String) -> Unit, onFetch: () -> Unit, fetchEnabled: Boolean = true) {
    val options = ((if (value.isNotBlank() && models.none { it.id == value }) listOf(NativeAiModelOption(value, value)) else emptyList()) + models).distinctBy { it.id }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        NativeAiSelect(label, value, options, enabled, Modifier.weight(1f), onChange)
        IconButton(onClick = onFetch, enabled = enabled && !loading && fetchEnabled, modifier = Modifier.width(48.dp).height(HlovetUi.controlHeight).testTag("ai-fetch-$label")) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = HlovetUi.accent)
            else Icon(Icons.Filled.CloudDownload, contentDescription = nativePhrase("获取模型列表", "Fetch model list"), tint = HlovetUi.accent, modifier = Modifier.size(18.dp))
        }
    }
}
@Composable
internal fun NativeAiPriceGrid(input: Long, cached: Long, output: Long, fallbackInput: Long, fallbackCached: Long, fallbackOutput: Long, enabled: Boolean, onChange: (Int, Long) -> Unit) {
    val values = listOf(input, cached, output, fallbackInput, fallbackCached, fallbackOutput)
    val labels = listOf(nativePhrase("输入价", "Input price"), nativePhrase("缓存输入价", "Cached input price"), nativePhrase("输出价", "Output price"), nativePhrase("备用输入价", "Fallback input price"), nativePhrase("备用缓存价", "Fallback cached price"), nativePhrase("备用输出价", "Fallback output price"))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (index in listOf(row, row + 3)) NativeAiPriceField(labels[index], values[index], enabled, Modifier.weight(1f)) { onChange(index, it) }
        }
    }
}
/** 展示单个价格输入字段，并在失焦时恢复服务端微单位对应的格式化值。 */
@Composable
private fun NativeAiPriceField(label: String, valueMicros: Long, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Long) -> Unit) {
    var text by remember { mutableStateOf(formatNativeAiPrice(valueMicros)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(valueMicros) {
        if (!focused) text = formatNativeAiPrice(valueMicros)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            val sanitized = sanitizeNativeAiPrice(next)
            text = sanitized
            parseNativeAiPrice(sanitized)?.let(onChange)
        },
        enabled = enabled,
        modifier = modifier.onFocusChanged { state ->
            focused = state.isFocused
            if (!state.isFocused) text = formatNativeAiPrice(valueMicros)
        },
        singleLine = true,
        label = { Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        textStyle = TextStyle(fontSize = 12.sp), shape = HlovetUi.controlShape,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = nativeAiFieldColors(),
    )
}

internal fun formatNativeAiPrice(valueMicros: Long): String = BigDecimal.valueOf(valueMicros, 6).stripTrailingZeros().toPlainString()

internal fun sanitizeNativeAiPrice(value: String): String {
    val filtered = value.filter { it.isDigit() || it == '.' }
    val dot = filtered.indexOf('.')
    if (dot < 0) return filtered
    val integer = filtered.substring(0, dot)
    val fraction = filtered.substring(dot + 1).filter(Char::isDigit).take(6)
    return integer + "." + fraction
}

internal fun parseNativeAiPrice(value: String): Long? = runCatching {
    BigDecimal(value).setScale(6, RoundingMode.HALF_UP).movePointRight(6).longValueExact().coerceAtLeast(0L)
}.getOrNull()

private fun nativeAiCapabilityLabel(item: NativeAiCapability): String =
    if (NativeI18n.locale == "en-US") item.labelEn.ifBlank { item.capability } else item.labelZh.ifBlank { item.capability }

@Composable
private fun NativeAiStat(label: String, value: String, modifier: Modifier) { GlassCard(modifier = modifier, shape = HlovetUi.rowShape) { Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text(label, color = HlovetUi.muted, fontSize = 9.sp) } } }
@Composable
private fun NativeAiAdminMessage(message: String, isError: Boolean, onDismiss: (() -> Unit)?) { GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (isError) Icons.Filled.Security else Icons.Filled.AutoAwesome, contentDescription = null, tint = if (isError) HlovetUi.secondaryAccent else HlovetUi.accent, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(message, color = if (isError) HlovetUi.secondaryAccent else HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.weight(1f)); onDismiss?.let { TextButton(onClick = it) { Text(nativePhrase("关闭", "Dismiss"), fontSize = 10.sp) } } } } }
@Composable private fun nativeAiFieldColors() = HlovetUi.fieldColors()
