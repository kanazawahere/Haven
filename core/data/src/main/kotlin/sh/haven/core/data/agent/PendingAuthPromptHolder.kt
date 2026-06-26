package sh.haven.core.data.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A connect attempt that stalled waiting for a password or key passphrase the
 * user must supply via Haven's in-app fallback dialog.
 *
 * [requiresPassphrase] is true when the dialog is specifically asking for an
 * encrypted SSH key's passphrase (the assigned key failed to decrypt — #292),
 * false when it's a host / account password.
 */
data class PendingAuthPrompt(
    val profileId: String,
    val label: String,
    val requiresPassphrase: Boolean,
)

/**
 * App-singleton mirror of [ConnectionsViewModel]'s `_passwordFallback` dialog
 * state so the MCP agent can observe it (`get_pending_auth_prompt`) and answer
 * it (`answer_auth_prompt`) without a human tap — closing the gap where an
 * auth-fallback prompt was invisible and unanswerable over MCP.
 *
 * ConnectionsViewModel writes here whenever the fallback dialog appears or is
 * dismissed; McpTools reads the current value.
 */
@Singleton
class PendingAuthPromptHolder @Inject constructor() {
    private val _prompt = MutableStateFlow<PendingAuthPrompt?>(null)
    val prompt: StateFlow<PendingAuthPrompt?> = _prompt.asStateFlow()

    fun set(prompt: PendingAuthPrompt?) {
        _prompt.value = prompt
    }
}
