package com.veyq.app.features.sharing

import com.veyq.app.shared.TestIds
import java.security.MessageDigest
import java.util.Base64

/**
 * A link's write proof in slice tests (ADR 0076). The web client derives `writeToken = HKDF(K_share,
 * "veyq/v1/share-write:" + shareId)` and posts `writeVerifier = SHA-256(writeToken)` with the share; naming yourself
 * presents the token as `X-Share-Write`. The backend derives nothing — it hashes what it is handed and compares — so
 * a slice test needs only a token that differs per share, and its hash: [token] derives one from the share id under a
 * test-only label (the real derivation is pinned by the crypto conformance vector `share_write_token`, not here).
 *
 * A share a test persists as an entity, bypassing `createShare`, holds no verifier and therefore takes no guest — the
 * fail-closed rule for links minted before ADR 0076. Set `writeVerifier = verifier(id)` on it if the test names one.
 */
object ShareWriteProof {
    const val HEADER = "X-Share-Write"

    /** The token a guest holding share [shareId]'s whole link presents. */
    fun token(shareId: String): String = b64(sha256("test-share-write:$shareId".toByteArray()))

    /** What the share must have stored: `SHA-256(token)`, as the client computes it. */
    fun verifier(shareId: String): String = b64(sha256(Base64.getDecoder().decode(token(shareId))))

    /** The `writeVerifier` member of a create or re-issue body, for splicing into its JSON. */
    fun field(shareId: String): String = """"writeVerifier":"${verifier(shareId)}""""

    /** A fresh client-minted id with its verifier — the `"shareId"` and `"writeVerifier"` members of a create or
     *  re-issue body whose test reads the id back from the response. */
    fun newShare(): String = TestIds.shareId().let { """"shareId":"$it",${field(it)}""" }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
