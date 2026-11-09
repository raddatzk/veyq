package me.raddatz.veyq.mobile.crypto

/**
 * A report's three plaintexts and their seals (ADR 0093 §§ 1-2, 11; `@veyq/crypto`'s `report.ts`, held to
 * `v1.json#report_bundle` and the `report-share`, `report-link`, `moderation-report` and `moderation-report-contact`
 * seals). A report share hands the moderators the keys of one reported item under the report's fresh `K_report`; the
 * report's own part — `K_report`, the reason — is sealed to the moderation epoch's public half, the reporter's contact
 * apart from it, so the server can drop the contact alone. Texts are NFC; each is refused above its byte bound, never cut.
 */
object Report {
    const val KEY_BYTES = 32
    const val REASON_BYTES = 8000
    const val CONTACT_BYTES = 254
    const val CONTEXT_BYTES = 8000
    const val BUCKET_BYTES = 512
    const val MAX_KEYS_PER_SHARE = 256

    /** One key a report share hands over: its role (`NODE`, `NODE_NAME`, `NODE_META`, …), an optional item ref, the key. */
    class Key(val role: String, val ref: String?, val key: ByteArray)

    /** What a report share is addressed by: an item of a kind and id, or a link by its share id. */
    sealed interface Item {
        data class Of(val kind: String, val id: String) : Item
        data class Link(val shareId: String) : Item
    }

    class Invalid(detail: String) : IllegalArgumentException("report_invalid: $detail")

    private val ROLE = Regex("^[A-Z][A-Z0-9_]{0,15}$")
    private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private fun u32be(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    private fun lp(b: ByteArray) = u32be(b.size) + b

    /** u32be(n) ‖ (lp(role) ‖ lp(ref or empty) ‖ lp(key))…, 1 ≤ n ≤ 256. */
    fun encodeKeys(keys: List<Key>): ByteArray {
        if (keys.isEmpty() || keys.size > MAX_KEYS_PER_SHARE) throw Invalid("key count")
        var out = u32be(keys.size)
        for (k in keys) {
            if (!ROLE.matches(k.role)) throw Invalid("role")
            if (k.ref != null && !CANONICAL_UUID.matches(k.ref)) throw Invalid("ref")
            if (k.key.size != KEY_BYTES) throw Invalid("key")
            out += lp(utf8(k.role)) + lp(k.ref?.let(::utf8) ?: ByteArray(0)) + lp(k.key)
        }
        return out
    }

    /** lp(K_report) ‖ lp(reason) ‖ lp(context), zero-padded to the next multiple of 512 bytes; the reason not blank. */
    fun encodeBody(reportKey: ByteArray, reason: String, context: String): ByteArray {
        if (reportKey.size != KEY_BYTES) throw Invalid("report key")
        val r = utf8(reason)
        val c = utf8(context)
        if (reason.isBlank() || r.size > REASON_BYTES) throw Invalid("reason")
        if (c.size > CONTEXT_BYTES) throw Invalid("context")
        return padded(lp(reportKey) + lp(r) + lp(c))
    }

    /** lp(contact) zero-padded to 512 bytes; not blank, at most 254 bytes. */
    fun encodeContact(contact: String): ByteArray {
        val b = utf8(contact)
        if (contact.isBlank() || b.size > CONTACT_BYTES) throw Invalid("contact")
        return lp(b).copyOf(BUCKET_BYTES)
    }

    private fun padded(fields: ByteArray): ByteArray = fields.copyOf(((fields.size + BUCKET_BYTES - 1) / BUCKET_BYTES) * BUCKET_BYTES)

    /** A fresh `K_report`. */
    fun newKey(suite: V1): ByteArray = suite.p.randomBytes(KEY_BYTES)

    /** The report share of [item]: its [keys] under [reportKey], bound to the item's address. */
    fun sealShare(suite: V1, reportKey: ByteArray, item: Item, keys: List<Key>): ByteArray {
        val plaintext = encodeKeys(keys)
        val context = when (item) {
            is Item.Link -> Context("report-link", "shareId" to item.shareId)
            is Item.Of -> Context("report-share", "itemKind" to item.kind, "itemId" to item.id)
        }
        return suite.seal(context, reportKey, plaintext)
    }

    /** The report's own part to the moderation epoch's public half [kem]. */
    fun seal(suite: V1, epoch: Int, kem: ByteArray, reportKey: ByteArray, reason: String, context: String = ""): ByteArray =
        suite.sealTo(Context("moderation-report", "epoch" to epoch.toLong()), kem, encodeBody(reportKey, reason, context))

    /** The reporter's contact, sealed apart from the report's part to the same epoch's public half. */
    fun sealContact(suite: V1, epoch: Int, kem: ByteArray, contact: String): ByteArray =
        suite.sealTo(Context("moderation-report-contact", "epoch" to epoch.toLong()), kem, encodeContact(contact))
}
