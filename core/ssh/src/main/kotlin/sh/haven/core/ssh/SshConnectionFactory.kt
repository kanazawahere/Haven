package sh.haven.core.ssh

/**
 * The single place an [SshConnection] is constructed for a profile (#58,
 * phase 4). Centralising it here means the whole-connection engine choice
 * lands in one branch when sshlib gains shell/exec/forwarding (phase 5+),
 * instead of being scattered across the connect and reconnect paths.
 */
object SshConnectionFactory {

    fun create(engine: SshEngine): SshConnection = when (engine) {
        SshEngine.JSCH -> SshClient()
        // Whole-connection sshlib (shell/exec/port-forwarding over sshlib) is
        // phase 5+. Until then a sshlib-toggled profile's LIVE connection is
        // JSch; only its SFTP is routed to sshlib (SshlibSftpConnector, phase
        // 1). So both engines build a JSch connection here for now — this is
        // the branch that changes when the sshlib SshConnection impl exists.
        SshEngine.SSHLIB -> SshClient()
    }

    fun create(config: ConnectionConfig): SshConnection = create(config.sshEngine)
}
