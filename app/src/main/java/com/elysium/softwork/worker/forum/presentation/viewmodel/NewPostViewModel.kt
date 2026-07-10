package com.elysium.softwork.worker.forum.presentation.viewmodel

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.elysium.softwork.SoftWorkApplication
import com.elysium.softwork.shared.application.usecase.GetForumAnonymityUseCase
import com.elysium.softwork.shared.data.local.SharedPrefsManager
import com.elysium.softwork.shared.data.network.BadRequestException
import com.elysium.softwork.worker.forum.application.usecase.CreateThreadUseCase
import com.elysium.softwork.worker.forum.application.usecase.PostMessageUseCase
import com.elysium.softwork.worker.forum.application.usecase.UploadMessageAssetUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A locally-picked file captured before upload: display [name], MIME [mimeType], and the raw
 * [bytes] read from the `content://` uri. Held in memory only until the two-phase publish
 * consumes it.
 */
data class PickedAsset(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * UI state holder for the new-thread composer.
 *
 * The thread is created under a [category] chosen on the preceding `CategorySelectionScreen`
 * (passed in as a nav argument via [setCategory]). Publishing is a **two-phase** backend
 * pipeline:
 *  - **Phase A** — [CreateThreadUseCase] creates the thread under the category, then
 *    [PostMessageUseCase] seeds the first message (binding the author's `user_account_id`) and
 *    yields the server `message_id`.
 *  - **Phase B (conditional)** — when the worker attached a file, [UploadMessageAssetUseCase]
 *    uploads it as `multipart/form-data` against the freshly-created message id.
 *
 * A backend `400` is parsed via [BadRequestException] into [PublishState.Error].
 *
 * @param createThread creates the thread under the selected category.
 * @param postMessage seeds the thread's first message and returns its id.
 * @param uploadAsset uploads the optional attachment against the seeded message.
 * @param getForumAnonymity reads the persisted forum-anonymity flag.
 * @param assetReader resolves a picked uri to a [PickedAsset] (name/MIME/bytes); wired to the
 *   application `ContentResolver` by the factory.
 * @param companyIdProvider supplies the worker's `company_id`, bound as the thread's owning area.
 * @property maxBodyLength character limit enforced by the body input + the live counter.
 */
class NewPostViewModel(
    private val createThread: CreateThreadUseCase,
    private val postMessage: PostMessageUseCase,
    private val uploadAsset: UploadMessageAssetUseCase,
    getForumAnonymity: GetForumAnonymityUseCase,
    private val assetReader: (Uri) -> PickedAsset?,
    private val companyIdProvider: () -> Long?,
) : ViewModel() {

    val maxBodyLength: Int = MAX_BODY_LENGTH

    /** Snapshot of the in-progress draft. */
    data class FormState(
        val title: String = "",
        val content: String = "",
    ) {
        val isReadyToPublish: Boolean get() = title.isNotBlank()
    }

    /** Distinct outcome flags so the UI can react and reset. */
    sealed interface PublishState {
        data object Idle : PublishState
        data object Publishing : PublishState
        data object Published : PublishState
        data class Error(val message: String) : PublishState
    }

    private val _form: MutableStateFlow<FormState> = MutableStateFlow(FormState())
    val form: StateFlow<FormState> = _form.asStateFlow()

    private val _publishState: MutableStateFlow<PublishState> = MutableStateFlow(PublishState.Idle)
    val publishState: StateFlow<PublishState> = _publishState.asStateFlow()

    private val _pickedAsset: MutableStateFlow<PickedAsset?> = MutableStateFlow(null)

    private val _pickedFileName: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Display name of the currently attached file, or `null` when none is attached. */
    val pickedFileName: StateFlow<String?> = _pickedFileName.asStateFlow()

    /** Resolved once on construction — re-enter the screen to pick up a privacy change. */
    val isAnonymous: Boolean = getForumAnonymity()

    /** Target category id forwarded from the selection screen. */
    private var categoryId: Long? = null

    /** Binds the category chosen on the preceding screen. Idempotent per screen entry. */
    fun setCategory(id: Long) {
        categoryId = id.takeIf { it != 0L }
    }

    fun onTitleChange(value: String) {
        _form.value = _form.value.copy(title = value)
    }

    fun onContentChange(value: String) {
        if (value.length > maxBodyLength) return
        _form.value = _form.value.copy(content = value)
    }

    /**
     * Captures (or clears) the picked attachment. The bytes are read eagerly from [uri] via the
     * injected [assetReader] so the upload phase needs no `Context`; a `null` [uri] clears the
     * selection. A read failure leaves the attachment unset.
     */
    fun onFilePicked(uri: Uri?) {
        val picked: PickedAsset? = uri?.let(assetReader)
        _pickedAsset.value = picked
        _pickedFileName.value = picked?.name
    }

    /**
     * Runs the two-phase publish. Requires a non-blank title; no-ops while publishing. On thread
     * success the state advances to [PublishState.Published] even if the (best-effort) message or
     * attachment step fails — the thread itself exists.
     */
    fun publish() {
        val current = _form.value
        if (!current.isReadyToPublish) return
        if (_publishState.value is PublishState.Publishing) return

        _publishState.value = PublishState.Publishing
        val attachment: PickedAsset? = _pickedAsset.value
        viewModelScope.launch {
            // Phase A — create the thread under the selected category.
            val threadResult = createThread(
                title = current.title,
                categoryId = categoryId,
                areaCompanyId = companyIdProvider(),
            )
            _publishState.value = threadResult.fold(
                onSuccess = { thread ->
                    val threadId: Long = thread.thread_id
                    if (threadId != 0L && (current.content.isNotBlank() || attachment != null)) {
                        // Seed the first message; a non-blank body is required, so fall back to the
                        // file name when the worker only attached a file.
                        val body: String = current.content.ifBlank { attachment?.name.orEmpty() }
                        postMessage(threadId, body).onSuccess { message ->
                            // Phase B — attach the file to the newly-created message.
                            val messageId: Long? = message.message_id
                            if (attachment != null && messageId != null) {
                                uploadAsset(
                                    messageId = messageId,
                                    name = attachment.name,
                                    fileType = attachment.mimeType,
                                    bytes = attachment.bytes,
                                )
                            }
                        }
                    }
                    PublishState.Published
                },
                onFailure = { PublishState.Error(resolveError(it)) },
            )
        }
    }

    /** Reset the publishing flag once the host has consumed it. */
    fun consumePublishState() {
        _publishState.value = PublishState.Idle
    }

    private fun resolveError(throwable: Throwable): String = when (throwable) {
        is BadRequestException -> throwable.response.primaryFieldError() ?: GENERIC_ERROR
        else -> throwable.message ?: GENERIC_ERROR
    }

    companion object {
        private const val MAX_BODY_LENGTH: Int = 500
        private const val GENERIC_ERROR: String = "Could not publish the thread"

        /**
         * Resolves a picked `content://` [uri] into a [PickedAsset] via the [resolver]: the MIME
         * type, the display name (from `OpenableColumns`), and the file bytes. Returns `null` on
         * any read failure so a bad pick simply leaves no attachment.
         */
        private fun readPickedAsset(resolver: ContentResolver, uri: Uri): PickedAsset? =
            runCatching {
                val mimeType: String = resolver.getType(uri) ?: "application/octet-stream"
                val displayName: String = resolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: uri.lastPathSegment
                    ?: "attachment"
                val stream = resolver.openInputStream(uri) ?: return@runCatching null
                val bytes: ByteArray = stream.use { it.readBytes() }
                PickedAsset(name = displayName, mimeType = mimeType, bytes = bytes)
            }.getOrNull()

        /** Factory that assembles the use cases from the application service locator. */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as SoftWorkApplication
                val locator = app.serviceLocator
                return NewPostViewModel(
                    createThread = CreateThreadUseCase(locator.forumStore),
                    postMessage = PostMessageUseCase(locator.forumStore, locator.sharedPrefsManager),
                    uploadAsset = UploadMessageAssetUseCase(locator.forumStore),
                    getForumAnonymity = GetForumAnonymityUseCase(locator.sharedPrefsManager),
                    assetReader = { uri -> readPickedAsset(app.contentResolver, uri) },
                    companyIdProvider = {
                        locator.sharedPrefsManager
                            .getLong(SharedPrefsManager.KEY_COMPANY_ID)
                            .takeIf { it != SharedPrefsManager.DEFAULT_LONG }
                    },
                ) as T
            }
        }
    }
}
