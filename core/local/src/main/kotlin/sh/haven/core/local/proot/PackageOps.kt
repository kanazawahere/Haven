package sh.haven.core.local.proot

/**
 * Package-manager strategy interface for the [PackageFamily] enum.
 *
 * A template string isn't enough on its own — different families
 * have distinct invocation conventions (apt's `DEBIAN_FRONTEND`,
 * pacman's `--noconfirm`, etc.) and distinct stdout heuristics for
 * detecting "the package install succeeded but printed warnings".
 * This interface captures both.
 *
 * Phase 1 ships [Apk] only. [Apt], [Pacman], [Xbps], [Nix] land in
 * Phases 2-5.
 */
interface PackageOps {
    /** Command that refreshes the local package index. */
    fun updateCmd(): String

    /** Command that installs [pkgs] and prints progress. */
    fun installCmd(pkgs: List<String>): String

    /** Command that removes [pkgs]. */
    fun removeCmd(pkgs: List<String>): String

    /**
     * Detect "install succeeded" from the combined stdout+stderr of
     * an install run. Used as a fallback when a file-existence
     * check is unreliable (e.g. marker-file DEs).
     */
    fun installSucceeded(output: String): Boolean

    companion object {
        fun forFamily(family: PackageFamily): PackageOps = when (family) {
            PackageFamily.APK -> Apk
            PackageFamily.APT -> error("APT support lands in Phase 2 — see issue #162")
            PackageFamily.PACMAN -> error("Pacman support lands in Phase 3 — see issue #162")
            PackageFamily.XBPS -> error("XBPS support lands in Phase 3 — see issue #162")
            PackageFamily.NIX -> error("Nix support lands in Phase 5 — see issue #162")
        }
    }
}

object Apk : PackageOps {
    override fun updateCmd(): String = "apk update"

    override fun installCmd(pkgs: List<String>): String =
        "apk add ${pkgs.joinToString(" ")}"

    override fun removeCmd(pkgs: List<String>): String =
        "apk del ${pkgs.joinToString(" ")}"

    override fun installSucceeded(output: String): Boolean =
        output.contains("OK:")
}
