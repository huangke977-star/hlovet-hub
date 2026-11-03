# Android 原生端功能覆盖矩阵

本文记录 PC 端 `apps/web/src/app` 与 Android 原生端 `apps/android/prototype` 的入口对应关系，作为移植验收清单。状态只描述当前源码和已有验证证据，不把“能编译”当成真实业务联调通过。

## 状态说明

- **已接入**：Android 已有对应原生路由和页面组件。
- **已验证**：除已接入外，已有单元测试、设备测试、本地真实 API 测试或本轮模拟器检查证据。
- **需回归**：代码已接入，但需要使用线上账号或特定权限、外部服务继续验证。
- **未完成**：PC 有功能，而 Android 当前没有等价入口或行为。

## 普通用户入口

| PC 入口 | Android 原生入口 | 状态 | 验证边界 |
| --- | --- | --- | --- |
| `/` | `DiscoverScreen` | 已验证 | 默认发现页；本轮确认搜索/筛选同排、文章流和懒加载 |
| `/articles` | `DiscoverScreen` | 已验证 | 文章、热门、最新、推荐由筛选弹窗切换 |
| `/topics` | `DiscoverScreen` 的专题模式 | 已接入 | 普通用户浏览；管理员进入专题管理 |
| `/collections` | `DiscoverScreen` 的合集模式 | 已接入 | 公开合集浏览 |
| `/articles/[slug]` | `DiscoveryDetailScreen` / 文章详情 | 已验证 | 包含评论、附件、离线回退和定位参数 |
| `/topics/[slug]` | `DetailTarget.Topic` | 已接入 | 详情、订阅、离线保存 |
| `/collections/[id]` | `DetailTarget.Collection` | 已接入 | 详情、订阅、离线保存 |
| `/articles/subscriptions` | `SubscriptionFeedScreen` | 已接入 | 订阅动态、排序、订阅管理和日报开关 |
| `/articles/resources` | `ResourceCenterScreen` | 已验证 | 资源浏览和兑换入口 |
| `/articles/resources/orders` | `ResourceOrdersScreen` | 已接入 | 兑换记录 |
| `/articles/resources/earnings` | `ResourceEarningsScreen` | 已接入 | 资源收益 |
| `/articles/mine` | `MyArticlesManagementScreen` | 已验证 | 草稿、发布、下架、受限、回收站、搜索和分页 |
| `/articles/manage` | `NativeAdminArticlesScreen` | 已接入 | 管理员文章/评论审核入口 |
| `/articles/write` | `NativeWriteScreen` | 已验证 | 编辑器、附件、任务列表、代码块、预览和草稿 |
| `/articles/edit/[id]` | `NativeWriteScreen` | 已接入 | 编辑已有文章 |
| `/articles/preview/[id]` | `NativeAdminArticlePreviewScreen` | 已接入 | 文章预览 |
| `/articles/favorites` | `ArticleListScreen` 收藏模式 | 已接入 | 搜索和分页 |
| `/articles/liked` | `ArticleListScreen` 点赞模式 | 已接入 | 搜索和分页 |
| `/articles/reading` | `ArticleListScreen` 阅读模式 | 已接入 | `tab=favorites/liked/history/read-later` |
| `/articles/history` | `ArticleListScreen` 历史模式 | 已接入 | 搜索、清除历史和分页 |
| `/articles/templates` | `NativeTemplateManagementScreen` | 已接入 | 模板管理和写作器联动 |
| `/offline` | `NativeOfflineScreen` | 已验证 | 离线文章、专题、合集和媒体缓存入口 |
| `/voices` | `NativeAnonymousTopicsScreen` | 已验证 | 搜索、筛选、创建、互动和自动加载 |
| `/announcements` | `NativePortalScreen` 公告模式 | 已接入 | 公告列表 |
| `/announcements/[id]` | `NativePortalScreen` 公告详情 | 已接入 | 深链打开指定公告 |
| `/feedback` | `NativePortalScreen` 反馈模式 | 已接入 | 提交和查看反馈 |
| `/suggestions` | `NativePortalScreen` 建议模式 | 已接入 | 提交和查看建议 |
| `/nav` | `NativePortalScreen` 导航模式 | 已接入 | 站内导航 |
| `/tools` | `NativePortalScreen` 工具模式 | 已接入 | 搜索和打开工具 |
| `/search` | `NativeSearchScreen` | 已接入 | 全站搜索和历史 |
| `/messages` | `NativeCommunityScreen` / `NativeGroupScreen` | 已验证 | 私聊、好友、群聊、附件、通知和通话入口 |
| `/dashboard` | `NativeDashboardScreen` | 已接入 | 个人工作台 |
| `/profile` | 原生个人中心 | 已验证 | 资料、内容中心和安全入口 |
| `/profile/edit` | `NativeProfileEditorScreen` | 已接入 | 资料编辑 |
| `/profile/articles` | `MyArticlesManagementScreen` | 已接入 | 兼容 PC 旧入口，保持跳转语义 |
| `/profile/integrations` | `NativeIntegrationsScreen` | 已验证 | 外部通知通道和 Google 关联入口 |
| `/profile/points` | `PointsScreen` | 已接入 | 积分明细 |
| `/profile/privacy` | `NativePrivacyScreen` | 已验证 | 凭据、二次验证、注销、导出和屏蔽 |
| `/profile/recommendations` | `NativeRecommendationFeedbackScreen` | 已接入 | 推荐反馈 |
| `/profile/reports` | `NativeReportsScreen` | 已接入 | 我的举报 |
| `/users/[username]` | `NativePublicProfileScreen` | 已验证 | 公开资料、文章、合集、订阅、好友、私信和屏蔽 |
| `/install` | `NativeInstallScreen` | 已验证 | 原生包/PWA 安装和版本信息 |
| `/ai` | `NativeAiScreen` | 已接入 | 对话、站内检索、草稿和工具入口；实际模型能力需按配置回归 |
| `/login` | `NativeLoginStep` | 已验证 | 密码、邮箱、TOTP、Passkey、Google、信任设备和新设备流程 |
| `/register` | `NativeLoginStep.Register` | 已接入 | 注册策略由服务端控制 |
| `/forgot-password` | `NativeLoginStep.RecoveryRequest` | 已接入 | 找回密码 |
| `/unsubscribe/[token]` | `NativeUnsubscribeScreen` | 已接入 | 邮件订阅退订 |
| `/native-auth/google` | OAuth 回调状态，不渲染独立页面 | 已接入 | 回调由 Activity intent 和登录/集成流程消费 |

