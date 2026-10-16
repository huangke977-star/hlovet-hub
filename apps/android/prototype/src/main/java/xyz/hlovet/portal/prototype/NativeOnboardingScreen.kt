package xyz.hlovet.portal.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** First-run interest selection shared with the web onboarding contract. */
@Composable
internal fun NativeOnboardingDialog(
    state: NativeOnboardingState,
    saving: Boolean,
    error: String?,
    onComplete: (topicIds: List<Int>, authorIds: List<Int>) -> Unit,
    onSkip: () -> Unit,
) {
    var selectedTopicIds by remember(state) {
        mutableStateOf(state.topics.filter { it.subscribed }.map { it.id }.take(3))
    }
    var selectedAuthorIds by remember(state) {
        mutableStateOf(state.authors.filter { it.subscribed }.map { it.id }.take(6))
    }

    fun toggleTopic(id: Int) {
        selectedTopicIds = if (id in selectedTopicIds) {
            selectedTopicIds.filterNot { it == id }
        } else if (selectedTopicIds.size < 3) {
            selectedTopicIds + id
        } else {
            selectedTopicIds
        }
    }

    fun toggleAuthor(id: Int) {
        selectedAuthorIds = if (id in selectedAuthorIds) {
            selectedAuthorIds.filterNot { it == id }
        } else if (selectedAuthorIds.size < 6) {
            selectedAuthorIds + id
        } else {
            selectedAuthorIds
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = 360.dp, max = 680.dp),
            shape = HlovetUi.cardShape,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(38.dp).clip(CircleShape).background(HlovetUi.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Explore, contentDescription = null, tint = HlovetUi.accent, modifier = Modifier.size(21.dp))
                    }
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nativePhrase("选择你的兴趣", "Choose your interests"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            nativePhrase("最多选择 3 个专题和 6 位创作者，之后可以在订阅页调整。", "Choose up to 3 topics and 6 creators. You can change this later in subscriptions."),
                            color = HlovetUi.muted,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider(color = HlovetUi.divider)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        OnboardingSectionHeading(nativePhrase("兴趣专题", "Topics"), nativePhrase("已选 ${selectedTopicIds.size}/3", "${selectedTopicIds.size}/3 selected"))
                    }
                    if (state.topics.isEmpty()) {
                        item { OnboardingEmptyText(nativePhrase("暂时没有可选专题，之后可以在发现页浏览。", "No topics are available yet. You can browse them later in Discover.")) }
                    } else {
                        items(state.topics, key = { it.id }) { topic ->
                            OnboardingTopicRow(
                                topic = topic,
                                selected = topic.id in selectedTopicIds,
                                onClick = { toggleTopic(topic.id) },
                            )
                        }
                    }
                    if (state.authors.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(5.dp))
                            OnboardingSectionHeading(nativePhrase("推荐创作者", "Creators"), nativePhrase("已选 ${selectedAuthorIds.size}/6", "${selectedAuthorIds.size}/6 selected"))
                        }
                        items(state.authors, key = { it.id }) { author ->
                            OnboardingAuthorRow(
                                author = author,
                                selected = author.id in selectedAuthorIds,
                                onClick = { toggleAuthor(author.id) },
                            )
                        }
                    }
                    if (!error.isNullOrBlank()) {
                        item {
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(color = HlovetUi.divider)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onSkip, enabled = !saving, modifier = Modifier.testTag("native-onboarding-skip")) {
                        Text(nativePhrase("暂时跳过", "Skip for now"), color = HlovetUi.muted, fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { onComplete(selectedTopicIds, selectedAuthorIds) },
                        enabled = !saving,
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = HlovetUi.accent,
                            )
                            Spacer(Modifier.size(6.dp))
                        } else {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = HlovetUi.accent)
                            Spacer(Modifier.size(4.dp))
                        }
                        Text(nativePhrase("完成", "Done"), color = HlovetUi.accent, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingSectionHeading(title: String, count: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(count, color = HlovetUi.muted, fontSize = 11.sp)
    }
}

@Composable
private fun OnboardingTopicRow(
    topic: NativeOnboardingTopic,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OnboardingSelectableRow(selected = selected, onClick = onClick) {
        NativeCoverImage(
            path = topic.coverPath,
            modifier = Modifier.size(44.dp),
            fallbackText = topic.title,
            tint = HlovetUi.accent,
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(topic.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                topic.description.ifBlank { nativePhrase("${topic.articleCount} 篇文章 · ${topic.subscriberCount} 人订阅", "${topic.articleCount} articles · ${topic.subscriberCount} subscribers") },
                color = HlovetUi.muted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) OnboardingCheckmark()
    }
}

@Composable
private fun OnboardingAuthorRow(
    author: NativeOnboardingAuthor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OnboardingSelectableRow(selected = selected, onClick = onClick) {
        NativeCoverImage(
            path = author.avatarUrl,
            modifier = Modifier.size(44.dp).clip(CircleShape),
            fallbackText = author.nickname,
            tint = HlovetUi.secondaryAccent,
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(author.nickname, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "@${author.username.ifBlank { "user" }} · ${nativePhrase("常写：", "Usually writes: ")}${author.topCategory}",
                color = HlovetUi.muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) OnboardingCheckmark()
    }
}

@Composable
private fun OnboardingSelectableRow(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HlovetUi.rowShape)
            .background(if (selected) HlovetUi.accentSoft else HlovetUi.surfaceLow.copy(alpha = .62f))
            .clickable(enabled = true, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun OnboardingCheckmark() {
    Box(
        modifier = Modifier.size(22.dp).clip(CircleShape).background(HlovetUi.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Check, contentDescription = nativePhrase("已选择", "Selected"), tint = HlovetUi.onAccent, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun OnboardingEmptyText(message: String) {
    Text(message, color = HlovetUi.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
}
