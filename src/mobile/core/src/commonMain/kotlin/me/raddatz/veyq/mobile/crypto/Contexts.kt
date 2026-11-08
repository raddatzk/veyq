package me.raddatz.veyq.mobile.crypto

/**
 * The typed seal contexts of the v1 suite, a port of `src/packages/crypto/src/seal.ts`: each purpose with its binding
 * fields in canonical order. A context is encoded `lp(utf8(purpose)) ‖ lp(field)…` in the table's order, an id as its
 * canonical lowercase UUID string, an integer as u64be, a role as its UTF-8 name. Pinned per purpose by the vectors'
 * `context_bytes`, so a table that drifted from the web's is a red conformance test, not a seal that fails to open.
 */
enum class FieldKind { ID, SHARE_ID, UINT, ROLE, OPTIONAL_ID, SCOPE_KIND, DIGEST, BYTES, PAIRING_CODE, OPERATION_ID }

typealias Schema = Map<String, List<Pair<String, FieldKind>>>

private fun s(vararg f: Pair<String, FieldKind>) = f.toList()
private val ID = FieldKind.ID
private val UINT = FieldKind.UINT

object Purposes {
    val SEAL: Schema = mapOf(
        "content-key" to s("scopeId" to ID, "nodeId" to ID, "epoch" to UINT, "placementRevision" to UINT),
        "blob-key" to s("scopeId" to ID, "ownerId" to ID, "blobId" to ID, "role" to FieldKind.ROLE),
        "share-key-recovery" to s("scopeId" to ID, "nodeId" to ID, "shareId" to FieldKind.SHARE_ID, "epoch" to UINT),
        "drop-private-key" to s("scopeId" to ID, "nodeId" to ID),
        "item-favorites" to s("scopeId" to ID, "nodeId" to ID),
        "favorites" to s("scopeId" to ID),
        "group-name" to s("scopeId" to ID, "nameEpoch" to UINT),
        "space-icon" to s("scopeId" to ID, "iconEpoch" to UINT),
        "identity-pins" to s("revision" to UINT),
        "identity-pin" to s("pinId" to FieldKind.DIGEST),
        "device-register" to s("revision" to UINT),
        "share-key" to s(),
        "share-password" to s(),
        "drop-public-key" to s(),
        "blob-meta" to s(),
        "master-key" to s(),
        "identity-priv" to s(),
        "report-share" to s("itemKind" to FieldKind.ROLE, "itemId" to ID),
        "report-link" to s("shareId" to FieldKind.SHARE_ID),
        "expense-entry-key" to s("scopeId" to ID, "nodeId" to ID, "entryId" to ID, "revision" to UINT),
        "moderation-statement-facts" to s("caseId" to ID, "statementId" to ID),
        "invite-link" to s("tenantId" to ID, "inviteId" to ID),
        "value-key" to s("scopeId" to ID, "epoch" to UINT, "valueAddress" to FieldKind.DIGEST),
    )

    val FIELD: Schema = mapOf(
        "node-name" to s("scopeId" to ID, "nodeId" to ID, "epoch" to UINT, "placementRevision" to UINT),
        "node-meta" to s("scopeId" to ID, "nodeId" to ID),
        "media-meta" to s("scopeId" to ID, "mediaId" to ID),
        "media-exif" to s("scopeId" to ID, "mediaId" to ID),
        "bucket-name" to s("scopeId" to ID, "nodeId" to ID, "bucketId" to ID),
        "share-identity-name" to s("scopeId" to ID, "shareId" to FieldKind.SHARE_ID, "identityId" to ID),
        "member-name" to s("scopeId" to ID, "userId" to ID),
        "member-avatar" to s("scopeId" to ID, "userId" to ID),
        "member-join-record" to s("scopeId" to ID, "userId" to ID),
        "member-verification" to s("scopeId" to ID, "userId" to ID, "subjectUserId" to ID, "epoch" to UINT),
        "expense-entry" to s("scopeId" to ID, "nodeId" to ID, "entryId" to ID, "revision" to UINT),
        "share-memory-name" to s(),
        "share-note" to s(),
        "share-name" to s(),
    )

