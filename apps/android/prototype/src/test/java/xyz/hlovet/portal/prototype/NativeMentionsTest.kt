package xyz.hlovet.portal.prototype

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test

/** 验证提及的原文坐标、空格确认、输入法边界及窗口定位，不依赖账号或网络。 */
class NativeMentionsTest {
    /** 空词、单字和正文中间的光标均使用同一候选规则。 */
    @Test fun findsEmptyAndSingleCharacterQueriesAtTheCursor() {
        // 保留 @ 前的空白、后面的正文，不能只匹配文本末尾。
        assertEquals(NativeActiveMention(0, 1, ""), nativeActiveMention(TextFieldValue("@", TextRange(1))))
        assertEquals(NativeActiveMention(6, 8, "n"), nativeActiveMention(TextFieldValue("hello @n suffix", TextRange(8))))
        assertEquals(NativeActiveMention(3, 6, "ab"), nativeActiveMention(TextFieldValue("hi\n@ab", TextRange(6))))
    }

    /** 非提及、超长、选区及输入法组合阶段不得接管候选。 */
    @Test fun ignoresInvalidContextsAndComposition() {
        // 邮箱、已确认标签和不合法用户名不触发，保持普通输入能力。
        listOf("mail@host", "@nice ", "@nice\n", "@nice\r\n", "@with-dash", "@" + "a".repeat(33)).forEach { text ->
            assertNull(nativeActiveMention(TextFieldValue(text, TextRange(text.length))))
        }
        assertNull(nativeActiveMention(TextFieldValue("@nice", TextRange(1, 5))))
        assertNull(nativeActiveMention(TextFieldValue("@nice", TextRange(5), TextRange(1, 5))))
    }

    /** 选择候选只替换当前片段并精确移动光标，不丢失后续正文。 */
    @Test fun insertsMentionWithoutDuplicatingOrDiscardingText() {
        // 标签与正文仍是可编辑的普通原文，插入一个空格后不复制最后字符。
        val next = nativeInsertMention(TextFieldValue("hi @ni tail", TextRange(6)), "nice3")
        assertEquals("hi @nice3  tail", next.text)
        assertEquals(TextRange(10), next.selection)
        assertEquals("@nice3 ", nativeInsertMention(TextFieldValue("@n", TextRange(2)), "nice3").text)
    }

    /** 非法候选或长度超限必须保留原文及选区。 */
    @Test fun rejectsInvalidUsernamesAndOverflow() {
        // 服务端候选异常不应使输入框突破长度限制或插入非法标识。
        val value = TextFieldValue("@n", TextRange(2))
        assertEquals(value, nativeInsertMention(value, "a-b"))
        assertEquals(value, nativeInsertMention(value, "nice3", maxLength = 5))
    }

    /** 输入标签需要空白确认，已发送正文允许以用户名结束，与 PC 正文展示一致。 */
    @Test fun confirmsComposingMentionsOnlyAfterWhitespace() {
        // 无空格保留普通文本；空格和换行都不改变字数，只新增背景样式。
        assertTrue(nativeMentionText("@nice3", Color.Blue, Color.White, false).spanStyles.isEmpty())
        assertEquals(1, nativeMentionText("@nice3 ", Color.Blue, Color.White, false).spanStyles.size)
        assertEquals(1, nativeMentionText("@nice3\n", Color.Blue, Color.White, false).spanStyles.size)
        assertEquals(1, nativeMentionText("@nice3", Color.Blue, Color.White).spanStyles.size)
    }

    /** 组合范围不能着色，普通文本中的其它已确认标签仍然保留。 */
    @Test fun skipsOnlyTheTokenOverlappingImeComposition() {
        // 一个输入法事务不应取消前面已经完成的提及样式。
        val styled = nativeMentionText("@aa @bb ", Color.Blue, Color.White, false, TextRange(4, 7))
        assertEquals(1, styled.spanStyles.size)
        assertEquals(0, styled.spanStyles.single().start)
        assertEquals(3, styled.spanStyles.single().end)
    }

    /** 邮箱、标点相连和无效账号格式保持普通文字。 */
    @Test fun preservesSyntaxBoundaries() {
        // 不扩大提及解析范围，也不把非用户名字符吞入标签。
        assertTrue(nativeMentionText("a@nice @one-two @x @nice!", Color.Blue, Color.White).spanStyles.isEmpty())
        assertEquals(2, nativeMentionText("@first hi @second", Color.Blue, Color.White).spanStyles.size)
    }

    /** 绘制转换必须维持所有 UTF-16 偏移，避免 emoji、中文或标签后的光标位移。 */
    @Test fun keepsAllOffsetsAndFontMetricsUnchanged() {
        // 检查字重和字号未改变，坐标映射逐位置恒等。
        val text = "中文 😀 @nice3 end"
        val transformed = NativeMentionTransformation(Color.Blue, Color.White, null).filter(AnnotatedString(text))
        assertEquals(text, transformed.text.text)
        (0..text.length).forEach { index ->
            assertEquals(index, transformed.offsetMapping.originalToTransformed(index))
            assertEquals(index, transformed.offsetMapping.transformedToOriginal(index))
        }
        assertTrue(transformed.text.spanStyles.all { it.item.fontSize.isUnspecified && it.item.fontWeight == null })
    }

    /** 菜单优先贴近光标上方并始终限制在窗口，顶部空间不足时显示到下方。 */
    @Test fun keepsPopupWithinWindow() {
        // 左右贴边和窗口顶部均不能把菜单裁到屏幕外。
        val provider = NativeMentionPopupPosition(Rect(190f, 10f, 191f, 30f), 6)
        assertEquals(IntOffset(114, 106), provider.calculatePosition(IntRect(0, 300, 300, 350), IntSize(320, 640), LayoutDirection.Ltr, IntSize(200, 198)))
        assertEquals(IntOffset(114, 46), provider.calculatePosition(IntRect(0, 10, 300, 60), IntSize(320, 640), LayoutDirection.Ltr, IntSize(200, 198)))
    }

    /** 菜单重建暂时没有自身锚点时，仍使用已测量输入框位置，不跳到窗口左上角。 */
    @Test fun usesMeasuredOriginWhenPopupAnchorIsNotPlacedYet() {
        // 输入框位置独立于条件弹层的布局周期，零锚点不得覆盖有效的窗口坐标。
        val provider = NativeMentionPopupPosition(Rect(190f, 10f, 191f, 30f), 6, IntOffset(0, 300))
        assertEquals(IntOffset(114, 106), provider.calculatePosition(IntRect.Zero, IntSize(320, 640), LayoutDirection.Ltr, IntSize(200, 198)))
    }

    /** 键盘移动父窗口后，实时锚点必须优先于先前缓存的输入框位置。 */
    @Test fun prefersLiveAnchorAfterKeyboardMovesTheWindow() {
        // 缓存仍指向窗口底部时，菜单也应跟随已经上移的实际输入框。
        val provider = NativeMentionPopupPosition(Rect(190f, 0f, 191f, 48f), 6, IntOffset(0, 550))
        assertEquals(IntOffset(114, 96), provider.calculatePosition(IntRect(0, 300, 300, 348), IntSize(320, 640), LayoutDirection.Ltr, IntSize(200, 198)))
    }
}
