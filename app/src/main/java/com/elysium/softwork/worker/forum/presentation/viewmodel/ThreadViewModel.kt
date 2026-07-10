package com.elysium.softwork.worker.forum.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.elysium.softwork.SoftWorkApplication
import com.elysium.softwork.shared.application.usecase.GetForumAnonymityUseCase
import com.elysium.softwork.shared.data.network.BadRequestException
import com.elysium.softwork.worker.forum.application.usecase.GetThreadUseCase
import com.elysium.softwork.worker.forum.application.usecase.ObserveThreadMessagesUseCase
import com.elysium.softwork.worker.forum.application.usecase.PostMessageUseCase
import com.elysium.softwork.worker.forum.application.usecase.RefreshThreadMessagesUseCase
import com.elysium.softwork.worker.forum.application.usecase.RefreshThreadUseCase
import com.elysium.softwork.worker.forum.domain.model.Message
import com.elysium.softwork.worker.forum.domain.model.Thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state holder for the thread-detail screen.
 *
 * Loads the [Thread] header through [GetThreadUseCase], streams its [Message]s from the
 * offline-first cache (refreshing from the network on entry), and posts new replies through
 * [PostMessageUseCase] (which binds the worker's `user_account_id` from `SharedPrefsManager`).
 * A backend `400` is caught, parsed via [BadRequestException], and routed into [errorMessage].
 *
 * @param getThread resolves a thread by id from the local cache.
 * @param observeMessages streams the cached messages for the thread.
 * @param refreshMessages pulls the thread's messages from the network.
 * @param refreshThread pull-to-refresh: re-fetches the thread detail (header + nested replies).
 * @param postMessage posts a reply (binds the author id from prefs).
 * @param getForumAnonymity reads the persisted forum-anonymity flag for the input avatar.
 */
class ThreadViewModel(
    private val getThread: GetThreadUseCase,
    private val observeMessages: ObserveThreadMessagesUseCase,
    private val refreshMessages: RefreshThreadMessagesUseCase,
    private val refreshThread: RefreshThreadUseCase,
    private val postMessage: PostMessageUseCase,
    getForumAnonymity: GetForumAnonymityUseCase,
) : ViewModel() {

    private val _thread: MutableStateFlow<Thread?> = MutableStateFlow(null)
    val thread: StateFlow<Thread?> = _thread.asStateFlow()

    private val _messages: MutableStateFlow<List<Message>> = MutableStateFlow(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _errorMessage: MutableStateFlow<String?> = MutableStateFlow(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isRefreshing: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * `true` while a pull-to-refresh round-trip is in flight. Bound to the
     * `PullToRefreshBox` indicator; flips back to `false` in a `finally` so the wheel always
     * retracts, even on a transport failure.
     */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Resolved once on construction — re-enter the screen to pick up a privacy change. */
    val isAnonymous: Boolean = getForumAnonymity()

    private var currentThreadId: Long = 0L

    /** Loads the thread header, streams its messages, and refreshes them from the network. */
    fun load(threadId: Long) {
        currentThreadId = threadId
        viewModelScope.launch { _thread.value = getThread(threadId) }
        viewModelScope.launch {
            refreshMessages(threadId).onFailure { _errorMessage.value = resolveError(it) }
        }
        viewModelScope.launch {
            observeMessages(threadId).collect { list -> _messages.value = list }
        }
    }

    /**
     * Pull-to-refresh handler for the thread screen. Re-fetches `GET /api/v1/threads/{id}`,
     * updating the header from the returned [Thread] while the nested replies flow back into
     * [messages] via the observed cache stream. No-ops until a thread has been loaded; the
     * indicator flag drops in `finally` so the wheel always retracts.
     */
    fun refresh() {
        if (currentThreadId == 0L || _isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                refreshThread(currentThreadId)
                    .onSuccess { _thread.value = it }
                    .onFailure { _errorMessage.value = resolveError(it) }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /** Posts [content] as a reply to the current thread. No-ops on blank input. */
    fun sendMessage(content: String) {
        if (content.isBlank() || currentThreadId == 0L) return
        viewModelScope.launch {
            postMessage(currentThreadId, content)
                .onSuccess {
                    // The reply itself streams into `messages` via observeMessages(threadId) once
                    // the store caches it. Here we only bump the one-shot-loaded header so the
                    // thread's reply counter reflects the new message instantly on this screen —
                    // the store bumps the same count in the shared cache for the feed.
                    _thread.value = _thread.value?.let { current ->
                        current.copy(message_count = (current.message_count ?: 0) + 1)
                    }
                }
                .onFailure { _errorMessage.value = resolveError(it) }
        }
    }

    /** Clears the surfaced error after the UI has shown it. */
    fun consumeError() {
        _errorMessage.value = null
    }

    private fun resolveError(throwable: Throwable): String = when (throwable) {
        is BadRequestException -> throwable.response.primaryFieldError() ?: GENERIC_ERROR
        else -> throwable.message ?: GENERIC_ERROR
    }

    companion object {
        private const val GENERIC_ERROR: String = "Something went wrong"

        /** Factory that assembles the use cases from the application service locator. */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SoftWorkApplication
                val locator = app.serviceLocator
                val store = locator.forumStore
                return ThreadViewModel(
                    getThread = GetThreadUseCase(store),
                    observeMessages = ObserveThreadMessagesUseCase(store),
                    refreshMessages = RefreshThreadMessagesUseCase(store),
                    refreshThread = RefreshThreadUseCase(store),
                    postMessage = PostMessageUseCase(store, locator.sharedPrefsManager),
                    getForumAnonymity = GetForumAnonymityUseCase(locator.sharedPrefsManager),
                ) as T
            }
        }
    }
}
