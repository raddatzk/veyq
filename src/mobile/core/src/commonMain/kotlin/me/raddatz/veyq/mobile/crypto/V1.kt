package me.raddatz.veyq.mobile.crypto

import org.kotlincrypto.hash.sha3.SHA3_256
import org.kotlincrypto.hash.sha3.SHAKE256

/** Argon2id parameters, as the wire's `kdfParams` carries them. */
data class ArgonParams(val t: Int, val m: Int, val p: Int, val dkLen: Int)

/** An X-Wing key pair: the 32-byte seed is the private key (draft-connolly-cfrg-xwing-kem-11). */
class XWingKey(val seed: ByteArray, val publicKey: ByteArray, internal val mlKemDk: ByteArray, internal val x25519Sk: ByteArray)

/** A member's identity private half: the X-Wing seed and the Ed25519 signing seed. */
class IdentityPrivate(val kemSeed: ByteArray, val signSeed: ByteArray)

/** A member's identity public half: X-Wing encapsulation key (1216 bytes) and Ed25519 public key (32). */
class IdentityPublic(val kem: ByteArray, val sign: ByteArray) {
    fun serialize(): ByteArray = concat(lp(kem), lp(sign))
}

/**
 * The v1 suite (a port of `src/packages/crypto/src/v1.ts`, `seal.ts` and `primitives.ts`, ADR 0017), over one
 * platform's [Primitives]. Every byte it writes or reads is pinned by `contracts/crypto/vectors/v1.json`.
 */
class V1(val p: Primitives) {
    // ---- X-Wing (draft-connolly-cfrg-xwing-kem-11) ----

    fun xwingKey(seed: ByteArray): XWingKey {
        require(seed.size == 32) { "an X-Wing seed is 32 bytes" }
        val expanded = SHAKE256(96).apply { update(seed) }.digest()
        val (ekM, dkM) = p.mlKem768KeyGen(expanded.copyOfRange(0, 32), expanded.copyOfRange(32, 64))
        val skX = expanded.copyOfRange(64, 96)
        val pkX = p.x25519(skX, null)
        return XWingKey(seed, concat(ekM, pkX), dkM, skX)
    }

    private fun combiner(ssM: ByteArray, ssX: ByteArray, ctX: ByteArray, pkX: ByteArray): ByteArray =
        SHA3_256().apply { update(concat(ssM, ssX, ctX, pkX, XWING_LABEL)) }.digest()

    /** Returns ct (1120 bytes) and the shared secret; [eseed] (64 bytes) makes it deterministic for the vectors. */
    fun xwingEncap(publicKey: ByteArray, eseed: ByteArray? = null): Pair<ByteArray, ByteArray> {
        require(publicKey.size == 1216) { "an X-Wing public key is 1216 bytes" }
        val es = eseed ?: p.randomBytes(64)
        val pkM = publicKey.copyOfRange(0, 1184)
        val pkX = publicKey.copyOfRange(1184, 1216)
        val ekX = es.copyOfRange(32, 64)
        val ctX = p.x25519(ekX, null)
        val ssX = p.x25519(ekX, pkX)
        val (ctM, ssM) = p.mlKem768Encaps(pkM, es.copyOfRange(0, 32))
        return concat(ctM, ctX) to combiner(ssM, ssX, ctX, pkX)
    }

    fun xwingDecap(key: XWingKey, ct: ByteArray): ByteArray {
        require(ct.size == 1120) { "an X-Wing ciphertext is 1120 bytes" }
        val ctM = ct.copyOfRange(0, 1088)
        val ctX = ct.copyOfRange(1088, 1120)
        val ssM = p.mlKem768Decaps(key.mlKemDk, ctM)
        val ssX = p.x25519(key.x25519Sk, ctX)
        return combiner(ssM, ssX, ctX, key.publicKey.copyOfRange(1184, 1216))
    }

    // ---- envelopes ----

    private fun aad(type: Int, context: ByteArray) = concat(byteArrayOf(type.toByte()), utf8("v1"), context)

