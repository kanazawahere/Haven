package sh.haven.feature.mail

import sh.haven.core.data.db.entities.MailRule
import sh.haven.core.data.mailrule.MailCondition
import sh.haven.core.data.mailrule.MailRuleAction
import sh.haven.core.data.mailrule.MailRuleJson

/**
 * Decides whether a [MailRule] falls entirely within the subset the in-app editor can build,
 * so the UI can edit curated rules and open advanced (agent-authored) ones **read-only** —
 * never re-serialising, hence never dropping, a condition or action it can't render.
 *
 * Curated conditions: From / To / Subject / IsUnread / HasAttachment.
 * Curated actions: ImapFilter (mark read/unread, set/unset flag, move, delete) + Notify.
 * Everything else (Body/attachment-name/attachment-mime/Header conditions; SaveAttachments,
 * RunCommand, SendToAgent, Forward, InvokeMcpTool actions) is "advanced" → read-only in-app.
 */
object MailRuleCurated {

    fun isCuratedCondition(c: MailCondition): Boolean = when (c) {
        is MailCondition.From,
        is MailCondition.To,
        is MailCondition.Subject,
        is MailCondition.IsUnread,
        is MailCondition.HasAttachment,
        -> true
        is MailCondition.Body,
        is MailCondition.AttachmentName,
        is MailCondition.AttachmentMime,
        is MailCondition.Header,
        -> false
    }

    fun isCuratedAction(a: MailRuleAction): Boolean = when (a) {
        is MailRuleAction.ImapFilter,
        is MailRuleAction.Notify,
        -> true
        is MailRuleAction.SaveAttachments,
        is MailRuleAction.RunCommand,
        is MailRuleAction.SendToAgent,
        is MailRuleAction.Forward,
        is MailRuleAction.InvokeMcpTool,
        -> false
    }

    /**
     * True when every condition and action of [rule] is curated (and it has at least one
     * action — a rule the editor could not have produced otherwise). Malformed JSON → false
     * (treated as advanced/read-only, the safe default).
     */
    fun isCurated(rule: MailRule): Boolean = runCatching {
        val criteria = MailRuleJson.criteriaFromJson(rule.criteriaJson)
        val actions = MailRuleJson.actionsFromJson(rule.actionsJson)
        actions.isNotEmpty() &&
            criteria.conditions.all { isCuratedCondition(it) } &&
            actions.all { isCuratedAction(it) }
    }.getOrDefault(false)
}