## 管理员入口

后台入口由 `NativeAdminHomeScreen` 的统一清单声明，路由由 `nativeAdminAccountTarget` 分发。当前清单覆盖：

`/admin/articles`、`/admin/analytics`、`/admin/audit`、`/admin/system`、`/admin/system/backups`、`/admin/system/operations`、`/admin/storage`、`/admin/topics`、`/admin/users`、`/admin/reports`、`/admin/content`、`/admin/violations`、`/admin/feedback`、`/admin/groups`、`/admin/voices`、`/admin/resources`、`/admin/announcements`、`/admin/security`、`/admin/deleted-users`、`/admin/integrations`、`/admin/ai`、`/admin/settings`、`/admin/backgrounds`、`/admin/android`、`/admin/cache`。

`NativeAdminRouteTest` 会检查：

1. 首页声明的每个入口都有原生目标。
2. 管理员和超级管理员的权限过滤没有越权入口。
3. `/admin/backgrounds` 与 `/admin/settings` 共用站点设置页面。
4. 本地化路径、尾部斜杠和 P21/P22 子模块不会丢失路由。

## 本轮重点验收证据

- `5e8c12b feat(android): streamline discover and anonymous feeds` 已推送到 `origin/main`。
- 发现页：首行搜索框与筛选图标同排；下方文章流占据主滚动区域；接近底部自动追加下一页。
- 匿名页：首行搜索框与筛选图标同排；下方匿名话题连续列表；接近底部自动追加下一页。
- `:prototype:testDebugUnitTest`、`:prototype:assembleDebug`、`:prototype:assembleDebugAndroidTest` 已通过。
- `emulator-5562` 已安装当前 Debug APK，并通过 UI 层级检查发现页和匿名页的搜索/筛选入口及列表区域。

## 仍需专项回归

这些项目不是缺少页面，而是需要特定外部条件或高风险真实数据才能完成闭环：

- Google OAuth、Passkey：需要真实设备/Play services 和可用 OAuth 配置。
- WebRTC 音视频通话：需要双设备、摄像头/麦克风权限和稳定网络。
- 邮件、Webhook、外部通知：需要对应服务端凭据和投递目标。
- AI、OCR、语音转文字、图片生成、RAG：需要后台配置可用供应商和模型。
- OSS/R2 媒体备份：需要对象存储凭据；未配置时只能验证降级状态。
- 管理后台涉及生产数据的写操作：继续使用本地容器或明确授权的测试数据验证。

每完成一项专项回归，应在本表更新状态和证据，并随代码提交一起推送。
