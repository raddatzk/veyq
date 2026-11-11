package me.raddatz.veyq.mobile.session

import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.raddatz.veyq.mobile.api.apis.ModerationApi
import me.raddatz.veyq.mobile.api.apis.ReportsApi
import me.raddatz.veyq.mobile.api.models.ReportSubmission
import me.raddatz.veyq.mobile.api.models.ReportSubmissionItemsInner
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Report
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64

/**
 * A member's report of a node to the moderators of its server, core's for every kind (the web's `core/report/`: `report.ts`
 * `submitReport`, `reportKeys.ts` `nodeReportKeys` and `nameReportKeys`; ADR 0093 — a legal duty, offered on every node).
 * The report hands the moderators the node's own key, its name's field key and its meta's field key (the whole node and
 * everything below it), or only the name's field key (its name and nothing else); each share sealed under the report's
 * fresh `K_report`, which goes with the reason to the moderation epoch's public half, the contact apart from it. Nothing is
 * readable to anyone else, the server included. A moderation epoch changed meanwhile is read again and sealed anew once.
 */
object NodeReport {
    /** The published moderation key, or null where this server takes no reports (`404`): then Report is not offered. */
    class Published(val epoch: Int, internal val kem: ByteArray)

    @Throws(Exception::class)
    suspend fun published(tenant: TenantSession): Published? {
        val account = tenant.account
        val r = account.respond { ModerationApi(tenant.base, account.http).getModerationKey() }
        if (r.status == 404) return null
        if (!r.success) throw IllegalStateException("moderation_key_${r.status}")
        val k = r.body()
        return Published(k.epoch, unb64(k.kemPublicKey))
    }

    /** A category a report names, with its words. */
    class Category(val token: String, val word: me.raddatz.veyq.mobile.words.Msg)

    const val CSAM = "CHILD_SEXUAL_ABUSE"

    /** The categories in the web's order (`REPORT_CATEGORIES`). */
    val CATEGORIES: List<Category> = listOf(
        Category(CSAM, me.raddatz.veyq.mobile.words.Msg.REPORT_CAT_CSAM),
        Category("TERRORIST_CONTENT", me.raddatz.veyq.mobile.words.Msg.REPORT_CAT_TERROR),
        Category("THREAT_TO_LIFE", me.raddatz.veyq.mobile.words.Msg.REPORT_CAT_THREAT),
        Category("ILLEGAL_CONTENT", me.raddatz.veyq.mobile.words.Msg.REPORT_CAT_ILLEGAL),
        Category("TERMS_BREACH", me.raddatz.veyq.mobile.words.Msg.REPORT_CAT_TERMS),
    )

    /** What the form says under a chosen category: the note on child sexual abuse material, else nothing. */
    fun note(category: String?): me.raddatz.veyq.mobile.words.Msg? = if (category == CSAM) me.raddatz.veyq.mobile.words.Msg.REPORT_CSAM_NOTE else null

    /** The reason's and the contact's room, in UTF-16 units as the web's `maxlength` counts them. */
    const val REASON_MAX = 2000
    const val CONTACT_MAX = 254

    /** A typed reason or contact cut to its room. */
    fun reason(text: String): String = text.take(REASON_MAX)
    fun contact(text: String): String = text.take(CONTACT_MAX)

    /** The privacy notice's passage on a reporter's contact (GDPR Art. 13 where a contact is taken). */
    const val PRIVACY_ANCHOR = "reporter-contact"

    /**
     * The server's privacy notice at its passage on a reporter's contact (the web's `privacyLink`): the page under the tenant's
     * [origin], whatever trailing slash it was written with.
     */
    fun privacyUrl(origin: String): String = "${origin.trimEnd('/')}/privacy#$PRIVACY_ANCHOR"

    /** Whether a report may be sent: a category chosen, a reason given, good faith confirmed (the web's form). */
    fun canSend(category: String?, reason: String, goodFaith: Boolean): Boolean = category != null && reason.isNotBlank() && goodFaith

    /** What is reported of the node: it and everything in it, or only its name. */
    enum class Scope { WHOLE, NAME }

    enum class Outcome {
        SENT,
        /** This server takes no reports. */
        MODERATION_OFF,
        /** Too many of this member's reports are still open. */
        TOO_MANY_OPEN,
        RATE_LIMITED,
        /** The node's keys do not open here any more. */
        UNAVAILABLE,
        /** The node is not there any more. */
        NOT_FOUND,
        FAILED,
    }

    /** What came of a report: on [Outcome.SENT] the reference (the first 8 of its id) and when it was received (ISO 8601). */
    class Sent(val outcome: Outcome, val reference: String? = null, val receivedAt: String? = null)

