package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal enum class NativeRecommendationTargetType(val wireValue: String, val labelZh: String, val labelEn: String) {
    ALL("all", "全部类型", "All types"),
    ARTICLE("article", "文章", "Articles"),
    TOPIC("topic", "专题", "Topics"),
    COLLECTION("collection", "合集", "Collections"),
    AUTHOR("author", "作者", "Authors"),
    GROUP("group", "群聊", "Groups"),
}

internal data class NativeRecommendationFeedbackItem(
    val id: Int,
    val targetType: NativeRecommendationTargetType,
    val targetId: Int,
    val label: String,
    val createdAt: String,
)

internal object NativeRecommendationFeedbackApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun record(accessToken: String, targetType: NativeRecommendationTargetType, targetId: Int) {
        request(
            path = "/discovery/recommendations/feedback",
            method = "POST",
            accessToken = accessToken,
            body = JSONObject().put("targetType", targetType.wireValue).put("targetId", targetId),
        )
    }

    fun list(accessToken: String, targetType: NativeRecommendationTargetType = NativeRecommendationTargetType.ALL): List<NativeRecommendationFeedbackItem> {
        val suffix = if (targetType == NativeRecommendationTargetType.ALL) "" else "?targetType=${targetType.wireValue}"
        return request(
            path = "/discovery/recommendations/feedback$suffix",
            method = "GET",
            accessToken = accessToken,
        ).optJSONArray("items").toItems()
    }

    fun restore(accessToken: String, item: NativeRecommendationFeedbackItem) {
        request(
            path = "/discovery/recommendations/feedback/${item.targetType.wireValue}/${item.targetId}",
            method = "DELETE",
            accessToken = accessToken,
        )
    }

    fun clear(accessToken: String, targetType: NativeRecommendationTargetType = NativeRecommendationTargetType.ALL): Int {
        val suffix = if (targetType == NativeRecommendationTargetType.ALL) "" else "?targetType=${targetType.wireValue}"
        return request(
            path = "/discovery/recommendations/feedback$suffix",
            method = "DELETE",
            accessToken = accessToken,
        ).optInt("count")
    }

    private fun request(path: String, method: String, accessToken: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
            doOutput = body != null
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) setRequestProperty("Content-Type", "application/json")
        }
        return try {
            body?.let { connection.outputStream.use { output -> output.write(it.toString().toByteArray(Charsets.UTF_8)) } }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(errorMessage(response, "推荐反馈操作失败（HTTP $status）。"))
            if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(body: String, fallback: String): String = runCatching {
        when (val message = JSONObject(body).opt("message")) {
            is JSONArray -> message.join("、")
            is String -> message
            else -> fallback
        }
    }.getOrDefault(fallback).ifBlank { fallback }

    private fun JSONArray?.toItems(): List<NativeRecommendationFeedbackItem> = buildList {
        for (index in 0 until (this@toItems?.length() ?: 0)) {
            val item = this@toItems?.optJSONObject(index) ?: continue
            add(
                NativeRecommendationFeedbackItem(
                    id = item.optInt("id"),
                    targetType = item.optString("targetType").toRecommendationTargetType(),
                    targetId = item.optInt("targetId"),
                    label = item.optString("label", "不可见内容"),
                    createdAt = item.optString("createdAt"),
                ),
            )
        }
    }

    private fun String.toRecommendationTargetType(): NativeRecommendationTargetType =
        NativeRecommendationTargetType.entries.firstOrNull { it.wireValue == this } ?: NativeRecommendationTargetType.ARTICLE
}
