package me.raddatz.veyq.mobile.sharing

import io.ktor.client.statement.bodyAsText
import me.raddatz.veyq.mobile.api.apis.ModerationApi
import me.raddatz.veyq.mobile.api.apis.SharingApi
import me.raddatz.veyq.mobile.api.models.ReportSubmission
import me.raddatz.veyq.mobile.api.models.ReportSubmissionItemsInner
import me.raddatz.veyq.mobile.crypto.Context
import me.raddatz.veyq.mobile.crypto.Report
import me.raddatz.veyq.mobile.crypto.b64
import me.raddatz.veyq.mobile.crypto.unb64
import me.raddatz.veyq.mobile.navigation.NodeAt
import me.raddatz.veyq.mobile.navigation.ReportSheet
import me.raddatz.veyq.mobile.session.NodeMetaWrite
import me.raddatz.veyq.mobile.session.NodeReport
import me.raddatz.veyq.mobile.session.OpenNode
import me.raddatz.veyq.mobile.session.Tree

/**
 * A link's guest reports what the link shows to **the share's own operator** (ADR 0150 decision 6, ADR 0120 decision 6,
 * ADR 0093 § 1; the web's `core/report/` for a guest's access), sealed to the share instance's published moderation key
 * and sent through the link (`reportInShare`, its password token and write token). This instance's moderators never
 * receive it: a bookmark's *Report shared content* is this report, made in process instead of the web's `&report`.
 *
 * What is reported is what the guest sees, as the web's share page reports it:
 * - an **upload link** (`WRITE_ONLY`) sees no node, so its guest reports the link itself (`LINK`, `linkReportKeys`: its
 *   `K_share`, which opens no content, and the field keys of its name and note);
 * - a **viewing link** (`READ`, `READ_WRITE`) is reported from the views (`reportNodes`, `nodeReportKeys`): a node whole
 *   (`NODE`: its key, its name's field key — the link's `share-memory-name` at the root, whose own entry lies above the
 *   link — and its meta's) or, below the root, its name only (`NAME`); a medium of a Memory as an `ITEM` ([reportItem]).
 *   A `LINK` item of a viewing link the server refuses (`404 report_item_not_found`, #1033).
 */
object GuestReport {
    /**
     * The report sheet of what [view]'s page shows: an upload link's link, a viewing link's node on screen (at its root —
     * where `&report` and *Report shared content* land — the link's root whole, no name of its own to report apart).
     */
    fun sheet(view: GuestView): ReportSheet? {
        val access = view.access ?: return null
        if (access.role == GuestAccess.WRITE_ONLY) return linkSheet(access)
        return nodeSheet(view, view.page.value?.node?.nodeId ?: access.tree?.root?.id ?: return null)
    }

    /** Node [nodeId]'s report sheet on a viewing link (a level's row, the page on screen); null on an upload link or a node it does not serve. */
    fun nodeSheet(view: GuestView, nodeId: String): ReportSheet? {
        val access = view.access ?: return null
        if (access.role == GuestAccess.WRITE_ONLY) return null
        val tree = access.tree ?: return null
        val node = tree.node(nodeId) ?: return null
        return ReportSheet(NodeAt(node.scopeId, node.id, node.kind, node.name), object : ReportSheet.Door {
            override val origin: String get() = access.origin
            override suspend fun send(scope: NodeReport.Scope, category: String, reason: String, contact: String) =
                reportNode(access, nodeId, scope, category, reason, contact)
        }, offersName = nodeId != tree.root.id)
    }

    private fun linkSheet(access: GuestAccess) = ReportSheet(access.root, object : ReportSheet.Door {
        override val origin: String get() = access.origin
        override suspend fun send(scope: NodeReport.Scope, category: String, reason: String, contact: String) =
            report(access, category, reason, contact)
    }, offersName = false)

    /** Whether the link's instance takes reports (it publishes a moderation key; the web's `reportOffered`). */
    suspend fun offered(access: GuestAccess): Boolean = runCatching {
        val r = ModerationApi(access.origin, access.client.http).getModerationKey()
        r.success
    }.getOrDefault(false)

    /** The upload link reported in [category] for [reason], with [contact]; the server's receipt or why not. */
    suspend fun report(access: GuestAccess, category: String, reason: String, contact: String): NodeReport.Sent =
        submit(access, category, reason, contact, ReportSubmissionItemsInner.Kind.LINK, null) { linkKeys(access) }

    /** Node [nodeId] of a viewing link reported whole or by its name ([scope]); the server's receipt or why not. */
    suspend fun reportNode(access: GuestAccess, nodeId: String, scope: NodeReport.Scope, category: String, reason: String, contact: String): NodeReport.Sent {
        val node = access.tree?.node(nodeId) ?: return NodeReport.Sent(NodeReport.Outcome.NOT_FOUND)
        val kind = NodeReport.itemKind(scope)
        return submit(access, category, reason, contact, kind, nodeId) { nodeKeys(access, node, scope) }
    }

