package com.elysium.softwork.feedback.presentation.views.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elysium.softwork.R
import com.elysium.softwork.feedback.presentation.viewmodel.AiChatViewModel
import com.elysium.softwork.feedback.domain.model.ChatMessage
import com.elysium.softwork.shared.presentation.theme.AccentDark
import com.elysium.softwork.shared.presentation.theme.AccentMint
import com.elysium.softwork.shared.presentation.theme.AccentWhite
import com.elysium.softwork.shared.presentation.theme.PrimaryNavy
import com.elysium.softwork.shared.presentation.theme.PrimarySky

/**
 * FlowWork AI chat surface.
 *
 * High-level composition:
 *  - [ChatTopBar] pinned at the top, padded for the status bar so the title clears any
 *    notch or camera cutout.
 *  - A [LazyColumn] in the middle, taking the remaining vertical space and rendering one
 *    [MessageBubble] per [ChatMessage] (with a [TypingIndicator] row appended while a
 *    reply is in flight). The list auto-scrolls to the newest item whenever the log
 *    grows so the worker never has to chase the conversation manually.
 *  - [ChatInputBar] pinned at the bottom, padded for the IME and the navigation bar so
 *    the composer floats above the software keyboard and never collides with the system
 *    gesture pill.
 *
 * The screen is designed for cold low-end devices: every list item carries a stable key,
 * the scroll-to-bottom side effect is wired to the message count (not the list reference)
 * so it only fires when items are actually appended, and the input value is hoisted into
 * [rememberSaveable] so process death preserves the in-progress draft.
 *
 * @param onBack invoked when the worker taps the back arrow in the header.
 * @param viewModel state holder for the conversation log and the send-in-flight flag.
 *   Defaulted to the ViewModel resolved through the manual service locator factory.
 */
@Composable
fun AiChatScreen(
    onBack: () -> Unit,
    viewModel: AiChatViewModel = viewModel(factory = AiChatViewModel.Factory),
) {
    val messages: List<ChatMessage> by viewModel.messages.collectAsStateWithLifecycle()
    val isSending: Boolean by viewModel.isSending.collectAsStateWithLifecycle()

    var draft: String by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Stable lambda references — defined once on first composition so child composables
    // do not re-bind their click handlers on every parent recomposition.
    val onSubmit: () -> Unit = remember(viewModel) {
        {
            val pending: String = draft
            if (pending.isNotBlank() && !isSending) {
                viewModel.send(pending)
                draft = ""
            }
        }
    }
    val canSend: Boolean = draft.isNotBlank() && !isSending

    // Auto-scroll to the newest entry whenever the log grows. Keyed on the list size +
    // the sending flag so the indicator row also pulls the viewport down when it appears.
    LaunchedEffect(messages.size, isSending) {
        val itemsTotal: Int = messages.size + if (isSending) 1 else 0
        if (itemsTotal > 0) listState.scrollToItem(itemsTotal - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AccentWhite),
    ) {
        ChatTopBar(onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty() && !isSending) {
                item(key = "empty-state") {
                    EmptyState()
                }
            }

            items(items = messages, key = { it.id }) { message ->
                MessageBubble(message = message)
            }

            if (isSending) {
                item(key = "typing-indicator") {
                    TypingIndicator()
                }
            }
        }

        ChatInputBar(
            value = draft,
            onValueChange = { draft = it },
            onSend = onSubmit,
            canSend = canSend,
        )
    }
}

/**
 * Header for the chat surface. Renders a back arrow on the left and the localised
 * "FlowWork AI" title in the brand navy weight.
 *
 * Applies `Modifier.windowInsetsPadding(WindowInsets.statusBars)` so the title clears the
 * status bar / notch / camera cutout regardless of device form factor. When the parent
 * host already consumes the status-bar inset (as `MainNavHost` does via
 * `consumeWindowInsets(paddingValues)`), this modifier resolves to zero additional
 * padding — there is no risk of double-padding.
 *
 * @param onBack invoked when the worker taps the back arrow.
 */
@Composable
private fun ChatTopBar(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.cd_back),
            tint = PrimaryNavy,
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onBack),
        )
        Text(
            text = stringResource(R.string.ai_chat_title),
            color = PrimaryNavy,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 44.dp),
        )
    }
}

/**
 * Centered empty-state shown when no messages have been exchanged yet. Mirrors the AI's
 * voice so the worker immediately understands the affordance of the screen.
 */
