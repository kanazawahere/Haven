package sh.haven.core.ssh

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-4 seam (#58): the factory is the single construction point for a
 * profile's live [SshConnection]. In this phase both engines still build a
 * JSch connection — a sshlib-toggled profile runs its shell/exec/forwards
 * over JSch and only routes SFTP to sshlib (phase 1). This pins that
 * invariant so a future whole-connection sshlib impl is a deliberate change
 * to the SSHLIB branch, not an accident.
 */
class SshConnectionFactoryTest {

    @Test
    fun `both engines currently build a JSch connection`() {
        assertTrue(SshConnectionFactory.create(SshEngine.JSCH) is SshClient)
        assertTrue(SshConnectionFactory.create(SshEngine.SSHLIB) is SshClient)
    }

    @Test
    fun `config overload resolves the engine from the HavenSshEngine directive`() {
        val jschCfg = ConnectionConfig(host = "h", username = "u")
        val sshlibCfg = ConnectionConfig(
            host = "h", username = "u",
            sshOptions = ConnectionConfig.parseSshOptions("HavenSshEngine sshlib"),
        )
        // Both concrete today; the point is the overload reads the directive
        // without throwing and would branch here once sshlib whole-connection lands.
        assertTrue(SshConnectionFactory.create(jschCfg) is SshConnection)
        assertTrue(SshConnectionFactory.create(sshlibCfg) is SshConnection)
        assertSame(SshEngine.JSCH, jschCfg.sshEngine)
        assertSame(SshEngine.SSHLIB, sshlibCfg.sshEngine)
    }

    @Test
    fun `sshEngineFromOptionsText matches the config-derived engine`() {
        assertSame(SshEngine.SSHLIB, sshEngineFromOptionsText("HavenSshEngine sshlib"))
        assertSame(SshEngine.JSCH, sshEngineFromOptionsText("ServerAliveInterval 30"))
        assertSame(SshEngine.JSCH, sshEngineFromOptionsText(null))
    }
}
