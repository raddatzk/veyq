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
        keyChain()
        identityPin()
        scopeSuccession()
        joinRecords()
        valueKeys()
        return failures.toList()
    }

    private val joins = JoinRecords(suite)

    private fun joinRecords() {
        val ik = v.obj("invite_key")
        val owner = ik.obj("owner")
        check("invite_key statement and owner signature") {
            val c = ik.obj("context")
            val stmt = joins.inviteKeyStatement(c.str("tenantId"), c.str("inviteId"), ik.hex("bundle"), c.long("expiresAt"))
            eqHex(stmt, ik.str("statement"))
            eqHex(p.ed25519Sign(v.obj("grant_signature").hex("sign_seed"), stmt), ik.str("signature"))
            eqHex(trust.fingerprintHash(owner.hex("bundle")), owner.str("fingerprint_hash"))
        }
        val jr = v.obj("join_record")
        val at = jr.obj("context")
        check("join_record decodes, verifies and encodes back") {
            val rec = joins.decode(jr.hex("record")) ?: fail("did not decode")
            eqHex(rec.identityPublicKey, jr.str("joiner_bundle"))
            eqHex(rec.secretKeyProofPublicKey, jr.str("secret_key_proof_public_key"))
            eqHex(rec.inviteSignature, jr.str("invite_signature"))
            eqHex(rec.identitySignature, jr.str("identity_signature"))
            eqHex(joins.joinRecordStatement(at.str("tenantId"), at.str("userId"), rec), jr.str("statement"))
            eqHex(joins.encode(rec), jr.str("record"))
            if (!joins.verifies(at.str("tenantId"), at.str("userId"), rec)) fail("the vector's record does not verify")
        }
        check("join_record refuses another address and a broken signature") {
            val rec = joins.decode(jr.hex("record")) ?: fail("did not decode")
            if (joins.verifies(at.str("tenantId"), "c0ffee00-0000-4000-8000-0000000000ff", rec)) fail("verified for another member")
            for (i in 0 until 3) {
                val broken = joins.decode(jr.hex("record"))!!
                val sig = listOf(broken.inviteKeySignature, broken.inviteSignature, broken.identitySignature)[i]
                sig[0] = (sig[0] + 1).toByte()
                if (joins.verifies(at.str("tenantId"), at.str("userId"), broken)) fail("signature $i was not checked")
            }
            if (joins.decode(jr.hex("record").copyOfRange(0, 40)) != null) fail("a truncated record decoded")
        }
        val je = v.obj("join_endorsement")
        check("join_endorsement") {
            val c = je.obj("context")
            val opened = je.hex("sealed_join_record")
            eqHex(p.sha256(opened), je.str("record_digest"))
            val stmt = joins.endorsementStatement(c.str("tenantId"), c.str("userId"), c.str("signerUserId"), opened)
            eqHex(stmt, je.str("statement"))
            val ownerSign = owner.hex("sign_public")
            if (!joins.endorsementVerifies(ownerSign, c.str("tenantId"), c.str("userId"), c.str("signerUserId"), opened, je.hex("signature"))) fail("does not verify")
            if (joins.endorsementVerifies(ownerSign, c.str("tenantId"), c.str("userId"), c.str("signerUserId"), opened + byteArrayOf(0), je.hex("signature"))) fail("another record verified")
        }
    }

    private fun valueKeys() {
        val vk = v.obj("value_key")
        val sym = v.obj("seal").obj("sym")
        val fields = v.obj("field_seal")
        for ((purpose, el) in vk.obj("addresses")) {
            check("value_key.addresses.$purpose") {
                val (schema, ctx) = when {
                    sym[purpose] != null -> Purposes.SEAL to context(purpose, Purposes.SEAL, sym.obj(purpose).obj("context"))
                    else -> Purposes.FIELD to context(purpose, Purposes.FIELD, fields.obj(purpose).obj("context"))
                }
                eqHex(joins.valueAddress(schema, ctx), (el as JsonPrimitive).content)
            }
        }
        check("value_key covers exactly the value purposes") { eq(vk.obj("addresses").keys, JoinRecords.VALUE_PURPOSES) }
        check("value_key opens a field top-down") { eqHex(sym.obj("value-key").hex("plaintext"), fields.obj("member-name").str("parent_key")) }
    }

    private val trust = Trust(suite)

    private fun JsonObject.hexOrNull(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { unhex(it) }

    private fun link(o: JsonObject) = Trust.KeyChainLink(
        o.long("index"), o.hexOrNull("previous_head"), o.hex("identity_public_key"), o.hex("secret_key_proof_public_key"),
        o.hexOrNull("identity_signature"), o.hexOrNull("proof_signature"),
    )

    private fun keyChain() {
        val k = v.obj("key_chain")
        val userId = k.str("user_id")
        val raw = k["links"]!!.jsonArray.map { it.jsonObject }
        val links = raw.map(::link)
        raw.forEachIndexed { i, o ->
            check("key_chain.links[$i] statement and head") {
                val stmt = if (i == 0) trust.keyChainGenesisStatement(userId, links[0])
                else trust.keyChainStepStatement(userId, i.toLong(), links[i].previousHead!!, links[i - 1], links[i])
                eqHex(stmt, o.str("statement"))
                eqHex(trust.keyChainHead(stmt), o.str("head"))
            }
        }
        check("key_chain verifies") {
            val ok = trust.verifyKeyChain(userId, links, null) as? Trust.KeyChainVerdict.Ok ?: fail("refused")
            eqHex(ok.head, raw.last().str("head"))
            eq(ok.index, links.size - 1)
            val from = trust.verifyKeyChain(userId, links, unhex(raw[1].str("head"))) as? Trust.KeyChainVerdict.Ok ?: fail("refused from step 1")
            eq(from.fromIndex, 1)
        }
        check("key_chain refuses a head not on it") {
            val r = trust.verifyKeyChain(userId, links, ByteArray(32)) as? Trust.KeyChainVerdict.Refused ?: fail("accepted")
            eq(r.reason, Trust.KeyChainRefusal.HEAD_NOT_IN_CHAIN)
        }
        check("key_chain refuses another account's chain") {
            if (trust.verifyKeyChain("c0ffee00-0000-4000-8000-0000000000ff", links, null) !is Trust.KeyChainVerdict.Refused) fail("accepted")
        }
        for ((name, el) in k.obj("failures")) {
            val f = el.jsonObject
            check("key_chain.failures.$name") {
                val r = trust.verifyKeyChain(userId, links.take(2) + link(f), null) as? Trust.KeyChainVerdict.Refused ?: fail("accepted")
                eq(r.step, 2)
                eq(r.reason.name.lowercase(), f.str("reason"))
            }
        }
        check("fingerprint hash") {
            val fp = v.obj("fingerprint")
            eqHex(trust.fingerprintHash(IdentityPublic(fp.hex("kem"), fp.hex("sign")).serialize()), fp.str("hash"))
        }
    }

    private fun identityPin() {
        val ip = v.obj("identity_pin")
        val mk = ip.hex("master_key")
        check("identity_pin index key") { eqHex(trust.identityPinIndexKey(mk), ip.str("k_pin_index")) }
        ip["pin_ids"]!!.jsonArray.forEachIndexed { i, e ->
            check("identity_pin.pin_ids[$i]") { eqHex(trust.identityPinId(ip.hex("k_pin_index"), e.jsonObject.str("userId")), e.jsonObject.str("pinId")) }
        }
        check("identity_pin entry bytes") { eq(ip.int("entry_bytes"), Trust.PIN_ENTRY_BYTES) }
        for (which in listOf("empty", "two")) {
            val s = ip.obj("set").obj(which)
            check("identity_pin.set.$which") {
                val entries = s["entries"]!!.jsonArray.map { it.jsonObject.hex("pinId") to it.jsonObject.hex("entry") }
                eqHex(trust.identityPinSetDigest(entries), s.str("digest"))
            }
        }
        check("identity_pin set refuses one id twice") {
            val e = ip.obj("set").obj("two")["entries"]!!.jsonArray[0].jsonObject
            if (runCatching { trust.identityPinSetDigest(listOf(e.hex("pinId") to e.hex("entry"), e.hex("pinId") to ByteArray(1))) }.isSuccess) fail("accepted")
        }
        val sym = v.obj("seal").obj("sym")
        check("identity_pin entry codec") {
            val plain = sym.obj("identity-pin").hex("plaintext")
            val (userId, pin) = trust.decodePinEntry(plain) ?: fail("did not decode")
            eqHex(trust.encodePinEntry(userId, pin), hex(plain))
            if (trust.decodePinEntry(plain.copyOf(plain.size - 1)) != null) fail("a short entry decoded")
            val negative = trust.encodePinEntry(userId, pin).decodeToString().trimEnd().replace("\"i\":${pin.index}", "\"i\":-1").let { utf8(it) }
            if (trust.decodePinEntry(negative.copyOf(Trust.PIN_ENTRY_BYTES).also { it.fill(0x20, negative.size) }) != null) fail("a negative index decoded")
        }
        check("identity_pin head codec") {
            val plain = sym.obj("identity-pins").hex("plaintext")
            val (count, digest) = trust.decodePinHead(plain) ?: fail("did not decode")
            eqHex(trust.encodePinHead(count, digest), hex(plain))
            if (trust.decodePinHead(utf8(plain.decodeToString().replace("\"version\":2", "\"version\":1"))) != null) fail("version 1 decoded")
        }
    }

    private fun scopeSuccession() {
        val s = v.obj("scope_succession")
        check("scope_succession keys") {
            val (pk, seed) = trust.successionKeyPair(s.hex("scope_key"))
            eqHex(seed, s.str("succession_seed"))
            eqHex(pk, s.str("succession_public_key"))
            val (nextPk, nextSeed) = trust.successionKeyPair(s.hex("next_scope_key"))
            eqHex(nextSeed, s.str("next_succession_seed"))
            eqHex(nextPk, s.str("next_succession_public_key"))
        }
        check("scope_succession statement") {
            val stmt = trust.scopeSuccessionStatement(s.str("scope_id"), s.long("next_epoch"), s.hex("next_succession_public_key"))
            eqHex(stmt, s.str("statement"))
            eqHex(p.ed25519Sign(s.hex("succession_seed"), stmt), s.str("signature"))
        }
        val link = Trust.SuccessionLink(s.long("next_epoch"), s.hex("next_succession_public_key"), s.hex("signature"))
        check("scope_succession path") {
            if (!trust.verifySuccessionPath(s.str("scope_id"), s.long("epoch"), s.hex("succession_public_key"), listOf(link), s.long("next_epoch"), s.hex("next_succession_public_key"))) fail("refused")
        }
        check("scope_succession path refuses a gap, a duplicate and another target") {
            val sc = s.str("scope_id")
            val held = s.hex("succession_public_key")
            val target = s.hex("next_succession_public_key")
            if (trust.verifySuccessionPath(sc, s.long("epoch") - 1, held, listOf(link), s.long("next_epoch"), target)) fail("a gap passed")
            if (trust.verifySuccessionPath(sc, s.long("epoch"), held, listOf(link, link), s.long("next_epoch"), target)) fail("a duplicate passed")
            if (trust.verifySuccessionPath(sc, s.long("epoch"), held, listOf(link), s.long("next_epoch"), held)) fail("another target passed")
            if (trust.verifySuccessionPath(sc, s.long("epoch"), target, listOf(link), s.long("next_epoch"), target)) fail("another held key passed")
        }
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
