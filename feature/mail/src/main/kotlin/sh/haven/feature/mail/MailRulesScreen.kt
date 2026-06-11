package sh.haven.feature.mail

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import sh.haven.core.data.db.entities.MailRule
import sh.haven.core.data.db.entities.MailRuleFiring
import sh.haven.core.data.db.entities.MailRulePendingAction
import sh.haven.core.data.mailrule.ImapFilterOp
import sh.haven.core.data.mailrule.MailCondition
import sh.haven.core.data.mailrule.MailCriteria
import sh.haven.core.data.mailrule.MailRuleAction
import sh.haven.core.data.mailrule.MailRuleJson
import sh.haven.core.data.mailrule.MatchCombinator
import sh.haven.core.data.mailrule.StringOp

/**
 * Mail Rules management UI (Phase 5), shown as a full-screen overlay from the Mail screen's
 * overflow menu. Three tabs — Rules, History, Approvals — plus a curated rule editor. An
 * agent-authored rule that uses features outside the curated subset opens read-only so it is
 * never corrupted (see [MailRuleCurated]).
 */

/** What the screen is showing besides the tabs: nothing, the editor, or a read-only view. */
private sealed interface Sub {
    data class Edit(val rule: MailRule?) : Sub
    data class ReadOnly(val rule: MailRule) : Sub
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailRulesScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MailRulesViewModel = hiltViewModel(),
) {
    val rules by viewModel.rules.collectAsState()
    val masterEnabled by viewModel.masterEnabled.collectAsState()
    val firings by viewModel.recentFirings.collectAsState()
    val pending by viewModel.pendingActions.collectAsState()
    val accounts by viewModel.emailAccounts.collectAsState()
    val message by viewModel.message.collectAsState()

    var sub by remember { mutableStateOf<Sub?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    val snackHost = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackHost.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    BackHandler(enabled = true) {
        if (sub != null) sub = null else onClose()
    }

    when (val s = sub) {
        is Sub.Edit -> RuleEditor(
            initial = s.rule,
            accounts = accounts,
            onCancel = { sub = null },
            onSave = { viewModel.saveRule(it); sub = null },
            modifier = modifier,
        )
        is Sub.ReadOnly -> RuleReadOnly(
            rule = s.rule,
            accountLabel = viewModel.accountLabel(s.rule.accountProfileId),
            onClose = { sub = null },
            modifier = modifier,
        )
        null -> Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.mail_rules_title)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.mail_rules_close))
                        }
                    },
                )
            },
            floatingActionButton = {
                if (selectedTab == 0) {
                    FloatingActionButton(onClick = { sub = Sub.Edit(null) }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.mail_rules_add))
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackHost) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                MasterToggle(enabled = masterEnabled, onChange = viewModel::setMasterEnabled)
                val pendingLabel = stringResource(R.string.mail_rules_tab_pending).let {
                    if (pending.isEmpty()) it else "$it (${pending.size})"
                }
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.mail_rules_tab_rules)) })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.mail_rules_tab_history)) })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 },
                        text = { Text(pendingLabel) })
                }
                when (selectedTab) {
                    0 -> RulesList(
                        rules = rules,
                        isCurated = viewModel::isCurated,
                        accountLabel = viewModel::accountLabel,
                        onToggle = viewModel::toggleEnabled,
                        onDelete = viewModel::deleteRule,
                        onOpen = { rule -> sub = if (viewModel.isCurated(rule)) Sub.Edit(rule) else Sub.ReadOnly(rule) },
                    )
                    1 -> FiringHistory(firings)
                    2 -> PendingApprovals(
                        pending = pending,
                        onApprove = viewModel::approvePending,
                        onReject = { viewModel.rejectPending(it.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MasterToggle(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.mail_rules_master), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.mail_rules_master_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }
        if (!enabled) {
            Text(
                stringResource(R.string.mail_rules_master_off_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ---- Rules list -----------------------------------------------------------

@Composable
private fun RulesList(
    rules: List<MailRule>,
    isCurated: (MailRule) -> Boolean,
    accountLabel: (String?) -> String,
    onToggle: (MailRule) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (MailRule) -> Unit,
) {
    if (rules.isEmpty()) {
        CenterMessage(stringResource(R.string.mail_rules_empty))
        return
    }
    var confirmDelete by remember { mutableStateOf<MailRule?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rules, key = { it.id }) { rule ->
            RuleRow(
                rule = rule,
                curated = isCurated(rule),
                scope = accountLabel(rule.accountProfileId),
                onToggle = { onToggle(rule) },
                onDelete = { confirmDelete = rule },
                onClick = { onOpen(rule) },
            )
            HorizontalDivider()
        }
    }
    confirmDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.mail_rules_delete_confirm_title)) },
            text = { Text(stringResource(R.string.mail_rules_delete_confirm_message, rule.name)) },
            confirmButton = {
                TextButton(onClick = { onDelete(rule.id); confirmDelete = null }) {
                    Text(stringResource(R.string.mail_rules_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.mail_rules_cancel)) }
            },
        )
    }
}

