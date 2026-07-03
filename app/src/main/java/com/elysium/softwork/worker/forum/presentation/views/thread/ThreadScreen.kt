package com.elysium.softwork.worker.forum.presentation.views.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elysium.softwork.R
import com.elysium.softwork.worker.forum.domain.model.Message
import com.elysium.softwork.worker.forum.domain.model.Thread
import com.elysium.softwork.worker.forum.presentation.viewmodel.ThreadViewModel
import com.elysium.softwork.shared.presentation.components.InitialsAvatar
import com.elysium.softwork.shared.presentation.components.SoftWorkCard
import com.elysium.softwork.shared.presentation.theme.AccentDark
import com.elysium.softwork.shared.presentation.theme.AccentWhite
import com.elysium.softwork.shared.presentation.theme.Danger
import com.elysium.softwork.shared.presentation.theme.PrimaryNavy
import com.elysium.softwork.shared.presentation.theme.PrimarySky

/**
 * Thread-detail screen. Shows the [Thread] header followed by its live [Message] list from
 * the offline-first cache; the bottom sticky input posts a real reply (the author id is bound
 * from prefs by the use case). A backend `400` surfaces inline.
 *
 * @param threadId backend `thread_id` of the open thread.
 * @param onReport navigates to the report screen for the current thread.
 */
@Composable
fun ThreadScreen(
    threadId: Long,
    userName: String,
    onBack: () -> Unit,
    onReport: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThreadViewModel = viewModel(factory = ThreadViewModel.Factory),
) {
    val thread: Thread? by viewModel.thread.collectAsStateWithLifecycle()
    val messages: List<Message> by viewModel.messages.collectAsStateWithLifecycle()
    val errorMessage: String? by viewModel.errorMessage.collectAsStateWithLifecycle()

    LaunchedEffect(threadId) {
        viewModel.load(threadId)
    }

    Column(modifier = modifier.fillMaxSize()) {
        ThreadHeader(onBack = onBack)

        errorMessage?.let { message ->
            Text(
                text = message,
                color = Danger,
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        ) {
            thread?.let { current ->
                item {
                    ThreadHeaderCard(
                        thread = current,
                        onReport = { onReport(current.thread_id) },
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.forum_thread_replies_section),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AccentDark,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
            }
            items(items = messages, key = { it.message_id ?: 0L }) { message ->
                MessageBubble(message = message)
            }
        }

        StickyCommentInput(
            isAnonymous = viewModel.isAnonymous,
            userName = userName,
            onSend = viewModel::sendMessage,
        )
    }
}

@Composable
private fun ThreadHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.cd_back),
            tint = AccentDark,
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onBack),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = stringResource(R.string.forum_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = PrimaryNavy,
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun ThreadHeaderCard(thread: Thread, onReport: () -> Unit) {
    SoftWorkCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = thread.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = PrimaryNavy,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(R.drawable.ic_flag),
                    contentDescription = stringResource(R.string.report_title),
                    tint = Danger.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(onClick = onReport),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                thread.last_message?.let { date ->
                    Text(
                        text = date,
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentDark.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.forum_replies_count, thread.message_count ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentDark,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 12.dp),
        color = AccentWhite,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = stringResource(R.string.forum_message_author, message.user_account_id ?: 0),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = PrimaryNavy,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message.content_message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentDark,
            )
        }
    }
}

@Composable
private fun AnonymousAvatar(size: androidx.compose.ui.unit.Dp = 32.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(color = AccentDark.copy(alpha = 0.55f), shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value / 2).sp)
    }
}

@Composable
private fun StickyCommentInput(isAnonymous: Boolean, userName: String, onSend: (String) -> Unit) {
    var draft: String by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isAnonymous) {
                AnonymousAvatar(size = 32.dp)
            } else {
                InitialsAvatar(fullName = userName, size = 32.dp)
            }
            Spacer(Modifier.size(10.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(color = AccentWhite, shape = RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (draft.isEmpty()) {
                    Text(
                        text = stringResource(R.string.forum_thread_comment_hint),
                        color = AccentDark.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = PrimaryNavy, fontSize = 14.sp),
                    cursorBrush = SolidColor(PrimarySky),
                )
            }

            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color = PrimarySky, shape = CircleShape)
                    .clickable(enabled = draft.isNotBlank()) {
                        onSend(draft)
                        draft = ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_send),
                    contentDescription = stringResource(R.string.cd_send),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
