package com.elysium.softwork.worker.forum.data.store

import com.elysium.softwork.shared.data.network.BadRequestException
import com.elysium.softwork.shared.data.network.BadRequestResponse
import com.elysium.softwork.worker.forum.data.local.MessageDao
import com.elysium.softwork.worker.forum.data.local.ThreadDao
import com.elysium.softwork.worker.forum.data.network.ForumWebService
import com.elysium.softwork.worker.forum.domain.model.Message
import com.elysium.softwork.worker.forum.domain.model.Thread
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import retrofit2.Response

/**
 * Offline-first [ForumStore] backed by the live FlowWork Spring Boot API + Room cache.
 *
 * No mock harness, no `SeedPosts`: [observeThreads] / [observeMessages] return the Room
 * [Flow]s directly so the UI always renders the cached snapshot; [refreshThreads] /
 * [refreshMessages] pull from the network and upsert. Writes ([createThread], [postMessage])
 * POST to the backend, cache the server-issued row, and surface a `400` as a
 * [BadRequestException].
 *
 * Messages are filtered client-side by `thread_id` because the backend exposes only the
 * unfiltered `GET /messages` list.
 *
 * @param threadDao Room DAO for the cached threads.
 * @param messageDao Room DAO for the cached messages.
 * @param webService Retrofit contract for the forum endpoints.
 * @param gson deserializer for the structured `400` validation payload.
 */
class ForumStoreImpl(
    private val threadDao: ThreadDao,
    private val messageDao: MessageDao,
    private val webService: ForumWebService,
    private val gson: Gson,
) : ForumStore {

    override fun observeThreads(): Flow<List<Thread>> = threadDao.observeThreads()

    override suspend fun refreshThreads(): Result<Unit> = runCatching {
        val threads = unwrapList(webService.getThreads())
        if (threads.isNotEmpty()) threadDao.upsertAll(threads)
    }

    override suspend fun getThread(threadId: Long): Thread? = threadDao.getById(threadId)

    override fun observeMessages(threadId: Long): Flow<List<Message>> =
        messageDao.observeForThread(threadId)

    override suspend fun refreshMessages(threadId: Long): Result<Unit> = runCatching {
        val messages = unwrapList(webService.getMessages())
            .filter { it.thread_id == threadId }
        if (messages.isNotEmpty()) messageDao.upsertAll(messages)
    }

    override suspend fun createThread(thread: Thread): Result<Thread> = runCatching {
        val created = unwrap(webService.createThread(thread))
        threadDao.upsert(created)
        created
    }

    override suspend fun postMessage(message: Message): Result<Message> = runCatching {
        val created = unwrap(webService.createMessage(message))
        messageDao.upsert(created)
        created
    }

    /** Unwraps a single-object [response]; a `400` becomes a [BadRequestException]. */
    private fun <T> unwrap(response: Response<T>): T {
        if (response.isSuccessful) {
            return response.body() ?: error("Empty response body")
        }
        throwTyped(response)
    }

    /** Unwraps a list [response], tolerating an empty body as an empty list. */
    private fun <T> unwrapList(response: Response<List<T>>): List<T> {
        if (response.isSuccessful) {
            return response.body().orEmpty()
        }
        throwTyped(response)
    }

    /**
     * Converts a non-2xx [response] into a typed failure: a `400` into a [BadRequestException]
     * carrying the parsed [BadRequestResponse], anything else into an [IllegalStateException].
     */
    private fun throwTyped(response: Response<*>): Nothing {
        val rawError: String? = runCatching { response.errorBody()?.string() }.getOrNull()
        if (response.code() == HTTP_BAD_REQUEST) {
            val parsed: BadRequestResponse = rawError
                ?.let { runCatching { gson.fromJson(it, BadRequestResponse::class.java) }.getOrNull() }
                ?: BadRequestResponse(message = rawError)
            throw BadRequestException(parsed)
        }
        error("HTTP ${response.code()} ${response.message().ifBlank { rawError ?: "request failed" }}")
    }

    private companion object {
        const val HTTP_BAD_REQUEST: Int = 400
    }
}