    /** §5b symmetric wrap: 0x02 ‖ nonce(12) ‖ aead. */
    fun seal(context: Context, key: ByteArray, plaintext: ByteArray, nonce: ByteArray = p.randomBytes(12)): ByteArray {
        val c = encodeContext(Purposes.SEAL, context)
        return concat(byteArrayOf(SYM), nonce, p.aesGcmSeal(key, nonce, plaintext, aad(SYM.toInt(), c)))
    }

    fun open(context: Context, key: ByteArray, blob: ByteArray): ByteArray {
        val c = encodeContext(Purposes.SEAL, context)
        if (blob.size < 1 + 12 + 16) throw OpenRefused(context.purpose)
        return guard(context) { p.aesGcmOpen(key, blob.copyOfRange(1, 13), blob.copyOfRange(13, blob.size), aad(SYM.toInt(), c)) }
    }

    // ---- a blob's bytes: chunked AEAD frames (ADR 0013 §3) ----

    /**
     * One frame of a blob's chunk sequence: nonce(12) ‖ AES-256-GCM(K_blob, nonce, chunk, aad = u32be(index) ‖ u32be(total)).
     * No type byte, purpose or suite marker: the key is the blob's own, and `total` in every frame binds where it ends.
     */
    fun sealFrame(blobKey: ByteArray, index: Int, total: Int, plaintext: ByteArray, nonce: ByteArray = p.randomBytes(FRAME_NONCE)): ByteArray =
        concat(nonce, p.aesGcmSeal(blobKey, nonce, plaintext, concat(u32be(index), u32be(total))))

    fun openFrame(blobKey: ByteArray, index: Int, total: Int, frame: ByteArray): ByteArray {
        return try {
            p.aesGcmOpen(blobKey, frame.copyOfRange(0, FRAME_NONCE), frame.copyOfRange(FRAME_NONCE, frame.size), concat(u32be(index), u32be(total)))
        } catch (e: Exception) {
            throw OpenRefused("chunk-frame")
        }
    }

    /** §5a asymmetric wrap: 0x01 ‖ ct(1120) ‖ nonce(12) ‖ aead. */
    fun sealTo(context: Context, publicKey: ByteArray, plaintext: ByteArray, eseed: ByteArray? = null, nonce: ByteArray = p.randomBytes(12)): ByteArray {
        val c = encodeContext(Purposes.SEAL_TO, context)
        val (ct, ss) = xwingEncap(publicKey, eseed)
        return concat(byteArrayOf(ASYM), ct, nonce, p.aesGcmSeal(asymKek(ss, c), nonce, plaintext, aad(ASYM.toInt(), c)))
    }

    fun openAs(context: Context, key: XWingKey, blob: ByteArray): ByteArray {
        val c = encodeContext(Purposes.SEAL_TO, context)
        if (blob.size < 1 + 1120 + 12 + 16) throw OpenRefused(context.purpose)
        return guard(context) {
            val ss = xwingDecap(key, blob.copyOfRange(1, 1121))
            p.aesGcmOpen(asymKek(ss, c), blob.copyOfRange(1121, 1133), blob.copyOfRange(1133, blob.size), aad(ASYM.toInt(), c))
        }
    }

    private fun asymKek(ss: ByteArray, context: ByteArray) = p.hkdfSha256(ss, ByteArray(0), concat(utf8("veyq/v1/wrap"), context), 32)

    fun fieldKey(parentKey: ByteArray, salt: ByteArray, purpose: String): ByteArray =
        p.hkdfSha256(parentKey, salt, utf8("veyq/v1/field/$purpose"), 32)

    /**
     * The blinded slug token (ADR 0080 decision 2, AD-26): HMAC-SHA256(HKDF(K_entry, ∅, "veyq/v1/slug-token:" ‖ parentSlot),
     * NFC(slug)) — [parentSlot] the parent's id, the scope id at the root; [slug] the name already folded by its kind
     * ([Naming]). Unique per parent, stable per parent key: the server compares tokens among siblings, never names.
     */
    fun slugToken(entryKey: ByteArray, parentSlot: String, slug: String): ByteArray =
        p.hmacSha256(p.hkdfSha256(entryKey, ByteArray(0), utf8("veyq/v1/slug-token:$parentSlot"), 32), utf8(slug))

