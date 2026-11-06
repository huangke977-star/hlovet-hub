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

/** 展示发现类信息流的固定首行工具栏，统一搜索、清空和筛选控件的尺寸与语义标签。 */
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
    // 工具栏保持单行紧凑高度，把首屏空间优先留给连续内容流，输入仍限制最大长度避免异常长文本撑开布局。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 搜索使用独立胶囊容器，避免 OutlinedTextField 的默认最小高度裁切占位文字。
        Surface(
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(24.dp),
            color = HlovetUi.glassFallback,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = searchContentDescription, tint = HlovetUi.muted, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(9.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { onQueryChange(it.take(120)) },
                    modifier = Modifier.weight(1f).fillMaxHeight().testTag(queryTag),
                    singleLine = true,
                    textStyle = TextStyle(color = HlovetUi.foreground, fontSize = 13.sp),
                    cursorBrush = SolidColor(HlovetUi.accent),
                    decorationBox = { innerTextField ->
                        // 占位文字和真实输入共用垂直居中容器，保证中英文和光标位置一致。
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            if (query.isBlank()) {
                                Text(
                                    placeholder,
                                    color = HlovetUi.muted,
                                    fontSize = 13.sp,
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
                    IconButton(onClick = onClearQuery, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = clearContentDescription, tint = HlovetUi.muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        // 筛选使用无背景图标按钮，激活状态只保留小标记，不额外占用列表宽度。
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onFilterClick,
                modifier = Modifier.size(44.dp).testTag(filterTag),
            ) {
                Icon(Icons.Filled.Tune, contentDescription = filterContentDescription, tint = HlovetUi.accent)
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
