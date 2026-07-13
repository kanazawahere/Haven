package sh.haven.feature.sftp.transport

/**
 * Shared `mkdir -p` walk for backends whose native mkdir creates a single
 * level and errors otherwise (SFTP's SSH_FX_FAILURE / SSH_FX_NO_SUCH_FILE,
 * smbj's STATUS_OBJECT_PATH_NOT_FOUND — #273). Tries each prefix
 * shallowest-first; a failed create is suppressed only when
 * [statIsDirectory] confirms the prefix already exists as a directory.
 * File collisions and genuine failures (permissions, dead session) rethrow
 * the original create error, satisfying the [FileBackend.mkdir] contract.
 */
internal suspend fun mkdirP(
    path: String,
    mkdir: suspend (String) -> Unit,
    statIsDirectory: suspend (String) -> Boolean,
) {
    val absolute = path.startsWith("/")
    var acc = ""
    for (p in path.split('/').filter { it.isNotEmpty() }) {
        acc = if (absolute || acc.isNotEmpty()) "$acc/$p" else p
        try {
            mkdir(acc)
        } catch (e: Exception) {
            val existsAsDir = try { statIsDirectory(acc) } catch (_: Exception) { false }
            if (!existsAsDir) throw e
        }
    }
}