    /**
     * A link's write proof (ADR 0076, `shareWriteToken`): HKDF-SHA256(K_share, empty salt, "veyq/v1/share-write:" + shareId,
     * 32). The share stores only its SHA-256 (`writeVerifier`); a guest presents it as `X-Share-Write`.
     */
    fun shareWriteToken(kShare: ByteArray, shareId: String): ByteArray =
        p.hkdfSha256(kShare, ByteArray(0), utf8("veyq/v1/share-write:$shareId"), 32)

    /** A link's password token (cr-0118, `sharesPwToken`): Argon2id(utf8(password), salt) at the pinned profile (t 3, 32 MiB, p 1). */
    fun sharePasswordToken(password: String, salt: ByteArray): ByteArray = p.argon2id(utf8(password), salt, 3, 32768, 1, 32)

    /**
     * The key one field envelope was sealed under (`fieldKeyOf`): derived from [parentKey] and the envelope's own salt, and
     * returned only once the envelope opens under it at [context] — the key a report hands over, which opens this envelope
     * and nothing else under [parentKey].
     */
    fun fieldKeyOf(context: Context, parentKey: ByteArray, envelope: ByteArray): ByteArray {
        if (envelope.size < 17 || envelope[0] != 0x03.toByte()) throw OpenRefused(context.purpose)
        openField(context, parentKey, envelope)
        return fieldKey(parentKey, envelope.copyOfRange(1, 17), context.purpose)
    }

    /** The field envelope (ADR 0093 § 9): 0x03 ‖ salt(16) ‖ nonce(12) ‖ aead, under HKDF(K_parent, salt, purpose). */
    fun sealField(
        context: Context, parentKey: ByteArray, plaintext: ByteArray,
        salt: ByteArray = p.randomBytes(16), nonce: ByteArray = p.randomBytes(12),
    ): ByteArray {
        val c = encodeContext(Purposes.FIELD, context)
        val k = fieldKey(parentKey, salt, context.purpose)
        return concat(byteArrayOf(FIELD), salt, nonce, p.aesGcmSeal(k, nonce, plaintext, aad(FIELD.toInt(), c)))
    }

    fun openField(context: Context, parentKey: ByteArray, envelope: ByteArray): ByteArray {
        val c = encodeContext(Purposes.FIELD, context)
        if (envelope.size < 1 + 16 + 12 + 16 || envelope[0] != FIELD) throw OpenRefused(context.purpose)
        val k = fieldKey(parentKey, envelope.copyOfRange(1, 17), context.purpose)
        return guard(context) { p.aesGcmOpen(k, envelope.copyOfRange(17, 29), envelope.copyOfRange(29, envelope.size), aad(FIELD.toInt(), c)) }
    }

    private inline fun guard(context: Context, f: () -> ByteArray): ByteArray =
        try { f() } catch (e: OpenRefused) { throw OpenRefused(context.purpose) } catch (e: IllegalArgumentException) { throw OpenRefused(context.purpose) }

    // ---- KEKs, auth ----

    /** The PASSWORD wrap's KEK: Argon2id(lp(secretKey) ‖ lp(utf8(password)), salt). */
    fun kekPasswordSecret(secretKey: ByteArray, password: String, salt: ByteArray, params: ArgonParams = ARGON): ByteArray =
        argon(concat(lp(secretKey), lp(utf8(password))), salt, params)

    /** A DEVICE wrap's KEK: Argon2id(lp(deviceFactor) ‖ lp(utf8(password)), salt). */
    fun kekDevicePassword(deviceFactor: ByteArray, password: String, salt: ByteArray, params: ArgonParams = ARGON): ByteArray =
        argon(concat(lp(deviceFactor), lp(utf8(password))), salt, params)

    fun kekRecovery(recoveryCode: ByteArray, salt: ByteArray, params: ArgonParams = ARGON): ByteArray = argon(recoveryCode, salt, params)

    private fun argon(secret: ByteArray, salt: ByteArray, a: ArgonParams): ByteArray {
        require(salt.size == 16) { "an Argon2id salt is 16 bytes" }
        return p.argon2id(secret, salt, a.t, a.m, a.p, a.dkLen)
    }

