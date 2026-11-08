package me.raddatz.veyq.mobile.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * The suite's conformance against `contracts/crypto/vectors/v1.json`, runnable over any platform's [Primitives]: the
 * JVM test runs it over BouncyCastle, the Apple app's test over its own primitives. It lives in main code, not in a
 * test source set, so a platform whose primitives are written outside Kotlin can still run it.
 *
 * Returns the failures, one line each; an empty list is a pass. [checked] counts the cases that ran, so a run that
 * silently checked nothing is visible.
 */
class Conformance(private val p: Primitives, vectorsJson: String) {
    private val v: JsonObject = Json.parseToJsonElement(vectorsJson).jsonObject
    private val suite = V1(p)
    private val failures = mutableListOf<String>()
    var checked = 0
        private set

    /** [argonAtProductionCost]: the m=32 MiB cases take a few seconds each, worth it on a device, optional on a CI JVM. */
    fun run(argonAtProductionCost: Boolean = true): List<String> {
        check("suite") { eq(v.str("suite"), "v1") }
        primitives()
        kek(argonAtProductionCost)
        if (argonAtProductionCost) auth()
        kdfParams()
        xwing()
        sealSym()
        sealAsym()
        fieldSeal()
        grantSignature()
        secretKeyText()
        return failures.toList()
    }

    private fun primitives() {
        val pr = v.obj("primitives")
        val h = pr.obj("hkdf_sha256_rfc5869_case1")
        check("hkdf") { eqHex(p.hkdfSha256(h.hex("ikm"), h.hex("salt"), h.hex("info"), h.int("length")), h.str("okm")) }
        val g = pr.obj("aes256gcm")
        check("aes256gcm seal") { eqHex(p.aesGcmSeal(g.hex("key"), g.hex("nonce"), g.hex("plaintext"), g.hex("aad")), g.str("ciphertext_and_tag")) }
        check("aes256gcm open") { eqHex(p.aesGcmOpen(g.hex("key"), g.hex("nonce"), g.hex("ciphertext_and_tag"), g.hex("aad")), g.str("plaintext")) }
    }

    private fun params(o: JsonObject) = o.obj("params").let { ArgonParams(it.int("t"), it.int("m"), it.int("p"), it.int("dkLen")) }

    private fun kek(production: Boolean) {
        val k = v.obj("kek")
        k.obj("password_secret_argon2id").let { c ->
            check("kek password+secret") { eqHex(suite.kekPasswordSecret(c.hex("secret_key"), c.str("password_utf8"), c.hex("salt"), params(c)), c.str("kek")) }
        }
        k.obj("device_password_argon2id").let { c ->
            check("kek device+password") { eqHex(suite.kekDevicePassword(c.hex("device_factor"), c.str("password_utf8"), c.hex("salt"), params(c)), c.str("kek")) }
        }
        k.obj("recovery_argon2id").let { c ->
            check("kek recovery") { eqHex(suite.kekRecovery(c.hex("recovery_code"), c.hex("salt"), params(c)), c.str("kek")) }
        }
        if (production) {
            k.obj("recovery_argon2id_m32768").let { c ->
                check("kek recovery m=32768") { eqHex(suite.kekRecovery(c.hex("recovery_code"), c.hex("salt"), params(c)), c.str("kek")) }
            }
        }
    }

    private fun auth() {
        val a = v.obj("auth")
        val s = a.obj("auth_salt")
        check("auth salt") { eqHex(suite.authSalt(s.hex("instance_salt"), s.str("email_utf8")), s.str("auth_salt")) }
        val kp = a.obj("auth_keypair")
        check("auth keypair") {
            val seed = suite.authSeed(kp.str("password_utf8"), kp.hex("auth_salt"))
            eqHex(seed, kp.str("private_key"))
            eqHex(p.ed25519Public(seed), kp.str("public_key"))
        }
    }

    private fun kdfParams() {
        val k = v.obj("kdf_params")
        check("kdfParams") {
            eqHex(suite.encodeKdfParams(params(k), k.hex("salt")), k.str("encoded"))
            val (a, salt) = suite.decodeKdfParams(k.hex("encoded"))
            eq(a, params(k))
            eqHex(salt, k.str("salt"))
        }
        check("kdfParams refuses other parameters") {
            val other = suite.encodeKdfParams(ArgonParams(3, 8192, 1, 32), k.hex("salt"))
            if (runCatching { suite.decodeKdfParams(other) }.isSuccess) fail("m=8192 was accepted")
        }
    }

    private fun xwing() {
        val x = v.obj("xwing")
        check("xwing draft") { eq(x.str("draft"), "draft-connolly-cfrg-xwing-kem-11") }
        x["vectors"]!!.jsonArray.forEachIndexed { i, e ->
            val c = e.jsonObject
            check("xwing[$i]") {
                val key = suite.xwingKey(c.hex("seed"))
                eqHex(key.publicKey, c.str("pk"))
                val (ct, ss) = suite.xwingEncap(key.publicKey, c.hex("eseed"))
                eqHex(ct, c.str("ct"))
                eqHex(ss, c.str("ss"))
                eqHex(suite.xwingDecap(key, c.hex("ct")), c.str("ss"))
            }
        }
    }

    private fun context(purpose: String, schema: Schema, o: JsonObject): Context {
        val kinds = schema[purpose] ?: throw SealContextInvalid("unknown purpose $purpose")
        val fields = o.mapValues { (name, el) ->
            val kind = kinds.firstOrNull { it.first == name }?.second
            val prim = el as? JsonPrimitive
            when {
                prim == null || prim.isString && prim.content == "null" -> null
                kind == FieldKind.UINT -> prim.long
                kind == FieldKind.DIGEST || kind == FieldKind.BYTES -> unhex(prim.content)
                prim.isString -> prim.content
                else -> prim.longOrNull ?: prim.content
            }
        }.filterKeys { it != "purpose" }
        return Context(purpose, fields)
    }