@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ai_chat_empty_title),
            color = PrimaryNavy,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.ai_chat_empty_subtitle),
            color = AccentDark,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One message bubble in the conversation log.
 *
 * Visual contract:
 *  - User messages align to the trailing edge, fill with [PrimarySky], and render text in
 *    white. The bottom-trailing corner is squared to mimic the speech-bubble tail.
 *  - AI messages align to the leading edge, fill with `Color.White`, and render text in
 *    [AccentDark]. The bottom-leading corner is squared instead.
 *  - Both variants cap their width at 280.dp so very long messages wrap to multiple lines
 *    rather than spanning the entire width of the screen.
 *
 * @param message message data to render.
 */
@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser: Boolean = message.isFromUser
    val bubbleColor: Color = if (isUser) PrimarySky else Color.White
    val textColor: Color = if (isUser) Color.White else AccentDark
    val alignment: Alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Surface(
            color = bubbleColor,
            shape = shape,
            tonalElevation = 0.dp,
            shadowElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Text(
                text = message.content,
                color = textColor,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * "AI is typing" indicator. Rendered as a small AI-side bubble holding three dots so the
 * worker has immediate feedback that their message was accepted and a reply is in
 * flight.
 */
@Composable
private fun TypingIndicator() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = 4.dp,
                bottomEnd = 18.dp,
            ),
            tonalElevation = 0.dp,
            shadowElevation = 1.dp,
        ) {
            Text(
                text = stringResource(R.string.ai_chat_typing),
                color = AccentDark,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .alpha(0.7f),
            )
        }
    }
}

/**
 * Bottom-pinned composer.
 *
 * Window-inset contract:
 *  - A single `windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))`
 *    lifts the composer above whichever inset is larger on each side. Keyboard hidden → the
 *    nav-bar inset keeps the composer clear of the gesture pill / button bar; keyboard
 *    visible → the IME inset (which already spans the nav-bar region) sits the composer flush
 *    above the keyboard. The union (max per side) is used deliberately instead of stacking
 *    `imePadding()` + `navigationBarsPadding()`, which would sum the two and leave an extra
 *    nav-bar-height gap while the keyboard is open.
 *  - Depends on the Activity declaring `windowSoftInputMode="adjustResize"` (see the manifest)
 *    so, under `enableEdgeToEdge()`, the IME inset is dispatched and animated rather than the
 *    window being panned (panning clips the top of the conversation and double-shifts the
 *    composer).
 *
 * Visual contract: a rounded white surface holding a leading brand-mark image (the
 * adaptive launcher foreground, drawn via `Image` to preserve native colors), a
 * single-line capped [BasicTextField] that grows to 4 lines, and a circular send button
 * that turns active once the draft is non-blank.
 *
 * @param value the current draft text.
 * @param onValueChange invoked on every keystroke.
 * @param onSend invoked when the worker taps the send button or hits the IME Send key.
 * @param canSend `true` when the send button should be enabled.
 */
@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AccentWhite)
            // Single inset source: the union takes the max per side of the IME and the
            // navigation-bar insets rather than summing them. Keyboard hidden → resolves to
            // the nav-bar height (composer clears the gesture pill); keyboard visible → resolves
            // to the IME height, which already spans the nav-bar region, so the composer sits
            // flush above the keyboard with no extra gap. Requires the Activity's
            // `windowSoftInputMode=adjustResize` (declared in the manifest) so the IME inset is
            // dispatched/animated under edge-to-edge instead of the window being panned.
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(36.dp),
        )

        Spacer(modifier = Modifier.size(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
                textStyle = LocalTextStyle.current.copy(
                    color = AccentDark,
                    fontSize = 14.sp,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PrimarySky),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                decorationBox = { innerTextField ->
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ai_chat_input_placeholder),
                            color = AccentDark.copy(alpha = 0.5f),
                            fontSize = 14.sp,
                            style = TextStyle.Default,
                        )
                    }
                    innerTextField()
                },
            )
        }

        Spacer(modifier = Modifier.size(8.dp))

        SendButton(enabled = canSend, onClick = onSend)
    }
}

/**
 * Circular send affordance. Switches between the active brand colour and the muted
 * [AccentMint] depending on [enabled]; the click handler is suppressed while disabled so
 * the gesture cannot enqueue a blank or in-flight send.
 */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val backgroundColor: Color = if (enabled) PrimarySky else AccentMint
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_send),
            contentDescription = stringResource(R.string.ai_chat_send_cd),
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}