    /**
     * Reports node [nodeId] of [scopeId] in [category] (the contract's token) for [reason] (not blank), with [contact]
     * (blank: none) for the receipt and the decision.
     */
    @Throws(Exception::class)
    suspend fun report(tenant: TenantSession, scopeId: String, nodeId: String, scope: Scope, category: String, reason: String, contact: String): Sent {
        val account = tenant.account
        val suite = account.suite
        val cat = ReportSubmission.Category.entries.firstOrNull { it.value == category } ?: return Sent(Outcome.FAILED)
        if (reason.isBlank()) return Sent(Outcome.FAILED)
        val node = tenant.trees()[scopeId]?.firstOrNull { it.id == nodeId } ?: return Sent(Outcome.NOT_FOUND)
        val keys = keysOf(tenant, scopeId, node, scope) ?: return Sent(Outcome.UNAVAILABLE)
        val kind = if (scope == Scope.WHOLE) ReportSubmissionItemsInner.Kind.NODE else ReportSubmissionItemsInner.Kind.NAME
        repeat(2) { attempt ->
            val key = published(tenant) ?: return Sent(Outcome.MODERATION_OFF)
            val reportKey = Report.newKey(suite)
            val submission = try {
                val trimmed = contact.trim()
                ReportSubmission(
                    category = cat,
                    epoch = key.epoch,
                    sealedReport = b64(Report.seal(suite, key.epoch, key.kem, reportKey, reason)),
                    items = listOf(ReportSubmissionItemsInner(kind, b64(Report.sealShare(suite, reportKey, Report.Item.Of(kind.value, nodeId), keys)), nodeId)),
                    sealedContact = trimmed.takeIf { it.isNotEmpty() }?.let { b64(Report.sealContact(suite, key.epoch, key.kem, it)) },
                    receiptAddress = trimmed.takeIf { it.isNotEmpty() },
                )
            } finally {
                // K_report lives only as long as the sealing: the moderators open it from the sealed part
                reportKey.fill(0)
            }
            val r = account.respond { ReportsApi(tenant.base, account.http).reportInScope(scopeId, submission) }
            if (r.success) {
                val receipt = r.body()
                return Sent(Outcome.SENT, receipt.reportId.take(8), receipt.receivedAt)
            }
            val code = codeOf(runCatching { r.response.bodyAsText() }.getOrDefault(""))
            if (code == "moderation_epoch_changed" && attempt == 0) return@repeat
            return Sent(
                when (code) {
                    "too_many_open_reports" -> Outcome.TOO_MANY_OPEN
                    "rate_limited" -> Outcome.RATE_LIMITED
                    "report_keys_unavailable" -> Outcome.UNAVAILABLE
                    "report_item_not_found" -> Outcome.NOT_FOUND
                    else -> if (r.status == 429) Outcome.RATE_LIMITED else Outcome.FAILED
                },
            )
        }
        return Sent(Outcome.FAILED)
    }

    /**
     * The keys a report of [node] hands over (`nodeReportKeys`, `nameReportKeys`): for the whole node its own key, its
     * name's and its meta's field keys; for its name only that name's field key. Null when nothing opens here.
     */
    internal suspend fun keysOf(tenant: TenantSession, scopeId: String, node: OpenNode, scope: Scope): List<Report.Key>? {
        val suite = tenant.account.suite
        val entryKey = node.parentId?.let { p -> tenant.trees()[scopeId]?.firstOrNull { it.id == p }?.key } ?: tenant.scopeKey(scopeId)?.first
        val nameKey = runCatching {
            val context = Context("node-name", "scopeId" to scopeId, "nodeId" to node.id, "epoch" to node.scopeEpoch.toLong(), "placementRevision" to node.placementRevision)
            suite.fieldKeyOf(context, entryKey!!, unb64(node.encryptedName!!))
        }.getOrNull()
        if (scope == Scope.NAME) return nameKey?.let { listOf(Report.Key("NODE_NAME", null, it)) }
        val keys = mutableListOf(Report.Key("NODE", null, node.key))
        nameKey?.let { keys += Report.Key("NODE_NAME", null, it) }
        node.wrappedMeta?.let { meta ->
            runCatching { suite.fieldKeyOf(NodeMetaWrite.context(scopeId, node.id), node.key, unb64(meta)) }.getOrNull()?.let { keys += Report.Key("NODE_META", null, it) }
        }
        return keys
    }

    private fun codeOf(body: String): String? = runCatching { (Json.parseToJsonElement(body) as JsonObject)["code"]?.jsonPrimitive?.content }.getOrNull()
}
