package xyz.hlovet.portal.prototype

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 描述一条已保存内容的类型、稳定标识和来源命名空间内的文件位置。 */
internal data class NativeOfflineContentEntry(
    val kind: String,
    val id: String,
    val title: String,
    val route: String,
    val cachedAt: Long,
    val sizeBytes: Long,
    val fileName: String,
)

/** 按服务器来源和账号持久化显式保存的内容，正文不随媒体缓存淘汰。 */
internal object NativeOfflineContentStore {
    private const val DIRECTORY = "offline-content"
    private const val INDEX_SUFFIX = "-index.json"

    /** 检查 context 中 apiBaseUrl 下 owner 的 slug 文章是否已保存。 */
    fun isArticleSaved(context: Context, owner: String, slug: String, apiBaseUrl: String = BuildConfig.API_BASE_URL): Boolean =
        // 保存状态必须来自当前服务器和账号的索引。
        list(context, owner, apiBaseUrl).any { it.kind == "article" && it.id == slug }

    /** 检查 context 中 apiBaseUrl 下 owner 的 slug 专题是否已保存。 */
    fun isTopicSaved(context: Context, owner: String, slug: String, apiBaseUrl: String = BuildConfig.API_BASE_URL): Boolean =
        // 专题 slug 在不同站点可以相同，不能查询未隔离的旧索引。
        list(context, owner, apiBaseUrl).any { it.kind == "topic" && it.id == slug }

    /** 检查 context 中 apiBaseUrl 下 owner 的 id 合集是否已保存。 */
    fun isCollectionSaved(context: Context, owner: String, id: Int, apiBaseUrl: String = BuildConfig.API_BASE_URL): Boolean =
        // 合集 ID 仅在当前服务器内表示同一对象。
        list(context, owner, apiBaseUrl).any { it.kind == "collection" && it.id == id.toString() }

    /** 将 article 保存到 context 中 apiBaseUrl 的 owner 命名空间，返回更新后的索引条目。 */
    @Synchronized
    fun saveArticle(context: Context, owner: String, article: RemoteArticle, apiBaseUrl: String = BuildConfig.API_BASE_URL): NativeOfflineContentEntry {
        // 来源和账号共同确定文件前缀，正文原子写入成功后才更新索引。
        val safeOwner = ownerKey(owner, apiBaseUrl)
        val fileName = fileName(safeOwner, "article", article.slug.ifBlank { article.id.toString() })
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val file = File(directory, fileName)
        writeAtomically(file, article.toJson().toString())
        // 记录稳定内容标识和实际文件大小，覆盖保存不会影响其它已保存条目。
        val entry = NativeOfflineContentEntry(
            kind = "article",
            id = article.slug.ifBlank { article.id.toString() },
            title = article.title.ifBlank { "未命名文章" },
            route = "/articles/${article.slug}",
            cachedAt = System.currentTimeMillis(),
            sizeBytes = file.length(),
            fileName = fileName,
        )
        upsert(context, safeOwner, entry)
        return entry
    }

    /** 读取 context 中 apiBaseUrl 下 owner 的 slug 文章；缺失或损坏时返回 null。 */
    @Synchronized
    fun loadArticle(context: Context, owner: String, slug: String, apiBaseUrl: String = BuildConfig.API_BASE_URL): RemoteArticle? {
        // 只从当前来源索引定位正文，不回退到来源不明的旧文件。
        val entry = list(context, owner, apiBaseUrl).firstOrNull { it.kind == "article" && it.id == slug } ?: return null
        val file = File(File(context.filesDir, DIRECTORY), entry.fileName)
        return runCatching { JSONObject(readAtomically(file)).toRemoteArticle() }.getOrNull()
    }

    /** 将 topic 及其文章清单保存到 context 中 apiBaseUrl 的 owner 命名空间，返回条目。 */
    @Synchronized
    fun saveTopic(context: Context, owner: String, topic: RemoteTopic, apiBaseUrl: String = BuildConfig.API_BASE_URL): NativeOfflineContentEntry {
        // slug 优先作为专题标识，来源摘要避免另一站点同名专题覆盖当前保存。
        val safeOwner = ownerKey(owner, apiBaseUrl)
        val id = topic.slug.ifBlank { topic.id.toString() }
        val fileName = fileName(safeOwner, "topic", id)
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val file = File(directory, fileName)
        writeAtomically(file, topic.toJson().toString())
        // 正文写入完成后更新该来源账号的索引，保留其它类型内容。
        val entry = NativeOfflineContentEntry(
            kind = "topic",
            id = id,
            title = topic.title.ifBlank { "未命名专题" },
            route = "/topics/${topic.slug.ifBlank { id }}",
            cachedAt = System.currentTimeMillis(),
            sizeBytes = file.length(),
            fileName = fileName,
        )
        upsert(context, safeOwner, entry)
        return entry
    }

