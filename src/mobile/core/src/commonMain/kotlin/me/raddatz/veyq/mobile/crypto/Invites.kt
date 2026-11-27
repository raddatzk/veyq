package me.raddatz.veyq.mobile.crypto

/**
 * A member invite (ADR 0111 decisions 1-3, 19; ADR 0129 decision 1), ported exactly from `src/packages/crypto/src/invite.ts`:
 * the invite key a seed derives, the owner's signature on it, the link's code and its sealed part, the fragment a link
 * carries, the invitee's checks ([checkInvite]), the accept proof and the twice-signed join record. Pinned by
 * `contracts/crypto/vectors/v1.json` (`#invite_key`, `#invite_link`, `#join_record`, `seal.sym['invite-link']`), run by
 * [Conformance] on every platform's primitives. The record's statement, encoding and seal are [JoinRecords]'.
 *
 * The seed, the code and the link's sealed part never reach the server (operator-blind): a mailed invite sends the sealed
 * part for the mail and the server forgets it, a link invite sends neither — the owner's client builds the link.
 */
class Invites(private val suite: V1) {
    private val p = suite.p
    private val joins = JoinRecords(suite)
    private val trust = Trust(suite)

    // ---- the invite key ----

    /** An invite key: an identity-shaped bundle, so a grant seals to it like to a member and it signs like an identity. */
    class InviteKey(val priv: IdentityPrivate, val pub: IdentityPublic) {
        fun zero() {
            priv.kemSeed.fill(0)
            priv.signSeed.fill(0)
        }
    }

    /** A fresh seed — what the owner's client draws, seals into the link and forgets. */
    fun generateSeed(): ByteArray = p.randomBytes(SEED_BYTES)

    /** `HKDF-SHA256(seed, empty, "veyq/v1/invite/kem" | "veyq/v1/invite/sign", 32)`, the X-Wing and Ed25519 seeds. */
    fun inviteKeyFromSeed(seed: ByteArray): InviteKey {
        require(seed.size == SEED_BYTES) { "invite seed: not 32 bytes" }
        val priv = IdentityPrivate(
            p.hkdfSha256(seed, ByteArray(0), utf8("veyq/v1/invite/kem"), 32),
            p.hkdfSha256(seed, ByteArray(0), utf8("veyq/v1/invite/sign"), 32),
        )
        return InviteKey(priv, suite.identityPublic(priv))
    }

    /** The owner's signature over the invite key ([JoinRecords.inviteKeyStatement]); [expiresAt] in Unix seconds. */
    fun signInviteKey(ownerSignSeed: ByteArray, tenantId: String, inviteId: String, invitePublicKey: ByteArray, expiresAt: Long): ByteArray =
        p.ed25519Sign(ownerSignSeed, joins.inviteKeyStatement(tenantId, inviteId, invitePublicKey, expiresAt))

    /** Whether [signature] is the owner's over this invite key. False — never a throw. */
    fun inviteKeySignatureVerifies(ownerSignPublic: ByteArray, tenantId: String, inviteId: String, invitePublicKey: ByteArray, expiresAt: Long, signature: ByteArray): Boolean =
        runCatching { p.ed25519Verify(ownerSignPublic, joins.inviteKeyStatement(tenantId, inviteId, invitePublicKey, expiresAt), signature) }.getOrDefault(false)

    // ---- the accept proof ----

    /** `invite-accept` over (invite, account, SHA-256 of its serialized identity bundle). */
    fun inviteAcceptStatement(inviteId: String, userId: String, identityPublicKey: ByteArray): ByteArray =
        encodeContext(Purposes.STATEMENT, Context("invite-accept", "inviteId" to inviteId, "userId" to userId, "identityDigest" to p.sha256(identityPublicKey)))

    /** The invite key's proof that its holder redeems it for this account and identity — what the server checks. */
    fun signInviteAccept(inviteSignSeed: ByteArray, inviteId: String, userId: String, identityPublicKey: ByteArray): ByteArray =
        p.ed25519Sign(inviteSignSeed, inviteAcceptStatement(inviteId, userId, identityPublicKey))

    // ---- the code ----

