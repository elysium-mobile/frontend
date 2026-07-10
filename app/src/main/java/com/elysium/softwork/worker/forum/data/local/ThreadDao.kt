package com.elysium.softwork.worker.forum.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.elysium.softwork.worker.forum.domain.model.Thread
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for cached forum [Thread]s. Reads expose a [Flow] so the feed auto-refreshes
 * whenever `ForumStoreImpl` upserts rows after a network refresh or a thread creation.
 */
@Dao
interface ThreadDao {

    /** All cached threads, newest activity first. The feed observes this stream directly. */
    @Query("SELECT * FROM threads ORDER BY thread_id DESC")
    fun observeThreads(): Flow<List<Thread>>

    /** One-shot lookup used by the thread-detail screen. */
    @Query("SELECT * FROM threads WHERE thread_id = :threadId LIMIT 1")
    suspend fun getById(threadId: Long): Thread?

    /** Replaces existing rows on PK conflict, so a single call handles insert and update. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(threads: List<Thread>)

    /** Convenience for a single upsert. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(thread: Thread)

    /** Wipes every cached thread. Used by [replaceAll] to enforce organizational isolation. */
    @Query("DELETE FROM threads")
    suspend fun clear()

    /**
     * Atomically replaces the entire thread cache with [threads] in one transaction, so the
     * observed feed emits exactly once (no empty-then-full flicker). Used by the company-scoped
     * refresh: the cache is made to hold **only** the signed-in worker's org threads, purging any
     * cross-tenant rows a previous unfiltered fetch may have left behind. An empty [threads] clears
     * the cache, which the feed renders as its empty state.
     */
    @Transaction
    suspend fun replaceAll(threads: List<Thread>) {
        clear()
        if (threads.isNotEmpty()) upsertAll(threads)
    }
}
