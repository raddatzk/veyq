package me.raddatz.veyq.mobile.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

/**
 * The JVM's and Android's primitives (ADR 0123): BouncyCastle's lightweight API, used directly rather than through a
 * JCA provider, so the same code runs on a desktop JVM and on every Android version without a provider to register.
 */
object BouncyCastlePrimitives : Primitives {
    private val random = SecureRandom()

    override fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    override fun sha256(data: ByteArray): ByteArray =
        ByteArray(32).also { out -> SHA256Digest().apply { update(data, 0, data.size); doFinal(out, 0) } }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        ByteArray(32).also { out -> HMac(SHA256Digest()).apply { init(KeyParameter(key)); update(data, 0, data.size); doFinal(out, 0) } }

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        ByteArray(length).also { out ->
            HKDFBytesGenerator(SHA256Digest()).apply {
                init(HKDFParameters(ikm, salt.takeIf { it.isNotEmpty() }, info))
                generateBytes(out, 0, length)
            }
        }

    private fun gcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray) =
        GCMBlockCipher.newInstance(AESEngine.newInstance()).apply {
            require(key.size == 32 && nonce.size == 12) { "AES-256-GCM takes a 32-byte key and a 12-byte nonce" }
            init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        }

    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val c = gcm(true, key, nonce, aad)
        val out = ByteArray(c.getOutputSize(plaintext.size))
        val n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray {
        val c = gcm(false, key, nonce, aad)
        val out = ByteArray(c.getOutputSize(ciphertextAndTag.size))
        return try {
            val n = c.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, out, 0)
            c.doFinal(out, n)
            out
        } catch (e: InvalidCipherTextException) {
            throw OpenRefused("aead")
        }
    }

    override fun argon2id(password: ByteArray, salt: ByteArray, t: Int, mKiB: Int, p: Int, length: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(t)
            .withMemoryAsKB(mKiB)
            .withParallelism(p)
            .withSalt(salt)
            .build()
        return ByteArray(length).also { Argon2BytesGenerator().apply { init(params) }.generateBytes(password, it) }
    }

    override fun ed25519Public(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    override fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray =
        Ed25519Signer().apply { init(true, Ed25519PrivateKeyParameters(seed, 0)); update(message, 0, message.size) }.generateSignature()

    override fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(publicKey, 0)); update(message, 0, message.size) }.verifySignature(signature)
    } catch (e: IllegalArgumentException) {
        false
    }

    override fun x25519(scalar: ByteArray, u: ByteArray?): ByteArray = ByteArray(32).also { out ->
        if (u == null) X25519.scalarMultBase(scalar, 0, out, 0) else require(X25519.calculateAgreement(scalar, 0, u, 0, out, 0)) { "X25519 produced the all-zero point" }
    }

    private val KEM768: MLKEMParameters = MLKEMParameters.ml_kem_768

    /** The decapsulation key handed back is the 64-byte seed `d ‖ z`; BouncyCastle re-expands it per use. */
    override fun mlKem768KeyGen(d: ByteArray, z: ByteArray): Pair<ByteArray, ByteArray> {
        val seed = concat(d, z)
        return MLKEMPrivateKeyParameters(KEM768, seed).publicKey to seed
    }

    override fun mlKem768Encaps(encapsulationKey: ByteArray, m: ByteArray?): Pair<ByteArray, ByteArray> {
        val pk = MLKEMPublicKeyParameters(KEM768, encapsulationKey)
        val sw = MLKEMGenerator.internalGenerateEncapsulated(pk, m ?: randomBytes(32))
        return sw.encapsulation to sw.secret
    }

    override fun mlKem768Decaps(decapsulationKey: ByteArray, ciphertext: ByteArray): ByteArray =
        MLKEMExtractor(MLKEMPrivateKeyParameters(KEM768, decapsulationKey)).extractSecret(ciphertext)
}