    /** How the link's secret was chosen; in the clear in the link, so the invitee's client asks for the right one. */
    enum class Mode(val byte: Byte) { CODE(1), PASSWORD(2) }

    /** A fresh code: 10 Crockford base32 characters, each the low 5 bits of a random byte (uniform: 256 = 8 × 32). */
    fun generateCode(): String {
        val r = p.randomBytes(CODE_LENGTH)
        return try { buildString { for (b in r) append(CROCKFORD[b.toInt() and 31]) } } finally { r.fill(0) }
    }

    /** The bytes the KEK is stretched from: the canonical code's UTF-8, or the password's (NFC) as typed; null for a code
     *  that is no code, or an empty password. */
    fun linkSecret(mode: Mode, typed: String): ByteArray? = when (mode) {
        Mode.PASSWORD -> if (typed.isNotEmpty()) utf8(typed) else null
        Mode.CODE -> normalizeCode(typed)?.let { utf8(it) }
    }

    /** `Argon2id(secret, salt)` at the production profile — the link came through the operator's mail, so no other. */
    fun kekInviteLink(secret: ByteArray, salt: ByteArray): ByteArray {
        require(salt.size == 16) { "an Argon2id salt is 16 bytes" }
        return p.argon2id(secret, salt, V1.ARGON.t, V1.ARGON.m, V1.ARGON.p, V1.ARGON.dkLen)
    }

    // ---- the link's sealed part ----

    /** What the link's seal holds: the seed, the head of the owner's key chain when they signed, and the owner's account. */
    class LinkPayload(val seed: ByteArray, val signerHead: ByteArray, val signerUserId: String)

    /** A link's sealed part, parsed and still sealed; its profile passed the one-profile gate. */
    class SealedLink(val mode: Mode, val params: ArgonParams, val salt: ByteArray, val sealed: ByteArray)

    /** `0x02 ‖ lp(seed) ‖ lp(signerHead) ‖ lp(utf8(signerUserId))`. */
    fun encodePayload(payload: LinkPayload): ByteArray {
        if (payload.seed.size != SEED_BYTES) throw InviteLinkInvalid("seed")
        if (payload.signerHead.size != HEAD_BYTES) throw InviteLinkInvalid("head")
        val user = utf8(payload.signerUserId)
        if (user.size != UUID_BYTES) throw InviteLinkInvalid("signer")
        return concat(byteArrayOf(PAYLOAD_VERSION), lp(payload.seed), lp(payload.signerHead), lp(user))
    }

    /** The payload decoded; [InviteLinkInvalid] for any other version (a version-1 layout included), size or length. */
    fun decodePayload(b: ByteArray): LinkPayload {
        if (b.isEmpty() || b[0] != PAYLOAD_VERSION) throw InviteLinkInvalid("version")
        val parts = readLp(b.copyOfRange(1, b.size), 3)
        if (parts[0].size != SEED_BYTES || parts[1].size != HEAD_BYTES || parts[2].size != UUID_BYTES) throw InviteLinkInvalid("size")
        return LinkPayload(parts[0], parts[1], parts[2].decodeToString())
    }

    /** The link's sealed part (base64url, unpadded): `mode ‖ kdfParams ‖ seal` under [kek] = `Argon2id(secret, salt)`. */
    fun sealLink(tenantId: String, inviteId: String, mode: Mode, kek: ByteArray, salt: ByteArray, payload: LinkPayload): String {
        if (salt.size != 16) throw InviteLinkInvalid("salt")
        val plain = encodePayload(payload)
        try {
            val sealed = suite.seal(linkContext(tenantId, inviteId), kek, plain)
            return b64url(concat(byteArrayOf(mode.byte), suite.encodeKdfParams(V1.ARGON, salt), sealed))
        } finally {
            plain.fill(0)
        }
    }

