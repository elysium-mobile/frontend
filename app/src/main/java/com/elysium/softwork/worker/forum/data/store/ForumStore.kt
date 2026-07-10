package com.elysium.softwork.worker.forum.data.store

import com.elysium.softwork.worker.forum.domain.model.Message
import com.elysium.softwork.worker.forum.domain.model.Thread
import kotlinx.coroutines.flow.Flow

/**
 * Forum data port. Offline-first: the UI observes the cached [Flow]s ([observeThreads],
 * [observeMessages]) from Room, and network calls ([refreshThreads], [refreshMessages],
 * [createThread], [postMessage]) are mutations that update the cache.
 *
 * Write operations return [Result] so callers get a single error channel — a `400 Bad
 * Request` surfaces as a [com.elysium.softwork.shared.data.network.BadRequestException].
 */
interface ForumStore {

    /** Live thread feed straight from the local cache. Never throws. */
    fun observeThreads(): Flow<List<Thread>>

    /** Pulls the latest threads from the server and upserts them into the cache. */
    suspend fun refreshThreads(): Result<Unit>

    /** One-shot cached lookup of a single thread by id. */
    suspend fun getThread(threadId: Long): Thread?

    /**
     * Pull-to-refresh entry point for the thread-detail screen. Fetches
     * `GET /api/v1/threads/{id}` (whose `ThreadResponse` nests the latest replies under
     * `message_responses`), upserts the thread header **and** the nested messages into the cache
     * so [observeMessages] re-emits, and returns the refreshed [Thread]. A `400` surfaces as a
     * [com.elysium.softwork.shared.data.network.BadRequestException].
     */
    suspend fun refreshThread(threadId: Long): Result<Thread>

    /** Live message stream for [threadId] from the local cache. */
    fun observeMessages(threadId: Long): Flow<List<Message>>

    /** Pulls the messages for [threadId] from the server and upserts them into the cache. */
    suspend fun refreshMessages(threadId: Long): Result<Unit>

    /** Creates a new thread; on success it is cached and [observeThreads] re-emits. */
    suspend fun createThread(thread: Thread): Result<Thread>

    /** Posts a new message; on success it is cached and [observeMessages] re-emits. */
    suspend fun postMessage(message: Message): Result<Message>
}
