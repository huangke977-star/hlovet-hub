@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class NativeAdminEntry(
    val path: String,
    val titleZh: String,
    val titleEn: String,
    val descriptionZh: String,
    val descriptionEn: String,
    val icon: ImageVector,
    val superAdminOnly: Boolean = false,
    val implemented: Boolean = false,
)

private fun NativeAdminEntry.title(): String = nativePhrase(titleZh, titleEn)
private fun NativeAdminEntry.description(): String = nativePhrase(descriptionZh, descriptionEn)

private val nativeAdminEntries = listOf(
    NativeAdminEntry("/admin/articles", "文章管理", "Article management", "审核文章、评论、置顶和可见范围。", "Review articles, comments, pins, and visibility.", Icons.Filled.Article, implemented = true),
    NativeAdminEntry("/admin/analytics", "运营数据", "Analytics", "查看用户、内容、消息、资源和风险统计。", "Review user, content, message, resource, and risk metrics.", Icons.Filled.Info, implemented = true),
    NativeAdminEntry("/admin/audit", "审计日志", "Audit log", "筛选关键管理操作，展开查看脱敏详情。", "Filter management actions and inspect redacted details.", Icons.Filled.LibraryBooks, implemented = true),
    NativeAdminEntry("/admin/system", "系统概况", "System overview", "查看应用、数据库、Redis、备份和存储状态。", "Review application, database, Redis, backup, and storage status.", Icons.Filled.Settings, superAdminOnly = true, implemented = true),
    NativeAdminEntry("/admin/system/backups", "备份管理", "Backup management", "维护备份策略、数据库归档、媒体任务和配置包。", "Maintain backup policies, database archives, media jobs, and configuration bundles.", Icons.Filled.Storage, superAdminOnly = true, implemented = true),
    NativeAdminEntry("/admin/system/operations", "运营与质量", "Operations and quality", "执行 P21 运营检查和 P22 生产质量检查。", "Run P21 operations checks and P22 production quality checks.", Icons.Filled.Build, superAdminOnly = true, implemented = true),
    NativeAdminEntry("/admin/storage", "存储管理", "Storage management", "扫描异常文件、修复缺失文件并维护回收站。", "Scan anomalies, repair missing files, and maintain the recycle bin.", Icons.Filled.Storage, superAdminOnly = true, implemented = true),
    NativeAdminEntry("/admin/topics", "专题管理", "Topic management", "管理专题属性、可见范围和文章排序。", "Manage topic properties, visibility, and article order.", Icons.Filled.LibraryBooks, implemented = true),
    NativeAdminEntry("/admin/users", "用户管理", "User management", "账号、角色、状态和安全处置。", "Manage accounts, roles, status, and security actions.", Icons.Filled.PersonOutline, implemented = true),
    NativeAdminEntry("/admin/reports", "举报中心", "Reports", "处理文章、评论和群消息举报。", "Process article, comment, and group-message reports.", Icons.Filled.Flag, implemented = true),
    NativeAdminEntry("/admin/content", "内容管理", "Portal content", "管理导航、工具和站内门户内容。", "Manage navigation, tools, and portal content.", Icons.Filled.Report, implemented = true),
    NativeAdminEntry("/admin/violations", "违规作者", "Restricted authors", "查看违规作者及相关记录。", "Review restricted authors and related records.", Icons.Filled.Warning, implemented = true),
    NativeAdminEntry("/admin/feedback", "用户反馈管理", "User feedback", "集中处理用户提交的反馈。", "Process feedback submitted by users.", Icons.Filled.Info, implemented = true),
    NativeAdminEntry("/admin/groups", "群聊管理", "Group management", "管理群聊和群聊举报。", "Manage groups and group reports.", Icons.Filled.Groups, implemented = true),
    NativeAdminEntry("/admin/voices", "匿名话题管理", "Anonymous topics", "维护匿名话题和互动内容。", "Maintain anonymous topics and interactions.", Icons.Filled.Groups, implemented = true),
    NativeAdminEntry("/admin/resources", "资源与积分", "Resources and points", "管理资源交付和积分账本。", "Manage resource delivery and the points ledger.", Icons.Filled.Storage, implemented = true),
    NativeAdminEntry("/admin/announcements", "公告管理", "Announcements", "发布和管理站点公告。", "Publish and manage site announcements.", Icons.Filled.Info, implemented = true),
    NativeAdminEntry("/admin/security", "安全管理", "Security", "管理邮件、验证码和登录安全策略。", "Manage email, verification, and sign-in security policies.", Icons.Filled.Shield, implemented = true),
    NativeAdminEntry("/admin/deleted-users", "已注销账号", "Deleted accounts", "查看注销账号的内容归属。", "Review content ownership for deleted accounts.", Icons.Filled.Delete, implemented = true),
    NativeAdminEntry("/admin/integrations", "外部集成", "External integrations", "管理 Google 登录、Webhook 和投递记录。", "Manage Google sign-in, webhooks, and delivery records.", Icons.Filled.Cloud, superAdminOnly = true, implemented = true),
    NativeAdminEntry("/admin/ai", "AI 配置", "AI configuration", "配置供应商、模型、能力和资源限制。", "Configure providers, models, capabilities, and resource limits.", Icons.Filled.AutoAwesome, superAdminOnly = true, implemented = true),
    NativeAdminEntry("/admin/settings", "站点设置", "Site settings", "配置站点身份、外观、资源和内容规则。", "Configure site identity, appearance, resources, and content rules.", Icons.Filled.Settings, superAdminOnly = true, implemented = true),
    NativeAdminEntry("/admin/android", "安装包管理", "App releases", "管理移动端安装包版本。", "Manage mobile app releases.", Icons.Filled.Download, superAdminOnly = true, implemented = true),
    NativeAdminEntry("/admin/cache", "缓存管理", "Cache management", "查看和维护 Redis 缓存。", "Review and maintain Redis cache.", Icons.Filled.Refresh, superAdminOnly = true, implemented = true),
)

