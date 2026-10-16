package xyz.hlovet.portal.prototype

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.view.ActionMode
import android.view.DragEvent
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.webkit.WebView

/** 从 clip 提取去重后的系统内容 URI；只兼容文件同步服务降级为文本后的 content URI。 */
internal fun nativeEditorContentUris(clip: ClipData?): List<Uri> {
    // 授权来自 Android 剪贴板或拖放，读取和批量大小限制仍由共享上传流程执行；纯文本只有结构合法时才进入候选。
    return buildList {
        for (index in 0 until (clip?.itemCount ?: 0)) {
            val item = clip?.getItemAt(index)
            // 部分桌面/模拟器剪贴板同步会把 URI 降级成 text/plain，保留该地址才能继续走原有权限校验。
            val uri = item?.uri ?: item?.intent?.data ?: nativeEditorContentUriFromText(item?.text)
            if (uri?.scheme == "content") add(uri)
        }
    }.distinctBy(Uri::toString)
}

/** 从同步服务产生的文本候选中提取 content URI；普通正文、网页地址和本地路径均返回 null。 */
internal fun nativeEditorContentUriFromText(value: CharSequence?): Uri? {
    // 结构校验与 Android Uri 构造分开，避免 JVM 单元测试依赖 Android 框架影子实现。
    val text = nativeEditorContentUriText(value) ?: return null
    // 正则已经确认是 content scheme；这里只创建地址对象，不读取、不授予任何权限。
    return runCatching { Uri.parse(text) }.getOrNull()
}

/** 规范化同步服务给出的文本候选；返回值仅是地址字符串，不代表已取得读取权限。 */
internal fun nativeEditorContentUriText(value: CharSequence?): String? {
    // 先限制长度和空白，避免把多行正文或带描述的剪贴板内容误判为文件地址。
    val text = value?.toString()?.trim().orEmpty()
    if (text.isBlank() || text.length > 2048 || text.any(Char::isWhitespace)) return null
    // 先用地址结构确认 authority 存在，避免 JVM 影子 Uri 与 Android 实机的 authority 行为不同。
    if (!Regex("^content://[^/?#]+(?:[/?#].*)?$", RegexOption.IGNORE_CASE).matches(text)) return null
    return text
}

/**
 * 使用页面 context 衔接系统文件粘贴与编辑器上传，普通文字及 HTML 继续走浏览器原有净化流程。
 * onSystemFiles 接收已授权 URI 并返回是否消费；onRejected 仅向当前页面反馈读取失败，不记录剪贴板内容。
 */
