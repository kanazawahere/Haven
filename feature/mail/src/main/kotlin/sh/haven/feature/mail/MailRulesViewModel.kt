package sh.haven.feature.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.haven.core.data.db.entities.MailRule
import sh.haven.core.data.db.entities.MailRuleFiring
import sh.haven.core.data.db.entities.MailRulePendingAction
import sh.haven.core.data.mailrule.MailRulePendingRunner
import sh.haven.core.data.mailrule.MailWatchPoker
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.MailRuleRepository
import javax.inject.Inject

/**
 * Drives the Mail Rules management UI (Phase 5). The backend (persistence, engine, MCP tools)
 * already ships; this gives a human the list / create-edit / enable-disable / delete surface
 * rules previously only had via the agent's MCP tools, plus the master automation toggle, a
 * firing-history log, and the pending destructive-action approvals queue.
 *
 * Editing is curated: [MailRuleCurated] gates which rules the form can build, so an
 * agent-authored advanced rule opens read-only and is never corrupted on save.
 */
@HiltViewModel
class MailRulesViewModel @Inject constructor(
    private val ruleRepository: MailRuleRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val connectionRepository: ConnectionRepository,
    private val pendingRunner: MailRulePendingRunner,
    private val watchPoker: MailWatchPoker,
) : ViewModel() {

    /** One connected-or-configured email account the picker offers as a rule scope. */
    data class EmailAccount(val profileId: String, val label: String)

    val rules: StateFlow<List<MailRule>> = ruleRepository.observeRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val masterEnabled: StateFlow<Boolean> = preferencesRepository.mailAutomationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val recentFirings: StateFlow<List<MailRuleFiring>> = ruleRepository.observeRecentFirings(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingActions: StateFlow<List<MailRulePendingAction>> = ruleRepository.observePendingActions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Every configured email account, for the editor's scope picker + list labelling. */
    val emailAccounts: StateFlow<List<EmailAccount>> = connectionRepository.observeAll()
        .map { profiles -> profiles.filter { it.isEmail }.map { EmailAccount(it.id, it.label) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** One-shot snackbar text (save/approve results, errors); the screen consumes it. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() = _message.update { null }

    /** A human label for a rule's `accountProfileId` ("Any account" when null / unknown id). */
    fun accountLabel(profileId: String?): String {
        if (profileId == null) return "Any account"
        return emailAccounts.value.firstOrNull { it.profileId == profileId }?.label ?: "Specific account"
    }

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setMailAutomationEnabled(enabled) }
    }

    /**
     * Upsert a rule. A new rule (id not in the current list) is appended at the end of the
     * evaluation order; an edit keeps its [MailRule.orderIndex]. Pokes the watch loop so a
     * just-saved rule is evaluated promptly instead of on the next 3-minute cycle.
     */
    fun saveRule(rule: MailRule) {
        viewModelScope.launch {
            val isNew = rules.value.none { it.id == rule.id }
            val toSave = if (isNew) {
                rule.copy(orderIndex = (rules.value.maxOfOrNull { it.orderIndex } ?: -1) + 1)
            } else {
                rule
            }
            ruleRepository.saveRule(toSave)
            watchPoker.pokeNow()
            _message.update { "Rule saved" }
        }
    }

    fun toggleEnabled(rule: MailRule) {
        viewModelScope.launch {
            ruleRepository.saveRule(rule.copy(enabled = !rule.enabled))
            watchPoker.pokeNow()
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch {
            ruleRepository.deleteRule(id)
            _message.update { "Rule deleted" }
        }
    }

    /** Run a queued destructive action (approve). Reports the result via the snackbar. */
    fun approvePending(pending: MailRulePendingAction) {
        viewModelScope.launch {
            val ok = runCatching { pendingRunner.runApproved(pending) }.getOrDefault(false)
            _message.update { if (ok) "Action approved" else "Action failed — left in the queue" }
        }
    }

    /** Discard a queued destructive action without running it (reject). */
    fun rejectPending(id: String) {
        viewModelScope.launch {
            ruleRepository.deletePendingAction(id)
            _message.update { "Action rejected" }
        }
    }

    fun isCurated(rule: MailRule): Boolean = MailRuleCurated.isCurated(rule)
}
