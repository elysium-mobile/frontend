package com.elysium.softwork.worker.forum.application.usecase

import com.elysium.softwork.worker.forum.data.store.ForumStore
import com.elysium.softwork.worker.forum.domain.model.Asset

/**
 * Uploads a file attachment for a freshly-created message (Phase B of the new-post pipeline).
 *
 * Pass-through to the store's multipart upload, which binds the controller-expected form fields
 * (`messageId`, `name`, `fileType`, `file`). Stateless; safe to share a single instance
 * process-wide.
 *
 * @param store forum data port that performs the multipart network call.
 */
class UploadMessageAssetUseCase(private val store: ForumStore) {

    /**
     * @param messageId the owning message's server id.
     * @param name the file's display name.
     * @param fileType the file's MIME type.
     * @param bytes the raw file content read from the picked `content://` uri.
     * @return [Result.success] with the stored [Asset] or [Result.failure] on error.
     */
    suspend operator fun invoke(
        messageId: Long,
        name: String,
        fileType: String,
        bytes: ByteArray,
    ): Result<Asset> = store.uploadAsset(messageId, name, fileType, bytes)
}