    /** A sealed part parsed before any derivation: its mode and its profile, which must be the one this client writes. */
    fun parseSealedLink(part: String): SealedLink {
        val b = unb64urlStrict(part) ?: throw InviteLinkInvalid("encoding")
        if (b.size <= HEADER_BYTES) throw InviteLinkInvalid("length")
        val mode = Mode.entries.firstOrNull { it.byte == b[0] } ?: throw InviteLinkInvalid("mode")
        val (params, salt) = try { suite.decodeKdfParams(b.copyOfRange(1, HEADER_BYTES)) } catch (e: IllegalArgumentException) { throw InviteLinkInvalid("profile") }
        return SealedLink(mode, params, salt, b.copyOfRange(HEADER_BYTES, b.size))
    }

    /** The payload opened with [kek]; [OpenRefused] (`invite-link`) for a wrong code or another invite's seal. */
    fun openLink(tenantId: String, inviteId: String, kek: ByteArray, link: SealedLink): LinkPayload {
        val plain = suite.open(linkContext(tenantId, inviteId), kek, link.sealed)
        try {
            return decodePayload(plain)
        } finally {
            plain.fill(0)
        }
    }

    private fun linkContext(tenantId: String, inviteId: String) = Context("invite-link", "tenantId" to tenantId, "inviteId" to inviteId)

    // ---- the fragment ----

    /** An invite link's fragment, split: the token, the sealed part (a member invite) and the code (a link invite). */
    data class Fragment(val token: String, val sealed: String?, val code: String?)

    // ---- the invitee's checks ----

    /** What the server serves about an invite — every byte unchecked until [checkInvite] ran. Bytes are DECODED. */
    data class ServedInvite(
        val tenantId: String,
        val inviteId: String,
        val invitePublicKey: ByteArray,
        val inviteKeySignature: ByteArray,
        val expiresAt: Long,
        val signerUserId: String,
        val signerIdentityPublicKey: ByteArray,
        val signerKeyChain: List<Trust.KeyChainLink>,
        val anchorWrappedGrant: ByteArray,
        val anchorEpoch: Long,
        val anchorGrantSignature: ByteArray,
        val wrappedGrant: ByteArray,
        val grantedEpoch: Long,
        val grantSignature: ByteArray,
        val granterIdentityPublicKey: ByteArray,
        val successions: List<Trust.SuccessionLink>,
    )

    /** Which check refused an invite: one refusal for the person ("ask for a new one"), one reason each for the tests. */
    enum class CheckFailure { KEY_MISMATCH, SIGNER_MISMATCH, SIGNER_CHAIN_MISMATCH, BAD_SIGNATURE, ANCHOR_INVALID, EPOCH_UNLINKED }

    class InviteCheckFailed(val reason: CheckFailure) : Exception("invite_check_failed: ${reason.name.lowercase()}")

    /** An invite that passed every check; the caller zeroes [epochKey] and [inviteKey] when done. */
    class CheckedInvite(val inviteKey: InviteKey, val epochKey: ByteArray, val grantedEpoch: Long, val signerHead: ByteArray, val signerIndex: Int, val signerIdentityPublicKey: ByteArray)

