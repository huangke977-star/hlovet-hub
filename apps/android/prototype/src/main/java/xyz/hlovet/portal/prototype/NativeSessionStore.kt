package xyz.hlovet.portal.prototype

import android.content.Context
import android.util.Base64
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** 按服务器来源加密保存登录会话，防止切换预览或正式服务器时跨来源恢复凭据。 */
internal object NativeSessionStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "hlovet.native.session"
    private const val PREFS = "native-session"
    private const val PAYLOAD = "payload"
    private const val LEGACY_NOTICE_SHOWN = "legacy-reauthentication-notice-shown"
    private val STORAGE_LOCK = Any()

    /** 用 context 保存 session 到 apiBaseUrl 对应来源；加密或写入失败返回 false，供界面提示。 */
    fun save(context: Context, session: NativeSession, apiBaseUrl: String = BuildConfig.API_BASE_URL): Boolean =
        synchronized(STORAGE_LOCK) { runCatching {
            // 只序列化当前来源的会话和展示设置，不保存登录密码。
            val serverOrigin = origin(apiBaseUrl)
            val plainText = JSONObject().apply {
                put("serverOrigin", serverOrigin)
                put("accessToken", session.accessToken)
                put("refreshToken", session.refreshToken)
                put("userId", session.userId)
                put("username", session.username)
                put("nickname", session.nickname)
                put("email", session.email)
                put("profileBio", session.profileBio)
                put("avatarUrl", session.avatarUrl)
                put("locale", session.locale)
                put("isSuperAdmin", session.isSuperAdmin)
                put("isAdministrator", session.isAdministrator)
                put("appearance", JSONObject().apply {
                    put("themeId", session.appearance.themeId)
                    put("accent", session.appearance.accent)
                    put("foreground", session.appearance.foreground)
                    put("muted", session.appearance.muted)
                    put("surface", session.appearance.surface)
                    put("cardAlpha", session.appearance.cardAlpha)
                    put("glassBlur", session.appearance.glassBlur)
                    put("glassTint", session.appearance.glassTint)
                    put("glassTintAlpha", session.appearance.glassTintAlpha)
                })
            }.toString()
            // 来源参与 GCM 认证，即使密文被复制到另一来源，也不能作为该来源会话恢复。
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(serverOrigin.toByteArray(Charsets.UTF_8))
            val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            // 原子写入保留上一次完整记录，写入异常时回滚而不是留下半个凭据文件。
            val file = AtomicFile(sessionFile(context, apiBaseUrl))
            check(file.baseFile.parentFile?.let { it.exists() || it.mkdirs() } == true)
            val stream = file.startWrite()
            try {
                stream.write(payload.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }.isSuccess }

    /** 从 context 读取 apiBaseUrl 的会话；记录缺失、损坏或来源认证失败均返回 null，不删除旧数据。 */
    fun load(context: Context, apiBaseUrl: String = BuildConfig.API_BASE_URL): NativeSession? =
        synchronized(STORAGE_LOCK) { runCatching {
            // 按规范化来源定位原子文件，只有该来源的主文件或备份文件才会被读取。
            val serverOrigin = origin(apiBaseUrl)
            val file = AtomicFile(sessionFile(context, apiBaseUrl))
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@runCatching null
            val payload = file.openRead().bufferedReader().use { it.readText() }
            val parts = payload.split('.', limit = 2)
            if (parts.size != 2) return@runCatching null
            // 先验证密文和来源绑定，再解析账号；拒绝被修改或跨来源复制的记录。
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            cipher.updateAAD(serverOrigin.toByteArray(Charsets.UTF_8))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
            if (json.optString("serverOrigin") != serverOrigin) return@runCatching null
            // 缺少访问令牌时不恢复账号；非敏感展示字段继续使用现有默认值。
            val appearance = json.optJSONObject("appearance") ?: JSONObject()
            NativeSession(
                accessToken = json.optString("accessToken").ifBlank { return@runCatching null },
                refreshToken = json.optString("refreshToken"),
                userId = json.optInt("userId", 0),
                username = json.optString("username", "user"),
                nickname = json.optString("nickname", json.optString("username", "user")),
                email = json.optString("email"),
                profileBio = json.optString("profileBio"),
                avatarUrl = json.optString("avatarUrl").takeIf { it.isNotBlank() && it != "null" },
                locale = json.optString("locale", "zh-CN"),
                isSuperAdmin = json.optBoolean("isSuperAdmin"),
                isAdministrator = json.optBoolean("isAdministrator"),
                appearance = PreviewAppearance(
                    themeId = appearance.optString("themeId", "cloud-blue"),
                    accent = appearance.optString("accent", "#0284c7"),
                    foreground = appearance.optString("foreground", "#1f2937"),
                    muted = appearance.optString("muted", "#52616f"),
                    surface = appearance.optString("surface", "#ffffff"),
                    cardAlpha = appearance.optInt("cardAlpha", 50),
                    glassBlur = appearance.optInt("glassBlur", 18),
                    glassTint = appearance.optString("glassTint", "#fff3f6"),
                    glassTintAlpha = appearance.optInt("glassTintAlpha", 0),
                ),
            )
        }.getOrNull() }

    /** 清理 context 中 apiBaseUrl 对应的会话，不清理其它来源、旧记录或可信设备 Cookie。 */
    fun clear(context: Context, apiBaseUrl: String = BuildConfig.API_BASE_URL) = synchronized(STORAGE_LOCK) {
        // 退出只影响当前来源，不能连带删除用户在其它服务器上的登录信息。
        runCatching { AtomicFile(sessionFile(context, apiBaseUrl)).delete() }
        Unit
    }

    /** 检查 context 中旧记录，apiBaseUrl 没有有效会话且首次提示状态保存成功时返回 true。 */
    fun consumeLegacyReauthenticationNotice(context: Context, apiBaseUrl: String = BuildConfig.API_BASE_URL): Boolean = synchronized(STORAGE_LOCK) {
        runCatching {
            // 旧记录未记录服务器来源，保留原文但不自动恢复或发送其中的令牌。
            origin(apiBaseUrl)
            if (load(context, apiBaseUrl) != null) return@runCatching false
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!preferences.contains(PAYLOAD) || preferences.getBoolean(LEGACY_NOTICE_SHOWN, false)) return@runCatching false
            // 先持久化已提示状态，避免每次冷启动都让用户重复处理旧版登录信息。
            preferences.edit().putBoolean(LEGACY_NOTICE_SHOWN, true).commit()
        }.getOrDefault(false)
    }

    /** 返回 context 中 apiBaseUrl 的来源摘要文件路径；本方法不创建文件，文件不参与系统备份。 */
    internal fun sessionFile(context: Context, apiBaseUrl: String = BuildConfig.API_BASE_URL): File {
        // 文件名只包含来源摘要，避免在文件名中泄露账号或令牌信息。
        val digest = MessageDigest.getInstance("SHA-256").digest(origin(apiBaseUrl).toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(File(context.noBackupFilesDir, "native-sessions"), "$name.bin")
    }

    /** 将 apiBaseUrl 规范化为协议、主机和有效端口组成的来源；无效地址抛出异常。 */
    private fun origin(apiBaseUrl: String): String = requireNotNull(nativeDeviceCookieOrigin(URI(apiBaseUrl))) {
        // 复用可信设备存储的来源规则，使两种凭据对站点边界的判断一致。
        "Invalid native session origin"
    }

    /** 在共享锁内获取或生成 Keystore AES 密钥，返回不可导出的本设备会话加密密钥。 */
    private fun getOrCreateKey(): SecretKey = synchronized(STORAGE_LOCK) {
        // 整个读取和生成流程持有同一把锁，避免并发初始化互相覆盖密钥。
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        // 密钥仅用于 GCM 加解密；会话恢复不额外触发生物认证，业务验证仍由服务端负责。
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generator.generateKey()
    }
}
