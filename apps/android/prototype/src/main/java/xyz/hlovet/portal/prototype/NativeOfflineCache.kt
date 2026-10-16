package xyz.hlovet.portal.prototype

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** 按服务器来源和账号保存首页及媒体缓存，新版媒体共享固定预算，旧版来源不明的文件保留但不读取。 */
internal object NativeOfflineCache {
    private const val MAX_MEDIA_BYTES = 32L * 1024L * 1024L
    private const val MAX_DOWNLOAD_BYTES = 50L * 1024L * 1024L
    private const val MAX_PREFETCH_FILE_BYTES = 4L * 1024L * 1024L
    private const val MEDIA_DIRECTORY = "media-cache"
    private const val TEMP_MEDIA_DIRECTORY = "media-temp"

    /** 记录新版共享媒体占用及未纳入淘汰预算的旧版文件占用。 */
    internal data class MediaCacheSummary(
        val fileCount: Int,
        val bytes: Long,
        val maxBytes: Long,
        val legacyBytes: Long = 0L,
    )

    /** 将 data 原子保存到 context 中 apiBaseUrl 下 owner 的首页缓存，不覆盖其它来源或旧版缓存。 */
    @Synchronized
    fun savePreview(context: Context, data: PreviewData, owner: String = "public", apiBaseUrl: String = BuildConfig.API_BASE_URL) {
        // 写入来源元数据，文件被复制到其它来源时也不能被误当作该站点首页。
        runCatching {
            val file = AtomicFile(previewFile(context, owner, apiBaseUrl))
            val content = data.toJson().put("serverOrigin", NativeOfflineOrigin.origin(apiBaseUrl)).toString()
            val stream = file.startWrite()
            try {
                stream.write(content.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Exception) {
                // 保留上一次完整首页，写入失败不能用半份数据替换可用缓存。
                file.failWrite(stream)
                throw error
            }
        }
    }

    /** 读取 context 中 apiBaseUrl 下 owner 的首页；缺失、损坏或来源不匹配返回 null。 */
    @Synchronized
    fun loadPreview(context: Context, owner: String = "public", apiBaseUrl: String = BuildConfig.API_BASE_URL): PreviewData? = runCatching {
        // 仅读取新版本的来源文件，不回退到无法判定站点的旧首页。
        val file = previewFile(context, owner, apiBaseUrl)
        if (!file.isFile && !File(file.path + ".bak").isFile) return@runCatching null
        val value = JSONObject(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        if (value.optString("serverOrigin") != NativeOfflineOrigin.origin(apiBaseUrl)) return@runCatching null
        value.toPreviewData()
    }.getOrNull()

    /** 在 context 中按 mediaPaths 迁移 apiBaseUrl 下 legacyOwner 的首页和媒体到 stableOwner，旧版无来源文件不参与。 */
    @Synchronized
    fun migrateOwner(context: Context, stableOwner: String, legacyOwner: String, mediaPaths: List<String> = emptyList(), apiBaseUrl: String = BuildConfig.API_BASE_URL) {
        // 账号更名迁移只能在同一来源发生，公共缓存不接收账号文件。
        val normalizedStable = stableOwner.trim()
        val normalizedLegacy = legacyOwner.trim()
        if (normalizedStable.isBlank() || normalizedLegacy.isBlank() || normalizedStable == normalizedLegacy) return
        if (normalizedStable == "public" || normalizedLegacy == "public") return
        val source = previewFile(context, normalizedLegacy, apiBaseUrl)
        val target = previewFile(context, normalizedStable, apiBaseUrl)
        if (target.isFile) {
            AtomicFile(source).delete()
        } else if (source.isFile) {
            target.parentFile?.mkdirs()
            if (source.renameTo(target)) {
                AtomicFile(source).delete()
            } else {
                runCatching {
                    source.copyTo(target, overwrite = false)
                    AtomicFile(source).delete()
                }
            }
        }
        // 只迁移当前来源命名空间内引用的媒体，目标已有缓存优先。
        val directory = File(context.filesDir, MEDIA_DIRECTORY)
        mediaPaths.filter(String::isNotBlank).forEach { path ->
            // 默认构建保留网页/API 分域时的媒体解析规则，测试显式来源则按其 API 地址解析。
            val mediaPath = if (apiBaseUrl == BuildConfig.API_BASE_URL) nativeMediaUrl(path) else path
            val url = resolveUrl(mediaPath, apiBaseUrl)
            val newKey = NativeOfflineOrigin.key("media|$normalizedStable|$url", apiBaseUrl)
            listOf(url, path).distinct().forEach legacyMedia@ { oldUrl ->
                val oldKey = NativeOfflineOrigin.key("media|$normalizedLegacy|$oldUrl", apiBaseUrl)
                val oldFile = findCachedMediaFile(directory, oldKey) ?: return@legacyMedia
                val newFile = File(directory, newKey + oldFile.name.removePrefix(oldKey))
                runCatching {
                    if (!newFile.isFile) oldFile.copyTo(newFile)
                    oldFile.delete()
                }
            }
        }
    }

    /** 返回 context 中 apiBaseUrl 下 owner 的首页文件路径，不创建或读取文件。 */
    private fun previewFile(context: Context, owner: String, apiBaseUrl: String): File {
        // 匿名首页同样隔离来源，避免测试站的公开列表在生产站断网时出现。
        val normalizedOwner = owner.trim().ifBlank { "public" }
        return File(context.filesDir, "preview-${NativeOfflineOrigin.key("preview|$normalizedOwner", apiBaseUrl)}.json")
    }

    fun loadBitmap(
        context: Context,
        url: String,
        accessToken: String? = null,
        cacheOwner: String = "public",
        offlineOnly: Boolean = false,
    ): Bitmap? {
        val file = if (offlineOnly) {
            loadCachedMediaFile(
                context = context,
                url = url,
                cacheOwner = cacheOwner,
            )
        } else {
            loadMediaFile(
                context = context,
                url = url,
                accessToken = accessToken,
                cacheOwner = cacheOwner,
                originalName = mediaFileName(url, "cached-image"),
                mimeType = "image/*",
            )
        } ?: return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null && file.parentFile == File(context.filesDir, MEDIA_DIRECTORY)) {
            file.delete()
        }
        return bitmap?.also {
            file.setLastModified(System.currentTimeMillis())
        }
    }

    /** 为 context 的 cacheOwner 下载 url；apiBaseUrl 隔离缓存，accessToken 仅按既有白名单发送，originalName/mimeType 决定后缀，maxBytes 限制大小，失败返回 null。 */
    fun loadMediaFile(
        context: Context,
        url: String,
        accessToken: String? = null,
        cacheOwner: String,
        originalName: String,
        mimeType: String,
        maxBytes: Long = MAX_DOWNLOAD_BYTES,
        apiBaseUrl: String = BuildConfig.API_BASE_URL,
    ): File? {
        // 即使两个服务器引用同一 URL，同名账号也不能复用对方受保护附件。
        val resolvedUrl = resolveUrl(url, apiBaseUrl)
        val extension = fileExtension(originalName, mimeType)
        val cacheDirectory = File(context.filesDir, MEDIA_DIRECTORY).apply { mkdirs() }
        val cacheKey = NativeOfflineOrigin.key("media|$cacheOwner|$resolvedUrl", apiBaseUrl)
        findCachedMediaFile(cacheDirectory, cacheKey)?.let { cachedFile ->
            cachedFile.setLastModified(System.currentTimeMillis())
            return cachedFile
        }
        // 不尝试旧版无来源媒体键；新下载的内容仅进入当前来源键。
        val cachedFile = File(cacheDirectory, "$cacheKey$extension")

        val temporaryDirectory = File(context.cacheDir, TEMP_MEDIA_DIRECTORY).apply { mkdirs() }
        // 保留真正后缀在末尾，大附件临时打开时 FileProvider 仍能识别其 MIME 类型。
        val temporaryFile = File(temporaryDirectory, "${NativeOfflineOrigin.key("media|$cacheOwner|$resolvedUrl|${System.nanoTime()}", apiBaseUrl)}.tmp$extension")
        return runCatching {
            downloadToFile(resolvedUrl, accessToken, temporaryFile, maxBytes.coerceAtMost(MAX_DOWNLOAD_BYTES))
            // 超出持久缓存预算的文件只供本次打开，避免淘汰全部已缓存媒体。
            if (temporaryFile.length() <= MAX_MEDIA_BYTES) {
                if (!temporaryFile.renameTo(cachedFile)) {
                    temporaryFile.copyTo(cachedFile, overwrite = true)
                    temporaryFile.delete()
                }
                trimMediaCache(cacheDirectory)
                cachedFile
            } else {
                temporaryFile
            }
        }.getOrElse {
            // 下载失败只删除本次临时文件，不损坏旧缓存或其它来源内容。
            temporaryFile.delete()
            null
        }
    }

    /** 从 context 中读取 apiBaseUrl 下 cacheOwner 的 url 媒体，无网络请求，未命中返回 null。 */
    fun loadCachedMediaFile(
        context: Context,
        url: String,
        cacheOwner: String,
        apiBaseUrl: String = BuildConfig.API_BASE_URL,
    ): File? {
        // 离线回读与下载使用相同规范化 URL 和来源键，不做旧版跨来源兜底。
        val resolvedUrl = resolveUrl(url, apiBaseUrl)
        val cacheDirectory = File(context.filesDir, MEDIA_DIRECTORY)
        val cacheKey = NativeOfflineOrigin.key("media|$cacheOwner|$resolvedUrl", apiBaseUrl)
        return findCachedMediaFile(cacheDirectory, cacheKey)?.also {
            it.setLastModified(System.currentTimeMillis())
        }
    }

    /** 统计 context 中新版媒体共享预算，并单独返回仍保留的旧版媒体字节数。 */
    fun mediaCacheSummary(context: Context): MediaCacheSummary {
        val files = File(context.filesDir, MEDIA_DIRECTORY)
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
        // 来源不明的历史媒体不自动删除，也不挤占新版 32 MB 的淘汰预算。
        val current = files.filter { it.name.startsWith("v2-") }
        return MediaCacheSummary(
            fileCount = current.size,
            bytes = current.sumOf { it.length() },
            maxBytes = MAX_MEDIA_BYTES,
            legacyBytes = files.filterNot { it.name.startsWith("v2-") }.sumOf { it.length() },
        )
    }

    /**
     * Warm only a small number of image files for explicitly saved content.
     * This keeps offline text useful without turning one save action into a
     * full media mirror or allowing a large image to evict the whole cache.
     */
    fun prefetchImages(
        context: Context,
        paths: List<String>,
        accessToken: String?,
        cacheOwner: String,
        maxItems: Int = 4,
    ): Int {
        var cached = 0
        paths.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(::nativeMediaUrl)
            .distinct()
            .take(maxItems.coerceAtLeast(0))
            .forEachIndexed { index, url ->
                val file = loadMediaFile(
                    context = context,
                    url = url,
                    accessToken = accessToken,
                    cacheOwner = cacheOwner,
                    originalName = mediaFileName(url, "offline-image-$index.jpg"),
                    mimeType = "image/*",
                    maxBytes = MAX_PREFETCH_FILE_BYTES,
                )
                if (file != null) cached++
            }
        return cached
    }

    /** Warm a bounded set of media referenced by an explicitly saved article body. */
    fun prefetchMedia(
        context: Context,
        paths: List<String>,
        accessToken: String?,
        cacheOwner: String,
        maxItems: Int = 6,
    ): Int {
        var cached = 0
        paths.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(::nativeMediaUrl)
            .distinct()
            .take(maxItems.coerceAtLeast(0))
            .forEachIndexed { index, url ->
                val file = loadMediaFile(
                    context = context,
                    url = url,
                    accessToken = accessToken,
                    cacheOwner = cacheOwner,
                    originalName = mediaFileName(url, "offline-media-$index"),
                    mimeType = "application/octet-stream",
                    maxBytes = MAX_PREFETCH_FILE_BYTES,
                )
                if (file != null) cached++
            }
        return cached
    }

    private fun findCachedMediaFile(directory: File, cacheKey: String): File? = directory
        .listFiles()
        .orEmpty()
        .asSequence()
        .filter { file -> file.isFile && (file.name == cacheKey || file.name.startsWith("$cacheKey.")) }
        .maxByOrNull(File::lastModified)

    private fun mediaFileName(url: String, fallback: String): String = runCatching {
        URL(resolveUrl(url)).path.substringAfterLast('/').takeIf(String::isNotBlank) ?: fallback
    }.getOrDefault(fallback)

    /** 按最近使用时间淘汰 directory 的新版媒体，跨来源共享预算，但不清理旧版来源不明文件。 */
    private fun trimMediaCache(directory: File) {
        // 只收集带来源键的新文件，显式保存的正文和历史媒体不属于淘汰范围。
        val files = directory.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("v2-") }
        var total = files.sumOf { it.length() }
        if (total <= MAX_MEDIA_BYTES) return
        // 新缓存全局最多 32 MB，最早使用的文件先淘汰，直到符合预算。
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_MEDIA_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    private fun downloadToFile(url: String, accessToken: String?, target: File, maxBytes: Long) {
        val connection = openMediaConnection(resolveUrl(url), accessToken)
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("附件下载失败（HTTP $status）")
            val announcedLength = connection.contentLengthLong
            if (announcedLength > maxBytes) throw IllegalStateException("媒体超过移动端单文件缓存限制。")
            target.parentFile?.mkdirs()
            var total = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw IllegalStateException("媒体超过移动端单文件缓存限制。")
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openMediaConnection(url: String, accessToken: String?): HttpURLConnection {
        var current = URL(url)
        repeat(6) { redirectCount ->
            require(current.protocol == "https" || current.protocol == "http") { "Unsupported media URL" }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "*/*")
                if (!accessToken.isNullOrBlank() && nativeMediaMayReceiveToken(current.toString())) {
                    setRequestProperty("Authorization", "Bearer $accessToken")
                }
                setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            }
            val next = try {
                val status = connection.responseCode
                if (status !in setOf(301, 302, 303, 307, 308)) return connection
                check(redirectCount < 5) { "Too many media redirects" }
                val location = connection.getHeaderField("Location") ?: error("Missing media redirect URL")
                URL(current, location).also {
                    check(current.protocol != "https" || it.protocol == "https") { "Insecure media redirect" }
                }
            } catch (exception: Exception) {
                connection.disconnect()
                throw exception
            }
            connection.disconnect()
            current = next
        }
        error("Too many media redirects")
    }

