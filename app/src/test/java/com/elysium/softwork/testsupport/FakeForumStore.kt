package com.elysium.softwork.testsupport

import com.elysium.softwork.worker.forum.data.store.ForumStore
import com.elysium.softwork.worker.forum.domain.model.Asset
import com.elysium.softwork.worker.forum.domain.model.Category
import com.elysium.softwork.worker.forum.domain.model.Forum
import com.elysium.softwork.worker.forum.domain.model.Message
import com.elysium.softwork.worker.forum.domain.model.Thread
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory test double for [ForumStore].
 *
 * Backs the thread feed and per-thread message streams with [MutableStateFlow]s so tests can
 * drive emissions through [emitThreads] / [emitMessages]. Recording counters let assertions
 * verify the ViewModel forwarded [refreshThreads] / [postMessage] without spying on
 * `viewModelScope`. Every suspending member returns immediately so tests run on virtual time.
 */
open class FakeForumStore(initialThreads: List<Thread> = emptyList()) : ForumStore {

    private val _threads: MutableStateFlow<List<Thread>> = MutableStateFlow(initialThreads)
    private val _messages: MutableStateFlow<List<Message>> = MutableStateFlow(emptyList())

    /** Tally of [refreshThreads] invocations. */
    var refreshThreadsInvocations: Int = 0
        private set

    /** Tally of [postMessage] invocations. */
    var postMessageInvocations: Int = 0
        private set

    /** Result returned by the next [refreshThreads] call. Defaults to success. */
    var nextRefreshThreadsResult: Result<Unit> = Result.success(Unit)

    /** Tally of [refreshThread] (pull-to-refresh) invocations. */
    var refreshThreadInvocations: Int = 0
        private set

    /** Result returned by the next [createThread] call. Defaults to a stub thread. */
    var nextCreateThreadResult: Result<Thread> = Result.success(Thread(thread_id = 1L))

    /** Result returned by the next [refreshThread] call. Defaults to a stub thread. */
    var nextRefreshThreadResult: Result<Thread> = Result.success(Thread(thread_id = 1L))

    /** Result returned by the next [postMessage] call. Defaults to a stub message. */
    var nextPostMessageResult: Result<Message> = Result.success(Message(message_id = 1L))

    override fun observeThreads(): Flow<List<Thread>> = _threads

    override suspend fun refreshThreads(): Result<Unit> {
        refreshThreadsInvocations += 1
        return nextRefreshThreadsResult
    }

    override suspend fun getThread(threadId: Long): Thread? =
        _threads.value.firstOrNull { it.thread_id == threadId }

    override suspend fun refreshThread(threadId: Long): Result<Thread> {
        refreshThreadInvocations += 1
        return nextRefreshThreadResult
    }

    override fun observeMessages(threadId: Long): Flow<List<Message>> = _messages

    override suspend fun refreshMessages(threadId: Long): Result<Unit> = Result.success(Unit)

    override suspend fun createThread(thread: Thread): Result<Thread> = nextCreateThreadResult

    override suspend fun postMessage(message: Message): Result<Message> {
        postMessageInvocations += 1
        return nextPostMessageResult
    }

    /** Result returned by the next [getCompanyForum] call. Defaults to no forum. */
    var nextCompanyForumResult: Result<Forum?> = Result.success(null)

    /** Result returned by the next [createCategory] call. Defaults to a stub category. */
    var nextCreateCategoryResult: Result<Category> = Result.success(Category(category_id = 1L))

    /** Tally of [uploadAsset] invocations. */
    var uploadAssetInvocations: Int = 0
        private set

    /** Result returned by the next [uploadAsset] call. Defaults to a stub asset. */
    var nextUploadAssetResult: Result<Asset> = Result.success(Asset(asset_id = 1L))

    override suspend fun getCompanyForum(): Result<Forum?> = nextCompanyForumResult

    override suspend fun createCategory(category: Category): Result<Category> = nextCreateCategoryResult

    override suspend fun uploadAsset(
        messageId: Long,
        name: String,
        fileType: String,
        bytes: ByteArray,
    ): Result<Asset> {
        uploadAssetInvocations += 1
        return nextUploadAssetResult
    }

    /** Test-only emission helper for the thread feed. */
    fun emitThreads(threads: List<Thread>) {
        _threads.value = threads
    }

    /** Test-only emission helper for the message stream. */
    fun emitMessages(messages: List<Message>) {
        _messages.value = messages
    }
}
