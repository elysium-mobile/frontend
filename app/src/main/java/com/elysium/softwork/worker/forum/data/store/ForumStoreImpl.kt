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
 * **Organizational isolation.** The feed refresh drives `GET /api/v1/forums` (whose
 * `ForumResponse` nests `categories → threads`) rather than the flat, cross-tenant
 * `GET /api/v1/threads`. Forums are filtered to the signed-in worker's company via
 * [companyIdProvider] (`SharedPrefsManager.KEY_COMPANY_ID`) **before** anything reaches the cache,
 * and the cache is then *replaced* (not merged) so no other organization's threads can linger.
 *
 * @param threadDao Room DAO for the cached threads.
 * @param messageDao Room DAO for the cached messages.
 * @param webService Retrofit contract for the forum endpoints.
 * @param gson deserializer for the structured `400` validation payload.
 * @param companyIdProvider supplies the signed-in worker's `company_id` (cached during the
 *   post-login `user_accounts` sync); read per refresh. `null` ⇒ no org resolved ⇒ empty feed.
 */
class ForumStoreImpl(
    private val threadDao: ThreadDao,
    private val messageDao: MessageDao,
    private val webService: ForumWebService,
    private val gson: Gson,
    private val companyIdProvider: () -> Long?,
) : ForumStore {

    override fun observeThreads(): Flow<List<Thread>> = threadDao.observeThreads()

    override suspend fun refreshThreads(): Result<Unit> = runCatching {
        val forums = unwrapList(webService.getForums())
        val companyId: Long? = companyIdProvider()
        // Company-scoped flatten: keep only forums owned by the worker's org, then descend
        // categories → threads. A null company (not yet synced) yields an empty feed rather than
        // leaking cross-tenant assets. `replaceAll` swaps the whole cache atomically so any
        // previously-cached foreign threads are purged.
        val orgThreads: List<Thread> = if (companyId == null) {
            emptyList()
        } else {
            forums.asSequence()
                .filter { it.company_id == companyId }
                .flatMap { forum -> forum.categories.orEmpty().asSequence() }
                .flatMap { category -> category.threads.orEmpty().asSequence() }
                .toList()
        }
        threadDao.replaceAll(orgThreads)
    }

    override suspend fun getThread(threadId: Long): Thread? = threadDao.getById(threadId)

    override suspend fun refreshThread(threadId: Long): Result<Thread> = runCatching {
        val thread = unwrap(webService.getThread(threadId))
        threadDao.upsert(thread)
        // The detail route nests the latest replies; cache them so observeMessages(threadId)
        // re-emits. Backfill thread_id defensively so the DAO's per-thread filter always matches.
        thread.message_responses
            ?.map { if (it.thread_id == null) it.copy(thread_id = threadId) else it }
            ?.takeIf { it.isNotEmpty() }
            ?.let { messageDao.upsertAll(it) }
        thread
    }

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
        // Live count sync: bump the owning thread's cached `message_count` so `observeThreads()`
        // re-emits and the feed's counter updates without a network re-fetch. The shared Room
        // cache is the single source both the feed (`ForumScreen`) and the per-thread message
        // stream (`ThreadScreen`) observe, so one write reconciles both viewports.
        val threadId: Long? = created.thread_id ?: message.thread_id
        if (threadId != null) {
            threadDao.getById(threadId)?.let { thread ->
                threadDao.upsert(thread.copy(message_count = (thread.message_count ?: 0) + 1))
            }
        }
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