internal class NativeEditorSystemWebView(
    context: Context,
    private val onSystemFiles: (List<Uri>) -> Boolean,
    private val onRejected: (String) -> Unit,
) : WebView(context) {
    /** event 的文件拖放由外层 Compose 单独分发，禁止浏览器先吞掉位置或落下事件。 */
    override fun dispatchDragEvent(event: DragEvent): Boolean {
        // AndroidView 中的原生浏览器也会接受文本 URI 拖动，拒绝其内层分发才能让统一上传目标处理。
        return false
    }

    /** 响应真实系统粘贴操作，返回是否消费文件；未含内容 URI 时交回浏览器。 */
    private fun pasteSystemFiles(): Boolean {
        // 仅在用户主动粘贴且窗口有焦点时读取剪贴板，不监听或后台采集剪贴板。
        if (!hasWindowFocus()) return false
        val uris = try {
            nativeEditorContentUris(context.getSystemService(ClipboardManager::class.java).primaryClip)
        } catch (_: SecurityException) {
            // 明确反馈无法读取，不把私有 URI 当作正文插入。
            onRejected(nativePhrase("无法读取剪贴板文件，请重新复制后粘贴。", "Cannot read the clipboard file. Copy it again and retry."))
            return true
        }
        // 实际字节在共享上传的 IO 线程中读取，主线程只交付系统地址。
        return uris.isNotEmpty() && onSystemFiles(uris)
    }

    /** 接收 event 的真实键盘粘贴快捷键；不改写文字输入、选择或其它快捷键。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 在 Chromium 将内容 URI 忽略前接管文件，普通 Ctrl+V 保持系统默认行为。
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_V &&
            event.isCtrlPressed && !event.isAltPressed && pasteSystemFiles()) return true
        return super.dispatchKeyEvent(event)
    }

    /** 接收 keyCode/event 对应的系统快捷键分发，覆盖 Android 不经普通按键入口的 Ctrl+V。 */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        // Activity 会把部分组合键独立分发为 shortcut，不能只覆盖 dispatchKeyEvent。
        if (keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed && !event.isAltPressed && pasteSystemFiles()) return true
        return super.onKeyShortcut(keyCode, event)
    }

    /** 判断 item 是否为系统粘贴，兼容 Chromium 自有菜单 ID，不拦截其它命令。 */
    private fun isPasteItem(item: MenuItem): Boolean {
        // 标准标题来自当前 Android 语言，不依赖固定英文或第三方资源 ID。
        return item.itemId in setOf(android.R.id.paste, android.R.id.pasteAsPlainText) ||
            item.title?.toString() == context.getString(android.R.string.paste)
    }

    /** 在文件剪贴板场景保留单一粘贴入口，避免 Android 15 同时展示两项同名操作。 */
    private fun keepSinglePasteItem(menu: Menu) {
        // Chromium 可能同时提供 paste 与 pasteAsPlainText，文件上传不需要两个等价入口。
        val pasteItems = (0 until menu.size()).map { menu.getItem(it) }.filter(::isPasteItem)
        if (pasteItems.size <= 1) return
        // 优先保留标准 paste；若浏览器只提供了其它粘贴项，则保留菜单中的第一项。
        val keep = pasteItems.firstOrNull { it.itemId == android.R.id.paste } ?: pasteItems.first()
        pasteItems.filterNot { it === keep }.forEach { menu.removeItem(it.itemId) }
    }

    /** 为 outAttrs 保留浏览器输入连接，只接管输入法发出的实际粘贴命令。 */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        // 软键盘的粘贴命令不一定生成按键，仍需通过同一内容 URI 入口处理。
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(connection, false) {
            /** 对 id 指定的系统粘贴先尝试文件，未消费时交回原输入连接。 */
            override fun performContextMenuAction(id: Int): Boolean {
                // 不截获复制、剪切、全选等其它编辑行为。
                if (id in setOf(android.R.id.paste, android.R.id.pasteAsPlainText) && pasteSystemFiles()) return true
                return super.performContextMenuAction(id)
            }
        }
    }

    /** 为 callback/type 对应的真实选择菜单保留浏览器动作，并补齐内容 URI 的粘贴入口。 */
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        // 菜单与浏览器共用同一选区，不能额外创建失去光标的原生弹窗。
        return super.startActionMode(PasteActionCallback(callback), type)
    }

    /** 转发浏览器选择菜单生命周期，只在用户实际点下粘贴时消费系统文件。 */
    private inner class PasteActionCallback(private val delegate: ActionMode.Callback) : ActionMode.Callback2() {
        /** 创建 mode/menu 时保留原菜单；剪贴板有文件时保证用户可以找到标准粘贴项。 */
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            // 浏览器可能因文件没有 text/html 而省略粘贴，原生入口需补充该标准动作。
            val created = delegate.onCreateActionMode(mode, menu)
            val hasFiles = try {
                hasWindowFocus() && nativeEditorContentUris(context.getSystemService(ClipboardManager::class.java).primaryClip).isNotEmpty()
            } catch (_: SecurityException) { false }
            if (hasFiles) {
                // 先合并浏览器已提供的同名项，再补齐完全缺失的系统粘贴入口。
                keepSinglePasteItem(menu)
                if ((0 until menu.size()).none { isPasteItem(menu.getItem(it)) }) {
                    menu.add(Menu.NONE, android.R.id.paste, Menu.NONE, android.R.string.paste)
                }
            }
            return created || menu.size() > 0
        }

        /** 准备 mode/menu 时沿用浏览器可用性及布局。 */
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            // 保留浏览器对当前选区的动态操作状态。
            val prepared = delegate.onPrepareActionMode(mode, menu)
            // 系统可能在 prepare 阶段重新插入纯文本粘贴项，文件入口仍只能保留一项。
            val hasFiles = try {
                hasWindowFocus() && nativeEditorContentUris(context.getSystemService(ClipboardManager::class.java).primaryClip).isNotEmpty()
            } catch (_: SecurityException) { false }
            if (hasFiles) keepSinglePasteItem(menu)
            return prepared
        }

        /** 消费 mode/item 中真正的粘贴点击；其余菜单操作完整转发。 */
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            // 文件处理后关闭选择菜单，但上传不改变原正文及插入点。
            if (isPasteItem(item) && pasteSystemFiles()) {
                mode.finish()
                return true
            }
            return delegate.onActionItemClicked(mode, item)
        }

        /** 销毁 mode 时通知浏览器恢复其原选区状态。 */
        override fun onDestroyActionMode(mode: ActionMode) {
            // 不保留系统选择菜单或其 Activity 引用。
            delegate.onDestroyActionMode(mode)
        }

        /** 使用 mode/view 对应选区填充 outRect，避免浮动菜单被错误定位到屏幕边缘。 */
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            // Callback2 的选区坐标由 Chromium 计算，不能替换成整个编辑器区域。
            if (delegate is ActionMode.Callback2) delegate.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }
}