@Composable
private fun RuleRow(
    rule: MailRule,
    curated: Boolean,
    scope: String,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Resolve @Composable strings before building the line (they can't be called
        // inside the plain buildString lambda).
        val lastFired = lastFiredText(rule.lastFiredAt)
        val agentLabel = stringResource(R.string.mail_rules_agent_made)
        val subLine = buildString {
            append(scope)
            append(" · ")
            append(lastFired)
            if (!curated) {
                append(" · ")
                append(agentLabel)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(rule.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                ruleSummary(rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subLine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.mail_rules_delete))
        }
    }
}

// ---- Curated editor -------------------------------------------------------

private enum class CondType { FROM, TO, SUBJECT, IS_UNREAD, HAS_ATTACHMENT }

private class ConditionDraft(
    type: CondType,
    op: StringOp = StringOp.CONTAINS,
    value: String = "",
    boolValue: Boolean = true,
) {
    var type by mutableStateOf(type)
    var op by mutableStateOf(op)
    var value by mutableStateOf(value)
    var boolValue by mutableStateOf(boolValue)
}

private enum class ActType { MARK_READ, MARK_UNREAD, FLAG, UNFLAG, MOVE, DELETE, NOTIFY }

private class ActionDraft(
    type: ActType = ActType.MARK_READ,
    destFolder: String = "",
    title: String = "",
    body: String = "",
) {
    var type by mutableStateOf(type)
    var destFolder by mutableStateOf(destFolder)
    var title by mutableStateOf(title)
    var body by mutableStateOf(body)
}

private fun ConditionDraft.toCondition(): MailCondition = when (type) {
    CondType.FROM -> MailCondition.From(op, value)
    CondType.TO -> MailCondition.To(op, value)
    CondType.SUBJECT -> MailCondition.Subject(op, value)
    CondType.IS_UNREAD -> MailCondition.IsUnread(boolValue)
    CondType.HAS_ATTACHMENT -> MailCondition.HasAttachment(boolValue)
}

private fun MailCondition.toDraft(): ConditionDraft = when (this) {
    is MailCondition.From -> ConditionDraft(CondType.FROM, op, value)
    is MailCondition.To -> ConditionDraft(CondType.TO, op, value)
    is MailCondition.Subject -> ConditionDraft(CondType.SUBJECT, op, value)
    is MailCondition.IsUnread -> ConditionDraft(CondType.IS_UNREAD, boolValue = value)
    is MailCondition.HasAttachment -> ConditionDraft(CondType.HAS_ATTACHMENT, boolValue = value)
    else -> ConditionDraft(CondType.SUBJECT)
}

private fun ActionDraft.toAction(): MailRuleAction = when (type) {
    ActType.MARK_READ -> MailRuleAction.ImapFilter(ImapFilterOp.MARK_READ)
    ActType.MARK_UNREAD -> MailRuleAction.ImapFilter(ImapFilterOp.MARK_UNREAD)
    ActType.FLAG -> MailRuleAction.ImapFilter(ImapFilterOp.SET_FLAGGED)
    ActType.UNFLAG -> MailRuleAction.ImapFilter(ImapFilterOp.UNSET_FLAGGED)
    ActType.MOVE -> MailRuleAction.ImapFilter(ImapFilterOp.MOVE, destFolder.trim().ifBlank { null })
    ActType.DELETE -> MailRuleAction.ImapFilter(ImapFilterOp.DELETE)
    ActType.NOTIFY -> MailRuleAction.Notify(title, body)
}

private fun MailRuleAction.toActionDraft(): ActionDraft = when (this) {
    is MailRuleAction.ImapFilter -> when (op) {
        ImapFilterOp.MARK_READ -> ActionDraft(ActType.MARK_READ)
        ImapFilterOp.MARK_UNREAD -> ActionDraft(ActType.MARK_UNREAD)
        ImapFilterOp.SET_FLAGGED -> ActionDraft(ActType.FLAG)
        ImapFilterOp.UNSET_FLAGGED -> ActionDraft(ActType.UNFLAG)
        ImapFilterOp.MOVE -> ActionDraft(ActType.MOVE, destFolder = destFolderId.orEmpty())
        ImapFilterOp.DELETE -> ActionDraft(ActType.DELETE)
    }
    is MailRuleAction.Notify -> ActionDraft(ActType.NOTIFY, title = titleTemplate, body = bodyTemplate)
    else -> ActionDraft(ActType.MARK_READ)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditor(
    initial: MailRule?,
    accounts: List<MailRulesViewModel.EmailAccount>,
    onCancel: () -> Unit,
    onSave: (MailRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    val parsedCriteria = remember(initial) {
        initial?.let { runCatching { MailRuleJson.criteriaFromJson(it.criteriaJson) }.getOrNull() }
    }
    val parsedActions = remember(initial) {
        initial?.let { runCatching { MailRuleJson.actionsFromJson(it.actionsJson) }.getOrNull() } ?: emptyList()
    }

    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var accountId by remember(initial) { mutableStateOf(initial?.accountProfileId) }
    var folder by remember(initial) { mutableStateOf(initial?.folderId ?: "INBOX") }
    var combinator by remember(initial) { mutableStateOf(parsedCriteria?.combinator ?: MatchCombinator.ALL) }
    var stopOnMatch by remember(initial) { mutableStateOf(initial?.stopOnMatch ?: false) }
    var notifyOnFire by remember(initial) { mutableStateOf(initial?.notifyOnFire ?: false) }
    val conditions = remember(initial) {
        mutableStateListOf<ConditionDraft>().apply {
            val seed = parsedCriteria?.conditions?.map { it.toDraft() } ?: listOf(ConditionDraft(CondType.SUBJECT))
            addAll(seed)
        }
    }
    val actions = remember(initial) {
        mutableStateListOf<ActionDraft>().apply {
            val seed = parsedActions.map { it.toActionDraft() }.ifEmpty { listOf(ActionDraft(ActType.MARK_READ)) }
            addAll(seed)
        }
    }
    var error by remember { mutableStateOf<String?>(null) }

    val errName = stringResource(R.string.mail_rules_err_name)
    val errConditions = stringResource(R.string.mail_rules_err_conditions)
    val errActions = stringResource(R.string.mail_rules_err_actions)
    val errMove = stringResource(R.string.mail_rules_err_move_folder)

    fun trySave() {
        when {
            name.isBlank() -> { error = errName; return }
            conditions.isEmpty() -> { error = errConditions; return }
            actions.isEmpty() -> { error = errActions; return }
            actions.any { it.type == ActType.MOVE && it.destFolder.isBlank() } -> { error = errMove; return }
        }
        val criteria = MailCriteria(combinator, conditions.map { it.toCondition() })
        val builtActions = actions.map { it.toAction() }
        val rule = (initial ?: MailRule(name = name, criteriaJson = "", actionsJson = "")).copy(
            name = name.trim(),
            accountProfileId = accountId,
            folderId = folder.trim().ifBlank { "INBOX" },
            stopOnMatch = stopOnMatch,
            notifyOnFire = notifyOnFire,
            criteriaJson = MailRuleJson.criteriaToJson(criteria),
            actionsJson = MailRuleJson.actionsToJson(builtActions),
        )
        onSave(rule)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (initial == null) R.string.mail_rules_new_title else R.string.mail_rules_edit_title))
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.mail_rules_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = { trySave() }) { Text(stringResource(R.string.mail_rules_save)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .imePadding().verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it; error = null },
                label = { Text(stringResource(R.string.mail_rules_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            AccountPicker(accounts, accountId) { accountId = it }
            OutlinedTextField(
                value = folder, onValueChange = { folder = it },
                label = { Text(stringResource(R.string.mail_rules_folder)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            SectionHeader(stringResource(R.string.mail_rules_match_label))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = combinator == MatchCombinator.ALL, onClick = { combinator = MatchCombinator.ALL },
                    label = { Text(stringResource(R.string.mail_rules_match_all)) })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = combinator == MatchCombinator.ANY, onClick = { combinator = MatchCombinator.ANY },
                    label = { Text(stringResource(R.string.mail_rules_match_any)) })
            }

            SectionHeader(stringResource(R.string.mail_rules_conditions))
            conditions.forEachIndexed { idx, c ->
                ConditionEditor(c, onRemove = { conditions.removeAt(idx) })
            }
            TextButton(onClick = { conditions.add(ConditionDraft(CondType.SUBJECT)) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.mail_rules_add_condition))
            }

            SectionHeader(stringResource(R.string.mail_rules_actions))
            actions.forEachIndexed { idx, a ->
                ActionEditor(a, onRemove = { actions.removeAt(idx) })
            }
            TextButton(onClick = { actions.add(ActionDraft(ActType.MARK_READ)) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.mail_rules_add_action))
            }

            ToggleRow(stringResource(R.string.mail_rules_stop_on_match), stopOnMatch) { stopOnMatch = it }
            ToggleRow(stringResource(R.string.mail_rules_notify_on_fire), notifyOnFire) { notifyOnFire = it }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountPicker(
    accounts: List<MailRulesViewModel.EmailAccount>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val anyLabel = stringResource(R.string.mail_rules_any_account)
    val label = accounts.firstOrNull { it.profileId == selected }?.label ?: anyLabel
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = label, onValueChange = {}, readOnly = true,
            label = { Text(stringResource(R.string.mail_rules_account)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(anyLabel) }, onClick = { onSelect(null); expanded = false })
            accounts.forEach { acc ->
                DropdownMenuItem(
                    text = { Text(acc.label) }, onClick = { onSelect(acc.profileId); expanded = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConditionEditor(c: ConditionDraft, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EnumDropdown(
                    options = CondType.entries,
                    selected = c.type,
                    labelOf = { condTypeLabel(it) },
                    modifier = Modifier.weight(1f),
                ) { c.type = it }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.mail_rules_remove))
                }
            }
            when (c.type) {
                CondType.IS_UNREAD, CondType.HAS_ATTACHMENT -> {
                    Row {
                        FilterChip(selected = c.boolValue, onClick = { c.boolValue = true },
                            label = { Text(stringResource(R.string.mail_rules_is_true)) })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = !c.boolValue, onClick = { c.boolValue = false },
                            label = { Text(stringResource(R.string.mail_rules_is_false)) })
                    }
                }
                else -> {
                    EnumDropdown(
                        options = StringOp.entries,
                        selected = c.op,
                        labelOf = { opLabel(it) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { c.op = it }
                    OutlinedTextField(
                        value = c.value, onValueChange = { c.value = it },
                        label = { Text(stringResource(R.string.mail_rules_value)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionEditor(a: ActionDraft, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EnumDropdown(
                    options = ActType.entries,
                    selected = a.type,
                    labelOf = { actTypeLabel(it) },
                    modifier = Modifier.weight(1f),
                ) { a.type = it }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.mail_rules_remove))
                }
            }
            when (a.type) {
                ActType.MOVE -> OutlinedTextField(
                    value = a.destFolder, onValueChange = { a.destFolder = it },
                    label = { Text(stringResource(R.string.mail_rules_act_dest_folder)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                ActType.NOTIFY -> {
                    OutlinedTextField(
                        value = a.title, onValueChange = { a.title = it },
                        label = { Text(stringResource(R.string.mail_rules_act_notify_title)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = a.body, onValueChange = { a.body = it },
                        label = { Text(stringResource(R.string.mail_rules_act_notify_body)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                else -> {}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    options: List<T>,
    selected: T,
    labelOf: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = labelOf(selected), onValueChange = {}, readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(labelOf(opt)) }, onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}

// ---- Read-only (advanced rule) -------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleReadOnly(rule: MailRule, accountLabel: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val criteria = remember(rule) { runCatching { MailRuleJson.criteriaFromJson(rule.criteriaJson) }.getOrNull() }
    val actions = remember(rule) { runCatching { MailRuleJson.actionsFromJson(rule.actionsJson) }.getOrDefault(emptyList()) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(rule.name) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.mail_rules_close))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.mail_rules_readonly_note),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            )
            SectionHeader(stringResource(R.string.mail_rules_scope))
            Text("$accountLabel · ${rule.folderId}", style = MaterialTheme.typography.bodyMedium)
            SectionHeader(stringResource(R.string.mail_rules_when))
            criteria?.conditions?.forEach { Text("• ${conditionText(it)}", style = MaterialTheme.typography.bodyMedium) }
            SectionHeader(stringResource(R.string.mail_rules_do))
            actions.forEach { Text("• ${actionText(it)}", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

// ---- History --------------------------------------------------------------

@Composable
private fun FiringHistory(firings: List<MailRuleFiring>) {
    if (firings.isEmpty()) {
        CenterMessage(stringResource(R.string.mail_rules_history_empty))
        return
    }
    val noSubject = stringResource(R.string.mail_rules_no_subject)
    val skipped = stringResource(R.string.mail_rules_history_skipped)
    val uidReset = stringResource(R.string.mail_rules_history_uidreset)
    LazyColumn(Modifier.fillMaxSize()) {
        items(firings, key = { it.id }) { f ->
            val headline = when (f.kind) {
                MailRuleFiring.KIND_POLL_SKIPPED -> skipped
                MailRuleFiring.KIND_UIDVALIDITY_RESET -> uidReset
                else -> f.messageSubject?.ifBlank { noSubject } ?: noSubject
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(headline, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = buildString {
                    append(DateUtils.getRelativeTimeSpanString(f.firedAt).toString())
                    f.outcomeSummary?.takeIf { it.isNotBlank() }?.let { append(" · "); append(it) }
                }
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
    }
}

// ---- Pending approvals ----------------------------------------------------

@Composable
private fun PendingApprovals(
    pending: List<MailRulePendingAction>,
    onApprove: (MailRulePendingAction) -> Unit,
    onReject: (MailRulePendingAction) -> Unit,
) {
    if (pending.isEmpty()) {
        CenterMessage(stringResource(R.string.mail_rules_pending_empty))
        return
    }
    val noSubject = stringResource(R.string.mail_rules_no_subject)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.mail_rules_pending_desc),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(pending, key = { it.id }) { p ->
            val action = remember(p.id) { MailRuleJson.actionsFromJson(p.actionJson).firstOrNull() }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    p.messageSubject?.ifBlank { noSubject } ?: noSubject,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    action?.let { actionText(it) } ?: stringResource(R.string.mail_rules_act_advanced),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = { onApprove(p) }) { Text(stringResource(R.string.mail_rules_approve)) }
                    TextButton(onClick = { onReject(p) }) { Text(stringResource(R.string.mail_rules_reject)) }
                }
            }
            HorizontalDivider()
        }
    }
}

// ---- Small shared pieces --------------------------------------------------

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- Localized summaries (built in @Composable so stringResource is usable) ----

@Composable
private fun lastFiredText(lastFiredAt: Long?): String =
    if (lastFiredAt == null) stringResource(R.string.mail_rules_never_fired)
    else stringResource(R.string.mail_rules_last_fired, DateUtils.getRelativeTimeSpanString(lastFiredAt).toString())

@Composable
private fun condTypeLabel(t: CondType): String = stringResource(
    when (t) {
        CondType.FROM -> R.string.mail_rules_cond_from
        CondType.TO -> R.string.mail_rules_cond_to
        CondType.SUBJECT -> R.string.mail_rules_cond_subject
        CondType.IS_UNREAD -> R.string.mail_rules_cond_unread
        CondType.HAS_ATTACHMENT -> R.string.mail_rules_cond_has_attachment
    },
)

@Composable
private fun actTypeLabel(t: ActType): String = stringResource(
    when (t) {
        ActType.MARK_READ -> R.string.mail_rules_act_mark_read
        ActType.MARK_UNREAD -> R.string.mail_rules_act_mark_unread
        ActType.FLAG -> R.string.mail_rules_act_flag
        ActType.UNFLAG -> R.string.mail_rules_act_unflag
        ActType.MOVE -> R.string.mail_rules_act_move
        ActType.DELETE -> R.string.mail_rules_act_delete
        ActType.NOTIFY -> R.string.mail_rules_act_notify
    },
)

@Composable
private fun opLabel(op: StringOp): String = stringResource(
    when (op) {
        StringOp.CONTAINS -> R.string.mail_rules_op_contains
        StringOp.EQUALS -> R.string.mail_rules_op_equals
        StringOp.REGEX -> R.string.mail_rules_op_regex
        StringOp.GLOB -> R.string.mail_rules_op_glob
    },
)

@Composable
private fun conditionText(c: MailCondition): String {
    val field = when (c) {
        is MailCondition.From -> stringResource(R.string.mail_rules_cond_from)
        is MailCondition.To -> stringResource(R.string.mail_rules_cond_to)
        is MailCondition.Subject -> stringResource(R.string.mail_rules_cond_subject)
        is MailCondition.IsUnread -> stringResource(R.string.mail_rules_cond_unread)
        is MailCondition.HasAttachment -> stringResource(R.string.mail_rules_cond_has_attachment)
        is MailCondition.Body -> "Body"
        is MailCondition.AttachmentName -> "Attachment name"
        is MailCondition.AttachmentMime -> "Attachment type"
        is MailCondition.Header -> c.name
    }
    return when (c) {
        is MailCondition.From -> "$field ${opLabel(c.op)} \"${c.value}\""
        is MailCondition.To -> "$field ${opLabel(c.op)} \"${c.value}\""
        is MailCondition.Subject -> "$field ${opLabel(c.op)} \"${c.value}\""
        is MailCondition.Body -> "$field ${opLabel(c.op)} \"${c.value}\""
        is MailCondition.AttachmentName -> "$field ${opLabel(c.op)} \"${c.value}\""
        is MailCondition.AttachmentMime -> "$field ${opLabel(c.op)} \"${c.value}\""
        is MailCondition.Header -> "$field ${opLabel(c.op)} \"${c.value}\""
        is MailCondition.IsUnread -> "$field ${boolText(c.value)}"
        is MailCondition.HasAttachment -> "$field ${boolText(c.value)}"
    }
}

@Composable
private fun boolText(v: Boolean): String =
    if (v) stringResource(R.string.mail_rules_is_true) else stringResource(R.string.mail_rules_is_false)

@Composable
private fun actionText(a: MailRuleAction): String = when (a) {
    is MailRuleAction.ImapFilter -> when (a.op) {
        ImapFilterOp.MARK_READ -> stringResource(R.string.mail_rules_act_mark_read)
        ImapFilterOp.MARK_UNREAD -> stringResource(R.string.mail_rules_act_mark_unread)
        ImapFilterOp.SET_FLAGGED -> stringResource(R.string.mail_rules_act_flag)
        ImapFilterOp.UNSET_FLAGGED -> stringResource(R.string.mail_rules_act_unflag)
        ImapFilterOp.MOVE -> "${stringResource(R.string.mail_rules_act_move)}: ${a.destFolderId.orEmpty()}"
        ImapFilterOp.DELETE -> stringResource(R.string.mail_rules_act_delete)
    }
    is MailRuleAction.Notify -> "${stringResource(R.string.mail_rules_act_notify)}: ${a.titleTemplate}"
    is MailRuleAction.SaveAttachments -> "Save attachments → ${a.destDir}"
    is MailRuleAction.RunCommand -> "Run: ${a.template}"
    is MailRuleAction.SendToAgent -> "Send to agent"
    is MailRuleAction.Forward -> "Forward → ${a.to.joinToString(", ")}"
    is MailRuleAction.InvokeMcpTool -> "Tool: ${a.toolName}"
}

@Composable
private fun ruleSummary(rule: MailRule): String {
    val criteria = remember(rule.criteriaJson) { runCatching { MailRuleJson.criteriaFromJson(rule.criteriaJson) }.getOrNull() }
    val actions = remember(rule.actionsJson) { runCatching { MailRuleJson.actionsFromJson(rule.actionsJson) }.getOrDefault(emptyList()) }
    val firstCond = criteria?.conditions?.firstOrNull()
    val firstAct = actions.firstOrNull()
    val condText = if (firstCond != null) conditionText(firstCond) else ""
    val actText = if (firstAct != null) actionText(firstAct) else ""
    val arrow = stringResource(R.string.mail_rules_arrow)
    val extraConds = (criteria?.conditions?.size ?: 0) - 1
    val extra = if (extraConds > 0) " " + stringResource(R.string.mail_rules_more_count, extraConds) else ""
    return "$condText$extra $arrow $actText"
}