    /** Item [itemId] (a Memory's medium) of a viewing link reported with the [keys] its application hands over. */
    suspend fun reportItem(access: GuestAccess, itemId: String, keys: List<Report.Key>?, category: String, reason: String, contact: String): NodeReport.Sent {
        if (keys.isNullOrEmpty()) return NodeReport.Sent(NodeReport.Outcome.UNAVAILABLE)
        return submit(access, category, reason, contact, ReportSubmissionItemsInner.Kind.ITEM, itemId) { keys.map { Report.Key(it.role, it.ref, it.key.copyOf()) } }
    }

    /**
     * One item of [kind] (addressed by [id]; a link's by the link) sealed with the keys [keysOf] hands over — fresh copies,
     * zeroed once sealed — and sent through the link, the moderation epoch read anew once where it changed meanwhile.
     */
    private suspend fun submit(
        access: GuestAccess, category: String, reason: String, contact: String, kind: ReportSubmissionItemsInner.Kind, id: String?,
        keysOf: () -> List<Report.Key>?,
    ): NodeReport.Sent {
        val cat = ReportSubmission.Category.entries.firstOrNull { it.value == category } ?: return NodeReport.Sent(NodeReport.Outcome.FAILED)
        if (reason.isBlank()) return NodeReport.Sent(NodeReport.Outcome.FAILED)
        val suite = access.suite
        repeat(2) { attempt ->
            val published = runCatching {
                val r = ModerationApi(access.origin, access.client.http).getModerationKey()
                if (r.status == 404) null else if (r.success) r.body() else error("moderation_key ${r.status}")
            }.getOrElse { return NodeReport.Sent(NodeReport.Outcome.FAILED) } ?: return NodeReport.Sent(NodeReport.Outcome.MODERATION_OFF)
            val kem = unb64(published.kemPublicKey)
            val keys = keysOf() ?: return NodeReport.Sent(NodeReport.Outcome.UNAVAILABLE)
            val reportKey = Report.newKey(suite)
            val submission = try {
                val trimmed = contact.trim()
                val item = if (kind == ReportSubmissionItemsInner.Kind.LINK) Report.Item.Link(access.shareId) else Report.Item.Of(kind.value, id!!)
                ReportSubmission(
                    category = cat,
                    epoch = published.epoch,
                    sealedReport = b64(Report.seal(suite, published.epoch, kem, reportKey, reason)),
                    items = listOf(ReportSubmissionItemsInner(kind, b64(Report.sealShare(suite, reportKey, item, keys)), id)),
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
                    "report_keys_unavailable" -> NodeReport.Outcome.UNAVAILABLE
                    "report_item_not_found" -> NodeReport.Outcome.NOT_FOUND
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
        linkNameKey(access)?.let { keys += Report.Key("LINK_NAME", null, it) }
        head.share.sealedNote?.let { note ->
            runCatching { suite.fieldKeyOf(ShareResolve.SHARE_NOTE, head.kShare, unb64(note)) }.getOrNull()?.let { keys += Report.Key("LINK_NOTE", null, it) }
        }
        return keys
    }

    /** The link's name's field key (`share-memory-name` under `K_share`); null where it does not open. */
    private fun linkNameKey(access: GuestAccess): ByteArray? =
        runCatching { access.suite.fieldKeyOf(ShareResolve.SHARE_MEMORY_NAME, access.head.kShare, unb64(access.head.share.sealedName!!)) }.getOrNull()

    /**
     * The keys a guest's report of [node] hands over (`nodeReportKeys`, `nameReportKeys` for a guest): whole — its key
     * (a copy), its name's field key (the link's name at the root) and its meta's; its name only — that name's field key,
     * never at the root (whose name is the link's). Null where nothing opens.
     */
    internal fun nodeKeys(access: GuestAccess, node: OpenNode, scope: NodeReport.Scope): List<Report.Key>? {
        val suite = access.suite
        val tree = access.tree ?: return null
        val atRoot = node.id == tree.root.id
        val nameKey = if (atRoot) null else runCatching {
            val parent = tree.node(node.parentId!!)!!
            val entryKey = Tree.childEntryKey(suite, node.scopeId, parent, parent.key, node.shareIdentityId, node.wrappedIdentityKey)
            val context = Context("node-name", "scopeId" to node.scopeId, "nodeId" to node.id, "epoch" to node.scopeEpoch.toLong(), "placementRevision" to node.placementRevision)
            suite.fieldKeyOf(context, entryKey, unb64(node.encryptedName!!))
        }.getOrNull()
        if (scope == NodeReport.Scope.NAME) return nameKey?.let { listOf(Report.Key("NODE_NAME", null, it)) }
        val keys = mutableListOf(Report.Key("NODE", null, node.key.copyOf()))
        if (atRoot) linkNameKey(access)?.let { keys += Report.Key("LINK_NAME", null, it) }
        nameKey?.let { keys += Report.Key("NODE_NAME", null, it) }
        node.wrappedMeta?.let { meta ->
            runCatching { suite.fieldKeyOf(NodeMetaWrite.context(node.scopeId, node.id), node.key, unb64(meta)) }.getOrNull()?.let { keys += Report.Key("NODE_META", null, it) }
        }
        return keys
    }
}