    /** 将媒体 value 解析为 apiBaseUrl 对应的绝对 URL，绝对外部地址保持不变。 */
    private fun resolveUrl(value: String, apiBaseUrl: String = BuildConfig.API_BASE_URL): String {
        // 相对附件路径必须跟随缓存来源解析，不使用固定的生产服务器地址。
        val normalized = value.trim()
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) return normalized
        if (normalized.startsWith("//")) return "https:$normalized"
        val base = apiBaseUrl.trimEnd('/')
        return if (normalized.startsWith("/api/")) {
            base.substringBeforeLast("/api") + normalized
        } else {
            "$base/${normalized.trimStart('/')}"
        }
    }

    private fun fileExtension(originalName: String, mimeType: String): String {
        val nameExtension = originalName.substringAfterLast('.', "")
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.length in 1..8 && it.all { character -> character.isLetterOrDigit() } }
        if (nameExtension != null) return ".$nameExtension"
        return when {
            mimeType.equals("image/webp", ignoreCase = true) -> ".webp"
            mimeType.equals("image/png", ignoreCase = true) -> ".png"
            mimeType.equals("image/jpeg", ignoreCase = true) -> ".jpg"
            mimeType.equals("audio/mpeg", ignoreCase = true) -> ".mp3"
            mimeType.equals("video/mp4", ignoreCase = true) -> ".mp4"
            else -> ".bin"
        }
    }

    private fun PreviewData.toJson(): JSONObject = JSONObject().apply {
        put("articles", JSONArray().also { array -> articles.forEach { array.put(it.toJson()) } })
        put("topics", JSONArray().also { array -> topics.forEach { array.put(it.toJson()) } })
        put("collections", JSONArray().also { array -> collections.forEach { array.put(it.toJson()) } })
        put("appearance", appearance.toJson())
        put("backgroundUrl", backgroundUrl ?: JSONObject.NULL)
    }

    private fun PreviewAppearance.toJson(): JSONObject = JSONObject().apply {
        put("themeId", themeId)
        put("accent", accent)
        put("foreground", foreground)
        put("muted", muted)
        put("surface", surface)
        put("cardAlpha", cardAlpha)
        put("glassBlur", glassBlur)
        put("glassTint", glassTint)
        put("glassTintAlpha", glassTintAlpha)
    }

    private fun RemoteArticle.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("slug", slug)
        put("summary", summary)
        put("content", content)
        put("contentFormat", contentFormat)
        put("category", category)
        put("tags", JSONArray(tags))
        put("titleColor", titleColor)
        put("viewCount", viewCount)
        put("likeCount", likeCount)
        put("favoriteCount", favoriteCount)
        put("commentCount", commentCount)
        put("authorName", authorName)
        put("authorUsername", authorUsername)
        put("authorId", authorId)
        put("liked", liked)
        put("favorited", favorited)
        put("readLater", readLater)
        put("images", JSONArray(images))
    }

    private fun RemoteTopic.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("slug", slug)
        put("description", description)
        put("coverPath", coverPath ?: JSONObject.NULL)
        put("visibility", visibility)
        put("status", status)
        put("sortOrder", sortOrder)
        put("roleCodes", JSONArray(roleCodes))
        put("articleCount", articleCount)
        put("subscriberCount", subscriberCount)
        put("subscribed", subscribed)
        put("updatedAt", updatedAt)
        put("articles", JSONArray().also { array -> articles.forEach { array.put(it.toJson()) } })
    }

    private fun RemoteCollection.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("coverPath", coverPath ?: JSONObject.NULL)
        put("visibility", visibility)
        put("articleCount", articleCount)
        put("subscriberCount", subscriberCount)
        put("subscribed", subscribed)
        put("updatedAt", updatedAt)
        put("ownerName", ownerName)
        put("articles", JSONArray().also { array -> articles.forEach { array.put(it.toJson()) } })
    }

    private fun JSONObject.toPreviewData(): PreviewData = PreviewData(
        articles = optJSONArray("articles").toArticles(),
        topics = optJSONArray("topics").toTopics(),
        collections = optJSONArray("collections").toCollections(),
        appearance = (optJSONObject("appearance") ?: JSONObject()).toAppearance(),
        backgroundUrl = optString("backgroundUrl").takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() },
    )

    private fun JSONObject.toAppearance(): PreviewAppearance = PreviewAppearance(
        themeId = optString("themeId", "cloud-blue"),
        accent = optString("accent", "#0284c7"),
        foreground = optString("foreground", "#1f2937"),
        muted = optString("muted", "#52616f"),
        surface = optString("surface", "#ffffff"),
        cardAlpha = optInt("cardAlpha", 50),
        glassBlur = optInt("glassBlur", 18),
        glassTint = optString("glassTint", "#fff3f6"),
        glassTintAlpha = optInt("glassTintAlpha", 0),
    )

    private fun JSONArray?.toArticles(): List<RemoteArticle> = buildList {
        if (this@toArticles == null) return@buildList
        for (index in 0 until length()) add(getJSONObject(index).toArticle())
    }

    private fun JSONArray?.toTopics(): List<RemoteTopic> = buildList {
        if (this@toTopics == null) return@buildList
        for (index in 0 until length()) {
            val value = getJSONObject(index)
            add(RemoteTopic(
                id = value.optInt("id"),
                title = value.optString("title"),
                description = value.optString("description"),
                articleCount = value.optInt("articleCount"),
                subscriberCount = value.optInt("subscriberCount"),
                articles = value.optJSONArray("articles").toArticles(),
                slug = value.optString("slug"),
                coverPath = value.optString("coverPath").takeIf { it.isNotBlank() && it != "null" },
                visibility = value.optString("visibility", "public"),
                subscribed = value.optBoolean("subscribed"),
                updatedAt = value.optString("updatedAt"),
                status = value.optString("status", "active"),
                sortOrder = value.optInt("sortOrder"),
                roleCodes = value.optJSONArray("roleCodes").toStrings(),
            ))
        }
    }

    private fun JSONArray?.toCollections(): List<RemoteCollection> = buildList {
        if (this@toCollections == null) return@buildList
        for (index in 0 until length()) {
            val value = getJSONObject(index)
            add(RemoteCollection(
                id = value.optInt("id"),
                name = value.optString("name"),
                description = value.optString("description"),
                articleCount = value.optInt("articleCount"),
                subscriberCount = value.optInt("subscriberCount"),
                ownerName = value.optString("ownerName"),
                articles = value.optJSONArray("articles").toArticles(),
                coverPath = value.optString("coverPath").takeIf { it.isNotBlank() && it != "null" },
                visibility = value.optString("visibility", "public"),
                subscribed = value.optBoolean("subscribed"),
                updatedAt = value.optString("updatedAt"),
            ))
        }
    }

    private fun JSONObject.toArticle(): RemoteArticle = RemoteArticle(
        id = optInt("id"),
        title = optString("title"),
        slug = optString("slug"),
        summary = optString("summary"),
        content = optString("content"),
        contentFormat = optString("contentFormat", "markdown"),
        category = optString("category"),
        tags = optJSONArray("tags").toStrings(),
        titleColor = optString("titleColor"),
        viewCount = optInt("viewCount"),
        likeCount = optInt("likeCount"),
        favoriteCount = optInt("favoriteCount"),
        commentCount = optInt("commentCount"),
        authorName = optString("authorName"),
        authorUsername = optString("authorUsername"),
        authorId = optInt("authorId"),
        liked = optBoolean("liked"),
        favorited = optBoolean("favorited"),
        readLater = optBoolean("readLater"),
        images = optJSONArray("images").toStrings(),
    )

    private fun JSONArray?.toStrings(): List<String> = buildList {
        if (this@toStrings == null) return@buildList
        for (index in 0 until length()) add(optString(index))
    }
}

internal fun nativeMediaMayReceiveToken(url: String, apiBaseUrl: String = BuildConfig.API_BASE_URL): Boolean = runCatching {
    val target = URL(url)
    val api = URL(apiBaseUrl)
    target.protocol.equals(api.protocol, ignoreCase = true) &&
        target.host.equals(api.host, ignoreCase = true) &&
        (if (target.port == -1) target.defaultPort else target.port) ==
        (if (api.port == -1) api.defaultPort else api.port)
}.getOrDefault(false)