    /** 读取 context 中 apiBaseUrl 下 owner 的 slug 专题；缺失或损坏时返回 null。 */
    @Synchronized
    fun loadTopic(context: Context, owner: String, slug: String, apiBaseUrl: String = BuildConfig.API_BASE_URL): RemoteTopic? {
        // 专题离线回读必须沿用保存时的服务器与账号边界。
        val entry = list(context, owner, apiBaseUrl).firstOrNull { it.kind == "topic" && it.id == slug } ?: return null
        val file = File(File(context.filesDir, DIRECTORY), entry.fileName)
        return runCatching { JSONObject(readAtomically(file)).toRemoteTopic() }.getOrNull()
    }

    /** 将 collection 保存到 context 中 apiBaseUrl 下 owner 的独立文件并返回条目。 */
    @Synchronized
    fun saveCollection(context: Context, owner: String, collection: RemoteCollection, apiBaseUrl: String = BuildConfig.API_BASE_URL): NativeOfflineContentEntry {
        // 合集与文章清单一同原子持久化，来源不同的相同 ID 使用不同文件。
        val safeOwner = ownerKey(owner, apiBaseUrl)
        val id = collection.id.toString()
        val fileName = fileName(safeOwner, "collection", id)
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val file = File(directory, fileName)
        writeAtomically(file, collection.toJson().toString())
        // 索引只指向已完成写入的当前来源文件。
        val entry = NativeOfflineContentEntry(
            kind = "collection",
            id = id,
            title = collection.name.ifBlank { "未命名合集" },
            route = "/collections/$id",
            cachedAt = System.currentTimeMillis(),
            sizeBytes = file.length(),
            fileName = fileName,
        )
        upsert(context, safeOwner, entry)
        return entry
    }

    /** 读取 context 中 apiBaseUrl 下 owner 的 id 合集；缺失或损坏时返回 null。 */
    @Synchronized
    fun loadCollection(context: Context, owner: String, id: Int, apiBaseUrl: String = BuildConfig.API_BASE_URL): RemoteCollection? {
        // 未命中当前命名空间时不尝试其它来源或旧版索引。
        val entry = list(context, owner, apiBaseUrl).firstOrNull { it.kind == "collection" && it.id == id.toString() } ?: return null
        val file = File(File(context.filesDir, DIRECTORY), entry.fileName)
        return runCatching { JSONObject(readAtomically(file)).toRemoteCollection() }.getOrNull()
    }

    /** 返回 context 中 apiBaseUrl 下 owner 的有效已保存条目，缺失或损坏索引返回空列表。 */
    @Synchronized
    fun list(context: Context, owner: String, apiBaseUrl: String = BuildConfig.API_BASE_URL): List<NativeOfflineContentEntry> {
        // 来源先参与账号键，再读取索引，用户名或 ID 相同不会扩大查询范围。
        return readEntries(context, ownerKey(owner, apiBaseUrl))
    }

    /** 在 context 中仅迁移 apiBaseUrl 下 legacyOwner 到 stableOwner，幂等合并，不读取未绑定来源的旧索引。 */
    @Synchronized
    fun migrateOwner(context: Context, stableOwner: String, legacyOwner: String, apiBaseUrl: String = BuildConfig.API_BASE_URL) {
        // 拒绝公共账号或同名迁移，防止账号内容混入匿名缓存。
        val normalizedStable = stableOwner.trim()
        val normalizedLegacy = legacyOwner.trim()
        if (normalizedStable.isBlank() || normalizedLegacy.isBlank() || normalizedStable == normalizedLegacy) return
        if (normalizedStable == "public" || normalizedLegacy == "public") return

        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val sourceKey = ownerKey(normalizedLegacy, apiBaseUrl)
        val targetKey = ownerKey(normalizedStable, apiBaseUrl)
        if (sourceKey == targetKey) return
        val sourceEntries = readEntries(context, sourceKey)
        if (sourceEntries.isEmpty()) return

        // 按类型和稳定内容 ID 去重，目标已有内容优先，失败的源文件保留供重试。
        val targetEntries = readEntries(context, targetKey)
        val merged = targetEntries.toMutableList()
        val seen = targetEntries.mapTo(mutableSetOf()) { "${it.kind}|${it.id}" }
        val sourceFilesToDelete = mutableListOf<File>()
        var complete = true

        sourceEntries.forEach { sourceEntry ->
            val sourceFile = File(directory, sourceEntry.fileName)
            val identity = "${sourceEntry.kind}|${sourceEntry.id}"
            if (identity in seen) {
                sourceFilesToDelete += sourceFile
                return@forEach
            }

            val targetFileName = fileName(targetKey, sourceEntry.kind, sourceEntry.id)
            val targetFile = File(directory, targetFileName)
            val copied = if (targetFile.isFile) true else copyFile(sourceFile, targetFile)
            if (copied) {
                sourceFilesToDelete += sourceFile
                merged += sourceEntry.copy(
                    sizeBytes = targetFile.length(),
                    fileName = targetFileName,
                )
                seen += identity
            } else {
                complete = false
            }
        }

        // 完整写入目标索引后才能清理成功迁移的文件，来源不明文件不会进入此流程。
        runCatching { writeIndex(context, targetKey, merged) }
            .onSuccess {
                sourceFilesToDelete.forEach { AtomicFile(it).delete() }
                if (complete) AtomicFile(File(directory, sourceKey + INDEX_SUFFIX)).delete()
            }
    }