internal fun implementedNativeAdminPaths(): List<String> = nativeAdminEntries.filter { it.implemented }.map { it.path }

internal fun nativeAdminPathsFor(isAdministrator: Boolean, isSuperAdmin: Boolean): List<String> =
    if (isAdministrator || isSuperAdmin) {
        nativeAdminEntries.filter { isSuperAdmin || !it.superAdminOnly }.map { it.path }
    } else {
        emptyList()
    }

@Composable
internal fun NativeAdminHomeScreen(
    session: NativeSession?,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    var notice by remember { mutableStateOf<String?>(null) }
    val entries = nativeAdminPathsFor(session?.isAdministrator == true, session?.isSuperAdmin == true)
        .mapNotNull { path -> nativeAdminEntries.firstOrNull { it.path == path } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TopAppBar(
                title = {
                    Column {
                        Text(nativePhrase("后台管理", "Administration"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(nativePhrase("按权限进入站点管理功能", "Access site management by permission"), color = HlovetUi.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        if (!notice.isNullOrBlank()) item { Text(notice!!, color = HlovetUi.secondaryAccent, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 22.dp)) }
        if (session == null) {
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(30.dp))
                        Text(nativePhrase("登录后查看后台管理", "Sign in to view administration"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Text(nativePhrase("管理入口会按当前账号权限显示。", "Available entries depend on your account permissions."), color = HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        TextButton(onClick = onLogin) { Text(nativePhrase("登录", "Sign in"), fontSize = 12.sp) }
                    }
                }
            }
        } else if (!session.isAdministrator && !session.isSuperAdmin) {
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = HlovetUi.secondaryAccent, modifier = Modifier.size(30.dp))
                        Text(nativePhrase("当前账号没有后台管理权限", "This account has no administration access"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                        Text(nativePhrase("请使用站点管理员账号访问。", "Use a site administrator account to continue."), color = HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        } else {
            item {
                GlassCard(modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = HlovetUi.cardShape) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(session.nickname.ifBlank { session.username }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(if (session.isSuperAdmin) nativePhrase("超级管理员", "Super administrator") else nativePhrase("管理员", "Administrator"), color = HlovetUi.muted, fontSize = 10.sp)
                        }
                        Text(nativePhrase("${entries.size} 个入口", "${entries.size} entries"), color = HlovetUi.muted, fontSize = 10.sp)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    entries.forEach { entry ->
                        GlassCard(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (entry.implemented) onOpenEntry(entry.path) else notice = nativePhrase("“${entry.title()}”的 Android 管理页面还在移植中，PC 端功能不受影响。", "The Android administration page for “${entry.title()}” is still being migrated; the PC feature is unaffected.")
                            },
                            shape = HlovetUi.rowShape,
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(entry.icon, contentDescription = null, tint = if (entry.implemented) HlovetUi.accent else HlovetUi.muted, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(9.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(entry.title(), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        if (entry.superAdminOnly) {
                                            Text(nativePhrase("超级管理员", "Super admin"), color = HlovetUi.secondaryAccent, fontSize = 9.sp, modifier = Modifier.padding(start = 6.dp))
                                        }
                                    }
                                    Text(entry.description(), color = HlovetUi.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                                }
                                Text(if (entry.implemented) nativePhrase("已接入", "Available") else nativePhrase("待移植", "Pending"), color = if (entry.implemented) HlovetUi.accent else HlovetUi.muted, fontSize = 9.sp)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = HlovetUi.muted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
