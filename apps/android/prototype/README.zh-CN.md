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
- 原生登录支持账号密码、邮箱设备验证、TOTP、Passkey 和 Google OAuth；登录成功后同步用户外观配置

当前页面仍是独立原生预览，不会替换现有 TWA。登录不会保存密码，访问令牌只保留在当前运行内存中；Passkey 依赖 Android Credential Manager 和 Google Play services，Google 登录依赖系统浏览器完成授权。

默认 API 地址为 `https://5200918.xyz/api`。如需切换到本地或测试 API，可以在构建时覆盖：

```powershell
..\gradlew.bat :prototype:assembleDebug -PpreviewApiBaseUrl=http://10.0.2.2:3001
```

当前调试 APK 的签名指纹为：

```text
C2:F8:64:5B:63:DC:DF:A6:25:11:BE:3B:92:5A:93:ED:AC:4D:57:75:CA:66:34:61:DC:0B:A6:97:22:D9:06:45
```

生产环境需要将它配置到 `ANDROID_APP_SHA256_CERT_FINGERPRINT` 和 `PASSKEY_ANDROID_ORIGINS`；正式签名 APK 发布后，应替换为正式签名证书指纹。

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
