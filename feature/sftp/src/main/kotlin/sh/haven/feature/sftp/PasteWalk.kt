package sh.haven.feature.sftp

/** One flattened leaf-file operation waiting to be persisted to the paste queue. */
internal data class PasteLeaf(
    val sourcePath: String,
    val sourceName: String,
    val size: Long,
    val destPath: String,
    val isTopLevel: Boolean,
)

/**
 * Walk clipboard [entries] into a flat list of leaf-file paste operations,
 * expanding directories depth-first in listing order via [list]. Each
 * [PasteLeaf.destPath] bakes in the directory prefix so the executor can
 * treat leaves atomically — mkdir-p the parent, then copy.
 *
 * Symlinks: a directory symlink the user directly selected (top level) is
 * followed — the browsing UI presented it as a navigable directory — but a
 * symlink discovered during the walk is emitted as a leaf rather than
 * recursed, so link cycles terminate and a linked tree is never copied
 * twice. Backends that don't report link metadata leave
 * [SftpEntry.isSymlink] false and walk as before.
 *
 * A [list] failure propagates (the paste surfaces the error); callers
 * needing best-effort counts catch per top-level entry.
 */
internal suspend fun walkPasteLeaves(
    entries: List<SftpEntry>,
    destRootPath: String,
    list: suspend (String) -> List<SftpEntry>,
): List<PasteLeaf> {
    val out = mutableListOf<PasteLeaf>()
    suspend fun walk(entry: SftpEntry, destPath: String, isTopLevel: Boolean) {
        val recurse = entry.isDirectory && (isTopLevel || !entry.isSymlink)
        if (!recurse) {
            out.add(PasteLeaf(entry.path, entry.name, entry.size, destPath, isTopLevel))
            return
        }
        for (child in list(entry.path)) {
            walk(child, destPath.trimEnd('/') + "/" + child.name, isTopLevel = false)
        }
    }
    for (entry in entries) {
        walk(entry, destRootPath.trimEnd('/') + "/" + entry.name, isTopLevel = true)
    }
    return out
}

/**
 * Windows-style unique-name candidate for the [i]-th rename attempt:
 * `dir/stem (i).ext`, with the ` (i)` inserted before the last extension.
 * Dotfiles (`.bashrc`) keep the suffix at the end rather than growing a
 * fake extension.
 */
internal fun uniqueNameCandidate(destPath: String, i: Int): String {
    val lastSlash = destPath.lastIndexOf('/')
    val dir = if (lastSlash >= 0) destPath.substring(0, lastSlash) else ""
    val name = if (lastSlash >= 0) destPath.substring(lastSlash + 1) else destPath
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    return if (dir.isEmpty()) "$stem ($i)$ext" else "$dir/$stem ($i)$ext"
}
