# HLOVET 原生移动端预览

这是一个独立的 Android 原生 UI 预览模块，不会替换现有的 TWA 应用，也不会连接生产数据。

## 当前包含

- 首页推荐与今日灵感入口
- 发现、搜索入口、推荐专题和合集
- 写作入口与最近草稿
- 消息、未读提醒和聊天列表
- 我的、内容管理、账号隐私和 AI 助手入口
- 底部五栏导航和 HLOVET 移动端颜色、间距、卡片、头像规范
- 采用开源 Material 3 + Jetcaster 内容流作为视觉基线，统一浅色主题、卡片、列表和导航规范
- 复用 PC 端的背景图和玻璃参数：背景图、浅色 wash、22dp 模糊、半透明白色面板和统一无边框芯片
- 首页推荐文章、发现中的专题/合集支持进入玻璃风格详情页，并提供返回路径
- 默认读取生产环境公开文章、专题和合集，包含加载中、空数据、网络错误和重试状态

当前页面仍是独立原生预览，不会替换现有 TWA，也不会读取或修改用户数据。登录、离线缓存、推送和 AI 能力会在后续原生模块阶段接入。

默认 API 地址为 `https://5200918.xyz/api`。如需切换到本地或测试 API，可以在构建时覆盖：

```powershell
..\gradlew.bat :prototype:assembleDebug -PpreviewApiBaseUrl=http://10.0.2.2:3001
```

## 构建

```powershell
$env:JAVA_HOME='D:\app\android-build\jdk17'
$env:ANDROID_HOME='D:\app\android-build\android-sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:PATH="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:PATH"
..\gradlew.bat :prototype:assembleDebug
```

APK 输出位置：

```text
apps/android/prototype/build/outputs/apk/debug/prototype-debug.apk
```

本次预览副本：

```text
output/android/hlovet-native-preview.apk
```
