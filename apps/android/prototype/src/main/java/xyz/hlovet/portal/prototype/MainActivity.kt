@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.credentials.CredentialManager
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbUpOffAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardElevation
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONObject
import androidx.compose.foundation.shape.RoundedCornerShape

private val Background: Color get() = HlovetUi.background
private val SurfaceLow: Color get() = HlovetUi.surfaceLow
private val SurfaceAccent: Color get() = HlovetUi.surfaceAccent
private val TextPrimary: Color get() = HlovetUi.foreground
private val TextMuted: Color get() = HlovetUi.muted
private val Accent: Color get() = HlovetUi.accent
private val Coral: Color get() = HlovetUi.secondaryAccent
internal const val NATIVE_VOICE_RECORDING_LIMIT_SECONDS = 5 * 60

internal fun nativeVoiceRecordingLimitReached(seconds: Int): Boolean =
    seconds >= NATIVE_VOICE_RECORDING_LIMIT_SECONDS

private sealed interface DetailTarget {
    data class Article(val article: RemoteArticle, val focusCommentId: Int? = null) : DetailTarget
    data class Topic(val topic: RemoteTopic) : DetailTarget
    data class Collection(val collection: RemoteCollection) : DetailTarget
}

private sealed interface NativeDetailBackTarget {
    data class Account(val target: AccountTarget) : NativeDetailBackTarget
    data class PublicProfile(val username: String) : NativeDetailBackTarget
    data class Detail(val target: DetailTarget) : NativeDetailBackTarget
}

private data class NativeProfileBackTarget(
    val target: DetailTarget,
    val detailBackTarget: NativeDetailBackTarget?,
)

private enum class NativeMessageOperation {
    Recall,
    DeleteSelf,
    DeleteEveryone,
    TogglePin,
}

internal fun nativeChatListHeaderOffset(hasMore: Boolean): Int = if (hasMore) 1 else 0

internal fun nativeChatListItemIndex(messageIndex: Int, hasMore: Boolean): Int =
    (nativeChatListHeaderOffset(hasMore) + messageIndex).coerceAtLeast(0)

internal fun canConsumeNativePortalIntent(
    portalIntentUrl: String?,
    sessionRestoreComplete: Boolean,
): Boolean = sessionRestoreComplete && !portalIntentUrl.isNullOrBlank()

private data class NativePendingMessageOperation(
    val operation: NativeMessageOperation,
    val messages: List<NativeChatMessage>,
) {
    val message: NativeChatMessage
        get() = messages.first()
}

private enum class NativeConversationOperation {
    Clear,
    Delete,
}

internal sealed interface AccountTarget {
    data object Dashboard : AccountTarget
    data object Navigation : AccountTarget
    data object Tools : AccountTarget
    data object Install : AccountTarget
    data object ProfileEditor : AccountTarget
    data object Announcements : AccountTarget
    data object Feedback : AccountTarget
    data object Suggestions : AccountTarget
    data object AnonymousTopics : AccountTarget
    data object AnonymousTopicManagement : AccountTarget
    data object RecommendationFeedback : AccountTarget
    data object MyArticles : AccountTarget
    data object MyFavorites : AccountTarget
    data object MyLiked : AccountTarget
    data object ReadLater : AccountTarget
    data object ReadingHistory : AccountTarget
    data object MySubscriptions : AccountTarget
    data object SubscriptionFeed : AccountTarget
    data object MyCollections : AccountTarget
    data object TemplateManagement : AccountTarget
    data object Offline : AccountTarget
    data object TopicManagement : AccountTarget
    data object Privacy : AccountTarget
    data object Sessions : AccountTarget
    data object Integrations : AccountTarget
    data object MyReports : AccountTarget
    data object AiAssistant : AccountTarget
    data object AdminHome : AccountTarget
    data object AdminTopics : AccountTarget
    data object AdminArticles : AccountTarget
    data object AdminAnalytics : AccountTarget
    data object AdminAudit : AccountTarget
    data object SystemStatus : AccountTarget
    data object AdminBackups : AccountTarget
    data object AdminOperations : AccountTarget
    data object AdminStorage : AccountTarget
    data object AdminUsers : AccountTarget
    data object AdminReports : AccountTarget
    data object AdminAnnouncements : AccountTarget
    data object AdminContent : AccountTarget
    data object AdminViolations : AccountTarget
    data object AdminFeedback : AccountTarget
    data object AdminResources : AccountTarget
    data object AdminGroups : AccountTarget
    data object AdminDeletedUsers : AccountTarget
    data object AdminSecurity : AccountTarget
    data object AdminCache : AccountTarget
    data object AdminAndroid : AccountTarget
    data object AdminIntegrations : AccountTarget
    data object AdminSettings : AccountTarget
    data object AdminAi : AccountTarget
    data object ResourceCenter : AccountTarget
    data object Points : AccountTarget
    data object ResourceOrders : AccountTarget
    data object ResourceEarnings : AccountTarget
}

internal fun nativeAdminAccountTarget(path: String): AccountTarget? = when (normalizeNativePath(path)) {
    "/admin" -> AccountTarget.AdminHome
    "/admin/articles" -> AccountTarget.AdminArticles
    "/admin/analytics" -> AccountTarget.AdminAnalytics
    "/admin/audit" -> AccountTarget.AdminAudit
    "/admin/system" -> AccountTarget.SystemStatus
    "/admin/system/backups" -> AccountTarget.AdminBackups
    "/admin/system/operations" -> AccountTarget.AdminOperations
    "/admin/storage" -> AccountTarget.AdminStorage
    "/admin/users" -> AccountTarget.AdminUsers
    "/admin/reports" -> AccountTarget.AdminReports
    "/admin/announcements" -> AccountTarget.AdminAnnouncements
    "/admin/content" -> AccountTarget.AdminContent
    "/admin/violations" -> AccountTarget.AdminViolations
    "/admin/feedback" -> AccountTarget.AdminFeedback
    "/admin/resources" -> AccountTarget.AdminResources
    "/admin/groups" -> AccountTarget.AdminGroups
    "/admin/deleted-users" -> AccountTarget.AdminDeletedUsers
    "/admin/security" -> AccountTarget.AdminSecurity
    "/admin/cache" -> AccountTarget.AdminCache
    "/admin/android" -> AccountTarget.AdminAndroid
    "/admin/integrations" -> AccountTarget.AdminIntegrations
    "/admin/settings", "/admin/backgrounds" -> AccountTarget.AdminSettings
    "/admin/ai" -> AccountTarget.AdminAi
    "/admin/voices" -> AccountTarget.AnonymousTopicManagement
    "/admin/topics" -> AccountTarget.AdminTopics
    else -> null
}

private fun accountTargetTitle(target: AccountTarget): String = when (target) {
    AccountTarget.Dashboard -> nativePhrase("个人工作台", "Dashboard")
    AccountTarget.Navigation -> nativePhrase("站内导航", "Navigation")
    AccountTarget.Tools -> nativePhrase("工具中心", "Tools")
    AccountTarget.Install -> nativePhrase("安装与版本", "Install and version")
    AccountTarget.ProfileEditor -> nativePhrase("编辑个人资料", "Edit profile")
    AccountTarget.Announcements -> nativePhrase("站点公告", "Announcements")
    AccountTarget.Feedback -> nativePhrase("问题反馈", "Report a problem")
    AccountTarget.Suggestions -> nativePhrase("意见建议", "Suggestions")
    AccountTarget.AnonymousTopics -> nativePhrase("匿名话题", "Anonymous topics")
    AccountTarget.AnonymousTopicManagement -> nativePhrase("匿名话题管理", "Anonymous topic management")
    AccountTarget.RecommendationFeedback -> nativePhrase("推荐反馈", "Recommendation feedback")
    AccountTarget.MyArticles -> nativePhrase("我的文章", "My articles")
    AccountTarget.MyFavorites -> nativePhrase("我的收藏", "Favorites")
    AccountTarget.MyLiked -> nativePhrase("我点赞的", "Liked")
    AccountTarget.ReadLater -> nativePhrase("稍后阅读", "Read later")
    AccountTarget.ReadingHistory -> nativePhrase("阅读历史", "Reading history")
    AccountTarget.MySubscriptions, AccountTarget.SubscriptionFeed -> nativePhrase("我的订阅", "Subscriptions")
    AccountTarget.MyCollections -> nativePhrase("我的合集", "My collections")
    AccountTarget.TemplateManagement -> nativePhrase("文章模板", "Article templates")
    AccountTarget.Offline -> nativePhrase("离线阅读", "Offline reading")
    AccountTarget.TopicManagement -> nativePhrase("专题管理", "Topic management")
    AccountTarget.Privacy -> nativePhrase("隐私与数据", "Privacy and data")
    AccountTarget.Sessions -> nativePhrase("登录设备", "Signed-in devices")
    AccountTarget.Integrations -> nativePhrase("集成管理", "Integrations")
    AccountTarget.MyReports -> nativePhrase("我的举报", "My reports")
    AccountTarget.AiAssistant -> nativePhrase("AI 助手", "AI assistant")
    AccountTarget.AdminHome -> nativePhrase("后台管理", "Administration")
    AccountTarget.AdminTopics -> nativePhrase("专题管理", "Topic management")
    AccountTarget.AdminArticles -> nativePhrase("文章管理", "Article management")
    AccountTarget.AdminAnalytics -> nativePhrase("运营数据", "Analytics")
    AccountTarget.AdminAudit -> nativePhrase("审计日志", "Audit log")
    AccountTarget.AdminUsers -> nativePhrase("用户管理", "User management")
    AccountTarget.AdminReports -> nativePhrase("举报中心", "Report center")
    AccountTarget.SystemStatus -> nativePhrase("系统概况", "System overview")
    AccountTarget.AdminBackups -> nativePhrase("备份管理", "Backups")
    AccountTarget.AdminOperations -> nativePhrase("运营与质量", "Operations and quality")
    AccountTarget.AdminStorage -> nativePhrase("存储管理", "Storage")
    AccountTarget.AdminAnnouncements -> nativePhrase("公告管理", "Announcement management")
    AccountTarget.AdminContent -> nativePhrase("内容管理", "Content management")
    AccountTarget.AdminViolations -> nativePhrase("违规作者", "Policy violations")
    AccountTarget.AdminFeedback -> nativePhrase("用户反馈管理", "Feedback management")
    AccountTarget.AdminResources -> nativePhrase("资源与积分", "Resources and points")
    AccountTarget.AdminGroups -> nativePhrase("群聊管理", "Group management")
    AccountTarget.AdminDeletedUsers -> nativePhrase("已注销账号", "Deleted accounts")
    AccountTarget.AdminSecurity -> nativePhrase("安全管理", "Security")
    AccountTarget.AdminCache -> nativePhrase("缓存管理", "Cache")
    AccountTarget.AdminAndroid -> nativePhrase("安装包管理", "Android releases")
    AccountTarget.AdminIntegrations -> nativePhrase("外部集成", "External integrations")
    AccountTarget.AdminSettings -> nativePhrase("站点设置", "Site settings")
    AccountTarget.AdminAi -> nativePhrase("AI 配置", "AI settings")
    AccountTarget.ResourceCenter -> nativePhrase("资源中心", "Resource center")
    AccountTarget.Points -> nativePhrase("积分明细", "Points activity")
    AccountTarget.ResourceOrders -> nativePhrase("兑换记录", "Redemptions")
    AccountTarget.ResourceEarnings -> nativePhrase("资源收益", "Resource earnings")
}

private fun accountTargetEmptyState(target: AccountTarget): String = when (target) {
    AccountTarget.Dashboard,
    AccountTarget.Navigation,
    AccountTarget.Tools,
    AccountTarget.Install,
    AccountTarget.ProfileEditor,
    AccountTarget.AdminHome,
    AccountTarget.AdminTopics,
    AccountTarget.AdminArticles,
    AccountTarget.AdminAnalytics,
    AccountTarget.AdminAudit,
    AccountTarget.AdminUsers,
    AccountTarget.AdminReports,
    AccountTarget.SystemStatus,
    AccountTarget.AdminBackups,
    AccountTarget.AdminOperations,
    AccountTarget.AdminStorage,
    AccountTarget.AdminAnnouncements,
    AccountTarget.AdminContent,
    AccountTarget.AdminViolations,
    AccountTarget.AdminFeedback,
    AccountTarget.AdminResources,
    AccountTarget.AdminGroups,
    AccountTarget.AdminDeletedUsers,
    AccountTarget.AdminSecurity,
    AccountTarget.AdminCache,
    AccountTarget.AdminAndroid,
    AccountTarget.AdminIntegrations,
    AccountTarget.AdminSettings,
    AccountTarget.AdminAi -> nativePhrase(
        "${accountTargetTitle(target)}由独立页面展示。",
        "${accountTargetTitle(target)} is shown on its own screen.",
    )
    AccountTarget.Announcements -> nativePhrase("暂无公告。", "No announcements yet.")
    AccountTarget.Feedback -> nativePhrase("暂无反馈。", "No feedback yet.")
    AccountTarget.Suggestions -> nativePhrase("暂无建议。", "No suggestions yet.")
    AccountTarget.AnonymousTopics, AccountTarget.AnonymousTopicManagement -> nativePhrase("暂无匿名话题。", "No anonymous topics yet.")
    AccountTarget.RecommendationFeedback -> nativePhrase("暂无不感兴趣的推荐反馈。", "No hidden recommendations.")
    AccountTarget.MyArticles -> nativePhrase("还没有文章。", "No articles yet.")
    AccountTarget.MyFavorites -> nativePhrase("还没有收藏文章。", "No favorite articles yet.")
    AccountTarget.MyLiked -> nativePhrase("还没有点赞文章。", "No liked articles yet.")
    AccountTarget.ReadLater -> nativePhrase("还没有稍后阅读的文章。", "No articles saved for later.")
    AccountTarget.ReadingHistory -> nativePhrase(
        "还没有阅读历史。打开文章后，最近阅读位置会自动保存。",
        "No reading history yet. Your latest position is saved when you open an article.",
    )
    AccountTarget.MySubscriptions -> nativePhrase("还没有订阅作者的文章。", "No articles from subscribed authors yet.")
    AccountTarget.SubscriptionFeed -> nativePhrase("还没有订阅动态。", "No subscription updates yet.")
    AccountTarget.MyCollections -> nativePhrase("还没有创建合集。", "No collections yet.")
    AccountTarget.TemplateManagement -> nativePhrase("还没有文章模板。", "No article templates yet.")
    AccountTarget.Offline -> nativePhrase("还没有离线内容。", "No offline content yet.")
    AccountTarget.TopicManagement -> nativePhrase("还没有创建专题。", "No topics yet.")
    AccountTarget.Privacy -> nativePhrase("暂无隐私数据。", "No privacy data available.")
    AccountTarget.Sessions -> nativePhrase("暂无登录设备。", "No signed-in devices.")
    AccountTarget.Integrations -> nativePhrase("暂无集成配置。", "No integrations configured.")
    AccountTarget.MyReports -> nativePhrase("暂无举报记录。", "No reports yet.")
    AccountTarget.AiAssistant -> nativePhrase("暂无 AI 内容。", "No AI content yet.")
    AccountTarget.ResourceCenter -> nativePhrase("暂无资源数据。", "No resource data yet.")
    AccountTarget.Points -> nativePhrase("暂无积分数据。", "No points activity yet.")
    AccountTarget.ResourceOrders -> nativePhrase("暂无兑换记录。", "No redemptions yet.")
    AccountTarget.ResourceEarnings -> nativePhrase("暂无资源收益。", "No resource earnings yet.")
}

private data class ConversationTarget(
    val preview: NativeConversationPreview,
    val focusMessageId: Int? = null,
)

private data class PendingMessagesRoute(
    val conversationId: Int,
    val messageId: Int? = null,
)

private data class NativeCallLauncher(
    val connected: Boolean,
    val active: Boolean,
    val start: (conversationId: Int, type: String) -> Unit,
)

private fun fallbackConversationPreview(
    notification: NativeNotificationPreview,
    conversationId: Int,
): NativeConversationPreview = NativeConversationPreview(
    id = conversationId,
    title = notification.title.ifBlank { nativePhrase("聊天会话", "Chat") },
    initials = notification.title.ifBlank { nativePhrase("聊天", "Chat") }.takeLast(2),
    message = notification.body,
    time = "",
    unreadCount = 0,
    kind = "",
)

private data class NativeArticleManagementAction(
    val article: RemoteArticle,
    val kind: String,
)

private data class NativeArticleAppealTarget(
    val article: RemoteArticle,
)

private data class NativeArticleManagementLoad(
    val summary: NativeArticleMineSummary,
    val dashboard: NativeArticleDashboard,
    val schedules: List<NativeArticleScheduleItem>,
    val articles: NativeArticleList,
)

private sealed interface NativeLoginStep {
    data object Credentials : NativeLoginStep
    data object Register : NativeLoginStep
    data object RecoveryRequest : NativeLoginStep
    data class RecoverySent(val email: String) : NativeLoginStep
    data class RecoveryReset(val token: String) : NativeLoginStep
    data object RecoveryComplete : NativeLoginStep
    data class Device(
        val challengeToken: String,
        val emailHint: String,
        val retryAfterSeconds: Int,
    ) : NativeLoginStep
    data class Totp(val challengeToken: String) : NativeLoginStep
    data class GoogleLink(val pendingToken: String, val email: String, val methods: GoogleLinkMethods) : NativeLoginStep
    data class GoogleLinkEmail(val pendingToken: String, val emailHint: String, val challengeToken: String) : NativeLoginStep
    data class GoogleLinkTotp(val pendingToken: String) : NativeLoginStep
    data class GoogleLinkPassword(val pendingToken: String) : NativeLoginStep
}

/** Keeps the existing page code on one shared glass surface implementation. */
@Suppress("UNUSED_PARAMETER")
@Composable
private fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = HlovetUi.cardShape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val configuredContainerColor = colors.containerColor.takeUnless { it == Color.Unspecified }
        ?: HlovetUi.glassFallback
    GlassCard(
        modifier = modifier,
        shape = shape,
        containerColor = configuredContainerColor,
        content = content,
    )
}

/** 原生应用入口，接收认证/通知/站内链接并挂载统一主题和导航界面。 */
class MainActivity : ComponentActivity() {
    private var oauthResultToken by mutableStateOf<String?>(null)
    private var oauthErrorMessage by mutableStateOf<String?>(null)
    private var oauthReturnTo by mutableStateOf<String?>(null)
    private var passwordResetToken by mutableStateOf<String?>(null)
    private var notificationIntent by mutableStateOf<NativeNotificationIntent?>(null)
    private var portalIntentUrl by mutableStateOf<String?>(null)

    /** 根据 savedInstanceState 初始化应用入口；会话恢复由界面后台任务负责。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        // 在任何网络请求前安装可信设备处理器，并读取用户语言与启动链接。
        super.onCreate(savedInstanceState)
        NativeTrustedDeviceCookies.install(this, BuildConfig.API_BASE_URL)
        NativeI18n.load(this)
        readOAuthResult(intent)
        readNotificationIntent(intent)
        readPortalIntent(intent)
        // 系统栏沿用统一主题；首页不使用 WebView，不在此提前启动浏览器引擎。
        window.statusBarColor = Background.toArgbCompat()
        window.navigationBarColor = Background.toArgbCompat()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true
        // 链接消费后清理入口状态，避免重组再次打开同一个认证或通知页面。
        setContent {
            HlovetTheme {
                HlovetMobilePreview(
                    oauthResultToken = oauthResultToken,
                    oauthErrorMessage = oauthErrorMessage,
                    oauthReturnTo = oauthReturnTo,
                    passwordResetToken = passwordResetToken,
                    notificationIntent = notificationIntent,
                    portalIntentUrl = portalIntentUrl,
                    onOAuthResultConsumed = {
                        oauthResultToken = null
                        oauthErrorMessage = null
                        oauthReturnTo = null
                    },
                    onPasswordResetTokenConsumed = { passwordResetToken = null },
                    onNotificationIntentConsumed = { notificationIntent = null },
                    onPortalIntentConsumed = { portalIntentUrl = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readOAuthResult(intent)
        readNotificationIntent(intent)
        readPortalIntent(intent)
    }

    private fun readOAuthResult(intent: Intent?) {
        val data = intent?.data ?: return
        when (val link = parseNativeAuthenticationLink(data.toString())) {
            is NativeAuthenticationLink.Google -> {
                passwordResetToken = null
                oauthResultToken = link.resultToken
                oauthErrorMessage = link.errorMessage
                oauthReturnTo = link.returnTo
            }
            is NativeAuthenticationLink.PasswordReset -> {
                oauthResultToken = null
                oauthErrorMessage = null
                oauthReturnTo = null
                passwordResetToken = link.token
            }
            null -> Unit
        }
    }

    private fun readNotificationIntent(intent: Intent?) {
        NativeLocalNotifications.intentFrom(intent)?.let { notificationIntent = it }
    }

    private fun readPortalIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val isPortalHost = data.host?.lowercase() in nativePortalHostnames()
        val isPortalHttpsLink = data.scheme.equals("https", ignoreCase = true) && isPortalHost
        val isLocalPreviewHttpLink = data.scheme.equals("http", ignoreCase = true) &&
            isPortalHost && BuildConfig.API_BASE_URL.startsWith("http://")
        portalIntentUrl = when {
            (isPortalHttpsLink || isLocalPreviewHttpLink) && parseNativeAuthenticationLink(data.toString()) == null -> data.toString()
            data.scheme == "hlovet-native" && data.host.equals("unsubscribe", ignoreCase = true) -> {
                data.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }?.let {
                    "${nativePortalBaseUrl()}/unsubscribe/${Uri.encode(it)}"
                }
            }
            else -> null
        }
    }
}

@Composable
private fun HlovetMobilePreview(
    oauthResultToken: String? = null,
    oauthErrorMessage: String? = null,
    oauthReturnTo: String? = null,
    passwordResetToken: String? = null,
    notificationIntent: NativeNotificationIntent? = null,
    portalIntentUrl: String? = null,
    onOAuthResultConsumed: () -> Unit = {},
    onPasswordResetTokenConsumed: () -> Unit = {},
    onNotificationIntentConsumed: () -> Unit = {},
    onPortalIntentConsumed: () -> Unit = {},
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var discoveryInitialMode by remember { mutableStateOf("推荐") }
    var discoveryInitialQuery by remember { mutableStateOf("") }
    var accountInitialStatus by remember { mutableStateOf<String?>(null) }
    var accountInitialQuery by remember { mutableStateOf("") }
    var subscriptionInitialSort by remember { mutableStateOf("latest") }
    var collectionInitialView by remember { mutableStateOf("mine") }
    var detailTarget by remember { mutableStateOf<DetailTarget?>(null) }
    var unsubscribeToken by remember { mutableStateOf<String?>(null) }
    var accountTarget by remember { mutableStateOf<AccountTarget?>(null) }
    var adminSystemParentIsAdmin by remember { mutableStateOf(false) }
    var adminSystemChildFromSystem by remember { mutableStateOf(false) }
    var announcementTargetId by remember { mutableStateOf<Int?>(null) }
    var publicProfileTarget by remember { mutableStateOf<String?>(null) }
    var profileBackTarget by remember { mutableStateOf<NativeProfileBackTarget?>(null) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchInitialQuery by remember { mutableStateOf("") }
    var conversationTarget by remember { mutableStateOf<ConversationTarget?>(null) }
    var pendingMessagesRoute by remember { mutableStateOf<PendingMessagesRoute?>(null) }
    var pendingMessagesRouteUrl by remember { mutableStateOf<String?>(null) }
    var groupScreenVisible by remember { mutableStateOf(false) }
    var groupInitialId by remember { mutableStateOf<Int?>(null) }
    var groupInitialView by remember { mutableStateOf("mine") }
    var groupInitialReportId by remember { mutableStateOf<Int?>(null) }
    var peopleScreenVisible by remember { mutableStateOf(false) }
    var detailBackTarget by remember { mutableStateOf<NativeDetailBackTarget?>(null) }
    var writeTarget by remember { mutableStateOf<NativeWriteTarget?>(null) }
    var pendingWriteTarget by remember { mutableStateOf<NativeWriteTarget?>(null) }
    var writeBackTarget by remember { mutableStateOf<AccountTarget?>(null) }
    var pendingWriteBackTarget by remember { mutableStateOf<AccountTarget?>(null) }
    var adminPreviewArticleId by remember { mutableStateOf<Int?>(null) }
    var pendingAdminPreviewArticleId by remember { mutableStateOf<Int?>(null) }
    var adminPreviewBackTarget by remember { mutableStateOf<AccountTarget?>(null) }
    var pendingAdminPreviewBackTarget by remember { mutableStateOf<AccountTarget?>(null) }
    var writeReloadKey by remember { mutableIntStateOf(0) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var loadState by remember { mutableStateOf<PreviewLoadState>(PreviewLoadState.Loading) }
    var backgroundBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var session by remember { mutableStateOf<NativeSession?>(null) }
    var sessionRestoreComplete by remember { mutableStateOf(false) }
    var offlineMigrationReadyOwner by remember { mutableStateOf<String?>(null) }
    var offlineLegacyOwner by remember { mutableStateOf<String?>(null) }
    var inboxSummary by remember { mutableStateOf<NativeInboxSummary?>(null) }
    var inboxReloadKey by remember { mutableIntStateOf(0) }
    var profileSummary by remember { mutableStateOf<NativeProfileSummary?>(null) }
    var profileLoading by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf<String?>(null) }
    var articleCenterSummary by remember { mutableStateOf<NativeArticleCenterSummary?>(null) }
    var reputationSummary by remember { mutableStateOf<NativeReputationSummary?>(null) }
    var reputationLoading by remember { mutableStateOf(false) }
    var reputationError by remember { mutableStateOf<String?>(null) }
    var loginVisible by remember { mutableStateOf(false) }
    var loginInitialStep by remember { mutableStateOf<NativeLoginStep>(NativeLoginStep.Credentials) }
    var onboardingState by remember { mutableStateOf<NativeOnboardingState?>(null) }
    var onboardingVisible by remember { mutableStateOf(false) }
    var onboardingSaving by remember { mutableStateOf(false) }
    var onboardingError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var appInForeground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session?.locale) {
        if (session != null) {
            NativeI18n.applyAndPersist(context, session?.locale)
        }
    }

    fun unregisterNativePush(previous: NativeSession?) {
        if (previous == null || !NativeFirebasePush.isConfigured()) return
        scope.launch(Dispatchers.IO) {
            runCatching { NativeFirebasePush.unregister(context, previous.accessToken) }
        }
    }

    /** 将 message 提示投递至 Android 主线程，网络或 Socket 回调不需要自行准备 Looper。 */
    fun showNativeNotice(message: String) {
        // 登录失效等异步结果也可能来自工作线程，不能让反馈本身导致页面崩溃。
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    var socketRefreshInProgress by remember { mutableStateOf(false) }
    val socketRefreshWaiters = remember { mutableListOf<(NativeSession?) -> Unit>() }

    fun requestSocketReauthentication(onComplete: (NativeSession?) -> Unit) {
        val current = session
        if (current == null || current.refreshToken.isBlank()) {
            onComplete(null)
            return
        }
        socketRefreshWaiters += onComplete
        if (socketRefreshInProgress) return
        socketRefreshInProgress = true
        scope.launch {
            val refreshed = runCatching {
                withContext(Dispatchers.IO) { NativeAuthApi.refresh(current.refreshToken) }
            }.getOrNull()
            if (refreshed != null) {
                HlovetUi.applyAppearance(refreshed.appearance)
                session = refreshed
                withContext(Dispatchers.IO) { NativeSessionStore.save(context, refreshed) }
            }
            val waiters = socketRefreshWaiters.toList()
            socketRefreshWaiters.clear()
            socketRefreshInProgress = false
            waiters.forEach { waiter -> waiter(refreshed) }
            if (refreshed == null) {
                showNativeNotice(nativePhrase("聊天连接重新认证失败，请重新登录。", "Chat reauthentication failed. Please sign in again."))
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        showNativeNotice(
            if (granted) nativePhrase("系统通知已开启。", "System notifications are enabled.")
            else nativePhrase("系统通知权限未开启，可在系统设置中允许。", "System notification permission is disabled. You can allow it in system settings."),
        )
    }

    fun requestNativeNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !NativeLocalNotifications.isAllowed(context)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showNativeNotice(nativePhrase("系统通知已开启。", "System notifications are enabled."))
        }
    }
    val hazeState = rememberHazeState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appInForeground = true
                Lifecycle.Event.ON_STOP -> appInForeground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun clearOpenTarget() {
        loginVisible = false
        discoveryInitialMode = "推荐"
        discoveryInitialQuery = ""
        accountInitialStatus = null
        accountInitialQuery = ""
        subscriptionInitialSort = "latest"
        collectionInitialView = "mine"
        accountTarget = null
        adminSystemParentIsAdmin = false
        adminSystemChildFromSystem = false
        announcementTargetId = null
        publicProfileTarget = null
        profileBackTarget = null
        searchVisible = false
        searchInitialQuery = ""
        conversationTarget = null
        pendingMessagesRoute = null
        pendingMessagesRouteUrl = null
        groupScreenVisible = false
        groupInitialId = null
        groupInitialView = "mine"
        groupInitialReportId = null
        peopleScreenVisible = false
        writeTarget = null
        pendingWriteTarget = null
        writeBackTarget = null
        pendingWriteBackTarget = null
        adminPreviewArticleId = null
        pendingAdminPreviewArticleId = null
        adminPreviewBackTarget = null
        pendingAdminPreviewBackTarget = null
        detailTarget = null
        detailBackTarget = null
        unsubscribeToken = null
    }

    fun openProtectedAccountTarget(
        target: AccountTarget,
        initialStatus: String? = null,
        initialQuery: String = "",
        initialSubscriptionSort: String = "latest",
        initialCollectionView: String = "mine",
    ) {
        clearOpenTarget()
        accountInitialStatus = initialStatus
        accountInitialQuery = initialQuery
        subscriptionInitialSort = initialSubscriptionSort
        collectionInitialView = normalizeNativeCollectionView(initialCollectionView)
        accountTarget = target
        if (session == null) loginVisible = true
    }

    fun openExternalPortalEntry(targetUri: Uri) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, targetUri))
        }.onFailure {
            showNativeNotice(nativePhrase("设备上没有可以打开该入口的应用。", "No app is available to open this entry."))
        }
    }

    fun openDetailTarget(target: DetailTarget, backTarget: NativeDetailBackTarget? = null) {
        clearOpenTarget()
        detailBackTarget = backTarget
        detailTarget = target
    }

    fun closeDetailTarget() {
        val backTarget = detailBackTarget
        detailBackTarget = null
        detailTarget = null
        when (backTarget) {
            is NativeDetailBackTarget.Account -> accountTarget = backTarget.target
            is NativeDetailBackTarget.PublicProfile -> publicProfileTarget = backTarget.username
            is NativeDetailBackTarget.Detail -> detailTarget = backTarget.target
            null -> Unit
        }
    }

    fun openProfileFromDetail(username: String, target: DetailTarget, parent: NativeDetailBackTarget?) {
        if (username.isBlank()) return
        detailTarget = null
        detailBackTarget = null
        profileBackTarget = NativeProfileBackTarget(target, parent)
        publicProfileTarget = username
    }

    fun closePublicProfile() {
        val backTarget = profileBackTarget
        profileBackTarget = null
        publicProfileTarget = null
        if (backTarget != null) {
            detailTarget = backTarget.target
            detailBackTarget = backTarget.detailBackTarget
        }
    }

    /** 按 route 打开会话；用 accessToken 校验实时可见性，existingConversation 仅提供标题，完成后返回。 */
    suspend fun openMessagesConversation(route: PendingMessagesRoute, accessToken: String, existingConversation: NativeConversationPreview? = null) {
        // 通知、系统 Intent 和应用链接共用同一个前置校验，旧缓存或本人隐藏不能绕过检查。
        if (route.messageId != null) {
            try {
                withContext(Dispatchers.IO) { NativeSocialApi.getMessageContext(accessToken, route.conversationId, route.messageId) }
            } catch (exception: CancellationException) {
                // 新入口或页面销毁取消旧导航时停止旧任务，不能把协程取消当成删除或网络错误。
                throw exception
            } catch (exception: Exception) {
                // 只有确定不可见才提示删除；网络、限流、登录失效等保留实际原因并留在原页面。
                val unavailable = exception is NativeSocialHttpException && exception.statusCode in listOf(403, 404)
                showNativeNotice(if (unavailable) nativePhrase("该消息已删除或当前不可见，无法定位。", "This message was deleted or is not visible to you, so it cannot be located.")
                    else exception.message ?: nativePhrase("暂时无法验证消息，请稍后重试。", "Unable to check this message right now. Try again shortly."))
                return
            }
        }
        // 在切换页面前读取预览；非权限性读取失败只降级标题，取消则仍交还调用方处理。
        val preview = try {
            withContext(Dispatchers.IO) {
                NativeSocialApi.listConversations(accessToken).firstOrNull { it.id == route.conversationId }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }
        val fallback = existingConversation ?: NativeConversationPreview(
            id = route.conversationId,
            title = nativePhrase("聊天会话", "Chat"),
            initials = nativePhrase("聊天", "Chat"),
            message = "",
            time = "",
            unreadCount = 0,
            kind = "",
        )
        // 所有异步校验结束后一次性切换，调用方可在本方法返回后安全消费系统入口。
        clearOpenTarget()
        selectedTab = 3
        conversationTarget = ConversationTarget(preview ?: fallback, route.messageId)
    }

    /** 解析 targetUri 的消息入口，失效定位保持当前页面，其余入口按登录状态延迟恢复。 */
    fun openMessagesRoute(targetUri: Uri) {
        // 链接携带确定删除状态时立即阻止；其它定位由统一会话入口做实时检查。
        val route = parseNativeMessagesRoute(targetUri)
        if (route.messageDeleted) {
            showNativeNotice(nativePhrase("该消息已删除，无法定位。", "This message was deleted and cannot be located."))
            return
        }
        // 已登录会话入口由统一函数先校验，不能在检查前关闭当前页面。
        if (route.kind == NativeMessagesRouteKind.Conversation && !session?.accessToken.isNullOrBlank()) {
            val pending = PendingMessagesRoute(route.conversationId ?: return, route.messageId)
            val accessToken = session!!.accessToken
            scope.launch { openMessagesConversation(pending, accessToken) }
            return
        }
        clearOpenTarget()
        selectedTab = 3
        val accessToken = session?.accessToken
        if (accessToken.isNullOrBlank() && route.kind != NativeMessagesRouteKind.Inbox) {
            pendingMessagesRouteUrl = targetUri.toString()
            loginVisible = true
            return
        }
        when (route.kind) {
            NativeMessagesRouteKind.Inbox -> Unit
            NativeMessagesRouteKind.Conversation -> {
                val conversationId = route.conversationId ?: return
                scope.launch { openMessagesConversation(PendingMessagesRoute(conversationId, route.messageId), accessToken.orEmpty()) }
            }
            NativeMessagesRouteKind.Friends -> peopleScreenVisible = true
            NativeMessagesRouteKind.GroupManager -> {
                groupInitialId = route.groupId
                groupInitialView = if (route.joinRequestId != null) "approvals" else "mine"
                groupInitialReportId = route.reportId
                groupScreenVisible = true
            }
        }
    }

    fun openWriteRoute(articleId: Int, backTarget: AccountTarget? = null) {
        val target = NativeWriteTarget.Existing(articleId)
        clearOpenTarget()
        if (session == null) {
            pendingWriteTarget = target
            pendingWriteBackTarget = backTarget
            loginVisible = true
        } else {
            writeBackTarget = backTarget
            writeTarget = target
        }
    }

    fun openAdminPreviewRoute(articleId: Int, backTarget: AccountTarget? = null) {
        clearOpenTarget()
        if (session == null) {
            pendingAdminPreviewArticleId = articleId
            pendingAdminPreviewBackTarget = backTarget
            loginVisible = true
        } else {
            adminPreviewBackTarget = backTarget
            adminPreviewArticleId = articleId
        }
    }

    fun closeWriteTarget() {
        val backTarget = writeBackTarget
        writeBackTarget = null
        writeTarget = null
        if (backTarget != null) accountTarget = backTarget
    }

    fun closeAdminPreviewTarget() {
        val backTarget = adminPreviewBackTarget
        adminPreviewBackTarget = null
        adminPreviewArticleId = null
        if (backTarget != null) accountTarget = backTarget
    }

    /** 将 rawUrlValue 分发至原生页面；内容定位需实时可见，普通阅读允许既有离线回退。 */
    fun openPortalUrl(rawUrlValue: String?) {
        // 统一解析本站地址与外链，后续分支维持各业务页面的登录和权限边界。
        val rawUrl = rawUrlValue?.trim().orEmpty()
        if (rawUrl.isBlank()) {
            showNativeNotice(nativePhrase("该入口暂未配置可打开的地址。", "This entry does not have an openable address configured yet."))
            return
        }
        val targetUri = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            Uri.parse(rawUrl)
        } else {
            Uri.parse("${nativePortalBaseUrl()}/${rawUrl.trimStart('/')}")
        }
        if (!isNativePortalHost(targetUri)) {
            openExternalPortalEntry(targetUri)
            return
        }
        val path = normalizeNativePath(targetUri.path)
        val adminTarget = nativeAdminAccountTarget(path)
        val articleManagementTarget = nativeArticleManagementTarget(path)
        when (path) {
            "/unsubscribe" -> {
                clearOpenTarget()
                loginVisible = false
                unsubscribeToken = ""
            }
            else -> when {
                path.startsWith("/unsubscribe/") -> {
                    clearOpenTarget()
                    loginVisible = false
                    unsubscribeToken = Uri.decode(path.removePrefix("/unsubscribe/").trim('/'))
                }
                path == "/search" -> {
                    clearOpenTarget()
                    searchInitialQuery = targetUri.getQueryParameter("q")?.trim().orEmpty().take(80)
                    searchVisible = true
                }
                path == "/login" -> {
                    clearOpenTarget()
                    loginInitialStep = NativeLoginStep.Credentials
                    loginVisible = true
                }
                path == "/register" -> {
                    clearOpenTarget()
                    loginInitialStep = NativeLoginStep.Register
                    loginVisible = true
                }
                path == "/forgot-password" -> {
                    clearOpenTarget()
                    loginInitialStep = NativeLoginStep.RecoveryRequest
                    loginVisible = true
                }
                path == "/dashboard" -> openProtectedAccountTarget(AccountTarget.Dashboard)
                path == "/profile/edit" -> openProtectedAccountTarget(AccountTarget.ProfileEditor)
                path == "/nav" -> {
                    clearOpenTarget()
                    accountTarget = AccountTarget.Navigation
                }
                path == "/tools" -> {
                    clearOpenTarget()
                    accountTarget = AccountTarget.Tools
                }
                path == "/install" -> {
                    clearOpenTarget()
                    accountTarget = AccountTarget.Install
                }
                path == "/messages" -> openMessagesRoute(targetUri)
                path == "/articles/write" -> {
                    clearOpenTarget()
                    if (session == null) {
                        loginVisible = true
                    } else {
                        writeBackTarget = null
                        writeTarget = NativeWriteTarget.New()
                    }
                }
                articleManagementTarget != null -> openProtectedAccountTarget(
                    target = articleManagementTarget,
                    initialStatus = if (articleManagementTarget == AccountTarget.MyArticles) {
                        normalizeNativeArticleStatus(targetUri.getQueryParameter("status"))
                    } else {
                        null
                    },
                    initialQuery = if (articleManagementTarget == AccountTarget.MyArticles) {
                        normalizeNativeRouteQuery(targetUri.getQueryParameter("q"))
                    } else {
                        ""
                    },
                )
                path == "/articles/favorites" -> openProtectedAccountTarget(AccountTarget.MyFavorites)
                path == "/articles/liked" -> openProtectedAccountTarget(AccountTarget.MyLiked)
                path == "/articles/reading" || path == "/articles/read-later" -> when (normalizeNativeReadingTab(targetUri.getQueryParameter("tab"))) {
                    "favorites" -> openProtectedAccountTarget(AccountTarget.MyFavorites)
                    "liked" -> openProtectedAccountTarget(AccountTarget.MyLiked)
                    "history" -> openProtectedAccountTarget(AccountTarget.ReadingHistory)
                    else -> openProtectedAccountTarget(AccountTarget.ReadLater)
                }
                path == "/articles/history" -> openProtectedAccountTarget(AccountTarget.ReadingHistory)
                path == "/articles/subscriptions" -> openProtectedAccountTarget(
                    target = AccountTarget.SubscriptionFeed,
                    initialSubscriptionSort = normalizeNativeSubscriptionSort(targetUri.getQueryParameter("sort")),
                )
                path == "/articles/collections" -> openProtectedAccountTarget(
                    target = AccountTarget.MyCollections,
                    initialCollectionView = "browse",
                )
                path == "/articles/templates" -> openProtectedAccountTarget(AccountTarget.TemplateManagement)
                path == "/articles/resources" -> {
                    clearOpenTarget()
                    accountTarget = AccountTarget.ResourceCenter
                }
                path == "/articles/resources/orders" -> openProtectedAccountTarget(AccountTarget.ResourceOrders)
                path == "/articles/resources/earnings" -> openProtectedAccountTarget(AccountTarget.ResourceEarnings)
                path == "/offline" -> {
                    clearOpenTarget()
                    accountTarget = AccountTarget.Offline
                }
                path == "/voices" -> {
                    clearOpenTarget()
                    accountTarget = AccountTarget.AnonymousTopics
                }
                path == "/ai" -> openProtectedAccountTarget(AccountTarget.AiAssistant)
                adminTarget != null -> openProtectedAccountTarget(adminTarget)
                path == "/profile" -> {
                    clearOpenTarget()
                    selectedTab = 4
                }
                path == "/profile/privacy" -> openProtectedAccountTarget(AccountTarget.Privacy)
                path == "/profile/integrations" -> openProtectedAccountTarget(AccountTarget.Integrations)
                path == "/profile/reports" -> openProtectedAccountTarget(AccountTarget.MyReports)
                path == "/profile/recommendations" -> openProtectedAccountTarget(AccountTarget.RecommendationFeedback)
                path == "/profile/points" -> openProtectedAccountTarget(AccountTarget.Points)
                path == "/topics" -> {
                    if (session?.isSuperAdmin == true || session?.isAdministrator == true) {
                        openProtectedAccountTarget(AccountTarget.TopicManagement)
                    } else {
                        clearOpenTarget()
                        discoveryInitialMode = "专题"
                        discoveryInitialQuery = targetUri.getQueryParameter("q")?.trim().orEmpty().take(80)
                        selectedTab = 1
                    }
                }
                path == "/collections" -> {
                    clearOpenTarget()
                    discoveryInitialMode = "合集"
                    discoveryInitialQuery = targetUri.getQueryParameter("q")?.trim().orEmpty().take(80)
                    selectedTab = 1
                }
                path == "/articles" -> {
                    clearOpenTarget()
                    discoveryInitialMode = "文章"
                    discoveryInitialQuery = targetUri.getQueryParameter("q")?.trim().orEmpty().take(80)
                    selectedTab = 1
                }
                path == "/announcements" -> {
                    clearOpenTarget()
                    accountTarget = AccountTarget.Announcements
                }
                path == "/feedback" -> {
                    clearOpenTarget()
                    accountTarget = AccountTarget.Feedback
                }
                path == "/suggestions" -> {
                    clearOpenTarget()
                    accountTarget = AccountTarget.Suggestions
                }
                path == "/" -> {
                    clearOpenTarget()
                    selectedTab = 0
                }
                path.startsWith("/articles/edit/") -> {
                    val articleId = path.removePrefix("/articles/edit/").trim('/').toIntOrNull()
                    if (articleId == null || articleId <= 0) {
                        showNativeNotice(nativePhrase("文章编号无效，无法打开编辑器。", "The article ID is invalid, so the editor cannot be opened."))
                    } else {
                        openWriteRoute(articleId)
                    }
                }
                path.startsWith("/articles/preview/") -> {
                    val articleId = path.removePrefix("/articles/preview/").trim('/').toIntOrNull()
                    if (articleId == null || articleId <= 0) {
                        showNativeNotice(nativePhrase("文章编号无效，无法打开预览。", "The article ID is invalid, so the preview cannot be opened."))
                    } else {
                        openAdminPreviewRoute(articleId)
                    }
                }
                path.startsWith("/articles/") -> {
                    val commentStatus = targetUri.getQueryParameter("commentStatus").orEmpty()
                    if (targetUri.getQueryParameter("commentDeleted") == "1" ||
                        commentStatus.equals("deleted", ignoreCase = true) ||
                        commentStatus.equals("blocked", ignoreCase = true)
                    ) {
                        showNativeNotice(nativePhrase("该评论已删除或屏蔽，无法定位。", "This comment was deleted or blocked and cannot be located."))
                        return
                    }
                    val slug = Uri.decode(path.removePrefix("/articles/")).trim('/').takeIf { it.isNotBlank() && !it.contains('/') && it !in setOf("write", "mine", "manage", "favorites", "liked", "reading", "history", "subscriptions", "collections", "templates", "resources", "preview", "edit") }
                    if (slug == null) {
                        openExternalPortalEntry(targetUri)
                    } else {
                        val focusCommentId = targetUri.getQueryParameter("focusId")?.toIntOrNull()
                            ?: targetUri.getQueryParameter("commentId")?.toIntOrNull()
                        scope.launch {
                            val loaded = runCatching {
                                withContext(Dispatchers.IO) {
                                    val article = NativeArticleApi.getArticle(session?.accessToken, slug)
                                    focusCommentId?.let { commentId ->
                                        val comments = NativeArticleApi.listComments(session?.accessToken, slug, focusId = commentId)
                                        check(comments.items.any { it.id == commentId && it.status == "active" }) {
                                            nativePhrase("该评论已删除或当前不可见，无法定位。", "This comment was deleted or is not visible to you, so it cannot be located.")
                                        }
                                    }
                                    article
                                }
                            }
                            loaded.onSuccess { article ->
                                openDetailTarget(DetailTarget.Article(article, focusCommentId))
                            }
                            if (loaded.isFailure) {
                                val exception = loaded.exceptionOrNull()
                                // 明确的评论定位必须先通过实时检查，不能用离线文章绕过失效目标拦截。
                                if (focusCommentId != null) {
                                    showNativeNotice(exception?.message ?: nativePhrase("暂时无法验证评论，请稍后重试。", "Unable to check this comment right now. Try again shortly."))
                                    return@launch
                                }
                                val cached = withContext(Dispatchers.IO) {
                                    NativeOfflineContentStore.loadArticle(
                                        context = context,
                                        owner = NativeOfflineOwner.forSession(session),
                                        slug = slug,
                                    )
                                }
                                if (cached != null) {
                                    openDetailTarget(DetailTarget.Article(cached, focusCommentId))
                                } else {
                                    showNativeNotice(exception?.message ?: nativePhrase("文章暂时无法打开。", "The article cannot be opened right now."))
                                }
                            }
                        }
                    }
                }
                path.startsWith("/topics/") -> {
                    val slug = Uri.decode(path.removePrefix("/topics/")).trim('/').takeIf { it.isNotBlank() && !it.contains('/') }
                    if (slug == null) {
                        openExternalPortalEntry(targetUri)
                    } else {
                        scope.launch {
                            val loaded = runCatching {
                                withContext(Dispatchers.IO) { NativeDiscoveryApi.getTopic(session?.accessToken, slug) }
                            }
                            loaded.onSuccess { topic ->
                                openDetailTarget(DetailTarget.Topic(topic))
                            }
                            if (loaded.isFailure) {
                                val exception = loaded.exceptionOrNull()
                                val cached = withContext(Dispatchers.IO) {
                                    NativeOfflineContentStore.loadTopic(
                                        context = context,
                                        owner = NativeOfflineOwner.forSession(session),
                                        slug = slug,
                                    )
                                }
                                if (cached != null) {
                                    openDetailTarget(DetailTarget.Topic(cached))
                                } else {
                                    showNativeNotice(exception?.message ?: nativePhrase("专题暂时无法打开。", "The topic cannot be opened right now."))
                                }
                            }
                        }
                    }
                }
                path.startsWith("/collections/") -> {
                    val id = path.removePrefix("/collections/").trim('/').toIntOrNull()
                    if (id == null || id <= 0) {
                        openExternalPortalEntry(targetUri)
                    } else {
                        scope.launch {
                            val loaded = runCatching {
                                withContext(Dispatchers.IO) { NativeDiscoveryApi.getCollection(session?.accessToken, id) }
                            }
                            loaded.onSuccess { collection ->
                                openDetailTarget(DetailTarget.Collection(collection))
                            }
                            if (loaded.isFailure) {
                                val exception = loaded.exceptionOrNull()
                                val cached = withContext(Dispatchers.IO) {
                                    NativeOfflineContentStore.loadCollection(
                                        context = context,
                                        owner = NativeOfflineOwner.forSession(session),
                                        id = id,
                                    )
                                }
                                if (cached != null) {
                                    openDetailTarget(DetailTarget.Collection(cached))
                                } else {
                                    showNativeNotice(exception?.message ?: nativePhrase("合集暂时无法打开。", "The collection cannot be opened right now."))
                                }
                            }
                        }
                    }
                }
                path.startsWith("/users/") -> {
                    val username = Uri.decode(path.removePrefix("/users/")).trim('/').takeIf { it.isNotBlank() && !it.contains('/') }
                    if (username == null) openExternalPortalEntry(targetUri) else {
                        clearOpenTarget()
                        publicProfileTarget = username
                    }
                }
                path.startsWith("/announcements/") -> {
                    val id = path.removePrefix("/announcements/").trim('/').toIntOrNull()
                    if (id == null || id <= 0) {
                        openExternalPortalEntry(targetUri)
                    } else {
                        clearOpenTarget()
                        announcementTargetId = id
                        accountTarget = AccountTarget.Announcements
                    }
                }
                else -> openExternalPortalEntry(targetUri)
            }
        }
    }

    LaunchedEffect(oauthResultToken, oauthErrorMessage, oauthReturnTo, passwordResetToken, session?.userId) {
        // A Google callback started from integrations stays on that page. All
        // other callbacks enter the native authentication flow, including cold starts.
        if (oauthReturnTo == "/profile/integrations" && session != null && (oauthResultToken != null || !oauthErrorMessage.isNullOrBlank())) {
            loginVisible = false
            accountTarget = AccountTarget.Integrations
        } else if (oauthResultToken != null || passwordResetToken != null) {
            loginInitialStep = NativeLoginStep.Credentials
            loginVisible = true
        }
    }

    LaunchedEffect(portalIntentUrl, sessionRestoreComplete) {
        if (!canConsumeNativePortalIntent(portalIntentUrl, sessionRestoreComplete)) return@LaunchedEffect
        val pendingUrl = portalIntentUrl ?: return@LaunchedEffect
        onPortalIntentConsumed()
        openPortalUrl(pendingUrl)
    }

    LaunchedEffect(session?.accessToken, pendingMessagesRoute) {
        val pending = pendingMessagesRoute ?: return@LaunchedEffect
        val accessToken = session?.accessToken ?: return@LaunchedEffect
        openMessagesConversation(pending, accessToken)
        pendingMessagesRoute = null
    }

    LaunchedEffect(session?.accessToken, pendingMessagesRouteUrl) {
        val pending = pendingMessagesRouteUrl ?: return@LaunchedEffect
        if (session?.accessToken.isNullOrBlank()) return@LaunchedEffect
        pendingMessagesRouteUrl = null
        openMessagesRoute(Uri.parse(pending))
    }

    /** 顺序校验并打开 notification；existingConversation 仅提供标题，返回时已完成目标切换或错误提示。 */
    suspend fun openNotification(
        notification: NativeNotificationPreview,
        existingConversation: NativeConversationPreview?,
    ) {
        // 登录状态和服务端删除标记优先，不能先跳转再在目标页补报错误。
        val token = session?.accessToken
        if (token.isNullOrBlank()) {
            loginVisible = true
            return
        }
        if (notification.messageDeleted || notification.commentDeleted) {
            showNativeNotice(nativePhrase("该消息已删除，无法定位。", "This message was deleted and cannot be located."))
            return
        }

        // 操作型通知维持好友和群管理路径，内容型通知按稳定内容 ID 做校验。
        val actionUri = notification.actionUrl?.let(Uri::parse)
        val actionPath = normalizeNativePath(actionUri?.path)
        if (actionUri != null && actionPath == "/messages") {
            val route = parseNativeMessagesRoute(actionUri)
            if (route.kind == NativeMessagesRouteKind.Friends || route.kind == NativeMessagesRouteKind.GroupManager) {
                openMessagesRoute(actionUri)
                return
            }
        }

        if (notification.friendshipId != null || notification.strangerRequest) {
            clearOpenTarget()
            selectedTab = 3
            peopleScreenVisible = true
            return
        }

        val conversationId = notification.conversationId
        if (notification.type == "mention_received" && conversationId != null) {
            openMessagesConversation(PendingMessagesRoute(conversationId, notification.messageId), token,
                existingConversation ?: fallbackConversationPreview(notification, conversationId))
            return
        }

        val articleSlug = notification.articleSlug
        if (!articleSlug.isNullOrBlank()) {
            try {
                val article = withContext(Dispatchers.IO) {
                    val loaded = NativeArticleApi.getArticle(token, articleSlug)
                    // 老系统通知可能只有链接而没有通知 ID，仍须验证目标评论确实存在且可见。
                    notification.commentId?.let { commentId ->
                        val comments = NativeArticleApi.listComments(token, articleSlug, focusId = commentId)
                        check(comments.items.any { it.id == commentId && it.status == "active" }) {
                            nativePhrase("该评论已删除或当前不可见，无法定位。", "This comment was deleted or is not visible to you, so it cannot be located.")
                        }
                    }
                    loaded
                }
                // 确认可见后同步写入目标，系统入口不能在后台校验尚未完成时就被消费。
                openDetailTarget(DetailTarget.Article(article, notification.commentId))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showNativeNotice(exception.message ?: nativePhrase("文章暂时无法打开。", "The article cannot be opened right now."))
            }
            return
        }

        if (conversationId != null) {
            openMessagesConversation(PendingMessagesRoute(conversationId, notification.messageId), token,
                existingConversation ?: fallbackConversationPreview(notification, conversationId))
            return
        }

        if (actionUri?.getQueryParameter("groupApproval")?.toIntOrNull() != null) {
            clearOpenTarget()
            selectedTab = 3
            groupScreenVisible = true
            return
        }
        if (actionUri == null) {
            showNativeNotice(nativePhrase("这条通知暂时没有可打开的页面。", "This notification does not have an openable page."))
        } else {
            // Keep notification links on the same native routing path as portal/search entries.
            openPortalUrl(notification.actionUrl)
        }
    }

    val nativeCallLauncher = NativeGlobalCallHost(
        session = session,
        onNotice = ::showNativeNotice,
        onReauthenticate = { complete -> requestSocketReauthentication { complete(it?.accessToken) } },
    )

    LaunchedEffect(Unit) {
        NativeLocalNotifications.ensureChannel(context)
    }

    LaunchedEffect(session?.accessToken, appInForeground) {
        val current = session ?: return@LaunchedEffect
        if (!appInForeground || current.accessToken.isBlank()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { NativeFirebasePush.sync(context, current.accessToken) }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val stored = withContext(Dispatchers.IO) { NativeSessionStore.load(context) }
            if (stored == null && withContext(Dispatchers.IO) { NativeSessionStore.consumeLegacyReauthenticationNotice(context) }) {
                showNativeNotice(nativePhrase(
                    "旧版本登录信息未记录服务器来源，请重新登录。个人设置和离线内容不会被删除。",
                    "Your previous session has no server origin. Please sign in again. Personal settings and offline content are preserved.",
                ))
            }
            val restored = stored?.let { saved ->
                if (saved.refreshToken.isBlank()) {
                    saved
                } else {
                    try {
                        withContext(Dispatchers.IO) { NativeAuthApi.refresh(saved.refreshToken) }
                    } catch (error: NativeAuthHttpException) {
                        if (error.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                            unregisterNativePush(saved)
                            withContext(Dispatchers.IO) { NativeSessionStore.clear(context) }
                            NativeLocalNotifications.clear(context)
                            loginVisible = true
                            showNativeNotice(nativePhrase("登录状态已失效，请重新登录。", "Your session has expired. Please sign in again."))
                            null
                        } else {
                            saved
                        }
                    } catch (_: Exception) {
                        saved
                    }
                }
            }
            if (restored != null) {
                HlovetUi.applyAppearance(restored.appearance)
                offlineLegacyOwner = null
                session = restored
                withContext(Dispatchers.IO) { NativeSessionStore.save(context, restored) }
            }
        } finally {
            // Deep links must wait until the saved session has either been restored or rejected.
            sessionRestoreComplete = true
        }
    }
    LaunchedEffect(session?.userId, session?.username, offlineLegacyOwner) {
        val current = session
        if (current == null) {
            offlineMigrationReadyOwner = NativeOfflineOwner.PUBLIC
            return@LaunchedEffect
        }
        val owner = NativeOfflineOwner.forSession(current)
        offlineMigrationReadyOwner = null
        withContext(Dispatchers.IO) {
            NativeOfflineOwner.migrate(context, current, offlineLegacyOwner)
        }
        if (session?.userId == current.userId && session?.username == current.username) {
            offlineMigrationReadyOwner = owner
        }
    }
    LaunchedEffect(session?.refreshToken) {
        val initial = session ?: return@LaunchedEffect
        if (initial.refreshToken.isBlank()) return@LaunchedEffect
        while (true) {
            // Access tokens default to 15 minutes; refresh well before expiry while this app is active.
            delay(8 * 60 * 1000L)
            val current = session ?: return@LaunchedEffect
            if (current.refreshToken != initial.refreshToken) return@LaunchedEffect
            try {
                val refreshed = withContext(Dispatchers.IO) { NativeAuthApi.refresh(current.refreshToken) }
                HlovetUi.applyAppearance(refreshed.appearance)
                session = refreshed
                withContext(Dispatchers.IO) { NativeSessionStore.save(context, refreshed) }
            } catch (error: NativeAuthHttpException) {
                if (error.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    unregisterNativePush(current)
                    withContext(Dispatchers.IO) { NativeSessionStore.clear(context) }
                    NativeLocalNotifications.clear(context)
                    session = null
                    profileSummary = null
                    reputationSummary = null
                    accountTarget = null
                    conversationTarget = null
                    writeTarget = null
                    detailTarget = null
                    loginVisible = true
                    showNativeNotice(nativePhrase("登录状态已失效，请重新登录。", "Your session has expired. Please sign in again."))
                    return@LaunchedEffect
                }
                // Keep the current session during temporary server/network failures and retry next cycle.
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A transient network failure must not discard the encrypted session or offline content.
            }
        }
    }
    LaunchedEffect(session?.accessToken, appInForeground, inboxReloadKey) {
        val accessToken = session?.accessToken
        if (accessToken.isNullOrBlank()) {
            inboxSummary = null
            return@LaunchedEffect
        }
        if (!appInForeground) return@LaunchedEffect
        while (isActive) {
            runCatching {
                withContext(Dispatchers.IO) { NativeSocialApi.loadInboxSummary(accessToken) }
            }.onSuccess { refreshed ->
                if (session?.accessToken == accessToken) inboxSummary = refreshed
            }
            if (NativeLocalNotifications.isAllowed(context)) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        NativeSocialApi.listNotifications(accessToken, limit = 50)
                    }
                }.onSuccess { page ->
                    // Notification de-duplication must survive a username change. The
                    // offline store already uses the immutable account id for the same
                    // isolation boundary, so keep foreground notifications on that key too.
                    val owner = NativeOfflineOwner.forSession(session)
                    val channelStates = page.channelStates.associateBy { it.channel }
                    val allowedNotifications = NativeLocalNotifications
                        .takeNewUnread(context, owner, page.items)
                        .filter { notification ->
                            notification.channel !in page.hiddenChannels &&
                                channelStates[notification.channel]?.pushEnabled != false
                        }
                    allowedNotifications
                        .forEach { notification ->
                            runCatching { NativeLocalNotifications.show(context, notification) }
                        }
                }
            }
            delay(45_000L)
        }
    }
    LaunchedEffect(notificationIntent, session?.accessToken, sessionRestoreComplete) {
        // 冷启动先完成加密会话恢复，再判断通知是否需要登录，避免短暂空会话误打开登录页。
        if (!sessionRestoreComplete) return@LaunchedEffect
        val pending = notificationIntent ?: return@LaunchedEffect
        val accessToken = session?.accessToken
        if (accessToken.isNullOrBlank()) {
            loginVisible = true
            return@LaunchedEffect
        }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                if (pending.notificationId != null) {
                    // 按通知 ID 获取实时状态，不以最近 50 条列表是否命中判断通知已经不存在。
                    NativeSocialApi.markNotificationRead(accessToken, pending.notificationId)
                } else {
                    val actionUri = pending.actionUrl?.let(Uri::parse)
                    val commentStatus = actionUri?.getQueryParameter("commentStatus").orEmpty()
                    NativeNotificationPreview(
                        id = 0,
                        title = pending.title.orEmpty().ifBlank { nativePhrase("系统通知", "System notification") },
                        body = pending.body.orEmpty(),
                        unread = false,
                        type = pending.type.orEmpty(),
                        actionUrl = pending.actionUrl,
                        conversationId = actionUri?.getQueryParameter("conversation")?.toIntOrNull(),
                        messageId = pending.messageId
                            ?: actionUri?.getQueryParameter("messageId")?.toIntOrNull(),
                        commentId = actionUri?.getQueryParameter("commentId")?.toIntOrNull(),
                        messageDeleted = actionUri?.getQueryParameter("messageDeleted") == "1",
                        commentDeleted = actionUri?.getQueryParameter("commentDeleted") == "1" ||
                            commentStatus.equals("deleted", ignoreCase = true) ||
                            commentStatus.equals("blocked", ignoreCase = true),
                    )
                }
            }
        }
        // 先完成目标校验和页面切换再清空入口；清空会取消当前 effect，不能提前中断尚未完成的导航。
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        result.onSuccess { openNotification(it, null) }
            .onFailure { showNativeNotice(it.message ?: nativePhrase("该通知暂时无法打开。", "The notification cannot be opened right now.")) }
        onNotificationIntentConsumed()
    }
    LaunchedEffect(passwordResetToken) {
        if (!passwordResetToken.isNullOrBlank()) loginVisible = true
    }
    LaunchedEffect(reloadKey, session?.accessToken, offlineMigrationReadyOwner) {
        val cacheOwner = NativeOfflineOwner.forSession(session)
        if (offlineMigrationReadyOwner != cacheOwner) return@LaunchedEffect
        val accessToken = session?.accessToken
        nativePreviewLoad(
            readCache = {
                withContext(Dispatchers.IO) {
                    NativeOfflineCache.loadPreview(context, cacheOwner)
                        ?: if (cacheOwner != "public") NativeOfflineCache.loadPreview(context, "public") else null
                }
            },
            fetch = { PreviewApi.load(accessToken) },
            save = { data -> withContext(Dispatchers.IO) { NativeOfflineCache.savePreview(context, data, cacheOwner) } },
        ).collect { state ->
            if (state is PreviewLoadState.Ready) {
                HlovetUi.applyAppearance(session?.appearance ?: state.data.appearance)
            }
            loadState = state
        }
    }
    LaunchedEffect(loadState) {
        val data = (loadState as? PreviewLoadState.Ready)?.data ?: return@LaunchedEffect
        backgroundBitmap = data.backgroundUrl?.let { url -> withContext(Dispatchers.IO) { NativeOfflineCache.loadBitmap(context, url) } }
    }
    LaunchedEffect(session?.accessToken, session?.username, session?.profileBio) {
        val currentSession = session ?: run {
            profileSummary = null
            profileError = null
            return@LaunchedEffect
        }
        profileLoading = true
        profileError = null
        try {
            profileSummary = withContext(Dispatchers.IO) {
                NativeProfileApi.loadSummary(currentSession.accessToken, currentSession.username)
            }
        } catch (error: Exception) {
            profileError = error.message ?: nativePhrase("个人资料加载失败。", "Could not load the profile.")
        } finally {
            profileLoading = false
        }
    }
    LaunchedEffect(session?.accessToken) {
        articleCenterSummary = runCatching {
            withContext(Dispatchers.IO) {
                session?.accessToken?.takeIf { it.isNotBlank() }?.let(NativeArticleApi::visibleCenterSummary)
                    ?: NativeArticleApi.publicCenterSummary()
            }
        }.getOrNull()
    }
    LaunchedEffect(session?.accessToken) {
        val currentToken = session?.accessToken
        if (currentToken.isNullOrBlank()) {
            reputationSummary = null
            reputationError = null
            reputationLoading = false
            return@LaunchedEffect
        }
        reputationLoading = true
        reputationError = null
        runCatching {
            withContext(Dispatchers.IO) { NativeResourceApi.getReputation(currentToken) }
        }.onSuccess { summary ->
            reputationSummary = summary
        }.onFailure { exception ->
            reputationSummary = null
            reputationError = exception.message ?: nativePhrase("成长与积分加载失败。", "Could not load growth and points.")
        }
        reputationLoading = false
    }
    LaunchedEffect(session?.accessToken) {
        onboardingState = null
        onboardingVisible = false
        onboardingError = null
        val token = session?.accessToken
        if (token.isNullOrBlank()) return@LaunchedEffect
        try {
            val state = withContext(Dispatchers.IO) { NativeDiscoveryApi.getOnboarding(token) }
            onboardingState = state
            onboardingVisible = !state.completed
        } catch (error: Exception) {
            // Interest onboarding must never prevent the rest of the app from opening.
            onboardingError = error.message ?: nativePhrase("兴趣引导暂时无法加载。", "Interest onboarding is temporarily unavailable.")
        }
    }

    fun submitOnboarding(topicIds: List<Int>, authorIds: List<Int>) {
        val token = session?.accessToken ?: return
        if (onboardingSaving) return
        scope.launch {
            onboardingSaving = true
            onboardingError = null
            try {
                withContext(Dispatchers.IO) {
                    NativeDiscoveryApi.completeOnboarding(token, topicIds, authorIds)
                }
                onboardingState = onboardingState?.copy(completed = true)
                onboardingVisible = false
                reloadKey += 1
            } catch (error: Exception) {
                onboardingError = error.message ?: nativePhrase("兴趣选择保存失败，请稍后重试。", "Could not save your interests. Try again later.")
            } finally {
                onboardingSaving = false
            }
        }
    }
    CompositionLocalProvider(LocalHlovetHaze provides hazeState) {
        BackHandler(
            enabled = onboardingVisible || loginVisible || unsubscribeToken != null || searchVisible || publicProfileTarget != null || conversationTarget != null || groupScreenVisible || detailTarget != null || accountTarget != null || writeTarget != null || adminPreviewArticleId != null,
        ) {
            when {
                onboardingVisible -> Unit
                loginVisible -> {
                    loginVisible = false
                    pendingWriteTarget = null
                    pendingWriteBackTarget = null
                    pendingAdminPreviewArticleId = null
                    pendingAdminPreviewBackTarget = null
                }
                unsubscribeToken != null -> unsubscribeToken = null
                searchVisible -> searchVisible = false
                publicProfileTarget != null -> closePublicProfile()
                conversationTarget != null -> conversationTarget = null
                groupScreenVisible -> groupScreenVisible = false
                detailTarget != null -> closeDetailTarget()
                accountTarget != null -> accountTarget = null
                adminPreviewArticleId != null -> closeAdminPreviewTarget()
                writeTarget != null -> closeWriteTarget()
            }
        }
        Box(Modifier.fillMaxSize()) {
            val backgroundPainter = backgroundBitmap?.asImageBitmap()?.let(::BitmapPainter)
            if (backgroundPainter != null) {
                Image(backgroundPainter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().hazeSource(hazeState))
            } else {
                Image(painterResource(id = R.drawable.hlovet_city_lights), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().hazeSource(hazeState))
            }
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = .34f)))
            if (unsubscribeToken != null) {
                NativeUnsubscribeScreen(
                    token = unsubscribeToken.orEmpty(),
                    onBack = { unsubscribeToken = null },
                )
            } else if (loginVisible) {
                NativeLoginScreen(
                    initialStep = loginInitialStep,
                    oauthResultToken = oauthResultToken,
                    oauthErrorMessage = oauthErrorMessage,
                    passwordResetToken = passwordResetToken,
                    onOAuthResultConsumed = onOAuthResultConsumed,
                    onPasswordResetTokenConsumed = onPasswordResetTokenConsumed,
                    onAuthenticated = { authenticated ->
                        val previous = session
                        offlineLegacyOwner = previous
                            ?.takeIf { old -> old.userId == authenticated.userId && old.username != authenticated.username }
                            ?.username
                        offlineMigrationReadyOwner = null
                        if (previous?.username != authenticated.username) {
                            unregisterNativePush(previous)
                            NativeLocalNotifications.clear(context)
                        }
                        session = authenticated
                        if (!NativeSessionStore.save(context, authenticated)) {
                            showNativeNotice(nativePhrase(
                                "登录成功，但登录信息未能保存在此设备上。重启后需要重新登录。",
                                "Signed in, but this device could not save your session. Sign in again after restarting.",
                            ))
                        }
                        loginVisible = false
                        loginInitialStep = NativeLoginStep.Credentials
                        val pendingWrite = pendingWriteTarget
                        val pendingWriteBack = pendingWriteBackTarget
                        val pendingPreview = pendingAdminPreviewArticleId
                        val pendingPreviewBack = pendingAdminPreviewBackTarget
                        pendingWriteTarget = null
                        pendingWriteBackTarget = null
                        pendingAdminPreviewArticleId = null
                        pendingAdminPreviewBackTarget = null
                        if (pendingWrite != null) {
                            writeBackTarget = pendingWriteBack
                            writeTarget = pendingWrite
                        }
                        if (pendingPreview != null) {
                            adminPreviewBackTarget = pendingPreviewBack
                            adminPreviewArticleId = pendingPreview
                        }
                    },
                    onCancel = {
                        loginVisible = false
                        loginInitialStep = NativeLoginStep.Credentials
                        pendingWriteTarget = null
                        pendingWriteBackTarget = null
                        pendingAdminPreviewArticleId = null
                        pendingAdminPreviewBackTarget = null
                    },
                )
            } else if (!sessionRestoreComplete) {
                PreviewStatusScreen(nativePhrase("正在恢复登录状态…", "Restoring session…"), null)
            } else when (val state = loadState) {
                PreviewLoadState.Loading -> PreviewStatusScreen(nativePhrase("正在读取站内内容…", "Loading public content…"), null)
                is PreviewLoadState.Error -> PreviewStatusScreen(
                    title = nativePhrase("暂时无法读取内容", "Content is temporarily unavailable"),
                    detail = state.message,
                    onRetry = { reloadKey += 1 },
                )
                is PreviewLoadState.Ready -> if (searchVisible) {
                    NativeSearchScreen(
                        session = session,
                        initialQuery = searchInitialQuery,
                        onBack = { searchVisible = false; searchInitialQuery = "" },
                        onOpenArticle = { slug ->
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { NativeArticleApi.getArticle(session?.accessToken, slug) } }
                                    .onSuccess { article -> openDetailTarget(DetailTarget.Article(article)) }
                                    .onFailure { showNativeNotice(it.message ?: nativePhrase("文章暂时无法打开。", "The article cannot be opened right now.")) }
                            }
                        },
                        onOpenTopic = { slug ->
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { NativeDiscoveryApi.getTopic(session?.accessToken, slug) } }
                                    .onSuccess { topic -> openDetailTarget(DetailTarget.Topic(topic)) }
                                    .onFailure { showNativeNotice(it.message ?: nativePhrase("专题暂时无法打开。", "The topic cannot be opened right now.")) }
                            }
                        },
                        onOpenCollection = { id ->
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { NativeDiscoveryApi.getCollection(session?.accessToken, id) } }
                                    .onSuccess { collection -> openDetailTarget(DetailTarget.Collection(collection)) }
                                    .onFailure { showNativeNotice(it.message ?: nativePhrase("合集暂时无法打开。", "The collection cannot be opened right now.")) }
                            }
                        },
                        onOpenUser = { username ->
                            clearOpenTarget()
                            publicProfileTarget = username
                        },
                        onOpenAnnouncement = { id ->
                            searchVisible = false
                            announcementTargetId = id
                            accountTarget = AccountTarget.Announcements
                        },
                        onOpenGroup = { group ->
                            if (session == null) {
                                showNativeNotice(nativePhrase("登录后才能打开群聊。", "Sign in to open group chats."))
                            } else {
                                searchVisible = false
                                selectedTab = 3
                                conversationTarget = ConversationTarget(
                                    NativeConversationPreview(
                                        id = group.conversationId,
                                        title = group.name,
                                        initials = group.name.takeLast(2),
                                        message = group.announcement,
                                        time = "",
                                        unreadCount = 0,
                                        kind = "group",
                                    ),
                                )
                            }
                        },
                        onOpenEntry = { entry ->
                            openPortalUrl(entry.url)
                        },
                    )
                } else if (publicProfileTarget != null) {
                    NativePublicProfileScreen(
                        username = publicProfileTarget!!,
                        session = session,
                        onBack = ::closePublicProfile,
                        onLogin = { loginVisible = true },
                        onOpenArticle = { article ->
                            openDetailTarget(DetailTarget.Article(article), NativeDetailBackTarget.PublicProfile(publicProfileTarget!!))
                        },
                        onOpenCollection = { collection ->
                            openDetailTarget(DetailTarget.Collection(collection), NativeDetailBackTarget.PublicProfile(publicProfileTarget!!))
                        },
                        onOpenConversation = { conversation ->
                            publicProfileTarget = null
                            selectedTab = 3
                            conversationTarget = ConversationTarget(conversation)
                        },
                        onOpenExternalFeed = { profileUsername ->
                            val feedPath = "/api/distribution/feeds/authors/${Uri.encode(profileUsername)}.rss"
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(nativeMediaUrl(feedPath))))
                            }.onFailure { showNativeNotice(nativePhrase("设备上没有可以打开 RSS 订阅的应用。", "No app is available to open RSS feeds.")) }
                        },
                        onNotice = ::showNativeNotice,
                    )
                } else if (accountTarget != null) {
                    if (accountTarget == AccountTarget.ProfileEditor) {
                        session?.let { currentSession ->
                            NativeProfileEditorScreen(
                                session = currentSession,
                                onBack = { accountTarget = null },
                                onSessionUpdated = { updated ->
                                    val previous = currentSession
                                    offlineLegacyOwner = previous
                                        .takeIf { old -> old.userId == updated.userId && old.username != updated.username }
                                        ?.username
                                    offlineMigrationReadyOwner = null
                                    session = updated
                                    NativeSessionStore.save(context, updated)
                                },
                                onNotice = ::showNativeNotice,
                            )
                        }
                    } else if (accountTarget == AccountTarget.Dashboard) {
                        NativeDashboardScreen(
                            session = session,
                            onBack = { accountTarget = null },
                            onLogin = { loginVisible = true },
                            onWrite = {
                                if (session == null) {
                                    loginVisible = true
                                } else {
                                    accountTarget = null
                                    writeBackTarget = null
                                    writeTarget = NativeWriteTarget.New()
                                }
                            },
                            onOpenMyArticles = { accountTarget = AccountTarget.MyArticles },
                            onOpenMessages = {
                                accountTarget = null
                                inboxReloadKey += 1
                                selectedTab = 3
                            },
                            onOpenPeople = {
                                accountTarget = null
                                selectedTab = 3
                                peopleScreenVisible = true
                            },
                            onOpenGroups = {
                                accountTarget = null
                                selectedTab = 3
                                groupScreenVisible = true
                            },
                            onOpenSuggestions = { accountTarget = AccountTarget.Suggestions },
                            onOpenManagementReports = { accountTarget = AccountTarget.MyReports },
                            onOpenAdminHome = { accountTarget = AccountTarget.AdminHome },
                            onOpenAdminSystem = {
                                adminSystemParentIsAdmin = false
                                adminSystemChildFromSystem = false
                                accountTarget = AccountTarget.SystemStatus
                            },
                        )
                    } else if (accountTarget == AccountTarget.Navigation) {
                        NativePortalScreen(
                            mode = NativePortalMode.Navigation,
                            session = session,
                            onBack = { accountTarget = null },
                            onLogin = { loginVisible = true },
                        onOpenEntry = { entry -> openPortalUrl(entry.url) },
                        )
                    } else if (accountTarget == AccountTarget.Tools) {
                        NativePortalScreen(
                            mode = NativePortalMode.Tools,
                            session = session,
                            onBack = { accountTarget = null },
                            onLogin = { loginVisible = true },
                        onOpenEntry = { entry -> openPortalUrl(entry.url) },
                        )
                    } else if (accountTarget == AccountTarget.Install) {
                        NativeInstallScreen(
                            onBack = { accountTarget = null },
                        )
                    } else if (accountTarget == AccountTarget.Announcements) {
                        NativeAnnouncementsScreen(
                            session = session,
                            onBack = { accountTarget = null },
                            initialAnnouncementId = announcementTargetId,
                        )
                    } else if (accountTarget == AccountTarget.Feedback) {
                        NativeFeedbackScreen(
                            session = session,
                            onBack = { accountTarget = null },
                            onLogin = { loginVisible = true },
                        )
                    } else if (accountTarget == AccountTarget.Suggestions) {
                        NativeSuggestionsScreen(
                            session = session,
                            onBack = { accountTarget = null },
                            onLogin = { loginVisible = true },
                        )
                    } else if (accountTarget == AccountTarget.AnonymousTopics) {
                        NativeAnonymousTopicsScreen(
                            session = session,
                            onBack = { accountTarget = null },
                        )
                    } else if (accountTarget == AccountTarget.AnonymousTopicManagement) {
                        NativeAnonymousTopicsScreen(
                            session = session,
                            management = true,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.RecommendationFeedback) {
                        NativeRecommendationFeedbackScreen(
                            session = session,
                            onBack = { accountTarget = null },
                        )
                    } else if (accountTarget == AccountTarget.TemplateManagement) {
                        NativeTemplateManagementScreen(
                            session = session,
                            onBack = { accountTarget = null },
                            onUseTemplate = { template ->
                                accountTarget = null
                                writeBackTarget = AccountTarget.TemplateManagement
                                writeTarget = NativeWriteTarget.New(template)
                            },
                        )
                    } else if (accountTarget == AccountTarget.Offline) {
                        NativeOfflineScreen(
                            session = session,
                            context = context,
                            onBack = { accountTarget = null },
                            onOpenArticle = { article -> openDetailTarget(DetailTarget.Article(article), NativeDetailBackTarget.Account(AccountTarget.Offline)) },
                            onOpenTopic = { topic -> openDetailTarget(DetailTarget.Topic(topic), NativeDetailBackTarget.Account(AccountTarget.Offline)) },
                            onOpenCollection = { collection -> openDetailTarget(DetailTarget.Collection(collection), NativeDetailBackTarget.Account(AccountTarget.Offline)) },
                        )
                    } else if (accountTarget == AccountTarget.Privacy) {
                        NativePrivacyScreen(
                            session = session,
                            onBack = { accountTarget = null },
                        )
                    } else if (accountTarget == AccountTarget.Sessions) {
                        NativeSessionsScreen(
                            session = session,
                            onBack = { accountTarget = null },
                            onCurrentSessionRevoked = {
                                val current = session
                                unregisterNativePush(current)
                                session = null
                                profileSummary = null
                                reputationSummary = null
                                NativeSessionStore.clear(context)
                                NativeLocalNotifications.clear(context)
                                accountTarget = null
                                pendingMessagesRoute = null
                                conversationTarget = null
                                if (current?.refreshToken?.isNotBlank() == true) {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { NativeAuthApi.logout(current.refreshToken) }
                                    }
                                }
                            },
                            onNotice = ::showNativeNotice,
                        )
                    } else if (accountTarget == AccountTarget.Integrations) {
                        NativeIntegrationsScreen(
                            session = session,
                            oauthResultToken = oauthResultToken,
                            oauthErrorMessage = oauthErrorMessage,
                            onOAuthResultConsumed = onOAuthResultConsumed,
                            onBack = { accountTarget = null },
                        )
                    } else if (accountTarget == AccountTarget.MyReports) {
                        NativeReportsScreen(
                            session = session,
                            onBack = { accountTarget = null },
                            onOpenArticle = { article -> openDetailTarget(DetailTarget.Article(article), NativeDetailBackTarget.Account(AccountTarget.MyReports)) },
                        )
                    } else if (accountTarget == AccountTarget.AiAssistant) {
                        NativeAiScreen(
                            session = session,
                            onBack = { accountTarget = null },
                            onOpenSource = { source ->
                                val token = session?.accessToken
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) { NativeArticleApi.getArticle(token, source.slug) }
                                    }.onSuccess { article ->
                                        openDetailTarget(DetailTarget.Article(article), NativeDetailBackTarget.Account(AccountTarget.AiAssistant))
                                    }.onFailure { exception ->
                                        showNativeNotice(exception.message ?: nativePhrase("文章暂时无法打开。", "The article cannot be opened right now."))
                                    }
                                }
                            },
                        )
                    } else if (accountTarget == AccountTarget.AdminHome) {
                        NativeAdminHomeScreen(
                            session = session,
                            onBack = { accountTarget = null },
                            onLogin = { loginVisible = true },
                            onOpenEntry = { path ->
                                val target = nativeAdminAccountTarget(path)
                                if (target != null) {
                                    if (target == AccountTarget.SystemStatus) {
                                        adminSystemParentIsAdmin = true
                                        adminSystemChildFromSystem = false
                                    } else if (target == AccountTarget.AdminBackups || target == AccountTarget.AdminOperations || target == AccountTarget.AdminStorage) {
                                        adminSystemChildFromSystem = false
                                    }
                                    accountTarget = target
                                } else {
                                    showNativeNotice(nativePhrase("该管理模块暂未移植到 Android 原生端。", "This admin module is not available in the native Android app yet."))
                                }
                            },
                        )
                    } else if (accountTarget == AccountTarget.AdminTopics) {
                        NativeTopicManagementScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                            onOpenArticle = { article -> openDetailTarget(DetailTarget.Article(article), NativeDetailBackTarget.Account(AccountTarget.AdminTopics)) },
                        )
                    } else if (accountTarget == AccountTarget.AdminArticles) {
                        NativeAdminArticlesScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                            onOpenPreview = { articleId -> openAdminPreviewRoute(articleId, AccountTarget.AdminArticles) },
                        )
                    } else if (accountTarget == AccountTarget.AdminAnalytics) {
                        NativeAdminAnalyticsScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                            onOpenArticle = { slug ->
                                val accessToken = session?.accessToken
                                if (accessToken.isNullOrBlank()) {
                                    loginVisible = true
                                } else {
                                    scope.launch {
                                        runCatching { withContext(Dispatchers.IO) { NativeArticleApi.getArticle(accessToken, slug) } }
                                            .onSuccess { article -> openDetailTarget(DetailTarget.Article(article), NativeDetailBackTarget.Account(AccountTarget.AdminAnalytics)) }
                                            .onFailure { exception -> showNativeNotice(exception.message ?: nativePhrase("文章暂时无法打开。", "The article cannot be opened right now.")) }
                                    }
                                }
                            },
                        )
                    } else if (accountTarget == AccountTarget.AdminAudit) {
                        NativeAdminAuditScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.SystemStatus) {
                        NativeSystemStatusScreen(
                            session = session,
                            onBack = { accountTarget = if (adminSystemParentIsAdmin) AccountTarget.AdminHome else null },
                            onOpenOperations = { adminSystemChildFromSystem = true; accountTarget = AccountTarget.AdminOperations },
                            onOpenStorage = { adminSystemChildFromSystem = true; accountTarget = AccountTarget.AdminStorage },
                            onOpenBackups = { adminSystemChildFromSystem = true; accountTarget = AccountTarget.AdminBackups },
                        )
                    } else if (accountTarget == AccountTarget.AdminBackups) {
                        NativeAdminBackupScreen(
                            session = session,
                            onBack = { accountTarget = if (adminSystemChildFromSystem) AccountTarget.SystemStatus else AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminOperations) {
                        NativeAdminOperationsScreen(
                            session = session,
                            onBack = { accountTarget = if (adminSystemChildFromSystem) AccountTarget.SystemStatus else AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminStorage) {
                        NativeAdminStorageScreen(
                            session = session,
                            onBack = { accountTarget = if (adminSystemChildFromSystem) AccountTarget.SystemStatus else AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminUsers) {
                        NativeAdminUsersScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminReports) {
                        NativeAdminReportsScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminAnnouncements) {
                        NativeAdminAnnouncementsScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminContent) {
                        NativeAdminPortalContentScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminViolations) {
                        NativeAdminViolationsScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                            onOpenArticle = { articleId -> openAdminPreviewRoute(articleId, AccountTarget.AdminViolations) },
                        )
                    } else if (accountTarget == AccountTarget.AdminFeedback) {
                        NativeAdminFeedbackScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminResources) {
                        NativeAdminResourcesScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminGroups) {
                        NativeAdminGroupsScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminDeletedUsers) {
                        NativeAdminDeletedUsersScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminSecurity) {
                        NativeAdminSecurityScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminCache) {
                        NativeAdminCacheScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminAndroid) {
                        NativeAdminAndroidScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminIntegrations) {
                        NativeAdminIntegrationsScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminSettings) {
                        NativeAdminSettingsScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.AdminAi) {
                        NativeAdminAiScreen(
                            session = session,
                            onBack = { accountTarget = AccountTarget.AdminHome },
                        )
                    } else if (accountTarget == AccountTarget.ResourceCenter) {
                        ResourceCenterScreen(
                            session = session,
                            onBack = { accountTarget = null },
                            onLogin = { loginVisible = true },
                            onOpenArticle = { article -> openDetailTarget(DetailTarget.Article(article), NativeDetailBackTarget.Account(AccountTarget.ResourceCenter)) },
                            onOpenPoints = { if (session == null) loginVisible = true else accountTarget = AccountTarget.Points },
                            onOpenOrders = { if (session == null) loginVisible = true else accountTarget = AccountTarget.ResourceOrders },
                            onOpenEarnings = { if (session == null) loginVisible = true else accountTarget = AccountTarget.ResourceEarnings },
                        )
                    } else if (accountTarget == AccountTarget.Points) {
                        PointsScreen(session = session, onBack = { accountTarget = null })
                    } else if (accountTarget == AccountTarget.ResourceOrders) {
                        ResourceOrdersScreen(
                            session = session,
                            onBack = { accountTarget = null },
                        )
                    } else if (accountTarget == AccountTarget.ResourceEarnings) {
                        ResourceEarningsScreen(
                            session = session,
                            onBack = { accountTarget = null },
                            onOpenArticle = { article -> openDetailTarget(DetailTarget.Article(article), NativeDetailBackTarget.Account(AccountTarget.ResourceEarnings)) },
                        )
                    } else if (accountTarget == AccountTarget.SubscriptionFeed) {
                        SubscriptionFeedScreen(
                            session = session,
                            initialSort = subscriptionInitialSort,
                            onBack = {
                                subscriptionInitialSort = "latest"
                                accountTarget = null
                            },
                            onOpenArticle = { article -> openDetailTarget(DetailTarget.Article(article), NativeDetailBackTarget.Account(AccountTarget.SubscriptionFeed)) },
                        )
                    } else if (accountTarget == AccountTarget.MyCollections) {
                        MyCollectionsScreen(
                            session = session,
                            initialView = collectionInitialView,
                            onBack = {
                                collectionInitialView = "mine"
                                accountTarget = null
                            },
                            onOpenCollection = { collection ->
                                openDetailTarget(DetailTarget.Collection(collection), NativeDetailBackTarget.Account(AccountTarget.MyCollections))
                            },
                        )
                    } else if (accountTarget == AccountTarget.TopicManagement) {
                        NativeTopicManagementScreen(
                            session = session,
                            onBack = { accountTarget = null },
                            onOpenArticle = { article ->
                                openDetailTarget(DetailTarget.Article(article), NativeDetailBackTarget.Account(AccountTarget.TopicManagement))
                            },
                        )
                    } else if (accountTarget == AccountTarget.MyArticles) {
                        MyArticlesManagementScreen(
                            session = session,
                            initialStatus = accountInitialStatus,
                            initialSearchQuery = accountInitialQuery,
                            onBack = {
                                accountInitialStatus = null
                                accountInitialQuery = ""
                                accountTarget = null
                            },
                            onArticleClick = { openDetailTarget(DetailTarget.Article(it), NativeDetailBackTarget.Account(AccountTarget.MyArticles)) },
                            onEditArticle = { id ->
                                openWriteRoute(id, AccountTarget.MyArticles)
                            },
                        )
                    } else {
                        AccountScreen(
                            target = accountTarget!!,
                            session = session,
                            onBack = { accountTarget = null },
                            onArticleClick = { openDetailTarget(DetailTarget.Article(it), NativeDetailBackTarget.Account(accountTarget!!)) },
                        )
                    }
                } else if (groupScreenVisible) {
                    NativeGroupScreen(
                        session = session,
                        initialGroupId = groupInitialId,
                        initialView = groupInitialView,
                        initialReportId = groupInitialReportId,
                        onBack = {
                            groupScreenVisible = false
                            groupInitialId = null
                            groupInitialView = "mine"
                            groupInitialReportId = null
                        },
                        onOpenConversation = { conversation ->
                            groupScreenVisible = false
                            selectedTab = 3
                            conversationTarget = ConversationTarget(conversation)
                        },
                    )
                } else if (peopleScreenVisible) {
                    NativePeopleScreen(
                        session = session,
                        onBack = { peopleScreenVisible = false },
                        onOpenConversation = { conversation ->
                            peopleScreenVisible = false
                            selectedTab = 3
                            conversationTarget = ConversationTarget(conversation)
                        },
                        onNotice = ::showNativeNotice,
                    )
                } else if (conversationTarget != null) {
                    ConversationScreen(
                        target = conversationTarget!!,
                        session = session,
                        callLauncher = nativeCallLauncher,
                        onBack = { conversationTarget = null },
                        onReauthenticate = { complete -> requestSocketReauthentication { complete(it?.accessToken) } },
                    )
                } else if (adminPreviewArticleId != null && session != null) {
                    NativeAdminArticlePreviewScreen(
                        session = session!!,
                        articleId = adminPreviewArticleId!!,
                        onBack = ::closeAdminPreviewTarget,
                    )
                } else if (writeTarget != null) {
                    val target = writeTarget!!
                    NativeWriteEditorScreen(
                        session = session,
                        articleId = (target as? NativeWriteTarget.Existing)?.id,
                        template = (target as? NativeWriteTarget.New)?.template,
                        onBack = ::closeWriteTarget,
                        onSaved = { writeReloadKey += 1 },
                        onPublished = {
                            closeWriteTarget()
                            writeReloadKey += 1
                        },
                    )
                } else if (detailTarget != null) {
                    when (val target = detailTarget!!) {
                        is DetailTarget.Article -> ArticleDetailScreen(
                            initialArticle = target.article,
                            focusCommentId = target.focusCommentId,
                            session = session,
                            onLogin = { loginVisible = true },
                            onBack = ::closeDetailTarget,
                            onOpenUser = { username ->
                                openProfileFromDetail(username, target, detailBackTarget)
                            },
                        )
                        is DetailTarget.Topic,
                        is DetailTarget.Collection -> DiscoveryDetailScreen(
                            target = target,
                            session = session,
                            onLogin = { loginVisible = true },
                            onBack = ::closeDetailTarget,
                            onArticleClick = { openDetailTarget(DetailTarget.Article(it), NativeDetailBackTarget.Detail(target)) },
                            onContentChanged = { reloadKey += 1 },
                        )
                    }
                } else Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier.hazeEffect(state = hazeState, style = navigationGlassStyle()),
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp
                    ) {
                        val items = listOf(
                            Triple(nativePhrase("首页", "Home"), Icons.Filled.Home, 0),
                            Triple(nativePhrase("发现", "Discover"), Icons.Filled.Explore, 1),
                            Triple(nativePhrase("写作", "Write"), Icons.Filled.AddCircleOutline, 2),
                            Triple(nativePhrase("消息", "Messages"), Icons.Filled.ChatBubbleOutline, 3),
                            Triple(nativePhrase("我的", "Profile"), Icons.Filled.PersonOutline, 4)
                        )
                        items.forEach { (label, icon, index) ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("native-navigation-$index"),
                                selected = selectedTab == index,
                                onClick = {
                                    if (index == 3) inboxReloadKey += 1
                                    selectedTab = index
                                },
                                icon = {
                                    Box(Modifier.size(30.dp)) {
                                        Icon(
                                            icon,
                                            contentDescription = label,
                                            modifier = Modifier.align(Alignment.Center).size(22.dp),
                                        )
                                        val unread = if (index == 3) inboxSummary?.totalUnread ?: 0 else 0
                                        if (unread > 0) {
                                            Surface(
                                                modifier = Modifier.align(Alignment.TopEnd),
                                                shape = CircleShape,
                                                color = Coral,
                                            ) {
                                                Text(
                                                    text = if (unread > 99) "99+" else unread.toString(),
                                                    color = HlovetUi.onAccent,
                                                    fontSize = 8.sp,
                                                    lineHeight = 9.sp,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Accent,
                                    selectedTextColor = Accent,
                                    indicatorColor = HlovetUi.accentSoft,
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted
                                )
                            )
                        }
                    }
                }
            ) { padding ->
                when (selectedTab) {
                    0 -> HomeScreen(
                        scaffoldPadding = padding,
                        session = session,
                        articles = state.data.articles,
                        onArticleClick = { openDetailTarget(DetailTarget.Article(it)) },
                        onOpenAnnouncements = { accountTarget = AccountTarget.Announcements },
                        onOpenAnonymousTopics = { accountTarget = AccountTarget.AnonymousTopics },
                        onOpenSuggestions = { accountTarget = AccountTarget.Suggestions },
                        onOpenSearch = { searchInitialQuery = ""; searchVisible = true },
                        onOpenWrite = {
                            if (session == null) {
                                loginVisible = true
                            } else {
                                selectedTab = 2
                                writeBackTarget = null
                                writeTarget = NativeWriteTarget.New()
                            }
                        },
                        onOpenSubscriptions = {
                            if (session == null) loginVisible = true else accountTarget = AccountTarget.SubscriptionFeed
                        },
                        onLogin = { loginVisible = true },
                    )
                    1 -> DiscoverScreen(
                        padding,
                        session = session,
                        initialMode = discoveryInitialMode,
                        initialQuery = discoveryInitialQuery,
                        articles = state.data.articles,
                        topics = state.data.topics,
                        collections = state.data.collections,
                        onLogin = { loginVisible = true },
                        onResourceCenterClick = { accountTarget = AccountTarget.ResourceCenter },
                        onTopicClick = { topic -> openDetailTarget(DetailTarget.Topic(topic)) },
                        onCollectionClick = { collection -> openDetailTarget(DetailTarget.Collection(collection)) },
                        onArticleClick = { openDetailTarget(DetailTarget.Article(it)) },
                        onOpenUser = { username -> clearOpenTarget(); publicProfileTarget = username },
                        onOpenGroup = { group ->
                            if (session == null) {
                                loginVisible = true
                            } else {
                                selectedTab = 3
                                conversationTarget = ConversationTarget(
                                    NativeConversationPreview(
                                        id = group.conversationId,
                                        title = group.name,
                                        initials = group.name.takeLast(2),
                                        message = group.announcement,
                                        time = "",
                                        unreadCount = 0,
                                        kind = "group",
                                    ),
                                )
                            }
                        },
                    )
                     2 -> NativeWriteHomeScreen(
                         scaffoldPadding = padding,
                         session = session,
                         refreshToken = writeReloadKey,
                         onLogin = { loginVisible = true },
                          onNewArticle = {
                              if (session == null) loginVisible = true else {
                                  writeBackTarget = null
                                  writeTarget = NativeWriteTarget.New()
                              }
                          },
                         onOpenDraft = { id ->
                             if (session == null) loginVisible = true else {
                                 writeBackTarget = null
                                 writeTarget = NativeWriteTarget.Existing(id)
                             }
                         },
                     )
                    3 -> MessagesScreen(
                        padding = padding,
                        session = session,
                        refreshKey = inboxReloadKey,
                        onLogin = { loginVisible = true },
                        onNotice = ::showNativeNotice,
                        onOpenPeople = {
                            if (session == null) loginVisible = true else peopleScreenVisible = true
                        },
                         onOpenGroups = {
                             if (session == null) {
                                 loginVisible = true
                             } else {
                                 groupInitialId = null
                                 groupInitialView = "mine"
                                 groupInitialReportId = null
                                 groupScreenVisible = true
                             }
                         },
                        onOpenConversation = { conversationTarget = ConversationTarget(it) },
                        onOpenNotification = { notification, preview -> scope.launch { openNotification(notification, preview) } },
                        onInboxSummaryChanged = { inboxSummary = it },
                        onEnableNativeNotifications = ::requestNativeNotificationPermission,
                    )
                    else -> ProfileScreen(
                        padding = padding,
                        session = session,
                        profile = profileSummary,
                        profileLoading = profileLoading,
                        profileError = profileError,
                        articleCenterSummary = articleCenterSummary,
                        reputation = reputationSummary,
                        reputationLoading = reputationLoading,
                        reputationError = reputationError,
                        onLogin = { loginVisible = true },
                        onEditProfile = {
                            if (session == null) loginVisible = true else accountTarget = AccountTarget.ProfileEditor
                        },
                        onOpenMyArticles = {
                            if (session == null) loginVisible = true else accountTarget = AccountTarget.MyArticles
                        },
                        onOpenPoints = {
                            if (session == null) loginVisible = true else accountTarget = AccountTarget.Points
                        },
                        onOpenAccountTarget = { target ->
                            if (target == AccountTarget.Navigation || target == AccountTarget.Tools || target == AccountTarget.ResourceCenter || target == AccountTarget.Announcements || target == AccountTarget.Suggestions || target == AccountTarget.AnonymousTopics || target == AccountTarget.Offline) {
                                accountTarget = target
                            } else if (session == null) {
                                loginVisible = true
                            } else {
                                accountTarget = target
                            }
                        },
                        onOpenPrivacy = {
                            if (session == null) loginVisible = true else accountTarget = AccountTarget.Privacy
                        },
                        onLogout = {
                            val current = session
                            unregisterNativePush(current)
                            session = null
                            profileSummary = null
                            NativeSessionStore.clear(context)
                            NativeLocalNotifications.clear(context)
                            pendingMessagesRoute = null
                            conversationTarget = null
                            pendingWriteTarget = null
                             pendingWriteBackTarget = null
                             writeTarget = null
                             writeBackTarget = null
                            adminPreviewArticleId = null
                            pendingAdminPreviewArticleId = null
                             pendingAdminPreviewBackTarget = null
                             adminPreviewBackTarget = null
                             detailTarget = null
                             detailBackTarget = null
                             publicProfileTarget = null
                             profileBackTarget = null
                             accountTarget = null
                            if (current?.refreshToken?.isNotBlank() == true) {
                                // Revocation is best-effort; local state is cleared immediately.
                                scope.launch(Dispatchers.IO) {
                                    runCatching { NativeAuthApi.logout(current.refreshToken) }
                                }
                            }
                        },
                    )
                }
                }
            }
            if (!loginVisible && onboardingVisible) {
                onboardingState?.let { state ->
                    NativeOnboardingDialog(
                        state = state,
                        saving = onboardingSaving,
                        error = onboardingError,
                        onComplete = ::submitOnboarding,
                        onSkip = { submitOnboarding(emptyList(), emptyList()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeLoginScreen(
    initialStep: NativeLoginStep = NativeLoginStep.Credentials,
    oauthResultToken: String?,
    oauthErrorMessage: String?,
    passwordResetToken: String?,
    onOAuthResultConsumed: () -> Unit,
    onPasswordResetTokenConsumed: () -> Unit,
    onAuthenticated: (NativeSession) -> Unit,
    onCancel: () -> Unit,
) {
    var step by remember(initialStep) { mutableStateOf(initialStep) }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var googleAvailable by remember { mutableStateOf<Boolean?>(null) }
    var googleProviderError by remember { mutableStateOf<String?>(null) }
    var googleProviderRetryKey by remember { mutableIntStateOf(0) }
    var registrationPolicy by remember { mutableStateOf<NativeRegistrationPolicy?>(null) }
    var registrationPolicyError by remember { mutableStateOf<String?>(null) }
    var registerUsername by remember { mutableStateOf("") }
    var registerNickname by remember { mutableStateOf("") }
    var registerEmail by remember { mutableStateOf("") }
    var registerCode by remember { mutableStateOf("") }
    var registerPassword by remember { mutableStateOf("") }
    var registerConfirmation by remember { mutableStateOf("") }
    var registerPasswordVisible by remember { mutableStateOf(false) }
    var registerConfirmationVisible by remember { mutableStateOf(false) }
    var registerTurnstileToken by remember { mutableStateOf("") }
    var registerTurnstileResetKey by remember { mutableIntStateOf(0) }
    var registrationCodeSentTo by remember { mutableStateOf("") }
    var registrationRetryAfter by remember { mutableIntStateOf(0) }
    var recoveryEmail by remember { mutableStateOf("") }
    var recoveryNewPassword by remember { mutableStateOf("") }
    var recoveryConfirmation by remember { mutableStateOf("") }
    var recoveryPasswordVisible by remember { mutableStateOf(false) }
    var recoveryConfirmationVisible by remember { mutableStateOf(false) }
    var recoveryTurnstileToken by remember { mutableStateOf("") }
    var recoveryTurnstileResetKey by remember { mutableIntStateOf(0) }
    var deviceRetryAfter by remember { mutableIntStateOf(0) }
    var loginTurnstileRequired by remember { mutableStateOf(false) }
    var loginTurnstileToken by remember { mutableStateOf("") }
    var loginTurnstileResetKey by remember { mutableIntStateOf(0) }
    var totpRecoveryMode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val credentialManager = remember { CredentialManager.create(context) }

    LaunchedEffect(googleProviderRetryKey) {
        googleAvailable = null
        googleProviderError = null
        runCatching { withContext(Dispatchers.IO) { NativeAuthApi.externalAuthProviders() } }
            .onSuccess { enabled ->
                googleAvailable = enabled
                googleProviderError = null
            }
            .onFailure { exception ->
                googleAvailable = null
                googleProviderError = exception.message
                    ?: nativePhrase("暂时无法读取 Google 登录配置。", "Google sign-in settings are temporarily unavailable.")
            }
    }

    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { NativeAuthApi.registrationPolicy() } }
            .onSuccess {
                registrationPolicy = it
                registrationPolicyError = null
            }
            .onFailure {
                registrationPolicyError = it.message ?: nativePhrase("暂时无法读取注册配置。", "Registration settings are temporarily unavailable.")
            }
    }

    LaunchedEffect(registrationRetryAfter) {
        if (registrationRetryAfter > 0) {
            delay(1000)
            registrationRetryAfter = (registrationRetryAfter - 1).coerceAtLeast(0)
        }
    }

    LaunchedEffect(deviceRetryAfter) {
        if (deviceRetryAfter > 0) {
            delay(1000)
            deviceRetryAfter = (deviceRetryAfter - 1).coerceAtLeast(0)
        }
    }

    fun openRegistration() {
        step = NativeLoginStep.Register
        error = null
        registerCode = ""
        registerTurnstileToken = ""
    }

    fun openPasswordRecovery() {
        step = NativeLoginStep.RecoveryRequest
        error = null
        recoveryEmail = ""
        recoveryTurnstileToken = ""
    }

    fun leaveCurrentStep() {
        when (step) {
            NativeLoginStep.Credentials -> onCancel()
            is NativeLoginStep.RecoverySent,
            NativeLoginStep.RecoveryRequest,
            is NativeLoginStep.RecoveryReset,
            NativeLoginStep.RecoveryComplete,
            NativeLoginStep.Register,
            is NativeLoginStep.Device,
            is NativeLoginStep.Totp,
            is NativeLoginStep.GoogleLink,
            is NativeLoginStep.GoogleLinkEmail,
            is NativeLoginStep.GoogleLinkTotp,
            is NativeLoginStep.GoogleLinkPassword -> {
                step = NativeLoginStep.Credentials
                error = null
            }
        }
    }

    fun submit(action: suspend () -> NativeAuthResult) {
        if (loading) return
        loading = true
        error = null
        notice = null
        scope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { action() }) {
                    is NativeAuthResult.Authenticated -> {
                        HlovetUi.applyAppearance(result.session.appearance)
                        loginTurnstileRequired = false
                        loginTurnstileToken = ""
                        onAuthenticated(result.session)
                    }
                    is NativeAuthResult.DeviceVerification -> {
                        step = NativeLoginStep.Device(result.challengeToken, result.emailHint, result.retryAfterSeconds)
                        deviceRetryAfter = result.retryAfterSeconds
                        code = ""
                    }
                    is NativeAuthResult.TotpVerification -> {
                        step = NativeLoginStep.Totp(result.challengeToken)
                        totpRecoveryMode = false
                        code = ""
                    }
                    is NativeAuthResult.GoogleLinkRequired -> {
                        step = NativeLoginStep.GoogleLink(result.pendingToken, result.email, result.methods)
                    }
                }
            } catch (exception: Exception) {
                if (step == NativeLoginStep.Credentials && exception is NativeAuthHttpException && exception.statusCode == 428) {
                    loginTurnstileRequired = true
                    loginTurnstileToken = ""
                    loginTurnstileResetKey += 1
                }
                if (step == NativeLoginStep.Credentials && loginTurnstileToken.isNotBlank()) {
                    loginTurnstileToken = ""
                    loginTurnstileResetKey += 1
                }
                error = exception.message ?: nativePhrase("认证失败，请稍后重试。", "Authentication failed. Try again later.")
            } finally {
                loading = false
            }
        }
    }

    fun requestRegistrationCode() {
        if (loading) return
        val policy = registrationPolicy
        val email = registerEmail.trim().lowercase()
        if (!Regex("^\\S+@\\S+\\.\\S+$").matches(email)) {
            error = nativePhrase("请输入有效的邮箱地址。", "Enter a valid email address.")
            return
        }
        if (policy?.turnstileEnabled == true && registerTurnstileToken.isBlank()) {
            error = nativePhrase("请先完成人机验证。", "Complete the human verification first.")
            return
        }
        loading = true
        error = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    NativeAuthApi.requestRegistrationCode(
                        email = email,
                        turnstileToken = registerTurnstileToken.takeIf { it.isNotBlank() },
                    )
                }
                registrationCodeSentTo = email
                registrationRetryAfter = result.retryAfterSeconds.coerceAtLeast(1)
                if (policy?.turnstileEnabled == true) {
                    registerTurnstileToken = ""
                    registerTurnstileResetKey += 1
                }
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("验证码发送失败，请稍后重试。", "The verification code could not be sent. Try again later.")
            } finally {
                loading = false
            }
        }
    }

    fun submitRegistration() {
        val policy = registrationPolicy
        val email = registerEmail.trim().lowercase()
        when {
            policy?.registrationOpen == false -> error = nativePhrase("当前暂未开放注册。", "Registration is currently closed.")
            registerUsername.trim().length < 3 -> error = nativePhrase("用户名至少需要 3 个字符。", "The username must contain at least 3 characters.")
            registerNickname.trim().isBlank() -> error = nativePhrase("请输入昵称。", "Enter a display name.")
            !Regex("^\\S+@\\S+\\.\\S+$").matches(email) -> error = nativePhrase("请输入有效的邮箱地址。", "Enter a valid email address.")
            policy?.emailVerificationEnabled == true && registerCode.filter(Char::isDigit).length != 6 -> error = nativePhrase("请输入 6 位邮箱验证码。", "Enter the 6-digit email code.")
            policy?.turnstileEnabled == true && policy?.emailVerificationEnabled != true && registerTurnstileToken.isBlank() -> error = nativePhrase("请先完成人机验证。", "Complete the human verification first.")
            registerPassword.length < 8 -> error = nativePhrase("密码至少需要 8 个字符。", "The password must contain at least 8 characters.")
            registerPassword != registerConfirmation -> error = nativePhrase("两次输入的密码不一致。", "The passwords do not match.")
            else -> submit {
                NativeAuthApi.register(
                    username = registerUsername.trim(),
                    nickname = registerNickname.trim(),
                    email = email,
                    password = registerPassword,
                    verificationCode = registerCode.takeIf { it.isNotBlank() },
                    turnstileToken = registerTurnstileToken.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    fun requestPasswordRecovery() {
        if (loading) return
        val policy = registrationPolicy
        val email = recoveryEmail.trim().lowercase()
        when {
            policy == null -> error = nativePhrase("正在读取安全配置，请稍后重试。", "Security settings are still loading. Try again shortly.")
            policy?.passwordRecoveryEnabled == false -> error = nativePhrase("当前暂未启用密码找回。", "Password recovery is currently disabled.")
            !Regex("^\\S+@\\S+\\.\\S+$").matches(email) -> error = nativePhrase("请输入有效的邮箱地址。", "Enter a valid email address.")
            policy?.recoveryTurnstileEnabled == true && recoveryTurnstileToken.isBlank() -> error = nativePhrase("请先完成人机验证。", "Complete the human verification first.")
            else -> {
                loading = true
                error = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            NativeAuthApi.requestPasswordRecovery(
                                email = email,
                                turnstileToken = recoveryTurnstileToken.takeIf { it.isNotBlank() },
                            )
                        }
                        recoveryEmail = email
                        step = NativeLoginStep.RecoverySent(email)
                    } catch (exception: Exception) {
                        error = exception.message ?: nativePhrase("找回邮件发送失败，请稍后重试。", "The recovery email could not be sent. Try again later.")
                    } finally {
                        recoveryTurnstileToken = ""
                        recoveryTurnstileResetKey += 1
                        loading = false
                    }
                }
            }
        }
    }

    fun submitPasswordRecoveryReset(current: NativeLoginStep.RecoveryReset) {
        if (loading) return
        val policy = registrationPolicy
        when {
            policy == null -> error = nativePhrase("正在读取安全配置，请稍后重试。", "Security settings are still loading. Try again shortly.")
            policy?.passwordRecoveryEnabled == false -> error = nativePhrase("当前暂未启用密码找回。", "Password recovery is currently disabled.")
            recoveryNewPassword.length < 8 -> error = nativePhrase("密码至少需要 8 个字符。", "The password must contain at least 8 characters.")
            recoveryNewPassword != recoveryConfirmation -> error = nativePhrase("两次输入的密码不一致。", "The passwords do not match.")
            policy?.recoveryTurnstileEnabled == true && recoveryTurnstileToken.isBlank() -> error = nativePhrase("请先完成人机验证。", "Complete the human verification first.")
            else -> {
                loading = true
                error = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            NativeAuthApi.resetPassword(
                                token = current.token,
                                newPassword = recoveryNewPassword,
                                turnstileToken = recoveryTurnstileToken.takeIf { it.isNotBlank() },
                            )
                        }
                        recoveryNewPassword = ""
                        recoveryConfirmation = ""
                        step = NativeLoginStep.RecoveryComplete
                    } catch (exception: Exception) {
                        error = exception.message ?: nativePhrase("密码重置失败，请检查链接是否有效。", "The password could not be reset. Check that the link is still valid.")
                    } finally {
                        recoveryTurnstileToken = ""
                        recoveryTurnstileResetKey += 1
                        loading = false
                    }
                }
            }
        }
    }

    fun resendDeviceCode(current: NativeLoginStep.Device) {
        if (loading || deviceRetryAfter > 0) return
        loading = true
        error = null
        notice = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    NativeAuthApi.resendDeviceVerification(current.challengeToken)
                }
                step = NativeLoginStep.Device(
                    challengeToken = result.challengeToken,
                    emailHint = result.emailHint.ifBlank { current.emailHint },
                    retryAfterSeconds = result.retryAfterSeconds,
                )
                deviceRetryAfter = result.retryAfterSeconds
                code = ""
                notice = nativePhrase("验证码已重新发送。", "Verification code resent.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("验证码重发失败，请稍后重试。", "The verification code could not be resent. Try again later.")
            } finally {
                loading = false
            }
        }
    }

    fun submitPasswordLogin() {
        if (loginTurnstileRequired && loginTurnstileToken.isBlank()) {
            error = nativePhrase("请先完成人机验证。", "Complete the human verification first.")
            return
        }
        submit {
            NativeAuthApi.login(
                account = account.trim(),
                password = password,
                turnstileToken = loginTurnstileToken.takeIf { it.isNotBlank() },
            )
        }
    }

    LaunchedEffect(oauthResultToken, oauthErrorMessage) {
        if (oauthResultToken == null && oauthErrorMessage.isNullOrBlank()) return@LaunchedEffect
        val token = oauthResultToken.orEmpty()
        onOAuthResultConsumed()
        if (!oauthErrorMessage.isNullOrBlank()) {
            error = oauthErrorMessage
            return@LaunchedEffect
        }
        if (token.isBlank()) {
            error = nativePhrase("Google 登录回调无效，请重新发起登录。", "The Google sign-in callback is invalid. Start sign-in again.")
            return@LaunchedEffect
        }
        submit { NativeAuthApi.consumeOAuthResult(token) }
    }

    LaunchedEffect(passwordResetToken) {
        val token = passwordResetToken ?: return@LaunchedEffect
        if (token.isBlank()) {
            onPasswordResetTokenConsumed()
            step = NativeLoginStep.RecoveryRequest
            error = nativePhrase("密码重置链接无效，请重新申请找回密码。", "The password reset link is invalid. Request a new reset link.")
            return@LaunchedEffect
        }
        step = NativeLoginStep.RecoveryReset(token)
        error = null
        recoveryNewPassword = ""
        recoveryConfirmation = ""
        recoveryTurnstileToken = ""
        onPasswordResetTokenConsumed()
    }

    fun submitPasskey() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val options = withContext(Dispatchers.IO) { NativeAuthApi.passkeyLoginOptions() }
                val credentialResult = withTimeout(20_000L) {
                    credentialManager.getCredential(
                        context = context,
                        request = GetCredentialRequest(
                            credentialOptions = listOf(GetPublicKeyCredentialOption(options.requestJson)),
                        ),
                    )
                }
                val credential = credentialResult.credential as? PublicKeyCredential
                    ?: throw IllegalStateException(nativePhrase("未返回通行密钥凭据", "No passkey credential was returned"))
                when (val result = withContext(Dispatchers.IO) {
                    NativeAuthApi.verifyPasskeyLogin(options.challengeToken, credential.authenticationResponseJson)
                }) {
                    is NativeAuthResult.Authenticated -> {
                        HlovetUi.applyAppearance(result.session.appearance)
                        onAuthenticated(result.session)
                    }
                    is NativeAuthResult.DeviceVerification -> {
                        step = NativeLoginStep.Device(result.challengeToken, result.emailHint, result.retryAfterSeconds)
                        deviceRetryAfter = result.retryAfterSeconds
                    }
                    is NativeAuthResult.TotpVerification -> {
                        step = NativeLoginStep.Totp(result.challengeToken)
                        totpRecoveryMode = false
                    }
                    is NativeAuthResult.GoogleLinkRequired -> step = NativeLoginStep.GoogleLink(result.pendingToken, result.email, result.methods)
                }
            } catch (exception: GetCredentialException) {
                error = credentialErrorMessage(exception)
            } catch (exception: TimeoutCancellationException) {
                error = nativePhrase("通行密钥验证超时，当前设备可能没有可用的通行密钥；请改用密码登录。", "Passkey verification timed out. This device may not have an available passkey; sign in with your password instead.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("通行密钥登录失败，请稍后重试。", "Passkey sign-in failed. Try again later.")
            } finally {
                loading = false
            }
        }
    }

    fun startGoogleLogin() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val authorizationUrl = withContext(Dispatchers.IO) { NativeAuthApi.startGoogleLogin() }
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)))
            } catch (exception: ActivityNotFoundException) {
                error = nativePhrase("设备没有可用的浏览器，无法打开 Google 登录。", "No browser is available to open Google sign-in.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("Google 登录暂不可用，请稍后重试。", "Google sign-in is unavailable. Try again later.")
            } finally {
                loading = false
            }
        }
    }

    fun startPasswordRecovery() {
        // Keep the recovery request inside the native authentication flow. The
        // email still contains the web fallback link, while the app reset link
        // returns through hlovet-native://auth/password-reset.
        step = NativeLoginStep.RecoveryRequest
        error = null
        recoveryEmail = account.trim().takeIf { it.contains("@") }.orEmpty()
        recoveryTurnstileToken = ""
    }

    fun submitGoogleLinkPasskey(current: NativeLoginStep.GoogleLink) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val options = withContext(Dispatchers.IO) { NativeAuthApi.googleLinkPasskeyOptions(current.pendingToken) }
                val credentialResult = withTimeout(20_000L) {
                    credentialManager.getCredential(
                        context = context,
                        request = GetCredentialRequest(
                            credentialOptions = listOf(GetPublicKeyCredentialOption(options.requestJson)),
                        ),
                    )
                }
                val credential = credentialResult.credential as? PublicKeyCredential
                    ?: throw IllegalStateException(nativePhrase("未返回通行密钥凭据", "No passkey credential was returned"))
                when (val result = withContext(Dispatchers.IO) {
                    NativeAuthApi.verifyGoogleLinkPasskey(current.pendingToken, options.challengeToken, credential.authenticationResponseJson)
                }) {
                    is NativeAuthResult.Authenticated -> {
                        HlovetUi.applyAppearance(result.session.appearance)
                        onAuthenticated(result.session)
                    }
                    is NativeAuthResult.DeviceVerification -> {
                        step = NativeLoginStep.Device(result.challengeToken, result.emailHint, result.retryAfterSeconds)
                        deviceRetryAfter = result.retryAfterSeconds
                    }
                    is NativeAuthResult.TotpVerification -> {
                        step = NativeLoginStep.Totp(result.challengeToken)
                        totpRecoveryMode = false
                    }
                    is NativeAuthResult.GoogleLinkRequired -> step = NativeLoginStep.GoogleLink(result.pendingToken, result.email, result.methods)
                }
            } catch (exception: GetCredentialException) {
                error = credentialErrorMessage(exception)
            } catch (exception: TimeoutCancellationException) {
                error = nativePhrase("通行密钥验证超时，当前设备可能没有可用的通行密钥；请改用其他方式完成验证。", "Passkey verification timed out. Use another verification method.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("Google 关联验证失败，请稍后重试。", "Google linking verification failed. Try again later.")
            } finally {
                loading = false
            }
        }
    }

    fun requestGoogleLinkEmail(current: NativeLoginStep.GoogleLink) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val challenge = withContext(Dispatchers.IO) { NativeAuthApi.requestGoogleLinkEmail(current.pendingToken) }
                step = NativeLoginStep.GoogleLinkEmail(current.pendingToken, challenge.emailHint, challenge.challengeToken)
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("验证码发送失败，请稍后重试。", "The verification code could not be sent. Try again later.")
            } finally {
                loading = false
            }
        }
    }

    val stepTitle = when (step) {
        NativeLoginStep.Credentials -> nativePhrase("登录 HLOVET", "Sign in to HLOVET")
        NativeLoginStep.Register -> nativePhrase("创建 HLOVET 账号", "Create a HLOVET account")
        NativeLoginStep.RecoveryRequest -> nativePhrase("找回密码", "Reset your password")
        is NativeLoginStep.RecoverySent -> nativePhrase("查收重置邮件", "Check your reset email")
        is NativeLoginStep.RecoveryReset -> nativePhrase("设置新密码", "Set a new password")
        NativeLoginStep.RecoveryComplete -> nativePhrase("密码已重置", "Password reset")
        else -> nativePhrase("完成安全验证", "Complete security verification")
    }
    val stepSubtitle = when (step) {
        NativeLoginStep.Credentials -> nativePhrase("登录后同步你的个人外观配置", "Sign in to sync your appearance settings")
        NativeLoginStep.Register -> nativePhrase("注册后即可同步个人外观配置", "Create an account to sync your appearance settings")
        NativeLoginStep.RecoveryRequest,
        is NativeLoginStep.RecoverySent,
        is NativeLoginStep.RecoveryReset,
        NativeLoginStep.RecoveryComplete -> nativePhrase("使用已验证邮箱恢复账号访问", "Recover access with your verified email")
        else -> nativePhrase("继续完成账号安全验证", "Continue account security verification")
    }

    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.cardShape) {
            Column(
                Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::leaveCurrentStep) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            stepTitle,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stepSubtitle,
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
                when (val currentStep = step) {
                    NativeLoginStep.Credentials -> {
                        OutlinedTextField(
                            value = account,
                            onValueChange = { account = it },
                            modifier = Modifier.fillMaxWidth().testTag("native-login-account"),
                            singleLine = true,
                            label = { Text(nativePhrase("账号或邮箱", "Username or email")) },
                            leadingIcon = { Icon(Icons.Filled.PersonOutline, contentDescription = null) },
                            colors = loginFieldColors(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth().testTag("native-login-password"),
                            singleLine = true,
                            label = { Text(nativePhrase("密码", "Password")) },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (passwordVisible) nativePhrase("隐藏密码", "Hide password") else nativePhrase("显示密码", "Show password"),
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            colors = loginFieldColors(),
                        )
                        if (loginTurnstileRequired) {
                            Text(
                                nativePhrase("登录失败次数过多，请先完成人机验证。", "Too many failed sign-in attempts. Complete the human verification first."),
                                color = TextMuted,
                                fontSize = 11.sp,
                            )
                            TurnstileField(
                                siteKey = registrationPolicy?.turnstileSiteKey.orEmpty(),
                                action = "login",
                                resetKey = loginTurnstileResetKey,
                                onTokenChange = { loginTurnstileToken = it },
                            )
                        }
                        Text(nativePhrase("不会保存密码；登录凭据使用 Android Keystore 加密保存。", "Your password is never stored. Sign-in credentials are encrypted with Android Keystore."), color = TextMuted, fontSize = 11.sp)
                        FilledTonalButton(
                            onClick = ::submitPasswordLogin,
                            enabled = account.isNotBlank() && password.isNotBlank() && !loading,
                            modifier = Modifier.fillMaxWidth().testTag("native-login-submit"),
                        ) {
                            Text(if (loading) nativePhrase("登录中…", "Signing in…") else nativePhrase("登录", "Sign in"))
                        }
                        Spacer(Modifier.height(2.dp))
                        FilledTonalButton(
                            onClick = ::submitPasskey,
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            LoginProviderContent(R.drawable.ic_passkey_color, nativePhrase("使用通行密钥登录", "Sign in with a passkey"))
                        }
                        when (googleAvailable) {
                            true -> {
                                FilledTonalButton(
                                    onClick = ::startGoogleLogin,
                                    enabled = !loading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    LoginProviderContent(R.drawable.ic_google_color, nativePhrase("使用 Google 登录", "Sign in with Google"))
                                }
                            }
                            false -> Unit
                            null -> {
                                TextButton(
                                    onClick = { googleProviderRetryKey += 1 },
                                    enabled = !loading,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (googleProviderError.isNullOrBlank()) {
                                            nativePhrase("正在读取 Google 登录配置…", "Loading Google sign-in settings…")
                                        } else {
                                            nativePhrase("Google 登录配置读取失败，点击重试", "Google sign-in settings failed to load. Tap to retry")
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = ::openRegistration,
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(nativePhrase("没有账号？注册账号", "No account? Create one"))
                        }
                        TextButton(
                            onClick = ::openPasswordRecovery,
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(nativePhrase("忘记密码？找回账号", "Forgot your password?"))
                        }
                    }
                    NativeLoginStep.RecoveryRequest -> {
                        val policy = registrationPolicy
                        if (policy == null) {
                            Text(nativePhrase("正在读取安全配置…", "Loading security settings…"), color = TextMuted, fontSize = 12.sp)
                        } else if (!policy.passwordRecoveryEnabled) {
                            Text(nativePhrase("当前暂未启用密码找回。", "Password recovery is currently disabled."), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        } else {
                            Text(nativePhrase("输入账号绑定的邮箱，我们会发送一封 30 分钟内有效的重置邮件。", "Enter the email linked to your account. We will send a reset link valid for 30 minutes."), color = TextMuted, fontSize = 12.sp)
                            OutlinedTextField(
                                value = recoveryEmail,
                                onValueChange = { recoveryEmail = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !loading,
                                singleLine = true,
                                label = { Text(nativePhrase("绑定邮箱", "Account email")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                colors = loginFieldColors(),
                            )
                            if (policy.recoveryTurnstileEnabled) {
                                Text(nativePhrase("发送重置邮件前需要完成人机验证。", "Complete the human verification before requesting a reset email."), color = TextMuted, fontSize = 11.sp)
                                TurnstileField(
                                    siteKey = policy.turnstileSiteKey,
                                    action = "password-recovery",
                                    resetKey = recoveryTurnstileResetKey,
                                    onTokenChange = { recoveryTurnstileToken = it },
                                )
                            }
                            FilledTonalButton(
                                onClick = ::requestPasswordRecovery,
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (loading) nativePhrase("发送中…", "Sending…") else nativePhrase("发送重置邮件", "Send reset email")) }
                            TextButton(
                                onClick = { step = NativeLoginStep.Credentials; error = null },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(nativePhrase("返回登录", "Back to sign in")) }
                        }
                    }
                    is NativeLoginStep.RecoverySent -> {
                        Text(nativePhrase("如果邮箱属于有效账号，重置链接已经发送到：", "If the email belongs to an account, a reset link was sent to:"), color = TextMuted, fontSize = 12.sp)
                        Text(currentStep.email, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(nativePhrase("请在邮件中点击“在 HLOVET Android 中重置密码”。链接 30 分钟内有效，使用一次后失效。", "Open the email and tap ‘Reset in HLOVET Android’. The link is valid for 30 minutes and can be used once."), color = TextMuted, fontSize = 12.sp)
                        TextButton(
                            onClick = {
                                recoveryEmail = currentStep.email
                                step = NativeLoginStep.RecoveryRequest
                                error = null
                            },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(nativePhrase("重新发送", "Send again")) }
                        TextButton(
                            onClick = { step = NativeLoginStep.Credentials; error = null },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(nativePhrase("返回登录", "Back to sign in")) }
                    }
                    is NativeLoginStep.RecoveryReset -> {
                        Text(nativePhrase("请设置一个至少 8 位的新密码。提交后，其他登录会话会被退出。", "Set a new password with at least 8 characters. Other sessions will be signed out."), color = TextMuted, fontSize = 12.sp)
                        OutlinedTextField(
                            value = recoveryNewPassword,
                            onValueChange = { recoveryNewPassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !loading,
                            singleLine = true,
                            label = { Text(nativePhrase("新密码", "New password")) },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { recoveryPasswordVisible = !recoveryPasswordVisible }) {
                                    Icon(
                                        if (recoveryPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (recoveryPasswordVisible) nativePhrase("隐藏密码", "Hide password") else nativePhrase("显示密码", "Show password"),
                                    )
                                }
                            },
                            visualTransformation = if (recoveryPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            colors = loginFieldColors(),
                        )
                        OutlinedTextField(
                            value = recoveryConfirmation,
                            onValueChange = { recoveryConfirmation = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !loading,
                            singleLine = true,
                            label = { Text(nativePhrase("确认新密码", "Confirm new password")) },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { recoveryConfirmationVisible = !recoveryConfirmationVisible }) {
                                    Icon(
                                        if (recoveryConfirmationVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (recoveryConfirmationVisible) nativePhrase("隐藏确认密码", "Hide confirmation") else nativePhrase("显示确认密码", "Show confirmation"),
                                    )
                                }
                            },
                            visualTransformation = if (recoveryConfirmationVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            colors = loginFieldColors(),
                        )
                        val policy = registrationPolicy
                        if (policy?.recoveryTurnstileEnabled == true) {
                            Text(nativePhrase("提交前需要完成人机验证。", "Complete the human verification before submitting."), color = TextMuted, fontSize = 11.sp)
                            TurnstileField(
                                siteKey = policy.turnstileSiteKey,
                                action = "password-reset",
                                resetKey = recoveryTurnstileResetKey,
                                onTokenChange = { recoveryTurnstileToken = it },
                            )
                        }
                        FilledTonalButton(
                            onClick = { submitPasswordRecoveryReset(currentStep) },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loading) nativePhrase("重置中…", "Resetting…") else nativePhrase("重置密码", "Reset password")) }
                    }
                    NativeLoginStep.RecoveryComplete -> {
                        Text(nativePhrase("密码已重置，出于安全考虑，其他设备上的登录会话已退出。", "Your password was reset. Other device sessions were signed out for security."), color = TextMuted, fontSize = 12.sp)
                        FilledTonalButton(
                            onClick = { step = NativeLoginStep.Credentials; error = null },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(nativePhrase("返回登录", "Back to sign in")) }
                    }
                    NativeLoginStep.Register -> {
                        val policy = registrationPolicy
                        val requiresEmailCode = policy?.emailVerificationEnabled == true
                        val requiresRegistrationTurnstile = policy?.turnstileEnabled == true && !requiresEmailCode
                        val registrationEmail = registerEmail.trim().lowercase()
                        val codeMatchesEmail = registrationCodeSentTo.isNotBlank() && registrationCodeSentTo == registrationEmail

                        if (policy == null) {
                            Text(
                                registrationPolicyError ?: nativePhrase("正在读取注册配置…", "Loading registration settings…"),
                                color = if (registrationPolicyError == null) TextMuted else MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                            )
                        } else if (!policy.registrationOpen) {
                            Text(nativePhrase("当前暂未开放注册。", "Registration is currently closed."), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        } else {
                            OutlinedTextField(
                                value = registerUsername,
                                onValueChange = { registerUsername = it.take(32) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !loading,
                                singleLine = true,
                                label = { Text(nativePhrase("用户名", "Username")) },
                                leadingIcon = { Icon(Icons.Filled.PersonOutline, contentDescription = null) },
                                colors = loginFieldColors(),
                            )
                            OutlinedTextField(
                                value = registerNickname,
                                onValueChange = { registerNickname = it.take(24) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !loading,
                                singleLine = true,
                                label = { Text(nativePhrase("昵称", "Display name")) },
                                colors = loginFieldColors(),
                            )
                            OutlinedTextField(
                                value = registerEmail,
                                onValueChange = { registerEmail = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !loading,
                                singleLine = true,
                                label = { Text(nativePhrase("邮箱", "Email")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                colors = loginFieldColors(),
                            )
                            if (requiresEmailCode) {
                                if (policy.turnstileEnabled) {
                                    Text(nativePhrase("发送邮箱验证码前需要完成人机验证。", "Complete the human verification before requesting an email code."), color = TextMuted, fontSize = 11.sp)
                                    TurnstileField(
                                        siteKey = policy.turnstileSiteKey,
                                        action = "registration-code",
                                        resetKey = registerTurnstileResetKey,
                                        onTokenChange = { registerTurnstileToken = it },
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    HlovetCodeInput(
                                        value = registerCode,
                                        onValueChange = { registerCode = it },
                                        modifier = Modifier.weight(1f),
                                        enabled = !loading,
                                        label = nativePhrase("邮箱验证码", "Email code"),
                                    )
                                    TextButton(
                                        onClick = ::requestRegistrationCode,
                                        enabled = !loading && registrationRetryAfter == 0,
                                    ) {
                                        Text(
                                            when {
                                                loading -> nativePhrase("发送中…", "Sending…")
                                                registrationRetryAfter > 0 -> "${registrationRetryAfter}s"
                                                codeMatchesEmail -> nativePhrase("重新发送", "Send again")
                                                else -> nativePhrase("获取验证码", "Get code")
                                            },
                                            maxLines = 1,
                                        )
                                    }
                                }
                                if (codeMatchesEmail) {
                                    Text(nativePhrase("验证码已发送到 $registrationCodeSentTo", "A verification code was sent to $registrationCodeSentTo"), color = TextMuted, fontSize = 11.sp)
                                }
                            }
                            OutlinedTextField(
                                value = registerPassword,
                                onValueChange = { registerPassword = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !loading,
                                singleLine = true,
                                label = { Text(nativePhrase("密码", "Password")) },
                                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                trailingIcon = {
                                    IconButton(onClick = { registerPasswordVisible = !registerPasswordVisible }) {
                                        Icon(
                                            if (registerPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = if (registerPasswordVisible) nativePhrase("隐藏密码", "Hide password") else nativePhrase("显示密码", "Show password"),
                                        )
                                    }
                                },
                                visualTransformation = if (registerPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                colors = loginFieldColors(),
                            )
                            OutlinedTextField(
                                value = registerConfirmation,
                                onValueChange = { registerConfirmation = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !loading,
                                singleLine = true,
                                label = { Text(nativePhrase("确认密码", "Confirm password")) },
                                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                trailingIcon = {
                                    IconButton(onClick = { registerConfirmationVisible = !registerConfirmationVisible }) {
                                        Icon(
                                            if (registerConfirmationVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = if (registerConfirmationVisible) nativePhrase("隐藏确认密码", "Hide confirmation") else nativePhrase("显示确认密码", "Show confirmation"),
                                        )
                                    }
                                },
                                visualTransformation = if (registerConfirmationVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                colors = loginFieldColors(),
                            )
                            if (requiresRegistrationTurnstile) {
                                Text(nativePhrase("提交注册前需要完成人机验证。", "Complete the human verification before creating your account."), color = TextMuted, fontSize = 11.sp)
                                TurnstileField(
                                    siteKey = policy.turnstileSiteKey,
                                    action = "register",
                                    resetKey = registerTurnstileResetKey,
                                    onTokenChange = { registerTurnstileToken = it },
                                )
                            }
                            FilledTonalButton(
                                onClick = ::submitRegistration,
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (loading) nativePhrase("处理中…", "Working…") else nativePhrase("注册账号", "Create account"))
                            }
                            TextButton(
                                onClick = { step = NativeLoginStep.Credentials; error = null },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(nativePhrase("返回登录", "Back to sign in")) }
                        }
                    }
                    is NativeLoginStep.Device -> {
                        Text(nativePhrase("新设备验证", "New device verification"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(nativePhrase("验证码已发送到 ${currentStep.emailHint.ifBlank { "绑定邮箱" }}。", "A verification code was sent to ${currentStep.emailHint.ifBlank { "your account email" }}."), color = TextMuted, fontSize = 12.sp)
                        VerificationCodeField(code, { code = it }, loading,
                            onComplete = { completed -> submit { NativeAuthApi.verifyDevice(currentStep.challengeToken, completed) } },
                        )
                        FilledTonalButton(
                            onClick = { submit { NativeAuthApi.verifyDevice(currentStep.challengeToken, code) } },
                            enabled = code.length == 6 && !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loading) nativePhrase("验证中…", "Verifying…") else nativePhrase("验证设备", "Verify device")) }
                        TextButton(
                            onClick = { resendDeviceCode(currentStep) },
                            enabled = !loading && deviceRetryAfter <= 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (deviceRetryAfter > 0) {
                                    nativePhrase("${deviceRetryAfter}s 后可重新发送", "Resend in ${deviceRetryAfter}s")
                                } else {
                                    nativePhrase("重新发送验证码", "Resend code")
                                },
                            )
                        }
                    }
                    is NativeLoginStep.Totp -> {
                        Text(nativePhrase("双因素认证", "Two-factor authentication"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (totpRecoveryMode) {
                                nativePhrase("请输入 6 位恢复码。", "Enter your 6-character recovery code.")
                            } else {
                                nativePhrase("请输入验证器中的 6 位验证码。", "Enter the 6-digit code from your authenticator.")
                            },
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
                        VerificationCodeField(code, { code = it }, loading, allowLetters = totpRecoveryMode,
                            onComplete = { completed -> submit { NativeAuthApi.verifyTotp(currentStep.challengeToken, completed) } },
                        )
                        FilledTonalButton(
                            onClick = { submit { NativeAuthApi.verifyTotp(currentStep.challengeToken, code) } },
                            enabled = code.length == 6 && !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loading) nativePhrase("验证中…", "Verifying…") else nativePhrase("完成登录", "Complete sign-in")) }
                        TextButton(
                            onClick = {
                                code = ""
                                totpRecoveryMode = !totpRecoveryMode
                                error = null
                            },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (totpRecoveryMode) {
                                    nativePhrase("使用身份验证器", "Use authenticator")
                                } else {
                                    nativePhrase("使用恢复码", "Use recovery code")
                                },
                            )
                        }
                    }
                    is NativeLoginStep.GoogleLink -> {
                        Text(nativePhrase("关联 Google 账号", "Link Google account"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            nativePhrase("${currentStep.email} 已存在站内账号，需要完成一次安全验证后才能关联。", "An account already uses ${currentStep.email}. Complete one security check to link Google."),
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
                        if (currentStep.methods.passkey) {
                            FilledTonalButton(
                                onClick = { submitGoogleLinkPasskey(currentStep) },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { LoginProviderContent(R.drawable.ic_passkey_color, nativePhrase("使用通行密钥验证", "Verify with a passkey")) }
                        }
                        if (currentStep.methods.email) {
                            FilledTonalButton(
                                onClick = { requestGoogleLinkEmail(currentStep) },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(nativePhrase("使用邮箱验证码", "Use an email code")) }
                        }
                        if (currentStep.methods.totp) {
                            FilledTonalButton(
                                onClick = {
                                    step = NativeLoginStep.GoogleLinkTotp(currentStep.pendingToken)
                                    code = ""
                                    totpRecoveryMode = false
                                    error = null
                                },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(nativePhrase("使用双因素认证", "Use two-factor authentication")) }
                        }
                        if (currentStep.methods.password) {
                            FilledTonalButton(
                                onClick = { step = NativeLoginStep.GoogleLinkPassword(currentStep.pendingToken) },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(nativePhrase("使用账号密码", "Use your password")) }
                        }
                    }
                    is NativeLoginStep.GoogleLinkEmail -> {
                        Text(nativePhrase("邮箱验证", "Email verification"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            nativePhrase("验证码已发送到 ${currentStep.emailHint.ifBlank { "绑定邮箱" }}。", "A verification code was sent to ${currentStep.emailHint.ifBlank { "your account email" }}."),
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
                        VerificationCodeField(code, { code = it }, loading,
                            onComplete = { completed -> submit { NativeAuthApi.verifyGoogleLinkEmail(currentStep.pendingToken, currentStep.challengeToken, completed) } },
                        )
                        FilledTonalButton(
                            onClick = { submit { NativeAuthApi.verifyGoogleLinkEmail(currentStep.pendingToken, currentStep.challengeToken, code) } },
                            enabled = code.length == 6 && !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loading) nativePhrase("验证中…", "Verifying…") else nativePhrase("完成关联", "Complete linking")) }
                    }
                    is NativeLoginStep.GoogleLinkTotp -> {
                        Text(nativePhrase("双因素认证", "Two-factor authentication"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (totpRecoveryMode) nativePhrase("请输入 6 位恢复码。", "Enter your 6-character recovery code.")
                            else nativePhrase("请输入身份验证器中的 6 位验证码。", "Enter the 6-digit code from your authenticator."),
                            color = TextMuted, fontSize = 12.sp,
                        )
                        VerificationCodeField(code, { code = it }, loading, allowLetters = totpRecoveryMode,
                            onComplete = { completed -> submit { NativeAuthApi.verifyGoogleLinkTotp(currentStep.pendingToken, completed) } },
                        )
                        FilledTonalButton(
                            onClick = { submit { NativeAuthApi.verifyGoogleLinkTotp(currentStep.pendingToken, code) } },
                            enabled = code.length == 6 && !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loading) nativePhrase("验证中…", "Verifying…") else nativePhrase("完成关联", "Complete linking")) }
                        TextButton(
                            onClick = { code = ""; totpRecoveryMode = !totpRecoveryMode; error = null },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (totpRecoveryMode) nativePhrase("使用身份验证器", "Use authenticator") else nativePhrase("使用恢复码", "Use recovery code"))
                        }
                    }
                    is NativeLoginStep.GoogleLinkPassword -> {
                        Text(nativePhrase("账号密码验证", "Password verification"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(nativePhrase("当前密码", "Current password")) },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (passwordVisible) nativePhrase("隐藏密码", "Hide password") else nativePhrase("显示密码", "Show password"),
                                    )
                                }
                            },
                            colors = loginFieldColors(),
                        )
                        FilledTonalButton(
                            onClick = { submit { NativeAuthApi.verifyGoogleLinkPassword(currentStep.pendingToken, password) } },
                            enabled = password.isNotBlank() && !loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loading) nativePhrase("验证中…", "Verifying…") else nativePhrase("完成关联", "Complete linking")) }
                    }
                }
                if (!error.isNullOrBlank()) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                if (!notice.isNullOrBlank()) {
                    Text(notice!!, color = Accent, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun LoginProviderContent(iconRes: Int, label: String) {
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Color.Unspecified,
        )
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** 用 siteKey/action 展示隔离的人机验证；resetKey 触发重载，onTokenChange 回传令牌供服务端校验。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TurnstileField(
    siteKey: String,
    action: String,
    resetKey: Int,
    onTokenChange: (String) -> Unit,
) {
    // 无站点配置时明确提示，不创建无法完成验证的浏览器视图。
    if (siteKey.isBlank()) {
        Text(nativePhrase("人机验证暂不可用，请联系管理员。", "Human verification is unavailable. Contact an administrator."), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        return
    }
    // 使用最新回调并按动作生成文档，只有进入验证页面才初始化 WebView。
    val callback = androidx.compose.runtime.rememberUpdatedState(onTokenChange)
    val html = remember(siteKey, action) { turnstileHtml(siteKey, action) }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        factory = { context ->
            nativeCreateWebView(context).apply {
                // 只允许必要的验证脚本及 DOM 存储，禁止访问本地文件。
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = WebViewClient()
                addJavascriptInterface(object {
                    @JavascriptInterface
                    /** 将浏览器取得的 token 交给界面，不能替代服务端校验。 */
                    fun onToken(token: String) {
                        // 更新当前验证动作的令牌。
                        callback.value(token)
                    }

                    @JavascriptInterface
                    /** 令牌过期时清空，禁止继续提交旧验证结果。 */
                    fun onExpired() {
                        // 让表单重新等待有效令牌。
                        callback.value("")
                    }

                    @JavascriptInterface
                    /** 浏览器验证失败时清空令牌，等待重试。 */
                    fun onError() {
                        // 失败不作为人机验证成功处理。
                        callback.value("")
                    }
                }, "HlovetTurnstile")
                tag = resetKey
                loadDataWithBaseURL(
                    nativePortalBaseUrl() + "/",
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
        },
        update = { webView ->
            // 仅新的重置请求重新加载，普通重组不使验证码失效。
            if (webView.tag != resetKey) {
                webView.tag = resetKey
                webView.reload()
            }
        },
    )
}

private fun turnstileHtml(siteKey: String, action: String): String {
    val quotedSiteKey = JSONObject.quote(siteKey)
    val quotedAction = JSONObject.quote(action)
    return """
        <!doctype html>
        <html><head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <style>
            html, body { margin: 0; padding: 0; background: transparent; overflow: hidden; }
            #widget { min-height: 64px; }
          </style>
          <script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit" async defer></script>
        </head><body>
          <div id="widget"></div>
          <script>
            (function boot() {
              if (!window.turnstile) { window.setTimeout(boot, 150); return; }
              window.turnstile.render('#widget', {
                sitekey: $quotedSiteKey,
                action: $quotedAction,
                theme: 'light',
                callback: function(token) { HlovetTurnstile.onToken(token); },
                'expired-callback': function() { HlovetTurnstile.onExpired(); },
                'error-callback': function() { HlovetTurnstile.onError(); }
              });
            })();
          </script>
        </body></html>
    """.trimIndent()
}

private fun credentialErrorMessage(exception: GetCredentialException): String {
    val message = exception.message.orEmpty()
    return when {
        message.contains("no provider dependencies", ignoreCase = true) ->
            nativePhrase("当前设备的 Google Play 服务不支持通行密钥，请更新 Play services 或改用真实 Android 设备。", "Google Play services on this device do not support passkeys. Update Play services or use a physical Android device.")

        message.contains("CancellationException", ignoreCase = true) ||
            message.contains("task was canceled", ignoreCase = true) ||
            message.contains("one tap", ignoreCase = true) ->
            nativePhrase("当前设备没有可用的通行密钥，或验证已取消。请先绑定通行密钥、设置屏幕锁后重试，或改用密码登录。", "No passkey is available on this device, or verification was canceled. Add a passkey and screen lock, or sign in with your password.")

        message.isBlank() -> nativePhrase("通行密钥验证失败，请改用其他方式登录。", "Passkey verification failed. Use another sign-in method.")
        else -> nativePhrase("通行密钥验证失败，请重试或改用密码登录。", "Passkey verification failed. Try again or use your password.")
    }
}

@Composable
private fun VerificationCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    loading: Boolean,
    allowLetters: Boolean = false,
    onComplete: ((String) -> Unit)? = null,
) {
    HlovetCodeInput(
        value = value,
        onValueChange = onValueChange,
        enabled = !loading,
        allowLetters = allowLetters,
        label = if (allowLetters) nativePhrase("6 位恢复码", "6-character recovery code") else nativePhrase("6 位验证码", "6-digit code"),
        onComplete = onComplete,
    )
}

@Composable
internal fun loginFieldColors() = HlovetUi.fieldColors(
    unfocusedBorderColor = HlovetUi.divider,
)

@Composable
private fun PreviewStatusScreen(title: String, detail: String?, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.cardShape) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Explore, contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(detail, color = TextMuted, fontSize = 13.sp)
                }
                if (onRetry != null) {
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = onRetry) { Text(nativePhrase("重新加载", "Reload")) }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(
    title: String,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null
) {
    TopAppBar(
        title = {
            Column {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Normal)
                }
            }
        },
        actions = { action?.invoke() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
    )
}

/**
 * 显示 initialArticle 的阅读与评论；focusCommentId 用于定位评论，session 决定权限及缓存归属。
 * onLogin/onBack/onOpenUser 分别处理登录、返回和作者导航，附件下载复用当前来源的统一文件操作面板。
 */
@Composable
private fun ArticleDetailScreen(
    initialArticle: RemoteArticle,
    focusCommentId: Int? = null,
    session: NativeSession?,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onOpenUser: (String) -> Unit = {},
) {
    // 阅读、评论及文件面板状态跟随文章和会话变化，不能把旧账号附件带入新身份。
    val context = LocalContext.current
    val offlineOwner = NativeOfflineOwner.forSession(session)
    var article by remember(initialArticle.slug, session?.accessToken) { mutableStateOf(initialArticle) }
    var comments by remember(initialArticle.slug, session?.accessToken) { mutableStateOf<List<NativeArticleComment>>(emptyList()) }
    var commentTotal by remember(initialArticle.slug, session?.accessToken) { mutableIntStateOf(initialArticle.commentCount) }
    var nextCommentCursor by remember(initialArticle.slug, session?.accessToken) { mutableStateOf<Int?>(null) }
    var hasMoreComments by remember(initialArticle.slug, session?.accessToken) { mutableStateOf(false) }
    var loading by remember(initialArticle.slug, session?.accessToken) { mutableStateOf(true) }
    var loadingMore by remember(initialArticle.slug, session?.accessToken) { mutableStateOf(false) }
    var actionBusy by remember(initialArticle.slug, session?.accessToken) { mutableStateOf("") }
    var commentInput by remember(initialArticle.slug, session?.accessToken) { mutableStateOf(TextFieldValue("")) }
    var commentAttachments by remember(initialArticle.slug, session?.accessToken) { mutableStateOf<List<NativeUploadFile>>(emptyList()) }
    var commentAttachmentLoading by remember(initialArticle.slug, session?.accessToken) { mutableStateOf(false) }
    var commentPreviewAttachment by remember(initialArticle.slug, session?.accessToken) { mutableStateOf<NativeChatAttachmentPreview?>(null) }
    var articlePreviewImage by remember(initialArticle.slug, session?.accessToken) { mutableStateOf<NativeArticleImageTarget?>(null) }
    var downloadedAttachment by remember(initialArticle.slug, session?.accessToken) { mutableStateOf<NativeAttachmentFile?>(null) }
    var replyingTo by remember(initialArticle.slug, session?.accessToken) { mutableStateOf<NativeArticleComment?>(null) }
    var reportingArticle by remember(initialArticle.slug, session?.accessToken) { mutableStateOf(false) }
    var reportingComment by remember(initialArticle.slug, session?.accessToken) { mutableStateOf<NativeArticleComment?>(null) }
    var deleteCommentTarget by remember(initialArticle.slug, session?.accessToken) { mutableStateOf<NativeArticleComment?>(null) }
    var articleReportReason by remember(initialArticle.slug, session?.accessToken) { mutableStateOf("spam") }
    var articleReportDetail by remember(initialArticle.slug, session?.accessToken) { mutableStateOf("") }
    var reportReason by remember(initialArticle.slug, session?.accessToken) { mutableStateOf("spam") }
    var reportDetail by remember(initialArticle.slug, session?.accessToken) { mutableStateOf("") }
    var notice by remember(initialArticle.slug, session?.accessToken) { mutableStateOf<String?>(null) }
    var highlightCommentId by remember(initialArticle.slug, session?.accessToken) { mutableStateOf<Int?>(null) }
    var mentionCandidates by remember(initialArticle.slug, session?.accessToken) { mutableStateOf<List<NativeSocialSearchUser>>(emptyList()) }
    var mentionLoading by remember(initialArticle.slug, session?.accessToken) { mutableStateOf(false) }
    var error by remember(initialArticle.slug, session?.accessToken) { mutableStateOf<String?>(null) }
    var offlineSaved by remember(initialArticle.slug, offlineOwner) { mutableStateOf(false) }
    var offlineNotice by remember(initialArticle.slug, offlineOwner) { mutableStateOf<String?>(null) }
    var showingOfflineContent by remember(initialArticle.slug, offlineOwner) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val commentListState = rememberLazyListState()

    /** 下载 target 对应的文章附件或读取当前账号离线缓存，成功后展示统一打开及保存入口。 */
    fun openArticleAttachment(target: NativeArticleAttachmentTarget) {
        // 单次下载与文章其它写操作互斥，来源和账号继续由现有离线键隔离。
        val token = session?.accessToken
        if (actionBusy.isNotBlank()) return
        scope.launch {
            actionBusy = "article-attachment"
            error = null
            try {
                val file = withContext(Dispatchers.IO) {
                    if (showingOfflineContent) {
                        NativeOfflineCache.loadCachedMediaFile(
                            context = context,
                            url = target.url,
                            cacheOwner = offlineOwner,
                        )
                    } else {
                        NativeOfflineCache.loadMediaFile(
                            context = context,
                            url = target.url,
                            accessToken = token,
                            cacheOwner = offlineOwner,
                            originalName = target.fileName,
                            mimeType = guessNativeArticleMimeType(target.url),
                        )
                    }
                } ?: throw IllegalStateException(nativePhrase("附件下载失败，请检查网络后重试。", "Attachment download failed. Check your connection and try again."))
                // 下载完成不直接交给外部查看器，允许没有查看器的设备使用系统另存为。
                downloadedAttachment = NativeAttachmentFile(file, target.fileName, guessNativeArticleMimeType(target.fileName))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // 页面或账号切换保持取消语义，不把旧文件发布到新会话。
                throw cancelled
            } catch (exception: Exception) {
                // 下载或缓存缺失反馈给当前文章，保留正文及其它操作。
                error = exception.message ?: nativePhrase("附件打开失败。", "Failed to open the attachment.")
            } finally {
                // 成功和失败都释放本篇文章的操作锁。
                actionBusy = ""
            }
        }
    }

    val commentAttachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty() || commentAttachmentLoading) return@rememberLauncherForActivityResult
        scope.launch {
            val available = NATIVE_CHAT_MAX_ATTACHMENTS - commentAttachments.size
            if (uris.size > available) {
                error = nativePhrase("一条评论最多包含 $NATIVE_CHAT_MAX_ATTACHMENTS 个附件。", "A comment can include up to $NATIVE_CHAT_MAX_ATTACHMENTS attachments.")
                return@launch
            }
            commentAttachmentLoading = true
            error = null
            try {
                val files = withContext(Dispatchers.IO) { uris.map { readNativeChatUploadFile(context, it) } }
                if (commentAttachments.sumOf { it.bytes.size.toLong() } + files.sumOf { it.bytes.size.toLong() } > NATIVE_CHAT_MAX_BATCH_BYTES) {
                    throw IllegalStateException(nativePhrase("一条评论的附件总大小不能超过 50 MB。", "Comment attachments cannot exceed 50 MB in total."))
                }
                commentAttachments = commentAttachments + files
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("读取评论附件失败，请重试。", "Failed to read the comment attachments. Try again." )
            } finally {
                commentAttachmentLoading = false
            }
        }
    }

    LaunchedEffect(initialArticle.slug, session?.accessToken, offlineOwner, focusCommentId) {
        loading = true
        error = null
        notice = null
        offlineNotice = null
        showingOfflineContent = false
        offlineSaved = withContext(Dispatchers.IO) { NativeOfflineContentStore.isArticleSaved(context, offlineOwner, initialArticle.slug) }
        try {
            val token = session?.accessToken
            val loadedArticle = withContext(Dispatchers.IO) { NativeArticleApi.getArticle(token, initialArticle.slug) }
            val loadedComments = withContext(Dispatchers.IO) {
                NativeArticleApi.listComments(token, initialArticle.slug, focusId = focusCommentId)
            }
            article = loadedArticle
            comments = loadedComments.items
            commentTotal = loadedComments.totalThreads
            nextCommentCursor = loadedComments.nextCursor
            hasMoreComments = loadedComments.hasMore
            if (focusCommentId != null && loadedComments.items.any { it.id == focusCommentId }) {
                highlightCommentId = focusCommentId
            } else if (focusCommentId != null) {
                notice = nativePhrase("该评论已删除或当前不可见，无法定位。", "The comment was deleted or is not visible, so it cannot be located.")
            }
        } catch (exception: Exception) {
            val cached = withContext(Dispatchers.IO) { NativeOfflineContentStore.loadArticle(context, offlineOwner, initialArticle.slug) }
            if (cached != null) {
                article = cached
                comments = emptyList()
                commentTotal = cached.commentCount
                nextCommentCursor = null
                hasMoreComments = false
                highlightCommentId = null
                offlineSaved = true
                showingOfflineContent = true
                offlineNotice = nativePhrase(
                    "当前网络不可用，正在显示已保存的离线内容。评论和互动暂不可用。",
                    "You are offline. Saved content is shown, while comments and interactions are unavailable.",
                )
            } else {
                error = exception.message ?: nativePhrase("文章详情加载失败。", "Failed to load the article.")
            }
        } finally {
            loading = false
        }
    }

    // 评论提及和聊天使用同一光标规则，输入 @ 即查询，组合输入期间不抢候选。
    LaunchedEffect(commentInput.text, commentInput.selection, commentInput.composition, session?.accessToken) {
        val token = session?.accessToken
        val active = nativeActiveMention(commentInput)
        mentionCandidates = emptyList()
        if (token.isNullOrBlank() || active == null) {
            mentionLoading = false
            return@LaunchedEffect
        }
        mentionLoading = true
        delay(180)
        runCatching {
            withContext(Dispatchers.IO) { NativeFriendsApi.searchUsers(token, active.query, limit = 20, mention = true) }
        }.onSuccess { mentionCandidates = it }
            .onFailure {
                // 光标或账号变化取消旧查询时，不让旧结果覆盖新片段的候选状态。
                if (it is CancellationException) throw it
                mentionCandidates = emptyList()
            }
        mentionLoading = false
    }

    // 评论前固定六项：标题栏、作者、正文、互动、评论标题和输入区；可选提示分别占一项。
    val commentRowsStartIndex = 6 +
        (if (!error.isNullOrBlank()) 1 else 0) +
        (if (!offlineNotice.isNullOrBlank()) 1 else 0) +
        (if (!notice.isNullOrBlank()) 1 else 0)

    val articleBodyIndex = 2 +
        (if (!error.isNullOrBlank()) 1 else 0) +
        (if (!offlineNotice.isNullOrBlank()) 1 else 0) +
        (if (!notice.isNullOrBlank()) 1 else 0)

    val readingProgressModifiers = rememberNativeReadingProgress(
        article = article,
        session = session,
        loading = loading,
        offlineNotice = offlineNotice,
        articleBodyIndex = articleBodyIndex,
        listState = commentListState,
        restoreSavedProgress = focusCommentId == null,
    )

    // 先定位当前评论，再保留两秒无边框背景；提示增减导致行号变化时重新定位。
    LaunchedEffect(highlightCommentId, comments, loading, commentRowsStartIndex) {
        val targetId = highlightCommentId ?: return@LaunchedEffect
        val targetIndex = comments.indexOfFirst { it.id == targetId }
        if (targetIndex < 0 || loading) return@LaunchedEffect
        delay(120)
        commentListState.animateScrollToItem((commentRowsStartIndex + targetIndex).coerceAtLeast(0))
        delay(2000)
        if (highlightCommentId == targetId) highlightCommentId = null
    }

    fun saveOffline() {
        if (loading || actionBusy.isNotBlank()) return
        scope.launch {
            actionBusy = "offline"
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeOfflineContentStore.saveArticle(context, offlineOwner, article)
                    NativeOfflineCache.prefetchMedia(
                        context = context,
                        paths = nativeOfflineArticleMediaPaths(article),
                        accessToken = session?.accessToken,
                        cacheOwner = offlineOwner,
                        maxItems = 6,
                    )
                }
            }
                .onSuccess { offlineSaved = true; offlineNotice = nativePhrase("文章已保存到本机，可在断网时打开。", "The article is saved on this device for offline reading.") }
                .onFailure { error = it.message ?: nativePhrase("保存离线内容失败。", "Failed to save offline content.") }
            actionBusy = ""
        }
    }

    fun toggleLike() {
        val token = session?.accessToken ?: run { onLogin(); return }
        if (actionBusy.isNotBlank()) return
        val targetValue = !article.liked
        scope.launch {
            actionBusy = "like"
            error = null
            try {
                val result = withContext(Dispatchers.IO) { NativeArticleApi.toggleLike(token, article.id, targetValue) }
                article = article.copy(liked = result.liked, likeCount = result.likeCount, favoriteCount = result.favoriteCount)
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("点赞操作失败。", "Failed to update the like.")
            } finally {
                actionBusy = ""
            }
        }
    }

    fun toggleFavorite() {
        val token = session?.accessToken ?: run { onLogin(); return }
        if (actionBusy.isNotBlank()) return
        val targetValue = !article.favorited
        scope.launch {
            actionBusy = "favorite"
            error = null
            try {
                val result = withContext(Dispatchers.IO) { NativeArticleApi.toggleFavorite(token, article.id, targetValue) }
                article = article.copy(favorited = result.favorited, likeCount = result.likeCount, favoriteCount = result.favoriteCount)
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("收藏操作失败。", "Failed to update the favorite.")
            } finally {
                actionBusy = ""
            }
        }
    }

    fun toggleReadLater() {
        val token = session?.accessToken ?: run { onLogin(); return }
        if (actionBusy.isNotBlank()) return
        val targetValue = !article.readLater
        scope.launch {
            actionBusy = "read-later"
            error = null
            try {
                val updated = withContext(Dispatchers.IO) { NativeArticleApi.toggleReadLater(token, article.id, targetValue) }
                article = article.copy(readLater = updated)
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("稍后读操作失败。", "Failed to update Read later.")
            } finally {
                actionBusy = ""
            }
        }
    }

    fun redeemResource(segment: RemoteArticleSegment) {
        val token = session?.accessToken ?: run { onLogin(); return }
        val blockKey = segment.key ?: return
        if (actionBusy.isNotBlank()) return
        scope.launch {
            actionBusy = "resource:$blockKey"
            error = null
            try {
                article = withContext(Dispatchers.IO) {
                    NativeArticleApi.redeemResource(token, article.id, blockKey)
                }
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("资源兑换失败。", "Failed to redeem the resource.")
            } finally {
                actionBusy = ""
            }
        }
    }

    suspend fun refreshComments(token: String, focusId: Int? = null) {
        val refreshed = withContext(Dispatchers.IO) {
            NativeArticleApi.listComments(token, article.slug, focusId = focusId)
        }
        comments = refreshed.items
        nextCommentCursor = refreshed.nextCursor
        hasMoreComments = refreshed.hasMore
        commentTotal = refreshed.totalThreads
    }

    fun loadMoreComments() {
        val cursor = nextCommentCursor ?: return
        if (loadingMore || !hasMoreComments) return
        scope.launch {
            loadingMore = true
            error = null
            try {
                val page = withContext(Dispatchers.IO) {
                    NativeArticleApi.listComments(session?.accessToken, article.slug, cursor = cursor)
                }
                comments = (comments + page.items).distinctBy { it.id }
                nextCommentCursor = page.nextCursor
                hasMoreComments = page.hasMore
                commentTotal = page.totalThreads
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("更多评论加载失败。", "Failed to load more comments.")
            } finally {
                loadingMore = false
            }
        }
    }

    /** 使用当前账号发布输入正文和附件；回复保留目标关联，成功后清空输入并显示成功提示。 */
    fun publishComment() {
        // 校验当前会话与实际正文，提及样式不影响发送给服务端的原文。
        val token = session?.accessToken ?: run { onLogin(); return }
        val body = commentInput.text.trim()
        if (body.isBlank() && commentAttachments.isEmpty() || actionBusy.isNotBlank()) return
        val replyTarget = replyingTo
        scope.launch {
            actionBusy = "comment"
            error = null
            try {
                // 评论、回复和附件在同一次接口调用持久化，提及通知由服务端按实际权限生成。
                val created = withContext(Dispatchers.IO) {
                    NativeArticleApi.createComment(
                        accessToken = token,
                        articleId = article.id,
                        body = body,
                        parentId = replyTarget?.id,
                        quotedCommentId = replyTarget?.id,
                        files = commentAttachments,
                    )
                }
                // 仅在接口成功后更新列表和计数，失败时保留全部待发送内容。
                comments = if (replyTarget == null) {
                    listOf(created) + comments
                } else {
                    val targetIndex = comments.indexOfFirst { it.id == replyTarget.id }
                    comments.toMutableList().apply {
                        add((targetIndex + 1).coerceIn(0, size), created)
                    }
                }
                commentInput = TextFieldValue("")
                commentAttachments = emptyList()
                replyingTo = null
                mentionCandidates = emptyList()
                if (replyTarget == null) commentTotal += 1
                article = article.copy(commentCount = article.commentCount + 1)
                notice = if (replyTarget == null) nativePhrase("评论已发布。", "Comment posted.") else nativePhrase("回复已发布。", "Reply posted.")
            } catch (exception: Exception) {
                // 发布错误反馈给本页，不把失败内容当作已发送后清空。
                error = exception.message ?: nativePhrase("评论发布失败。", "Failed to post the comment.")
            } finally {
                // 每条发布路径都释放忙状态，允许用户修正后重试。
                actionBusy = ""
            }
        }
    }

    fun deleteComment(comment: NativeArticleComment) {
        val token = session?.accessToken ?: run { onLogin(); return }
        if (actionBusy.isNotBlank()) return
        scope.launch {
            actionBusy = "comment-delete-${comment.id}"
            error = null
            try {
                withContext(Dispatchers.IO) { NativeArticleApi.deleteComment(token, comment.id) }
                // The mutation succeeded already. Treat the follow-up reads
                // as best effort so a transient refresh error is not shown as
                // a false deletion failure.
                comments = comments.filterNot { it.id == comment.id }
                article = article.copy(commentCount = (article.commentCount - 1).coerceAtLeast(0))
                var refreshWarning = false
                try {
                    refreshComments(token)
                } catch (_: Exception) {
                    refreshWarning = true
                }
                try {
                    article = withContext(Dispatchers.IO) { NativeArticleApi.getArticle(token, article.slug) }
                } catch (_: Exception) {
                    refreshWarning = true
                }
                if (replyingTo?.id == comment.id) replyingTo = null
                deleteCommentTarget = null
                notice = if (refreshWarning) {
                    nativePhrase("评论已删除，但最新列表暂时无法刷新。", "The comment was deleted, but the latest list could not be refreshed.")
                } else {
                    nativePhrase("评论已删除。", "Comment deleted.")
                }
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("评论删除失败。", "Failed to delete the comment.")
            } finally {
                actionBusy = ""
            }
        }
    }

    fun reportComment() {
        val token = session?.accessToken ?: run { onLogin(); return }
        val target = reportingComment ?: return
        if (actionBusy.isNotBlank()) return
        scope.launch {
            actionBusy = "comment-report-${target.id}"
            error = null
            try {
                withContext(Dispatchers.IO) {
                    NativeArticleApi.reportComment(token, target.id, reportReason, reportDetail)
                }
                comments = comments.map { if (it.id == target.id) it.copy(reported = true) else it }
                reportingComment = null
                reportDetail = ""
                notice = nativePhrase("举报已提交，管理员会尽快处理。", "Report submitted for moderator review.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("评论举报失败。", "Failed to report the comment.")
            } finally {
                actionBusy = ""
            }
        }
    }

    fun submitArticleReport() {
        val token = session?.accessToken ?: run { onLogin(); return }
        if (actionBusy.isNotBlank()) return
        scope.launch {
            actionBusy = "article-report"
            error = null
            try {
                withContext(Dispatchers.IO) {
                    NativeArticleApi.reportArticle(token, article.id, articleReportReason, articleReportDetail)
                }
                reportingArticle = false
                articleReportDetail = ""
                notice = nativePhrase("文章举报已提交，管理员会尽快处理。", "Article report submitted for moderator review.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("文章举报提交失败。", "Failed to report the article.")
            } finally {
                actionBusy = ""
            }
        }
    }

    fun toggleCommentLike(comment: NativeArticleComment) {
        val token = session?.accessToken ?: run { onLogin(); return }
        if (actionBusy.isNotBlank()) return
        scope.launch {
            actionBusy = "comment-like-${comment.id}"
            error = null
            try {
                val result = withContext(Dispatchers.IO) {
                    NativeArticleApi.toggleCommentLike(token, comment.id, !comment.liked)
                }
                comments = comments.map { item ->
                    if (item.id == comment.id) item.copy(liked = result.liked, likeCount = result.likeCount) else item
                }
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("评论点赞失败。", "Failed to update the comment like.")
            } finally {
                actionBusy = ""
            }
        }
    }

    // Android 15 边到边窗口不会自动扣除键盘高度，评论列表必须同步缩小可见区域以保留输入和发送入口。
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().then(readingProgressModifiers.viewport),
        state = commentListState,
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TopAppBar(
                title = { Text(nativePhrase("文章详情", "Article"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"), modifier = Modifier.size(22.dp))
                    }
                },
                actions = {
                    if (session != null && article.authorUsername.isNotBlank() && !session.username.equals(article.authorUsername, ignoreCase = true)) {
                        IconButton(
                            onClick = {
                                articleReportReason = "spam"
                                articleReportDetail = ""
                                reportingArticle = true
                            },
                            enabled = !loading && actionBusy.isBlank(),
                        ) {
                            Icon(Icons.Filled.Flag, contentDescription = nativePhrase("举报文章", "Report article"), tint = TextMuted)
                        }
                    }
                    IconButton(onClick = ::saveOffline, enabled = !loading && actionBusy.isBlank()) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = if (offlineSaved) nativePhrase("更新离线内容", "Update offline copy") else nativePhrase("保存离线", "Save offline"),
                            tint = if (offlineSaved) Accent else TextMuted,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (!error.isNullOrBlank()) {
            item {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
            }
        }
        if (!offlineNotice.isNullOrBlank()) {
            item {
                Text(
                    offlineNotice!!,
                    color = Accent,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
            }
        }
        if (!notice.isNullOrBlank()) {
            item {
                Text(
                    notice!!,
                    color = Accent,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
            }
        }
        item {
            GlassCard(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.cardShape,
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.clickable(enabled = article.authorUsername.isNotBlank()) {
                            onOpenUser(article.authorUsername)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(article.authorName.takeLast(2), Coral)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(article.authorName, fontWeight = FontWeight.SemiBold)
                            Text("@${article.authorUsername.ifBlank { "user" }} · ${articleReadTime(article)}", color = TextMuted, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        article.title,
                        color = article.titleColor.toNativeComposeColor(Accent),
                        fontSize = 25.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (article.category.isNotBlank()) {
                        HlovetBadge(
                            text = article.category,
                            fontSize = 12.sp,
                            containerColor = HlovetUi.accentSoft,
                        )
                    }
                    if (article.tags.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(article.tags.joinToString("  ·  "), color = TextMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item {
            GlassCard(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .then(readingProgressModifiers.body),
                shape = HlovetUi.cardShape,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (loading) Text(nativePhrase("正在读取完整正文…", "Loading the full article..."), color = TextMuted, fontSize = 13.sp)
                    if (article.summary.isNotBlank()) {
                        Text(article.summary, fontSize = 16.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium)
                    }
                    ArticleContentPreview(
                        article = article,
                        busyKey = actionBusy,
                        isLoggedIn = session != null,
                        accessToken = session?.accessToken,
                        cacheOwner = offlineOwner,
                        offlineOnly = showingOfflineContent,
                        onLogin = onLogin,
                        onRedeem = ::redeemResource,
                        onPreviewImage = { articlePreviewImage = it },
                        onOpenAttachment = ::openArticleAttachment,
                    )
                    Text(
                        nativePhrase(
                            "阅读 ${article.viewCount}  ·  点赞 ${article.likeCount}  ·  收藏 ${article.favoriteCount}  ·  评论 ${article.commentCount}",
                            "${article.viewCount} views  ·  ${article.likeCount} likes  ·  ${article.favoriteCount} favorites  ·  ${article.commentCount} comments",
                        ),
                        color = TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        item {
            GlassCard(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.rowShape,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
                    ArticleActionButton(
                        label = if (article.liked) nativePhrase("已点赞", "Liked") else nativePhrase("点赞", "Like"),
                        count = article.likeCount,
                        active = article.liked,
                        enabled = actionBusy.isBlank(),
                        icon = if (article.liked) Icons.Filled.ThumbUp else Icons.Filled.ThumbUpOffAlt,
                        onClick = ::toggleLike,
                        modifier = Modifier.weight(1f),
                    )
                    ArticleActionButton(
                        label = if (article.favorited) nativePhrase("已收藏", "Saved") else nativePhrase("收藏", "Save"),
                        count = article.favoriteCount,
                        active = article.favorited,
                        enabled = actionBusy.isBlank(),
                        icon = if (article.favorited) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        onClick = ::toggleFavorite,
                        modifier = Modifier.weight(1f),
                    )
                    ArticleActionButton(
                        label = if (article.readLater) nativePhrase("已加入", "Added") else nativePhrase("稍后读", "Read later"),
                        count = null,
                        active = article.readLater,
                        enabled = actionBusy.isBlank(),
                        icon = if (article.readLater) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        onClick = ::toggleReadLater,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item { SectionHeading(nativePhrase("评论与回复", "Comments and replies"), nativePhrase("$commentTotal 条", "$commentTotal total")) }
        item {
            GlassCard(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.cardShape,
            ) {
                if (session == null) {
                    ListItem(
                        headlineContent = { Text(nativePhrase("登录后参与评论", "Sign in to join the discussion"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp) },
                        supportingContent = { Text(nativePhrase("登录后可以评论、回复和点赞", "Sign in to comment, reply, and like"), color = TextMuted, fontSize = 11.sp) },
                        leadingContent = { Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, tint = Accent) },
                        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(onClick = onLogin),
                    )
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        replyingTo?.let { target ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Reply, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(nativePhrase("回复 @${target.authorUsername.ifBlank { target.authorName }}", "Replying to @${target.authorUsername.ifBlank { target.authorName }}"), color = Accent, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                IconButton(onClick = { replyingTo = null }, enabled = actionBusy.isBlank(), modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = nativePhrase("取消回复", "Cancel reply"), tint = TextMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        if (commentAttachments.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(commentAttachments) { attachment ->
                                    Surface(color = SurfaceAccent, shape = HlovetUi.rowShape) {
                                        Row(
                                            modifier = Modifier.padding(start = 8.dp, top = 5.dp, end = 2.dp, bottom = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                if (attachment.mimeType.startsWith("image/")) Icons.Filled.Image else Icons.Filled.AttachFile,
                                                contentDescription = null,
                                                tint = Accent,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                attachment.name,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 150.dp),
                                            )
                                            IconButton(
                                                onClick = { commentAttachments = commentAttachments.filterNot { it === attachment } },
                                                enabled = !commentAttachmentLoading && actionBusy.isBlank(),
                                                modifier = Modifier.size(24.dp),
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = nativePhrase("移除附件", "Remove attachment"), modifier = Modifier.size(13.dp))
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            IconButton(
                                onClick = { commentAttachmentPicker.launch(arrayOf("*/*")) },
                                enabled = !commentAttachmentLoading && actionBusy.isBlank() && commentAttachments.size < NATIVE_CHAT_MAX_ATTACHMENTS,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    Icons.Filled.AttachFile,
                                    contentDescription = if (commentAttachmentLoading) nativePhrase("正在读取附件", "Reading attachment") else nativePhrase("添加评论附件", "Add comment attachment"),
                                    tint = if (!commentAttachmentLoading && commentAttachments.size < NATIVE_CHAT_MAX_ATTACHMENTS) Accent else TextMuted,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                            NativeMentionTextField(
                                value = commentInput,
                                onValueChange = { commentInput = it },
                                candidates = mentionCandidates,
                                loading = mentionLoading,
                                modifier = Modifier.weight(1f),
                                enabled = actionBusy.isBlank(),
                                placeholder = if (replyingTo == null) nativePhrase("写下评论…", "Write a comment...") else nativePhrase("写下回复…", "Write a reply..."),
                            )
                            IconButton(
                                onClick = ::publishComment,
                                enabled = (commentInput.text.isNotBlank() || commentAttachments.isNotEmpty()) && !commentAttachmentLoading && actionBusy.isBlank(),
                            ) {
                                Icon(Icons.Filled.Send, contentDescription = nativePhrase("发布评论", "Post comment"), tint = Accent)
                            }
                        }
                    }
                }
            }
        }
        if (loading && comments.isEmpty()) {
            item { EmptyState(nativePhrase("正在读取评论…", "Loading comments...")) }
        } else if (comments.isEmpty()) {
            item { EmptyState(nativePhrase("还没有评论，来留下第一条吧。", "No comments yet. Start the discussion.")) }
        } else {
            comments.forEach { comment ->
                item(key = "article-comment-${comment.id}") {
                    val parent = comment.parentId?.let { parentId -> comments.firstOrNull { it.id == parentId } }
                    val isReply = comment.parentId != null
                GlassCard(
                    modifier = Modifier
                        .padding(start = if (isReply) 36.dp else 20.dp, end = 20.dp)
                        .fillMaxWidth()
                        .testTag("article-comment-${comment.id}"),
                    shape = HlovetUi.cardShape,
                ) {
                    ArticleCommentRow(
                        comment = comment,
                        parent = parent,
                        enabled = actionBusy.isBlank(),
                        highlighted = highlightCommentId == comment.id,
                        accessToken = session?.accessToken,
                        cacheOwner = NativeOfflineOwner.forSession(session),
                        canDelete = session?.username?.equals(comment.authorUsername, ignoreCase = true) == true || session?.isAdministrator == true || session?.isSuperAdmin == true,
                        canReport = session?.username?.equals(comment.authorUsername, ignoreCase = true) != true,
                        onLike = { toggleCommentLike(comment) },
                        onReply = { replyingTo = comment },
                        onPreviewAttachment = { commentPreviewAttachment = it },
                        onReport = {
                            reportReason = "spam"
                            reportDetail = ""
                            reportingComment = comment
                        },
                        onDelete = { deleteCommentTarget = comment },
                    )
                }
            }
        }
        }
        if (hasMoreComments) {
            item {
                TextButton(
                    onClick = ::loadMoreComments,
                    enabled = !loadingMore,
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                ) { Text(if (loadingMore) nativePhrase("读取中…", "Loading...") else nativePhrase("加载更多评论", "Load more comments")) }
            }
        }
    }

    if (reportingArticle) NativeArticleReportDialog(
        articleTitle = article.title,
        reason = articleReportReason,
        detail = articleReportDetail,
        busy = actionBusy == "article-report",
        onReasonChange = { articleReportReason = it },
        onDetailChange = { articleReportDetail = it },
        onSubmit = ::submitArticleReport,
        onDismiss = { if (actionBusy.isBlank()) reportingArticle = false },
    )
    reportingComment?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (actionBusy.isBlank()) reportingComment = null },
            title = { Text(nativePhrase("举报评论", "Report comment")) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(nativePhrase("请选择举报原因", "Select a reason"), color = TextMuted, fontSize = 12.sp)
                    NATIVE_ARTICLE_REPORT_REASONS.forEach { value ->
                        FilterChip(
                            selected = reportReason == value,
                            onClick = { reportReason = value },
                            label = { Text(nativeArticleReportReasonLabel(value), fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = reportDetail,
                        onValueChange = { reportDetail = it.take(500) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        label = { Text(nativePhrase("补充说明（可选）", "Additional details (optional)"), fontSize = 12.sp) },
                        colors = loginFieldColors(),
                    )
                    Text(nativePhrase("举报对象：${target.authorName}", "Reporting: ${target.authorName}"), color = TextMuted, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = ::reportComment, enabled = actionBusy.isBlank()) {
                    Text(if (actionBusy.startsWith("comment-report")) nativePhrase("提交中…", "Submitting...") else nativePhrase("提交举报", "Submit report"), color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { reportingComment = null }, enabled = actionBusy.isBlank()) { Text(nativePhrase("取消", "Cancel")) }
            },
        )
    }

    deleteCommentTarget?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (actionBusy.isBlank()) deleteCommentTarget = null },
            title = { Text(nativePhrase("删除评论", "Delete comment")) },
            text = { Text(nativePhrase("确定删除这条评论吗？删除后评论内容将无法恢复。", "Delete this comment? Its content cannot be recovered.")) },
            confirmButton = {
                TextButton(onClick = { deleteComment(target) }, enabled = actionBusy.isBlank()) {
                    Text(if (actionBusy.startsWith("comment-delete")) nativePhrase("删除中…", "Deleting...") else nativePhrase("删除", "Delete"), color = Coral)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCommentTarget = null }, enabled = actionBusy.isBlank()) { Text(nativePhrase("取消", "Cancel")) }
            },
        )
    }

    commentPreviewAttachment?.let { attachment ->
        NativeChatImagePreviewDialog(
            attachment = attachment,
            accessToken = session?.accessToken,
            cacheOwner = NativeOfflineOwner.forSession(session),
            onDismiss = { commentPreviewAttachment = null },
        )
    }

    // 文件面板与原图片预览分离，系统保存取消不会影响评论或正文。
    downloadedAttachment?.let { attachment ->
        NativeAttachmentActionsDialog(attachment, onDismiss = { downloadedAttachment = null })
    }
    articlePreviewImage?.let { target ->
        NativeArticleImagePreviewDialog(
            target = target,
            accessToken = session?.accessToken,
            cacheOwner = offlineOwner,
            offlineOnly = showingOfflineContent,
            onDismiss = { articlePreviewImage = null },
        )
    }
}

@Composable
private fun NativeArticleReportDialog(
    articleTitle: String,
    reason: String,
    detail: String,
    busy: Boolean,
    onReasonChange: (String) -> Unit,
    onDetailChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    HlovetAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(nativePhrase("举报文章", "Report article")) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(articleTitle, color = TextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(NATIVE_ARTICLE_REPORT_REASONS, key = { it }) { value ->
                        FilterChip(
                            selected = reason == value,
                            onClick = { onReasonChange(value) },
                            label = { Text(nativeArticleReportReasonLabel(value), fontSize = 11.sp) },
                        )
                    }
                }
                OutlinedTextField(
                    value = detail,
                    onValueChange = { onDetailChange(it.take(500)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    placeholder = { Text(nativePhrase("补充说明（可选）", "Additional details (optional)"), fontSize = 12.sp) },
                    colors = loginFieldColors(),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(nativePhrase("取消", "Cancel")) }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !busy) {
                Text(if (busy) nativePhrase("提交中…", "Submitting...") else nativePhrase("提交举报", "Submit report"), color = Coral)
            }
        },
    )
}

@Composable
private fun ArticleContentPreview(
    article: RemoteArticle,
    busyKey: String,
    isLoggedIn: Boolean,
    accessToken: String?,
    cacheOwner: String,
    offlineOnly: Boolean,
    onLogin: () -> Unit,
    onRedeem: (RemoteArticleSegment) -> Unit,
    onPreviewImage: (NativeArticleImageTarget) -> Unit,
    onOpenAttachment: (NativeArticleAttachmentTarget) -> Unit,
) {
    val segments = article.contentSegments.ifEmpty {
        if (article.content.isBlank()) {
            emptyList()
        } else {
            listOf(RemoteArticleSegment(type = article.contentFormat, content = article.content))
        }
    }
    if (segments.isEmpty()) {
        Text(nativePhrase("这篇文章暂时没有可预览的正文。", "This article has no previewable content."), color = TextMuted, fontSize = 15.sp, lineHeight = 25.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        segments.forEach { segment ->
            if (segment.type == "resource" && !segment.unlocked) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = HlovetUi.rowShape,
                    color = SurfaceLow,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(nativePhrase("积分资源区块", "Points-only resource"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                nativePhrase("兑换后永久解锁 · ${segment.pointCost ?: 0} 积分", "Unlock permanently · ${segment.pointCost ?: 0} points"),
                                color = TextMuted,
                                fontSize = 11.sp,
                            )
                        }
                        FilledTonalButton(
                            onClick = { if (isLoggedIn) onRedeem(segment) else onLogin() },
                            enabled = busyKey.isBlank(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(if (busyKey == "resource:${segment.key}") nativePhrase("兑换中…", "Redeeming...") else nativePhrase("兑换", "Redeem"), fontSize = 12.sp)
                        }
                    }
                }
            } else {
                // The native body renderer sanitizes and formats Markdown/HTML.
                // Do not pass it through the legacy plain-text cleanup, which
                // would remove code, media, and structural HTML first.
                val content = segment.content.orEmpty().trim()
                if (content.isNotBlank()) {
                    NativeArticleBodyWebView(
                        content = content,
                        contentFormat = segment.type,
                        accessToken = accessToken,
                        cacheOwner = cacheOwner,
                        offlineOnly = offlineOnly,
                        onPreviewImage = onPreviewImage,
                        onOpenAttachment = onOpenAttachment,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArticleActionButton(
    label: String,
    count: Int?,
    active: Boolean,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = if (active) Accent else TextMuted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(3.dp))
            Text(
                if (count == null) label else "$label $count",
                color = if (active) Accent else TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

/** 显示 comment 及 parent 回复关系；enabled 控制操作，highlighted 仅提供短暂背景，附件按 accessToken/cacheOwner 隔离。操作回调由阅读页统一处理。 */
@Composable
private fun ArticleCommentRow(
    comment: NativeArticleComment,
    parent: NativeArticleComment?,
    enabled: Boolean,
    highlighted: Boolean,
    accessToken: String?,
    cacheOwner: String,
    canDelete: Boolean,
    canReport: Boolean,
    onLike: () -> Unit,
    onReply: () -> Unit,
    onReport: () -> Unit,
    onDelete: () -> Unit,
    onPreviewAttachment: (NativeChatAttachmentPreview) -> Unit,
) {
    // 不可见评论不显示互动入口，定位使用背景和无障碍状态，不添加边框。
    val unavailable = comment.status != "active"
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (highlighted) Accent.copy(alpha = .14f) else Color.Transparent, HlovetUi.cardShape)
            .semantics { if (highlighted) stateDescription = nativePhrase("已定位的评论", "Located comment") }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 作者行与正文保持同一个缩进，附件播放器沿用正文的可用宽度。
        Row(verticalAlignment = Alignment.Top) {
        Avatar(comment.authorInitials, Accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.authorName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(comment.createdAt, color = TextMuted, fontSize = 10.sp)
            }
            // 回复对象和引用只说明当前评论的上下文，不改变定位目标。
            if (parent != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    nativePhrase("回复 @${parent.authorUsername.ifBlank { parent.authorName }}", "Reply to @${parent.authorUsername.ifBlank { parent.authorName }}"),
                    color = Accent,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            if (comment.quote != null) {
                Surface(
                    color = SurfaceLow,
                    shape = HlovetUi.rowShape,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Text(comment.quote.authorName, color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            nativeMentionText(if (comment.quote.available) {
                                comment.quote.body.ifBlank { nativePhrase("原评论没有文字内容。", "The original comment has no text.") }
                            } else {
                                nativePhrase("原评论已不可见。", "The original comment is unavailable.")
                            }, Accent, HlovetUi.accentSoft),
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // 图片、文件和音视频共用附件入口，认证与播放生命周期在共享组件处理。
            comment.attachments.forEach { attachment ->
                val preview = attachment.toChatAttachmentPreview()
                NativeChatAttachmentItem(
                    attachment = preview,
                    accessToken = accessToken,
                    cacheOwner = cacheOwner,
                    onPreview = { onPreviewAttachment(preview) },
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                nativeMentionText(comment.body, Accent, HlovetUi.accentSoft),
                color = if (unavailable) TextMuted else TextPrimary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
            // 仅当前用户有权进行的互动操作显示按钮，实际权限继续由接口校验。
            if (!unavailable) Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                IconButton(onClick = onLike, enabled = enabled, modifier = Modifier.size(34.dp)) {
                    Icon(
                        if (comment.liked) Icons.Filled.ThumbUp else Icons.Filled.ThumbUpOffAlt,
                        contentDescription = if (comment.liked) nativePhrase("取消评论点赞", "Unlike comment") else nativePhrase("点赞评论", "Like comment"),
                        tint = if (comment.liked) Accent else TextMuted,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Text(comment.likeCount.toString(), color = TextMuted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onReply, enabled = enabled, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.Reply, contentDescription = nativePhrase("回复评论", "Reply to comment"), tint = TextMuted, modifier = Modifier.size(17.dp))
                }
                if (canReport && !comment.reported) {
                    IconButton(onClick = onReport, enabled = enabled, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.Report, contentDescription = nativePhrase("举报评论", "Report comment"), tint = TextMuted, modifier = Modifier.size(17.dp))
                    }
                } else if (canReport) {
                    Icon(Icons.Filled.Flag, contentDescription = nativePhrase("已举报", "Reported"), tint = Accent, modifier = Modifier.padding(horizontal = 8.dp).size(16.dp))
                }
                if (canDelete) {
                    IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = nativePhrase("删除评论", "Delete comment"), tint = Coral, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
    }
}

private fun NativeArticleAttachment.toChatAttachmentPreview(): NativeChatAttachmentPreview = NativeChatAttachmentPreview(
    id = id,
    conversationId = 0,
    kind = kind,
    name = originalName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    downloadUrl = downloadUrl,
    thumbnailUrl = thumbnailUrl,
    createdAt = "",
)

@Composable
private fun DiscoveryDetailScreen(
    target: DetailTarget,
    session: NativeSession?,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onArticleClick: (RemoteArticle) -> Unit,
    onContentChanged: () -> Unit,
) {
    val context = LocalContext.current
    val offlineOwner = NativeOfflineOwner.forSession(session)
    var topic by remember(target) { mutableStateOf((target as? DetailTarget.Topic)?.topic) }
    var collection by remember(target) { mutableStateOf((target as? DetailTarget.Collection)?.collection) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var offlineNotice by remember { mutableStateOf<String?>(null) }
    var showingOfflineContent by remember { mutableStateOf(false) }
    var offlineSaved by remember(target, offlineOwner) { mutableStateOf(false) }
    var offlineBusy by remember { mutableStateOf(false) }
    var subscriptionBusy by remember { mutableStateOf(false) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(target, session?.accessToken, offlineOwner, reloadNonce) {
        loading = true
        error = null
        offlineNotice = null
        showingOfflineContent = false
        val cachedTopic = if (target is DetailTarget.Topic) {
            withContext(Dispatchers.IO) {
                NativeOfflineContentStore.loadTopic(context, offlineOwner, target.topic.slug.ifBlank { target.topic.id.toString() })
            }
        } else null
        val cachedCollection = if (target is DetailTarget.Collection) {
            withContext(Dispatchers.IO) {
                NativeOfflineContentStore.loadCollection(context, offlineOwner, target.collection.id)
            }
        } else null
        offlineSaved = cachedTopic != null || cachedCollection != null
        cachedTopic?.let { topic = it }
        cachedCollection?.let { collection = it }
        try {
            when (target) {
                is DetailTarget.Topic -> {
                    topic = withContext(Dispatchers.IO) {
                        NativeDiscoveryApi.getTopic(session?.accessToken, target.topic.slug.ifBlank { target.topic.title })
                    }
                    collection = null
                }
                is DetailTarget.Collection -> {
                    collection = withContext(Dispatchers.IO) {
                        NativeDiscoveryApi.getCollection(session?.accessToken, target.collection.id)
                    }
                    topic = null
                }
                is DetailTarget.Article -> Unit
            }
        } catch (loadError: Exception) {
            if (cachedTopic != null || cachedCollection != null) {
                showingOfflineContent = true
                offlineNotice = nativePhrase(
                    "当前网络不可用，正在显示已保存的内容。订阅和互动暂不可用。",
                    "You are offline. Saved content is shown, while subscriptions and interactions are unavailable.",
                )
            } else {
                error = loadError.message ?: nativePhrase("专题或合集加载失败，请稍后重试。", "The topic or collection could not be loaded. Try again later.")
            }
        } finally {
            loading = false
        }
    }

    fun saveOffline() {
        if (offlineBusy) return
        val currentTopic = topic
        val currentCollection = collection
        if (currentTopic == null && currentCollection == null) return
        scope.launch {
            offlineBusy = true
            actionError = null
            runCatching {
                withContext(Dispatchers.IO) {
                    if (currentTopic != null) {
                        NativeOfflineContentStore.saveTopic(context, offlineOwner, currentTopic)
                        NativeOfflineCache.prefetchImages(
                            context = context,
                            paths = buildList {
                                currentTopic.coverPath?.let(::add)
                                currentTopic.articles.asSequence().flatMap { it.images.asSequence() }.forEach(::add)
                            },
                            accessToken = session?.accessToken,
                            cacheOwner = offlineOwner,
                            maxItems = 4,
                        )
                    } else {
                        val collection = currentCollection!!
                        NativeOfflineContentStore.saveCollection(context, offlineOwner, collection)
                        NativeOfflineCache.prefetchImages(
                            context = context,
                            paths = buildList {
                                collection.coverPath?.let(::add)
                                collection.articles.asSequence().flatMap { it.images.asSequence() }.forEach(::add)
                            },
                            accessToken = session?.accessToken,
                            cacheOwner = offlineOwner,
                            maxItems = 4,
                        )
                    }
                }
            }.onSuccess {
                offlineSaved = true
                offlineNotice = nativePhrase("已保存到本机，断网时仍可打开。", "Saved on this device for offline access.")
            }.onFailure { actionError = it.message ?: nativePhrase("保存离线内容失败。", "Failed to save offline content.") }
            offlineBusy = false
        }
    }

    val title = topic?.title ?: collection?.name ?: when (target) {
        is DetailTarget.Topic -> target.topic.title
        is DetailTarget.Collection -> target.collection.name
        is DetailTarget.Article -> nativePhrase("内容详情", "Content details")
    }
    val description = topic?.description ?: collection?.description
    val articleCount = topic?.articleCount ?: collection?.articleCount ?: 0
    val subscriberCount = topic?.subscriberCount ?: collection?.subscriberCount ?: 0
    val subscribed = topic?.subscribed ?: collection?.subscribed ?: false
    val articles = topic?.articles ?: collection?.articles.orEmpty()
    val targetColor = if (target is DetailTarget.Collection) Coral else Accent
    val externalFeedPath = when (target) {
        is DetailTarget.Topic -> "/api/distribution/feeds/topics/${Uri.encode(topic?.slug ?: target.topic.slug)}.rss"
        is DetailTarget.Collection -> "/api/distribution/feeds/collections/${collection?.id ?: target.collection.id}.rss"
        is DetailTarget.Article -> null
    }

    fun toggleSubscription() {
        val token = session?.accessToken
        if (token.isNullOrBlank()) {
            onLogin()
            return
        }
        scope.launch {
            subscriptionBusy = true
            actionError = null
            try {
                val result = withContext(Dispatchers.IO) {
                    when (target) {
                        is DetailTarget.Topic -> NativeDiscoveryApi.setTopicSubscription(token, target.topic.id, !subscribed)
                        is DetailTarget.Collection -> NativeDiscoveryApi.setCollectionSubscription(token, target.collection.id, !subscribed)
                        is DetailTarget.Article -> NativeSubscriptionResult(subscribed, subscriberCount)
                    }
                }
                if (topic != null) topic = topic!!.copy(subscribed = result.subscribed, subscriberCount = result.subscriberCount)
                if (collection != null) collection = collection!!.copy(subscribed = result.subscribed, subscriberCount = result.subscriberCount)
                onContentChanged()
            } catch (action: Exception) {
                actionError = action.message ?: nativePhrase("订阅操作失败，请稍后重试。", "The subscription could not be updated. Try again later.")
            } finally {
                subscriptionBusy = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Text(
                        text = if (target is DetailTarget.Collection) nativePhrase("合集详情", "Collection") else nativePhrase("专题详情", "Topic"),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"), modifier = Modifier.size(22.dp))
                    }
                },
                actions = {
                    externalFeedPath?.let { feedPath ->
                        IconButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(nativeMediaUrl(feedPath))))
                                }.onFailure { actionError = nativePhrase("设备上没有可以打开 RSS 订阅的应用。", "No app on this device can open the RSS feed.") }
                            },
                        ) {
                            Icon(Icons.Filled.RssFeed, contentDescription = nativePhrase("站外订阅", "External RSS feed"), tint = targetColor)
                        }
                    }
                    IconButton(
                        onClick = ::saveOffline,
                        enabled = !loading && !offlineBusy && (topic != null || collection != null),
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = if (offlineSaved) nativePhrase("更新离线内容", "Update offline copy") else nativePhrase("保存离线", "Save offline"),
                            tint = if (offlineSaved) targetColor else TextMuted,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            GlassCard(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.cardShape,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NativeCoverImage(
                            path = topic?.coverPath ?: collection?.coverPath,
                            modifier = Modifier.size(68.dp),
                            fallbackText = title,
                            tint = targetColor,
                            accessToken = session?.accessToken,
                            cacheOwner = offlineOwner,
                            offlineOnly = showingOfflineContent,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                nativePhrase("$articleCount 篇文章 · $subscriberCount 人订阅", "$articleCount articles · $subscriberCount subscribers"),
                                color = TextMuted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    if (!description.isNullOrBlank()) {
                        Text(description!!, color = TextMuted, fontSize = 14.sp, lineHeight = 22.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = ::toggleSubscription,
                            enabled = !subscriptionBusy,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                if (subscribed) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (subscribed) nativePhrase("已订阅", "Subscribed") else nativePhrase("订阅", "Subscribe"), fontSize = 12.sp)
                        }
                        if (loading) {
                            Spacer(Modifier.width(10.dp))
                            Text(nativePhrase("正在更新…", "Updating..."), color = TextMuted, fontSize = 11.sp)
                        }
                    }
                    error?.let { message -> Text(message, color = Coral, fontSize = 12.sp) }
                    offlineNotice?.let { message -> Text(message, color = Accent, fontSize = 12.sp) }
                    actionError?.let { message -> Text(message, color = Coral, fontSize = 12.sp) }
                }
            }
        }
        if (error != null) {
            item {
                TextButton(onClick = { reloadNonce += 1 }, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(nativePhrase("重新加载", "Reload"))
                }
            }
        }
        item { SectionHeading(nativePhrase("收录文章", "Articles"), "") }
        item {
            if (articles.isEmpty()) EmptyState(nativePhrase("这个内容集合暂时还没有可见文章", "No visible articles in this collection yet."))
            else ArticleGroup(articles, onArticleClick = onArticleClick)
        }
    }
}

@Composable
private fun HomeScreen(
    scaffoldPadding: PaddingValues,
    session: NativeSession?,
    articles: List<RemoteArticle>,
    onArticleClick: (RemoteArticle) -> Unit,
    onOpenAnnouncements: () -> Unit,
    onOpenAnonymousTopics: () -> Unit,
    onOpenSuggestions: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenWrite: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var homeArticles by remember(articles) { mutableStateOf(articles) }
    var homePage by remember(articles) { mutableIntStateOf(1) }
    var canLoadMore by remember(articles) { mutableStateOf(articles.size >= 8) }
    var loadingMore by remember { mutableStateOf(false) }
    var loadMoreError by remember { mutableStateOf<String?>(null) }
    var recommendationNotice by remember { mutableStateOf<String?>(null) }
    var recommendationNoticeIsError by remember { mutableStateOf(false) }
    var subscriptionArticles by remember(session?.username) { mutableStateOf<List<RemoteArticle>>(emptyList()) }
    var subscriptionsLoading by remember(session?.username) { mutableStateOf(false) }

    LaunchedEffect(session?.accessToken) {
        val token = session?.accessToken
        subscriptionArticles = emptyList()
        if (token.isNullOrBlank()) {
            subscriptionsLoading = false
            return@LaunchedEffect
        }
        subscriptionsLoading = true
        runCatching {
            withContext(Dispatchers.IO) { NativeArticleApi.listSubscriptions(token, page = 1, pageSize = 12).items }
        }.onSuccess { subscriptionArticles = it }
            .onFailure { subscriptionArticles = emptyList() }
        subscriptionsLoading = false
    }

    fun loadMoreHome() {
        if (loadingMore || !canLoadMore) return
        val nextPage = homePage + 1
        loadingMore = true
        loadMoreError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { NativeArticleApi.listHome(session?.accessToken, nextPage, pageSize = 8) }
            }.onSuccess { next ->
                homeArticles = (homeArticles + next.items).distinctBy { it.id }
                homePage = nextPage
                canLoadMore = nextPage < next.totalPages
            }.onFailure { loadMoreError = it.message ?: nativePhrase("加载更多推荐失败，请稍后重试。", "Could not load more recommendations. Try again later.") }
            loadingMore = false
        }
    }

    fun markNotInterested(article: RemoteArticle) {
        val token = session?.accessToken
        if (token.isNullOrBlank()) {
            onLogin()
            return
        }
        scope.launch {
            recommendationNotice = null
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeRecommendationFeedbackApi.record(token, NativeRecommendationTargetType.ARTICLE, article.id)
                }
            }.onSuccess {
                homeArticles = homeArticles.filterNot { it.id == article.id }
                recommendationNoticeIsError = false
                recommendationNotice = nativePhrase("已减少这类推荐。可在个人中心的推荐反馈中恢复。", "You will see fewer recommendations like this. Restore it from Recommendation feedback in your profile.")
            }.onFailure {
                recommendationNoticeIsError = true
                recommendationNotice = it.message ?: nativePhrase("暂时无法更新推荐反馈。", "Recommendation feedback could not be updated.")
            }
        }
    }

    val followingAuthors = subscriptionArticles
        .distinctBy { article -> if (article.authorId > 0) "id:${article.authorId}" else "name:${article.authorUsername.ifBlank { article.authorName }}" }
        .take(8)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            AppHeader(
                title = "HLOVET",
                subtitle = nativePhrase("今天，也为值得留下的内容留一点空间。", "Make a little room for ideas worth keeping."),
                action = {
                    IconButton(onClick = onOpenSearch) { Icon(Icons.Filled.Search, nativePhrase("全站搜索", "Search")) }
                    IconButton(onClick = onOpenAnnouncements) { Icon(Icons.Filled.NotificationsNone, nativePhrase("站点公告", "Announcements")) }
                }
            )
        }
        item { FeaturedEntry(onClick = onOpenWrite) }
        item { SectionHeading(nativePhrase("匿名话题", "Anonymous topics"), nativePhrase("进入", "Open"), onAction = onOpenAnonymousTopics) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable(onClick = onOpenAnonymousTopics),
                shape = HlovetUi.cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nativePhrase("不必留下身份，也可以聊聊", "Join the conversation without revealing your identity"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(nativePhrase("浏览公开话题、参与讨论，或发起一个新的话题", "Browse public topics, join a discussion, or start a new one"), color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = nativePhrase("进入匿名话题", "Open anonymous topics"), tint = TextMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
        item { SectionHeading(nativePhrase("为你推荐", "Recommended for you"), if (canLoadMore) nativePhrase("更多", "More") else "", onAction = if (canLoadMore) ::loadMoreHome else null) }
        item {
            if (homeArticles.isEmpty()) EmptyState(nativePhrase("暂时没有公开文章", "No public articles yet"))
            else ArticleGroup(
                homeArticles,
                onArticleClick = onArticleClick,
                onNotInterested = ::markNotInterested,
            )
        }
        recommendationNotice?.let { message -> item { Text(message, color = if (recommendationNoticeIsError) Coral else Accent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp)) } }
        if (loadingMore) item { Text(nativePhrase("正在加载更多推荐…", "Loading more recommendations…"), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
        loadMoreError?.let { message -> item { Text(message, color = Coral, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 20.dp)) } }
        item { SectionHeading(nativePhrase("正在关注", "Following"), nativePhrase("查看订阅", "View subscriptions"), onAction = onOpenSubscriptions) }
        item {
            when {
                session == null -> Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nativePhrase("登录后查看你订阅作者的最新文章", "Sign in to see the latest articles from authors you follow"), color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onLogin, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(nativePhrase("登录", "Sign in"), fontSize = 12.sp) }
                }
                subscriptionsLoading -> Text(nativePhrase("正在读取订阅动态…", "Loading subscription updates…"), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
                followingAuthors.isEmpty() -> EmptyState(nativePhrase("暂时没有订阅作者的最新文章", "No recent articles from followed authors"))
                else -> FollowingStrip(followingAuthors, onArticleClick)
            }
        }
        item { NativeSuggestionsPreview(onOpenAll = onOpenSuggestions) }
    }
}

@Composable
private fun FeaturedEntry(onClick: () -> Unit) {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
        colors = CardDefaults.cardColors(containerColor = SurfaceAccent),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        ListItem(
            headlineContent = { Text(nativePhrase("从一篇好文章开始", "Start with a good article"), fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            supportingContent = { Text(nativePhrase("把想法整理成可以继续使用的知识。", "Turn your ideas into knowledge you can build on."), color = TextMuted, fontSize = 12.sp) },
            leadingContent = { Avatar("H", Accent, large = true) },
            trailingContent = { Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = Accent) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onClick)
        )
    }
}

@Composable
private fun SectionHeading(title: String, action: String, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Row(
            modifier = if (onAction == null) Modifier else Modifier.clickable(onClick = onAction),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(action, color = TextMuted, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ArticleGroup(
    displayArticles: List<RemoteArticle>,
    onArticleClick: (RemoteArticle) -> Unit = {},
    onNotInterested: ((RemoteArticle) -> Unit)? = null,
) {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            displayArticles.forEachIndexed { index, article ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onArticleClick(article) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(article.authorName.takeLast(2), Coral)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(article.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(article.authorName, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(" · ", color = TextMuted, fontSize = 11.sp)
                            Text(articleReadTime(article), color = TextMuted, fontSize = 11.sp)
                            Spacer(Modifier.width(7.dp))
                            Text(article.category, color = Accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onNotInterested != null) {
                            IconButton(onClick = { onNotInterested(article) }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Filled.VisibilityOff, contentDescription = nativePhrase("不感兴趣", "Not interested"), tint = TextMuted, modifier = Modifier.size(17.dp))
                            }
                        }
                        Icon(Icons.Filled.BookmarkBorder, contentDescription = null, tint = TextMuted, modifier = Modifier.size(21.dp))
                    }
                }
                if (index < displayArticles.lastIndex) {
                    HorizontalDivider(color = HlovetUi.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun FollowingStrip(
    latestArticles: List<RemoteArticle>,
    onArticleClick: (RemoteArticle) -> Unit,
) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(latestArticles, key = { article -> "${article.authorId}:${article.authorUsername}:${article.slug}" }) { article ->
            AssistChip(
                onClick = { onArticleClick(article) },
                label = { Text(article.authorName, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Avatar(article.authorName.takeLast(2), Accent, compact = true) },
                colors = AssistChipDefaults.assistChipColors(containerColor = HlovetUi.surface),
                border = null
            )
        }
    }
}

@Composable
private fun DiscoverScreen(
    scaffoldPadding: PaddingValues,
    session: NativeSession?,
    initialMode: String = "推荐",
    initialQuery: String = "",
    articles: List<RemoteArticle>,
    topics: List<RemoteTopic>,
    collections: List<RemoteCollection>,
    onLogin: () -> Unit,
    onResourceCenterClick: () -> Unit = {},
    onTopicClick: (RemoteTopic) -> Unit = {},
    onCollectionClick: (RemoteCollection) -> Unit = {},
    onArticleClick: (RemoteArticle) -> Unit = {},
    onOpenUser: (String) -> Unit = {},
    onOpenGroup: (NativeRecommendationGroup) -> Unit = {},
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var searchResult by remember { mutableStateOf<NativeDiscoverySearchResult?>(null) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var subscriptionError by remember { mutableStateOf<String?>(null) }
    var loadMoreError by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var discoveryMode by remember(initialMode) { mutableStateOf(initialMode) }
    var articleSort by remember { mutableStateOf("recommended") }
    var articleSortDialogOpen by remember { mutableStateOf(false) }
    var discoveryPage by remember(articles, topics, collections) { mutableIntStateOf(1) }
    var canLoadMore by remember(articles, topics, collections) {
        mutableStateOf(articles.size >= 8 || topics.size >= 8 || collections.size >= 8)
    }
    var localArticles by remember(articles) { mutableStateOf(articles) }
    var localTopics by remember(topics) { mutableStateOf(topics) }
    var localCollections by remember(collections) { mutableStateOf(collections) }
    var recommendations by remember(session?.accessToken) { mutableStateOf<NativeDiscoveryRecommendations?>(null) }
    var recommendationsLoading by remember(session?.accessToken) { mutableStateOf(false) }
    var recommendationsError by remember(session?.accessToken) { mutableStateOf<String?>(null) }
    var recommendationActionBusy by remember(session?.accessToken) { mutableStateOf(false) }
    var joinRequestTarget by remember(session?.accessToken) { mutableStateOf<NativeRecommendationGroup?>(null) }
    var joinRequestNote by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session?.accessToken) {
        recommendations = null
        recommendationsError = null
        val token = session?.accessToken
        if (token.isNullOrBlank()) {
            recommendationsLoading = false
            return@LaunchedEffect
        }
        recommendationsLoading = true
        runCatching {
            withContext(Dispatchers.IO) { NativeDiscoveryApi.listRecommendations(token) }
        }.onSuccess { recommendations = it }
            .onFailure { recommendationsError = it.message ?: nativePhrase("个性化推荐暂时无法加载。", "Personalized recommendations are temporarily unavailable.") }
        recommendationsLoading = false
    }

    LaunchedEffect(query, session?.accessToken) {
        val keyword = query.trim()
        if (keyword.length < 2) {
            searchResult = null
            searchError = null
            searchLoading = false
            return@LaunchedEffect
        }
        delay(350)
        searchLoading = true
        searchError = null
        try {
            searchResult = withContext(Dispatchers.IO) {
                NativeDiscoveryApi.search(session?.accessToken, keyword)
            }
        } catch (error: Exception) {
            searchResult = null
            searchError = error.message ?: nativePhrase("搜索失败，请稍后重试。", "Search failed. Try again later.")
        } finally {
            searchLoading = false
        }
    }

    val toggleTopic: (RemoteTopic) -> Unit = { topic ->
        val token = session?.accessToken
        if (token.isNullOrBlank()) {
            onLogin()
        } else {
            scope.launch {
                subscriptionError = null
                try {
                    val result = withContext(Dispatchers.IO) {
                        NativeDiscoveryApi.setTopicSubscription(token, topic.id, !topic.subscribed)
                    }
                    localTopics = localTopics.map {
                        if (it.id == topic.id) it.copy(
                            subscribed = result.subscribed,
                            subscriberCount = result.subscriberCount,
                        ) else it
                    }
                } catch (error: Exception) {
                    subscriptionError = error.message ?: nativePhrase("专题订阅操作失败。", "The topic subscription could not be updated.")
                }
            }
        }
    }

    val toggleCollection: (RemoteCollection) -> Unit = { collection ->
        val token = session?.accessToken
        if (token.isNullOrBlank()) {
            onLogin()
        } else {
            scope.launch {
                subscriptionError = null
                try {
                    val result = withContext(Dispatchers.IO) {
                        NativeDiscoveryApi.setCollectionSubscription(token, collection.id, !collection.subscribed)
                    }
                    localCollections = localCollections.map {
                        if (it.id == collection.id) it.copy(
                            subscribed = result.subscribed,
                            subscriberCount = result.subscriberCount,
                        ) else it
                    }
                } catch (error: Exception) {
                    subscriptionError = error.message ?: nativePhrase("合集订阅操作失败。", "The collection subscription could not be updated.")
                }
            }
        }
    }

    fun refreshRecommendations() {
        val token = session?.accessToken ?: return
        if (recommendationActionBusy) return
        recommendationActionBusy = true
        recommendationsError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeDiscoveryApi.listRecommendations(token, recommendations?.let { if (it.hasMore) it.batch + 1 else 0 } ?: 0)
                }
            }.onSuccess { recommendations = it }
                .onFailure { recommendationsError = it.message ?: nativePhrase("推荐刷新失败，请稍后重试。", "Recommendations could not be refreshed. Try again later.") }
            recommendationActionBusy = false
        }
    }

    fun markRecommendationNotInterested(targetType: String, targetId: Int) {
        val token = session?.accessToken ?: return
        if (recommendationActionBusy) return
        recommendationActionBusy = true
        recommendationsError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeDiscoveryApi.recordRecommendationFeedback(token, targetType, targetId)
                }
            }.onSuccess {
                recommendations = recommendations?.let { current ->
                    current.copy(
                        topics = if (targetType == "topic") current.topics.filterNot { it.id == targetId } else current.topics,
                        collections = if (targetType == "collection") current.collections.filterNot { it.id == targetId } else current.collections,
                        authors = if (targetType == "author") current.authors.filterNot { it.author.id == targetId } else current.authors,
                        groups = if (targetType == "group") current.groups.filterNot { it.id == targetId } else current.groups,
                    )
                }
            }.onFailure { recommendationsError = it.message ?: nativePhrase("推荐反馈保存失败。", "Recommendation feedback could not be saved.") }
            recommendationActionBusy = false
        }
    }

    fun toggleRecommendedTopic(topic: NativeRecommendationTopic) {
        val token = session?.accessToken ?: return
        if (recommendationActionBusy) return
        recommendationActionBusy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeDiscoveryApi.setTopicSubscription(token, topic.id, !topic.subscribed)
                }
            }.onSuccess { result ->
                recommendations = recommendations?.copy(
                    topics = recommendations?.topics.orEmpty().map {
                        if (it.id == topic.id) it.copy(subscribed = result.subscribed, subscriberCount = result.subscriberCount) else it
                    },
                )
            }.onFailure { recommendationsError = it.message ?: nativePhrase("专题订阅操作失败。", "The topic subscription could not be updated.") }
            recommendationActionBusy = false
        }
    }

    fun toggleRecommendedCollection(collection: NativeRecommendationCollection) {
        val token = session?.accessToken ?: return
        if (recommendationActionBusy) return
        recommendationActionBusy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeDiscoveryApi.setCollectionSubscription(token, collection.id, !collection.subscribed)
                }
            }.onSuccess { result ->
                recommendations = recommendations?.copy(
                    collections = recommendations?.collections.orEmpty().map {
                        if (it.id == collection.id) it.copy(subscribed = result.subscribed, subscriberCount = result.subscriberCount) else it
                    },
                )
            }.onFailure { recommendationsError = it.message ?: nativePhrase("合集订阅操作失败。", "The collection subscription could not be updated.") }
            recommendationActionBusy = false
        }
    }

    fun toggleRecommendedAuthor(author: NativeRecommendationAuthorItem) {
        val token = session?.accessToken ?: return
        if (recommendationActionBusy) return
        recommendationActionBusy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeFriendsApi.setAuthorSubscription(token, author.author.id, !author.subscribed)
                }
            }.onSuccess {
                recommendations = recommendations?.copy(
                    authors = recommendations?.authors.orEmpty().map {
                        if (it.author.id == author.author.id) it.copy(subscribed = !author.subscribed) else it
                    },
                )
            }.onFailure { recommendationsError = it.message ?: nativePhrase("作者订阅操作失败。", "The author subscription could not be updated.") }
            recommendationActionBusy = false
        }
    }

    fun submitJoinRequest() {
        val token = session?.accessToken ?: return
        val target = joinRequestTarget ?: return
        if (recommendationActionBusy) return
        recommendationActionBusy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeGroupApi.requestJoin(token, target.id, joinRequestNote)
                }
            }.onSuccess {
                recommendations = recommendations?.copy(groups = recommendations?.groups.orEmpty().filterNot { it.id == target.id })
                joinRequestTarget = null
                joinRequestNote = ""
            }.onFailure { recommendationsError = it.message ?: nativePhrase("入群申请提交失败。", "The group join request could not be submitted.") }
            recommendationActionBusy = false
        }
    }

    fun discoverySort(mode: String = discoveryMode, sortOverride: String? = null): String = when (mode) {
        "热门" -> "popular"
        "最新" -> "latest"
        "文章" -> normalizeNativeArticleSort(sortOverride ?: articleSort)
        else -> "recommended"
    }

    fun loadDiscoveryMode(mode: String, force: Boolean = false, sortOverride: String? = null) {
        if (loadingMore || !force && mode == discoveryMode && discoveryPage == 1) return
        discoveryMode = mode
        val requestSort = discoverySort(mode, sortOverride)
        loadingMore = true
        loadMoreError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeDiscoveryApi.listDiscoveryPage(session?.accessToken, page = 1, pageSize = 8, sort = requestSort)
                }
            }.onSuccess { first ->
                localArticles = first.articles
                localTopics = first.topics
                localCollections = first.collections
                discoveryPage = 1
                canLoadMore = first.totalPages > 1
            }.onFailure { loadMoreError = it.message ?: nativePhrase("切换发现内容失败，请稍后重试。", "Discover content could not be switched. Try again later.") }
            loadingMore = false
        }
    }

    LaunchedEffect(initialMode) {
        if (initialMode != "推荐") loadDiscoveryMode(initialMode, force = true)
    }

    fun loadMoreDiscovery() {
        if (loadingMore || !canLoadMore) return
        val nextPage = discoveryPage + 1
        loadingMore = true
        loadMoreError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeDiscoveryApi.listDiscoveryPage(session?.accessToken, nextPage, pageSize = 8, sort = discoverySort())
                }
            }.onSuccess { next ->
                localArticles = (localArticles + next.articles).distinctBy { it.id }
                localTopics = (localTopics + next.topics).distinctBy { it.id }
                localCollections = (localCollections + next.collections).distinctBy { it.id }
                discoveryPage = nextPage
                canLoadMore = nextPage < next.totalPages
            }.onFailure { loadMoreError = it.message ?: nativePhrase("加载更多内容失败，请稍后重试。", "More content could not be loaded. Try again later.") }
            loadingMore = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(scaffoldPadding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            AppHeader(
                nativePhrase("发现", "Discover"),
                nativePhrase("找到正在发生的内容", "Find what is happening now"),
                action = {
                    IconButton(onClick = onResourceCenterClick) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = nativePhrase("资源中心", "Resource center"), tint = Accent)
                    }
                },
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = nativePhrase("搜索", "Search"), tint = TextMuted) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = nativePhrase("清除搜索", "Clear search"), tint = TextMuted)
                        }
                    }
                },
                placeholder = { Text(nativePhrase("搜索文章、专题和合集", "Search articles, topics, and collections"), color = TextMuted, fontSize = 13.sp) },
                colors = HlovetUi.fieldColors(
                    focusedBorderColor = Accent,
                ),
            )
        }
        if (query.trim().length >= 2) {
            item {
                when {
                    searchLoading -> Text(nativePhrase("正在搜索…", "Searching…"), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
                    searchError != null -> Text(searchError!!, color = Coral, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
                    else -> Text(nativePhrase("搜索结果", "Search results"), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
            val result = searchResult
            if (result != null) {
                if (result.articles.isNotEmpty()) {
                    item { SectionHeading(nativePhrase("文章", "Articles"), "") }
                    item { ArticleGroup(result.articles, onArticleClick = onArticleClick) }
                }
                if (result.topics.isNotEmpty()) {
                    item { SectionHeading(nativePhrase("专题", "Topics"), "") }
                    items(result.topics) { topic ->
                        TopicRow(
                            title = topic.title,
                            meta = nativePhrase("${topic.articleCount} 篇文章 · ${topic.subscriberCount} 人订阅", "${topic.articleCount} articles · ${topic.subscriberCount} subscribers"),
                            color = Accent,
                            articles = topic.articles,
                            coverPath = topic.coverPath,
                            subscribed = topic.subscribed,
                            onClick = { onTopicClick(topic) },
                            onSubscribe = { toggleTopic(topic) },
                        )
                    }
                }
                if (result.collections.isNotEmpty()) {
                    item { SectionHeading(nativePhrase("合集", "Collections"), "") }
                    items(result.collections) { collection ->
                        TopicRow(
                            title = collection.name,
                            meta = nativePhrase("${collection.articleCount} 篇文章 · ${collection.subscriberCount} 人订阅", "${collection.articleCount} articles · ${collection.subscriberCount} subscribers"),
                            color = Coral,
                            articles = collection.articles,
                            coverPath = collection.coverPath,
                            subscribed = collection.subscribed,
                            onClick = { onCollectionClick(collection) },
                            onSubscribe = { toggleCollection(collection) },
                        )
                    }
                }
                if (result.articles.isEmpty() && result.topics.isEmpty() && result.collections.isEmpty()) {
                    item { EmptyState(nativePhrase("没有找到匹配内容", "No matching content found")) }
                }
            }
        } else {
            if (discoveryMode == "推荐") {
                item {
                    NativeRecommendationPanel(
                        session = session,
                        recommendations = recommendations,
                        loading = recommendationsLoading,
                        error = recommendationsError,
                        actionBusy = recommendationActionBusy,
                        onLogin = onLogin,
                        onRefresh = ::refreshRecommendations,
                        onFeedback = ::markRecommendationNotInterested,
                        onToggleTopic = ::toggleRecommendedTopic,
                        onToggleCollection = ::toggleRecommendedCollection,
                        onToggleAuthor = ::toggleRecommendedAuthor,
                        onOpenTopic = { topic ->
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { NativeDiscoveryApi.getTopic(session?.accessToken, topic.slug.ifBlank { topic.title }) }
                                }.onSuccess(onTopicClick)
                                    .onFailure { recommendationsError = it.message ?: nativePhrase("专题暂时无法打开。", "The topic is temporarily unavailable.") }
                            }
                        },
                        onOpenCollection = { collection ->
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { NativeDiscoveryApi.getCollection(session?.accessToken, collection.id) }
                                }.onSuccess(onCollectionClick)
                                    .onFailure { recommendationsError = it.message ?: nativePhrase("合集暂时无法打开。", "The collection is temporarily unavailable.") }
                            }
                        },
                        onOpenUser = onOpenUser,
                        onOpenGroup = { group ->
                            if (group.isMember) onOpenGroup(group) else joinRequestTarget = group
                        },
                    )
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("推荐", "热门", "最新", "文章", "专题", "合集")) { mode ->
                        FilterChip(
                            selected = mode == discoveryMode,
                            onClick = { loadDiscoveryMode(mode) },
                            label = { Text(nativeDiscoveryModeLabel(mode), fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Accent,
                                selectedLabelColor = HlovetUi.onAccent,
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            border = null,
                        )
                    }
                }
            }
            if (discoveryMode == "文章") {
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        FilterChip(
                            selected = articleSort != "recommended",
                            onClick = { articleSortDialogOpen = true },
                            label = {
                                Text(
                                    nativeArticleSortDisplayLabel(articleSort),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            border = null,
                        )
                    }
                }
            }
            val visibility = nativeDiscoveryVisibility(discoveryMode)
            val showTopics = visibility.showTopics
            val showCollections = visibility.showCollections
            val showArticles = visibility.showArticles
            item {
                SectionHeading(
                    when (discoveryMode) {
                        "文章", "热门", "最新" -> if (discoveryMode == "热门") nativePhrase("热门文章", "Popular articles") else if (discoveryMode == "最新") nativePhrase("最新文章", "Latest articles") else nativePhrase("文章", "Articles")
                        "专题" -> nativePhrase("专题", "Topics")
                        "合集" -> nativePhrase("合集", "Collections")
                        else -> nativePhrase("专题与合集", "Topics and collections")
                    },
                    "",
                )
            }
            if ((showTopics && localTopics.isEmpty()) && (showCollections && localCollections.isEmpty()) && (!showArticles || localArticles.isEmpty())) {
                item { EmptyState(if (showArticles && !showTopics && !showCollections) nativePhrase("暂无可展示的文章", "No articles to show") else nativePhrase("暂时没有公开专题或合集", "No public topics or collections yet")) }
            } else {
                if (showTopics) items(localTopics.take(discoveryPage * 3)) { topic ->
                    TopicRow(
                        title = topic.title,
                        meta = nativePhrase("专题 · ${topic.articleCount} 篇文章 · ${topic.subscriberCount} 人订阅", "Topic · ${topic.articleCount} articles · ${topic.subscriberCount} subscribers"),
                        color = Accent,
                        articles = topic.articles,
                        coverPath = topic.coverPath,
                        subscribed = topic.subscribed,
                        onClick = { onTopicClick(topic) },
                        onSubscribe = { toggleTopic(topic) },
                    )
                }
                if (showCollections) items(localCollections.take(discoveryPage * 3)) { collection ->
                    TopicRow(
                        title = collection.name,
                        meta = nativePhrase("合集 · ${collection.articleCount} 篇文章 · ${collection.subscriberCount} 人订阅", "Collection · ${collection.articleCount} articles · ${collection.subscriberCount} subscribers"),
                        color = Coral,
                        articles = collection.articles,
                        coverPath = collection.coverPath,
                        subscribed = collection.subscribed,
                        onClick = { onCollectionClick(collection) },
                        onSubscribe = { toggleCollection(collection) },
                    )
                }
            }
            loadMoreError?.let { message ->
                item { Text(message, color = Coral, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
            }
            subscriptionError?.let { message ->
                item { Text(message, color = Coral, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp)) }
            }
            if (showArticles) {
                if (discoveryMode != "文章") {
                    item { SectionHeading(if (discoveryMode == "热门") nativePhrase("热门文章", "Popular articles") else if (discoveryMode == "最新") nativePhrase("最新文章", "Latest articles") else nativePhrase("最近更新", "Recently updated"), "") }
                }
                item {
                    val recent = localArticles.take(discoveryPage * 4)
                    if (recent.isEmpty()) EmptyState(nativePhrase("暂无可展示的文章", "No articles to show"))
                    else ArticleGroup(recent, onArticleClick = onArticleClick)
                }
            }
            if (canLoadMore || loadingMore) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = ::loadMoreDiscovery, enabled = !loadingMore) {
                            Text(if (loadingMore) nativePhrase("正在加载…", "Loading…") else nativePhrase("加载更多", "Load more"), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    if (articleSortDialogOpen) {
        HlovetAlertDialog(
            onDismissRequest = { articleSortDialogOpen = false },
            title = { Text(nativePhrase("文章排序", "Article sorting")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    nativeArticleSortOptions.forEach { option ->
                        TextButton(
                            onClick = {
                                articleSort = option.value
                                articleSortDialogOpen = false
                                loadDiscoveryMode("文章", force = true, sortOverride = option.value)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(nativeArticleSortDisplayLabel(option.value), fontSize = 13.sp)
                                if (articleSort == option.value) {
                                    Icon(Icons.Filled.Favorite, contentDescription = nativePhrase("当前排序", "Current sorting"), tint = Accent, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { articleSortDialogOpen = false }) { Text(nativePhrase("取消", "Cancel")) }
            },
        )
    }

    joinRequestTarget?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = {
                if (!recommendationActionBusy) {
                    joinRequestTarget = null
                    joinRequestNote = ""
                }
            },
            title = { Text(nativePhrase("申请加入${target.name}", "Request to join ${target.name}")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(nativePhrase("这是一个需要审核的群聊，申请备注可选。", "This group requires approval. A note is optional."), color = TextMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = joinRequestNote,
                        onValueChange = { joinRequestNote = it.take(200) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        label = { Text(nativePhrase("申请备注", "Request note")) },
                        placeholder = { Text(nativePhrase("简单说明一下加入原因", "Briefly explain why you would like to join"), fontSize = 12.sp) },
                        colors = HlovetUi.fieldColors(),
                        enabled = !recommendationActionBusy,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { joinRequestTarget = null; joinRequestNote = "" },
                    enabled = !recommendationActionBusy,
                ) { Text(nativePhrase("取消", "Cancel")) }
            },
            confirmButton = {
                TextButton(onClick = ::submitJoinRequest, enabled = !recommendationActionBusy) {
                    Text(if (recommendationActionBusy) nativePhrase("提交中…", "Submitting…") else nativePhrase("提交申请", "Submit request"))
                }
            },
        )
    }
}

@Composable
private fun NativeRecommendationPanel(
    session: NativeSession?,
    recommendations: NativeDiscoveryRecommendations?,
    loading: Boolean,
    error: String?,
    actionBusy: Boolean,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onFeedback: (String, Int) -> Unit,
    onToggleTopic: (NativeRecommendationTopic) -> Unit,
    onToggleCollection: (NativeRecommendationCollection) -> Unit,
    onToggleAuthor: (NativeRecommendationAuthorItem) -> Unit,
    onOpenTopic: (NativeRecommendationTopic) -> Unit,
    onOpenCollection: (NativeRecommendationCollection) -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenGroup: (NativeRecommendationGroup) -> Unit,
) {
    GlassCard(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Accent, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(nativePhrase("为你推荐", "Recommended for you"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                if (session != null) {
                    IconButton(onClick = onRefresh, enabled = !loading && !actionBusy, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新推荐", "Refresh recommendations"), tint = TextMuted, modifier = Modifier.size(17.dp))
                    }
                }
            }
            when {
                session == null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(nativePhrase("登录后根据你的阅读和订阅推荐内容", "Sign in for recommendations based on your reading and subscriptions"), color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = onLogin, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                            Text(nativePhrase("登录", "Sign in"), fontSize = 12.sp)
                        }
                    }
                }
                loading && recommendations == null -> Text(nativePhrase("正在生成推荐…", "Building recommendations…"), color = TextMuted, fontSize = 12.sp)
                error != null && recommendations == null -> Text(error, color = Coral, fontSize = 12.sp)
                recommendations != null -> {
                    val current = recommendations
                    NativeRecommendationSection(
                        title = nativePhrase("专题", "Topics"),
                        icon = Icons.Filled.LibraryBooks,
                        visible = current.topics.take(3),
                    ) { topic ->
                        NativeRecommendationRow(
                            title = topic.title,
                            detail = nativePhrase("${topic.articleCount} 篇文章 · ${topic.subscriberCount} 人订阅", "${topic.articleCount} articles · ${topic.subscriberCount} subscribers"),
                            reason = nativeRecommendationReasonDisplayText(topic.reasons),
                            avatarPath = topic.coverPath,
                            fallbackText = topic.title,
                            tint = Accent,
                            actionIcon = if (topic.subscribed) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone,
                            actionLabel = if (topic.subscribed) nativePhrase("取消订阅", "Unsubscribe") else nativePhrase("订阅", "Subscribe"),
                            actionTint = if (topic.subscribed) Accent else TextMuted,
                            enabled = !actionBusy,
                            onOpen = { onOpenTopic(topic) },
                            onAction = { onToggleTopic(topic) },
                            onFeedback = { onFeedback("topic", topic.id) },
                        )
                    }
                    NativeRecommendationSection(
                        title = nativePhrase("合集", "Collections"),
                        icon = Icons.Filled.FolderOpen,
                        visible = current.collections.take(3),
                    ) { collection ->
                        NativeRecommendationRow(
                            title = collection.name,
                            detail = nativePhrase("${collection.articleCount} 篇文章 · ${collection.subscriberCount} 人订阅", "${collection.articleCount} articles · ${collection.subscriberCount} subscribers"),
                            reason = nativeRecommendationReasonDisplayText(collection.reasons),
                            avatarPath = null,
                            fallbackText = collection.name,
                            tint = Coral,
                            actionIcon = if (collection.subscribed) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone,
                            actionLabel = if (collection.subscribed) nativePhrase("取消订阅", "Unsubscribe") else nativePhrase("订阅", "Subscribe"),
                            actionTint = if (collection.subscribed) Accent else TextMuted,
                            enabled = !actionBusy,
                            onOpen = { onOpenCollection(collection) },
                            onAction = { onToggleCollection(collection) },
                            onFeedback = { onFeedback("collection", collection.id) },
                        )
                    }
                    NativeRecommendationSection(
                        title = nativePhrase("作者", "Authors"),
                        icon = Icons.Filled.PersonOutline,
                        visible = current.authors.take(3),
                    ) { author ->
                        NativeRecommendationRow(
                            title = author.author.nickname,
                            detail = nativePhrase("${author.topCategory} · ${author.articleCount} 篇文章", "${author.topCategory} · ${author.articleCount} articles"),
                            reason = nativeRecommendationReasonDisplayText(author.reasons),
                            avatarPath = author.author.avatarUrl,
                            fallbackText = author.author.nickname.ifBlank { author.author.username },
                            tint = Accent,
                            actionIcon = if (author.subscribed) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone,
                            actionLabel = if (author.subscribed) nativePhrase("取消订阅", "Unsubscribe") else nativePhrase("订阅", "Subscribe"),
                            actionTint = if (author.subscribed) Accent else TextMuted,
                            enabled = !actionBusy,
                            onOpen = { onOpenUser(author.author.username) },
                            onAction = { onToggleAuthor(author) },
                            onFeedback = { onFeedback("author", author.author.id) },
                        )
                    }
                    NativeRecommendationSection(
                        title = nativePhrase("群聊", "Group chats"),
                        icon = Icons.Filled.Groups,
                        visible = current.groups.take(3),
                    ) { group ->
                        NativeRecommendationRow(
                            title = group.name,
                            detail = nativePhrase("${group.memberCount} 位成员 · ${if (group.joinMode == "approval") "申请加入" else "邀请加入"}", "${group.memberCount} members · ${if (group.joinMode == "approval") "Request to join" else "Invite only"}"),
                            reason = nativeRecommendationReasonDisplayText(group.reasons),
                            avatarPath = group.avatarUrl,
                            fallbackText = group.name,
                            tint = Coral,
                            actionIcon = if (group.isMember) Icons.Filled.ChatBubbleOutline else Icons.Filled.PersonOutline,
                            actionLabel = if (group.isMember) nativePhrase("打开群聊", "Open group") else nativePhrase("申请加入", "Request to join"),
                            actionTint = if (group.isMember) Accent else TextMuted,
                            enabled = !actionBusy,
                            onOpen = { onOpenGroup(group) },
                            onAction = { onOpenGroup(group) },
                            onFeedback = { onFeedback("group", group.id) },
                        )
                    }
                    if (error != null) Text(error, color = Coral, fontSize = 11.sp)
                    if (!current.hasMore && current.topics.isEmpty() && current.collections.isEmpty() && current.authors.isEmpty() && current.groups.isEmpty()) {
                        Text(nativePhrase("暂时没有新的个性化推荐。", "No new personalized recommendations yet."), color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> NativeRecommendationSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    visible: List<T>,
    content: @Composable (T) -> Unit,
) {
    if (visible.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(title, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
    visible.forEach { content(it) }
}

@Composable
private fun NativeRecommendationRow(
    title: String,
    detail: String,
    reason: String,
    avatarPath: String?,
    fallbackText: String,
    tint: Color,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    actionLabel: String,
    actionTint: Color,
    enabled: Boolean,
    onOpen: () -> Unit,
    onAction: () -> Unit,
    onFeedback: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NativeCoverImage(
            path = avatarPath,
            modifier = Modifier.size(34.dp),
            fallbackText = fallbackText,
            tint = tint,
        )
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (reason.isNotBlank()) Text(reason, color = Accent, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onAction, enabled = enabled, modifier = Modifier.size(30.dp)) {
            Icon(actionIcon, contentDescription = actionLabel, tint = actionTint, modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onFeedback, enabled = enabled, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Filled.VisibilityOff, contentDescription = nativePhrase("不感兴趣", "Not interested"), tint = TextMuted, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun TopicRow(
    title: String,
    meta: String,
    color: Color,
    articles: List<RemoteArticle>,
    coverPath: String? = null,
    subscribed: Boolean = false,
    onClick: () -> Unit = {},
    onSubscribe: () -> Unit = {},
) {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable { onClick() },
        shape = HlovetUi.rowShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(meta, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = {
                NativeCoverImage(
                    path = coverPath,
                    modifier = Modifier.size(48.dp),
                    fallbackText = title,
                    tint = color,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onSubscribe, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (subscribed) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone,
                            contentDescription = if (subscribed) nativePhrase("取消订阅", "Unsubscribe") else nativePhrase("订阅", "Subscribe"),
                            tint = if (subscribed) Accent else TextMuted,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = nativePhrase("打开详情", "Open details"), tint = TextMuted)
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun MessagesScreen(
    padding: PaddingValues,
    session: NativeSession?,
    refreshKey: Int,
    onLogin: () -> Unit,
    onNotice: (String) -> Unit,
    onOpenPeople: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenConversation: (NativeConversationPreview) -> Unit,
    onOpenNotification: (NativeNotificationPreview, NativeConversationPreview?) -> Unit,
    onInboxSummaryChanged: (NativeInboxSummary) -> Unit,
    onEnableNativeNotifications: () -> Unit,
) {
    val sessionKey = session?.refreshToken?.substringBefore('.')?.takeIf { it.isNotBlank() }
        ?: session?.username.orEmpty()
    var inbox by remember(sessionKey) { mutableStateOf<NativeInbox?>(null) }
    var loading by remember(sessionKey) { mutableStateOf(false) }
    var error by remember(sessionKey) { mutableStateOf<String?>(null) }
    var localRefreshKey by remember(sessionKey) { mutableIntStateOf(0) }
    var notificationSettingsOpen by remember(sessionKey) { mutableStateOf(false) }
    var notificationSettingsBusy by remember(sessionKey) { mutableStateOf<String?>(null) }
    var notificationSettingsError by remember(sessionKey) { mutableStateOf<String?>(null) }
    var notificationDeleteTarget by remember(sessionKey) { mutableStateOf<NativeNotificationPreview?>(null) }
    var notificationDeleteSelection by remember(sessionKey) { mutableStateOf<Set<Int>?>(null) }
    var notificationClearChannel by remember(sessionKey) { mutableStateOf<String?>(null) }
    var notificationActionBusy by remember(sessionKey) { mutableStateOf(false) }
    var notificationSelectionMode by remember(sessionKey) { mutableStateOf(false) }
    var selectedNotificationIds by remember(sessionKey) { mutableStateOf<Set<Int>>(emptySet()) }
    var notificationLoadingMore by remember(sessionKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val notificationChannelOptions = listOf(
        Triple("system", nativePhrase("系统消息", "System"), nativePhrase("好友申请结果和内容处理通知", "Friend request results and content moderation updates")),
        Triple("subscription", nativePhrase("订阅更新", "Subscriptions"), nativePhrase("订阅作者发布的新内容", "New content from authors you follow")),
        Triple("interaction", nativePhrase("互动消息", "Interactions"), nativePhrase("点赞、收藏、评论和新订阅", "Likes, favorites, comments, and new subscribers")),
    )

    LaunchedEffect(session?.accessToken, refreshKey, localRefreshKey) {
        val accessToken = session?.accessToken ?: run {
            inbox = null
            notificationSelectionMode = false
            selectedNotificationIds = emptySet()
            notificationDeleteSelection = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val refreshed = withContext(Dispatchers.IO) { NativeSocialApi.loadInbox(accessToken) }
            inbox = refreshed
            notificationSelectionMode = false
            selectedNotificationIds = emptySet()
            notificationDeleteSelection = null
            onInboxSummaryChanged(refreshed.toSummary())
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase("消息加载失败，请稍后重试。", "Messages could not be loaded. Try again later.")
        } finally {
            loading = false
        }
    }

    fun deleteNotification(target: NativeNotificationPreview) {
        val accessToken = session?.accessToken ?: return
        notificationActionBusy = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { NativeSocialApi.deleteNotification(accessToken, target.id) } }
                .onSuccess {
                    val updated = inbox?.copy(
                        unreadNotifications = ((inbox?.unreadNotifications ?: 0) - if (target.unread) 1 else 0).coerceAtLeast(0),
                        notifications = inbox?.notifications.orEmpty().filterNot { it.id == target.id },
                    )
                    inbox = updated
                    selectedNotificationIds = selectedNotificationIds - target.id
                    if (selectedNotificationIds.isEmpty()) notificationSelectionMode = false
                    updated?.let { onInboxSummaryChanged(it.toSummary()) }
                    notificationDeleteTarget = null
                    onNotice(nativePhrase("通知已删除。", "Notification deleted."))
                }
                .onFailure { onNotice(it.message ?: nativePhrase("通知删除失败。", "Failed to delete the notification.")) }
            notificationActionBusy = false
        }
    }

    fun handleNotificationAction(target: NativeNotificationPreview, accepted: Boolean) {
        val accessToken = session?.accessToken ?: return
        if (notificationActionBusy || target.actionType == NativeNotificationActionType.None) return
        notificationActionBusy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when (target.actionType) {
                        NativeNotificationActionType.FriendRequest -> {
                            val friendshipId = target.friendshipId
                                ?: error(nativePhrase("好友申请缺少编号。", "The friend request is missing its ID."))
                            NativeFriendsApi.respondFriendRequest(accessToken, friendshipId, if (accepted) "accepted" else "declined")
                        }
                        NativeNotificationActionType.StrangerMessageRequest -> {
                            val requestId = target.requestId
                                ?: error(nativePhrase("消息请求缺少编号。", "The message request is missing its ID."))
                            NativeFriendsApi.respondStrangerRequest(accessToken, requestId, if (accepted) "accepted" else "declined")
                        }
                        NativeNotificationActionType.GroupInvitation -> {
                            val groupId = target.groupId
                            val invitationId = target.invitationId
                            if (invitationId != null) {
                                NativeGroupApi.respondInvitation(accessToken, invitationId, if (accepted) "accepted" else "declined")
                            } else if (groupId != null) {
                                NativeGroupApi.respondInvitationByGroup(accessToken, groupId, if (accepted) "accepted" else "declined")
                            } else {
                                error(nativePhrase("群邀请缺少编号。", "The group invitation is missing its ID."))
                            }
                        }
                        NativeNotificationActionType.GroupJoinRequest -> {
                            val groupId = target.groupId
                                ?: error(nativePhrase("群聊编号缺失。", "The group ID is missing."))
                            val joinRequestId = target.joinRequestId
                                ?: error(nativePhrase("入群申请缺少编号。", "The join request is missing its ID."))
                            NativeGroupApi.respondJoinRequest(accessToken, groupId, joinRequestId, if (accepted) "approved" else "rejected")
                        }
                        NativeNotificationActionType.GroupReport -> {
                            val reportId = target.reportId
                                ?: error(nativePhrase("举报缺少编号。", "The report is missing its ID."))
                            NativeGroupApi.handleAdminGroupReport(
                                accessToken = accessToken,
                                reportId = reportId,
                                status = if (accepted) "resolved" else "rejected",
                                resolution = if (accepted) {
                                    nativePhrase("管理员已删除被举报消息", "An administrator deleted the reported message")
                                } else {
                                    nativePhrase("未发现违规", "No violation found")
                                },
                                deleteMessage = accepted,
                            )
                        }
                        NativeNotificationActionType.None -> Unit
                    }
                    NativeSocialApi.deleteNotification(accessToken, target.id)
                }
            }.onSuccess {
                val updated = inbox?.copy(
                    unreadNotifications = ((inbox?.unreadNotifications ?: 0) - if (target.unread) 1 else 0).coerceAtLeast(0),
                    notifications = inbox?.notifications.orEmpty().filterNot { it.id == target.id },
                )
                inbox = updated
                updated?.let { onInboxSummaryChanged(it.toSummary()) }
                onNotice(
                    when (target.actionType) {
                        NativeNotificationActionType.GroupReport -> if (accepted) {
                            nativePhrase("举报已处理，消息已删除。", "Report resolved and the message was deleted.")
                        } else {
                            nativePhrase("举报已驳回。", "Report rejected.")
                        }
                        else -> if (accepted) {
                            nativePhrase("已同意该请求。", "Request accepted.")
                        } else {
                            nativePhrase("已拒绝该请求。", "Request declined.")
                        }
                    },
                )
            }.onFailure { onNotice(it.message ?: nativePhrase("通知操作失败。", "Notification action failed.")) }
            notificationActionBusy = false
        }
    }

    fun loadMoreNotifications() {
        val accessToken = session?.accessToken ?: return
        val current = inbox ?: return
        if (notificationLoadingMore || !current.notificationsHasMore) return
        val beforeId = current.notifications.lastOrNull()?.id ?: return
        notificationLoadingMore = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeSocialApi.listNotifications(accessToken, beforeId = beforeId)
                }
            }.onSuccess { page ->
                val latest = inbox ?: current
                val merged = (latest.notifications + page.items)
                    .distinctBy { it.id }
                    .sortedByDescending { it.id }
                inbox = latest.copy(
                    notifications = merged,
                    notificationsHasMore = page.hasMore,
                )
            }.onFailure { onNotice(it.message ?: nativePhrase("更多通知加载失败。", "Failed to load more notifications.")) }
            notificationLoadingMore = false
        }
    }

    fun markSelectedNotificationsRead() {
        val accessToken = session?.accessToken ?: return
        val ids = selectedNotificationIds.toList()
        if (ids.isEmpty() || notificationActionBusy) return
        notificationActionBusy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeSocialApi.markSelectedNotificationsRead(accessToken, ids)
                }
            }.onSuccess { count ->
                val selected = ids.toSet()
                val unreadChanged = inbox?.notifications.orEmpty().count { it.id in selected && it.unread }
                val updated = inbox?.copy(
                    unreadNotifications = ((inbox?.unreadNotifications ?: 0) - unreadChanged).coerceAtLeast(0),
                    notifications = inbox?.notifications.orEmpty().map { if (it.id in selected) it.copy(unread = false) else it },
                )
                inbox = updated
                updated?.let { onInboxSummaryChanged(it.toSummary()) }
                notificationSelectionMode = false
                selectedNotificationIds = emptySet()
                onNotice(
                    if (count > 0) {
                        nativePhrase("已将 $count 条通知标为已读。", "$count notifications marked as read.")
                    } else {
                        nativePhrase("所选通知均已是已读状态。", "The selected notifications are already read.")
                    },
                )
            }.onFailure { onNotice(it.message ?: nativePhrase("通知状态更新失败。", "Failed to update notification status.")) }
            notificationActionBusy = false
        }
    }

    fun clearNotificationChannel(channel: String, label: String) {
        val accessToken = session?.accessToken ?: return
        notificationActionBusy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeSocialApi.clearNotifications(accessToken, channel)
                    NativeSocialApi.loadInbox(accessToken)
                }
            }.onSuccess { refreshed ->
                inbox = refreshed
                notificationSelectionMode = false
                selectedNotificationIds = emptySet()
                onInboxSummaryChanged(refreshed.toSummary())
                notificationClearChannel = null
                onNotice(nativePhrase("${label}通知已清空。", "$label notifications cleared."))
            }.onFailure { onNotice(it.message ?: nativePhrase("通知清空失败。", "Failed to clear notifications.")) }
            notificationActionBusy = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            AppHeader(
                nativePhrase("消息", "Messages"),
                nativePhrase("聊天、评论和提醒", "Chats, comments, and alerts"),
                action = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (session == null) onLogin() else localRefreshKey += 1
                            },
                            enabled = !loading,
                        ) { Icon(Icons.Filled.Refresh, nativePhrase("刷新消息", "Refresh messages")) }
                        IconButton(onClick = onOpenPeople) { Icon(Icons.Filled.PersonOutline, nativePhrase("好友与请求", "Friends and requests")) }
                        IconButton(
                            onClick = {
                                if (session == null) onLogin() else {
                                    notificationSettingsError = null
                                    notificationSettingsOpen = true
                                }
                            },
                        ) {
                            Icon(Icons.Filled.Settings, nativePhrase("通知设置", "Notification settings"))
                        }
                        IconButton(onClick = onOpenGroups) { Icon(Icons.Filled.Groups, nativePhrase("群聊管理", "Group chats")) }
                    }
                },
            )
        }
        if (session == null) {
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    shape = HlovetUi.rowShape,
                    colors = CardDefaults.cardColors(containerColor = SurfaceAccent),
                ) {
                    ListItem(
                        headlineContent = { Text(nativePhrase("登录后查看消息", "Sign in to view messages"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp) },
                        supportingContent = { Text(nativePhrase("聊天、@提醒和系统通知会显示在这里", "Chats, mentions, and system notifications appear here"), color = TextMuted, fontSize = 11.sp) },
                        leadingContent = { Icon(Icons.Filled.Login, contentDescription = null, tint = Accent) },
                        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(onClick = onLogin),
                    )
                }
            }
        } else if (loading) {
            item { EmptyState(nativePhrase("正在读取消息…", "Loading messages...")) }
        } else if (!error.isNullOrBlank()) {
            item { PreviewStatusScreen(title = nativePhrase("消息暂时无法读取", "Messages are unavailable"), detail = error) }
        } else {
            val currentInbox = inbox
            if (currentInbox != null) {
                val visibleNotifications = currentInbox.notifications.filterNot { notification ->
                    notification.channel in currentInbox.hiddenNotificationChannels
                }
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = HlovetUi.rowShape,
                        colors = CardDefaults.cardColors(containerColor = SurfaceAccent),
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    nativePhrase("${currentInbox.unreadMessages} 条未读消息", "${currentInbox.unreadMessages} unread messages"),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                )
                            },
                            supportingContent = {
                                Text(
                                    nativePhrase(
                                        "通知 ${currentInbox.unreadNotifications} · 好友申请 ${currentInbox.pendingFriendRequests} · 陌生人请求 ${currentInbox.pendingStrangerRequests}",
                                        "Notifications ${currentInbox.unreadNotifications} · Friend requests ${currentInbox.pendingFriendRequests} · Message requests ${currentInbox.pendingStrangerRequests}",
                                    ),
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = { Icon(Icons.Filled.NotificationsNone, contentDescription = null, tint = Accent) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                val token = session?.accessToken ?: return@clickable
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { NativeSocialApi.markAllNotificationsRead(token) } }
                                     val updated = inbox?.copy(
                                        unreadNotifications = 0,
                                        notifications = inbox?.notifications?.map { it.copy(unread = false) }.orEmpty(),
                                    )
                                     inbox = updated
                                     updated?.let { onInboxSummaryChanged(it.toSummary()) }
                                }
                            },
                        )
                    }
                }
                item {
                    SectionHeading(
                        nativePhrase("聊天", "Chats"),
                        nativePhrase("${currentInbox.conversations.size} 个会话", "${currentInbox.conversations.size} conversations"),
                    )
                }
                if (currentInbox.conversations.isEmpty()) {
                    item { EmptyState(nativePhrase("暂时没有可显示的聊天会话。", "No conversations to show.")) }
                } else {
                    item {
                        Card(
                            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                            shape = HlovetUi.cardShape,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Column {
                                currentInbox.conversations.forEachIndexed { index, chat ->
                                    ListItem(
                                        headlineContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(chat.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (!chat.muted && chat.unreadCount > 0) {
                                                    Surface(shape = CircleShape, color = Accent) {
                                                        Text(chat.unreadCount.toString(), color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                                    }
                                                }
                                            }
                                        },
                                        supportingContent = {
                                            Text(
                                                if (chat.muted) nativePhrase("已静音 · ${chat.message}", "Muted · ${chat.message}") else chat.message,
                                                color = TextMuted,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        leadingContent = { Avatar(chat.initials, Accent) },
                                        trailingContent = { Text(chat.time, color = TextMuted, fontSize = 10.sp) },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        modifier = Modifier.clickable { onOpenConversation(chat) },
                                    )
                                    if (index < currentInbox.conversations.lastIndex) HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(horizontal = 16.dp))
                                }
                            }
                        }
                    }
                }
                item {
                    SectionHeading(
                        nativePhrase("最近通知", "Recent notifications"),
                        if (notificationSelectionMode) nativePhrase("取消选择", "Cancel selection") else nativePhrase("选择", "Select"),
                        onAction = {
                            notificationSelectionMode = !notificationSelectionMode
                            if (!notificationSelectionMode) selectedNotificationIds = emptySet()
                        },
                    )
                }
                if (notificationSelectionMode) {
                    item {
                        val allSelected = visibleNotifications.isNotEmpty() && visibleNotifications.all { it.id in selectedNotificationIds }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(nativePhrase("已选 ${selectedNotificationIds.size} 条", "${selectedNotificationIds.size} selected"), color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                TextButton(
                                    onClick = {
                                        selectedNotificationIds = if (allSelected) emptySet() else visibleNotifications.map { it.id }.toSet()
                                    },
                                    enabled = visibleNotifications.isNotEmpty() && !notificationActionBusy,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                ) { Text(if (allSelected) nativePhrase("取消全选", "Deselect all") else nativePhrase("全选", "Select all"), fontSize = 12.sp) }
                                TextButton(
                                    onClick = ::markSelectedNotificationsRead,
                                    enabled = selectedNotificationIds.isNotEmpty() && !notificationActionBusy,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                ) { Text(nativePhrase("标为已读", "Mark read"), fontSize = 12.sp) }
                                TextButton(
                                    onClick = { notificationDeleteSelection = selectedNotificationIds },
                                    enabled = selectedNotificationIds.isNotEmpty() && !notificationActionBusy,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                ) { Text(nativePhrase("删除", "Delete"), color = Coral, fontSize = 12.sp) }
                            }
                        }
                    }
                }
                if (visibleNotifications.isEmpty()) {
                    item { EmptyState(nativePhrase("暂时没有通知。", "No notifications yet.")) }
                } else {
                    item {
                        Card(
                            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                            shape = HlovetUi.cardShape,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Column {
                                visibleNotifications.forEachIndexed { index, notification ->
                                    ListItem(
                                        headlineContent = { Text(notification.title, fontWeight = if (notification.unread) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                notification.groupName?.let { groupName ->
                                                    Text(
                                                        groupName,
                                                        color = Accent,
                                                        fontSize = 11.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                nativeNotificationBody(notification).takeIf { it.isNotBlank() }?.let { body ->
                                                    Text(
                                                        body,
                                                        color = TextMuted,
                                                        fontSize = 12.sp,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                notification.commentBody?.let { commentBody ->
                                                    Text(
                                                        "\"$commentBody\"",
                                                        color = TextMuted,
                                                        fontSize = 11.sp,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                nativeNotificationStatus(notification)?.let { status ->
                                                    Text(status, color = TextMuted, fontSize = 11.sp)
                                                }
                                                notification.reportMessage?.let { reportMessage ->
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                                        Icon(Icons.Filled.Report, contentDescription = null, tint = Coral, modifier = Modifier.size(14.dp))
                                                        Text(
                                                            nativeNotificationMessagePreview(reportMessage),
                                                            color = TextMuted,
                                                            fontSize = 11.sp,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                }
                                                if (notification.actionType != NativeNotificationActionType.None) {
                                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        TextButton(
                                                            onClick = { handleNotificationAction(notification, true) },
                                                            enabled = !notificationActionBusy,
                                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                        ) {
                                                            Text(
                                                                if (notification.actionType == NativeNotificationActionType.GroupReport) nativePhrase("处理", "Resolve") else nativePhrase("同意", "Accept"),
                                                                fontSize = 10.sp,
                                                                color = Accent,
                                                            )
                                                        }
                                                        TextButton(
                                                            onClick = { handleNotificationAction(notification, false) },
                                                            enabled = !notificationActionBusy,
                                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                        ) {
                                                            Text(
                                                                if (notification.actionType == NativeNotificationActionType.GroupReport) nativePhrase("驳回", "Reject") else nativePhrase("拒绝", "Decline"),
                                                                fontSize = 10.sp,
                                                                color = if (notification.actionType == NativeNotificationActionType.GroupReport) Coral else TextMuted,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        leadingContent = {
                                            if (notificationSelectionMode) {
                                                HlovetCheckbox(
                                                    checked = notification.id in selectedNotificationIds,
                                                    onCheckedChange = {
                                                        selectedNotificationIds = if (notification.id in selectedNotificationIds) {
                                                            selectedNotificationIds - notification.id
                                                        } else {
                                                            selectedNotificationIds + notification.id
                                                        }
                                                    },
                                                )
                                            } else {
                                                Icon(Icons.Filled.NotificationsNone, contentDescription = null, tint = if (notification.unread) Accent else TextMuted)
                                            }
                                        },
                                        trailingContent = {
                                            IconButton(
                                                onClick = { notificationDeleteTarget = notification },
                                                enabled = !notificationActionBusy,
                                            ) { Icon(Icons.Filled.Delete, nativePhrase("删除通知", "Delete notification"), tint = TextMuted, modifier = Modifier.size(17.dp)) }
                                        },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        modifier = Modifier.testTag("notification-${notification.id}").clickable {
                                            if (notificationSelectionMode) {
                                                selectedNotificationIds = if (notification.id in selectedNotificationIds) {
                                                    selectedNotificationIds - notification.id
                                                } else {
                                                    selectedNotificationIds + notification.id
                                                }
                                                return@clickable
                                            }
                                            val token = session?.accessToken ?: return@clickable
                                            scope.launch {
                                                // 每次点击都读取实时通知，已读缓存也可能在之后被删除或屏蔽。
                                                val currentNotification = try {
                                                    withContext(Dispatchers.IO) { NativeSocialApi.markNotificationRead(token, notification.id) }
                                                } catch (exception: Exception) {
                                                    error = exception.message ?: nativePhrase("通知状态读取失败，请稍后重试。", "Unable to check this notification. Try again shortly.")
                                                    return@launch
                                                }
                                                if (notification.unread) {
                                                     val updated = inbox?.copy(
                                                        unreadNotifications = (inbox?.unreadNotifications ?: 0).coerceAtLeast(1) - 1,
                                                        notifications = inbox?.notifications?.map {
                                                            if (it.id == notification.id) currentNotification else it
                                                        }.orEmpty(),
                                                    )
                                                     inbox = updated
                                                     updated?.let { onInboxSummaryChanged(it.toSummary()) }
                                                }
                                                onOpenNotification(
                                                    currentNotification,
                                                    notification.conversationId?.let { conversationId ->
                                                        currentInbox.conversations.firstOrNull { it.id == conversationId }
                                                    },
                                                )
                                            }
                                        },
                                    )
                                    if (index < visibleNotifications.lastIndex) HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(horizontal = 16.dp))
                                }
                            }
                        }
                    }
                    if (currentInbox.notificationsHasMore) {
                        item {
                            TextButton(
                                onClick = ::loadMoreNotifications,
                                enabled = !notificationLoadingMore && !notificationActionBusy,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            ) {
                                Text(if (notificationLoadingMore) nativePhrase("正在加载更多…", "Loading more...") else nativePhrase("加载更多通知", "Load more notifications"), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    notificationDeleteTarget?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (!notificationActionBusy) notificationDeleteTarget = null },
            title = { Text(nativePhrase("删除通知", "Delete notification")) },
            text = { Text(nativePhrase("确定删除这条通知吗？删除后无法恢复。", "Delete this notification? This cannot be undone."), fontSize = 13.sp) },
            dismissButton = {
                TextButton(onClick = { notificationDeleteTarget = null }, enabled = !notificationActionBusy) { Text(nativePhrase("取消", "Cancel")) }
            },
            confirmButton = {
                TextButton(onClick = { deleteNotification(target) }, enabled = !notificationActionBusy) { Text(nativePhrase("删除", "Delete")) }
            },
        )
    }

    notificationDeleteSelection?.let { selectedIds ->
        HlovetAlertDialog(
            onDismissRequest = { if (!notificationActionBusy) notificationDeleteSelection = null },
            title = { Text(nativePhrase("批量删除通知", "Delete notifications")) },
            text = {
                Text(
                    nativePhrase("确定删除选中的 ${selectedIds.size} 条通知吗？删除后无法恢复。", "Delete ${selectedIds.size} selected notifications? This cannot be undone."),
                    fontSize = 13.sp,
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { notificationDeleteSelection = null },
                    enabled = !notificationActionBusy,
                ) { Text(nativePhrase("取消", "Cancel")) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val accessToken = session?.accessToken
                        if (accessToken == null || selectedIds.isEmpty() || notificationActionBusy) return@TextButton
                        notificationActionBusy = true
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    NativeSocialApi.deleteSelectedNotifications(accessToken, selectedIds)
                                }
                            }.onSuccess { count ->
                                val deleted = selectedIds.toSet()
                                val unreadDeleted = inbox?.notifications.orEmpty().count { it.id in deleted && it.unread }
                                val updated = inbox?.copy(
                                    unreadNotifications = ((inbox?.unreadNotifications ?: 0) - unreadDeleted).coerceAtLeast(0),
                                    notifications = inbox?.notifications.orEmpty().filterNot { it.id in deleted },
                                )
                                inbox = updated
                                updated?.let { onInboxSummaryChanged(it.toSummary()) }
                                notificationDeleteSelection = null
                                notificationSelectionMode = false
                                selectedNotificationIds = emptySet()
                                onNotice(nativePhrase("已删除 $count 条通知。", "$count notifications deleted."))
                            }.onFailure { onNotice(it.message ?: nativePhrase("通知删除失败。", "Failed to delete notifications.")) }
                            notificationActionBusy = false
                        }
                    },
                    enabled = !notificationActionBusy,
                ) { Text(if (notificationActionBusy) nativePhrase("处理中…", "Working...") else nativePhrase("确认删除", "Delete"), color = Coral) }
            },
        )
    }

    notificationClearChannel?.let { channel ->
        val label = notificationChannelOptions.firstOrNull { it.first == channel }?.second ?: nativePhrase("该频道", "This channel")
        HlovetAlertDialog(
            onDismissRequest = { if (!notificationActionBusy) notificationClearChannel = null },
            title = { Text(nativePhrase("清空$label", "Clear $label")) },
            text = { Text(nativePhrase("将删除当前账号中该频道可清除的通知，待处理好友申请不会受影响。", "Clear removable notifications in this channel. Pending friend requests will not be affected."), fontSize = 13.sp) },
            dismissButton = {
                TextButton(onClick = { notificationClearChannel = null }, enabled = !notificationActionBusy) { Text(nativePhrase("取消", "Cancel")) }
            },
            confirmButton = {
                TextButton(onClick = { clearNotificationChannel(channel, label) }, enabled = !notificationActionBusy) { Text(nativePhrase("确认清空", "Clear")) }
            },
        )
    }

    if (notificationSettingsOpen && session != null) {
        HlovetAlertDialog(
            onDismissRequest = {
                if (notificationSettingsBusy == null) {
                    notificationSettingsOpen = false
                    notificationSettingsError = null
                }
            },
            title = { Text(nativePhrase("通知设置", "Notification settings"), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(nativePhrase("分别控制站内提醒、设备推送和订阅日报。", "Control in-app alerts, device notifications, and subscription digests."), color = TextMuted, fontSize = 12.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(nativePhrase("Android 系统提醒", "Android notifications"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            nativePhrase(
                                "原生客户端会在应用打开期间检查新的未读通知；后台实时推送需要额外配置 FCM。",
                                "The app checks for unread notifications while open. Real-time background delivery requires FCM configuration.",
                            ),
                            color = TextMuted,
                            fontSize = 11.sp,
                        )
                        TextButton(
                            onClick = onEnableNativeNotifications,
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        ) {
                            Text(
                                if (NativeLocalNotifications.isAllowed(context)) nativePhrase("系统通知已允许", "System notifications allowed") else nativePhrase("开启系统通知", "Enable system notifications"),
                                color = if (NativeLocalNotifications.isAllowed(context)) TextMuted else Accent,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    HorizontalDivider(color = HlovetUi.divider)
                    notificationChannelOptions.forEach { (channel, label, description) ->
                        val state = inbox?.notificationChannelStates?.firstOrNull { it.channel == channel }
                            ?: NativeNotificationChannelState(channel, 0, true, true)
                        val busy = notificationSettingsBusy == channel
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(description, color = TextMuted, fontSize = 11.sp)
                                }
                                HlovetSwitch(
                                    checked = state.pushEnabled,
                                    enabled = notificationSettingsBusy == null,
                                    onCheckedChange = { enabled ->
                                        val token = session.accessToken
                                        notificationSettingsBusy = channel
                                        notificationSettingsError = null
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    NativeSocialApi.updateNotificationChannelSettings(token, channel, pushEnabled = enabled)
                                                }
                                            }.onSuccess { updated ->
                                                inbox = inbox?.copy(
                                                    notificationChannelStates = inbox?.notificationChannelStates.orEmpty().map {
                                                        if (it.channel == channel) updated else it
                                                    },
                                                )
                                                onNotice(
                                                    if (enabled) nativePhrase("${label}已开启推送。", "$label notifications enabled.")
                                                    else nativePhrase("${label}已暂停推送。", "$label notifications paused."),
                                                )
                                            }.onFailure {
                                                notificationSettingsError = it.message ?: nativePhrase("通知设置更新失败。", "Failed to update notification settings.")
                                            }
                                            notificationSettingsBusy = null
                                        }
                                    },
                                )
                            }
                            TextButton(
                                onClick = {
                                    val token = session.accessToken
                                    notificationSettingsBusy = channel
                                    notificationSettingsError = null
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) { NativeSocialApi.hideNotificationChannel(token, channel) }
                                        }.onSuccess {
                                            inbox = inbox?.copy(
                                                hiddenNotificationChannels = (inbox?.hiddenNotificationChannels.orEmpty() + channel).distinct(),
                                            )
                                            onNotice(nativePhrase("${label}已清除并隐藏，收到新通知后会重新出现。", "$label was cleared and hidden. It will reappear when a new notification arrives."))
                                        }.onFailure {
                                            notificationSettingsError = it.message ?: nativePhrase("通知频道处理失败。", "Failed to update the notification channel.")
                                        }
                                        notificationSettingsBusy = null
                                    }
                                },
                                enabled = notificationSettingsBusy == null && channel !in (inbox?.hiddenNotificationChannels.orEmpty()),
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    if (channel in (inbox?.hiddenNotificationChannels.orEmpty())) {
                                        nativePhrase("已隐藏，等待新通知恢复", "Hidden until a new notification arrives")
                                    } else {
                                        nativePhrase("清除并隐藏此频道", "Clear and hide this channel")
                                    },
                                    color = if (channel in (inbox?.hiddenNotificationChannels.orEmpty())) TextMuted else Accent,
                                    fontSize = 11.sp,
                                )
                            }
                            TextButton(
                                onClick = { notificationClearChannel = channel },
                                enabled = notificationSettingsBusy == null && !notificationActionBusy,
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                            ) {
                                Text(nativePhrase("删除此频道通知", "Delete this channel's notifications"), color = HlovetUi.secondaryAccent, fontSize = 11.sp)
                            }
                        }
                        if (channel != notificationChannelOptions.last().first) {
                            HorizontalDivider(color = HlovetUi.divider)
                        }
                    }
                    val subscriptionState = inbox?.notificationChannelStates?.firstOrNull { it.channel == "subscription" }
                        ?: NativeNotificationChannelState("subscription", 0, true, true)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(nativePhrase("每日订阅汇总", "Daily subscription digest"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(nativePhrase("每天汇总关注作者的新文章", "A daily summary of new articles from authors you follow"), color = TextMuted, fontSize = 11.sp)
                        }
                        HlovetSwitch(
                            checked = subscriptionState.digestEnabled,
                            enabled = notificationSettingsBusy == null,
                            onCheckedChange = { enabled ->
                                val token = session.accessToken
                                notificationSettingsBusy = "subscription-digest"
                                notificationSettingsError = null
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            NativeSocialApi.updateNotificationChannelSettings(token, "subscription", digestEnabled = enabled)
                                        }
                                    }.onSuccess { updated ->
                                        inbox = inbox?.copy(
                                            notificationChannelStates = inbox?.notificationChannelStates.orEmpty().map {
                                                if (it.channel == "subscription") updated else it
                                            },
                                        )
                                        onNotice(if (enabled) nativePhrase("订阅日报已开启。", "Subscription digest enabled.") else nativePhrase("订阅日报已暂停。", "Subscription digest paused."))
                                    }.onFailure {
                                        notificationSettingsError = it.message ?: nativePhrase("订阅日报更新失败。", "Failed to update the subscription digest.")
                                    }
                                    notificationSettingsBusy = null
                                }
                            },
                        )
                    }
                    notificationSettingsError?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (notificationSettingsBusy == null) {
                            notificationSettingsOpen = false
                            notificationSettingsError = null
                        }
                    },
                    enabled = notificationSettingsBusy == null,
                ) { Text(nativePhrase("完成", "Done")) }
            },
        )
    }
}

@Composable
private fun NativeGlobalCallHost(
    session: NativeSession?,
    onNotice: (String) -> Unit,
    onReauthenticate: (((String?) -> Unit) -> Unit),
): NativeCallLauncher {
    val accessToken = session?.accessToken
    val sessionKey = session?.refreshToken?.substringBefore('.')?.takeIf { it.isNotBlank() }
        ?: session?.username.orEmpty()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chatSocket by remember(sessionKey) { mutableStateOf<NativeChatSocket?>(null) }
    var connected by remember(sessionKey) { mutableStateOf(false) }
    var callSession by remember(sessionKey) { mutableStateOf<NativeCallSession?>(null) }
    var callPhase by remember(sessionKey) { mutableStateOf<String?>(null) }
    var callPreparing by remember(sessionKey) { mutableStateOf(false) }
    var callMuted by remember(sessionKey) { mutableStateOf(false) }
    var callCameraEnabled by remember(sessionKey) { mutableStateOf(true) }
    var callClient by remember(sessionKey) { mutableStateOf<NativeWebRtcCall?>(null) }
    var localVideoTrack by remember(sessionKey) { mutableStateOf<VideoTrack?>(null) }
    var remoteVideoTrack by remember(sessionKey) { mutableStateOf<VideoTrack?>(null) }
    var pendingPermissionConversationId by remember(sessionKey) { mutableStateOf<Int?>(null) }
    var pendingPermissionCallType by remember(sessionKey) { mutableStateOf<String?>(null) }
    var pendingPermissionAccept by remember(sessionKey) { mutableStateOf(false) }

    fun clearCall() {
        callClient?.close()
        callClient = null
        localVideoTrack = null
        remoteVideoTrack = null
        callSession = null
        callPhase = null
        callPreparing = false
        callMuted = false
        callCameraEnabled = true
    }

    val latestSocket = rememberUpdatedState(chatSocket)
    val latestCallSession = rememberUpdatedState(callSession)
    val latestCallClient = rememberUpdatedState(callClient)
    val latestCallPreparing = rememberUpdatedState(callPreparing)
    val latestClearCall = rememberUpdatedState(::clearCall)
    val latestNotice = rememberUpdatedState(onNotice)

    fun handleCallError(message: String) {
        latestCallSession.value?.id?.let { latestSocket.value?.endCall(it, "failed") }
        clearCall()
        onNotice(message)
    }

    val latestHandleCallError = rememberUpdatedState(::handleCallError)

    suspend fun buildCallClient(type: String, token: String): NativeWebRtcCall {
        val client = NativeWebRtcCall(
            context = context,
            type = type,
            iceServers = withContext(Dispatchers.IO) { NativeSocialApi.getCallIceServers(token) },
            onSignal = { signal ->
                latestCallSession.value?.id?.let { callId ->
                    latestSocket.value?.sendCallSignal(callId, signal)
                }
            },
            onConnected = {
                scope.launch {
                    val callId = latestCallSession.value?.id ?: return@launch
                    callPhase = "active"
                    latestSocket.value?.markCallConnected(callId)
                }
            },
            onRemoteVideo = { track -> scope.launch { remoteVideoTrack = track } },
            onError = { message -> scope.launch { latestHandleCallError.value(message) } },
        )
        client.start()
        return client
    }

    fun startNativeCall(conversationId: Int, type: String) {
        val token = accessToken ?: return
        if (callSession != null || callPreparing) {
            onNotice(nativePhrase("当前已有一通电话正在处理。", "Another call is already in progress."))
            return
        }
        val socket = chatSocket
        if (!connected || socket == null) {
            onNotice(nativePhrase("通话连接尚未建立，请稍后重试。", "The call connection is not ready. Try again shortly."))
            return
        }
        scope.launch {
            callPreparing = true
            try {
                val client = buildCallClient(type, token)
                callClient = client
                localVideoTrack = client.localVideoTrack
                callCameraEnabled = type == "video"
                socket.startCall(conversationId, type) { ok, call, message ->
                    scope.launch {
                        if (!ok || call == null) {
                            client.close()
                            if (callClient === client) {
                                callClient = null
                                localVideoTrack = null
                                remoteVideoTrack = null
                            }
                    onNotice(message ?: nativePhrase("通话发起失败。", "Failed to start the call."))
                        } else {
                            callSession = call
                            callPhase = "outgoing"
                        }
                        callPreparing = false
                    }
                }
            } catch (exception: Exception) {
                clearCall()
                onNotice(exception.message ?: nativePhrase("通话发起失败。", "Failed to start the call."))
            }
        }
    }

    fun acceptNativeCall() {
        val token = accessToken ?: return
        val incoming = callSession ?: return
        val socket = chatSocket ?: return
        if (callPreparing) return
        scope.launch {
            callPreparing = true
            try {
                val client = buildCallClient(incoming.type, token)
                callClient = client
                localVideoTrack = client.localVideoTrack
                callCameraEnabled = incoming.type == "video"
                socket.respondCall(incoming.id, true) { ok, call, message ->
                    scope.launch {
                        if (!ok || call == null) {
                            clearCall()
                    onNotice(message ?: nativePhrase("接听失败。", "Failed to answer the call."))
                        } else {
                            callSession = call
                            callPhase = "connecting"
                            callPreparing = false
                        }
                    }
                }
            } catch (exception: Exception) {
                clearCall()
                onNotice(exception.message ?: nativePhrase("无法接听通话。", "Unable to answer the call."))
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val conversationId = pendingPermissionConversationId
        val type = pendingPermissionCallType
        val accepting = pendingPermissionAccept
        pendingPermissionConversationId = null
        pendingPermissionCallType = null
        pendingPermissionAccept = false
        if (type == null) return@rememberLauncherForActivityResult
        if (result.values.all { it }) {
            if (accepting) acceptNativeCall()
            else if (conversationId != null) startNativeCall(conversationId, type)
        } else {
            onNotice(
                nativePhrase(
                    "通话需要麦克风${if (type == "video") "和摄像头" else ""}权限。",
                    "This call needs ${if (type == "video") "microphone and camera" else "microphone"} permission.",
                ),
            )
        }
    }

    fun requiredPermissions(type: String): Array<String> = if (type == "video") {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    fun requestNativeCall(conversationId: Int, type: String) {
        val missing = requiredPermissions(type).filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startNativeCall(conversationId, type)
        } else {
            pendingPermissionConversationId = conversationId
            pendingPermissionCallType = type
            pendingPermissionAccept = false
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun requestAcceptNativeCall() {
        val incoming = callSession ?: return
        val missing = requiredPermissions(incoming.type).filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            acceptNativeCall()
        } else {
            pendingPermissionConversationId = incoming.conversationId
            pendingPermissionCallType = incoming.type
            pendingPermissionAccept = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun declineNativeCall() {
        callSession?.let { chatSocket?.respondCall(it.id, false) }
        clearCall()
    }

    fun endNativeCall() {
        callSession?.let { chatSocket?.endCall(it.id) }
        clearCall()
    }

    DisposableEffect(sessionKey) {
        val token = accessToken
        if (token.isNullOrBlank()) {
            onDispose { clearCall() }
        } else {
            val instance = NativeChatSocket(
                accessToken = token,
                onMessage = {},
                onRead = { _, _ -> },
                onError = { message ->
                    if (latestCallSession.value != null) scope.launch { latestNotice.value(message) }
                },
                onConnectionChanged = { value -> scope.launch { connected = value } },
                onCallIncoming = { incoming ->
                    scope.launch {
                        if (latestCallSession.value == null && !latestCallPreparing.value) {
                            callSession = incoming
                            callPhase = "incoming"
                        }
                    }
                },
                onCallAccepted = { accepted ->
                    if (accepted.id == latestCallSession.value?.id) {
                        scope.launch {
                            callSession = accepted
                            callPhase = "connecting"
                            latestCallClient.value?.createOffer()
                        }
                    }
                },
                onCallClaimed = { claimed ->
                    if (claimed.id == latestCallSession.value?.id) {
                        scope.launch {
                            if (latestCallClient.value == null) {
                                latestClearCall.value()
                        latestNotice.value(nativePhrase("这通电话已在其他设备接听。", "This call was answered on another device."))
                            } else {
                                callSession = claimed
                                callPhase = "connecting"
                            }
                        }
                    }
                },
                onCallSignal = { callId, signal ->
                    if (callId == latestCallSession.value?.id) {
                        scope.launch { latestCallClient.value?.handleSignal(signal) }
                    }
                },
                onCallActive = { active ->
                    if (active.id == latestCallSession.value?.id) {
                        scope.launch {
                            callSession = active
                            callPhase = "active"
                        }
                    }
                },
                onCallEnded = { ended ->
                    if (ended.id == latestCallSession.value?.id) {
                        scope.launch {
                            latestClearCall.value()
                            latestNotice.value(
                                when (ended.status) {
                            "declined" -> nativePhrase("对方已拒绝通话。", "The call was declined.")
                            "missed" -> nativePhrase("无人接听。", "The call was not answered.")
                            "busy" -> nativePhrase("对方正在通话中。", "The other person is busy.")
                            "failed" -> nativePhrase("通话连接失败。", "The call connection failed.")
                            else -> nativePhrase("通话已结束。", "The call ended.")
                                },
                            )
                        }
                    }
                },
                onReauthenticate = onReauthenticate,
            )
            chatSocket = instance
            instance.connect()
            onDispose {
                instance.close()
                if (chatSocket === instance) chatSocket = null
                connected = false
                clearCall()
            }
        }
    }

    LaunchedEffect(accessToken) {
        accessToken?.let { chatSocket?.updateAccessToken(it) }
    }

    callSession?.let { activeCall ->
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
                    NativeCallPanel(
                        call = activeCall,
                        phase = callPhase.orEmpty(),
                        preparing = callPreparing,
                        localVideoTrack = localVideoTrack,
                        remoteVideoTrack = remoteVideoTrack,
                        eglContext = callClient?.eglContext,
                        muted = callMuted,
                        cameraEnabled = callCameraEnabled,
                        onAccept = ::requestAcceptNativeCall,
                        onDecline = ::declineNativeCall,
                        onEnd = ::endNativeCall,
                        onToggleMute = {
                            callMuted = !callMuted
                            callClient?.setMuted(callMuted)
                        },
                        onToggleCamera = {
                            callCameraEnabled = !callCameraEnabled
                            callClient?.setCameraEnabled(callCameraEnabled)
                        },
                        onSwitchCamera = { callClient?.switchCamera() },
                    )
                }
            }
        }
    }

    return NativeCallLauncher(
        connected = connected,
        active = callSession != null || callPreparing,
        start = ::requestNativeCall,
    )
}

/** 展示 target 会话及定位消息；session 提供当前权限，callLauncher 发起通话，onBack 返回，onReauthenticate 刷新连接认证。 */
@Composable
private fun ConversationScreen(
    target: ConversationTarget,
    session: NativeSession?,
    callLauncher: NativeCallLauncher,
    onBack: () -> Unit,
    onReauthenticate: (((String?) -> Unit) -> Unit),
) {
    // 消息、输入草稿和网络任务按账号/会话隔离，退出或切换时不复用上一位用户的数据。
    val accessToken = session?.accessToken
    val sessionKey = session?.refreshToken?.substringBefore('.')?.takeIf { it.isNotBlank() }
        ?: session?.username.orEmpty()
    val conversationId = target.preview.id
    var messages by remember(sessionKey, conversationId) { mutableStateOf<List<NativeChatMessage>>(emptyList()) }
    var hasMore by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var loading by remember(sessionKey, conversationId) { mutableStateOf(true) }
    var loadingOlder by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var sending by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var connected by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var input by remember(sessionKey, conversationId) { mutableStateOf(TextFieldValue("")) }
    var error by remember(sessionKey, conversationId) { mutableStateOf<String?>(null) }
    var chatSocket by remember(sessionKey, conversationId) { mutableStateOf<NativeChatSocket?>(null) }
    var pinnedMessages by remember(sessionKey, conversationId) { mutableStateOf<List<NativeChatMessage>>(emptyList()) }
    var pinnedCursor by remember(sessionKey, conversationId) { mutableIntStateOf(0) }
    var searchOpen by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var searchQuery by remember(sessionKey, conversationId) { mutableStateOf("") }
    var searchResults by remember(sessionKey, conversationId) { mutableStateOf<List<NativeChatMessage>>(emptyList()) }
    var searchHasMore by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var searchBeforeId by remember(sessionKey, conversationId) { mutableStateOf<Int?>(null) }
    var searchLoading by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var searchLoadingMore by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var highlightedMessageId by remember(sessionKey, conversationId) { mutableStateOf<Int?>(null) }
    var pendingFollowMessageId by remember(sessionKey, conversationId) { mutableStateOf<Int?>(null) }
    var handledFocusMessageId by remember(sessionKey, conversationId) { mutableStateOf<Int?>(null) }
    var pendingAttachments by remember(sessionKey, conversationId) { mutableStateOf<List<NativeChatAttachmentPreview>>(emptyList()) }
    var uploadingAttachments by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var previewAttachment by remember(sessionKey, conversationId) { mutableStateOf<NativeChatAttachmentPreview?>(null) }
    var voiceRecording by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var voiceRecordingSeconds by remember(sessionKey, conversationId) { mutableIntStateOf(0) }
    var actionMessage by remember(sessionKey, conversationId) { mutableStateOf<NativeChatMessage?>(null) }
    var quotedMessage by remember(sessionKey, conversationId) { mutableStateOf<NativeChatMessage?>(null) }
    var pendingMessageOperation by remember(sessionKey, conversationId) { mutableStateOf<NativePendingMessageOperation?>(null) }
    var messageActionBusy by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var selectionMode by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var selectedMessageIds by remember(sessionKey, conversationId) { mutableStateOf<Set<Int>>(emptySet()) }
    var conversationMenuOpen by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var conversationMuted by remember(sessionKey, conversationId) { mutableStateOf(target.preview.muted) }
    var conversationSettingsBusy by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var pendingConversationOperation by remember(sessionKey, conversationId) { mutableStateOf<NativeConversationOperation?>(null) }
    var conversationActionBusy by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var forwardTargets by remember(sessionKey, conversationId) { mutableStateOf<List<NativeConversationPreview>>(emptyList()) }
    var forwardDialogOpen by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var forwardLoading by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var forwardBusy by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var forwardingMessages by remember(sessionKey, conversationId) { mutableStateOf<List<NativeChatMessage>>(emptyList()) }
    var reportMessage by remember(sessionKey, conversationId) { mutableStateOf<NativeChatMessage?>(null) }
    var reportReason by remember(sessionKey, conversationId) { mutableStateOf("spam") }
    var reportDetail by remember(sessionKey, conversationId) { mutableStateOf("") }
    var reportBusy by remember(sessionKey, conversationId) { mutableStateOf(false) }
    var mentionCandidates by remember(sessionKey, conversationId) { mutableStateOf<List<NativeSocialSearchUser>>(emptyList()) }
    var mentionLoading by remember(sessionKey, conversationId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val voiceRecorder = remember(sessionKey, conversationId) { NativeVoiceRecorder(context) }

    DisposableEffect(voiceRecorder) {
        onDispose { voiceRecorder.cancel() }
    }

    suspend fun uploadSelectedAttachments(uris: List<Uri>) {
        val token = accessToken ?: return
        if (uris.isEmpty() || uploadingAttachments) return
        val available = NATIVE_CHAT_MAX_ATTACHMENTS - pendingAttachments.size
        if (uris.size > available) {
            error = nativePhrase("一条消息最多包含 $NATIVE_CHAT_MAX_ATTACHMENTS 个附件。", "A message can include up to $NATIVE_CHAT_MAX_ATTACHMENTS attachments.")
            return
        }
        uploadingAttachments = true
        error = null
        try {
            val files = withContext(Dispatchers.IO) { uris.map { readNativeChatUploadFile(context, it) } }
            if (files.sumOf { it.bytes.size.toLong() } > NATIVE_CHAT_MAX_BATCH_BYTES) {
                throw IllegalStateException(nativePhrase("单条消息附件总大小不能超过 50 MB。", "Message attachments cannot exceed 50 MB in total."))
            }
            val uploaded = withContext(Dispatchers.IO) {
                NativeSocialApi.uploadChatAttachments(token, conversationId, files)
            }
            pendingAttachments = (pendingAttachments + uploaded).distinctBy { it.id }
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase("聊天附件上传失败，请稍后重试。", "Failed to upload chat attachments. Try again later.")
        } finally {
            uploadingAttachments = false
        }
    }

    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        scope.launch { uploadSelectedAttachments(uris) }
    }

    fun beginVoiceRecording() {
        if (voiceRecording || sending || uploadingAttachments || !connected) return
        try {
            voiceRecorder.start()
            voiceRecording = true
            voiceRecordingSeconds = 0
            error = null
        } catch (exception: Exception) {
            voiceRecorder.cancel()
            error = exception.message ?: nativePhrase("无法开始录音，请检查麦克风权限。", "Unable to start recording. Check microphone permission.")
        }
    }

    val voicePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) beginVoiceRecording() else error = nativePhrase("发送语音消息需要麦克风权限。", "Microphone permission is required to send voice messages.")
    }

    fun requestVoiceRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginVoiceRecording()
        } else {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun stopVoiceRecording(send: Boolean) {
        if (!voiceRecording) return
        val file = voiceRecorder.stop()
        voiceRecording = false
        voiceRecordingSeconds = 0
        if (!send) {
            file?.delete()
            return
        }
        val token = accessToken
        if (token.isNullOrBlank() || file == null) {
            file?.delete()
            error = if (file == null) {
                nativePhrase("没有录到有效的语音内容。", "No usable audio was recorded.")
            } else {
                nativePhrase("登录状态已失效，请重新登录。", "Your session expired. Sign in again.")
            }
            return
        }
        scope.launch {
            uploadingAttachments = true
            sending = false
            error = null
            try {
                val uploaded = withContext(Dispatchers.IO) {
                    NativeSocialApi.uploadChatAttachments(token, conversationId, listOf(readVoiceUploadFile(file)))
                }
                val socket = chatSocket ?: throw IllegalStateException(nativePhrase("聊天连接尚未建立，请稍后重试。", "The chat connection is not ready. Try again shortly."))
                if (uploaded.isEmpty()) throw IllegalStateException(nativePhrase("语音上传没有返回附件。", "The voice upload returned no attachment."))
                sending = true
                val result = suspendCancellableCoroutine<Pair<Boolean, String?>> { continuation ->
                    socket.send(conversationId, "", uploaded.map { it.id }) { ok, message ->
                        if (continuation.isActive) continuation.resume(ok to message)
                    }
                }
                if (!result.first) throw IllegalStateException(result.second ?: nativePhrase("语音消息发送失败。", "Failed to send the voice message."))
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("语音消息发送失败，请稍后重试。", "Failed to send the voice message. Try again later.")
            } finally {
                sending = false
                uploadingAttachments = false
                file.delete()
            }
        }
    }

    LaunchedEffect(voiceRecording) {
        while (voiceRecording) {
            delay(1_000)
            if (!voiceRecording) break
            voiceRecordingSeconds += 1
            if (nativeVoiceRecordingLimitReached(voiceRecordingSeconds)) {
                stopVoiceRecording(send = true)
                break
            }
        }
    }

    LaunchedEffect(accessToken, conversationId) {
        val token = accessToken ?: run {
            loading = false
            error = nativePhrase("请先登录后查看聊天。", "Sign in to view this chat.")
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val result = withContext(Dispatchers.IO) { NativeSocialApi.listMessages(token, conversationId) }
            messages = result.items
            hasMore = result.hasMore
            // 普通打开会话显示最新记录；明确的通知定位交给专用定位流程，避免互相抢滚动。
            if (target.focusMessageId == null) pendingFollowMessageId = result.items.lastOrNull()?.id
            pinnedMessages = withContext(Dispatchers.IO) { runCatching { NativeSocialApi.listPinnedMessages(token, conversationId) }.getOrDefault(emptyList()) }
            withContext(Dispatchers.IO) { NativeSocialApi.markConversationRead(token, conversationId) }
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase("聊天记录加载失败。", "Failed to load chat history.")
        } finally {
            loading = false
        }
    }

    LaunchedEffect(searchQuery, searchOpen, accessToken, conversationId) {
        val token = accessToken ?: return@LaunchedEffect
        val query = searchQuery.trim()
        if (!searchOpen || query.isBlank()) {
            searchResults = emptyList()
            searchHasMore = false
            searchBeforeId = null
            searchLoading = false
            searchLoadingMore = false
            return@LaunchedEffect
        }
        delay(260)
        searchLoading = true
        searchBeforeId = null
        runCatching { withContext(Dispatchers.IO) { NativeSocialApi.searchMessages(token, conversationId, query) } }
            .onSuccess {
                searchResults = it.items
                searchHasMore = it.hasMore
                searchBeforeId = it.items.lastOrNull()?.id
            }
            .onFailure { error = it.message ?: nativePhrase("聊天内容搜索失败。", "Chat search failed.") }
        searchLoading = false
    }

    fun loadMoreSearchResults() {
        val token = accessToken ?: return
        val query = searchQuery.trim()
        val beforeId = searchBeforeId ?: return
        if (!searchOpen || query.isBlank() || !searchHasMore || searchLoading || searchLoadingMore) return
        scope.launch {
            searchLoadingMore = true
            runCatching {
                withContext(Dispatchers.IO) { NativeSocialApi.searchMessages(token, conversationId, query, beforeId = beforeId) }
            }.onSuccess { result ->
                searchResults = (searchResults + result.items).distinctBy { it.id }
                searchHasMore = result.hasMore
                searchBeforeId = result.items.lastOrNull()?.id ?: searchBeforeId
            }.onFailure { error = it.message ?: nativePhrase("更多聊天搜索结果读取失败。", "Failed to load more chat search results.") }
            searchLoadingMore = false
        }
    }

    // 候选只对应当前光标片段；先清空旧项，避免短暂选择到上一个关键词的结果。
    LaunchedEffect(input.text, input.selection, input.composition, accessToken, conversationId) {
        val token = accessToken
        val active = nativeActiveMention(input)
        mentionCandidates = emptyList()
        if (token.isNullOrBlank() || active == null) {
            mentionLoading = false
            return@LaunchedEffect
        }
        mentionLoading = true
        delay(180)
        runCatching {
            withContext(Dispatchers.IO) { NativeFriendsApi.searchUsers(token, active.query, limit = 20, mention = true, conversationId = conversationId) }
        }.onSuccess {
            mentionCandidates = it
        }.onFailure {
            // 被新输入取消的查询保持取消语义，不能清空下一次查询的候选。
            if (it is CancellationException) throw it
            mentionCandidates = emptyList()
        }
        mentionLoading = false
    }

    DisposableEffect(sessionKey, conversationId) {
        val token = accessToken
        if (token.isNullOrBlank()) {
            onDispose { }
        } else {
            val instance = NativeChatSocket(
                accessToken = token,
                onMessage = { message ->
                    if (message.conversationId == conversationId) {
                        scope.launch {
                            // 追加前记录是否停在最新消息；追加后再判断会把原本在底部的用户误判为阅读历史。
                            val wasAtLatest = messages.isEmpty() || !listState.canScrollForward
                            val ownMessage = message.senderUsername.isNotBlank() && message.senderUsername == session?.username
                            messages = (messages + message).distinctBy { it.id }.sortedBy { it.id }
                            if (ownMessage || (wasAtLatest && highlightedMessageId == null)) pendingFollowMessageId = message.id
                            withContext(Dispatchers.IO) { runCatching { NativeSocialApi.markConversationRead(token, conversationId) } }
                        }
                    }
                },
                onRead = { _, _ -> },
                onMessagesDeleted = { deletedConversationId, deletedMessageIds ->
                    scope.launch {
                        if (deletedConversationId != conversationId) return@launch
                        messages = messages.filterNot { it.id in deletedMessageIds }
                        if (highlightedMessageId?.let(deletedMessageIds::contains) == true) highlightedMessageId = null
                        if (actionMessage?.id?.let(deletedMessageIds::contains) == true) actionMessage = null
                        if (quotedMessage?.id?.let(deletedMessageIds::contains) == true) quotedMessage = null
                        if (pendingMessageOperation?.messages?.any { it.id in deletedMessageIds } == true) pendingMessageOperation = null
                        pinnedMessages = pinnedMessages.filterNot { it.id in deletedMessageIds }
                        selectedMessageIds = selectedMessageIds - deletedMessageIds.toSet()
                        if (selectedMessageIds.isEmpty()) selectionMode = false
                    }
                },
                onConversationCleared = { clearedConversationId ->
                    if (clearedConversationId == conversationId) {
                        scope.launch {
                            messages = emptyList()
                            pinnedMessages = emptyList()
                            pinnedCursor = 0
                            highlightedMessageId = null
                            selectionMode = false
                            selectedMessageIds = emptySet()
                        }
                    }
                },
                onConversationDeleted = { deletedConversationId ->
                    if (deletedConversationId == conversationId && pendingConversationOperation != NativeConversationOperation.Delete) {
                        scope.launch { onBack() }
                    }
                },
                onError = { message -> scope.launch { error = message } },
                onConnectionChanged = { value -> scope.launch { connected = value } },
                onReauthenticate = onReauthenticate,
            )
            chatSocket = instance
            instance.connect()
            onDispose {
                instance.close()
                if (chatSocket === instance) chatSocket = null
            }
        }
    }

    LaunchedEffect(accessToken, conversationId) {
        accessToken?.let { chatSocket?.updateAccessToken(it) }
    }

    // 只有初次进入、本人发送或原本停在底部时跟随，手动阅读历史及定位状态不被新消息打断。
    LaunchedEffect(pendingFollowMessageId, loading, loadingOlder, highlightedMessageId) {
        val requestedId = pendingFollowMessageId ?: return@LaunchedEffect
        if (loading || loadingOlder || highlightedMessageId != null) return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == requestedId }
        if (index < 0) return@LaunchedEffect
        // 等消息列表应用本次快照后滚动，避免旧布局的行数或高度让最新消息仍停在视野外。
        androidx.compose.runtime.withFrameNanos { }
        listState.animateScrollToItem(nativeChatListItemIndex(index, hasMore))
        if (pendingFollowMessageId == requestedId) pendingFollowMessageId = null
    }

    /** 定位 messageId 对应消息，必要时读取真实上下文，并在整行高亮两秒后恢复。 */
    fun focusMessage(messageId: Int) {
        // 当前会话令牌决定定位权限，取消待执行的底部跟随，防止与搜索或置顶争夺滚动位置。
        val token = accessToken ?: return
        pendingFollowMessageId = null
        scope.launch {
            try {
                // 未加载的消息按 ID 单独读取，合并后保持服务端顺序与唯一性。
                highlightedMessageId = messageId
                if (messages.none { it.id == messageId }) {
                    val focused = withContext(Dispatchers.IO) { NativeSocialApi.getMessageContext(token, conversationId, messageId) }
                    messages = (messages + focused).distinctBy { it.id }.sortedBy { it.id }
                }
                delay(80)
                val index = messages.indexOfFirst { it.id == messageId }
                if (index < 0) throw IllegalStateException(nativePhrase("消息不存在或当前不可见。", "The message does not exist or is not visible."))
                // 高亮计时从定位完成开始，不把网络加载耗时计入用户可见的两秒。
                listState.animateScrollToItem(nativeChatListItemIndex(index, hasMore))
                delay(2_000)
                if (highlightedMessageId == messageId) highlightedMessageId = null
            } catch (exception: Exception) {
                // 失效消息明确提示并移除高亮，不留下看似已成功定位的状态。
                highlightedMessageId = null
                error = exception.message ?: nativePhrase("无法定位到这条消息。", "Unable to locate this message.")
            }
        }
    }

    LaunchedEffect(target.focusMessageId, loading) {
        val messageId = target.focusMessageId ?: return@LaunchedEffect
        if (!loading && handledFocusMessageId != messageId) {
            handledFocusMessageId = messageId
            focusMessage(messageId)
        }
    }

    /** 加载当前会话更早记录，以首个可见消息的稳定 ID 和像素偏移保持原阅读位置。 */
    fun loadOlderMessages() {
        // 不以“读取更早”按钮作为锚点；它在最后一页消失时会让旧 index 指向另一条消息。
        val token = accessToken ?: return
        val firstId = messages.firstOrNull()?.id ?: return
        if (loadingOlder || !hasMore) return
        val anchorItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Int }
        val anchorMessageId = anchorItem?.key as? Int
        val anchorOffset = anchorItem?.offset ?: 0
        pendingFollowMessageId = null
        scope.launch {
            loadingOlder = true
            try {
                // 按服务端游标读取并以消息 ID 去重，追加的新消息不会改变原有锚点身份。
                val result = withContext(Dispatchers.IO) {
                    NativeSocialApi.listMessages(token, conversationId, beforeId = firstId)
                }
                messages = (result.items + messages).distinctBy { it.id }.sortedBy { it.id }
                hasMore = result.hasMore
                // 在下一次重测量时同时应用新索引与偏移，避免任意延迟期间先跳走再恢复。
                val anchorIndex = messages.indexOfFirst { it.id == anchorMessageId }
                if (anchorIndex >= 0) {
                    listState.requestScrollToItem(nativeChatListItemIndex(anchorIndex, result.hasMore), -anchorOffset)
                }
            } catch (exception: Exception) {
                // 加载失败仍保留已显示消息及阅读位置，允许用户重试。
                error = exception.message ?: nativePhrase("更早的聊天记录加载失败。", "Failed to load older messages.")
            } finally {
                loadingOlder = false
            }
        }
    }

    fun sendMessage() {
        val body = input.text.trim()
        if ((body.isBlank() && pendingAttachments.isEmpty()) || sending || uploadingAttachments) return
        sending = true
        error = null
        val attachmentIds = pendingAttachments.map { it.id }
        chatSocket?.send(conversationId, body, attachmentIds, quotedMessage?.id) { ok, message ->
            scope.launch {
                sending = false
                if (ok) {
                    input = TextFieldValue("")
                    pendingAttachments = emptyList()
                    quotedMessage = null
                } else {
                    error = message ?: nativePhrase("消息发送失败。", "Failed to send the message.")
                }
            }
        } ?: run {
            sending = false
            error = nativePhrase("聊天连接尚未建立，请稍后重试。", "The chat connection is not ready. Try again shortly.")
        }
    }

    fun openMessageActions(message: NativeChatMessage) {
        if (message.type != "system") actionMessage = message
    }

    fun quoteMessage(message: NativeChatMessage) {
        quotedMessage = message
        actionMessage = null
    }

    fun requestMessageOperation(operation: NativeMessageOperation) {
        val message = actionMessage ?: return
        actionMessage = null
        pendingMessageOperation = NativePendingMessageOperation(operation, listOf(message))
    }

    fun clearMessageSelection() {
        selectionMode = false
        selectedMessageIds = emptySet()
    }

    fun beginMessageSelection(message: NativeChatMessage) {
        actionMessage = null
        selectionMode = true
        selectedMessageIds = setOf(message.id)
    }

    fun toggleMessageSelection(message: NativeChatMessage) {
        if (message.type == "system") return
        val next = selectedMessageIds.toMutableSet()
        if (!next.add(message.id)) next.remove(message.id)
        selectedMessageIds = next
        if (next.isEmpty()) clearMessageSelection()
    }

    fun requestSelectedOperation(operation: NativeMessageOperation) {
        val selected = messages.filter { it.id in selectedMessageIds && it.type != "system" }
        if (selected.isEmpty()) return
        pendingMessageOperation = NativePendingMessageOperation(operation, selected)
        clearMessageSelection()
    }

    fun openForwardDialogForMessages(messagesToForward: List<NativeChatMessage>) {
        if (messagesToForward.isEmpty()) return
        actionMessage = null
        forwardingMessages = messagesToForward.distinctBy { it.id }
        clearMessageSelection()
        forwardDialogOpen = true
        forwardLoading = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeSocialApi.listConversations(accessToken.orEmpty())
                }
            }.onSuccess { conversations ->
                forwardTargets = conversations.filterNot { it.id == conversationId }
            }.onFailure {
                forwardTargets = emptyList()
            error = it.message ?: nativePhrase("可转发会话加载失败。", "Failed to load available forwarding targets.")
            }
            forwardLoading = false
        }
    }

    fun openForwardDialog(message: NativeChatMessage) {
        openForwardDialogForMessages(listOf(message))
    }

    fun openReportDialog(message: NativeChatMessage) {
        actionMessage = null
        reportMessage = message
        reportReason = "spam"
        reportDetail = ""
    }

    fun forwardMessageTo(target: NativeConversationPreview) {
        if (forwardingMessages.isEmpty()) return
        val socket = chatSocket
        if (forwardBusy || socket == null) {
        if (socket == null) error = nativePhrase("聊天连接尚未建立，请稍后重试。", "The chat connection is not ready. Try again shortly.")
            return
        }
        forwardBusy = true
        socket.mutate(
            "chat:messages:forward",
            JSONObject()
                .put("sourceConversationId", conversationId)
                .put("targetConversationId", target.id)
                .put("messageIds", org.json.JSONArray(forwardingMessages.map { it.id })),
        ) { ok, errorMessage ->
            scope.launch {
                forwardBusy = false
                if (!ok) {
                    error = errorMessage ?: nativePhrase("消息转发失败。", "Failed to forward the message.")
                    return@launch
                }
                forwardDialogOpen = false
                val count = forwardingMessages.size
                forwardingMessages = emptyList()
                Toast.makeText(
                    context,
                    nativePhrase("已向“${target.title}”转发 $count 条消息。", "$count messages forwarded to “${target.title}”."),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun requestConversationOperation(operation: NativeConversationOperation) {
        conversationMenuOpen = false
        pendingConversationOperation = operation
    }

    fun updateConversationMute(muted: Boolean) {
        val token = accessToken
        if (token.isNullOrBlank() || conversationSettingsBusy) return
        conversationSettingsBusy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeSocialApi.updateConversationSettings(token, conversationId, muted)
                }
            }.onSuccess { updated ->
                conversationMuted = updated.muted
                Toast.makeText(
                    context,
                    if (updated.muted) nativePhrase("已开启消息免打扰。", "Chat notifications muted.") else nativePhrase("已关闭消息免打扰。", "Chat notifications unmuted."),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure {
                error = it.message ?: nativePhrase("免打扰设置失败。", "Failed to update mute settings.")
            }
            conversationSettingsBusy = false
        }
    }

    fun executeConversationOperation() {
        val pending = pendingConversationOperation ?: return
        val socket = chatSocket
        if (conversationActionBusy) return
        if (socket == null) {
            error = nativePhrase("聊天连接尚未建立，请稍后重试。", "The chat connection is not ready. Try again shortly.")
            return
        }
        conversationActionBusy = true
        val event = if (pending == NativeConversationOperation.Clear) "chat:conversation:clear" else "chat:conversation:delete"
        socket.mutate(event, JSONObject().put("conversationId", conversationId)) { ok, message ->
            scope.launch {
                conversationActionBusy = false
                if (!ok) {
                    error = message ?: nativePhrase("聊天操作失败。", "The chat action failed.")
                    return@launch
                }
                pendingConversationOperation = null
                if (pending == NativeConversationOperation.Clear) {
                    messages = emptyList()
                    pinnedMessages = emptyList()
                    pinnedCursor = 0
                    highlightedMessageId = null
                    selectionMode = false
                    selectedMessageIds = emptySet()
                    Toast.makeText(context, nativePhrase("聊天记录已清空。", "Chat history cleared."), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, nativePhrase("聊天已从当前列表删除。", "Chat removed from this list."), Toast.LENGTH_SHORT).show()
                    onBack()
                }
            }
        }
    }

    fun submitReport() {
        val message = reportMessage ?: return
        val token = accessToken
        val groupId = target.preview.groupId
        if (token.isNullOrBlank() || groupId == null || reportBusy) return
        reportBusy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeSocialApi.reportGroupMessage(token, groupId, message.id, reportReason, reportDetail)
                }
            }.onSuccess {
                reportMessage = null
                Toast.makeText(context, nativePhrase("举报已提交。", "Report submitted."), Toast.LENGTH_SHORT).show()
            }.onFailure {
                error = it.message ?: nativePhrase("举报提交失败。", "Failed to submit the report.")
            }
            reportBusy = false
        }
    }

    fun copyMessageBody(message: NativeChatMessage) {
        if (message.body.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(nativePhrase("聊天消息", "Chat message"), message.body))
        actionMessage = null
        Toast.makeText(context, nativePhrase("消息文字已复制。", "Message copied."), Toast.LENGTH_SHORT).show()
    }

    fun executeMessageOperation() {
        val pending = pendingMessageOperation ?: return
        val socket = chatSocket
        if (messageActionBusy) return
        if (pending.operation != NativeMessageOperation.TogglePin && socket == null) {
            error = nativePhrase("聊天连接尚未建立，请稍后重试。", "The chat connection is not ready. Try again shortly.")
            return
        }
        messageActionBusy = true
        if (pending.operation == NativeMessageOperation.TogglePin) {
            val token = accessToken
            scope.launch {
                runCatching {
                if (token.isNullOrBlank()) throw IllegalStateException(nativePhrase("登录状态已失效，请重新登录。", "Your session expired. Sign in again."))
                    withContext(Dispatchers.IO) {
                        NativeSocialApi.setMessagePin(token, conversationId, pending.message.id, !pending.message.isPinned)
                    }
                }.onSuccess { updated ->
                    messages = messages.map { if (it.id == updated.id) updated else it }
                    pinnedMessages = if (updated.isPinned) {
                        listOf(updated) + pinnedMessages.filterNot { it.id == updated.id }
                    } else {
                        pinnedMessages.filterNot { it.id == updated.id }
                    }
                    pinnedCursor = 0
                    pendingMessageOperation = null
                    Toast.makeText(
                        context,
                        if (updated.isPinned) nativePhrase("消息已置顶。", "Message pinned.") else nativePhrase("消息已取消置顶。", "Message unpinned."),
                        Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure {
                    error = it.message ?: nativePhrase("消息置顶操作失败。", "Failed to update the pinned message.")
                }
                messageActionBusy = false
            }
            return
        }
        val socketForMutation = socket ?: run {
            messageActionBusy = false
            error = nativePhrase("聊天连接尚未建立，请稍后重试。", "The chat connection is not ready. Try again shortly.")
            return
        }
        val (event, payload) = when (pending.operation) {
            NativeMessageOperation.Recall -> {
                "chat:message:recall" to JSONObject().put("messageId", pending.message.id)
            }
            NativeMessageOperation.DeleteSelf -> {
                "chat:messages:delete-self" to JSONObject()
                    .put("conversationId", conversationId)
                    .put("messageIds", org.json.JSONArray(pending.messages.map { it.id }))
            }
            NativeMessageOperation.DeleteEveryone -> {
                "chat:messages:delete-everyone" to JSONObject()
                    .put("conversationId", conversationId)
                    .put("messageIds", org.json.JSONArray(pending.messages.map { it.id }))
            }
            NativeMessageOperation.TogglePin -> return
        }
        socketForMutation.mutate(event, payload) { ok, message ->
            scope.launch {
                messageActionBusy = false
                if (!ok) {
                    error = message ?: nativePhrase("消息操作失败。", "The message action failed.")
                    return@launch
                }
                val deletedIds = pending.messages.map { it.id }.toSet()
                messages = messages.filterNot { it.id in deletedIds }
                pinnedMessages = pinnedMessages.filterNot { it.id in deletedIds }
                selectedMessageIds = selectedMessageIds - deletedIds
                pendingMessageOperation = null
                Toast.makeText(
                    context,
                    when (pending.operation) {
                        NativeMessageOperation.Recall -> nativePhrase("消息已撤回。", "Message recalled.")
                        NativeMessageOperation.DeleteSelf -> nativePhrase("已从当前账号删除 ${pending.messages.size} 条消息。", "${pending.messages.size} messages deleted from this account.")
                        NativeMessageOperation.DeleteEveryone -> nativePhrase("已双向删除 ${pending.messages.size} 条消息。", "${pending.messages.size} messages deleted for everyone.")
                        NativeMessageOperation.TogglePin -> if (pending.message.isPinned) nativePhrase("消息已取消置顶。", "Message unpinned.") else nativePhrase("消息已置顶。", "Message pinned.")
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // Android 15 边到边窗口不会自动缩小到键盘上方；显式消费 IME 留白，使输入栏和标题同时保留。
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                if (searchOpen) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(nativePhrase("搜索聊天内容", "Search messages"), fontSize = 13.sp) },
                        colors = loginFieldColors(),
                    )
                } else {
                    if (selectionMode) {
                        Text(nativePhrase("已选择 ${selectedMessageIds.size} 条消息", "${selectedMessageIds.size} messages selected"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Column {
                            Text(target.preview.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                            conversationMuted -> nativePhrase(
                                "已静音 · ${if (connected) "在线聊天" else "正在连接…"}",
                                "Muted · ${if (connected) "Online" else "Connecting..."}",
                            )
                            connected -> nativePhrase("在线聊天", "Online")
                            else -> nativePhrase("正在连接…", "Connecting...")
                                },
                                color = TextMuted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = { if (selectionMode) clearMessageSelection() else onBack() }) {
                    Icon(
                        if (selectionMode) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (selectionMode) nativePhrase("退出多选", "Exit selection") else nativePhrase("返回", "Back"),
                    )
                }
            },
            actions = {
                if (selectionMode) {
                    IconButton(
                        onClick = { openForwardDialogForMessages(messages.filter { it.id in selectedMessageIds }) },
                        enabled = selectedMessageIds.isNotEmpty() && connected,
                    ) { Icon(Icons.Filled.ArrowForward, contentDescription = nativePhrase("转发所选消息", "Forward selected messages"), tint = if (connected) Accent else TextMuted) }
                    IconButton(
                        onClick = { requestSelectedOperation(NativeMessageOperation.DeleteSelf) },
                        enabled = selectedMessageIds.isNotEmpty() && connected,
                    ) { Icon(Icons.Filled.Delete, contentDescription = nativePhrase("删除所选消息", "Delete selected messages"), tint = if (connected) Accent else TextMuted) }
                    IconButton(
                        onClick = { requestSelectedOperation(NativeMessageOperation.DeleteEveryone) },
                        enabled = selectedMessageIds.isNotEmpty() && connected,
                    ) { Icon(Icons.Filled.Delete, contentDescription = nativePhrase("双向删除所选消息", "Delete selected messages for everyone"), tint = if (connected) Coral else TextMuted) }
                } else if (searchOpen) {
                    IconButton(onClick = { searchOpen = false; searchQuery = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭搜索", "Close search"))
                    }
                } else {
                    IconButton(onClick = { searchOpen = true }) { Icon(Icons.Filled.Search, contentDescription = nativePhrase("搜索聊天内容", "Search messages")) }
                    if (connected && callLauncher.connected && !callLauncher.active && target.preview.kind != "group") {
                        IconButton(onClick = { callLauncher.start(conversationId, "voice") }) {
                        Icon(Icons.Filled.Call, contentDescription = nativePhrase("发起语音通话", "Start voice call"), tint = Accent)
                        }
                        IconButton(onClick = { callLauncher.start(conversationId, "video") }) {
                        Icon(Icons.Filled.Videocam, contentDescription = nativePhrase("发起视频通话", "Start video call"), tint = Accent)
                        }
                    }
                    IconButton(onClick = { conversationMenuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = nativePhrase("聊天设置", "Chat settings"))
                    }
                    Icon(
                        if (connected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                        contentDescription = if (connected) nativePhrase("聊天已连接", "Chat connected") else nativePhrase("聊天未连接", "Chat disconnected"),
                        tint = if (connected) Accent else TextMuted,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        if (!error.isNullOrBlank()) {
            Text(
                text = error!!,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (searchOpen && searchQuery.isNotBlank()) {
            GlassCard(
                modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth(),
                shape = HlovetUi.cardShape,
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (searchLoading) {
                            Text(nativePhrase("正在搜索…", "Searching..."), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
                    } else if (searchResults.isEmpty()) {
                            Text(nativePhrase("没有找到匹配消息。", "No matching messages."), color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                            contentPadding = PaddingValues(vertical = 2.dp),
                        ) {
                            items(searchResults, key = { it.id }) { result ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { focusMessage(result.id); searchOpen = false; searchQuery = "" }.padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(result.senderName, color = Accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(result.body.ifBlank { nativePhrase("附件消息", "Attachment") }, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(result.createdAt, color = TextMuted, fontSize = 10.sp)
                                }
                            }
                            if (searchHasMore) {
                                item {
                                    TextButton(onClick = ::loadMoreSearchResults, enabled = !searchLoadingMore, modifier = Modifier.fillMaxWidth()) {
                                Text(if (searchLoadingMore) nativePhrase("读取中…", "Loading...") else nativePhrase("读取更多结果", "Load more results"), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!searchOpen && pinnedMessages.isNotEmpty()) {
            val pinned = pinnedMessages.getOrNull(pinnedCursor % pinnedMessages.size) ?: pinnedMessages.first()
            GlassCard(
                modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth().clickable {
                    focusMessage(pinned.id)
                    pinnedCursor = (pinnedCursor + 1) % pinnedMessages.size
                },
                shape = HlovetUi.rowShape,
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PushPin, contentDescription = nativePhrase("置顶消息", "Pinned message"), tint = Accent, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nativePhrase("置顶消息", "Pinned message"), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        Text(pinned.body.ifBlank { nativePhrase("附件消息", "Attachment") }, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(nativePhrase("点击定位", "Tap to locate"), color = TextMuted, fontSize = 10.sp)
                }
            }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState(nativePhrase("正在读取聊天记录…", "Loading chat history...")) }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat-messages"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (hasMore) {
                        item(key = "older-messages") {
                            TextButton(
                                onClick = ::loadOlderMessages,
                                enabled = !loadingOlder,
                                modifier = Modifier.fillMaxWidth().testTag("chat-load-older"),
                            ) { Text(if (loadingOlder) nativePhrase("读取中…", "Loading...") else nativePhrase("读取更早消息", "Load older messages")) }
                        }
                    }
                    if (messages.isEmpty()) {
                        item { EmptyState(nativePhrase("还没有聊天消息。", "No messages yet.")) }
                    } else {
                        items(messages, key = { it.id }) { message ->
                            NativeChatMessageBubble(
                                message = message,
                                own = message.senderUsername.isNotBlank() && message.senderUsername == session?.username,
                                highlighted = highlightedMessageId == message.id,
                                selectionMode = selectionMode,
                                selected = message.id in selectedMessageIds,
                                accessToken = accessToken,
                                cacheOwner = NativeOfflineOwner.forSession(session),
                                onPreviewAttachment = { previewAttachment = it },
                                onOpenQuotedMessage = ::focusMessage,
                                onOpenActions = { if (selectionMode) toggleMessageSelection(message) else openMessageActions(message) },
                                onToggleSelection = { toggleMessageSelection(message) },
                            )
                        }
                    }
                }
                if (pendingAttachments.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(pendingAttachments, key = { it.id }) { attachment ->
                            Surface(
                                color = SurfaceAccent,
                                shape = HlovetUi.rowShape,
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (attachment.kind == "image") Icons.Filled.Image else Icons.Filled.AttachFile,
                                        contentDescription = null,
                                        tint = Accent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(attachment.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                                    IconButton(
                                        onClick = { pendingAttachments = pendingAttachments.filterNot { it.id == attachment.id } },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                    Icon(Icons.Filled.Close, contentDescription = nativePhrase("移除附件", "Remove attachment"), modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                quotedMessage?.let { quoted ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        color = SurfaceAccent,
                        shape = HlovetUi.rowShape,
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 11.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Reply, contentDescription = null, tint = Accent, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Column(Modifier.weight(1f)) {
                                Text(nativePhrase("引用 ${quoted.senderName}", "Quoting ${quoted.senderName}"), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    nativeMentionText(quoted.body.ifBlank { nativePhrase("附件消息", "Attachment") }, Accent, HlovetUi.accentSoft),
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { quotedMessage = null }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = nativePhrase("取消引用", "Cancel quote"), tint = TextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (voiceRecording) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        color = SurfaceAccent,
                        shape = HlovetUi.rowShape,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                                Icon(Icons.Filled.Mic, contentDescription = nativePhrase("正在录音", "Recording"), tint = Coral, modifier = Modifier.size(20.dp))
                            Text(
                                    text = nativePhrase(
                                        "录音中 ${voiceRecordingSeconds / 60}:${(voiceRecordingSeconds % 60).toString().padStart(2, '0')}",
                                        "Recording ${voiceRecordingSeconds / 60}:${(voiceRecordingSeconds % 60).toString().padStart(2, '0')}",
                                    ),
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = { stopVoiceRecording(send = false) },
                                modifier = Modifier.size(38.dp),
                            ) {
                                    Icon(Icons.Filled.Close, contentDescription = nativePhrase("取消录音", "Cancel recording"), tint = TextMuted, modifier = Modifier.size(19.dp))
                            }
                            IconButton(
                                onClick = { stopVoiceRecording(send = true) },
                                modifier = Modifier.size(38.dp),
                            ) {
                                    Icon(Icons.Filled.Send, contentDescription = nativePhrase("发送语音", "Send voice message"), tint = Accent, modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = ::requestVoiceRecording,
                            enabled = connected && !sending && !uploadingAttachments && pendingAttachments.isEmpty(),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                        contentDescription = nativePhrase("录制语音消息", "Record voice message"),
                                tint = if (connected && !uploadingAttachments && pendingAttachments.isEmpty()) Accent else TextMuted,
                            )
                        }
                        IconButton(
                            onClick = { attachmentPicker.launch(arrayOf("*/*")) },
                            enabled = connected && !sending && !uploadingAttachments && pendingAttachments.size < 9,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Filled.AttachFile,
                                        contentDescription = if (uploadingAttachments) nativePhrase("正在上传附件", "Uploading attachment") else nativePhrase("添加附件", "Add attachment"),
                                tint = if (connected && !uploadingAttachments) Accent else TextMuted,
                            )
                        }
                        NativeMentionTextField(
                            value = input,
                            onValueChange = { input = it },
                            candidates = mentionCandidates,
                            loading = mentionLoading,
                            modifier = Modifier.weight(1f),
                            enabled = connected && !sending,
                            placeholder = nativePhrase("输入消息…", "Message..."),
                        )
                        IconButton(
                            onClick = ::sendMessage,
                            enabled = connected && (input.text.isNotBlank() || pendingAttachments.isNotEmpty()) && !sending && !uploadingAttachments,
                            modifier = Modifier.size(40.dp),
                        ) {
                                Icon(Icons.Filled.Send, contentDescription = nativePhrase("发送", "Send"), tint = if (connected) Accent else TextMuted)
                        }
                    }
                }
            }
        }
    }
    previewAttachment?.let { attachment ->
        NativeChatImagePreviewDialog(
            attachment = attachment,
            accessToken = accessToken,
            cacheOwner = NativeOfflineOwner.forSession(session),
            onDismiss = { previewAttachment = null },
        )
    }
    actionMessage?.let { message ->
        HlovetAlertDialog(
            onDismissRequest = { if (!messageActionBusy) actionMessage = null },
            title = { Text(nativePhrase("消息操作", "Message actions")) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (message.body.isNotBlank()) {
                        TextButton(onClick = { copyMessageBody(message) }, modifier = Modifier.fillMaxWidth()) {
                            Text(nativePhrase("复制文字", "Copy text"), modifier = Modifier.fillMaxWidth())
                        }
                    }
                    TextButton(onClick = { quoteMessage(message) }, modifier = Modifier.fillMaxWidth()) {
                        Text(nativePhrase("引用回复", "Reply with quote"), modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { openForwardDialog(message) }, modifier = Modifier.fillMaxWidth()) {
                        Text(nativePhrase("转发消息", "Forward message"), modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { beginMessageSelection(message) }, modifier = Modifier.fillMaxWidth()) {
                        Text(nativePhrase("选择多条消息", "Select messages"), modifier = Modifier.fillMaxWidth())
                    }
                    if (target.preview.groupId != null) {
                        TextButton(
                            onClick = { requestMessageOperation(NativeMessageOperation.TogglePin) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (message.isPinned) nativePhrase("取消置顶", "Unpin") else nativePhrase("置顶消息", "Pin message"),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (message.senderUsername != session?.username) {
                            TextButton(onClick = { openReportDialog(message) }, modifier = Modifier.fillMaxWidth()) {
                                Text(nativePhrase("举报消息", "Report message"), color = Coral, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    if (message.senderUsername == session?.username) {
                        TextButton(
                            onClick = { requestMessageOperation(NativeMessageOperation.Recall) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(nativePhrase("撤回消息", "Recall message"), modifier = Modifier.fillMaxWidth()) }
                    }
                    TextButton(
                        onClick = { requestMessageOperation(NativeMessageOperation.DeleteSelf) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(nativePhrase("从当前账号删除", "Delete for this account"), modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = { requestMessageOperation(NativeMessageOperation.DeleteEveryone) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(nativePhrase("双向删除", "Delete for everyone"), color = Coral, modifier = Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionMessage = null }, enabled = !messageActionBusy) {
                    Text(nativePhrase("关闭", "Close"))
                }
            },
        )
    }
    pendingMessageOperation?.let { pending ->
        val selectedCountLabel = if (pending.messages.size > 1) {
            nativePhrase("（${pending.messages.size} 条）", " (${pending.messages.size})")
        } else {
            ""
        }
        val title = when (pending.operation) {
            NativeMessageOperation.Recall -> nativePhrase("撤回这条消息", "Recall this message")
            NativeMessageOperation.DeleteSelf -> nativePhrase("从当前账号删除", "Delete for this account") + selectedCountLabel
            NativeMessageOperation.DeleteEveryone -> nativePhrase("双向删除", "Delete for everyone") + selectedCountLabel
            NativeMessageOperation.TogglePin -> if (pending.message.isPinned) {
                nativePhrase("取消置顶消息", "Unpin message")
            } else {
                nativePhrase("置顶消息", "Pin message")
            }
        }
        val description = when (pending.operation) {
            NativeMessageOperation.Recall -> nativePhrase(
                "撤回后，双方聊天会保留一条系统提示，原消息和附件将被移除。",
                "A system notice will remain for both participants, while the original message and its attachments will be removed.",
            )
            NativeMessageOperation.DeleteSelf -> nativePhrase(
                "选中的 ${pending.messages.size} 条消息只会从当前账号隐藏，对方仍然可以查看。",
                "The selected ${pending.messages.size} message(s) will only be hidden from this account. Other participants can still view them.",
            )
            NativeMessageOperation.DeleteEveryone -> nativePhrase(
                "选中的 ${pending.messages.size} 条消息会从双方可见记录中永久删除，关联附件也会被移除。",
                "The selected ${pending.messages.size} message(s) and their attachments will be permanently removed for everyone.",
            )
            NativeMessageOperation.TogglePin -> if (pending.message.isPinned) {
                nativePhrase(
                    "取消置顶后，这条消息会从聊天顶部的置顶提示中移除。",
                    "This message will be removed from the pinned message shown at the top of the conversation.",
                )
            } else {
                nativePhrase(
                    "置顶后，群聊成员可以从聊天顶部快速定位这条消息。",
                    "Group members will be able to locate this message from the pinned message shown at the top of the conversation.",
                )
            }
        }
        HlovetAlertDialog(
            onDismissRequest = { if (!messageActionBusy) pendingMessageOperation = null },
            title = { Text(title) },
            text = { Text(description, fontSize = 13.sp) },
            dismissButton = {
                TextButton(
                    onClick = { pendingMessageOperation = null },
                    enabled = !messageActionBusy,
                ) { Text(nativePhrase("取消", "Cancel")) }
            },
            confirmButton = {
                TextButton(onClick = ::executeMessageOperation, enabled = !messageActionBusy) {
                    Text(
                        if (messageActionBusy) nativePhrase("处理中…", "Processing...") else nativePhrase("确认", "Confirm"),
                        color = if (pending.operation == NativeMessageOperation.DeleteEveryone) Coral else Accent,
                    )
                }
            },
        )
    }
    if (conversationMenuOpen) {
        HlovetAlertDialog(
            onDismissRequest = { conversationMenuOpen = false },
            title = { Text(nativePhrase("聊天设置", "Chat settings")) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(nativePhrase("消息免打扰", "Mute notifications"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                nativePhrase("关闭后仍可在消息页查看新消息", "New messages will still appear in Messages."),
                                color = TextMuted,
                                fontSize = 11.sp,
                            )
                        }
                        HlovetSwitch(
                            checked = conversationMuted,
                            enabled = !conversationSettingsBusy,
                            onCheckedChange = ::updateConversationMute,
                        )
                    }
                    HorizontalDivider(color = HlovetUi.divider)
                    TextButton(
                        onClick = { requestConversationOperation(NativeConversationOperation.Clear) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(nativePhrase("清空聊天记录", "Clear chat history"), modifier = Modifier.fillMaxWidth()) }
                    TextButton(
                        onClick = { requestConversationOperation(NativeConversationOperation.Delete) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(nativePhrase("从当前列表删除聊天", "Remove chat from list"), color = Coral, modifier = Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = {
                TextButton(onClick = { conversationMenuOpen = false }) { Text(nativePhrase("关闭", "Close")) }
            },
        )
    }
    pendingConversationOperation?.let { pending ->
        val isClear = pending == NativeConversationOperation.Clear
        HlovetAlertDialog(
            onDismissRequest = { if (!conversationActionBusy) pendingConversationOperation = null },
            title = {
                Text(if (isClear) nativePhrase("清空聊天记录", "Clear chat history") else nativePhrase("删除聊天", "Remove chat"))
            },
            text = {
                Text(
                    if (isClear) {
                        nativePhrase(
                            "只会清空当前账号在这个会话中的历史记录，对方的记录不受影响。此操作不可恢复。",
                            "This only clears the conversation history for the current account. Other participants are unaffected. This cannot be undone.",
                        )
                    } else {
                        nativePhrase(
                            "聊天会从当前账号的会话列表隐藏，不会删除对方的记录；之后仍可通过好友或群聊入口重新打开。",
                            "The chat will be hidden from this account's conversation list without deleting anyone else's history. You can reopen it later from Friends or Groups.",
                        )
                    },
                    fontSize = 13.sp,
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingConversationOperation = null },
                    enabled = !conversationActionBusy,
                ) { Text(nativePhrase("取消", "Cancel")) }
            },
            confirmButton = {
                TextButton(onClick = ::executeConversationOperation, enabled = !conversationActionBusy) {
                    Text(
                        if (conversationActionBusy) nativePhrase("处理中…", "Processing...") else nativePhrase("确认", "Confirm"),
                        color = if (isClear) Accent else Coral,
                    )
                }
            },
        )
    }
    if (forwardDialogOpen) {
        HlovetAlertDialog(
            onDismissRequest = {
                if (!forwardBusy) {
                    forwardDialogOpen = false
                    forwardingMessages = emptyList()
                }
            },
            title = {
                Text(nativePhrase("转发 ${forwardingMessages.size} 条消息", "Forward ${forwardingMessages.size} message(s)"))
            },
            text = {
                when {
                    forwardLoading -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        Text(nativePhrase("正在读取可转发会话…", "Loading available conversations..."), color = TextMuted, fontSize = 13.sp)
                    }
                    forwardTargets.isEmpty() -> Text(
                        nativePhrase("没有找到可以转发的其他会话。", "No other conversations are available."),
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(forwardTargets, key = { it.id }) { targetConversation ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !forwardBusy) { forwardMessageTo(targetConversation) }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(targetConversation.initials, Accent, modifier = Modifier.size(34.dp))
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(targetConversation.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (targetConversation.groupId != null) nativePhrase("群聊", "Group") else nativePhrase("直接会话", "Direct chat"),
                                        color = TextMuted,
                                        fontSize = 10.sp,
                                    )
                                }
                                if (forwardBusy) Text(nativePhrase("转发中…", "Forwarding..."), color = TextMuted, fontSize = 10.sp)
                                else Icon(
                                    Icons.Filled.ArrowForward,
                                    contentDescription = nativePhrase("转发", "Forward"),
                                    tint = Accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!forwardBusy) {
                            forwardDialogOpen = false
                            forwardingMessages = emptyList()
                        }
                    },
                    enabled = !forwardBusy,
                ) { Text(nativePhrase("关闭", "Close")) }
            },
        )
    }
    reportMessage?.let { reportedMessage ->
        val reportReasons = listOf(
            "spam" to nativePhrase("垃圾信息", "Spam"),
            "harassment" to nativePhrase("骚扰或攻击", "Harassment or abuse"),
            "illegal" to nativePhrase("违法违规", "Illegal content"),
            "privacy" to nativePhrase("隐私泄露", "Privacy violation"),
            "misinformation" to nativePhrase("虚假信息", "Misinformation"),
            "other" to nativePhrase("其他", "Other"),
        )
        HlovetAlertDialog(
            onDismissRequest = { if (!reportBusy) reportMessage = null },
            title = { Text(nativePhrase("举报群消息", "Report group message")) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        reportedMessage.body.ifBlank { nativePhrase("附件消息", "Attachment") },
                        color = TextMuted,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(reportReasons, key = { it.first }) { (value, label) ->
                            FilterChip(
                                selected = reportReason == value,
                                onClick = { reportReason = value },
                                label = { Text(label, fontSize = 11.sp) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = reportDetail,
                        onValueChange = { reportDetail = it.take(300) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        placeholder = { Text(nativePhrase("补充说明（可选）", "Additional details (optional)"), fontSize = 12.sp) },
                        colors = loginFieldColors(),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { reportMessage = null }, enabled = !reportBusy) { Text(nativePhrase("取消", "Cancel")) }
            },
            confirmButton = {
                TextButton(onClick = ::submitReport, enabled = !reportBusy) {
                    Text(if (reportBusy) nativePhrase("提交中…", "Submitting...") else nativePhrase("提交举报", "Submit report"), color = Coral)
                }
            },
        )
    }
}

@Composable
private fun NativeCallPanel(
    call: NativeCallSession,
    phase: String,
    preparing: Boolean,
    localVideoTrack: VideoTrack?,
    remoteVideoTrack: VideoTrack?,
    eglContext: EglBase.Context?,
    muted: Boolean,
    cameraEnabled: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
) {
    val isVideo = call.type == "video"
    GlassCard(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isVideo) Icons.Filled.Videocam else Icons.Filled.Call, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isVideo) nativePhrase("视频通话", "Video call") else nativePhrase("语音通话", "Voice call"),
                        color = Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(call.userName, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    when {
                        preparing -> nativePhrase("准备中…", "Preparing...")
                        phase == "incoming" -> nativePhrase("来电", "Incoming call")
                        phase == "outgoing" -> nativePhrase("等待接听", "Waiting for answer")
                        phase == "active" -> nativePhrase("通话中", "In call")
                        else -> nativePhrase("连接中", "Connecting")
                    },
                    color = TextMuted,
                    fontSize = 11.sp,
                )
            }
            if (isVideo && eglContext != null) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (remoteVideoTrack != null) {
                        NativeWebRtcVideoView(
                            track = remoteVideoTrack,
                            eglContext = eglContext,
                            mirror = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(nativePhrase("等待对方画面…", "Waiting for remote video..."), color = TextMuted, fontSize = 12.sp)
                    }
                    if (localVideoTrack != null) {
                        NativeWebRtcVideoView(
                            track = localVideoTrack,
                            eglContext = eglContext,
                            mirror = true,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(width = 96.dp, height = 128.dp),
                        )
                    }
                }
            } else if (!isVideo) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Avatar(call.userName.takeLast(2), Accent, large = true)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (phase == "incoming") {
                    IconButton(onClick = onDecline, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.CallEnd, contentDescription = nativePhrase("拒绝通话", "Decline call"), tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = onAccept, enabled = !preparing, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.Call, contentDescription = nativePhrase("接听通话", "Answer call"), tint = Accent)
                    }
                } else {
                    IconButton(onClick = onToggleMute, enabled = phase == "active" || phase == "connecting", modifier = Modifier.size(42.dp)) {
                        Icon(
                            if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = if (muted) nativePhrase("打开麦克风", "Unmute") else nativePhrase("静音", "Mute"),
                            tint = if (muted) MaterialTheme.colorScheme.error else Accent,
                        )
                    }
                    if (isVideo) {
                        IconButton(onClick = onToggleCamera, enabled = phase == "active" || phase == "connecting", modifier = Modifier.size(42.dp)) {
                            Icon(
                                if (cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                                contentDescription = if (cameraEnabled) nativePhrase("关闭摄像头", "Turn camera off") else nativePhrase("打开摄像头", "Turn camera on"),
                                tint = if (cameraEnabled) Accent else TextMuted,
                            )
                        }
                        IconButton(onClick = onSwitchCamera, enabled = phase == "active" || phase == "connecting", modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Filled.Cameraswitch, contentDescription = nativePhrase("切换摄像头", "Switch camera"), tint = Accent)
                        }
                    }
                    IconButton(onClick = onEnd, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Filled.CallEnd, contentDescription = nativePhrase("结束通话", "End call"), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeWebRtcVideoView(
    track: VideoTrack?,
    eglContext: EglBase.Context,
    mirror: Boolean,
    modifier: Modifier = Modifier,
) {
    var renderer by remember(eglContext) { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            SurfaceViewRenderer(viewContext).also {
                it.init(eglContext, null)
                it.setEnableHardwareScaler(true)
                it.setMirror(mirror)
                it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                renderer = it
            }
        },
        update = { it.setMirror(mirror) },
    )
    DisposableEffect(track, renderer) {
        val currentRenderer = renderer
        if (track != null && currentRenderer != null) track.addSink(currentRenderer)
        onDispose {
            if (track != null && currentRenderer != null) track.removeSink(currentRenderer)
            currentRenderer?.release()
        }
    }
}

/**
 * 展示 message 的整行消息与附件，own 决定排列方向，highlighted/selected 表示定位或多选状态。
 * accessToken/cacheOwner 约束附件访问与缓存归属，各回调仅通知预览、引用、消息操作和选择事件。
 */
@Composable
private fun NativeChatMessageBubble(
    message: NativeChatMessage,
    own: Boolean,
    highlighted: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    accessToken: String?,
    cacheOwner: String,
    onPreviewAttachment: (NativeChatAttachmentPreview) -> Unit,
    onOpenQuotedMessage: (Int) -> Unit,
    onOpenActions: (NativeChatMessage) -> Unit,
    onToggleSelection: () -> Unit = {},
) {
    // 整行背景和无障碍状态使用同一定位状态，便于读屏识别目标且不改变消息布局。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat-message-${message.id}")
            .semantics { if (highlighted) stateDescription = nativePhrase("已定位的消息", "Located message") }
            .background(
                when {
                    selected -> Accent.copy(alpha = .22f)
                    highlighted -> Accent.copy(alpha = .18f)
                    else -> Color.Transparent
                },
                HlovetUi.rowShape,
            )
            .clickable(enabled = selectionMode, onClick = onToggleSelection),
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        // 头像与选择框分列，自己的消息靠右，不因内容长度改变交互入口顺序。
        if (selectionMode && !own) {
            HlovetCheckbox(checked = selected, onCheckedChange = null, modifier = Modifier.size(28.dp))
        }
        if (!own) {
            Avatar(message.senderInitials, Accent, modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(8.dp))
        }
        Surface(
            color = if (own) Accent.copy(alpha = .16f) else MaterialTheme.colorScheme.surface.copy(alpha = .92f),
            shape = if (own) HlovetUi.outgoingMessageShape else HlovetUi.incomingMessageShape,
            tonalElevation = 0.dp,
            modifier = Modifier.widthIn(max = 310.dp),
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                if (!own) Text(message.senderName, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                // 引用仅在原消息仍可见时允许定位，不可见时保留上下文提示。
                message.quote?.let { quote ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .clickable(enabled = quote.available) { onOpenQuotedMessage(quote.id) },
                        color = SurfaceAccent,
                        shape = HlovetUi.rowShape,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Reply, contentDescription = null, tint = Accent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(quote.senderName, color = Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    nativeMentionText(if (quote.available) {
                                        quote.body.ifBlank { nativePhrase("附件消息", "Attachment") }
                                    } else {
                                        nativePhrase("原消息已不可见", "Original message is unavailable")
                                    }, Accent, HlovetUi.accentSoft),
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                // 正文和附件沿用同一鉴权及缓存归属，空内容保留消息类型和时间。
                if (message.body.isNotBlank()) {
                    NativeChatBodyText(message.body)
                }
                message.attachments.forEach { attachment ->
                    NativeChatAttachmentItem(
                        attachment = attachment,
                        accessToken = accessToken,
                        cacheOwner = cacheOwner,
                        onPreview = { onPreviewAttachment(attachment) },
                    )
                }
                if (message.body.isBlank() && message.attachments.isEmpty()) {
                    Text(nativePhrase("${message.type} 消息", "${message.type} message"), color = TextMuted, fontSize = 12.sp)
                }
                Text(message.createdAt, color = TextMuted, fontSize = 10.sp, modifier = Modifier.align(Alignment.End).padding(top = 4.dp))
            }
        }
        // 多选与普通操作互斥，避免勾选时触发另一项消息操作。
        if (selectionMode && own) {
            HlovetCheckbox(checked = selected, onCheckedChange = null, modifier = Modifier.size(28.dp))
        }
        if (message.type != "system" && !selectionMode) {
            IconButton(
                onClick = { onOpenActions(message) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = nativePhrase("消息操作", "Message actions"),
                    tint = TextMuted,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        if (own) Spacer(Modifier.width(4.dp))
    }
}

/** 使用与评论及输入框一致的提及背景呈现 body，不改变原消息文本或字形尺寸。 */
@Composable
private fun NativeChatBodyText(body: String) {
    // 只读消息允许以用户名结束，标签背景沿用账号外观的统一强调色。
    Text(nativeMentionText(body, Accent, HlovetUi.accentSoft), color = TextPrimary, fontSize = 14.sp)
}

/** 显示 attachment 的缩略图或文件行，accessToken/cacheOwner 保持当前账号下载隔离，onPreview 打开图片预览。 */
@Composable
internal fun NativeChatAttachmentItem(
    attachment: NativeChatAttachmentPreview,
    accessToken: String?,
    cacheOwner: String,
    onPreview: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var thumbnail by remember(attachment.id, accessToken) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(attachment.id, accessToken) { mutableStateOf(false) }
    var error by remember(attachment.id, accessToken) { mutableStateOf<String?>(null) }
    var downloadedAttachment by remember(attachment.id, accessToken, cacheOwner) { mutableStateOf<NativeAttachmentFile?>(null) }
    val errorViewRequester = remember(attachment.id, accessToken, cacheOwner) { BringIntoViewRequester() }

    // 图片缩略图只在当前附件及身份下读取，普通文件由用户主动下载。
    LaunchedEffect(attachment.id, attachment.thumbnailUrl, accessToken) {
        if (attachment.kind != "image") return@LaunchedEffect
        thumbnail = withContext(Dispatchers.IO) {
            NativeOfflineCache.loadBitmap(
                context = context,
                url = attachment.thumbnailUrl ?: attachment.downloadUrl,
                accessToken = accessToken,
                cacheOwner = cacheOwner,
            )
        }
    }

    /** 下载当前聊天或评论附件，避免重复点击并保留系统打开和另存为两种操作。 */
    fun openAttachment() {
        // 文件下载过程不改变消息正文，失败留在该附件行显示。
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    NativeOfflineCache.loadMediaFile(
                        context = context,
                        url = attachment.downloadUrl,
                        accessToken = accessToken,
                        cacheOwner = cacheOwner,
                        originalName = attachment.name,
                        mimeType = attachment.mimeType,
                    )
                } ?: throw IllegalStateException(nativePhrase("附件下载失败，请检查网络后重试。", "Attachment download failed. Check your connection and try again."))
                downloadedAttachment = NativeAttachmentFile(file, attachment.name, attachment.mimeType)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // 离开消息页面时停止后续展示，不恢复旧身份的附件面板。
                throw cancelled
            } catch (exception: Exception) {
                // 认证或下载失败仍保持消息可读，并允许再次点击重试。
                error = exception.message ?: nativePhrase("附件打开失败。", "Unable to open the attachment.")
            } finally {
                // 所有路径释放下载状态，不能让失败附件一直显示读取中。
                loading = false
            }
        }
    }

    // 图片保留预览，音视频提供手动行内播放，所有附件继续支持统一系统文件操作。
    downloadedAttachment?.let { file ->
        NativeAttachmentActionsDialog(file, onDismiss = { downloadedAttachment = null })
    }
    if (attachment.kind == "image") {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 76.dp, max = 180.dp)
                .clickable(onClick = onPreview),
            color = SurfaceLow,
            shape = HlovetUi.rowShape,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = attachment.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Filled.Image, contentDescription = attachment.name, tint = Accent, modifier = Modifier.size(28.dp))
                }
            }
        }
    } else if (attachment.kind == "audio" || attachment.kind == "video") {
        NativeInlineMediaAttachment(attachment, accessToken, cacheOwner, onDownload = ::openAttachment)
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(onClick = ::openAttachment),
            color = SurfaceLow,
            shape = HlovetUi.rowShape,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(attachment.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatNativeAttachmentSize(attachment.sizeBytes), color = TextMuted, fontSize = 10.sp)
                }
                if (loading) {
                    Text(nativePhrase("读取中…", "Loading..."), color = TextMuted, fontSize = 10.sp)
                } else {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = nativePhrase("下载附件", "Download attachment"),
                        tint = Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
    // 下载失败对普通文件和音视频一视同仁，完整错误留在附件旁以便重试。
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.bringIntoViewRequester(errorViewRequester)) }
    LaunchedEffect(error) {
        // 长弹窗中新出现的错误先等待布局，再滚入可视区，不能只存在于屏幕外语义树。
        if (!error.isNullOrBlank()) {
            androidx.compose.runtime.withFrameNanos { }
            errorViewRequester.bringIntoView()
        }
    }
}

/** 预览 attachment 原图，accessToken/cacheOwner 隔离身份；onDismiss 关闭弹窗，失败可在原位置重试。 */
@Composable
internal fun NativeChatImagePreviewDialog(
    attachment: NativeChatAttachmentPreview,
    accessToken: String?,
    cacheOwner: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(attachment.id, accessToken) { mutableStateOf<Bitmap?>(null) }
    var error by remember(attachment.id, accessToken) { mutableStateOf<String?>(null) }
    var reloadKey by remember(attachment.id, accessToken) { mutableIntStateOf(0) }
    // 原图读取与当前弹窗生命周期绑定，关闭或换账号后不展示迟到内容。
    LaunchedEffect(attachment.id, accessToken, cacheOwner, reloadKey) {
        error = null
        bitmap = withContext(Dispatchers.IO) {
            NativeOfflineCache.loadBitmap(
                context = context,
                url = attachment.downloadUrl,
                accessToken = accessToken,
                cacheOwner = cacheOwner,
            )
        }
        if (bitmap == null) {
            error = nativePhrase("图片加载失败，请检查网络后重试。", "Image loading failed. Check your connection and try again.")
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            shape = HlovetUi.cardShape,
            color = SurfaceLow,
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(attachment.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = nativePhrase("关闭图片预览", "Close image preview"), modifier = Modifier.size(18.dp))
                    }
                }
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 620.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        bitmap != null -> Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = attachment.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            TextButton(onClick = { reloadKey++ }) { Text(nativePhrase("重试", "Retry")) }
                        }
                        else -> Text(nativePhrase("正在读取图片…", "Loading image..."), color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    padding: PaddingValues,
    session: NativeSession?,
    profile: NativeProfileSummary?,
    profileLoading: Boolean,
    profileError: String?,
    articleCenterSummary: NativeArticleCenterSummary?,
    reputation: NativeReputationSummary?,
    reputationLoading: Boolean,
    reputationError: String?,
    onLogin: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenMyArticles: () -> Unit,
    onOpenPoints: () -> Unit,
    onOpenAccountTarget: (AccountTarget) -> Unit,
    onOpenPrivacy: () -> Unit,
    onLogout: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AppHeader(nativePhrase("我的", "Profile"), nativePhrase("账号、内容和安全设置", "Account, content, and security")) }
        item {
            Card(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = HlovetUi.cardShape,
                colors = CardDefaults.cardColors(containerColor = SurfaceAccent),
            ) {
                if (session == null) {
                    ListItem(
                        headlineContent = { Text(nativePhrase("登录账号", "Sign in"), fontWeight = FontWeight.Bold, fontSize = 15.sp) },
                        supportingContent = { Text(nativePhrase("登录后同步个人主题和卡片配置", "Sign in to sync your theme and card settings"), color = TextMuted, fontSize = 11.sp) },
                        leadingContent = { Icon(Icons.Filled.Login, contentDescription = null, tint = Accent) },
                        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.testTag("native-profile-login").clickable(onClick = onLogin),
                    )
                } else {
                    ListItem(
                        headlineContent = { Text(session.nickname, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
                        supportingContent = {
                            Text(
                                when {
                                    profileLoading -> nativePhrase(
                                        "@${session.username} · 正在读取个人资料",
                                        "@${session.username} · Loading profile",
                                    )
                                    profile?.publicArticleCount != null -> nativePhrase(
                                        "@${session.username} · ${profile.publicArticleCount} 篇公开文章",
                                        "@${session.username} · ${profile.publicArticleCount} public articles",
                                    )
                                    else -> nativePhrase(
                                        "@${session.username} · 已同步个人外观",
                                        "@${session.username} · Appearance synced",
                                    )
                                },
                                color = TextMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.testTag("native-profile-username"),
                            )
                        },
                        leadingContent = {
                            NativeCoverImage(
                                path = session.avatarUrl,
                                modifier = Modifier.size(40.dp),
                                fallbackText = session.nickname.ifBlank { session.username },
                                tint = Accent,
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onEditProfile) {
                                    Icon(Icons.Filled.Edit, contentDescription = nativePhrase("编辑个人资料", "Edit profile"), tint = Accent)
                                }
                                IconButton(onClick = onLogout) {
                                    Icon(Icons.Filled.Logout, contentDescription = nativePhrase("退出登录", "Sign out"), tint = TextMuted)
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(onClick = onEditProfile),
                    )
                }
            }
        }
        if (session != null && !profileError.isNullOrBlank()) {
            item {
                Text(
                    profileError,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
            }
        }
        if (session != null && profile != null) {
            item {
                ProfileStatsCard(profile)
            }
            item {
                ProfileAccountMottoCard(profile)
            }
        }
        if (session != null) {
            item {
                ProfileGrowthCard(
                    summary = reputation,
                    loading = reputationLoading,
                    error = reputationError,
                    onOpenPoints = onOpenPoints,
                )
            }
        }
        item {
            ProfileSection(
                title = nativePhrase("站点入口", "Site"),
                rows = listOf(
                    ProfileSectionRow(nativePhrase("个人工作台", "Dashboard")) { onOpenAccountTarget(AccountTarget.Dashboard) },
                    ProfileSectionRow(nativePhrase("站内导航", "Navigation")) { onOpenAccountTarget(AccountTarget.Navigation) },
                    ProfileSectionRow(nativePhrase("工具中心", "Tools")) { onOpenAccountTarget(AccountTarget.Tools) },
                ),
                icon = Icons.Filled.Explore,
            )
        }
        item {
            val contentRows = buildList {
                add(ProfileSectionRow(nativePhrase("我的文章", "My articles"), articleCenterSummary?.mine, onOpenMyArticles))
                add(ProfileSectionRow(nativePhrase("我的收藏", "Favorites"), articleCenterSummary?.favorites) { onOpenAccountTarget(AccountTarget.MyFavorites) })
                add(ProfileSectionRow(nativePhrase("我点赞的", "Liked"), articleCenterSummary?.liked) { onOpenAccountTarget(AccountTarget.MyLiked) })
                add(ProfileSectionRow(nativePhrase("稍后阅读", "Read later"), articleCenterSummary?.readLater) { onOpenAccountTarget(AccountTarget.ReadLater) })
                add(ProfileSectionRow(nativePhrase("阅读历史", "Reading history"), articleCenterSummary?.history) { onOpenAccountTarget(AccountTarget.ReadingHistory) })
                add(ProfileSectionRow(nativePhrase("我的订阅", "Subscriptions"), articleCenterSummary?.subscriptions) { onOpenAccountTarget(AccountTarget.SubscriptionFeed) })
                add(ProfileSectionRow(nativePhrase("我的合集", "My collections")) { onOpenAccountTarget(AccountTarget.MyCollections) })
                add(ProfileSectionRow(nativePhrase("文章模板", "Article templates")) { onOpenAccountTarget(AccountTarget.TemplateManagement) })
                add(ProfileSectionRow(nativePhrase("离线阅读", "Offline reading")) { onOpenAccountTarget(AccountTarget.Offline) })
                add(ProfileSectionRow(nativePhrase("匿名话题", "Anonymous topics")) { onOpenAccountTarget(AccountTarget.AnonymousTopics) })
                add(ProfileSectionRow(nativePhrase("推荐反馈", "Recommendation feedback")) { onOpenAccountTarget(AccountTarget.RecommendationFeedback) })
                if (session?.isSuperAdmin == true || session?.isAdministrator == true) {
                    add(ProfileSectionRow(nativePhrase("专题管理", "Topic management")) { onOpenAccountTarget(AccountTarget.TopicManagement) })
                }
            }
            ProfileSection(
                title = nativePhrase("内容管理", "Content"),
                rows = contentRows,
                icon = Icons.Filled.LibraryBooks,
            )
        }
        item {
            ProfileSection(
                title = nativePhrase("账号与隐私", "Account and privacy"),
                rows = listOf(
                    ProfileSectionRow(nativePhrase("账号凭据", "Credentials"), onClick = onOpenPrivacy),
                    ProfileSectionRow(nativePhrase("登录设备", "Signed-in devices")) { onOpenAccountTarget(AccountTarget.Sessions) },
                    ProfileSectionRow(nativePhrase("双因素认证", "Two-factor authentication"), onClick = onOpenPrivacy),
                    ProfileSectionRow(nativePhrase("通行密钥", "Passkeys"), onClick = onOpenPrivacy),
                    ProfileSectionRow(nativePhrase("集成管理", "Integrations")) { onOpenAccountTarget(AccountTarget.Integrations) },
                    ProfileSectionRow(nativePhrase("我的举报", "My reports")) { onOpenAccountTarget(AccountTarget.MyReports) },
                ),
                icon = Icons.Filled.Settings,
            )
        }
        item {
            ProfileSection(
                title = nativePhrase("积分与资源", "Points and resources"),
                rows = listOf(
                    ProfileSectionRow(nativePhrase("资源中心", "Resource center")) { onOpenAccountTarget(AccountTarget.ResourceCenter) },
                    ProfileSectionRow(nativePhrase("积分明细", "Points activity")) { onOpenAccountTarget(AccountTarget.Points) },
                    ProfileSectionRow(nativePhrase("兑换记录", "Redemptions")) { onOpenAccountTarget(AccountTarget.ResourceOrders) },
                    ProfileSectionRow(nativePhrase("资源收益", "Resource earnings")) { onOpenAccountTarget(AccountTarget.ResourceEarnings) },
                ),
                icon = Icons.Filled.AutoAwesome,
            )
        }
        item {
            ProfileSection(
                title = nativePhrase("AI 助手", "AI assistant"),
                rows = listOf(ProfileSectionRow(nativePhrase("AI 助手", "AI assistant")) { onOpenAccountTarget(AccountTarget.AiAssistant) }),
                icon = Icons.Filled.AutoAwesome,
            )
        }
        item {
            ProfileSection(
                title = nativePhrase("帮助与反馈", "Help and feedback"),
                rows = listOf(
                    ProfileSectionRow(nativePhrase("站点公告", "Announcements")) { onOpenAccountTarget(AccountTarget.Announcements) },
                    ProfileSectionRow(nativePhrase("问题反馈", "Report a problem")) { onOpenAccountTarget(AccountTarget.Feedback) },
                    ProfileSectionRow(nativePhrase("意见建议", "Suggestions")) { onOpenAccountTarget(AccountTarget.Suggestions) },
                ),
                icon = Icons.Filled.ChatBubbleOutline,
            )
        }
    }
}

private data class ProfileSectionRow(
    val label: String,
    val count: Int? = null,
    val onClick: () -> Unit,
)

@Composable
private fun ProfileSection(
    title: String,
    rows: List<ProfileSectionRow>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(title, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, bottom = 7.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = HlovetUi.cardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column {
                rows.forEachIndexed { index, row ->
                    ListItem(
                        headlineContent = { Text(row.label, fontSize = 13.sp) },
                        leadingContent = { Icon(icon, contentDescription = null, tint = Accent) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                row.count?.let { count ->
                                    Text(count.toString(), color = TextMuted, fontSize = 11.sp)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(onClick = row.onClick)
                    )
                    if (index < rows.lastIndex) HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

/** 为 session 展示个人/可见合集，initialView 指定入口；身份变化时销毁旧表单、协程和选图结果。 */
@Composable
internal fun MyCollectionsScreen(
    session: NativeSession?,
    initialView: String = "mine",
    onBack: () -> Unit,
    onOpenCollection: (RemoteCollection) -> Unit,
) {
    // 同一路由换账号也必须重建状态，不能把旧账号迟到的保存结果显示给新账号。
    androidx.compose.runtime.key(session?.userId, session?.accessToken) {
        MyCollectionsContent(session, initialView, onBack, onOpenCollection)
    }
}

/** 维护当前 session 的合集及文章分页；导航回调不参与持久化，接口继续校验所有权。 */
@Composable
private fun MyCollectionsContent(
    session: NativeSession?,
    initialView: String,
    onBack: () -> Unit,
    onOpenCollection: (RemoteCollection) -> Unit,
) {
    // 列表、编辑草稿与错误分开保存，读取失败不能清空已显示内容或覆盖未保存输入。
    val token = session?.accessToken
    val scope = rememberCoroutineScope()
    var collections by remember(token) { mutableStateOf<List<RemoteCollection>>(emptyList()) }
    var articles by remember(token) { mutableStateOf<List<RemoteArticle>>(emptyList()) }
    var loading by remember(token) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember(token) { mutableIntStateOf(0) }
    var editorOpen by remember { mutableStateOf(false) }
    var editorCollection by remember { mutableStateOf<RemoteCollection?>(null) }
    var editorName by remember { mutableStateOf("") }
    var editorDescription by remember { mutableStateOf("") }
    var editorCoverPath by remember { mutableStateOf("") }
    var editorVisibility by remember { mutableStateOf("public") }
    var editorArticleIds by remember { mutableStateOf<List<Int>>(emptyList()) }
    var editorArticleTitles by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var coverFile by remember { mutableStateOf<NativeUploadFile?>(null) }
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var coverLoading by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val editorScroll = rememberScrollState()
    var articleQuery by remember { mutableStateOf("") }
    var articlePage by remember { mutableIntStateOf(1) }
    var articleTotalPages by remember { mutableIntStateOf(1) }
    var articleLoading by remember { mutableStateOf(false) }
    var articleError by remember { mutableStateOf<String?>(null) }
    var articleReload by remember { mutableIntStateOf(0) }
    var articleGeneration by remember { mutableIntStateOf(0) }
    var deleteTarget by remember { mutableStateOf<RemoteCollection?>(null) }
    var collectionView by remember(token, initialView) { mutableStateOf(normalizeNativeCollectionView(initialView)) }
    var browseQuery by remember(token) { mutableStateOf("") }
    var browseCollections by remember(token) { mutableStateOf<List<RemoteCollection>>(emptyList()) }
    var browsePage by remember(token) { mutableIntStateOf(1) }
    var browseTotalPages by remember(token) { mutableIntStateOf(1) }
    var browseTotal by remember(token) { mutableIntStateOf(0) }
    var browseLoading by remember(token) { mutableStateOf(false) }
    var browseLoadingMore by remember(token) { mutableStateOf(false) }
    var browseError by remember(token) { mutableStateOf<String?>(null) }
    var browseReloadKey by remember(token) { mutableIntStateOf(0) }
    var browseActionId by remember(token) { mutableStateOf<Int?>(null) }
    var browseGeneration by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(editorOpen, editorError) {
        // 校验与网络错误必须在当前弹窗可见，不藏在键盘后或后方列表。
        if (editorOpen) editorScroll.scrollTo(0)
    }

    LaunchedEffect(token, reloadKey) {
        // 合集读取不依赖可添加文章接口，文章接口失败也不影响已有合集管理。
        if (token.isNullOrBlank()) {
            loading = false
            error = nativePhrase("请先登录后查看个人合集。", "Sign in to view your collections.")
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { withContext(Dispatchers.IO) { NativeDiscoveryApi.listMyCollections(token) } }
            .onSuccess { collections = it }
            .onFailure {
                if (it is CancellationException) throw it
                error = it.message ?: nativePhrase("合集加载失败，请稍后重试。", "Unable to load collections. Try again later.")
            }
        loading = false
    }

    LaunchedEffect(token, editorOpen, articleQuery, articleReload) {
        // 与 PC 一致只提供本人已发布文章；分页和搜索允许访问第一批之外的全部候选。
        articleGeneration++
        if (!editorOpen || token.isNullOrBlank()) return@LaunchedEffect
        articleLoading = true
        articleError = null
        try {
            delay(180)
            val result = withContext(Dispatchers.IO) { NativeArticleApi.listMine(token, page = 1, pageSize = 50, status = "published", search = articleQuery) }
            articles = result.items
            articlePage = result.page
            articleTotalPages = result.totalPages
        } catch (exception: CancellationException) { throw exception }
        catch (exception: Exception) { articleError = exception.message ?: nativePhrase("可添加文章读取失败。", "Could not load available articles.") }
        finally { articleLoading = false }
    }

    LaunchedEffect(token, collectionView, browseQuery, browseReloadKey) {
        // 新关键词使旧分页失效，迟到的加载更多结果不能混入新搜索。
        browseGeneration++
        browseLoadingMore = false
        if (collectionView != "browse" || token.isNullOrBlank()) return@LaunchedEffect
        delay(220)
        browseLoading = true
        browseError = null
        runCatching {
            withContext(Dispatchers.IO) {
                NativeDiscoveryApi.listVisibleCollections(
                    accessToken = token,
                    query = browseQuery,
                    page = 1,
                    pageSize = 12,
                )
            }
        }.onSuccess { result ->
            browseCollections = result.items
            browsePage = result.page
            browseTotalPages = result.totalPages
            browseTotal = result.total
        }.onFailure { exception ->
            if (exception is CancellationException) throw exception
            browseError = exception.message ?: nativePhrase("可见合集加载失败，请稍后重试。", "Unable to load visible collections. Try again later.")
        }
        browseLoading = false
    }

    /** 打开空白创建表单，不继承前次失败反馈、候选搜索或封面。 */
    fun openCreate() {
        // 每次打开建立新草稿，已创建但未完成的记录仍保留在列表中。
        editorCollection = null
        editorName = ""
        editorDescription = ""
        editorCoverPath = ""
        editorVisibility = "public"
        editorArticleIds = emptyList()
        editorArticleTitles = emptyMap()
        coverFile = null
        coverUri = null
        error = null
        editorError = null
        articleQuery = ""
        editorOpen = true
    }

    /** 将 collection 的已保存状态作为本次编辑基线，保留已有文章的完整顺序。 */
    fun openEdit(collection: RemoteCollection) {
        // 已收录文章不依赖候选分页，可在搜索或翻页后继续显示与排序。
        editorCollection = collection
        editorName = collection.name
        editorDescription = collection.description
        editorCoverPath = collection.coverPath.orEmpty()
        editorVisibility = collection.visibility
        editorArticleIds = collection.articles.map { it.id }
        editorArticleTitles = collection.articles.associate { it.id to it.title }
        coverFile = null
        coverUri = null
        error = null
        editorError = null
        articleQuery = ""
        editorOpen = true
    }

    /** 切换 articleId 的收录状态，保存中禁止变更本次提交快照。 */
    fun toggleArticle(articleId: Int) {
        // 顺序使用稳定文章编号，不使用标题或当前候选页进行去重。
        if (saving) return
        articles.firstOrNull { it.id == articleId }?.let { editorArticleTitles = editorArticleTitles + (it.id to it.title) }
        editorArticleIds = if (articleId in editorArticleIds) {
            editorArticleIds.filterNot { it == articleId }
        } else {
            editorArticleIds + articleId
        }
    }

    /** 将 saved 的已确认结果写回列表与编辑基线，部分失败重试不会再次创建合集。 */
    fun acceptSavedCollection(saved: RemoteCollection) {
        // 每个接口成功后即记住服务器状态，后续步骤失败不伪装整组回滚。
        editorCollection = saved
        editorArticleTitles = editorArticleTitles + saved.articles.associate { it.id to it.title }
        collections = if (collections.any { it.id == saved.id }) collections.map { if (it.id == saved.id) saved else it } else collections + saved
    }

    /** 保存当前编辑快照，分步骤记录成功状态；失败保留草稿和已创建编号以继续重试。 */
    fun saveCollection() {
        // 在协程开始前冻结业务输入并同步加锁，避免保存中修改草稿造成混合提交。
        val currentToken = token
        val name = editorName.trim()
        if (currentToken.isNullOrBlank() || name.isBlank() || saving) {
            if (name.isBlank()) editorError = nativePhrase("合集名称不能为空。", "Collection name is required.")
            return
        }
        if (coverLoading) return
        val existing = editorCollection
        val description = editorDescription
        val visibility = editorVisibility
        val desiredIds = editorArticleIds.toList()
        val coverPath = editorCoverPath
        val pendingCover = coverFile
        saving = true
        editorError = null
        scope.launch {
            var savedStep = false
            try {
                // 创建/元数据先保存；后续收录和封面接口本身不属于同一服务端事务。
                var saved = withContext(Dispatchers.IO) {
                    if (existing == null) {
                        NativeDiscoveryApi.createCollection(
                            accessToken = currentToken,
                            name = name,
                            description = description,
                            visibility = visibility,
                            articleIds = desiredIds,
                            coverPath = coverPath.takeIf(String::isNotBlank),
                        )
                    } else {
                        NativeDiscoveryApi.updateCollection(
                            accessToken = currentToken,
                            id = existing.id,
                            name = name,
                            description = description,
                            visibility = visibility,
                            coverPath = if (pendingCover == null) coverPath else null,
                        )
                    }
                }
                savedStep = true
                acceptSavedCollection(saved)
                if (existing != null) {
                    // 以最新服务端返回的收录集合计算差异，每步确认后推进重试基线。
                    val currentIds = saved.articles.map { it.id }
                    for (articleId in currentIds.filterNot { it in desiredIds }) {
                        saved = withContext(Dispatchers.IO) { NativeDiscoveryApi.removeCollectionArticle(currentToken, saved.id, articleId) }
                        acceptSavedCollection(saved)
                    }
                    for (articleId in desiredIds.filterNot { it in currentIds }) {
                        saved = withContext(Dispatchers.IO) { NativeDiscoveryApi.addCollectionArticle(currentToken, saved.id, articleId) }
                        acceptSavedCollection(saved)
                    }
                    if (desiredIds.isNotEmpty() && desiredIds != saved.articles.map { it.id }) {
                        saved = withContext(Dispatchers.IO) { NativeDiscoveryApi.reorderCollectionArticles(currentToken, saved.id, desiredIds) }
                        acceptSavedCollection(saved)
                    }
                }
                // 封面失败保留所选文件；再次保存沿用已创建编号，不再发送创建请求。
                pendingCover?.let { file ->
                    saved = withContext(Dispatchers.IO) { NativeDiscoveryApi.uploadCollectionCover(currentToken, saved.id, file) }
                    acceptSavedCollection(saved)
                }
                coverFile = null
                coverUri = null
                editorOpen = false
                browseReloadKey++
                notice = if (existing == null) {
                    nativePhrase("合集已创建。", "Collection created.")
                } else {
                    nativePhrase("合集设置已保存。", "Collection settings saved.")
                }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                // 已成功的接口不可假装回滚；错误在当前表单说明可以重试剩余步骤。
                val message = exception.message ?: nativePhrase("合集保存失败，请稍后重试。", "Unable to save the collection. Try again later.")
                editorError = if (savedStep) nativePhrase("部分内容已保存，请重试剩余修改：$message", "Some changes were saved. Retry the remaining changes: $message") else message
            } finally {
                saving = false
            }
        }
    }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // 选图取消不改草稿，文件读取复用有界上传读取器，过大文件不会整份进入内存。
        if (uri == null) return@rememberLauncherForActivityResult
        coverUri = uri
        coverLoading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { readCollectionCoverFile(context, uri) } }
                .onSuccess { file -> coverFile = file; editorCoverPath = "" }
                .onFailure { if (it is CancellationException) throw it; editorError = it.message ?: nativePhrase("封面读取失败。", "Unable to read the cover image.") }
            coverLoading = false
        }
    }

    /** 删除 collection 仅移除合集，失败保留确认弹窗并就地显示原因。 */
    fun deleteCollection(collection: RemoteCollection) {
        // 文章不随合集删除；只有服务端确认成功后才更新本地列表。
        val currentToken = token ?: return
        if (saving) return
        saving = true
        deleteError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeDiscoveryApi.deleteCollection(currentToken, collection.id) }
                collections = collections.filterNot { it.id == collection.id }
                browseReloadKey++
                deleteTarget = null
                notice = nativePhrase("合集已删除，文章内容未受影响。", "Collection deleted. Its articles were not affected.")
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                deleteError = exception.message ?: nativePhrase("合集删除失败，请稍后重试。", "Unable to delete the collection. Try again later.")
            } finally {
                saving = false
            }
        }
    }

    /** 追加当前搜索的下一页合集，不接收已失效搜索的迟到回包。 */
    fun loadMoreBrowseCollections() {
        // 捕获当前查询版本和页码，新的搜索即使关键词相同也有独立版本。
        val currentToken = token
        if (currentToken.isNullOrBlank() || browseLoading || browseLoadingMore || browsePage >= browseTotalPages) return
        browseLoadingMore = true
        val generation = browseGeneration
        val query = browseQuery
        val nextPage = browsePage + 1
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeDiscoveryApi.listVisibleCollections(
                        accessToken = currentToken,
                        query = query,
                        page = nextPage,
                        pageSize = 12,
                    )
                }
            }.onSuccess { result ->
                if (generation != browseGeneration) return@onSuccess
                browseCollections = (browseCollections + result.items).distinctBy { it.id }
                browsePage = result.page
                browseTotalPages = result.totalPages
                browseTotal = result.total
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception
                if (generation != browseGeneration) return@onFailure
                browseError = exception.message ?: nativePhrase("更多合集加载失败，请稍后重试。", "Unable to load more collections. Try again later.")
            }
            if (generation == browseGeneration) browseLoadingMore = false
        }
    }

    /** 追加 articleQuery 对应的下一页本人已发布文章，失败不移除已加载候选和收录顺序。 */
    fun loadMoreArticles() {
        // 分页只访问目标文章查询，不以首批或界面条数限制可收录范围。
        val accessToken = token ?: return
        if (articleLoading || articlePage >= articleTotalPages) return
        val generation = articleGeneration
        val query = articleQuery
        val nextPage = articlePage + 1
        articleLoading = true
        articleError = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { NativeArticleApi.listMine(accessToken, page = nextPage, pageSize = 50, status = "published", search = query) }
                if (generation != articleGeneration) return@launch
                articles = (articles + result.items).distinctBy { it.id }
                articlePage = result.page
                articleTotalPages = result.totalPages
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { if (generation == articleGeneration) articleError = exception.message ?: nativePhrase("更多文章读取失败。", "Could not load more articles.") }
            finally { if (generation == articleGeneration) articleLoading = false }
        }
    }

    /** 切换 collection 的订阅；成功使用接口计数，失败保留原状态。 */
    fun toggleBrowseSubscription(collection: RemoteCollection) {
        // 同步锁定动作，避免重复点击造成订阅反复切换。
        val currentToken = token ?: return
        if (browseActionId != null) return
        browseActionId = collection.id
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeDiscoveryApi.setCollectionSubscription(currentToken, collection.id, !collection.subscribed)
                }
            }.onSuccess { result ->
                browseCollections = browseCollections.map {
                    if (it.id == collection.id) it.copy(
                        subscribed = result.subscribed,
                        subscriberCount = result.subscriberCount,
                    ) else it
                }
                notice = if (result.subscribed) {
                    nativePhrase("已订阅合集。", "Collection subscribed.")
                } else {
                    nativePhrase("已取消合集订阅。", "Collection subscription removed.")
                }
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception
                browseError = exception.message ?: nativePhrase("合集订阅操作失败，请稍后重试。", "Unable to update the collection subscription. Try again later.")
            }
            browseActionId = null
        }
    }

    /** 根据当前视图刷新合集列表，不重置编辑草稿或候选分页。 */
    fun refreshCollections() {
        // 两个视图独立失效，刷新不覆盖另一个视图的未完成操作。
        if (collectionView == "browse") browseReloadKey++ else reloadKey++
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("collections-list"),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Text(
                        if (collectionView == "mine") nativePhrase("我的合集", "My collections") else nativePhrase("浏览合集", "Browse collections"),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(onClick = ::refreshCollections, enabled = !saving && !browseLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新合集", "Refresh collections"))
                    }
                    if (collectionView == "mine") {
                        IconButton(onClick = ::openCreate, enabled = !saving) {
                            Icon(Icons.Filled.AddCircleOutline, contentDescription = nativePhrase("新建合集", "New collection"))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        item {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = collectionView == "browse",
                    onClick = { collectionView = "browse" },
                    label = { Text(nativePhrase("浏览合集", "Browse collections"), fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Filled.Explore, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    border = null,
                )
                FilterChip(
                    selected = collectionView == "mine",
                    onClick = { collectionView = "mine" },
                    label = { Text(nativePhrase("我的合集", "My collections"), fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    border = null,
                )
            }
        }
        if (collectionView == "browse") {
            item {
                OutlinedTextField(
                    value = browseQuery,
                    onValueChange = { browseQuery = it.take(80) },
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("collection-browse-search"),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Accent) },
                    placeholder = { Text(nativePhrase("搜索合集、说明或作者", "Search collections, descriptions, or authors"), fontSize = 12.sp) },
                    label = { Text(nativePhrase("搜索合集", "Search collections"), fontSize = 11.sp) },
                    colors = loginFieldColors(),
                )
            }
            if (browseLoading && browseCollections.isEmpty()) {
                item { EmptyState(nativePhrase("正在读取可见合集…", "Loading visible collections...")) }
            } else if (!browseError.isNullOrBlank() && browseCollections.isEmpty()) {
                item { EmptyState(browseError!!) }
            } else if (browseCollections.isEmpty()) {
                item {
                    EmptyState(
                        if (browseQuery.isBlank()) {
                            nativePhrase("还没有可浏览的合集。", "No collections are available yet.")
                        } else {
                            nativePhrase("没有找到匹配的合集。", "No matching collections found.")
                        },
                    )
                }
            } else {
                item {
                    Text(
                        nativePhrase("共 ${browseTotal} 个可见合集", "$browseTotal visible collection(s)"),
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                }
                items(browseCollections, key = { it.id }) { collection ->
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("collection-browse-${collection.id}").clickable { onOpenCollection(collection) },
                        shape = HlovetUi.cardShape,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NativeCoverImage(path = collection.coverPath, modifier = Modifier.size(58.dp), fallbackText = collection.name, tint = Coral)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(collection.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    collection.description.ifBlank { nativePhrase("暂无合集说明", "No description") },
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    nativePhrase(
                                        "${collection.articleCount} 篇文章 · ${collection.subscriberCount} 人订阅 · ${collection.ownerName}",
                                        "${collection.articleCount} article(s) · ${collection.subscriberCount} subscriber(s) · ${collection.ownerName}",
                                    ),
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { toggleBrowseSubscription(collection) },
                                enabled = browseActionId == null,
                                modifier = Modifier.size(38.dp),
                            ) {
                                Icon(
                                    if (collection.subscribed) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                    contentDescription = if (collection.subscribed) {
                                        nativePhrase("取消订阅", "Unsubscribe")
                                    } else {
                                        nativePhrase("订阅合集", "Subscribe to collection")
                                    },
                                    tint = if (collection.subscribed) Accent else TextMuted,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                    }
                }
                if (browseLoadingMore) item { EmptyState(nativePhrase("正在加载更多合集…", "Loading more collections...")) }
                if (!browseLoadingMore && browsePage < browseTotalPages) {
                    item {
                        FilledTonalButton(
                            onClick = ::loadMoreBrowseCollections,
                            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                            enabled = !browseLoading,
                        ) { Text(nativePhrase("加载更多", "Load more"), fontSize = 12.sp) }
                    }
                }
            }
            if (!browseError.isNullOrBlank() && browseCollections.isNotEmpty()) {
                item { Text(browseError!!, color = Coral, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
            }
        } else if (loading && collections.isEmpty()) {
            item { EmptyState(nativePhrase("正在读取我的合集…", "Loading your collections...")) }
        } else if (!error.isNullOrBlank() && collections.isEmpty()) {
            item { EmptyState(error!!) }
        } else if (collections.isEmpty()) {
            item { EmptyState(nativePhrase("还没有创建合集。点击右上角加号创建一个合集。", "You have not created a collection yet. Use the plus button to create one.")) }
        } else {
            item {
                Text(
                    nativePhrase("共 ${collections.size} 个合集", "${collections.size} collection(s)"),
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
            }
            items(collections, key = { it.id }) { collection ->
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().testTag("collection-${collection.id}"), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NativeCoverImage(path = collection.coverPath, modifier = Modifier.size(52.dp), fallbackText = collection.name, tint = Coral)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f).clickable { onOpenCollection(collection) }) {
                                Text(collection.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    nativePhrase(
                                        "${collection.articleCount} 篇文章 · ${collection.subscriberCount} 人订阅",
                                        "${collection.articleCount} article(s) · ${collection.subscriberCount} subscriber(s)",
                                    ),
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                )
                            }
                            IconButton(onClick = { openEdit(collection) }, enabled = !saving, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Edit, contentDescription = nativePhrase("编辑合集", "Edit collection"), tint = Accent, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { deleteError = null; deleteTarget = collection }, enabled = !saving, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Delete, contentDescription = nativePhrase("删除合集", "Delete collection"), tint = Coral, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (collection.description.isNotBlank()) Text(collection.description, color = TextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (!error.isNullOrBlank() && collections.isNotEmpty()) item { Text(error!!, color = Coral, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
        notice?.let { item { Text(it, color = Accent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) } }
    }

    if (editorOpen) {
        HlovetAlertDialog(
            onDismissRequest = { if (!saving && !coverLoading) editorOpen = false },
            title = {
                Text(if (editorCollection == null) nativePhrase("新建合集", "New collection") else nativePhrase("编辑合集", "Edit collection"))
            },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(editorScroll).testTag("collection-editor"), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    editorError?.let { Text(it, color = Coral, fontSize = 12.sp, modifier = Modifier.testTag("collection-editor-error")) }
                    OutlinedTextField(
                        value = editorName,
                        onValueChange = { editorName = it.take(80) },
                        modifier = Modifier.fillMaxWidth().testTag("collection-name"),
                        enabled = !saving,
                        singleLine = true,
                        label = { Text(nativePhrase("合集名称", "Collection name")) },
                        colors = loginFieldColors(),
                    )
                    OutlinedTextField(
                        value = editorDescription,
                        onValueChange = { editorDescription = it.take(300) },
                        modifier = Modifier.fillMaxWidth().testTag("collection-description"),
                        enabled = !saving,
                        minLines = 2,
                        maxLines = 4,
                        label = { Text(nativePhrase("描述", "Description")) },
                        colors = loginFieldColors(),
                    )
                    OutlinedTextField(
                        value = editorCoverPath,
                        onValueChange = { editorCoverPath = it.take(512); coverFile = null; coverUri = null },
                        modifier = Modifier.fillMaxWidth().testTag("collection-cover-url"),
                        enabled = !saving && !coverLoading,
                        singleLine = true,
                        label = { Text(nativePhrase("封面地址（可选）", "Cover URL (optional)")) },
                        colors = loginFieldColors(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NativeCoverImage(
                            path = editorCoverPath.takeIf(String::isNotBlank),
                            modifier = Modifier.size(58.dp),
                            fallbackText = editorName.ifBlank { nativePhrase("合集", "Collection") },
                            tint = Coral,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(nativePhrase("也可以直接选择图片上传", "You can also upload an image directly"), color = TextMuted, fontSize = 11.sp)
                            Text(
                                when {
                                    coverLoading -> nativePhrase("正在读取图片…", "Reading image...")
                                    coverFile != null -> nativePhrase("已选择：${coverFile!!.name}", "Selected: ${coverFile!!.name}")
                                    coverUri != null -> nativePhrase("已选择图片", "Image selected")
                                    else -> nativePhrase("支持 JPG、PNG、WebP，最大 10 MB", "JPG, PNG, or WebP up to 10 MB")
                                },
                                color = if (coverFile != null) Accent else TextMuted,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { coverPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/avif")) }, enabled = !saving && !coverLoading) {
                            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(nativePhrase("选择", "Choose"), fontSize = 11.sp)
                        }
                    }
                    Text(nativePhrase("可见范围", "Visibility"), color = TextMuted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "public" to nativePhrase("公开", "Public"),
                            "authenticated" to nativePhrase("登录可见", "Signed-in users"),
                            "private" to nativePhrase("仅自己", "Only me"),
                        ).forEach { (value, label) ->
                            FilterChip(selected = editorVisibility == value, onClick = { editorVisibility = value }, enabled = !saving, label = { Text(label, fontSize = 11.sp) }, border = null)
                        }
                    }
                    Text(
                        nativePhrase("已收录文章（${editorArticleIds.size}）", "Included articles (${editorArticleIds.size})"),
                        color = TextMuted,
                        fontSize = 11.sp,
                    )
                    if (editorArticleIds.isEmpty()) {
                        Text(nativePhrase("暂未收录文章。", "No articles included yet."), color = TextMuted, fontSize = 12.sp)
                    } else {
                        NativeReorderableArticleRows(
                            items = editorArticleIds,
                            enabled = !saving,
                            titleFor = { articleId -> editorArticleTitles[articleId] ?: nativePhrase("文章 #$articleId", "Article #$articleId") },
                            supportingFor = { nativePhrase("已收录", "Included") },
                            onReordered = { editorArticleIds = it },
                            onRemove = ::toggleArticle,
                        )
                    }
                    Text(nativePhrase("可添加文章", "Available articles"), color = TextMuted, fontSize = 11.sp)
                    OutlinedTextField(value = articleQuery, onValueChange = { articleQuery = it }, singleLine = true, enabled = !saving,
                        modifier = Modifier.fillMaxWidth().testTag("collection-article-search"), label = { Text(nativePhrase("搜索本人已发布文章", "Search your published articles"), fontSize = 11.sp) }, colors = loginFieldColors())
                    articleError?.let { message ->
                        Text(message, color = Coral, fontSize = 11.sp)
                        TextButton(onClick = { articleReload++ }, enabled = !articleLoading && !saving) { Text(nativePhrase("重试读取文章", "Retry articles"), fontSize = 11.sp) }
                    }
                    if (!articleLoading && articleError == null && articles.filterNot { it.id in editorArticleIds }.isEmpty()) {
                        Text(nativePhrase("暂无可添加文章。", "No articles are available to add."), color = TextMuted, fontSize = 12.sp)
                    }
                    articles.filterNot { it.id in editorArticleIds }.forEach { article ->
                        Row(Modifier.fillMaxWidth().testTag("collection-available-${article.id}").clickable(enabled = !saving) { toggleArticle(article.id) }, verticalAlignment = Alignment.CenterVertically) {
                            HlovetCheckbox(checked = article.id in editorArticleIds, onCheckedChange = { toggleArticle(article.id) }, enabled = !saving)
                            Text(article.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                    if (articleLoading) Text(nativePhrase("正在读取文章…", "Loading articles…"), color = TextMuted, fontSize = 11.sp)
                    if (!articleLoading && articlePage < articleTotalPages) TextButton(onClick = ::loadMoreArticles, enabled = !saving) { Text(nativePhrase("加载更多可添加文章", "Load more available articles"), fontSize = 11.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = ::saveCollection, enabled = !saving && !coverLoading) {
                    Text(if (saving) nativePhrase("保存中…", "Saving...") else nativePhrase("保存", "Save"), color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { editorOpen = false }, enabled = !saving && !coverLoading) { Text(nativePhrase("取消", "Cancel")) }
            },
        )
    }

    deleteTarget?.let { collection ->
        HlovetAlertDialog(
            onDismissRequest = { if (!saving) deleteTarget = null },
            title = { Text(nativePhrase("删除合集", "Delete collection")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    deleteError?.let { Text(it, color = Coral, fontSize = 12.sp, modifier = Modifier.testTag("collection-delete-error")) }
                    Text(
                    nativePhrase(
                        "确定要删除“${collection.name}”吗？文章本身不会被删除。",
                        "Delete \"${collection.name}\"? Its articles will not be deleted.",
                    ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { deleteCollection(collection) }, enabled = !saving) { Text(nativePhrase("删除", "Delete"), color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }, enabled = !saving) { Text(nativePhrase("取消", "Cancel")) }
            },
        )
    }
}

@Composable
private fun MyArticlesManagementScreen(
    session: NativeSession?,
    initialStatus: String? = null,
    initialSearchQuery: String = "",
    onBack: () -> Unit,
    onArticleClick: (RemoteArticle) -> Unit,
    onEditArticle: (Int) -> Unit,
) {
    val accessToken = session?.accessToken
    val scope = rememberCoroutineScope()
    var selectedStatus by remember(accessToken, initialStatus) { mutableStateOf(initialStatus) }
    var searchQuery by remember(accessToken, initialSearchQuery) { mutableStateOf(initialSearchQuery) }
    var articleList by remember(accessToken, selectedStatus, searchQuery) { mutableStateOf<NativeArticleList?>(null) }
    var summary by remember(accessToken) { mutableStateOf<NativeArticleMineSummary?>(null) }
    var dashboard by remember(accessToken) { mutableStateOf<NativeArticleDashboard?>(null) }
    var schedules by remember(accessToken) { mutableStateOf<List<NativeArticleScheduleItem>>(emptyList()) }
    var loading by remember(accessToken, selectedStatus, searchQuery) { mutableStateOf(true) }
    var loadingMore by remember(accessToken, selectedStatus, searchQuery) { mutableStateOf(false) }
    var error by remember(accessToken, selectedStatus, searchQuery) { mutableStateOf<String?>(null) }
    var notice by remember(accessToken, selectedStatus, searchQuery) { mutableStateOf<String?>(null) }
    var reloadKey by remember(accessToken, selectedStatus) { mutableIntStateOf(0) }
    var actionTarget by remember { mutableStateOf<NativeArticleManagementAction?>(null) }
    var actionLoading by remember { mutableStateOf(false) }
    var scheduleLoading by remember { mutableStateOf(false) }
    var appealTarget by remember { mutableStateOf<NativeArticleAppealTarget?>(null) }
    var appealReason by remember { mutableStateOf("") }
    var appealLoading by remember { mutableStateOf(false) }

    val statusOptions = listOf(
        null to nativePhrase("全部", "All"),
        "draft" to nativePhrase("草稿", "Drafts"),
        "published" to nativePhrase("已发布", "Published"),
        "unpublished" to nativePhrase("已下架", "Unpublished"),
        "blocked" to nativePhrase("受限", "Restricted"),
        "deleted" to nativePhrase("回收站", "Trash"),
    )

    fun statusCount(status: String?): Int = when (status) {
        null -> summary?.total ?: 0
        "draft" -> summary?.draft ?: 0
        "published" -> summary?.published ?: 0
        "unpublished" -> summary?.unpublished ?: 0
        "blocked" -> summary?.blocked ?: 0
        "deleted" -> summary?.deleted ?: 0
        else -> 0
    }

    fun statusLabel(status: String): String = when (status) {
        "draft" -> nativePhrase("草稿", "Draft")
        "published" -> nativePhrase("已发布", "Published")
        "unpublished" -> nativePhrase("已下架", "Unpublished")
        "blocked" -> nativePhrase("受限", "Restricted")
        "deleted" -> nativePhrase("回收站", "Trash")
        else -> nativePhrase("未知状态", "Unknown status")
    }

    fun actionTitle(kind: String): String = when (kind) {
        "unpublish" -> nativePhrase("下架文章", "Unpublish article")
        "delete" -> nativePhrase("移入回收站", "Move to trash")
        "restore" -> nativePhrase("恢复文章", "Restore article")
        "permanent" -> nativePhrase("彻底删除", "Delete permanently")
        else -> nativePhrase("文章操作", "Article action")
    }

    fun actionMessage(kind: String, title: String): String = when (kind) {
        "unpublish" -> nativePhrase(
            "确定要下架《$title》吗？下架后文章不再出现在公开内容中。",
            "Unpublish \"$title\"? It will no longer appear in public content.",
        )
        "delete" -> nativePhrase("确定要把《$title》移入回收站吗？", "Move \"$title\" to the trash?")
        "restore" -> nativePhrase("确定要把《$title》恢复为草稿吗？", "Restore \"$title\" as a draft?")
        "permanent" -> nativePhrase(
            "确定要彻底删除《$title》吗？文章、图片和附件都无法恢复。",
            "Permanently delete \"$title\"? Its article content, images, and attachments cannot be recovered.",
        )
        else -> nativePhrase("确定要执行此操作吗？", "Continue with this action?")
    }

    fun actionSuccess(kind: String): String = when (kind) {
        "unpublish" -> nativePhrase("文章已下架。", "Article unpublished.")
        "delete" -> nativePhrase("文章已移入回收站。", "Article moved to the trash.")
        "restore" -> nativePhrase("文章已恢复为草稿。", "Article restored as a draft.")
        "permanent" -> nativePhrase("文章已彻底删除。", "Article permanently deleted.")
        else -> nativePhrase("文章操作已完成。", "Article action completed.")
    }

    fun runAction(target: NativeArticleManagementAction) {
        val token = accessToken
        if (token.isNullOrBlank() || actionLoading) return
        actionLoading = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (target.kind) {
                        "unpublish" -> NativeArticleApi.unpublish(token, target.article.id)
                        "delete" -> NativeArticleApi.deleteArticle(token, target.article.id)
                        "restore" -> NativeArticleApi.restoreArticle(token, target.article.id)
                        "permanent" -> NativeArticleApi.permanentlyDeleteArticle(token, target.article.id)
                        else -> Unit
                    }
                }
                notice = actionSuccess(target.kind)
                actionTarget = null
                reloadKey += 1
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("文章操作失败，请稍后重试。", "Unable to complete the article action. Try again later.")
            } finally {
                actionLoading = false
            }
        }
    }

    fun cancelSchedule(item: NativeArticleScheduleItem) {
        val token = accessToken
        if (token.isNullOrBlank() || scheduleLoading) return
        scheduleLoading = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { NativeArticleApi.cancelArticleSchedule(token, item.id) }
                notice = nativePhrase("文章发布计划已取消。", "Article schedule canceled.")
                schedules = withContext(Dispatchers.IO) { NativeArticleApi.listMySchedules(token) }
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("取消发布计划失败，请稍后重试。", "Unable to cancel the schedule. Try again later.")
            } finally {
                scheduleLoading = false
            }
        }
    }

    fun submitAppeal() {
        val token = accessToken ?: return
        val target = appealTarget ?: return
        val reason = appealReason.trim()
        if (reason.isBlank() || appealLoading) return
        scope.launch {
            appealLoading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) { NativeArticleApi.createArticleAppeal(token, target.article.id, reason) }
            }.onSuccess {
                appealTarget = null
                appealReason = ""
                notice = nativePhrase("申诉已提交，等待管理员处理。", "Appeal submitted for administrator review.")
            }.onFailure { error = it.message ?: nativePhrase("申诉提交失败，请稍后重试。", "Unable to submit the appeal. Try again later.") }
            appealLoading = false
        }
    }

    LaunchedEffect(accessToken, selectedStatus, searchQuery, reloadKey) {
        if (accessToken.isNullOrBlank()) {
            loading = false
            error = nativePhrase("请先登录后查看我的文章。", "Sign in to view your articles.")
            return@LaunchedEffect
        }
        loading = true
        loadingMore = false
        error = null
        delay(250)
        try {
            val loaded = withContext(Dispatchers.IO) {
                val loadedSummary = NativeArticleApi.mineSummary(accessToken)
                val loadedDashboard = NativeArticleApi.mineDashboard(accessToken)
                val loadedSchedules = NativeArticleApi.listMySchedules(accessToken)
                val loadedList = NativeArticleApi.listMine(
                    accessToken = accessToken,
                    page = 1,
                    pageSize = 12,
                    status = selectedStatus,
                    search = searchQuery.trim().takeIf { it.isNotBlank() },
                )
                NativeArticleManagementLoad(loadedSummary, loadedDashboard, loadedSchedules, loadedList)
            }
            summary = loaded.summary
            dashboard = loaded.dashboard
            schedules = loaded.schedules
            articleList = loaded.articles
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase("文章列表加载失败，请稍后重试。", "Unable to load your articles. Try again later.")
        } finally {
            loading = false
        }
    }

    fun loadMoreArticles() {
        val token = accessToken ?: return
        val current = articleList ?: return
        if (loading || loadingMore || current.page >= current.totalPages) return
        loadingMore = true
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    NativeArticleApi.listMine(
                        accessToken = token,
                        page = current.page + 1,
                        pageSize = 12,
                        status = selectedStatus,
                        search = searchQuery.trim().takeIf { it.isNotBlank() },
                    )
                }
            }.onSuccess { nextPage ->
                val existing = articleList
                if (existing == null || nextPage.page <= 1) {
                    articleList = nextPage
                } else {
                    articleList = existing.copy(
                        items = (existing.items + nextPage.items).distinctBy { it.id },
                        total = nextPage.total,
                        page = nextPage.page,
                        totalPages = nextPage.totalPages,
                    )
                }
            }.onFailure { exception ->
                error = exception.message ?: nativePhrase("更多文章加载失败，请稍后重试。", "Unable to load more articles. Try again later.")
            }
            loadingMore = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(nativePhrase("我的文章", "My articles"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(onClick = { reloadKey += 1 }, enabled = !loading && !loadingMore && !actionLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"), tint = Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(100) },
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    singleLine = true,
                    label = { Text(nativePhrase("搜索文章", "Search articles"), fontSize = 12.sp) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }, enabled = !loading && !loadingMore) {
                                Icon(Icons.Filled.Close, contentDescription = nativePhrase("清除搜索", "Clear search"), tint = TextMuted)
                            }
                        }
                    },
                    colors = loginFieldColors(),
                )
            }
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(statusOptions) { (value, label) ->
                        FilterChip(
                            selected = selectedStatus == value,
                            onClick = { if (!loading && !actionLoading) { selectedStatus = value; notice = null } },
                            label = { Text("$label ${statusCount(value)}", fontSize = 11.sp) },
                            border = null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Accent,
                                selectedLabelColor = Color.White,
                                containerColor = SurfaceLow,
                                labelColor = TextPrimary,
                            ),
                        )
                    }
                }
            }
            dashboard?.let { creator ->
                item {
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = HlovetUi.cardShape,
                    ) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(nativePhrase("创作数据", "Creator insights"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(nativePhrase("文章表现与资源收益", "Article performance and resource earnings"), color = TextMuted, fontSize = 10.sp)
                                }
                                Text(nativePhrase("${creator.views} 阅读", "${creator.views} views"), color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                NativeCreatorMetric(nativePhrase("获赞", "Likes"), creator.likes)
                                NativeCreatorMetric(nativePhrase("评论", "Comments"), creator.comments)
                                NativeCreatorMetric(nativePhrase("收藏", "Favorites"), creator.favorites)
                                NativeCreatorMetric(nativePhrase("兑换", "Redemptions"), creator.resourceExchanges)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                NativeCreatorMetric(nativePhrase("待入账", "Pending"), creator.pendingPoints)
                                NativeCreatorMetric(nativePhrase("已到账", "Settled"), creator.settledPoints)
                            }
                            if (creator.recentResourceIncome.isNotEmpty()) {
                                HorizontalDivider(color = HlovetUi.divider)
                                Text(nativePhrase("最近资源收益", "Recent resource earnings"), color = TextMuted, fontSize = 11.sp)
                                creator.recentResourceIncome.take(3).forEach { income ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(income.articleTitle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                listOfNotNull(
                                                    income.buyerName,
                                                    income.settledAt?.let { nativePhrase("已到账 $it", "Settled $it") }
                                                        ?: nativePhrase("预计 ${income.availableAt}到账", "Expected ${income.availableAt}"),
                                                ).joinToString(" · "),
                                                color = TextMuted,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text("+${income.pointCost}", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                GlassCard(
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    shape = HlovetUi.cardShape,
                ) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(nativePhrase("发布计划", "Publishing schedule"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(schedules.size.toString(), color = TextMuted, fontSize = 11.sp)
                        }
                        if (schedules.isEmpty()) {
                            Text(nativePhrase("还没有安排发布或下线的文章。", "No articles are scheduled for publishing or unpublishing."), color = TextMuted, fontSize = 11.sp)
                        } else {
                            schedules.take(20).forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { onEditArticle(item.id) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title.ifBlank { nativePhrase("未命名文章", "Untitled article") }, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            when {
                                                !item.error.isNullOrBlank() -> nativePhrase("执行失败：${item.error}", "Failed: ${item.error}")
                                                !item.publishAt.isNullOrBlank() -> nativePhrase("发布于 ${item.publishAt}", "Publish at ${item.publishAt}")
                                                !item.unpublishAt.isNullOrBlank() -> nativePhrase("下线于 ${item.unpublishAt}", "Unpublish at ${item.unpublishAt}")
                                                else -> nativePhrase("计划已设置", "Schedule set")
                                            },
                                            color = if (!item.error.isNullOrBlank()) Coral else TextMuted,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        onClick = { cancelSchedule(item) },
                                        enabled = !scheduleLoading && !actionLoading,
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = nativePhrase("取消计划", "Cancel schedule"), tint = TextMuted, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!error.isNullOrBlank()) {
                item {
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = HlovetUi.rowShape,
                    ) {
                        Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
                    }
                }
            }
            if (!notice.isNullOrBlank()) {
                item {
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = HlovetUi.rowShape,
                    ) {
                        Text(notice!!, color = Accent, fontSize = 12.sp, modifier = Modifier.padding(14.dp))
                    }
                }
            }
            when {
                loading -> item { EmptyState(nativePhrase("正在读取我的文章…", "Loading your articles...")) }
                articleList?.items.isNullOrEmpty() -> item {
                    EmptyState(
                        if (selectedStatus == "deleted") {
                            nativePhrase("回收站目前是空的。", "The trash is empty.")
                        } else {
                            nativePhrase("当前筛选下还没有文章。", "No articles match this filter.")
                        },
                    )
                }
                else -> {
                    val currentArticles = articleList!!
                    item {
                        Text(
                            nativePhrase(
                                "显示 ${currentArticles.items.size} 篇 · 共 ${currentArticles.total} 篇",
                                "Showing ${currentArticles.items.size} of ${currentArticles.total} article(s)",
                            ),
                            color = TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 22.dp),
                        )
                    }
                    items(currentArticles.items, key = { it.id }) { article ->
                        Card(
                            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                            shape = HlovetUi.cardShape,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        article.title.ifBlank { nativePhrase("未命名文章", "Untitled article") },
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    HlovetBadge(
                                        text = statusLabel(article.status),
                                        fontSize = 10.sp,
                                        containerColor = if (article.status == "blocked") Coral else Accent,
                                        contentColor = Color.White,
                                    )
                                }
                                Text(
                                    listOfNotNull(
                                        article.category.takeIf { it.isNotBlank() },
                                        nativePhrase("阅读 ${article.viewCount}", "${article.viewCount} views"),
                                        nativePhrase("评论 ${article.commentCount}", "${article.commentCount} comments"),
                                        article.updatedAt.takeIf { it.isNotBlank() }?.let { nativePhrase("更新于 $it", "Updated $it") },
                                    ).joinToString(" · "),
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (article.status == "blocked" && !article.blockedReason.isNullOrBlank()) {
                                    Text(nativePhrase("受限原因：${article.blockedReason}", "Restriction reason: ${article.blockedReason}"), color = Coral, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    if (article.status != "deleted") {
                                        TextButton(onClick = { onEditArticle(article.id) }) { Text(nativePhrase("编辑", "Edit"), fontSize = 12.sp) }
                                    }
                                    if ((article.status == "published" || article.status == "blocked") && article.slug.isNotBlank()) {
                                        TextButton(onClick = { onArticleClick(article) }) { Text(nativePhrase("查看", "View"), fontSize = 12.sp) }
                                    }
                                    if (article.status == "blocked") {
                                        TextButton(onClick = { appealTarget = NativeArticleAppealTarget(article); appealReason = "" }) { Text(nativePhrase("申诉", "Appeal"), fontSize = 12.sp) }
                                    }
                                    if (article.status == "published") {
                                        TextButton(onClick = { actionTarget = NativeArticleManagementAction(article, "unpublish") }) { Text(nativePhrase("下架", "Unpublish"), fontSize = 12.sp) }
                                    }
                                    if (article.status == "deleted") {
                                        TextButton(onClick = { actionTarget = NativeArticleManagementAction(article, "restore") }) { Text(nativePhrase("恢复", "Restore"), fontSize = 12.sp) }
                                    }
                                    TextButton(onClick = {
                                        actionTarget = NativeArticleManagementAction(article, if (article.status == "deleted") "permanent" else "delete")
                                    }) {
                                        Text(
                                            if (article.status == "deleted") nativePhrase("彻底删除", "Delete permanently") else nativePhrase("删除", "Delete"),
                                            color = Coral,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (currentArticles.page < currentArticles.totalPages) {
                        item {
                            TextButton(
                                onClick = ::loadMoreArticles,
                                enabled = !loading && !loadingMore && !actionLoading,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            ) {
                                Text(
                                    if (loadingMore) {
                                        nativePhrase("加载中…", "Loading...")
                                    } else {
                                        nativePhrase(
                                            "加载更多文章（${currentArticles.page}/${currentArticles.totalPages}）",
                                            "Load more articles (${currentArticles.page}/${currentArticles.totalPages})",
                                        )
                                    },
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    actionTarget?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (!actionLoading) actionTarget = null },
            title = { Text(actionTitle(target.kind)) },
            text = { Text(actionMessage(target.kind, target.article.title.ifBlank { nativePhrase("未命名文章", "Untitled article") })) },
            confirmButton = {
                TextButton(
                    onClick = { runAction(target) },
                    enabled = !actionLoading,
                ) {
                    Text(
                        if (actionLoading) nativePhrase("处理中…", "Processing...") else nativePhrase("确定", "Confirm"),
                        color = if (target.kind == "delete" || target.kind == "permanent") Coral else Accent,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { actionTarget = null }, enabled = !actionLoading) { Text(nativePhrase("取消", "Cancel")) }
            },
        )
    }

    appealTarget?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (!appealLoading) appealTarget = null },
            title = { Text(nativePhrase("申诉文章", "Appeal article restriction")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        nativePhrase(
                            "《${target.article.title.ifBlank { nativePhrase("未命名文章", "Untitled article") }}》当前处于受限状态，请说明申诉理由和已完成的修改。",
                            "\"${target.article.title.ifBlank { nativePhrase("未命名文章", "Untitled article") }}\" is restricted. Explain why you are appealing and what changes you have made.",
                        ),
                        fontSize = 12.sp,
                    )
                    OutlinedTextField(
                        value = appealReason,
                        onValueChange = { appealReason = it.take(1000) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5,
                        maxLines = 8,
                        label = { Text(nativePhrase("申诉理由", "Appeal reason")) },
                        placeholder = { Text(nativePhrase("填写申诉理由和已做的修改", "Describe your appeal and the changes you made")) },
                        enabled = !appealLoading,
                        colors = loginFieldColors(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = ::submitAppeal, enabled = appealReason.isNotBlank() && !appealLoading) {
                    Text(if (appealLoading) nativePhrase("提交中…", "Submitting...") else nativePhrase("提交申诉", "Submit appeal"), color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { appealTarget = null }, enabled = !appealLoading) { Text(nativePhrase("取消", "Cancel")) }
            },
        )
    }
}

@Composable
private fun NativeCreatorMetric(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}

private data class NativePrivacyVerificationTarget(
    val kind: String,
    val title: String,
    val action: String? = null,
    val passkeyId: Int? = null,
)

private data class NativeTotpEnrollmentState(
    val secret: String,
    val otpAuthUri: String,
)

private fun NativePrivacyVerificationTarget.isSensitiveAction(): Boolean = action != null

@Composable
internal fun NativePrivacyScreen(
    session: NativeSession?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessToken = session?.accessToken
    var overview by remember(accessToken) { mutableStateOf<NativePrivacyOverview?>(null) }
    var security by remember(accessToken) { mutableStateOf<NativeSecurityOverview?>(null) }
    var passkeys by remember(accessToken) { mutableStateOf<List<NativePasskeySummary>>(emptyList()) }
    var audit by remember(accessToken) { mutableStateOf<List<NativePrivacyAuditRecord>>(emptyList()) }
    var exportJob by remember(accessToken) { mutableStateOf<NativePrivacyExportJob?>(null) }
    var loading by remember(accessToken) { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var unblockTarget by remember { mutableStateOf<NativeBlockedUser?>(null) }
    var trustedDeviceTarget by remember { mutableStateOf<NativeTrustedDevice?>(null) }
    var exportContent by remember { mutableStateOf<String?>(null) }
    var verificationTarget by remember { mutableStateOf<NativePrivacyVerificationTarget?>(null) }
    var verificationStep by remember { mutableStateOf("choose") }
    var verificationCode by remember { mutableStateOf("") }
    var verificationPassword by remember { mutableStateOf("") }
    var verificationEmailChallenge by remember { mutableStateOf("") }
    var verificationEmailCooldown by remember { mutableIntStateOf(0) }
    var verificationBusy by remember { mutableStateOf(false) }
    var emailVerificationCode by remember { mutableStateOf("") }
    var emailVerificationCooldown by remember { mutableIntStateOf(0) }
    var emailVerificationOpen by remember { mutableStateOf(false) }
    var emailVerificationBusy by remember { mutableStateOf(false) }
    var formTarget by remember { mutableStateOf<String?>(null) }
    var emailDraft by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var passwordConfirmation by remember { mutableStateOf("") }
    var passkeyName by remember { mutableStateOf("") }
    var editingPasskeyId by remember { mutableIntStateOf(0) }
    var editingPasskeyName by remember { mutableStateOf("") }
    var totpEnrollment by remember { mutableStateOf<NativeTotpEnrollmentState?>(null) }
    var totpEnrollmentCode by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var verificationPasswordVisible by remember { mutableStateOf(false) }
    val credentialManager = remember { CredentialManager.create(context) }

    val saveExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val content = exportContent
        if (uri == null || content == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(content.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException(nativePhrase("无法打开导出文件。", "Unable to open the export file."))
                }
            }.onSuccess {
                notice = nativePhrase("数据导出文件已保存。", "Data export saved.")
            }.onFailure {
                error = it.message ?: nativePhrase("导出文件保存失败。", "Unable to save the export file.")
            }
        }
    }

    LaunchedEffect(accessToken, reloadKey) {
        if (accessToken.isNullOrBlank()) {
            loading = false
            error = nativePhrase("请先登录后查看隐私与数据。", "Sign in to view privacy and data settings.")
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            withContext(Dispatchers.IO) {
                val nextOverview = NativePrivacyApi.getOverview(accessToken)
                val nextAudit = runCatching { NativePrivacyApi.listAudit(accessToken) }.getOrDefault(emptyList())
                val nextSecurity = runCatching { NativePrivacyApi.getSecurity(accessToken) }.getOrNull()
                val nextPasskeys = runCatching { NativePrivacyApi.listPasskeys(accessToken) }.getOrDefault(emptyList())
                QuadPrivacyData(nextOverview, nextSecurity, nextPasskeys, nextAudit)
            }
        }.onSuccess { data ->
            overview = data.overview
            security = data.security
            passkeys = data.passkeys
            audit = data.audit
        }.onFailure {
            error = it.message ?: nativePhrase("隐私与数据加载失败。", "Unable to load privacy and data settings.")
        }
        loading = false
    }

    LaunchedEffect(exportJob?.id, accessToken) {
        val token = accessToken ?: return@LaunchedEffect
        val id = exportJob?.id ?: return@LaunchedEffect
        if (exportJob?.status !in setOf("queued", "processing")) return@LaunchedEffect
        repeat(40) {
            delay(1600)
            runCatching { withContext(Dispatchers.IO) { NativePrivacyApi.getExport(token, id) } }
                .onSuccess { exportJob = it }
                .onFailure { error = it.message ?: nativePhrase("导出状态读取失败。", "Unable to read the export status.") }
            if (exportJob?.status !in setOf("queued", "processing")) return@LaunchedEffect
        }
    }

    LaunchedEffect(verificationEmailCooldown) {
        if (verificationEmailCooldown > 0) {
            delay(1_000)
            verificationEmailCooldown -= 1
        }
    }

    LaunchedEffect(emailVerificationCooldown) {
        if (emailVerificationCooldown > 0) {
            delay(1_000)
            emailVerificationCooldown -= 1
        }
    }

    fun openVerification(target: NativePrivacyVerificationTarget) {
        error = null
        notice = null
        verificationTarget = target
        verificationStep = "choose"
        verificationCode = ""
        verificationPassword = ""
        verificationEmailChallenge = ""
        verificationPasswordVisible = false
    }

    fun closeVerification() {
        if (verificationBusy) return
        verificationTarget = null
        verificationStep = "choose"
        verificationCode = ""
        verificationPassword = ""
        verificationEmailChallenge = ""
        verificationPasswordVisible = false
    }

    fun finishSensitiveVerification(target: NativePrivacyVerificationTarget) {
        verificationTarget = null
        verificationStep = "choose"
        verificationCode = ""
        verificationPassword = ""
        verificationEmailChallenge = ""
        verificationPasswordVisible = false
        reloadKey += 1
        notice = when (target.action) {
            "account_deletion" -> nativePhrase("账号已进入 7 天注销冷静期。", "Your account is now in the 7-day deletion cooling-off period.")
            "password_change" -> nativePhrase("密码修改成功。", "Password changed successfully.")
            "email_change" -> nativePhrase("邮箱修改成功。", "Email changed successfully.")
            else -> nativePhrase("操作成功。", "Action completed.")
        }
    }

    fun openEmailChange() {
        val nextEmail = emailDraft.trim().lowercase()
        when {
            !Regex("^\\S+@\\S+\\.\\S+$").matches(nextEmail) -> error = nativePhrase("请输入有效的邮箱地址。", "Enter a valid email address.")
            nextEmail == overview?.email?.trim()?.lowercase() -> error = nativePhrase("新邮箱与当前邮箱相同。", "The new email matches your current email.")
            else -> {
                emailDraft = nextEmail
                formTarget = null
                openVerification(NativePrivacyVerificationTarget("email_change", nativePhrase("修改邮箱", "Change email"), action = "email_change"))
            }
        }
    }

    fun sendEmailVerification() {
        val token = accessToken ?: return
        val current = security ?: return
        if (current.mailServiceEnabled.not() || !current.emailVerifiedAt.isNullOrBlank() || emailVerificationBusy || emailVerificationCooldown > 0) return
        scope.launch {
            emailVerificationBusy = true
            error = null
            notice = null
            runCatching { withContext(Dispatchers.IO) { NativePrivacyApi.requestEmailVerification(token) } }
                .onSuccess { retryAfter ->
                    emailVerificationCode = ""
                    emailVerificationCooldown = retryAfter
                    emailVerificationOpen = true
                    notice = nativePhrase("验证码已发送，请检查邮箱。", "Verification code sent. Check your email.")
                }
                .onFailure { error = it.message ?: nativePhrase("验证码发送失败，请稍后重试。", "Unable to send the verification code. Try again later.") }
            emailVerificationBusy = false
        }
    }

    fun confirmEmailVerification(code: String = emailVerificationCode) {
        val token = accessToken ?: return
        if (code.length != 6 || emailVerificationBusy) return
        scope.launch {
            emailVerificationBusy = true
            error = null
            notice = null
            runCatching { withContext(Dispatchers.IO) { NativePrivacyApi.confirmEmailVerification(token, code) } }
                .onSuccess { verifiedAt ->
                    security = security?.copy(emailVerifiedAt = verifiedAt ?: "verified")
                    emailVerificationCode = ""
                    emailVerificationOpen = false
                    notice = nativePhrase("邮箱验证完成。", "Email verification complete.")
                }
                .onFailure {
                    emailVerificationCode = ""
                    error = it.message ?: nativePhrase("邮箱验证失败，请检查验证码后重试。", "Email verification failed. Check the code and try again.")
                }
            emailVerificationBusy = false
        }
    }

    fun openPasswordChange() {
        when {
            newPassword.length < 8 -> error = nativePhrase("新密码至少需要 8 位。", "The new password must be at least 8 characters.")
            newPassword != passwordConfirmation -> error = nativePhrase("两次输入的新密码不一致。", "The new passwords do not match.")
            else -> {
                formTarget = null
                openVerification(NativePrivacyVerificationTarget("password_change", nativePhrase("修改密码", "Change password"), action = "password_change"))
            }
        }
    }

    fun openPasskeyRegistration() {
        formTarget = null
        openVerification(NativePrivacyVerificationTarget("passkey_registration", nativePhrase("添加通行密钥", "Add passkey"), action = "passkey_registration"))
    }

    fun savePasskeyName(passkey: NativePasskeySummary) {
        val token = accessToken ?: return
        val nextName = editingPasskeyName.trim()
        if (nextName.isBlank()) {
            error = nativePhrase("请输入通行密钥名称。", "Enter a passkey name.")
            return
        }
        if (busy != null) return
        scope.launch {
            busy = "passkey-rename-${passkey.id}"
            error = null
            runCatching { withContext(Dispatchers.IO) { NativePrivacyApi.renamePasskey(token, passkey.id, nextName) } }
                .onSuccess {
                    passkeys = passkeys.map { current -> if (current.id == passkey.id) current.copy(name = nextName) else current }
                    editingPasskeyId = 0
                    editingPasskeyName = ""
                    notice = nativePhrase("通行密钥名称已更新。", "Passkey name updated.")
                }
                .onFailure { error = it.message ?: nativePhrase("通行密钥名称更新失败。", "Unable to update the passkey name.") }
            busy = null
        }
    }

    lateinit var applySensitiveAction: suspend (NativePrivacyVerificationTarget, String) -> Unit

    fun selectVerificationMethod(method: String) {
        val target = verificationTarget ?: return
        if (verificationBusy) return
        error = null
        verificationStep = method
        verificationCode = ""
        verificationPassword = ""
        if (method == "passkey") {
            verificationBusy = true
            scope.launch {
                try {
                    val token = accessToken ?: throw IllegalStateException(nativePhrase("登录状态已失效，请重新登录。", "Your session has expired. Sign in again."))
                    val response = withContext(Dispatchers.IO) {
                        when {
                            target.kind == "passkey_delete" && target.passkeyId != null -> {
                                val options = NativePrivacyApi.passkeyDeletionOptions(token, target.passkeyId)
                                options to "passkey_delete"
                            }
                            target.kind == "totp_disable" -> {
                                val options = NativePrivacyApi.totpDisablePasskeyOptions(token)
                                options to "totp_disable"
                            }
                            else -> {
                                val options = NativePrivacyApi.sensitivePasskeyOptions(token, target.action ?: error(nativePhrase("验证操作缺少动作", "Verification action is missing")))
                                options to "sensitive"
                            }
                        }
                    }
                    val credentialResult = withTimeout(20_000L) {
                        credentialManager.getCredential(
                            context = context,
                            request = GetCredentialRequest(
                                credentialOptions = listOf(GetPublicKeyCredentialOption(response.first.requestJson)),
                            ),
                        )
                    }
                    val credential = credentialResult.credential as? PublicKeyCredential
                        ?: throw IllegalStateException(nativePhrase("未返回通行密钥凭据", "No passkey credential was returned"))
                    val sensitiveVerificationToken = withContext(Dispatchers.IO) {
                        when (response.second) {
                            "passkey_delete" -> {
                                NativePrivacyApi.verifyPasskeyDeletion(token, target.passkeyId!!, response.first.challengeToken, credential.authenticationResponseJson)
                                null
                            }
                            "totp_disable" -> {
                                NativePrivacyApi.verifyTotpDisablePasskey(token, response.first.challengeToken, credential.authenticationResponseJson)
                                null
                            }
                            else -> NativePrivacyApi.verifySensitivePasskey(token, target.action!!, response.first.challengeToken, credential.authenticationResponseJson)
                        }
                    }
                    if (target.isSensitiveAction()) {
                        applySensitiveAction(target, sensitiveVerificationToken ?: throw IllegalStateException(nativePhrase("安全验证令牌缺失", "Security verification token is missing")))
                    }
                    reloadKey += 1
                    verificationTarget = null
                    notice = when (target.kind) {
                        "passkey_delete" -> nativePhrase("通行密钥已移除。", "Passkey removed.")
                        "totp_disable" -> nativePhrase("双因素认证已关闭。", "Two-factor authentication disabled.")
                        "passkey_registration" -> nativePhrase("通行密钥已添加。", "Passkey added.")
                        "account_deletion" -> nativePhrase("账号已进入 7 天注销冷静期。", "Your account is now in the 7-day deletion cooling-off period.")
                        "password_change" -> nativePhrase("密码修改成功。", "Password changed successfully.")
                        "email_change" -> nativePhrase("邮箱修改成功。", "Email changed successfully.")
                        "totp_enrollment" -> notice ?: nativePhrase("操作成功。", "Action completed.")
                        else -> nativePhrase("操作成功。", "Action completed.")
                    }
                } catch (exception: GetCredentialException) {
                    error = credentialErrorMessage(exception)
                } catch (exception: CreateCredentialException) {
                    val message = exception.message.orEmpty()
                    if (message.contains("cancel", ignoreCase = true)) {
                        notice = nativePhrase("已取消添加通行密钥。", "Passkey creation canceled.")
                    } else {
                        error = nativePhrase("通行密钥创建失败，请确认设备已设置屏幕锁并重试。", "Passkey creation failed. Make sure a screen lock is configured and try again.")
                    }
                } catch (exception: TimeoutCancellationException) {
                    error = nativePhrase("通行密钥验证超时，请重试。", "Passkey verification timed out. Try again.")
                } catch (exception: Exception) {
                    error = exception.message ?: nativePhrase("安全验证失败，请重试。", "Security verification failed. Try again.")
                } finally {
                    verificationBusy = false
                }
            }
            return
        }
        if (method == "email") {
            scope.launch {
                verificationBusy = true
                try {
                    val token = accessToken ?: throw IllegalStateException(nativePhrase("登录状态已失效，请重新登录。", "Your session has expired. Sign in again."))
                    val challenge = withContext(Dispatchers.IO) {
                        when {
                            target.kind == "passkey_delete" && target.passkeyId != null -> NativePrivacyApi.requestPasskeyDeletionEmail(token, target.passkeyId)
                            target.kind == "totp_disable" -> NativePrivacyApi.requestTotpDisableEmail(token)
                            else -> NativePrivacyApi.requestSensitiveEmail(token, target.action ?: error(nativePhrase("验证操作缺少动作", "Verification action is missing")))
                        }
                    }
                    verificationEmailChallenge = challenge.challengeToken
                    verificationEmailCooldown = challenge.retryAfterSeconds
                    notice = nativePhrase("验证码已发送到你的邮箱。", "A verification code was sent to your email.")
                } catch (exception: Exception) {
                    error = exception.message ?: nativePhrase("验证码发送失败，请稍后重试。", "Unable to send the verification code. Try again later.")
                } finally {
                    verificationBusy = false
                }
            }
        }
    }

    fun verifyCode(code: String) {
        val target = verificationTarget ?: return
        if (code.length != 6 || verificationBusy) return
        if (verificationStep == "email" && target.kind != "totp_disable" && verificationEmailChallenge.isBlank()) return
        verificationBusy = true
        scope.launch {
            try {
                val token = accessToken ?: throw IllegalStateException(nativePhrase("登录状态已失效，请重新登录。", "Your session has expired. Sign in again."))
                when {
                    target.kind == "passkey_delete" && target.passkeyId != null -> withContext(Dispatchers.IO) {
                        if (verificationStep == "email") NativePrivacyApi.deletePasskeyWithEmail(token, target.passkeyId, verificationEmailChallenge, code)
                        else NativePrivacyApi.deletePasskeyWithTotp(token, target.passkeyId, code)
                    }
                    target.kind == "totp_disable" -> withContext(Dispatchers.IO) {
                        if (verificationStep == "email") NativePrivacyApi.disableTotpWithEmail(token, code)
                        else NativePrivacyApi.disableTotpWithTotp(token, code)
                    }
                    else -> {
                        val verificationToken = withContext(Dispatchers.IO) {
                            if (verificationStep == "email") NativePrivacyApi.verifySensitiveEmail(token, target.action!!, verificationEmailChallenge, code)
                            else NativePrivacyApi.verifySensitiveTotp(token, target.action!!, code)
                        }
                        applySensitiveAction(target, verificationToken)
                    }
                }
                if (target.kind == "passkey_delete") notice = nativePhrase("通行密钥已移除。", "Passkey removed.")
                if (target.kind == "totp_disable") notice = nativePhrase("双因素认证已关闭。", "Two-factor authentication disabled.")
                if (target.isSensitiveAction()) {
                    // Email/TOTP verification used to leave the dialog open after the
                    // protected mutation succeeded. Close it and refresh the cards so
                    // the result is visible immediately, matching the passkey path.
                    if (target.action != "totp_enrollment") finishSensitiveVerification(target)
                } else {
                    verificationTarget = null
                    verificationCode = ""
                    reloadKey += 1
                }
            } catch (exception: Exception) {
                verificationCode = ""
                error = exception.message ?: nativePhrase("验证码验证失败，请检查后重试。", "Code verification failed. Check the code and try again.")
            } finally {
                verificationBusy = false
            }
        }
    }

    fun verifyPassword() {
        val target = verificationTarget ?: return
        if (verificationPassword.isBlank() || verificationBusy) return
        verificationBusy = true
        scope.launch {
            try {
                val token = accessToken ?: throw IllegalStateException(nativePhrase("登录状态已失效，请重新登录。", "Your session has expired. Sign in again."))
                when {
                    target.kind == "passkey_delete" && target.passkeyId != null -> withContext(Dispatchers.IO) { NativePrivacyApi.deletePasskeyWithPassword(token, target.passkeyId, verificationPassword) }
                    target.kind == "totp_disable" -> withContext(Dispatchers.IO) { NativePrivacyApi.disableTotpWithPassword(token, verificationPassword) }
                    else -> {
                        val verificationToken = withContext(Dispatchers.IO) { NativePrivacyApi.verifySensitivePassword(token, target.action!!, verificationPassword) }
                        applySensitiveAction(target, verificationToken)
                    }
                }
                if (target.kind == "passkey_delete") notice = nativePhrase("通行密钥已移除。", "Passkey removed.")
                if (target.kind == "totp_disable") notice = nativePhrase("双因素认证已关闭。", "Two-factor authentication disabled.")
                if (target.isSensitiveAction()) {
                    if (target.action != "totp_enrollment") finishSensitiveVerification(target)
                } else {
                    verificationTarget = null
                    reloadKey += 1
                }
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("密码验证失败，请检查密码后重试。", "Password verification failed. Check your password and try again.")
            } finally {
                verificationBusy = false
            }
        }
    }

    applySensitiveAction = action@{ target, verificationToken ->
        val token = accessToken ?: throw IllegalStateException(nativePhrase("登录状态已失效，请重新登录。", "Your session has expired. Sign in again."))
        when (target.action) {
            "account_deletion" -> withContext(Dispatchers.IO) { NativePrivacyApi.requestDeletion(token, verificationToken) }
            "password_change" -> {
                withContext(Dispatchers.IO) { NativePrivacyApi.changePassword(token, verificationToken, newPassword) }
                newPassword = ""
                passwordConfirmation = ""
            }
            "email_change" -> {
                val changed = withContext(Dispatchers.IO) { NativePrivacyApi.changeEmail(token, verificationToken, emailDraft) }
                emailDraft = changed
            }
            "totp_enrollment" -> {
                val enrollment = withContext(Dispatchers.IO) { NativePrivacyApi.beginTotpEnrollment(token, verificationToken) }
                totpEnrollment = NativeTotpEnrollmentState(enrollment.secret, enrollment.otpAuthUri)
                verificationTarget = null
                verificationStep = "choose"
                notice = nativePhrase(
                    "验证成功，请在验证器中添加密钥后输入 6 位验证码。",
                    "Verification succeeded. Add the key to your authenticator, then enter the 6-digit code.",
                )
                return@action
            }
            "passkey_registration" -> {
                val options = withContext(Dispatchers.IO) { NativePrivacyApi.passkeyRegistrationOptions(token, verificationToken) }
                val credentialResult = withTimeout(20_000L) {
                    credentialManager.createCredential(
                        context = context,
                        request = CreatePublicKeyCredentialRequest(options.requestJson),
                    )
                }
                val credential = credentialResult as? CreatePublicKeyCredentialResponse
                    ?: throw IllegalStateException(nativePhrase("未返回通行密钥注册凭据", "No passkey registration credential was returned"))
                withContext(Dispatchers.IO) {
                    NativePrivacyApi.verifyPasskeyRegistration(token, options.challengeToken, credential.registrationResponseJson, passkeyName)
                }
                passkeyName = ""
            }
        }
        verificationTarget = null
        verificationStep = "choose"
        verificationCode = ""
        reloadKey += 1
        notice = when (target.action) {
            "account_deletion" -> nativePhrase("账号已进入 7 天注销冷静期。", "Your account is now in the 7-day deletion cooling-off period.")
            "password_change" -> nativePhrase("密码修改成功。", "Password changed successfully.")
            "email_change" -> nativePhrase("邮箱修改成功。", "Email changed successfully.")
            "passkey_registration" -> nativePhrase("通行密钥已添加。", "Passkey added.")
            else -> nativePhrase("操作成功。", "Action completed.")
        }
    }

    fun confirmTotpEnrollment() {
        val enrollment = totpEnrollment ?: return
        if (totpEnrollmentCode.length != 6 || verificationBusy) return
        val token = accessToken ?: return
        verificationBusy = true
        scope.launch {
            try {
                val recoveryCodes = withContext(Dispatchers.IO) { NativePrivacyApi.confirmTotp(token, totpEnrollmentCode) }
                totpEnrollment = null
                totpEnrollmentCode = ""
                reloadKey += 1
                notice = if (recoveryCodes.isEmpty()) {
                    nativePhrase("双因素认证已启用。", "Two-factor authentication enabled.")
                } else {
                    nativePhrase(
                        "双因素认证已启用，请保存恢复码：${recoveryCodes.joinToString("、")}",
                        "Two-factor authentication enabled. Save these recovery codes: ${recoveryCodes.joinToString(", ")}",
                    )
                }
            } catch (exception: Exception) {
                totpEnrollmentCode = ""
                error = exception.message ?: nativePhrase("验证码无效，请检查后重试。", "The code is invalid. Check it and try again.")
            } finally {
                verificationBusy = false
            }
        }
    }

    fun cancelDeletion() {
        val token = accessToken ?: return
        if (busy != null) return
        scope.launch {
            busy = "cancel-deletion"
            try {
                withContext(Dispatchers.IO) { NativePrivacyApi.cancelDeletion(token) }
                overview = overview?.copy(deletionPending = false, deletionScheduledAt = null)
                notice = nativePhrase("已撤回注销申请。", "Account deletion request withdrawn.")
                reloadKey += 1
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("撤回注销申请失败。", "Unable to withdraw the account deletion request.")
            } finally {
                busy = null
            }
        }
    }

    fun requestExport() {
        val token = accessToken ?: return
        if (busy != null) return
        scope.launch {
            busy = "export"
            error = null
            runCatching { withContext(Dispatchers.IO) { NativePrivacyApi.requestExport(token) } }
                .onSuccess {
                    exportJob = it
                    notice = nativePhrase("数据导出任务已创建，完成后可以保存文件。", "Data export requested. You can save the file when it is ready.")
                }
                .onFailure { error = it.message ?: nativePhrase("数据导出任务创建失败。", "Unable to request a data export.") }
            busy = null
        }
    }

    fun saveExport() {
        val token = accessToken ?: return
        val job = exportJob ?: return
        if (busy != null || job.status != "completed") return
        scope.launch {
            busy = "download"
            error = null
            runCatching { withContext(Dispatchers.IO) { NativePrivacyApi.downloadExport(token, job.id) } }
                .onSuccess {
                    exportContent = it
                    saveExportLauncher.launch("hlovet-data-export-${job.id}.json")
                }
                .onFailure { error = it.message ?: nativePhrase("导出文件读取失败。", "Unable to read the export file.") }
            busy = null
        }
    }

    fun unblockUser(target: NativeBlockedUser) {
        val token = accessToken ?: return
        if (busy != null) return
        scope.launch {
            busy = "unblock-${target.friendshipId}"
            error = null
            runCatching { withContext(Dispatchers.IO) { NativePrivacyApi.unblock(token, target.friendshipId) } }
                .onSuccess {
                    overview = overview?.copy(blocked = overview?.blocked.orEmpty().filterNot { it.friendshipId == target.friendshipId })
                    notice = nativePhrase("已解除对 ${target.nickname} 的屏蔽。", "${target.nickname} has been unblocked.")
                    unblockTarget = null
                }
                .onFailure { error = it.message ?: nativePhrase("解除屏蔽失败。", "Unable to unblock this user.") }
            busy = null
        }
    }

    fun updateSecurityPreference(key: String, enabled: Boolean) {
        val token = accessToken ?: return
        val current = security ?: return
        if (busy != null) return
        val nextLoginAlerts = if (key == "login") enabled else current.loginAlertsEnabled
        val nextEmailAlerts = if (key == "email") enabled else current.emailAlertsEnabled
        val nextNewDeviceAlerts = if (key == "new-device") enabled else current.newDeviceAlertsEnabled
        scope.launch {
            busy = "security-preferences"
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    NativePrivacyApi.updateSecurityPreferences(
                        accessToken = token,
                        loginAlertsEnabled = nextLoginAlerts,
                        emailAlertsEnabled = nextEmailAlerts,
                        newDeviceAlertsEnabled = nextNewDeviceAlerts,
                    )
                }
            }.onSuccess { preferences ->
                security = security?.copy(
                    loginAlertsEnabled = preferences.loginAlertsEnabled,
                    emailAlertsEnabled = preferences.emailAlertsEnabled,
                    newDeviceAlertsEnabled = preferences.newDeviceAlertsEnabled,
                )
                notice = nativePhrase("安全提醒设置已保存。", "Security alert settings saved.")
            }.onFailure {
                error = it.message ?: nativePhrase("安全提醒设置保存失败。", "Unable to save security alert settings.")
            }
            busy = null
        }
    }

    fun cancelTrustedDevice(target: NativeTrustedDevice) {
        val token = accessToken ?: return
        if (busy != null) return
        scope.launch {
            busy = "trusted-device-${target.id}"
            error = null
            runCatching {
                withContext(Dispatchers.IO) { NativePrivacyApi.cancelTrustedDevice(token, target.id) }
            }.onSuccess { currentSession ->
                security = security?.copy(
                    trustedDevices = security?.trustedDevices.orEmpty().filterNot { it.id == target.id },
                )
                trustedDeviceTarget = null
                notice = if (currentSession) {
                    nativePhrase("已取消当前设备信任；当前会话仍保持登录。", "This device is no longer trusted. The current session remains signed in.")
                } else {
                    nativePhrase("已取消该设备信任。", "Device trust removed.")
                }
            }.onFailure {
                error = it.message ?: nativePhrase("取消设备信任失败。", "Unable to remove device trust.")
            }
            busy = null
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("privacy-list"),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(
                title = { Text(nativePhrase("隐私与数据", "Privacy and data"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(onClick = { reloadKey += 1 }, enabled = busy == null) {
                        Icon(Icons.Filled.Refresh, contentDescription = nativePhrase("刷新", "Refresh"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (loading) {
            item { EmptyState(nativePhrase("正在读取隐私与数据…", "Loading privacy and data settings...")) }
        } else if (!error.isNullOrBlank() && overview == null) {
            item { EmptyState(error!!) }
        } else {
            overview?.let { current ->
                item {
                    PrivacyCard(title = nativePhrase("账号凭据", "Account credentials"), icon = Icons.Filled.Lock) {
                        PrivacyLine(nativePhrase("当前邮箱", "Current email"), current.email.ifBlank { nativePhrase("未设置", "Not set") })
                        PrivacyLine(
                            nativePhrase("邮箱状态", "Email status"),
                            if (security?.emailVerifiedAt != null) nativePhrase("已验证", "Verified") else nativePhrase("未验证", "Unverified"),
                        )
                        Text(nativePhrase("修改邮箱或密码前，需要先通过一种安全验证。", "Complete one security verification before changing your email or password."), color = TextMuted, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    emailDraft = current.email
                                    formTarget = if (formTarget == "email") null else "email"
                                },
                                enabled = busy == null && !verificationBusy,
                            ) { Text(nativePhrase("修改邮箱", "Change email"), fontSize = 12.sp) }
                            FilledTonalButton(
                                onClick = { formTarget = if (formTarget == "password") null else "password" },
                                enabled = busy == null && !verificationBusy,
                            ) { Text(nativePhrase("修改密码", "Change password"), fontSize = 12.sp) }
                        }
                        if (formTarget == "email") {
                            OutlinedTextField(
                                value = emailDraft,
                                onValueChange = { emailDraft = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(nativePhrase("新邮箱", "New email")) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                colors = loginFieldColors(),
                            )
                            FilledTonalButton(onClick = ::openEmailChange, enabled = emailDraft.isNotBlank()) {
                                Text(nativePhrase("继续验证", "Continue to verification"), fontSize = 12.sp)
                            }
                        }
                        if (formTarget == "password") {
                            OutlinedTextField(
                                value = newPassword,
                                onValueChange = { newPassword = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(nativePhrase("新密码", "New password")) },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = if (passwordVisible) nativePhrase("隐藏密码", "Hide password") else nativePhrase("显示密码", "Show password"),
                                        )
                                    }
                                },
                                colors = loginFieldColors(),
                            )
                            OutlinedTextField(
                                value = passwordConfirmation,
                                onValueChange = { passwordConfirmation = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(nativePhrase("确认新密码", "Confirm new password")) },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                colors = loginFieldColors(),
                            )
                            FilledTonalButton(onClick = ::openPasswordChange, enabled = newPassword.isNotBlank() && passwordConfirmation.isNotBlank()) {
                                Text(nativePhrase("继续验证", "Continue to verification"), fontSize = 12.sp)
                            }
                        }
                    }
                }
                item {
                    PrivacyCard(title = nativePhrase("注销账号", "Delete account"), icon = Icons.Filled.Lock) {
                        Text(nativePhrase("注销会保留公开内容并匿名显示；7 天冷静期内可以撤回。", "Public content will remain anonymously visible. You can withdraw the request during the 7-day cooling-off period."), color = TextMuted, fontSize = 12.sp)
                        if (current.deletionPending) {
                            Text(
                                nativePhrase(
                                    "账号处于注销冷静期${current.deletionScheduledAt?.let { "，计划于 ${privacyTime(it)} 完成" }.orEmpty()}。",
                                    "Your account is in the deletion cooling-off period${current.deletionScheduledAt?.let { "; scheduled for ${privacyTime(it)}" }.orEmpty()}.",
                                ),
                                color = Coral,
                                fontSize = 12.sp,
                            )
                            FilledTonalButton(onClick = ::cancelDeletion, enabled = busy == null) {
                                Text(if (busy == "cancel-deletion") nativePhrase("撤回中…", "Withdrawing...") else nativePhrase("撤回注销", "Withdraw deletion"), fontSize = 12.sp)
                            }
                        } else {
                            FilledTonalButton(
                                onClick = { openVerification(NativePrivacyVerificationTarget("account_deletion", nativePhrase("申请注销账号", "Request account deletion"), action = "account_deletion")) },
                                enabled = busy == null,
                            ) { Text(nativePhrase("申请注销", "Request deletion"), fontSize = 12.sp, color = Coral) }
                        }
                    }
                }
                item {
                    PrivacyCard(title = nativePhrase("双因素认证", "Two-factor authentication"), icon = Icons.Filled.Shield) {
                        PrivacyLine(nativePhrase("当前状态", "Current status"), if (current.totpEnabled) nativePhrase("已启用", "Enabled") else nativePhrase("未启用", "Disabled"))
                        if (current.totpEnabled) {
                            Text(nativePhrase("解除时可使用通行密钥、邮箱、当前密码或身份验证器验证码。", "Use a passkey, email, current password, or authenticator code to disable it."), color = TextMuted, fontSize = 11.sp)
                            FilledTonalButton(
                                onClick = { openVerification(NativePrivacyVerificationTarget("totp_disable", nativePhrase("解除双因素认证", "Disable two-factor authentication"))) },
                                enabled = busy == null,
                            ) { Text(nativePhrase("解除双因素认证", "Disable two-factor authentication"), fontSize = 12.sp) }
                        } else if (totpEnrollment == null) {
                            Text(nativePhrase("绑定前需要先完成一次安全验证。", "Complete one security verification before enabling it."), color = TextMuted, fontSize = 11.sp)
                            FilledTonalButton(
                                onClick = { openVerification(NativePrivacyVerificationTarget("totp_enrollment", nativePhrase("绑定双因素认证", "Enable two-factor authentication"), action = "totp_enrollment")) },
                                enabled = busy == null,
                            ) { Text(nativePhrase("绑定双因素认证", "Enable two-factor authentication"), fontSize = 12.sp) }
                        }
                        totpEnrollment?.let { enrollment ->
                            Text(nativePhrase("请在身份验证器中添加以下密钥：", "Add this key to your authenticator:"), color = TextMuted, fontSize = 11.sp)
                            Text(enrollment.secret, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.testTag("privacy-totp-secret"))
                            if (enrollment.otpAuthUri.isNotBlank()) {
                                Text(enrollment.otpAuthUri, color = TextMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            PrivacyCodeInput(
                                value = totpEnrollmentCode,
                                onValueChange = { next ->
                                    totpEnrollmentCode = next
                                    if (next.length == 6) confirmTotpEnrollment()
                                },
                                enabled = !verificationBusy,
                                allowLetters = false,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = ::confirmTotpEnrollment, enabled = totpEnrollmentCode.length == 6 && !verificationBusy) {
                                    Text(if (verificationBusy) nativePhrase("验证中…", "Verifying...") else nativePhrase("确认绑定", "Confirm setup"), fontSize = 12.sp)
                                }
                                TextButton(onClick = { totpEnrollment = null; totpEnrollmentCode = "" }, enabled = !verificationBusy) {
                                    Text(nativePhrase("取消", "Cancel"), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
            item {
                PrivacyCard(title = nativePhrase("数据导出", "Data export"), icon = Icons.Filled.Download) {
                    Text(nativePhrase("导出个人资料、文章、评论、收藏、订阅和安全记录。", "Export your profile, articles, comments, favorites, subscriptions, and security records."), color = TextMuted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = ::requestExport, enabled = busy == null) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (busy == "export") nativePhrase("生成中…", "Generating...") else nativePhrase("申请导出", "Request export"), fontSize = 12.sp)
                        }
                        exportJob?.let { job ->
                            Text(exportStatusLabel(job.status), color = TextMuted, fontSize = 11.sp)
                        }
                    }
                    exportJob?.let { job ->
                        if (!job.error.isNullOrBlank()) Text(job.error!!, color = Coral, fontSize = 11.sp)
                        if (job.status == "completed") {
                            TextButton(onClick = ::saveExport, enabled = busy == null) {
                                Text(nativePhrase("保存 JSON", "Save JSON"), color = Accent, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            item {
                PrivacyCard(title = nativePhrase("屏蔽关系", "Blocked users"), icon = Icons.Filled.Lock) {
                    val blocked = overview?.blocked.orEmpty()
                    if (blocked.isEmpty()) {
                        Text(nativePhrase("暂无屏蔽用户。", "No blocked users."), color = TextMuted, fontSize = 12.sp)
                    } else {
                        blocked.forEach { target ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(target.nickname, Coral, compact = true)
                                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                                    Text(target.nickname, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("@${target.username}", color = TextMuted, fontSize = 11.sp)
                                }
                                TextButton(onClick = { unblockTarget = target }, enabled = busy == null) {
                                    Text(nativePhrase("解除屏蔽", "Unblock"), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
            item {
                PrivacyCard(title = nativePhrase("登录保护", "Sign-in protection"), icon = Icons.Filled.Shield) {
                    security?.let { current ->
                        Text(
                            if (current.mailServiceEnabled) {
                                nativePhrase("可分别控制登录和新设备提醒；邮件提醒依赖邮件服务。", "Control sign-in and new-device alerts separately. Email alerts depend on the mail service.")
                            } else {
                                nativePhrase("邮件服务未启用，邮箱验证和邮件提醒暂不可用。", "The mail service is disabled, so email verification and email alerts are unavailable.")
                            },
                            color = TextMuted,
                            fontSize = 11.sp,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (current.emailVerifiedAt.isNullOrBlank()) nativePhrase("邮箱待验证", "Email verification pending") else nativePhrase("邮箱已验证", "Email verified"),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (current.emailVerifiedAt.isNullOrBlank()) Coral else Accent,
                                )
                                Text(
                                    if (current.emailVerifiedAt.isNullOrBlank()) {
                                        nativePhrase("验证后可接收找回密码和风险提醒邮件。", "Verify your email to receive recovery and risk alert emails.")
                                    } else {
                                        nativePhrase("当前邮箱已完成验证。", "The current email address is verified.")
                                    },
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                )
                            }
                            if (current.emailVerifiedAt.isNullOrBlank() && current.mailServiceEnabled) {
                                TextButton(
                                    onClick = ::sendEmailVerification,
                                    enabled = !emailVerificationBusy && emailVerificationCooldown <= 0,
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        when {
                                            emailVerificationBusy -> nativePhrase("发送中…", "Sending...")
                                            emailVerificationCooldown > 0 -> nativePhrase("${emailVerificationCooldown}s", "${emailVerificationCooldown}s")
                                            else -> nativePhrase("验证邮箱", "Verify email")
                                        },
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                        if (current.emailVerifiedAt.isNullOrBlank() && emailVerificationOpen && current.mailServiceEnabled) {
                            PrivacyCodeInput(
                                value = emailVerificationCode,
                                onValueChange = { next ->
                                    emailVerificationCode = next
                                    if (next.length == 6) confirmEmailVerification(next)
                                },
                                enabled = !emailVerificationBusy,
                                allowLetters = false,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(nativePhrase("验证码已发送到当前邮箱。", "The code was sent to the current email."), color = TextMuted, fontSize = 10.sp)
                                TextButton(
                                    onClick = ::sendEmailVerification,
                                    enabled = !emailVerificationBusy && emailVerificationCooldown <= 0,
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        if (emailVerificationCooldown > 0) nativePhrase("${emailVerificationCooldown}s 后重发", "Resend in ${emailVerificationCooldown}s") else nativePhrase("重新发送", "Resend"),
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                        SecurityPreferenceRow(
                            title = nativePhrase("登录提醒", "Sign-in alerts"),
                            description = nativePhrase("在账号发生登录事件时提示。", "Notify you when a sign-in event occurs."),
                            checked = current.loginAlertsEnabled,
                            enabled = busy == null,
                            onCheckedChange = { updateSecurityPreference("login", it) },
                        )
                        SecurityPreferenceRow(
                            title = nativePhrase("邮件提醒", "Email alerts"),
                            description = if (current.mailServiceEnabled) {
                                nativePhrase("通过已验证邮箱接收风险提醒。", "Receive risk alerts through your verified email.")
                            } else {
                                nativePhrase("邮件服务已停用。", "The mail service is disabled.")
                            },
                            checked = current.emailAlertsEnabled,
                            enabled = busy == null && current.mailServiceEnabled,
                            onCheckedChange = { updateSecurityPreference("email", it) },
                        )
                        SecurityPreferenceRow(
                            title = nativePhrase("新设备提醒", "New-device alerts"),
                            description = nativePhrase("发现新设备登录时额外提醒。", "Send an extra alert when a new device signs in."),
                            checked = current.newDeviceAlertsEnabled,
                            enabled = busy == null,
                            onCheckedChange = { updateSecurityPreference("new-device", it) },
                        )
                        if (current.trustedDevices.isNotEmpty()) {
                            Text(nativePhrase("可信设备", "Trusted devices"), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
                            current.trustedDevices.forEach { device ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            nativePhrase(
                                                "${device.label}${if (device.current) " · 当前设备" else ""}",
                                                "${device.label}${if (device.current) " · Current device" else ""}",
                                            ),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            buildString {
                                                append(device.ip.takeIf { it.isNotBlank() } ?: nativePhrase("IP 未记录", "IP not recorded"))
                                                if (device.lastSeenAt.isNotBlank()) append(nativePhrase(" · 最近 ${privacyTime(device.lastSeenAt)}", " · Last seen ${privacyTime(device.lastSeenAt)}"))
                                            },
                                            color = TextMuted,
                                            fontSize = 10.sp,
                                        )
                                    }
                                    IconButton(
                                        onClick = { trustedDeviceTarget = device },
                                        enabled = busy == null,
                                        modifier = Modifier.size(34.dp),
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = nativePhrase("取消信任", "Remove trust"), tint = Coral, modifier = Modifier.size(17.dp))
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(top = 4.dp))
                        Text(nativePhrase("最近安全事件", "Recent security events"), color = TextMuted, fontSize = 11.sp)
                        if (current.events.isEmpty()) {
                            Text(nativePhrase("暂无安全事件。", "No security events."), color = TextMuted, fontSize = 11.sp)
                        } else {
                            current.events.take(5).forEach { event ->
                                SecurityEventRow(event)
                            }
                        }
                    } ?: Text(nativePhrase("账号安全信息暂时不可用。", "Account security information is currently unavailable."), color = TextMuted, fontSize = 12.sp)
                }
            }
            item {
                PrivacyCard(title = nativePhrase("通行密钥", "Passkeys"), icon = Icons.Filled.Fingerprint) {
                    Text(nativePhrase("通行密钥可用于无密码登录，也可验证敏感操作。添加前需要先验证身份。", "Passkeys support passwordless sign-in and sensitive action verification. Verify your identity before adding one."), color = TextMuted, fontSize = 11.sp)
                    FilledTonalButton(
                        onClick = { formTarget = if (formTarget == "passkey") null else "passkey" },
                        enabled = busy == null && !verificationBusy,
                    ) {
                        Icon(Icons.Filled.AddCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(nativePhrase("添加通行密钥", "Add passkey"), fontSize = 12.sp)
                    }
                    if (formTarget == "passkey") {
                        OutlinedTextField(
                            value = passkeyName,
                            onValueChange = { passkeyName = it.take(120) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(nativePhrase("密钥名称（可选）", "Passkey name (optional)")) },
                            colors = loginFieldColors(),
                        )
                        FilledTonalButton(onClick = ::openPasskeyRegistration, enabled = !verificationBusy) {
                            Text(nativePhrase("继续验证", "Continue to verification"), fontSize = 12.sp)
                    }
                    if (passkeys.isEmpty()) {
                        Text(nativePhrase("还没有绑定通行密钥。", "No passkeys added yet."), color = TextMuted, fontSize = 12.sp)
                    } else {
                        passkeys.forEach { passkey ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (editingPasskeyId == passkey.id) {
                                    OutlinedTextField(
                                        value = editingPasskeyName,
                                        onValueChange = { editingPasskeyName = it.take(120) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        label = { Text(nativePhrase("密钥名称", "Passkey name")) },
                                        colors = loginFieldColors(),
                                    )
                                    IconButton(
                                        onClick = { savePasskeyName(passkey) },
                                        enabled = busy == null && !verificationBusy,
                                    ) { Icon(Icons.Filled.Check, contentDescription = nativePhrase("保存名称", "Save name"), tint = Accent) }
                                    IconButton(
                                        onClick = { editingPasskeyId = 0; editingPasskeyName = "" },
                                        enabled = busy == null && !verificationBusy,
                                    ) { Icon(Icons.Filled.Close, contentDescription = nativePhrase("取消编辑", "Cancel editing"), tint = TextMuted) }
                                } else {
                                    Column(Modifier.weight(1f)) {
                                        Text(passkey.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        Text(nativePhrase("添加于 ${privacyTime(passkey.createdAt)}", "Added ${privacyTime(passkey.createdAt)}"), color = TextMuted, fontSize = 10.sp)
                                    }
                                    IconButton(
                                        onClick = { editingPasskeyId = passkey.id; editingPasskeyName = passkey.name },
                                        enabled = busy == null && !verificationBusy,
                                    ) { Icon(Icons.Filled.Edit, contentDescription = nativePhrase("编辑名称", "Edit name"), tint = Accent, modifier = Modifier.size(17.dp)) }
                                    TextButton(
                                        onClick = { openVerification(NativePrivacyVerificationTarget("passkey_delete", nativePhrase("移除通行密钥", "Remove passkey"), passkeyId = passkey.id)) },
                                        enabled = busy == null && !verificationBusy,
                                    ) { Text(nativePhrase("移除", "Remove"), fontSize = 11.sp, color = Coral) }
                                }
                            }
                        }
                    }
                }
            }
            }
            item {
                PrivacyCard(title = nativePhrase("最近隐私操作", "Recent privacy activity"), icon = Icons.Filled.Settings) {
                    if (audit.isEmpty()) {
                        Text(nativePhrase("暂无隐私操作记录。", "No privacy activity yet."), color = TextMuted, fontSize = 12.sp)
                    } else {
                        audit.take(8).forEach { record ->
                            PrivacyLine(privacyActionLabel(record.action), privacyTime(record.createdAt))
                        }
                    }
                }
            }
            if (!error.isNullOrBlank()) item { Text(error!!, color = Coral, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
            notice?.let { message -> item { Text(message, color = Accent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp).testTag("privacy-notice")) } }
        }
    }

    unblockTarget?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (busy == null) unblockTarget = null },
            title = { Text(nativePhrase("解除屏蔽", "Unblock user")) },
            text = { Text(nativePhrase("确定要解除对 ${target.nickname} 的屏蔽吗？", "Unblock ${target.nickname}?")) },
            confirmButton = {
                TextButton(onClick = { unblockUser(target) }, enabled = busy == null) { Text(nativePhrase("确定", "Confirm"), color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { unblockTarget = null }, enabled = busy == null) { Text(nativePhrase("取消", "Cancel")) }
            },
        )
    }

    trustedDeviceTarget?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = { if (busy == null) trustedDeviceTarget = null },
            title = { Text(nativePhrase("取消设备信任", "Remove device trust")) },
            text = {
                Text(
                    if (target.current) {
                        nativePhrase(
                            "确定取消当前设备的信任吗？当前会话不会退出，但下次登录需要重新验证。",
                            "Remove trust from this device? The current session will remain signed in, but the next sign-in will require verification.",
                        )
                    } else {
                        nativePhrase(
                            "确定取消“${target.label}”的信任吗？该设备下次登录需要重新验证。",
                            "Remove trust from \"${target.label}\"? Its next sign-in will require verification.",
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { cancelTrustedDevice(target) },
                    enabled = busy == null,
                ) {
                    Text(
                        if (busy == "trusted-device-${target.id}") nativePhrase("处理中…", "Processing...") else nativePhrase("确定", "Confirm"),
                        color = Coral,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { trustedDeviceTarget = null }, enabled = busy == null) { Text(nativePhrase("取消", "Cancel")) }
            },
        )
    }

    verificationTarget?.let { target ->
        HlovetAlertDialog(
            onDismissRequest = ::closeVerification,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (verificationStep != "choose") {
                        IconButton(
                            onClick = {
                                if (!verificationBusy) {
                                    verificationStep = "choose"
                                    verificationCode = ""
                                    verificationPassword = ""
                                    error = null
                                }
                            },
                            enabled = !verificationBusy,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回验证方式", "Back to verification methods"))
                        }
                    }
                    Text(if (verificationStep == "choose") nativePhrase("选择验证方式", "Choose a verification method") else target.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (verificationStep) {
                        "choose" -> {
                            Text(
                                nativePhrase(
                                    "通过任意一种可用方式验证后，将立即继续${target.title}。",
                                    "Complete any available verification method to continue: ${target.title}.",
                                ),
                                color = TextMuted,
                                fontSize = 12.sp,
                            )
                            if (passkeys.isNotEmpty()) {
                                FilledTonalButton(
                                    onClick = { selectVerificationMethod("passkey") },
                                    enabled = !verificationBusy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(7.dp))
                                    Text(nativePhrase("通行密钥", "Passkey"))
                                }
                            }
                            if (!overview?.email.isNullOrBlank()) {
                                FilledTonalButton(
                                    onClick = { selectVerificationMethod("email") },
                                    enabled = !verificationBusy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(nativePhrase("邮箱验证码", "Email code")) }
                            }
                            if (overview?.totpEnabled == true) {
                                FilledTonalButton(
                                    onClick = { selectVerificationMethod("totp") },
                                    enabled = !verificationBusy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(nativePhrase("双因素认证或恢复码", "Authenticator or recovery code")) }
                            }
                            FilledTonalButton(
                                onClick = { selectVerificationMethod("password") },
                                enabled = !verificationBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(nativePhrase("当前密码", "Current password")) }
                        }
                        "passkey" -> {
                            Text(nativePhrase("请在系统窗口中完成通行密钥验证。", "Complete passkey verification in the system window."), color = TextMuted, fontSize = 12.sp)
                            if (verificationBusy) Text(nativePhrase("正在等待设备验证…", "Waiting for device verification..."), color = Accent, fontSize = 12.sp)
                        }
                        "password" -> {
                            Text(nativePhrase("输入当前账号密码。密码错误时会在这里明确提示。", "Enter your current password. An incorrect password will be reported here."), color = TextMuted, fontSize = 12.sp)
                            OutlinedTextField(
                                value = verificationPassword,
                                onValueChange = { verificationPassword = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !verificationBusy,
                                singleLine = true,
                                label = { Text(nativePhrase("当前密码", "Current password")) },
                                visualTransformation = if (verificationPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { verificationPasswordVisible = !verificationPasswordVisible }) {
                                        Icon(
                                            if (verificationPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = if (verificationPasswordVisible) nativePhrase("隐藏密码", "Hide password") else nativePhrase("显示密码", "Show password"),
                                        )
                                    }
                                },
                                colors = loginFieldColors(),
                            )
                            FilledTonalButton(
                                onClick = ::verifyPassword,
                                enabled = verificationPassword.isNotBlank() && !verificationBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (verificationBusy) nativePhrase("验证中…", "Verifying...") else nativePhrase("继续", "Continue")) }
                        }
                        "email", "totp" -> {
                            Text(
                                if (verificationStep == "email") {
                                    nativePhrase("输入邮箱中的 6 位验证码，填满后自动验证。", "Enter the 6-digit email code. Verification starts automatically when all digits are filled.")
                                } else {
                                    nativePhrase("输入身份验证器验证码或 6 位恢复码，填满后自动验证。", "Enter an authenticator code or 6-character recovery code. Verification starts automatically when filled.")
                                },
                                color = TextMuted,
                                fontSize = 12.sp,
                            )
                            PrivacyCodeInput(
                                value = verificationCode,
                                onValueChange = { next ->
                                    verificationCode = next
                                    if (next.length == 6) verifyCode(next)
                                },
                                enabled = !verificationBusy,
                                allowLetters = verificationStep == "totp",
                            )
                            if (verificationBusy) Text(nativePhrase("正在验证…", "Verifying..."), color = Accent, fontSize = 11.sp)
                            if (verificationStep == "email") {
                                TextButton(
                                    onClick = { selectVerificationMethod("email") },
                                    enabled = !verificationBusy && verificationEmailCooldown <= 0,
                                ) {
                                    Text(
                                        if (verificationEmailCooldown > 0) {
                                            nativePhrase("${verificationEmailCooldown}s 后可重新发送", "Resend in ${verificationEmailCooldown}s")
                                        } else {
                                            nativePhrase("重新发送验证码", "Resend code")
                                        },
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                    if (!error.isNullOrBlank()) Text(error!!, color = Coral, fontSize = 11.sp)
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = ::closeVerification, enabled = !verificationBusy) { Text(nativePhrase("关闭", "Close")) }
            },
        )
    }
}

private data class QuadPrivacyData(
    val overview: NativePrivacyOverview,
    val security: NativeSecurityOverview?,
    val passkeys: List<NativePasskeySummary>,
    val audit: List<NativePrivacyAuditRecord>,
)

@Composable
private fun PrivacyCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    GlassCard(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(9.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            content()
        }
    }
}

@Composable
private fun SecurityPreferenceRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = TextMuted, fontSize = 10.sp)
        }
        HlovetSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun SecurityEventRow(event: NativeSecurityEvent) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(nativeSecurityRiskColor(event.riskLevel), CircleShape),
        )
        Column(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp)) {
            Text(
                nativeSecurityEventLabel(event.type, event.summary),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (event.summary.isNotBlank() && event.summary != nativeSecurityEventLabel(event.type, event.summary)) {
                Text(event.summary, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(privacyTime(event.createdAt), color = TextMuted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun PrivacyCodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    allowLetters: Boolean,
) {
    HlovetCodeInput(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        allowLetters = allowLetters,
        label = if (allowLetters) nativePhrase("验证码或恢复码", "Authenticator or recovery code") else nativePhrase("6 位验证码", "6-digit code"),
    )
}

@Composable
private fun PrivacyLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Text(value, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun exportStatusLabel(status: String): String = when (status) {
    "queued" -> nativePhrase("排队中", "Queued")
    "processing" -> nativePhrase("生成中", "Generating")
    "completed" -> nativePhrase("已完成", "Completed")
    "failed" -> nativePhrase("失败", "Failed")
    "expired" -> nativePhrase("已过期", "Expired")
    else -> status.ifBlank { nativePhrase("未知状态", "Unknown status") }
}

private fun privacyActionLabel(action: String): String = when (action) {
    "privacy_overview_viewed" -> nativePhrase("查看隐私设置", "Viewed privacy settings")
    "data_export_requested" -> nativePhrase("申请数据导出", "Requested data export")
    "data_export_completed" -> nativePhrase("生成数据导出", "Generated data export")
    "data_export_downloaded" -> nativePhrase("下载数据导出", "Downloaded data export")
    "account_deletion_requested" -> nativePhrase("申请注销账号", "Requested account deletion")
    "account_deletion_cancelled" -> nativePhrase("撤回注销申请", "Withdrew account deletion")
    "totp_enrollment_started" -> nativePhrase("开始绑定双因素认证", "Started two-factor authentication setup")
    "totp_enabled" -> nativePhrase("启用双因素认证", "Enabled two-factor authentication")
    "totp_disabled", "totp_disabled_by_email" -> nativePhrase("关闭双因素认证", "Disabled two-factor authentication")
    else -> action.ifBlank { nativePhrase("隐私设置变更", "Privacy setting changed") }
}

internal fun nativeSecurityEventLabel(type: String, fallback: String = ""): String = when (type) {
    "login", "login_success" -> nativePhrase("登录成功", "Sign-in successful")
    "login_failed" -> nativePhrase("登录失败", "Sign-in failed")
    "new_device" -> nativePhrase("新设备登录", "New-device sign-in")
    "new_ip", "unfamiliar_ip" -> nativePhrase("陌生 IP 登录", "Unfamiliar IP sign-in")
    "unusual_frequency" -> nativePhrase("异常登录频率", "Unusual sign-in frequency")
    "login_blocked" -> nativePhrase("登录已限制", "Sign-in blocked")
    "password_changed" -> nativePhrase("密码已修改", "Password changed")
    "password_reset" -> nativePhrase("密码已重置", "Password reset")
    "email_verified" -> nativePhrase("邮箱已验证", "Email verified")
    else -> fallback.ifBlank { nativePhrase("安全事件", "Security event") }
}

private fun nativeSecurityRiskColor(level: String): Color = when (level.lowercase()) {
    "high", "critical" -> Coral
    "medium", "warning" -> Color(0xFFE3A84B)
    else -> Accent
}

private fun privacyTime(value: String): String = when {
    value.length >= 16 -> value.substring(0, 16).replace('T', ' ')
    value.isBlank() -> ""
    else -> value
}

@Composable
private fun AccountScreen(
    target: AccountTarget,
    session: NativeSession?,
    onBack: () -> Unit,
    onArticleClick: (RemoteArticle) -> Unit,
) {
    val searchable = target == AccountTarget.MyFavorites ||
        target == AccountTarget.MyLiked ||
        target == AccountTarget.ReadLater ||
        target == AccountTarget.ReadingHistory ||
        target == AccountTarget.MySubscriptions
    var searchQuery by remember(session?.accessToken, target) { mutableStateOf("") }
    var articleList by remember(session?.accessToken, target, searchQuery) { mutableStateOf<NativeArticleList?>(null) }
    var loading by remember(session?.accessToken, target, searchQuery) { mutableStateOf(true) }
    var loadingMore by remember(session?.accessToken, target, searchQuery) { mutableStateOf(false) }
    var error by remember(session?.accessToken, target, searchQuery) { mutableStateOf<String?>(null) }
    var notice by remember(session?.accessToken, target, searchQuery) { mutableStateOf<String?>(null) }
    var clearHistoryConfirm by remember(session?.accessToken, target) { mutableStateOf(false) }
    var historyActionBusy by remember(session?.accessToken, target) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun removeHistory(article: RemoteArticle) {
        val accessToken = session?.accessToken ?: return
        if (historyActionBusy || target != AccountTarget.ReadingHistory) return
        scope.launch {
            historyActionBusy = true
            error = null
            try {
                withContext(Dispatchers.IO) { NativeArticleApi.removeReadingHistory(accessToken, article.id) }
                articleList = articleList?.let { current ->
                    current.copy(
                        items = current.items.filterNot { it.id == article.id },
                        total = (current.total - 1).coerceAtLeast(0),
                    )
                }
                notice = nativePhrase("已移除这条阅读记录。", "Reading history entry removed.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("移除阅读记录失败。", "Failed to remove the reading history entry.")
            } finally {
                historyActionBusy = false
            }
        }
    }

    fun clearHistory() {
        val accessToken = session?.accessToken ?: return
        if (historyActionBusy || target != AccountTarget.ReadingHistory) return
        scope.launch {
            historyActionBusy = true
            clearHistoryConfirm = false
            error = null
            try {
                withContext(Dispatchers.IO) { NativeArticleApi.clearReadingHistory(accessToken) }
                articleList = articleList?.copy(items = emptyList(), total = 0)
                notice = nativePhrase("阅读历史已清空。", "Reading history cleared.")
            } catch (exception: Exception) {
                error = exception.message ?: nativePhrase("清空阅读历史失败。", "Failed to clear reading history.")
            } finally {
                historyActionBusy = false
            }
        }
    }

    fun loadArticlePage(accessToken: String, page: Int): NativeArticleList? {
        val query = searchQuery.trim().takeIf { it.isNotBlank() }
        return when (target) {
            AccountTarget.MyFavorites -> NativeArticleApi.listFavorites(accessToken, page = page, pageSize = 12, search = query)
            AccountTarget.MyLiked -> NativeArticleApi.listLiked(accessToken, page = page, pageSize = 12, search = query)
            AccountTarget.ReadLater -> NativeArticleApi.listReadLater(accessToken, page = page, pageSize = 12, search = query)
            AccountTarget.ReadingHistory -> NativeArticleApi.listReadingHistory(accessToken, page = page, pageSize = 12, search = query)
            AccountTarget.MySubscriptions -> NativeArticleApi.listSubscriptions(accessToken, page = page, pageSize = 12, search = query)
            else -> null
        }
    }

    fun loadMoreArticles() {
        val accessToken = session?.accessToken ?: return
        val current = articleList ?: return
        if (!searchable || loading || loadingMore || current.page >= current.totalPages) return
        loadingMore = true
        error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { loadArticlePage(accessToken, current.page + 1) } }
                .onSuccess { nextPage ->
                    if (nextPage == null) return@onSuccess
                    val existing = articleList
                    articleList = if (existing == null || nextPage.page <= 1) {
                        nextPage
                    } else {
                        existing.copy(
                            items = (existing.items + nextPage.items).distinctBy { it.id },
                            total = nextPage.total,
                            page = nextPage.page,
                            totalPages = nextPage.totalPages,
                        )
                    }
                }
                .onFailure { exception ->
                    error = exception.message ?: nativePhrase("更多内容加载失败，请稍后重试。", "Unable to load more content. Try again later.")
                }
            loadingMore = false
        }
    }

    LaunchedEffect(session?.accessToken, target, searchQuery) {
        val accessToken = session?.accessToken
        if (accessToken.isNullOrBlank()) {
            loading = false
            error = nativePhrase("请先登录后查看个人内容。", "Sign in to view your personal content.")
            return@LaunchedEffect
        }
        loading = true
        loadingMore = false
        error = null
        notice = null
        delay(220)
        try {
            articleList = when (target) {
                AccountTarget.Dashboard,
                AccountTarget.Navigation,
                AccountTarget.Tools,
                AccountTarget.Install,
                AccountTarget.ProfileEditor -> NativeArticleList(emptyList(), 0, 1, 1)
                AccountTarget.Announcements,
                AccountTarget.Feedback,
                AccountTarget.Suggestions,
                AccountTarget.AnonymousTopics,
                  AccountTarget.AnonymousTopicManagement,
                AccountTarget.RecommendationFeedback,
                AccountTarget.TemplateManagement,
                AccountTarget.Offline -> NativeArticleList(emptyList(), 0, 1, 1)
                AccountTarget.MyArticles -> withContext(Dispatchers.IO) { NativeArticleApi.listMine(accessToken, page = 1, pageSize = 12) }
                AccountTarget.MyFavorites,
                AccountTarget.MyLiked,
                AccountTarget.ReadLater,
                AccountTarget.ReadingHistory,
                AccountTarget.MySubscriptions -> withContext(Dispatchers.IO) { loadArticlePage(accessToken, 1) }
                AccountTarget.SubscriptionFeed -> NativeArticleList(emptyList(), 0, 1, 1)
                AccountTarget.MyCollections -> NativeArticleList(emptyList(), 0, 1, 1)
                AccountTarget.TopicManagement -> NativeArticleList(emptyList(), 0, 1, 1)
                AccountTarget.Privacy -> NativeArticleList(emptyList(), 0, 1, 1)
                 AccountTarget.Sessions -> NativeArticleList(emptyList(), 0, 1, 1)
                AccountTarget.Integrations -> NativeArticleList(emptyList(), 0, 1, 1)
                AccountTarget.MyReports -> NativeArticleList(emptyList(), 0, 1, 1)
                AccountTarget.AiAssistant -> NativeArticleList(emptyList(), 0, 1, 1)
                 AccountTarget.AdminHome,
                 AccountTarget.AdminTopics,
                 AccountTarget.AdminArticles,
                AccountTarget.AdminAnalytics,
                AccountTarget.AdminAudit,
                AccountTarget.AdminUsers,
                AccountTarget.AdminReports,
                AccountTarget.AdminAnnouncements,
                AccountTarget.AdminContent,
                AccountTarget.AdminViolations,
                AccountTarget.AdminFeedback,
                AccountTarget.AdminResources,
                AccountTarget.AdminGroups,
                AccountTarget.AdminDeletedUsers,
                AccountTarget.AdminSecurity,
                AccountTarget.AdminCache,
                AccountTarget.AdminAndroid,
                AccountTarget.AdminIntegrations,
                AccountTarget.AdminSettings,
                AccountTarget.AdminAi -> NativeArticleList(emptyList(), 0, 1, 1)
                AccountTarget.SystemStatus,
                AccountTarget.AdminBackups,
                AccountTarget.AdminOperations -> NativeArticleList(emptyList(), 0, 1, 1)
                AccountTarget.AdminStorage -> NativeArticleList(emptyList(), 0, 1, 1)
                AccountTarget.ResourceCenter,
                AccountTarget.Points,
                AccountTarget.ResourceOrders,
                AccountTarget.ResourceEarnings -> NativeArticleList(emptyList(), 0, 1, 1)
            }
        } catch (exception: Exception) {
            error = exception.message ?: nativePhrase("内容加载失败，请稍后重试。", "Content could not be loaded. Try again later.")
        } finally {
            loading = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Text(
                        accountTargetTitle(target),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"))
                    }
                },
                actions = {
                    if (target == AccountTarget.ReadingHistory && !articleList?.items.isNullOrEmpty()) {
                        IconButton(onClick = { clearHistoryConfirm = true }, enabled = !historyActionBusy) {
                            Icon(Icons.Filled.Delete, contentDescription = nativePhrase("清空阅读历史", "Clear reading history"), tint = Coral)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (searchable) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(100) },
                    modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                    singleLine = true,
                    label = { Text(nativePhrase("搜索文章", "Search articles"), fontSize = 12.sp) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }, enabled = !loading && !loadingMore) {
                                Icon(Icons.Filled.Close, contentDescription = nativePhrase("清除搜索", "Clear search"), tint = TextMuted)
                            }
                        }
                    },
                    colors = loginFieldColors(),
                )
            }
        }
        if (!notice.isNullOrBlank()) {
            item {
                Text(notice!!, color = Accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp))
            }
        }
        when {
            loading -> item { EmptyState(nativePhrase("正在读取个人内容…", "Loading personal content...")) }
            !error.isNullOrBlank() -> item { EmptyState(error!!) }
            articleList?.items.isNullOrEmpty() -> item {
                EmptyState(accountTargetEmptyState(target))
            }
            else -> {
                item {
                    Text(
                        nativePhrase("共 ${articleList?.total ?: 0} 篇", "${articleList?.total ?: 0} articles"),
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                }
                item {
                    GlassCard(
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                        shape = HlovetUi.cardShape,
                    ) {
                        Column {
                            val currentItems = articleList!!.items
                            currentItems.forEachIndexed { index, article ->
                                ListItem(
                                    headlineContent = { Text(article.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = {
                                        Text(
                                            if (target == AccountTarget.ReadingHistory) {
                                                listOfNotNull(
                                                    nativePhrase("已读 ${article.readingProgress ?: 1}%", "${article.readingProgress ?: 1}% read"),
                                                    article.lastReadAt?.takeIf { it.isNotBlank() }?.let {
                                                        nativePhrase("上次阅读 ${formatNativeTime(it)}", "Last read ${formatNativeTime(it)}")
                                                    },
                                                ).joinToString(" · ")
                                            } else {
                                                listOfNotNull(
                                                    article.category.takeIf { it.isNotBlank() },
                                                    nativePhrase("阅读 ${article.viewCount}", "${article.viewCount} views"),
                                                    nativePhrase("评论 ${article.commentCount}", "${article.commentCount} comments"),
                                                ).joinToString(" · ")
                                            },
                                            color = TextMuted,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingContent = { Icon(Icons.Filled.LibraryBooks, contentDescription = null, tint = Accent) },
                                    trailingContent = {
                                        if (target == AccountTarget.ReadingHistory) {
                                            IconButton(onClick = { removeHistory(article) }, enabled = !historyActionBusy) {
                                                Icon(Icons.Filled.Delete, contentDescription = nativePhrase("移除阅读记录", "Remove from reading history"), tint = Coral, modifier = Modifier.size(18.dp))
                                            }
                                        } else {
                                            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextMuted)
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.clickable { onArticleClick(article) },
                                )
                                if (index < currentItems.lastIndex) HorizontalDivider(color = HlovetUi.divider, modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
                if (searchable && articleList!!.page < articleList!!.totalPages) {
                    item {
                        TextButton(
                            onClick = ::loadMoreArticles,
                            enabled = !loading && !loadingMore && !historyActionBusy,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        ) {
                            Text(
                                if (loadingMore) {
                                    nativePhrase("加载中…", "Loading...")
                                } else {
                                    nativePhrase(
                                        "加载更多（${articleList!!.page}/${articleList!!.totalPages}）",
                                        "Load more (${articleList!!.page}/${articleList!!.totalPages})",
                                    )
                                },
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }

    if (clearHistoryConfirm) {
        HlovetAlertDialog(
            onDismissRequest = { clearHistoryConfirm = false },
            title = { Text(nativePhrase("清空阅读历史", "Clear reading history")) },
            text = { Text(nativePhrase("确定清空全部阅读历史吗？收藏、点赞和稍后阅读不会受到影响。", "Clear all reading history? Favorites, likes, and Read later will not be affected.")) },
            confirmButton = {
                TextButton(onClick = ::clearHistory, enabled = !historyActionBusy) { Text(nativePhrase("清空", "Clear"), color = Coral) }
            },
            dismissButton = { TextButton(onClick = { clearHistoryConfirm = false }) { Text(nativePhrase("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun ProfileGrowthCard(
    summary: NativeReputationSummary?,
    loading: Boolean,
    error: String?,
    onOpenPoints: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().clickable(enabled = summary != null, onClick = onOpenPoints),
        shape = HlovetUi.cardShape,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(nativePhrase("成长与积分", "Growth and points"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(nativePhrase("查看成长等级、经验和积分", "View your level, experience, and points"), color = TextMuted, fontSize = 10.sp)
                }
                TextButton(onClick = onOpenPoints, enabled = summary != null) {
                    Text(nativePhrase("积分明细", "Points activity"), fontSize = 11.sp)
                }
            }
            when {
                loading -> Text(nativePhrase("正在读取成长数据…", "Loading growth data..."), color = TextMuted, fontSize = 11.sp)
                !error.isNullOrBlank() -> Text(error, color = Coral, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                summary != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Lv.${summary.level.level} ${summary.level.name}", color = Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(nativePhrase("经验 ${summary.experience}", "${summary.experience} XP"), color = TextMuted, fontSize = 10.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(summary.points.toString(), color = Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(nativePhrase("当前积分", "Current points"), color = TextMuted, fontSize = 10.sp)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(7.dp).background(HlovetUi.divider, HlovetUi.rowShape)) {
                        Box(
                            Modifier
                                .fillMaxWidth(summary.progressPercent.coerceIn(0, 100) / 100f)
                                .height(7.dp)
                                .background(Accent, HlovetUi.rowShape),
                        )
                    }
                    Text(
                        summary.nextLevel?.let {
                            nativePhrase(
                                "距离 ${it.name} 还需 ${summary.experienceToNext} 点经验",
                                "${summary.experienceToNext} XP to ${it.name}",
                            )
                        } ?: nativePhrase("已达到当前最高成长等级", "You have reached the highest current level"),
                        color = TextMuted,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileStatsCard(profile: NativeProfileSummary) {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
        colors = CardDefaults.cardColors(containerColor = SurfaceAccent),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 15.dp)) {
            Text(nativePhrase("公开资料", "Public profile"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ProfileStat(nativePhrase("文章", "Articles"), profile.publicArticleCount)
                ProfileStat(nativePhrase("获赞", "Likes"), profile.receivedLikeCount)
                ProfileStat(nativePhrase("阅读", "Views"), profile.publicViewCount)
                ProfileStat(nativePhrase("订阅者", "Subscribers"), profile.subscriberCount)
            }
            if (!profile.bio.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(profile.bio, color = TextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ProfileAccountMottoCard(profile: NativeProfileSummary) {
    Card(
        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
        shape = HlovetUi.cardShape,
        colors = CardDefaults.cardColors(containerColor = SurfaceAccent),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(nativePhrase("来到 HLOVET", "Joined HLOVET"), color = TextMuted, fontSize = 10.sp)
                    Text(profile.createdAt?.take(10) ?: "—", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(nativePhrase("相伴时长", "Time with HLOVET"), color = TextMuted, fontSize = 10.sp)
                    Text(nativeMemberDuration(profile.createdAt), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(nativePhrase("给你的话", "A note for you"), color = TextMuted, fontSize = 10.sp)
                Text(nativeAccountMotto(profile.id, profile.username, profile.createdAt), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ProfileStat(label: String, value: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value?.toString() ?: "—", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Accent)
        Text(label, color = TextMuted, fontSize = 10.sp)
    }
}

/** 按 text 后两位显示统一头像，color 为头像色；large/compact 和 modifier 控制已有尺寸规格。 */
@Composable
internal fun Avatar(
    text: String,
    color: Color,
    large: Boolean = false,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // 统一在固定头像尺寸内居中绘制，供消息、评论与提及候选复用。
    val size = when {
        large -> 54.dp
        compact -> 24.dp
        else -> 40.dp
    }
    Surface(shape = CircleShape, color = color.copy(alpha = .14f), modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text.takeLast(2), color = color, fontSize = if (large) 15.sp else 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private data class ChatPreview(val name: String, val message: String, val time: String, val initials: String)

@Composable
private fun nativeNotificationBody(notification: NativeNotificationPreview): String = when {
    notification.messageDeleted || notification.commentDeleted -> nativePhrase(
        "这条被提及的消息已被删除，无法继续定位。",
        "The mentioned message was deleted and can no longer be opened.",
    )
    notification.contextKind == "group_report" -> ""
    notification.contextKind == "friend_request" -> notification.requestNote ?: notification.body
    notification.contextKind == "stranger_message_request" -> notification.requestBody ?: notification.body
    else -> notification.body
}

@Composable
private fun nativeNotificationStatus(notification: NativeNotificationPreview): String? {
    val status = notification.contextStatus?.lowercase() ?: return null
    return when (notification.contextKind) {
        "friend_request" -> when (status) {
            "accepted" -> nativePhrase("好友申请已通过", "Friend request accepted")
            "declined", "rejected" -> nativePhrase("好友申请未通过", "Friend request declined")
            else -> null
        }
        "stranger_message_request" -> when (status) {
            "accepted" -> nativePhrase("消息请求已通过", "Message request accepted")
            "declined", "rejected" -> nativePhrase("消息请求已拒绝", "Message request declined")
            else -> null
        }
        "group_invitation" -> when (status) {
            "accepted", "already_joined" -> nativePhrase("已加入群聊", "Already joined the group")
            "declined" -> nativePhrase("群聊邀请已拒绝", "Group invitation declined")
            "expired" -> nativePhrase("群聊邀请已过期", "Group invitation expired")
            else -> null
        }
        "group_join_request" -> when (status) {
            "approved" -> nativePhrase("入群申请已通过", "Join request approved")
            "rejected" -> nativePhrase("入群申请已驳回", "Join request rejected")
            else -> null
        }
        "group_report" -> when (status) {
            "resolved" -> nativePhrase("群消息举报已处理", "Group report resolved")
            "rejected" -> nativePhrase("群消息举报已驳回", "Group report rejected")
            else -> null
        }
        else -> null
    }
}

@Composable
private fun nativeNotificationMessagePreview(message: NativeChatMessage): String = when {
    message.body.isNotBlank() -> message.body
    message.attachments.any { it.kind == "image" } -> nativePhrase("图片消息", "Image message")
    message.attachments.any { it.kind == "audio" } -> nativePhrase("语音消息", "Voice message")
    message.attachments.isNotEmpty() -> nativePhrase("附件消息", "Attachment message")
    else -> nativePhrase("未包含文字内容", "No text content")
}

@Composable
private fun EmptyState(message: String) {
    GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.rowShape) {
        Text(message, color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(18.dp))
    }
}

private fun articleReadTime(article: RemoteArticle): String {
    val minutes = maxOf(1, cleanArticleText(article.content).length / 420)
    return nativePhrase("$minutes 分钟阅读", "$minutes min read")
}

private fun nativeArticleReportReasonLabel(reason: String): String = when (reason) {
    "spam" -> nativePhrase("垃圾信息", "Spam")
    "harassment" -> nativePhrase("骚扰或攻击", "Harassment or abuse")
    "illegal" -> nativePhrase("违法违规", "Illegal content")
    "privacy" -> nativePhrase("隐私泄露", "Privacy violation")
    "misinformation" -> nativePhrase("虚假信息", "Misinformation")
    else -> nativePhrase("其他", "Other")
}

private fun normalizeNativePath(rawPath: String?): String {
    val path = rawPath.orEmpty().trimEnd('/').ifBlank { "/" }
    return when {
        path == "/en" -> "/"
        path.startsWith("/en/") -> path.removePrefix("/en")
        else -> path
    }
}

internal fun nativeArticleManagementTarget(rawPath: String): AccountTarget? = when (normalizeNativePath(rawPath)) {
    "/articles/mine", "/profile/articles" -> AccountTarget.MyArticles
    "/articles/manage" -> AccountTarget.AdminArticles
    else -> null
}

private fun String.toNativeComposeColor(fallback: Color): Color = runCatching {
    Color(android.graphics.Color.parseColor(this))
}.getOrDefault(fallback)

internal fun normalizeNativeReadingTab(value: String?): String = when (value?.trim()?.lowercase()) {
    "history" -> "history"
    "favorites" -> "favorites"
    "liked" -> "liked"
    else -> "read-later"
}

internal fun normalizeNativeArticleStatus(value: String?): String? = when (value?.trim()?.lowercase()) {
    "draft", "published", "unpublished", "blocked", "deleted" -> value.trim().lowercase()
    else -> null
}

internal fun normalizeNativeSubscriptionSort(value: String?): String = when (value?.trim()?.lowercase()) {
    "unread", "popular" -> value.trim().lowercase()
    else -> "latest"
}

internal fun normalizeNativeRouteQuery(value: String?): String = value?.trim()?.take(80).orEmpty()

internal fun normalizeNativeCollectionView(value: String?): String =
    if (value?.trim()?.lowercase() == "browse") "browse" else "mine"

internal data class NativeArticleSortOption(
    val value: String,
    val label: String,
)

internal val nativeArticleSortOptions = listOf(
    NativeArticleSortOption("recommended", "综合推荐"),
    NativeArticleSortOption("latest", "最新发布"),
    NativeArticleSortOption("popular", "综合热度"),
    NativeArticleSortOption("views", "最多浏览"),
    NativeArticleSortOption("likes", "最多点赞"),
    NativeArticleSortOption("favorites", "最多收藏"),
    NativeArticleSortOption("comments", "最多评论"),
)

internal fun normalizeNativeArticleSort(value: String?): String =
    nativeArticleSortOptions.firstOrNull { it.value == value?.trim()?.lowercase() }?.value ?: "recommended"

internal fun nativeArticleSortLabel(value: String?): String =
    nativeArticleSortDisplayLabel(value)

internal fun nativeArticleSortDisplayLabel(value: String?): String = when (normalizeNativeArticleSort(value)) {
    "latest" -> nativePhrase("最新发布", "Latest")
    "popular" -> nativePhrase("综合热度", "Overall popularity")
    "views" -> nativePhrase("最多浏览", "Most viewed")
    "likes" -> nativePhrase("最多点赞", "Most liked")
    "favorites" -> nativePhrase("最多收藏", "Most saved")
    "comments" -> nativePhrase("最多评论", "Most commented")
    else -> nativePhrase("综合推荐", "Recommended")
}

internal fun nativeDiscoveryModeLabel(mode: String): String = when (mode) {
    "热门" -> nativePhrase("热门", "Popular")
    "最新" -> nativePhrase("最新", "Latest")
    "文章" -> nativePhrase("文章", "Articles")
    "专题" -> nativePhrase("专题", "Topics")
    "合集" -> nativePhrase("合集", "Collections")
    else -> nativePhrase("推荐", "Recommended")
}

internal data class NativeDiscoveryVisibility(
    val showArticles: Boolean,
    val showTopics: Boolean,
    val showCollections: Boolean,
)

internal fun nativeDiscoveryVisibility(mode: String): NativeDiscoveryVisibility = when (mode) {
    "文章", "热门", "最新" -> NativeDiscoveryVisibility(showArticles = true, showTopics = false, showCollections = false)
    "专题" -> NativeDiscoveryVisibility(showArticles = false, showTopics = true, showCollections = false)
    "合集" -> NativeDiscoveryVisibility(showArticles = false, showTopics = false, showCollections = true)
    else -> NativeDiscoveryVisibility(showArticles = true, showTopics = true, showCollections = true)
}

private fun isNativePortalHost(uri: Uri): Boolean {
    val host = uri.host?.lowercase().orEmpty()
    return host.isBlank() || host in nativePortalHostnames()
}

private fun formatNativeTime(value: String): String = when {
    value.length >= 16 -> value.substring(0, 16).replace('T', ' ')
    value.isBlank() -> ""
    else -> value
}

private fun cleanArticleText(content: String): String = content
    .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
    .replace(Regex("<[^>]+>"), "")
    .replace(Regex("[`*_>#]"), "")
    .replace(Regex("\\n{3,}"), "\\n\\n")
    .trim()

/** 从 context 读取 uri 指向的合集封面；10 MB 上限在流读取过程中生效，返回原文件信息。 */
private fun readCollectionCoverFile(context: Context, uri: Uri): NativeUploadFile {
    // 与其它附件共用有界读取器，拒绝空文件及未知长度的超限内容。
    return readNativeUploadFile(context, uri, 10L * 1024L * 1024L)
}

private fun readVoiceUploadFile(file: java.io.File): NativeUploadFile {
    if (!file.exists() || !file.isFile) throw IllegalStateException(nativePhrase("录音文件不存在，请重新录制。", "The recording file is missing. Record it again."))
    val maxBytes = 20L * 1024L * 1024L
    if (file.length() > maxBytes) throw IllegalStateException(nativePhrase("语音消息不能超过 20 MB。", "A voice message cannot exceed 20 MB."))
    val bytes = file.readBytes()
    if (bytes.isEmpty()) throw IllegalStateException(nativePhrase("没有录到有效的语音内容。", "No usable audio was recorded."))
    return NativeUploadFile(file.name, "audio/mp4", bytes)
}

private fun formatNativeAttachmentSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt()
)
