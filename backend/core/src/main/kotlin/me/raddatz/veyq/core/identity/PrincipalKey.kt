package me.raddatz.veyq.core.identity

/**
 * Who wrote or owns something, as the server records it: a member (`user:{userId}`) XOR a link's guest identity
 * (`share:{identityId}`) — ADR 0034's spelling, the one ref for every attribution the platform keeps: a medium's and a
 * bucket's owner (Memories' `owner_key`), an attachment's uploader ([me.raddatz.veyq.core.blob.NodeAttachmentEntity]),
 * a sealed field's last editor (en.platform.field-editor, ADR 0093 § 8). Always taken from the authenticated caller of
 * the write — a member's session principal, a guest's identity from its contributor token — never from a request.
 *
 * The string form is a **contract, not an internal encoding**: the OpenAPI specs pin it (`ownerKey`, `uploaderKey`), the
 * web client parses the `user:` / `share:` prefixes itself, and `veyq_bucket.owner_key` stores it. [format] / [parse]
 * round-trip it byte-identically. A table that stores the ref as columns keeps the pair the database can check
 * ([userId] — no foreign key, a member who left stays named — and [identityId], a foreign key onto
 * `veyq_share_identity`), exactly one of the two set.
 */
sealed interface PrincipalKey {
    fun format(): String

    /** The member's id, or null for a guest — the `*_user_id` column of a stored pair. */
    val userId: String?

    /** The guest identity's id, or null for a member — the `*_identity_id` column of a stored pair. */
    val identityId: String?

    data class User(override val userId: String) : PrincipalKey {
        override val identityId: String? get() = null
        override fun format(): String = "$PREFIX_USER$userId"
    }

    data class Share(override val identityId: String) : PrincipalKey {
        override val userId: String? get() = null
        override fun format(): String = "$PREFIX_SHARE$identityId"
    }

    companion object {
        const val PREFIX_USER = "user:"
        const val PREFIX_SHARE = "share:"

        /** Parse a stored or relayed key. A non-`share:` value is a member's (the `user:` prefix stripped; a bare value
         *  passes through unchanged) — the server only ever inspects the `share:` form. */
        fun parse(raw: String): PrincipalKey =
            if (raw.startsWith(PREFIX_SHARE)) Share(raw.removePrefix(PREFIX_SHARE))
            else User(raw.removePrefix(PREFIX_USER))

        /** The ref a stored pair holds — the member's id or the identity's, whichever is set — or null for neither. */
        fun of(userId: String?, identityId: String?): PrincipalKey? =
            userId?.let(::User) ?: identityId?.let(::Share)
    }
}
