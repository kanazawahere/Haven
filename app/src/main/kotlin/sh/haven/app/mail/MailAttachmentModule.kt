package sh.haven.app.mail

import android.content.Context
import android.net.Uri
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.mail.OutgoingAttachment
import sh.haven.feature.mail.AttachmentResolver
import sh.haven.feature.mail.DraftAttachment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-layer [AttachmentResolver]: reads a composer attachment's device SAF uri to
 * bytes at send time via the [Context.getContentResolver]. Lives in `app` (not
 * `feature/mail`) because it needs Android content plumbing; bound to the
 * `feature/mail` interface so [sh.haven.feature.mail.MailViewModel] stays clean.
 */
@Singleton
class DefaultAttachmentResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) : AttachmentResolver {

    override suspend fun resolve(attachment: DraftAttachment): OutgoingAttachment =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(attachment.uri)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Couldn't open ${attachment.displayName}")
            if (bytes.size > MAX_BYTES) {
                throw IllegalStateException(
                    "${attachment.displayName} is ${bytes.size / (1024 * 1024)} MiB — exceeds the ${MAX_BYTES / (1024 * 1024)} MiB limit",
                )
            }
            OutgoingAttachment(
                filename = attachment.displayName,
                mimeType = attachment.mimeType.ifBlank { "application/octet-stream" },
                bytes = bytes,
            )
        }

    companion object {
        /** Whole file is buffered in RAM (and again as a MIME part) — keep it to the SMTP norm. */
        private const val MAX_BYTES = 25L * 1024 * 1024
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MailAttachmentModule {
    @Binds
    abstract fun bindAttachmentResolver(impl: DefaultAttachmentResolver): AttachmentResolver
}
