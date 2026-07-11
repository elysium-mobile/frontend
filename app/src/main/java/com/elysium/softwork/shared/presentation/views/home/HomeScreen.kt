package com.elysium.softwork.shared.presentation.views.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elysium.softwork.R
import com.elysium.softwork.shared.presentation.components.InitialsAvatar
import com.elysium.softwork.shared.presentation.components.SoftWorkCard
import com.elysium.softwork.shared.presentation.viewmodel.HomeViewModel
import com.elysium.softwork.worker.forum.domain.model.Thread
import com.elysium.softwork.worker.forum.presentation.viewmodel.ForumViewModel
import com.elysium.softwork.shared.presentation.theme.AccentDark
import com.elysium.softwork.shared.presentation.theme.AccentMint
import com.elysium.softwork.shared.presentation.theme.AccentWhite
import com.elysium.softwork.shared.presentation.theme.PrimaryNavy
import com.elysium.softwork.shared.presentation.theme.PrimarySky
import com.elysium.softwork.shared.presentation.theme.PrimaryTeal
import com.elysium.softwork.shared.presentation.theme.Danger

/**
 * Authenticated home dashboard. Hosts the daily mood check-in plus quick-access cards into
 * the report-incident flow, internal forums, AI assistant, surveys, and membership.
 *
 * Wires action callbacks rather than navigating directly so that the parent navigation host
 * decides where each card leads — keeps the screen testable in isolation.
 *
 * The "Internal forums" card subtitle shows the **live count** of the company's forum threads,
 * sourced from [ForumViewModel] — whose `threads` flow is already scoped to the signed-in worker's
 * organization (company-filtered `GET /api/v1/forums`), so no additional filtering is needed here.
 *
 * The greeting name + avatar are sourced from the persisted session snapshot via [HomeViewModel]
 * (populated on company association: first/last name, falling back to the pseudonym/email), so the
 * dashboard shows real credentials with no logout cycle. The [userName] param is a fallback used
 * only when nothing is cached yet.
 *
 * @param userName fallback name for the greeting/avatar when no cached identity exists.
 * @param onReportIncident handler for the Report-incident card.
 * @param onOpenForums handler for the Internal-forums card.
 * @param onOpenAssistant handler for the AI-assistant card.
 * @param onOpenSurveys handler for the Surveys card.
 * @param onOpenMembership handler for the Membership card.
 * @param forumViewModel supplies the company-scoped thread feed; defaulted to the locator factory.
 */
@Composable
fun HomeScreen(
    userName: String,
    onReportIncident: () -> Unit,
    onOpenForums: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenSurveys: () -> Unit,
    onOpenMembership: () -> Unit,
    modifier: Modifier = Modifier,
    forumViewModel: ForumViewModel = viewModel(factory = ForumViewModel.Factory),
    homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val threads: List<Thread> by forumViewModel.threads.collectAsStateWithLifecycle()
    val displayName: String by homeViewModel.displayName.collectAsStateWithLifecycle()
    // Prefer the persisted real identity; fall back to the passed placeholder only when empty.
    val resolvedName: String = displayName.ifBlank { userName }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        Header(userName = resolvedName)

        Spacer(Modifier.height(20.dp))

        MoodCheckInCard()

        Spacer(Modifier.height(16.dp))

        ActionCard(
            iconRes = R.drawable.ic_flag,
            iconContentDescription = stringResource(R.string.report_title),
            title = stringResource(R.string.home_report_incident),
            subtitle = stringResource(R.string.home_report_incident_subtitle),
            onClick = onReportIncident,
            iconTint = Danger,
        )

        Spacer(Modifier.height(12.dp))

        ActionCard(
            iconRes = R.drawable.ic_people,
            iconContentDescription = stringResource(R.string.cd_people),
            title = stringResource(R.string.home_internal_forums),
            subtitle = stringResource(R.string.home_internal_forums_subtitle, threads.size),
            onClick = onOpenForums,
        )

        Spacer(Modifier.height(12.dp))

        ActionCard(
            iconRes = R.drawable.ic_sparkle,
            iconContentDescription = stringResource(R.string.cd_sparkle),
            title = stringResource(R.string.home_ai_assistant),
            subtitle = stringResource(R.string.home_ai_assistant_subtitle),
            onClick = onOpenAssistant,
        )

        Spacer(Modifier.height(12.dp))

        ActionCard(
            iconRes = R.drawable.ic_check_circle,
            iconContentDescription = stringResource(R.string.home_surveys),
            title = stringResource(R.string.home_surveys),
            subtitle = stringResource(R.string.home_surveys_subtitle),
            onClick = onOpenSurveys,
        )

        Spacer(Modifier.height(12.dp))

        ActionCard(
            iconRes = R.drawable.ic_payments, // Using sparkle for membership as per design preference
            iconContentDescription = stringResource(R.string.home_membership),
            title = stringResource(R.string.home_membership),
            subtitle = stringResource(R.string.home_membership_subtitle),
            onClick = onOpenMembership,
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Header(userName: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.home_greeting),
                style = MaterialTheme.typography.bodyMedium,
                color = AccentDark,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = userName,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = PrimaryNavy,
            )
        }
        InitialsAvatar(fullName = userName, size = 44.dp)
    }
}

@Composable
private fun MoodCheckInCard() {
    SoftWorkCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.home_mood_question),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = PrimaryNavy,
                    modifier = Modifier.weight(1f),
                )
                AnonymousBadge()
            }
            Spacer(Modifier.height(16.dp))
            MoodSelector()
        }
    }
}

@Composable
private fun AnonymousBadge() {
    Box(
        modifier = Modifier
            .background(color = AccentMint, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.home_anonymous_badge),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = PrimaryNavy,
            fontSize = 10.sp,
        )
    }
}

/** 5-point mood scale. Values 0..4 map left-to-right; 2 (neutral) is the default selection. */
@Composable
private fun MoodSelector() {
    val emojis: List<String> = remember { listOf("😞", "😟", "😐", "🙂", "🤩") }
    var selectedIndex: Int by rememberSaveable { mutableIntStateOf(2) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        emojis.forEachIndexed { index, emoji ->
            MoodEmojiButton(
                emoji = emoji,
                selected = index == selectedIndex,
                onClick = { selectedIndex = index },
            )
        }
    }
}

@Composable
private fun MoodEmojiButton(
    emoji: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderModifier: Modifier = if (selected) {
        Modifier.border(width = 2.dp, color = PrimaryTeal, shape = CircleShape)
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(color = AccentWhite, shape = CircleShape)
            .then(borderModifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, fontSize = 24.sp)
    }
}

@Composable
private fun ActionCard(
    iconRes: Int,
    iconContentDescription: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    iconTint: Color = PrimarySky,
) {
    SoftWorkCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(color = if (iconTint == Danger) Danger.copy(alpha = 0.1f) else AccentMint.copy(alpha = 0.4f), shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = iconContentDescription,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = PrimaryNavy,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentDark,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = stringResource(R.string.cd_chevron),
                tint = AccentDark,
                modifier = Modifier
                    .size(24.dp)
                    .padding(start = 8.dp),
            )
        }
    }
}
