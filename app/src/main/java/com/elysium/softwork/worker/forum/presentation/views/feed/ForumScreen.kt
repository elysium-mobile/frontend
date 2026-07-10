package com.elysium.softwork.worker.forum.presentation.views.feed

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elysium.softwork.R
import com.elysium.softwork.worker.forum.domain.model.Thread
import com.elysium.softwork.worker.forum.presentation.viewmodel.ForumViewModel
import com.elysium.softwork.shared.presentation.components.SoftWorkCard
import com.elysium.softwork.shared.presentation.theme.AccentDark
import com.elysium.softwork.shared.presentation.theme.Danger
import com.elysium.softwork.shared.presentation.theme.PrimaryNavy
import com.elysium.softwork.shared.presentation.theme.PrimarySky

/**
 * Forum feed (the "Forum" tab destination). Lists the backend discussion [Thread]s with a
 * circular FAB that opens the new-thread composer.
 *
 * Adapted to the live hierarchical backend: a thread carries only a title, a reply count, and
 * a last-activity date, so the card shows those — the former author / anonymity / category /
 * body-snippet are not part of the thread payload.
 *
 * The feed is wrapped in a Material 3 [PullToRefreshBox]: a pull-down calls
 * [ForumViewModel.refresh] (re-fetches `GET /api/v1/forums` and re-applies the company filter),
 * with the top indicator driven by `ForumViewModel.isRefreshing`.
 *
 * @param onNewPost handler for the "+" FAB tap.
 * @param onOpenThread handler invoked with the thread id when a card is tapped.
 * @param onReportPost handler for reporting a thread directly from the feed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumScreen(
    onNewPost: () -> Unit,
    onOpenThread: (Long) -> Unit,
    onReportPost: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ForumViewModel = viewModel(factory = ForumViewModel.Factory),
) {
    val threads: List<Thread> by viewModel.threads.collectAsStateWithLifecycle()
    val errorMessage: String? by viewModel.errorMessage.collectAsStateWithLifecycle()
    val isRefreshing: Boolean by viewModel.isRefreshing.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            FeedHeader()

            errorMessage?.let { message ->
                Text(
                    text = message,
                    color = Danger,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // A single LazyColumn hosts both states so the pull gesture always has a
                // scrollable child to attach to. When empty, a viewport-filling item shows the
                // graceful fallback (never cross-tenant content — the company-scoped refresh
                // caches only the worker's org threads) while staying pullable.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                ) {
                    if (threads.isEmpty()) {
                        item(key = "empty") {
                            ForumEmptyState(modifier = Modifier.fillParentMaxSize())
                        }
                    } else {
                        items(items = threads, key = { it.thread_id }) { thread ->
                            ThreadCard(
                                thread = thread,
                                onClick = { onOpenThread(thread.thread_id) },
                                onReport = { onReportPost(thread.thread_id) },
                            )
                        }
                    }
                }
            }
        }

        NewPostFab(
            onClick = onNewPost,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun FeedHeader() {
    Text(
        text = stringResource(R.string.forum_title),
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        color = PrimaryNavy,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ForumEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.forum_empty),
            color = AccentDark,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ThreadCard(thread: Thread, onClick: () -> Unit, onReport: () -> Unit) {
    SoftWorkCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = thread.title.orEmpty(),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = PrimaryNavy,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(R.drawable.ic_flag),
                    contentDescription = stringResource(R.string.report_title),
                    tint = Danger.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onReport),
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
private fun NewPostFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .background(color = PrimarySky, shape = RoundedCornerShape(28.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_add),
            contentDescription = stringResource(R.string.cd_new_post),
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}
