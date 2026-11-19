package me.raddatz.veyq.mobile.sharing

import io.ktor.client.statement.bodyAsText
import me.raddatz.veyq.mobile.api.apis.ModerationApi
import me.raddatz.veyq.mobile.api.apis.SharingApi
import me.raddatz.veyq.mobile.api.models.ReportSubmission
import me.raddatz.veyq.mobile.api.models.ReportSubmissionItemsInner
import me.raddatz.veyq.mobile.crypto.Report
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.navigation.ReportSheet
import me.raddatz.veyq.mobile.session.NodeReport

/**
 * A link's guest reports what the link shows to **the share's own operator** (ADR 0150 decision 6, ADR 0120 decision 6;
 * the web's `report.ts` for a guest's access, `linkReportKeys`): the link itself — its `K_share`, which opens nothing
 * stored, and the field keys of its name and note — sealed to the share instance's published moderation key and sent
 * through the link (`reportInShare`, its password token and write token). This instance's moderators never receive it:
 * a bookmark's *Report shared content* is this report, made in process instead of the web's `&report`.
 */
object GuestReport {
    /** The report sheet of [view]'s link (the whole link: no name of its own to report apart). */
    fun sheet(view: GuestView): ReportSheet? {
        val access = view.access ?: return null
        return ReportSheet(access.root, object : ReportSheet.Door {
            override val origin: String get() = access.origin
            override suspend fun send(scope: NodeReport.Scope, category: String, reason: String, contact: String) =
                report(access, category, reason, contact)
        }, offersName = false)
    }

    /** The link reported in [category] for [reason], with [contact]; the server's receipt or why not. */
    suspend fun report(access: GuestAccess, category: String, reason: String, contact: String): NodeReport.Sent {
        val cat = ReportSubmission.Category.entries.firstOrNull { it.value == category } ?: return NodeReport.Sent(NodeReport.Outcome.FAILED)
        if (reason.isBlank()) return NodeReport.Sent(NodeReport.Outcome.FAILED)
        val suite = access.suite
        repeat(2) { attempt ->
            val published = runCatching {
                val r = ModerationApi(access.origin, access.client.http).getModerationKey()
                if (r.status == 404) null else if (r.success) r.body() else error("moderation_key ${r.status}")
            }.getOrElse { return NodeReport.Sent(NodeReport.Outcome.FAILED) } ?: return NodeReport.Sent(NodeReport.Outcome.MODERATION_OFF)
            val kem = unb64(published.kemPublicKey)
            val reportKey = Report.newKey(suite)
            val keys = linkKeys(access)
            val submission = try {
                val trimmed = contact.trim()
                ReportSubmission(
                    category = cat,
                    epoch = published.epoch,
                    sealedReport = b64(Report.seal(suite, published.epoch, kem, reportKey, reason)),
                    items = listOf(
                        ReportSubmissionItemsInner(
                            ReportSubmissionItemsInner.Kind.LINK, b64(Report.sealShare(suite, reportKey, Report.Item.Link(access.shareId), keys)), null,
                        ),
                    ),
                    sealedContact = trimmed.takeIf { it.isNotEmpty() }?.let { b64(Report.sealContact(suite, published.epoch, kem, it)) },
                    receiptAddress = trimmed.takeIf { it.isNotEmpty() },
                )
            } finally {
                reportKey.fill(0)
                keys.forEach { it.key.fill(0) }
            }
            val r = SharingApi(access.origin, access.client.http).reportInShare(access.shareId, submission, access.pwToken, access.writeToken())
            if (r.success) {
                val receipt = r.body()
                return NodeReport.Sent(NodeReport.Outcome.SENT, receipt.reportId.take(8), receipt.receivedAt)
            }
            val code = GuestBlobs.codeOf(runCatching { r.response.bodyAsText() }.getOrDefault(""))
            if (code == "moderation_epoch_changed" && attempt == 0) return@repeat
            return NodeReport.Sent(
                when (code) {
                    "too_many_open_reports" -> NodeReport.Outcome.TOO_MANY_OPEN
                    "rate_limited" -> NodeReport.Outcome.RATE_LIMITED
                    else -> if (r.status == 429) NodeReport.Outcome.RATE_LIMITED else NodeReport.Outcome.FAILED
                },
            )
        }
        return NodeReport.Sent(NodeReport.Outcome.FAILED)
    }

    /** The link's keys (`linkReportKeys`): `K_share` (a copy), and the field keys of its name and note where they open. */
    private fun linkKeys(access: GuestAccess): List<Report.Key> {
        val suite = access.suite
        val head = access.head
        val keys = mutableListOf(Report.Key("SHARE", null, head.kShare.copyOf()))
        runCatching { suite.fieldKeyOf(ShareResolve.SHARE_MEMORY_NAME, head.kShare, unb64(head.share.sealedName!!)) }.getOrNull()
            ?.let { keys += Report.Key("LINK_NAME", null, it) }
        head.share.sealedNote?.let { note ->
            runCatching { suite.fieldKeyOf(ShareResolve.SHARE_NOTE, head.kShare, unb64(note)) }.getOrNull()?.let { keys += Report.Key("LINK_NOTE", null, it) }
        }
        return keys
    }
}
