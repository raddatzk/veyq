package me.raddatz.veyq.core.moderation.statement

import me.raddatz.veyq.core.identity.VeyqUser
import me.raddatz.veyq.core.membership.MembershipEntity
import me.raddatz.veyq.core.moderation.block.BlockKind
import me.raddatz.veyq.core.moderation.cases.ResolvedStep
import me.raddatz.veyq.core.moderation.cases.Targets
import me.raddatz.veyq.core.moderation.hold.HeldRow
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import me.raddatz.veyq.core.sharing.ShareEntity
import jakarta.enterprise.context.ApplicationScoped

/** One recipient of a statement: a member's account or a guest identity, and why it is told. */
data class Recipient(val kind: RecipientKind, val id: String, val roles: Set<StatementRole>)

/**
 * Who a measure's statement of reasons reaches (ADR 0093 § 7, uc.moderation.object-to-a-measure step 2), from clear
 * metadata only: the creator of a blocked link or of the link a blocked guest came through, the uploaders the case's held
 * rows name (a blocked guest: that guest alone), and on a content block every owner of the space. A member whose account
 * is gone has nobody to tell and is left out; a guest is listed, though veyq tells it nothing — it has no account.
 */
@ApplicationScoped
class StatementRecipients {

    fun of(case: ModerationCaseEntity, step: ResolvedStep, known: Targets, held: List<HeldRow>?): List<Recipient> {
        val out = LinkedHashMap<String, Pair<RecipientKind, MutableSet<StatementRole>>>()
        fun add(kind: RecipientKind, id: String, role: StatementRole) {
            out.getOrPut(id) { kind to linkedSetOf() }.second += role
        }
        fun uploaders() = known.uploaders.forEach {
            add(if (it.isGuest) RecipientKind.GUEST else RecipientKind.ACCOUNT, it.id, StatementRole.UPLOADER)
        }
        when (step.kind) {
            BlockKind.SHARE -> {
                creatorOf(step.targetId, held)?.let { add(RecipientKind.ACCOUNT, it, StatementRole.SHARE_CREATOR) }
                uploaders()
            }
            BlockKind.CONTENT_NODE, BlockKind.CONTENT_ITEM -> {
                uploaders()
                MembershipEntity.list("scopeId = ?1 and role = ?2", case.scopeId, MembershipEntity.OWNER)
                    .forEach { add(RecipientKind.ACCOUNT, it.userId, StatementRole.SCOPE_OWNER) }
            }
            BlockKind.GUEST_IDENTITY -> {
                add(RecipientKind.GUEST, step.targetId, StatementRole.UPLOADER)
                step.shareId?.let { creatorOf(it, held) }?.let { add(RecipientKind.ACCOUNT, it, StatementRole.SHARE_CREATOR) }
            }
            else -> {}
        }
        val accounts = out.filterValues { it.first == RecipientKind.ACCOUNT }.keys.toList()
        val living = if (accounts.isEmpty()) emptySet() else VeyqUser.list("id in ?1", accounts).map { it.id }.toSet()
        return out.mapNotNull { (id, v) ->
            if (v.first == RecipientKind.ACCOUNT && id !in living) null else Recipient(v.first, id, v.second)
        }
    }

    companion object {
        /**
         * How many members one step can wrap its facts' key to (ADR 0093 § 7). The moderator's browser seals the facts
         * once and wraps only `K_facts` per member, sending every wrap in the one `takeModerationMeasure` body
         * (`factsKeyFor`, which must name exactly these members), under the server's request limit
         * (`quarkus.http.limits.max-body-size` is unset: Quarkus' 10,240 KiB = 10,485,760 bytes). A wrap is always
         * 1,181 bytes, 1,576 base64 characters; as a JSON entry with its account id, 1,650 bytes, whatever the facts'
         * length. Every other field of the body at its largest — the note, the log's facts and a deferral's reason at
         * 12,456 characters each, the facts sealed once at 10,964, 100 reporter notices at 254-character addresses — is
         * 80,812 bytes, so at most 6,306 wraps fit; 6,000 make a largest body of 9,980,811 bytes, about 500 KB under the
         * limit, and `StatementRecipientsBoundSliceTest` sends exactly that body.
         * The list is never cut to fit — a statement reaches every affected member — so past it a step with facts is
         * refused (`statement_recipients_too_many`). `ModerationStatementRecipients.accounts` and
         * `ModerationStatementInput.factsKeyFor` carry the same number.
         */
        const val MAX_SEALED = 6000
    }

    /** A link's creator: the live row's, or the held copy's once the link was deleted. */
    private fun creatorOf(shareId: String, held: List<HeldRow>?): String? =
        ShareEntity.findById(shareId)?.ownerAccount
            ?: held.orEmpty().firstOrNull { it.table == "veyq_share" && it.sourceId == shareId }?.get("owner_account")
}