    private fun sealSym() {
        for ((purpose, el) in v.obj("seal").obj("sym")) {
            val c = el.jsonObject
            check("seal.sym $purpose") {
                val ctx = context(purpose, Purposes.SEAL, c.obj("context"))
                eqHex(encodeContext(Purposes.SEAL, ctx), c.str("context_bytes"))
                eqHex(suite.seal(ctx, c.hex("kek"), c.hex("plaintext"), c.hex("nonce")), c.str("blob"))
                eqHex(suite.open(ctx, c.hex("kek"), c.hex("blob")), c.str("plaintext"))
            }
        }
    }

    private fun sealAsym() {
        val recipient = suite.xwingKey(v.obj("xwing")["vectors"]!!.jsonArray[0].jsonObject.hex("sk"))
        for ((purpose, el) in v.obj("seal").obj("asym")) {
            val c = el.jsonObject
            check("seal.asym $purpose") {
                val ctx = context(purpose, Purposes.SEAL_TO, c.obj("context"))
                eqHex(encodeContext(Purposes.SEAL_TO, ctx), c.str("context_bytes"))
                eqHex(suite.sealTo(ctx, c.hex("pk"), c.hex("plaintext"), c.hex("eseed"), c.hex("nonce")), c.str("blob"))
                eqHex(suite.openAs(ctx, recipient, c.hex("blob")), c.str("plaintext"))
            }
        }
        check("seal.asym refuses another slot") {
            val c = v.obj("seal").obj("asym").obj("scope-grant")
            val ctx = context("scope-grant", Purposes.SEAL_TO, c.obj("context"))
            val moved = Context("scope-grant", ctx.fields + ("epoch" to (ctx.fields["epoch"] as Long) + 1))
            if (runCatching { suite.openAs(moved, recipient, c.hex("blob")) }.isSuccess) fail("opened at another epoch")
        }
    }

    private fun fieldSeal() {
        for ((purpose, el) in v.obj("field_seal")) {
            if (el !is JsonObject) continue
            val c = el.jsonObject
            check("field_seal $purpose") {
                val ctx = context(purpose, Purposes.FIELD, c.obj("context"))
                eqHex(encodeContext(Purposes.FIELD, ctx), c.str("context_bytes"))
                eqHex(suite.fieldKey(c.hex("parent_key"), c.hex("salt"), purpose), c.str("field_key"))
                eqHex(suite.sealField(ctx, c.hex("parent_key"), c.hex("plaintext"), c.hex("salt"), c.hex("nonce")), c.str("envelope"))
                eqHex(suite.openField(ctx, c.hex("parent_key"), c.hex("envelope")), c.str("plaintext"))
            }
        }
    }

    private fun grantSignature() {
        val g = v.obj("grant_signature")
        check("grant signing key") { eqHex(p.ed25519Public(g.hex("sign_seed")), g.str("sign_public")) }
        for (which in listOf("personal", "group")) {
            val c = g.obj(which)
            check("grant_signature $which") {
                val ctx = c.obj("context")
                val stmt = suite.grantStatement(
                    ctx.str("scopeId"), ctx.str("scopeKind"), ctx.long("epoch"), ctx.str("recipientUserId"), c.hex("recipient_identity_public_key"), c.hex("wrapped_grant"),
                )
                eqHex(stmt, c.str("statement"))
                if (!p.ed25519Verify(g.hex("sign_public"), stmt, c.hex("signature"))) fail("the vector's signature does not verify")
                val tampered = stmt.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
                if (p.ed25519Verify(g.hex("sign_public"), tampered, c.hex("signature"))) fail("a tampered statement verified")
            }
        }
    }

    private fun secretKeyText() {
        for ((i, e) in v.obj("secret_key_text")["cases"]!!.jsonArray.withIndex()) {
            val c = e.jsonObject
            check("secret_key_text[$i]") {
                eq(SecretKeyText.format(p, c.hex("key")), c.str("text"))
                eqHex(SecretKeyText.read(p, c.str("text").lowercase().replace("-", " ")) ?: fail("did not read back"), c.str("key"))
            }
        }
        check("secret_key_text refuses a typo") {
            val text = v.obj("secret_key_text")["cases"]!!.jsonArray[0].jsonObject.str("text")
            val typo = (if (text[0] == '0') "1" else "0") + text.drop(1)
            if (SecretKeyText.read(p, typo) != null) fail("a typo read as a key")
        }
    }

    // ---- plumbing ----

    private class Mismatch(msg: String) : Exception(msg)

    private fun fail(msg: String): Nothing = throw Mismatch(msg)
    private fun eq(a: Any?, b: Any?) { if (a != b) fail("expected $b, got $a") }
    private fun eqHex(a: ByteArray, expected: String) { val h = hex(a); if (h != expected) fail("expected ${expected.take(32)}…, got ${h.take(32)}…") }

    private inline fun check(name: String, f: () -> Unit) {
        checked++
        try { f() } catch (e: Throwable) { failures += "$name: ${e.message ?: e::class.simpleName}" }
    }

    private fun JsonObject.obj(k: String) = this[k]?.jsonObject ?: fail("missing $k")
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content ?: fail("missing $k")
    private fun JsonObject.hex(k: String) = unhex(str(k))
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.int ?: fail("missing $k")
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.long ?: fail("missing $k")
    @Suppress("unused") private fun JsonElement.o() = jsonObject
}
