@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.hlovet.portal.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/** 描述光标前正在编辑的提及片段，start/end 为原始文本偏移，query 不含 @。 */
internal data class NativeActiveMention(val start: Int, val end: Int, val query: String)

/** 从 value 的折叠光标前读取候选关键词；选区、输入法组合文本和非用户名片段均不触发。 */
internal fun nativeActiveMention(value: TextFieldValue): NativeActiveMention? {
    // 不接管输入法尚未提交的拼音，也不覆盖用户选中的文本。
    if (!value.selection.collapsed || value.composition != null) return null
    val cursor = value.selection.start.coerceIn(0, value.text.length)
    // 使用绝对文本结尾；普通 $ 会匹配末尾换行之前，导致换行后错误保留候选。
    val match = Regex("(?:^|\\s)@([A-Za-z0-9_]{0,32})\\z").find(value.text.substring(0, cursor)) ?: return null
    // 使用原文偏移而非正则匹配起点，保留 @ 前的空白和后续正文。
    return NativeActiveMention(cursor - match.groupValues[1].length - 1, cursor, match.groupValues[1])
}

/** 将 username 插入 value 当前提及片段并补一个空格；非法账号或超过 maxLength 时保留原输入。 */
internal fun nativeInsertMention(value: TextFieldValue, username: String, maxLength: Int = 2000): TextFieldValue {
    // 候选账号继续遵循站点用户名格式，不允许客户端插入不可识别的标签。
    val active = nativeActiveMention(value) ?: return value
    if (!Regex("[A-Za-z0-9_]{2,32}").matches(username)) return value
    val replacement = "@$username "
    val text = value.text.replaceRange(active.start, active.end, replacement)
    if (text.length > maxLength) return value
    // 只替换光标之前的提及片段，后半段内容和新光标均使用原文坐标。
    return TextFieldValue(text, TextRange(active.start + replacement.length))
}

/** 为 text 生成不改变字宽的提及样式；输入中需空白确认，正文允许以用户名结尾。 */
internal fun nativeMentionText(
    text: String,
    accent: Color,
    background: Color,
    confirmAtEnd: Boolean = true,
    composition: TextRange? = null,
): AnnotatedString {
    // 背景与颜色不会引入额外字符、字重或字号变化，保持光标、选择和换行与原文字形一致。
    val builder = AnnotatedString.Builder(text)
    val boundary = if (confirmAtEnd) "(?=\\s|$)" else "(?=\\s)"
    Regex("(^|\\s)(@[A-Za-z0-9_]{2,32})$boundary").findAll(text).forEach { match ->
        val start = match.range.first + match.groupValues[1].length
        val end = start + match.groupValues[2].length
        if (composition == null || end <= composition.min || start >= composition.max) {
            builder.addStyle(SpanStyle(color = accent, background = background), start, end)
        }
    }
    // 所有偏移保持恒等，供输入与只读正文使用同一标签规则。
    return builder.toAnnotatedString()
}

/** 仅绘制已确认提及，不改变原文或偏移；颜色跟随当前账号外观，composition 为输入法组合范围。 */
internal class NativeMentionTransformation(
    private val accent: Color,
    private val background: Color,
    private val composition: TextRange?,
) : VisualTransformation {
    /** 为 text 应用确认标签并返回恒等坐标映射，避免光标和文本错位。 */
    override fun filter(text: AnnotatedString): TransformedText {
        // 将样式作为同一段原文的绘制属性，而非叠加第二个文本视图。
        return TransformedText(nativeMentionText(text.text, accent, background, false, composition), OffsetMapping.Identity)
    }
}

/** 将菜单放到 caret 附近；gap 为像素间距，origin 为已测量的输入框窗口坐标，避免弹层重建时使用零锚点。 */
internal class NativeMentionPopupPosition(private val caret: Rect, private val gap: Int, private val origin: IntOffset? = null) : PopupPositionProvider {
    /** 根据 anchorBounds、窗口尺寸和 popupContentSize 计算完整可见的菜单位置。 */
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        // Popup 会逐帧追踪键盘引起的父窗口位移；只有尚未测量时才回退缓存，不能用旧坐标覆盖实时锚点。
        val anchorMeasured = anchorBounds.width > 0 && anchorBounds.height > 0
        val originX = if (anchorMeasured) anchorBounds.left else origin?.x ?: anchorBounds.left
        val originY = if (anchorMeasured) anchorBounds.top else origin?.y ?: anchorBounds.top
        // 优先出现在光标上方；空间不足改放下方，最后按当前窗口可用范围裁剪位置。
        val left = originX + caret.left.roundToInt()
        val above = originY + caret.top.roundToInt() - gap - popupContentSize.height
        val below = originY + caret.bottom.roundToInt() + gap
        val top = if (above >= gap) above else below
        return IntOffset(
            left.coerceIn(gap, (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)),
            top.coerceIn(gap, (windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap)),
        )
    }
}

