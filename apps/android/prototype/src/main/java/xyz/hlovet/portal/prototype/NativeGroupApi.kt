package xyz.hlovet.portal.prototype

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

internal data class NativeGroupUser(
    val id: Int,
    val nickname: String,
    val username: String,
    val avatarUrl: String?,
) {
    val initials: String
        get() = (nickname.ifBlank { username }.ifBlank { "群友" }).takeLast(2)
}

internal data class NativeGroupSummary(
    val id: Int,
    val conversationId: Int,
    val owner: NativeGroupUser,
    val name: String,
    val avatarUrl: String?,
    val announcement: String,
    val joinMode: String,
    val membersCanInvite: Boolean,
    val memberLimit: Int,
    val memberCount: Int,
    val temporary: Boolean,
    val expiresAt: String?,
    val isBanned: Boolean,
    val bannedUntil: String?,
    val banReason: String?,
    val currentMemberRole: String?,
    val currentAlias: String?,
    val canManage: Boolean,
    val canModerate: Boolean,
    val canInvite: Boolean,
    val pendingJoinRequestCount: Int,
    val pendingReportCount: Int,
)

internal data class NativeGroupMember(
    val user: NativeGroupUser,
    val role: String,
    val status: String,
    val alias: String?,
    val mutedUntil: String?,
    val joinedAt: String,
    val isSelf: Boolean,
)

internal data class NativeGroupDetail(
    val summary: NativeGroupSummary,
    val members: List<NativeGroupMember>,
    val banRecords: List<NativeGroupBanRecord>,
)

internal data class NativeGroupBanRecord(
    val id: Int,
    val reason: String,
    val startsAt: String,
    val expiresAt: String?,
    val liftedAt: String?,
    val createdAt: String,
    val actor: NativeGroupUser,
    val liftedBy: NativeGroupUser?,
)

internal data class NativeGroupInvitation(
    val id: Int,
    val group: NativeGroupSummary,
    val inviter: NativeGroupUser,
    val status: String,
    val expiresAt: String,
)

internal data class NativeGroupJoinRequest(
    val id: Int,
    val groupId: Int,
    val groupName: String,
    val user: NativeGroupUser,
    val note: String?,
    val status: String,
)

internal data class NativeGroupApprovals(
    val invitations: List<NativeGroupInvitation>,
    val joinRequests: List<NativeGroupJoinRequest>,
)

internal data class NativeAdminGroupReport(
    val id: Int,
    val group: NativeGroupSummary,
    val message: NativeChatMessage,
    val reporter: NativeGroupUser,
    val reason: String,
    val detail: String?,
    val status: String,
    val resolution: String?,
    val createdAt: String,
)

internal object NativeGroupApi {
    private const val DEFAULT_BASE_URL = "https://5200918.xyz/api"

    private val baseUrl: String
        get() = BuildConfig.API_BASE_URL.trimEnd('/').ifBlank { DEFAULT_BASE_URL }

    fun listMine(accessToken: String): List<NativeGroupSummary> =
        request("/social/groups", "GET", accessToken).optJSONArray("items").toGroupSummaries()

    fun listAdmin(accessToken: String, query: String = ""): List<NativeGroupSummary> {
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        return request("/social/groups/admin?q=$encoded&limit=50", "GET", accessToken)
            .optJSONArray("items")
            .toGroupSummaries()
    }

    fun listAdminReports(accessToken: String, status: String = "pending", groupId: Int? = null): List<NativeAdminGroupReport> {
        val encoded = URLEncoder.encode(status.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        val groupQuery = groupId?.takeIf { it > 0 }?.let { "&groupId=$it" }.orEmpty()
        val values = request("/social/group-reports?status=$encoded$groupQuery", "GET", accessToken).optJSONArray("items")
        return buildList {
            if (values == null) return@buildList
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                add(
                    NativeAdminGroupReport(
                        id = value.optInt("id"),
                        group = parseGroupSummary(value.optJSONObject("group") ?: JSONObject()),
                        message = NativeSocialApi.parseChatMessage(value.optJSONObject("message") ?: JSONObject()),
                        reporter = parseUser(value.optJSONObject("reporter")),
                        reason = value.optString("reason", "other"),
                        detail = value.optString("detail").takeIf { it.isNotBlank() && it != "null" },
                        status = value.optString("status", "pending"),
                        resolution = value.optString("resolution").takeIf { it.isNotBlank() && it != "null" },
                        createdAt = value.optString("createdAt"),
                    ),
                )
            }
        }
    }

