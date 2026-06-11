package sh.haven.feature.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.MailRule
import sh.haven.core.data.mailrule.ImapFilterOp
import sh.haven.core.data.mailrule.MailCondition
import sh.haven.core.data.mailrule.MailCriteria
import sh.haven.core.data.mailrule.MailRuleAction
import sh.haven.core.data.mailrule.MailRuleJson
import sh.haven.core.data.mailrule.MatchCombinator
import sh.haven.core.data.mailrule.StringOp

/**
 * Guards the safe-passthrough property: only rules the in-app editor can faithfully rebuild
 * are classified curated (editable); anything using an advanced condition/action is advanced
 * (opened read-only), so the human UI never re-serialises and drops an agent-authored part.
 */
class MailRuleCuratedTest {

    private fun rule(criteria: MailCriteria, actions: List<MailRuleAction>) = MailRule(
        name = "r",
        criteriaJson = MailRuleJson.criteriaToJson(criteria),
        actionsJson = MailRuleJson.actionsToJson(actions),
    )

    @Test
    fun `subject contains + mark read is curated`() {
        val r = rule(
            MailCriteria(MatchCombinator.ALL, listOf(MailCondition.Subject(StringOp.CONTAINS, "invoice"))),
            listOf(MailRuleAction.ImapFilter(ImapFilterOp.MARK_READ)),
        )
        assertTrue(MailRuleCurated.isCurated(r))
    }

    @Test
    fun `has-attachment + move is curated`() {
        val r = rule(
            MailCriteria(MatchCombinator.ANY, listOf(MailCondition.HasAttachment(true))),
            listOf(MailRuleAction.ImapFilter(ImapFilterOp.MOVE, "Archive")),
        )
        assertTrue(MailRuleCurated.isCurated(r))
    }

    @Test
    fun `notify action is curated`() {
        val r = rule(
            MailCriteria(MatchCombinator.ALL, listOf(MailCondition.From(StringOp.EQUALS, "boss@x.com"))),
            listOf(MailRuleAction.Notify("New mail", "{subject}")),
        )
        assertTrue(MailRuleCurated.isCurated(r))
    }

    @Test
    fun `body condition is advanced`() {
        val r = rule(
            MailCriteria(MatchCombinator.ALL, listOf(MailCondition.Body(StringOp.CONTAINS, "wire transfer"))),
            listOf(MailRuleAction.ImapFilter(ImapFilterOp.MARK_READ)),
        )
        assertFalse(MailRuleCurated.isCurated(r))
    }

    @Test
    fun `header condition is advanced`() {
        val r = rule(
            MailCriteria(MatchCombinator.ALL, listOf(MailCondition.Header("List-Id", StringOp.CONTAINS, "android"))),
            listOf(MailRuleAction.ImapFilter(ImapFilterOp.DELETE)),
        )
        assertFalse(MailRuleCurated.isCurated(r))
    }

    @Test
    fun `run-command action is advanced`() {
        val r = rule(
            MailCriteria(MatchCombinator.ALL, listOf(MailCondition.Subject(StringOp.CONTAINS, "deploy"))),
            listOf(MailRuleAction.RunCommand("echo {subject}")),
        )
        assertFalse(MailRuleCurated.isCurated(r))
    }

    @Test
    fun `invoke-mcp-tool action is advanced`() {
        val r = rule(
            MailCriteria(MatchCombinator.ALL, listOf(MailCondition.Subject(StringOp.CONTAINS, "x"))),
            listOf(MailRuleAction.InvokeMcpTool("send_to_agent", "{}")),
        )
        assertFalse(MailRuleCurated.isCurated(r))
    }

    @Test
    fun `empty actions is not curated`() {
        val r = rule(
            MailCriteria(MatchCombinator.ALL, listOf(MailCondition.Subject(StringOp.CONTAINS, "x"))),
            emptyList(),
        )
        assertFalse(MailRuleCurated.isCurated(r))
    }

    @Test
    fun `malformed json is not curated`() {
        val r = MailRule(name = "bad", criteriaJson = "{not json", actionsJson = "[")
        assertFalse(MailRuleCurated.isCurated(r))
    }
}