/** 聊天和评论共用的提及输入框；candidates/loading 由所在页面按权限查询，placeholder 为当前动作提示，semanticTag 用于稳定定位输入控件。 */
@Composable
internal fun NativeMentionTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    candidates: List<NativeSocialSearchUser>,
    loading: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    semanticTag: String = "native-mention-input",
) {
    // 输入和菜单共享原文字形、焦点及候选选中索引；没有候选时保留原生键盘行为。
    val active = nativeActiveMention(value)
    var focused by remember { mutableStateOf(false) }
    var activeIndex by remember(active) { mutableIntStateOf(0) }
    var dismissed by remember(active) { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var fieldOrigin by remember { mutableStateOf(Offset.Zero) }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    var textOrigin by remember { mutableStateOf(Offset.Zero) }
    val listState = rememberLazyListState()
    val interaction = remember { MutableInteractionSource() }
    val transformation = remember(HlovetUi.accent, HlovetUi.accentSoft, value.composition) {
        NativeMentionTransformation(HlovetUi.accent, HlovetUi.accentSoft, value.composition)
    }
    val showMenu = enabled && focused && active != null && !dismissed && (loading || candidates.isNotEmpty())
    val optionIndex = activeIndex.coerceIn(0, candidates.lastIndex.coerceAtLeast(0))

    // 方向键切换到尚未显示的候选时，同步滚动菜单，不移动正文或输入框。
    LaunchedEffect(optionIndex, candidates, showMenu) {
        if (showMenu && !loading && candidates.isNotEmpty()) listState.scrollToItem(optionIndex)
    }

    Box(modifier.onGloballyPositioned { fieldOrigin = it.positionInWindow(); fieldSize = it.size }) {
        BasicTextField(
            value = value,
            onValueChange = { next ->
                // 保留完整的选区和输入法组合状态；长度超限时拒绝本次变更，不截断半个输入法事务。
                if (next.text.length <= 2000) onValueChange(next)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag(semanticTag)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    // 候选仅在显示且加载完成时拦截导航；组合输入期间不抢回车。
                    if (!showMenu || loading || candidates.isEmpty() || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> { activeIndex = (activeIndex.coerceIn(0, candidates.lastIndex) + 1) % candidates.size; true }
                        Key.DirectionUp -> { activeIndex = (activeIndex.coerceIn(0, candidates.lastIndex) - 1 + candidates.size) % candidates.size; true }
                        Key.Enter, Key.NumPadEnter -> {
                            if (event.isShiftPressed) {
                                // 部分 Android 键盘忽略 Shift+Enter，显式插入换行并保持后续正文。
                                val cursor = value.selection.start.coerceIn(0, value.text.length)
                                val next = value.text.substring(0, cursor) + "\n" + value.text.substring(cursor)
                                if (next.length <= 2000) onValueChange(TextFieldValue(next, TextRange(cursor + 1)))
                            } else {
                                onValueChange(nativeInsertMention(value, candidates[activeIndex.coerceIn(0, candidates.lastIndex)].user.username))
                            }
                            true
                        }
                        Key.Tab -> {
                            onValueChange(nativeInsertMention(value, candidates[activeIndex.coerceIn(0, candidates.lastIndex)].user.username)); true
                        }
                        Key.Escape -> { dismissed = true; true }
                        else -> false
                    }
                },
            enabled = enabled,
            textStyle = TextStyle(color = HlovetUi.foreground, fontSize = 13.sp, lineHeight = 20.sp),
            cursorBrush = SolidColor(HlovetUi.accent),
            interactionSource = interaction,
            minLines = 1,
            maxLines = 4,
            visualTransformation = transformation,
            onTextLayout = { layout = it },
            decorationBox = { inner ->
                // 沿用全站输入框的背景和边框，只缩减消息输入的内边距，不建立另一套视觉体系。
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value.text, innerTextField = {
                        Box(Modifier.onGloballyPositioned { textOrigin = it.positionInWindow() }) { inner() }
                    }, enabled = enabled, singleLine = false, visualTransformation = transformation,
                    interactionSource = interaction, colors = loginFieldColors(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    placeholder = { Text(placeholder, color = HlovetUi.muted, fontSize = 13.sp, lineHeight = 20.sp) },
                )
            },
        )
        if (showMenu) {
            // 使用真实文本光标矩形定位独立弹层，避免被评论卡片、聊天输入栏或滚动容器裁剪。
            val cursor = value.selection.start.coerceIn(0, value.text.length)
            val cursorRect = layout?.let { it.getCursorRect(cursor.coerceAtMost(it.layoutInput.text.length)) } ?: Rect.Zero
            val rawCaret = cursorRect.translate(textOrigin - fieldOrigin)
            // 水平跟随可见光标，竖直避开整个输入区域，避免内边距使菜单压住边框或当前输入行。
            val relativeCaret = Rect(
                rawCaret.left.coerceIn(0f, fieldSize.width.toFloat()),
                0f,
                rawCaret.right.coerceIn(0f, fieldSize.width.toFloat()),
                fieldSize.height.toFloat(),
            )
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            Popup(
                popupPositionProvider = NativeMentionPopupPosition(relativeCaret, gap, IntOffset(fieldOrigin.x.roundToInt(), fieldOrigin.y.roundToInt())),
                onDismissRequest = { dismissed = true },
                properties = PopupProperties(focusable = false, clippingEnabled = false),
            ) {
                // 候选关闭即销毁独立窗口，避免空窗口在键盘尺寸变化后保留旧的布局状态。
                Surface(
                    modifier = Modifier.width(280.dp).testTag("mention-suggestions"),
                    shape = HlovetUi.menuShape, color = HlovetUi.menuSurface, tonalElevation = 0.dp,
                ) {
                    if (loading) {
                        Text(nativePhrase("正在查找用户…", "Searching users..."), color = HlovetUi.muted, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 180.dp)) {
                            itemsIndexed(candidates, key = { _, candidate -> candidate.user.id }) { index, candidate ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().testTag("mention-option-${candidate.user.id}")
                                        .background(if (index == optionIndex) HlovetUi.accentSoft else Color.Transparent)
                                        .selectable(index == optionIndex, role = Role.Button) {
                                            onValueChange(nativeInsertMention(value, candidate.user.username))
                                        }.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 复用现有头像组件，保持候选项与聊天中同类组件一致。
                                    Avatar(candidate.user.nickname.ifBlank { candidate.user.username }.takeLast(2), HlovetUi.accent, modifier = Modifier.size(30.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(candidate.user.nickname, color = HlovetUi.foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("@${candidate.user.username}", color = HlovetUi.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
