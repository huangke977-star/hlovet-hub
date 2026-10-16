package xyz.hlovet.portal.prototype

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface NativeUnsubscribeState {
    data object Loading : NativeUnsubscribeState
    data object Done : NativeUnsubscribeState
    data class Error(val message: String) : NativeUnsubscribeState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeUnsubscribeScreen(
    token: String,
    onBack: () -> Unit,
) {
    var state by remember(token) { mutableStateOf<NativeUnsubscribeState>(NativeUnsubscribeState.Loading) }

    LaunchedEffect(token) {
        state = NativeUnsubscribeState.Loading
        runCatching {
            withContext(Dispatchers.IO) { NativeDistributionApi.unsubscribeEmail(token) }
        }.onSuccess {
            state = NativeUnsubscribeState.Done
        }.onFailure { error ->
            state = NativeUnsubscribeState.Error(error.message ?: nativePhrase("退订请求暂时无法完成。", "The unsubscribe request could not be completed."))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopAppBar(
            title = { Text(nativePhrase("邮件订阅", "Email subscription"), fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = nativePhrase("返回", "Back"))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth(), shape = HlovetUi.cardShape) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (val current = state) {
                        NativeUnsubscribeState.Loading -> {
                            Icon(Icons.Filled.Mail, contentDescription = null, tint = HlovetUi.accent)
                            Spacer(Modifier.height(12.dp))
                            Text(nativePhrase("正在处理邮件日报退订", "Processing email digest unsubscribe"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(7.dp))
                            Text(
                                nativePhrase("正在处理你的邮件日报退订请求。", "Processing your email digest unsubscribe request."),
                                color = HlovetUi.muted,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                        NativeUnsubscribeState.Done -> {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = HlovetUi.accent)
                            Spacer(Modifier.height(12.dp))
                            Text(nativePhrase("邮件日报已退订", "Email digest unsubscribed"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(7.dp))
                            Text(
                                nativePhrase("邮件日报已关闭，你的站内订阅不会改变。", "Email digest delivery has been disabled. Your site subscriptions are unchanged."),
                                color = HlovetUi.muted,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = onBack) { Text(nativePhrase("返回首页", "Back to home")) }
                        }
                        is NativeUnsubscribeState.Error -> {
                            Icon(Icons.Filled.Close, contentDescription = null, tint = HlovetUi.secondaryAccent)
                            Spacer(Modifier.height(12.dp))
                            Text(nativePhrase("退订链接不可用", "Unsubscribe link unavailable"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(7.dp))
                            Text(
                                current.message,
                                color = HlovetUi.muted,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = onBack) { Text(nativePhrase("返回首页", "Back to home")) }
                        }
                    }
                }
            }
        }
    }
}