    /**
     * The invitee's checks, in the order an operator would have to beat them (the port of `checkInvite`): the key the
     * seed derives is the served one; the served signer is the account the seal names; the served chain walked from the
     * head inside the seal ends at the served signer key; that key signed the invite key and the anchor grant; and the
     * tenant key is the anchor's `E_a` or one the succession statements lead to from it. Throws [InviteCheckFailed].
     */
    fun checkInvite(payload: LinkPayload, served: ServedInvite): CheckedInvite {
        val inviteKey = inviteKeyFromSeed(payload.seed)
        fun fail(r: CheckFailure): Nothing {
            inviteKey.zero()
            throw InviteCheckFailed(r)
        }
        if (!inviteKey.pub.serialize().contentEquals(served.invitePublicKey)) fail(CheckFailure.KEY_MISMATCH)
        if (payload.signerUserId != served.signerUserId) fail(CheckFailure.SIGNER_MISMATCH)
        val chain = trust.verifyKeyChain(payload.signerUserId, served.signerKeyChain, payload.signerHead)
        if (chain !is Trust.KeyChainVerdict.Ok || !chain.keys.identityPublicKey.contentEquals(served.signerIdentityPublicKey)) fail(CheckFailure.SIGNER_CHAIN_MISMATCH)
        val signerSign = suite.deserializeIdentityPublic(chain.keys.identityPublicKey).sign
        if (!inviteKeySignatureVerifies(signerSign, served.tenantId, served.inviteId, served.invitePublicKey, served.expiresAt, served.inviteKeySignature)) {
            fail(CheckFailure.BAD_SIGNATURE)
        }
        fun grantOf(epoch: Long, wrapped: ByteArray) = runCatching {
            suite.grantStatement(served.tenantId, "TENANT", epoch, served.inviteId, served.invitePublicKey, wrapped)
        }.getOrNull()
        fun verifies(key: ByteArray, msg: ByteArray?, sig: ByteArray) = msg != null && runCatching { p.ed25519Verify(key, msg, sig) }.getOrDefault(false)
        if (!verifies(signerSign, grantOf(served.anchorEpoch, served.anchorWrappedGrant), served.anchorGrantSignature)) fail(CheckFailure.ANCHOR_INVALID)
        val anchorKey = openGrant(served.tenantId, served.anchorEpoch, inviteKey, served.anchorWrappedGrant) ?: fail(CheckFailure.EPOCH_UNLINKED)
        fun unlinked(): Nothing {
            anchorKey.fill(0)
            fail(CheckFailure.EPOCH_UNLINKED)
        }
        fun checked(epochKey: ByteArray) = CheckedInvite(inviteKey, epochKey, served.grantedEpoch, chain.head, chain.index, chain.keys.identityPublicKey)
        if (served.grantedEpoch == served.anchorEpoch) {
            if (!served.wrappedGrant.contentEquals(served.anchorWrappedGrant)) unlinked()
            return checked(anchorKey)
        }
        val granterSign = runCatching { suite.deserializeIdentityPublic(served.granterIdentityPublicKey).sign }.getOrNull() ?: unlinked()
        if (!verifies(granterSign, grantOf(served.grantedEpoch, served.wrappedGrant), served.grantSignature)) unlinked()
        val epochKey = openGrant(served.tenantId, served.grantedEpoch, inviteKey, served.wrappedGrant) ?: unlinked()
        val (anchorPublic, anchorSeed) = trust.successionKeyPair(anchorKey)
        val (carriedPublic, carriedSeed) = trust.successionKeyPair(epochKey)
        val linked = trust.verifySuccessionPath(served.tenantId, served.anchorEpoch, anchorPublic, served.successions, served.grantedEpoch, carriedPublic)
        anchorSeed.fill(0)
        carriedSeed.fill(0)
        if (!linked) {
            epochKey.fill(0)
            unlinked()
        }
        anchorKey.fill(0)
        return checked(epochKey)
    }

    /** The tenant key a grant to the invite key carries, or null when it does not open as that epoch's grant. */
    private fun openGrant(tenantId: String, epoch: Long, inviteKey: InviteKey, wrapped: ByteArray): ByteArray? = runCatching {
        suite.openAs(Context("scope-grant", "scopeId" to tenantId, "epoch" to epoch), suite.xwingKey(inviteKey.priv.kemSeed), wrapped)
    }.getOrNull()

    // ---- the join record ----

    /** The record signed twice — by the invite key and by the new identity — over the same `join-record` statement. */
    fun signJoinRecord(
        tenantId: String, userId: String,
        identityPublicKey: ByteArray, secretKeyProofPublicKey: ByteArray, inviteId: String, invitePublicKey: ByteArray,
        inviteKeySignature: ByteArray, inviteExpiresAt: Long, signerUserId: String, signerIdentityPublicKey: ByteArray,
        inviteSignSeed: ByteArray, identitySignSeed: ByteArray,
    ): JoinRecords.JoinRecord {
        val body = JoinRecords.JoinRecord(
            identityPublicKey, secretKeyProofPublicKey, inviteId, invitePublicKey, inviteKeySignature, inviteExpiresAt,
            signerUserId, signerIdentityPublicKey, ByteArray(0), ByteArray(0),
        )
        val stmt = joins.joinRecordStatement(tenantId, userId, body)
        return JoinRecords.JoinRecord(
            identityPublicKey, secretKeyProofPublicKey, inviteId, invitePublicKey, inviteKeySignature, inviteExpiresAt,
            signerUserId, signerIdentityPublicKey, p.ed25519Sign(inviteSignSeed, stmt), p.ed25519Sign(identitySignSeed, stmt),
        )
    }