    val SEAL_TO: Schema = mapOf(
        "scope-grant" to s("scopeId" to ID, "epoch" to UINT),
        "group-name-key" to s("scopeId" to ID, "nameEpoch" to UINT),
        "upload-identity-key" to s("scopeId" to ID, "nodeId" to ID, "identityId" to ID),
        "pairing-grant" to s(),
        "moderation-grant" to s("epoch" to UINT),
        "moderation-report" to s("epoch" to UINT),
        "moderation-report-contact" to s("epoch" to UINT),
        "moderation-note" to s("epoch" to UINT, "caseId" to ID),
        "moderation-facts-key" to s("caseId" to ID, "statementId" to ID, "accountId" to ID),
        "moderation-objection" to s("epoch" to UINT, "statementId" to ID),
        "moderation-order" to s("epoch" to UINT, "orderId" to ID),
        "moderation-step-facts" to s("epoch" to UINT, "statementId" to ID),
    )

    val STATEMENT: Schema = mapOf(
        "grant" to s(
            "scopeId" to ID, "scopeKind" to FieldKind.SCOPE_KIND, "epoch" to UINT, "recipientUserId" to ID,
            "recipientIdentityDigest" to FieldKind.DIGEST, "wrappedGrantDigest" to FieldKind.DIGEST,
        ),
        "scope-succession" to s("scopeId" to ID, "epoch" to UINT, "successionPublicKey" to FieldKind.BYTES),
    )
}

/** One context: a purpose and exactly its binding fields (an id as a String, an integer as Long, bytes as ByteArray). */
data class Context(val purpose: String, val fields: Map<String, Any?> = emptyMap()) {
    constructor(purpose: String, vararg fields: Pair<String, Any?>) : this(purpose, fields.toMap())
}

private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val SHARE_ID = Regex("^[A-Za-z0-9_-]{43}$")
private val BLOB_ROLE = Regex("^[A-Z][A-Z0-9_]{0,15}$")
private val SCOPE_KINDS = setOf("TENANT", "GROUP", "PERSONAL")
private val PAIRING_CODE = Regex("^[0-9]{6}$")
private val OPERATION_ID = Regex("^[a-z][A-Za-z0-9]{0,63}$")

private fun encodeField(purpose: String, name: String, kind: FieldKind, value: Any?): ByteArray {
    fun bad(what: String): Nothing = throw SealContextInvalid("$purpose.$name is not $what")
    fun str(re: Regex, what: String) = (value as? String)?.takeIf { re.matches(it) }?.let { utf8(it) } ?: bad(what)
    return when (kind) {
        FieldKind.ID -> str(CANONICAL_UUID, "a canonical lowercase UUID")
        FieldKind.SHARE_ID -> str(SHARE_ID, "a share id")
        FieldKind.UINT -> when (value) {
            is Long -> value
            is Int -> value.toLong()
            else -> bad("an unsigned integer")
        }.let { if (it < 0) bad("an unsigned integer") else u64be(it) }
        FieldKind.ROLE -> str(BLOB_ROLE, "a blob role")
        FieldKind.OPTIONAL_ID -> if (value == null) ByteArray(0) else encodeField(purpose, name, FieldKind.ID, value)
        FieldKind.SCOPE_KIND -> (value as? String)?.takeIf { it in SCOPE_KINDS }?.let { utf8(it) } ?: bad("a scope kind")
        FieldKind.DIGEST -> (value as? ByteArray)?.takeIf { it.size == 32 } ?: bad("a SHA-256 digest")
        FieldKind.BYTES -> value as? ByteArray ?: bad("a byte string")
        FieldKind.PAIRING_CODE -> str(PAIRING_CODE, "a six-digit pairing code")
        FieldKind.OPERATION_ID -> str(OPERATION_ID, "an operation id")
    }
}

fun encodeContext(schema: Schema, context: Context): ByteArray {
    val fields = schema[context.purpose] ?: throw SealContextInvalid("unknown purpose ${context.purpose}")
    val extra = context.fields.keys.filter { k -> fields.none { it.first == k } }
    if (extra.isNotEmpty()) throw SealContextInvalid("${context.purpose} does not bind ${extra.joinToString()}")
    for ((name, kind) in fields) {
        if (name !in context.fields && kind != FieldKind.OPTIONAL_ID) throw SealContextInvalid("${context.purpose}.$name is missing")
    }
    return concat(lp(utf8(context.purpose)), *fields.map { (name, kind) -> lp(encodeField(context.purpose, name, kind, context.fields[name])) }.toTypedArray())
}
