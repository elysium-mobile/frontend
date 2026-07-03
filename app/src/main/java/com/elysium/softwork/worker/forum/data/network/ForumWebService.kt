package com.elysium.softwork.worker.forum.data.network

import com.elysium.softwork.worker.forum.domain.model.Asset
import com.elysium.softwork.worker.forum.domain.model.Category
import com.elysium.softwork.worker.forum.domain.model.Forum
import com.elysium.softwork.worker.forum.domain.model.Message
import com.elysium.softwork.worker.forum.domain.model.Report
import com.elysium.softwork.worker.forum.domain.model.Thread
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * Retrofit contract for the live Worker-Forum endpoints of the FlowWork Spring Boot API.
 *
 * The annotation-free domain beans ([Forum], [Category], [Thread], [Message], [Asset],
 * [Report]) carry both request bodies and response payloads (the bean shortcut) — no DTOs.
 * All paths are **relative**; the host + `/` base lives in `BuildConfig.BACKEND_BASE_URL`
 * (resolved by `ApiClient`), and `AuthInterceptor` attaches the bearer token automatically.
 */
interface ForumWebService {

    // region Forums & Categories
    /** Lists every forum (with nested categories). */
    @GET("api/v1/forums")
    suspend fun getForums(): Response<List<Forum>>

    /** Lists every category (with nested threads). */
    @GET("api/v1/categories")
    suspend fun getCategories(): Response<List<Category>>
    // endregion

    // region Threads
    /** Lists every thread. Backs the offline-first feed cache. */
    @GET("api/v1/threads")
    suspend fun getThreads(): Response<List<Thread>>

    /** Fetches a single thread by its `thread_id`. */
    @GET("api/v1/threads/{id}")
    suspend fun getThread(@Path("id") id: Long): Response<Thread>

    /** Creates a new thread. */
    @POST("api/v1/threads")
    suspend fun createThread(@Body thread: Thread): Response<Thread>
    // endregion

    // region Messages
    /** Lists every message; filtered client-side by `thread_id` for a given thread. */
    @GET("api/v1/messages")
    suspend fun getMessages(): Response<List<Message>>

    /** Fetches a single message by its `message_id`. */
    @GET("api/v1/messages/{id}")
    suspend fun getMessage(@Path("id") id: Long): Response<Message>

    /** Posts a new message to a thread. */
    @POST("api/v1/messages")
    suspend fun createMessage(@Body message: Message): Response<Message>
    // endregion

    // region Assets & Reports
    /**
     * Uploads an attachment for a message. **Multipart exception:** unlike every other endpoint,
     * asset creation is `multipart/form-data` (the backend streams the binary to Cloudinary),
     * so the form-field names bypass Jackson's snake_case JSON strategy and stay camelCase
     * exactly as the controller declares them — `messageId`, `name`, `fileType`, `file`. The
     * server computes `url` and `file_size`; the JSON [Asset] response comes back snake_case.
     */
    @Multipart
    @POST("api/v1/assets")
    suspend fun createAsset(
        @Part("messageId") messageId: RequestBody,
        @Part("name") name: RequestBody,
        @Part("fileType") fileType: RequestBody,
        @Part file: MultipartBody.Part,
    ): Response<Asset>

    /** Submits a content/conduct report. */
    @POST("api/v1/reports")
    suspend fun createReport(@Body report: Report): Response<Report>

    /** Lists the reports submitted (used by the report-status screen). */
    @GET("api/v1/reports")
    suspend fun getReports(): Response<List<Report>>
    // endregion
}