    /** Exactly [count] length-prefixed fields and nothing after them; [InviteLinkInvalid] `truncated` / `trailing`. */
    private fun readLp(b: ByteArray, count: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>(count)
        var off = 0
        repeat(count) {
            if (off + 4 > b.size) throw InviteLinkInvalid("truncated")
            val n = readU32be(b, off).toLong() and 0xffffffffL
            off += 4
            if (off + n > b.size) throw InviteLinkInvalid("truncated")
            out += b.copyOfRange(off, off + n.toInt())
            off += n.toInt()
        }
        if (off != b.size) throw InviteLinkInvalid("trailing")
        return out
    }

    companion object {
        /** The size of an invite key's seed — what the link carries. */
        const val SEED_BYTES = 32

        /** A code is 10 Crockford base32 characters — 50 bits. */
        const val CODE_LENGTH = 10

        /** Crockford's base32: digits and capitals without I, L, O and U (`crockford.ts`; the Secret Key's alphabet too). */
        const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        private const val UUID_BYTES = 36
        private const val HEAD_BYTES = 32
        private const val PAYLOAD_VERSION: Byte = 0x02
        private const val HEADER_BYTES = 1 + 32

        /** A code as shown and passed on: two groups of five. */
        fun formatCode(code: String): String = "${code.take(5)}-${code.drop(5)}"

        /**
         * A typed code in its canonical form, or null: case folded, spaces and hyphens dropped, I and L read as 1, O as 0
         * (`foldCrockford`, as the Secret Key's text folds); U is not in the alphabet.
         */
        fun normalizeCode(typed: String): String? {
            val s = SecretKeyText.fold(typed)
            return s.takeIf { it.length == CODE_LENGTH && it.all { c -> c in CROCKFORD } }
        }

        /**
         * The fragment an invite link carries (`parseInviteFragment`): the token alone (an owner invite), `token.sealed`
         * (a mailed member invite) or `token.sealed.code` (a link invite). The code comes back canonical; one that is no
         * code — or a code with no sealed part to open — is null, and the page asks for it.
         */
        fun parseFragment(fragment: String): Fragment {
            val raw = if (fragment.startsWith("#")) fragment.substring(1) else fragment
            val first = raw.indexOf('.')
            if (first < 0) return Fragment(raw, null, null)
            val second = raw.indexOf('.', first + 1)
            val sealed = (if (second < 0) raw.substring(first + 1) else raw.substring(first + 1, second)).ifEmpty { null }
            val code = if (second < 0 || sealed == null) null else normalizeCode(raw.substring(second + 1))
            return Fragment(raw.substring(0, first), sealed, code)
        }

        /** Base64url without padding, strictly: its alphabet only; null otherwise (a `.` included). */
        internal fun unb64urlStrict(s: String): ByteArray? {
            if (s.isEmpty() || s.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }) return null
            return runCatching {
                val std = s.replace('-', '+').replace('_', '/')
                unb64(std + "=".repeat((4 - std.length % 4) % 4))
            }.getOrNull()
        }
    }

    /**
     * A link invite's fragment `token.sealed.code` (no `#`): what the owner's client hands over. Only a code-mode sealed
     * part takes one, the code written canonical; refuses an empty token or one holding a `.`.
     */
    fun buildFragment(token: String, sealed: String, code: String): String {
        if (token.isEmpty() || token.contains('.')) throw InviteLinkInvalid("token")
        if (parseSealedLink(sealed).mode != Mode.CODE) throw InviteLinkInvalid("mode")
        val canonical = normalizeCode(code) ?: throw InviteLinkInvalid("code")
        return "$token.$sealed.$canonical"
    }
}

/** A link's sealed part, payload or fragment that is not one this client reads. */
class InviteLinkInvalid(val reason: String) : IllegalArgumentException("invite_link_invalid: $reason")