    /** 读取 context 的 key 索引，只接受该命名空间对应的内容文件名，损坏记录返回空列表。 */
    private fun readEntries(context: Context, key: String): List<NativeOfflineContentEntry> {
        // 索引拷贝或伪造不能引用其它来源文件，也不能借相对路径跳出内容目录。
        val index = File(File(context.filesDir, DIRECTORY), key + INDEX_SUFFIX)
        return runCatching {
            val array = JSONArray(readAtomically(index))
            buildList {
                for (position in 0 until array.length()) {
                    array.optJSONObject(position)?.let { value ->
                        val storedName = value.optString("fileName")
                        val expectedName = fileName(key, value.optString("kind"), value.optString("id"))
                        val file = File(index.parentFile, storedName)
                        if (storedName == expectedName && (file.isFile || File(file.path + ".bak").isFile)) add(value.toEntry())
                    }
                }
            }.sortedByDescending { it.cachedAt }
        }.getOrDefault(emptyList())
    }

    /** 删除 context 中 apiBaseUrl 下 owner 的 entry，外部来源或其它账号的条目不执行删除。 */
    @Synchronized
    fun delete(context: Context, owner: String, entry: NativeOfflineContentEntry, apiBaseUrl: String = BuildConfig.API_BASE_URL) {
        // 必须先确认条目属于当前来源索引，再改动文件及索引。
        val key = ownerKey(owner, apiBaseUrl)
        if (list(context, owner, apiBaseUrl).none { it.fileName == entry.fileName }) return
        AtomicFile(File(File(context.filesDir, DIRECTORY), entry.fileName)).delete()
        writeIndex(context, key, list(context, owner, apiBaseUrl).filterNot { it.fileName == entry.fileName })
    }

    /** 清理 context 中 apiBaseUrl 下 owner 的内容，不清理其它服务器及未记录来源的旧文件。 */
    @Synchronized
    fun clear(context: Context, owner: String, apiBaseUrl: String = BuildConfig.API_BASE_URL) {
        // 清空限定为当前来源账号索引中的文件，不能按全目录删除。
        val key = ownerKey(owner, apiBaseUrl)
        list(context, owner, apiBaseUrl).forEach { AtomicFile(File(File(context.filesDir, DIRECTORY), it.fileName)).delete() }
        AtomicFile(File(File(context.filesDir, DIRECTORY), key + INDEX_SUFFIX)).delete()
    }

    private fun upsert(context: Context, ownerKey: String, entry: NativeOfflineContentEntry) {
        val current = readEntries(context, ownerKey).filterNot { it.fileName == entry.fileName }
        writeIndex(context, ownerKey, current + entry)
    }

    private fun writeIndex(context: Context, ownerKey: String, entries: List<NativeOfflineContentEntry>) {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val target = File(directory, ownerKey + INDEX_SUFFIX)
        writeAtomically(target, JSONArray().apply { entries.forEach { put(it.toJson()) } }.toString())
    }