    /** ADR 0003: HKDF-SHA256(utf8(email.trim().lowercase()), instanceSalt, "veyq/v1/auth-salt", 16). */
    fun authSalt(instanceSalt: ByteArray, email: String): ByteArray =
        p.hkdfSha256(utf8(email.trim().lowercase()), instanceSalt, utf8("veyq/v1/auth-salt"), 16)

    /** ADR 0003: the auth key pair's seed is Argon2id(utf8(password), authSalt); its Ed25519 key signs the nonce. */
    fun authSeed(password: String, authSalt: ByteArray): ByteArray = argon(utf8(password), authSalt, ARGON)

    /** `u32be t ‖ m ‖ p ‖ dkLen ‖ salt(16)`; anything but the suite's one parameter set is refused before Argon runs. */
    fun decodeKdfParams(encoded: ByteArray): Pair<ArgonParams, ByteArray> {
        require(encoded.size == 32) { "kdfParams are 32 bytes" }
        val a = ArgonParams(readU32be(encoded, 0), readU32be(encoded, 4), readU32be(encoded, 8), readU32be(encoded, 12))
        require(a == ARGON) { "kdfParams are not the suite's Argon2id parameters" }
        return a to encoded.copyOfRange(16, 32)
    }

    fun encodeKdfParams(a: ArgonParams, salt: ByteArray): ByteArray = concat(u32be(a.t), u32be(a.m), u32be(a.p), u32be(a.dkLen), salt)

    // ---- identity ----

    fun deserializeIdentityPrivate(b: ByteArray): IdentityPrivate {
        val (kem, sign) = splitLp(b, 2)
        require(kem.size == 32 && sign.size == 32) { "an identity private key is two 32-byte seeds" }
        return IdentityPrivate(kem, sign)
    }

    fun deserializeIdentityPublic(b: ByteArray): IdentityPublic {
        val (kem, sign) = splitLp(b, 2)
        require(kem.size == 1216 && sign.size == 32) { "an identity public key is 1216 + 32 bytes" }
        return IdentityPublic(kem, sign)
    }

    fun identityPublic(priv: IdentityPrivate): IdentityPublic = IdentityPublic(xwingKey(priv.kemSeed).publicKey, p.ed25519Public(priv.signSeed))

    /**
     * The grant statement a scope grant's signature covers (ADR 0028 decision 6). [recipientIdentityPublicKey] is the
     * recipient's serialized public bundle as the wire carries it; the statement binds its SHA-256.
     */
    fun grantStatement(scopeId: String, scopeKind: String, epoch: Long, recipientUserId: String, recipientIdentityPublicKey: ByteArray, wrappedGrant: ByteArray): ByteArray =
        encodeContext(
            Purposes.STATEMENT,
            Context(
                "grant",
                "scopeId" to scopeId, "scopeKind" to scopeKind, "epoch" to epoch, "recipientUserId" to recipientUserId,
                "recipientIdentityDigest" to p.sha256(recipientIdentityPublicKey), "wrappedGrantDigest" to p.sha256(wrappedGrant),
            ),
        )

    companion object {
        val ARGON = ArgonParams(t = 3, m = 32768, p = 1, dkLen = 32)
        private const val SYM: Byte = 0x02
        private const val ASYM: Byte = 0x01
        private const val FIELD: Byte = 0x03

        /** `v1.json#chunk_frame_size`: every frame but the last carries exactly [FRAME_PLAINTEXT] bytes; an empty blob is
         *  one frame carrying none. */
        const val FRAME_PLAINTEXT = 262_144
        const val FRAME_NONCE = 12
        const val FRAME_TAG = 16
        const val FRAME_OVERHEAD = FRAME_NONCE + FRAME_TAG

        /** How many frames [size] plaintext bytes seal to (at least one). */
        fun chunkCount(size: Long): Int = maxOf(1L, (size + FRAME_PLAINTEXT - 1) / FRAME_PLAINTEXT).toInt()

        /** X-Wing's label, `\.//^\`. */
        private val XWING_LABEL = byteArrayOf(0x5c, 0x2e, 0x2f, 0x2f, 0x5e, 0x5c)
    }
}