    fun banAdminGroup(accessToken: String, groupId: Int, permanent: Boolean, durationMinutes: Int?, reason: String): NativeGroupSummary {
        val body = JSONObject().apply {
            put("permanent", permanent)
            if (!permanent && durationMinutes != null) put("durationMinutes", durationMinutes)
            put("reason", reason.trim())
        }
        return parseGroupSummary(request("/social/groups/$groupId/ban", "PATCH", accessToken, body))
    }

    fun liftAdminGroupBan(accessToken: String, groupId: Int): NativeGroupSummary =
        parseGroupSummary(request("/social/groups/$groupId/ban", "DELETE", accessToken))

    fun handleAdminGroupReport(accessToken: String, reportId: Int, status: String, resolution: String, deleteMessage: Boolean) {
        request(
            "/social/group-reports/$reportId",
            "PATCH",
            accessToken,
            JSONObject()
                .put("status", status)
                .put("resolution", resolution.trim())
                .put("deleteMessage", deleteMessage),
        )
    }

    fun search(accessToken: String, query: String): List<NativeGroupSummary> {
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        return request("/social/groups/search?q=$encoded&limit=30", "GET", accessToken)
            .optJSONArray("items")
            .toGroupSummaries()
    }

    fun searchUsers(accessToken: String, query: String): List<NativeGroupUser> {
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name()).replace("+", "%20")
        val response = request("/social/users/search?q=$encoded&limit=20", "GET", accessToken)
        return buildList {
            val values = response.optJSONArray("items") ?: return@buildList
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                add(parseUser(value))
            }
        }
    }

    fun get(accessToken: String, groupId: Int): NativeGroupDetail =
        parseGroupDetail(request("/social/groups/$groupId", "GET", accessToken))

    fun create(
        accessToken: String,
        name: String,
        temporary: Boolean,
        ttlDays: Int,
        joinMode: String,
    ): NativeGroupDetail = parseGroupDetail(
        request(
            "/social/groups",
            "POST",
            accessToken,
            JSONObject()
                .put("name", name.trim())
                .put("temporary", temporary)
                .put("ttlDays", ttlDays.coerceIn(1, 30))
                .put("joinMode", if (joinMode == "invite_only") "invite_only" else "approval"),
        ),
    )

    fun update(
        accessToken: String,
        groupId: Int,
        name: String? = null,
        announcement: String? = null,
        joinMode: String? = null,
        membersCanInvite: Boolean? = null,
    ): NativeGroupDetail {
        val body = JSONObject()
        name?.let { body.put("name", it.trim()) }
        announcement?.let { body.put("announcement", it.trim()) }
        joinMode?.let { body.put("joinMode", it) }
        membersCanInvite?.let { body.put("membersCanInvite", it) }
        return parseGroupDetail(request("/social/groups/$groupId", "PATCH", accessToken, body))
    }

    fun updateAlias(accessToken: String, groupId: Int, alias: String): NativeGroupDetail =
        parseGroupDetail(
            request(
                "/social/groups/$groupId/alias",
                "PATCH",
                accessToken,
                JSONObject().put("alias", alias.trim()),
            ),
        )

    fun invite(accessToken: String, groupId: Int, userIds: List<Int>) {
        request(
            "/social/groups/$groupId/invitations",
            "POST",
            accessToken,
            JSONObject().put("userIds", JSONArray(userIds)),
        )
    }

    fun listApprovals(accessToken: String): NativeGroupApprovals {
        val response = request("/social/group-approvals", "GET", accessToken)
        val invitations = response.optJSONArray("invitations").toInvitations()
        val requests = buildList {
            val values = response.optJSONArray("joinRequests") ?: return@buildList
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val group = value.optJSONObject("group")
                add(
                    NativeGroupJoinRequest(
                        id = value.optInt("id"),
                        groupId = value.optInt("groupId", group?.optInt("id") ?: 0),
                        groupName = group?.optString("name").orEmpty().ifBlank { "群聊" },
                        user = parseUser(value.optJSONObject("user")),
                        note = value.optString("note").takeIf { it.isNotBlank() && it != "null" },
                        status = value.optString("status", "pending"),
                    ),
                )
            }
        }
        return NativeGroupApprovals(invitations, requests)
    }

    fun respondInvitation(accessToken: String, invitationId: Int, status: String) {
        request(
            "/social/group-invitations/$invitationId/respond",
            "PATCH",
            accessToken,
            JSONObject().put("status", status),
        )
    }

    fun respondInvitationByGroup(accessToken: String, groupId: Int, status: String): NativeGroupDetail? {
        return request(
            "/social/groups/$groupId/invitation/respond",
            "PATCH",
            accessToken,
            JSONObject().put("status", status),
        ).optJSONObject("group")?.let(::parseGroupDetail)
    }

    fun requestJoin(accessToken: String, groupId: Int, note: String): String =
        request(
            "/social/groups/$groupId/join-requests",
            "POST",
            accessToken,
            JSONObject().put("note", note.trim()),
        ).optString("status", "pending")

    fun listJoinRequests(accessToken: String, groupId: Int): List<NativeGroupJoinRequest> {
        val response = request("/social/groups/$groupId/join-requests", "GET", accessToken)
        return buildList {
            val values = response.optJSONArray("items") ?: return@buildList
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                add(
                    NativeGroupJoinRequest(
                        id = value.optInt("id"),
                        groupId = value.optInt("groupId", groupId),
                        groupName = "群聊",
                        user = parseUser(value.optJSONObject("user")),
                        note = value.optString("note").takeIf { it.isNotBlank() && it != "null" },
                        status = value.optString("status", "pending"),
                    ),
                )
            }
        }
    }

    fun respondJoinRequest(accessToken: String, groupId: Int, requestId: Int, status: String) {
        request(
            "/social/groups/$groupId/join-requests/$requestId",
            "PATCH",
            accessToken,
            JSONObject().put("status", status),
        )
    }

    fun updateMember(
        accessToken: String,
        groupId: Int,
        userId: Int,
        role: String? = null,
        mutedMinutes: Int? = null,
    ): NativeGroupDetail {
        val body = JSONObject()
        role?.let { body.put("role", it) }
        mutedMinutes?.let { body.put("mutedMinutes", it) }
        return parseGroupDetail(request("/social/groups/$groupId/members/$userId", "PATCH", accessToken, body))
    }

    fun removeMember(accessToken: String, groupId: Int, userId: Int): NativeGroupDetail =
        parseGroupDetail(request("/social/groups/$groupId/members/$userId", "DELETE", accessToken))

    fun blockMember(accessToken: String, groupId: Int, userId: Int): NativeGroupDetail =
        parseGroupDetail(request("/social/groups/$groupId/members/$userId/block", "POST", accessToken))

    fun unblockMember(accessToken: String, groupId: Int, userId: Int): NativeGroupDetail =
        parseGroupDetail(request("/social/groups/$groupId/members/$userId/block", "DELETE", accessToken))

    fun transferOwner(accessToken: String, groupId: Int, userId: Int): NativeGroupDetail =
        parseGroupDetail(
            request(
                "/social/groups/$groupId/transfer",
                "POST",
                accessToken,
                JSONObject().put("userId", userId),
            ),
        )

    fun listBanRecords(accessToken: String, groupId: Int): List<NativeGroupBanRecord> {
        val values = request("/social/groups/$groupId/ban-records", "GET", accessToken).optJSONArray("items")
        return values.toBanRecords()
    }

    fun uploadAvatar(accessToken: String, groupId: Int, file: NativeUploadFile): NativeGroupDetail {
        val boundary = "----HlovetAndroid${System.currentTimeMillis()}"
        val connection = (URL(baseUrl + "/social/groups/$groupId/avatar").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            BufferedOutputStream(connection.outputStream).use { output ->
                val safeName = file.name.replace("\"", "").replace("\r", "").replace("\n", "")
                output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                output.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n".toByteArray(Charsets.UTF_8))
                output.write("Content-Type: ${file.mimeType.ifBlank { "application/octet-stream" }}\r\n\r\n".toByteArray(Charsets.UTF_8))
                output.write(file.bytes)
                output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "群头像上传失败（HTTP $status）"
                throw IllegalStateException(message)
            }
            parseGroupDetail(if (body.isBlank()) JSONObject() else JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    fun leave(accessToken: String, groupId: Int) {
        request("/social/groups/$groupId/leave", "POST", accessToken, JSONObject())
    }

    fun dissolve(accessToken: String, groupId: Int) {
        request("/social/groups/$groupId", "DELETE", accessToken)
    }

    private fun JSONArray?.toGroupSummaries(): List<NativeGroupSummary> = buildList {
        if (this@toGroupSummaries == null) return@buildList
        for (index in 0 until length()) {
            optJSONObject(index)?.let { add(parseGroupSummary(it)) }
        }
    }

    private fun JSONArray?.toInvitations(): List<NativeGroupInvitation> = buildList {
        if (this@toInvitations == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeGroupInvitation(
                    id = value.optInt("id"),
                    group = parseGroupSummary(value.optJSONObject("group") ?: JSONObject()),
                    inviter = parseUser(value.optJSONObject("inviter")),
                    status = value.optString("status", "pending"),
                    expiresAt = value.optString("expiresAt"),
                ),
            )
        }
    }

    private fun parseGroupDetail(value: JSONObject): NativeGroupDetail = NativeGroupDetail(
        summary = parseGroupSummary(value),
        members = value.optJSONArray("members").toMembers(),
        banRecords = value.optJSONArray("banRecords").toBanRecords(),
    )

    private fun JSONArray?.toBanRecords(): List<NativeGroupBanRecord> = buildList {
        if (this@toBanRecords == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeGroupBanRecord(
                    id = value.optInt("id"),
                    reason = value.optString("reason", "未说明原因"),
                    startsAt = value.optString("startsAt"),
                    expiresAt = value.optString("expiresAt").takeIf { it.isNotBlank() && it != "null" },
                    liftedAt = value.optString("liftedAt").takeIf { it.isNotBlank() && it != "null" },
                    createdAt = value.optString("createdAt"),
                    actor = parseUser(value.optJSONObject("actor")),
                    liftedBy = value.optJSONObject("liftedBy")?.let(::parseUser),
                ),
            )
        }
    }

    private fun JSONArray?.toMembers(): List<NativeGroupMember> = buildList {
        if (this@toMembers == null) return@buildList
        for (index in 0 until length()) {
            val value = optJSONObject(index) ?: continue
            add(
                NativeGroupMember(
                    user = parseUser(value.optJSONObject("user")),
                    role = value.optString("role", "member"),
                    status = value.optString("status", "active"),
                    alias = value.optString("alias").takeIf { it.isNotBlank() && it != "null" },
                    mutedUntil = value.optString("mutedUntil").takeIf { it.isNotBlank() && it != "null" },
                    joinedAt = value.optString("joinedAt"),
                    isSelf = value.optBoolean("isSelf"),
                ),
            )
        }
    }

    private fun parseGroupSummary(value: JSONObject): NativeGroupSummary {
        val owner = parseUser(value.optJSONObject("owner"))
        return NativeGroupSummary(
            id = value.optInt("id"),
            conversationId = value.optInt("conversationId"),
            owner = owner,
            name = value.optString("name", "未命名群聊"),
            avatarUrl = value.optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" },
            announcement = value.optString("announcement"),
            joinMode = value.optString("joinMode", "approval"),
            membersCanInvite = value.optBoolean("membersCanInvite"),
            memberLimit = value.optInt("memberLimit", 100),
            memberCount = value.optInt("memberCount"),
            temporary = value.optBoolean("temporary"),
            expiresAt = value.optString("expiresAt").takeIf { it.isNotBlank() && it != "null" },
            isBanned = value.optBoolean("isBanned"),
            bannedUntil = value.optString("bannedUntil").takeIf { it.isNotBlank() && it != "null" },
            banReason = value.optString("banReason").takeIf { it.isNotBlank() && it != "null" },
            currentMemberRole = value.optString("currentMemberRole").takeIf { it.isNotBlank() && it != "null" },
            currentAlias = value.optString("currentAlias").takeIf { it.isNotBlank() && it != "null" },
            canManage = value.optBoolean("canManage"),
            canModerate = value.optBoolean("canModerate"),
            canInvite = value.optBoolean("canInvite"),
            pendingJoinRequestCount = value.optInt("pendingJoinRequestCount"),
            pendingReportCount = value.optInt("pendingReportCount"),
        )
    }

    private fun parseUser(value: JSONObject?): NativeGroupUser {
        val source = value ?: JSONObject()
        val username = source.optString("username", "用户")
        return NativeGroupUser(
            id = source.optInt("id"),
            nickname = source.optString("nickname").ifBlank { username },
            username = username,
            avatarUrl = source.optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun request(
        path: String,
        method: String,
        accessToken: String,
        payload: JSONObject? = null,
    ): JSONObject {
        val hasBody = payload != null && method != "GET"
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 18_000
            doOutput = hasBody
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("User-Agent", NativeAuthApi.USER_AGENT)
            if (hasBody) setRequestProperty("Content-Type", "application/json")
        }
        return try {
            if (hasBody) connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "群聊操作失败（HTTP $status）"
                throw IllegalStateException(message)
            }
            if (body.isBlank()) JSONObject() else JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
