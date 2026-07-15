package sh.haven.core.btserial

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BtSerialSessionManager"

/**
 * Manages Bluetooth-serial terminal sessions (#406), mirroring
 * [sh.haven.core.et.EtSessionManager]: register → connect (open RFCOMM) →
 * create terminal → detach/reattach/remove.
 *
 * The RFCOMM connect is the only blocking part and runs on IO in
 * [createTerminalSession]; everything else is registry bookkeeping.
 */
@Singleton
class BtSerialSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val deviceAddress: String = "",
        val secure: Boolean = true,
        val deviceName: String? = null,
        /** Open RFCOMM link, set by [connectSession], consumed by [createTerminalSession]. */
        val transport: BtSerialTransport? = null,
        val session: BtSerialSession? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    // Built on first real connect only (tests override openTransport before that,
    // so no Bluetooth is touched under test).
    private val connector by lazy { AndroidBtSerialConnector(context) }

    /**
     * Opens the RFCOMM transport. Test seam — the real path dials a Bluetooth
     * socket via [AndroidBtSerialConnector]; tests substitute piped streams.
     */
    internal var openTransport: (address: String, secure: Boolean) -> BtSerialTransport =
        { address, secure -> connector.connect(address, secure) }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "btserial-session-io").apply { isDaemon = true }
    }

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED || it.status == SessionState.Status.CONNECTING
        }

    /** Register a new session (status CONNECTING). Returns the generated sessionId. */
    fun registerSession(
        profileId: String,
        label: String,
        deviceAddress: String,
        secure: Boolean = true,
    ): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
                deviceAddress = deviceAddress,
                secure = secure,
            ))
        }
        return sessionId
    }

    /**
     * Open the RFCOMM link (blocking, on IO). Sets status CONNECTED and stashes
     * the transport for [createTerminalSession] to wrap. Throws on failure
     * (status → ERROR). Mirrors EtSessionManager.connectSession.
     */
    suspend fun connectSession(sessionId: String) {
        val state = _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")
        if (state.transport != null || state.session != null) return
        if (state.deviceAddress.isEmpty()) throw IllegalStateException("No device address for $sessionId")

        val transport = try {
            withContext(Dispatchers.IO) { openTransport(state.deviceAddress, state.secure) }
        } catch (e: Exception) {
            Log.e(TAG, "RFCOMM connect failed for ${state.label}: ${e.message}")
            updateStatus(sessionId, SessionState.Status.ERROR)
            throw e
        }
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.CONNECTED,
                deviceName = transport.remoteName,
                transport = transport,
            ))
        }
    }

    /**
     * Wrap the already-open transport ([connectSession] first) in a terminal
     * session, wiring device output to [onDataReceived]. Non-suspend so the
     * terminal syncSessions loop can call it. Idempotent.
     */
    fun createTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
    ): BtSerialSession? {
        val state = _sessions.value[sessionId] ?: return null
        state.session?.let { return it }
        val transport = state.transport ?: return null

        val session = BtSerialSession(
            sessionId = sessionId,
            transport = transport,
            onDataReceived = onDataReceived,
            onDisconnected = { updateStatus(it, SessionState.Status.DISCONNECTED) },
        )
        session.start()

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(session = session))
        }
        return session
    }

    fun isReadyForTerminal(sessionId: String): Boolean {
        val s = _sessions.value[sessionId] ?: return false
        return s.status == SessionState.Status.CONNECTED && s.session == null && s.transport != null
    }

    /** Detach the terminal without dropping the link. */
    fun detachTerminalSession(sessionId: String) {
        val state = _sessions.value[sessionId] ?: return
        state.session?.detach()
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(session = null))
        }
    }

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = status))
        }
    }

    /** Send [text] as UTF-8 to the session (sendInput contract, #366). */
    fun sendInput(sessionId: String, text: String) {
        val session = _sessions.value[sessionId]?.session
            ?: throw IllegalStateException("No live BT-serial session: $sessionId")
        session.sendInput(text.toByteArray(Charsets.UTF_8))
    }

    // Close the terminal session if one exists (it owns the transport), else the
    // bare transport opened by connectSession before a terminal was created.
    private fun tearDown(state: SessionState) {
        runCatching { state.session?.close() ?: state.transport?.close() }
            .onFailure { Log.e(TAG, "tearDown failed for ${state.sessionId}", it) }
    }

    fun removeSession(sessionId: String) {
        val state = _sessions.value[sessionId] ?: return
        _sessions.update { it - sessionId }
        ioExecutor.execute { tearDown(state) }
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val toRemove = _sessions.value.values.filter { it.profileId == profileId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        ioExecutor.execute { toRemove.forEach { tearDown(it) } }
    }

    fun disconnectAll() {
        val snapshot = _sessions.value.values.toList()
        _sessions.update { emptyMap() }
        ioExecutor.execute { snapshot.forEach { tearDown(it) } }
    }

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }
}