    private fun writeAtomically(target: File, content: String) {
        val file = AtomicFile(target)
        val output = file.startWrite()
        try {
            output.write(content.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (exception: Exception) {
            file.failWrite(output)
            throw exception
        }
    }

    private fun readAtomically(target: File): String = AtomicFile(target).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** 返回 context 中 apiBaseUrl 下 owner 已保存内容引用的媒体路径，供同来源账号迁移使用。 */
    @Synchronized
    fun mediaPaths(context: Context, owner: String, apiBaseUrl: String = BuildConfig.API_BASE_URL): List<String> =
        // 媒体来源跟随正文索引，避免迁移另一服务器的受保护文件。
        list(context, owner, apiBaseUrl).flatMap { entry ->
            when (entry.kind) {
                "article" -> loadArticle(context, owner, entry.id, apiBaseUrl)?.let(::nativeOfflineArticleMediaPaths).orEmpty()
                "topic" -> loadTopic(context, owner, entry.id, apiBaseUrl)?.let { topic ->
                    listOfNotNull(topic.coverPath) + topic.articles.flatMap(::nativeOfflineArticleMediaPaths)
                }.orEmpty()
                "collection" -> entry.id.toIntOrNull()?.let { loadCollection(context, owner, it, apiBaseUrl) }?.let { collection ->
                    listOfNotNull(collection.coverPath) + collection.articles.flatMap(::nativeOfflineArticleMediaPaths)
                }.orEmpty()
                else -> emptyList()
            }
        }.distinct()

    private fun copyFile(source: File, target: File): Boolean {
        if (!source.isFile) return false
        target.parentFile?.mkdirs()
        return runCatching {
            source.copyTo(target, overwrite = false)
            true
        }.getOrDefault(false)
    }

    /** 为 apiBaseUrl 下 owner 生成内容存储前缀，空账号归入该服务器公共缓存。 */
    private fun ownerKey(owner: String, apiBaseUrl: String): String =
        // 类型前缀让正文索引与首页、媒体、草稿的键保持独立。
        NativeOfflineOrigin.key("content|${owner.ifBlank { "public" }}", apiBaseUrl)

    private fun fileName(ownerKey: String, kind: String, id: String): String =
        "$ownerKey-${sha256("$kind|$id").take(32)}.json"

    private fun NativeOfflineContentEntry.toJson() = JSONObject().apply {
        put("kind", kind); put("id", id); put("title", title); put("route", route)
        put("cachedAt", cachedAt); put("sizeBytes", sizeBytes); put("fileName", fileName)
    }

    private fun JSONObject.toEntry() = NativeOfflineContentEntry(
        kind = optString("kind"), id = optString("id"), title = optString("title"), route = optString("route"),
        cachedAt = optLong("cachedAt"), sizeBytes = optLong("sizeBytes"), fileName = optString("fileName"),
    )

    private fun RemoteArticle.toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("slug", slug); put("summary", summary); put("content", content)
        put("contentFormat", contentFormat); put("category", category); put("tags", JSONArray(tags)); put("titleColor", titleColor)
        put("viewCount", viewCount); put("likeCount", likeCount); put("favoriteCount", favoriteCount); put("commentCount", commentCount)
        put("authorName", authorName); put("authorUsername", authorUsername); put("authorId", authorId)
        put("liked", liked); put("favorited", favorited); put("readLater", readLater); put("images", JSONArray(images)); put("updatedAt", updatedAt)
        put("resourceEnabled", resourceEnabled)
        put("contentSegments", JSONArray().also { array -> contentSegments.forEach { segment -> array.put(segment.toJson()) } })
        put("resourceBlocks", JSONArray().also { array -> resourceBlocks.forEach { block -> array.put(block.toJson()) } })
        put("status", status); put("blockedReason", blockedReason ?: JSONObject.NULL); put("isPinned", isPinned)
        put("visibility", visibility); put("allowedRoleCodes", JSONArray(allowedRoleCodes)); put("pinOrder", pinOrder)
        put("readingProgress", readingProgress ?: JSONObject.NULL); put("lastReadAt", lastReadAt ?: JSONObject.NULL)
    }

    private fun RemoteTopic.toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("slug", slug); put("description", description)
        put("articleCount", articleCount); put("subscriberCount", subscriberCount); put("coverPath", coverPath ?: JSONObject.NULL)
        put("visibility", visibility); put("subscribed", subscribed); put("updatedAt", updatedAt)
        put("status", status); put("sortOrder", sortOrder); put("roleCodes", JSONArray(roleCodes))
        put("articles", JSONArray().also { array -> articles.forEach { array.put(it.toJson()) } })
    }

