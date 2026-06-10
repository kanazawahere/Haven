package sh.haven.feature.mail

import sh.haven.core.mail.OutgoingAttachment

/**
 * Resolves a composer [DraftAttachment] (a device SAF uri) into in-memory bytes
 * at send time. The concrete implementation lives in the app/composition layer
 * because it needs an Android `ContentResolver`; keeping the interface here lets
 * [MailViewModel] depend on it without `feature/mail` pulling in content
 * plumbing, and keeps [sh.haven.core.mail.OutgoingMail] free of Android types.
 */
interface AttachmentResolver {
    /** Read [attachment]'s bytes; throws if the source can't be read or exceeds the size cap. */
    suspend fun resolve(attachment: DraftAttachment): OutgoingAttachment
}
