package com.veyq.app.shared.crypto

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * A stand-in for an account's Secret-Key proof key in slice tests (ADR 0060 Decision 2). The client derives the
 * real one from the Secret Key; the server only ever verifies, so any Ed25519 keypair plays the part. Two
 * instances are two different Secret Keys — which is exactly the attacker a bite test needs: someone holding a
 * valid session who signs with a key of their own.
 */
class TestProofKey {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** The raw 32-byte public key, standard base64 — what registration stores. */
    val publicKey: String = pair.public.encoded.let { Base64.getEncoder().encodeToString(it.copyOfRange(it.size - 32, it.size)) }

    /** Sign a statement [SecretKeyProof] built; standard base64, as the wire carries it. */
    fun sign(statement: ByteArray?): String {
        requireNotNull(statement) { "the statement could not be built — a fixture passed non-base64" }
        return Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(statement)
            Base64.getEncoder().encodeToString(sign())
        }
    }
}
