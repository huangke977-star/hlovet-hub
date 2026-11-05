package xyz.hlovet.portal.prototype

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    // 工具栏保持紧凑高度，把首屏空间优先留给连续内容流，输入仍限制最大长度避免异常长文本撑开布局。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { onQueryChange(it.take(120)) },
            modifier = Modifier.weight(1f).height(50.dp).testTag(queryTag),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = searchContentDescription, tint = HlovetUi.muted) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClearQuery, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = clearContentDescription, tint = HlovetUi.muted)
                    }
                }
            },
            placeholder = {
                Text(
                    placeholder,
                    color = HlovetUi.muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            colors = HlovetUi.fieldColors(
                focusedBorderColor = HlovetUi.accent,
                unfocusedBorderColor = HlovetUi.divider,
            ),
        )
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