    private fun RemoteCollection.toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("description", description)
        put("articleCount", articleCount); put("subscriberCount", subscriberCount); put("ownerName", ownerName)
        put("coverPath", coverPath ?: JSONObject.NULL); put("visibility", visibility); put("subscribed", subscribed)
        put("updatedAt", updatedAt)
        put("articles", JSONArray().also { array -> articles.forEach { array.put(it.toJson()) } })
    }

    private fun JSONObject.toRemoteArticle() = RemoteArticle(
        id = optInt("id"), title = optString("title"), slug = optString("slug"), summary = optString("summary"), content = optString("content"),
        contentFormat = optString("contentFormat", "markdown"), category = optString("category"), tags = optJSONArray("tags").toStrings(),
        titleColor = optString("titleColor"),
        viewCount = optInt("viewCount"), likeCount = optInt("likeCount"), favoriteCount = optInt("favoriteCount"), commentCount = optInt("commentCount"),
        authorName = optString("authorName"), authorUsername = optString("authorUsername"), authorId = optInt("authorId"), liked = optBoolean("liked"),
        favorited = optBoolean("favorited"), readLater = optBoolean("readLater"), images = optJSONArray("images").toStrings(), updatedAt = optString("updatedAt"),
        resourceEnabled = optBoolean("resourceEnabled"), contentSegments = optJSONArray("contentSegments").toSegments(), resourceBlocks = optJSONArray("resourceBlocks").toResourceBlocks(),
        status = optString("status", "published"), blockedReason = optString("blockedReason").takeIf { it.isNotBlank() && it != "null" }, isPinned = optBoolean("isPinned"),
        visibility = optString("visibility", "public"), allowedRoleCodes = optJSONArray("allowedRoleCodes").toStrings(), pinOrder = optInt("pinOrder"),
        readingProgress = if (isNull("readingProgress")) null else optInt("readingProgress"), lastReadAt = optString("lastReadAt").takeIf { it.isNotBlank() && it != "null" },
    )

    private fun JSONObject.toRemoteTopic() = RemoteTopic(
        id = optInt("id"), title = optString("title"), slug = optString("slug"), description = optString("description"),
        articleCount = optInt("articleCount"), subscriberCount = optInt("subscriberCount"),
        articles = optJSONArray("articles").toRemoteArticles(), coverPath = optString("coverPath").takeIf { it.isNotBlank() && it != "null" },
        visibility = optString("visibility", "public"), subscribed = optBoolean("subscribed"), updatedAt = optString("updatedAt"),
        status = optString("status", "active"), sortOrder = optInt("sortOrder"), roleCodes = optJSONArray("roleCodes").toStrings(),
    )

    private fun JSONObject.toRemoteCollection() = RemoteCollection(
        id = optInt("id"), name = optString("name"), description = optString("description"),
        articleCount = optInt("articleCount"), subscriberCount = optInt("subscriberCount"), ownerName = optString("ownerName"),
        articles = optJSONArray("articles").toRemoteArticles(), coverPath = optString("coverPath").takeIf { it.isNotBlank() && it != "null" },
        visibility = optString("visibility", "public"), subscribed = optBoolean("subscribed"), updatedAt = optString("updatedAt"),
    )

    private fun JSONArray?.toRemoteArticles(): List<RemoteArticle> = buildList {
        for (position in 0 until (this@toRemoteArticles?.length() ?: 0)) {
            this@toRemoteArticles?.optJSONObject(position)?.let { add(it.toRemoteArticle()) }
        }
    }

    private fun RemoteArticleSegment.toJson() = JSONObject().apply {
        put("type", type); put("content", content ?: JSONObject.NULL); put("key", key ?: JSONObject.NULL)
        put("pointCost", pointCost ?: JSONObject.NULL); put("unlocked", unlocked)
    }

    private fun RemoteResourceBlock.toJson() = JSONObject().apply {
        put("key", key); put("pointCost", pointCost); put("unlocked", unlocked)
    }

    private fun JSONArray?.toSegments(): List<RemoteArticleSegment> = buildList {
        for (position in 0 until (this@toSegments?.length() ?: 0)) {
            this@toSegments?.optJSONObject(position)?.let { value ->
                add(RemoteArticleSegment(
                    type = value.optString("type"),
                    content = value.optString("content").takeIf { it.isNotBlank() && it != "null" },
                    key = value.optString("key").takeIf { it.isNotBlank() && it != "null" },
                    pointCost = if (value.isNull("pointCost") || !value.has("pointCost")) null else value.optInt("pointCost"),
                    unlocked = value.optBoolean("unlocked", true),
                ))
            }
        }
    }

    private fun JSONArray?.toResourceBlocks(): List<RemoteResourceBlock> = buildList {
        for (position in 0 until (this@toResourceBlocks?.length() ?: 0)) {
            this@toResourceBlocks?.optJSONObject(position)?.let { value ->
                add(RemoteResourceBlock(value.optString("key"), value.optInt("pointCost"), value.optBoolean("unlocked")))
            }
        }
    }

    private fun JSONArray?.toStrings(): List<String> = buildList {
        for (position in 0 until (this@toStrings?.length() ?: 0)) add(this@toStrings?.optString(position).orEmpty())
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
