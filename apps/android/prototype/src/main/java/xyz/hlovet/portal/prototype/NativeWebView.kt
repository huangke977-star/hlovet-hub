package xyz.hlovet.portal.prototype

import android.content.Context
import android.webkit.WebView

/**
 * 按实际页面需求创建 WebView；context 必须来自主线程上的页面或 Activity。
 * factory 可提供特定输入行为；返回未配置业务内容的视图，仅调试构建开放检查，不提前初始化浏览器引擎。
 */
internal fun nativeCreateWebView(context: Context, factory: (Context) -> WebView = ::WebView): WebView {
    // 浏览器引擎的初始化留到实际创建视图时，避免调试设置提前拖慢首页启动。
    val view = factory(context)
    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    // 文件、网络、脚本及账号权限仍由各业务视图按自己的边界配置。
    return view
}
