package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** 展示发现类信息流的固定首行工具栏；首行只承载搜索与筛选，内容流从下一行开始。 */
@Composable
internal fun NativeFeedToolbar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onFilterClick: () -> Unit,
    queryTag: String,
    filterTag: String,
    placeholder: String,
    searchContentDescription: String,
    clearContentDescription: String,
    filterContentDescription: String,
    hasActiveFilter: Boolean,
) {
    // 工具栏保持资讯应用常用的紧凑单行高度，把首屏空间优先留给连续内容流，输入仍限制最大长度避免异常长文本撑开布局。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 搜索使用独立胶囊容器，避免 OutlinedTextField 的默认最小高度裁切占位文字。
        Surface(
            modifier = Modifier.weight(1f).height(34.dp),
            shape = RoundedCornerShape(20.dp),
            color = HlovetUi.glassFallback,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(34.dp).padding(start = 11.dp, end = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = searchContentDescription, tint = HlovetUi.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { onQueryChange(it.take(120)) },
                    modifier = Modifier.weight(1f).fillMaxHeight().testTag(queryTag),
                    singleLine = true,
                    textStyle = TextStyle(color = HlovetUi.foreground, fontSize = 12.sp),
                    cursorBrush = SolidColor(HlovetUi.accent),
                    decorationBox = { innerTextField ->
                        // 占位文字和真实输入共用垂直居中容器，保证中英文和光标位置一致。
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (query.isBlank()) {
                                Text(
                                    placeholder,
                                    color = HlovetUi.muted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    // 清空按钮只在有输入时出现，避免空状态挤压搜索文本。
                    IconButton(onClick = onClearQuery, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = clearContentDescription, tint = HlovetUi.muted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        // 筛选使用无背景图标按钮，激活状态只保留小标记，不额外占用列表宽度。
        Box(
            modifier = Modifier.size(34.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onFilterClick,
                modifier = Modifier.size(34.dp).testTag(filterTag),
            ) {
                Icon(Icons.Filled.Tune, contentDescription = filterContentDescription, tint = HlovetUi.accent, modifier = Modifier.size(20.dp))
            }
            if (hasActiveFilter) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).size(6.dp),
                    shape = CircleShape,
                    color = HlovetUi.secondaryAccent,
                ) {}
            }
        }
    }
}
