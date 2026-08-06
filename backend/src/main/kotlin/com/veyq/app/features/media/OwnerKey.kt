package com.veyq.app.features.media

/**
 * Attribution key for who contributed a media / owns a bucket: a scope member (`user:{userId}`) XOR an
 * anonymous share contributor (`share:{shareIdentityId}`).
 *
 * The string form is a **contract, not an internal encoding** — the OpenAPI spec pins it (`BucketSummary` /
 * `MediaSummary` `ownerKey`) and the web client parses the `user:`/`share:` prefixes itself, and it is the
 * `veyq_bucket.owner_key` column value. So this type is **internal only**: it centralizes construction + the
 * one `share:` inspection the server does, and round-trips **byte-identically** via [format]/[parse].
 * Persistence, DTO mapping and Panache queries keep using the [format] string. The server only ever inspects
 * the `share:` form (to fetch a contributor's sealed name); a `user:` key is opaque relay it never re-parses.
 */
sealed interface OwnerKey {
    fun format(): String

    data class User(val userId: String) : OwnerKey {
        override fun format(): String = "$PREFIX_USER$userId"
    }

    data class Share(val shareIdentityId: String) : OwnerKey {
        override fun format(): String = "$PREFIX_SHARE$shareIdentityId"
    }

    companion object {
        const val PREFIX_USER = "user:"
        const val PREFIX_SHARE = "share:"

        /** Parse a stored/relayed owner_key. A non-`share:` value is a member key (the `user:` prefix stripped;
         *  a bare legacy value passes through unchanged) — matching the server's existing share-only inspection. */
        fun parse(raw: String): OwnerKey =
            if (raw.startsWith(PREFIX_SHARE)) Share(raw.removePrefix(PREFIX_SHARE))
            else User(raw.removePrefix(PREFIX_USER))
    }
}
