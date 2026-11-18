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
import me.raddatz.veyq.mobile.trust.SafetyNumbers

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
        pairingChannel()
        secretKeyText()
        emergencyKitToken()
        keyChain()
        identityPin()
        scopeSuccession()
        joinRecords()
        valueKeys()
        slugs()
        shareWrite()
        reportBundle()
        blobs()
        secretKeyProof()
        authAttestation()
        rotationStage()
        verification()
        safetyCode()
        return failures.toList()
    }

    /** `#chunk_frame`, `#chunk_frame_size`, `#file_blob`'s chain, and the blob-meta plaintext the layout writes. */
    private fun blobs() {
        val f = v.obj("chunk_frame")
        check("chunk_frame") {
            eqHex(suite.sealFrame(f.hex("contentKey"), f.int("index"), f.int("total"), f.hex("plaintext"), f.hex("nonce")), f.str("frame"))
            eqHex(suite.openFrame(f.hex("contentKey"), f.int("index"), f.int("total"), f.hex("frame")), f.str("plaintext"))
        }
        check("chunk_frame bound to its place") {
            val refused = runCatching { suite.openFrame(f.hex("contentKey"), f.int("index") + 1, f.int("total"), f.hex("frame")) }.isFailure &&
                runCatching { suite.openFrame(f.hex("contentKey"), f.int("index"), f.int("total") + 1, f.hex("frame")) }.isFailure
            if (!refused) fail("a frame opened at another index or total")
        }
        val size = v.obj("chunk_frame_size")
        check("chunk_frame_size") {
            eq(V1.FRAME_PLAINTEXT, size.int("plaintextBytes"))
            eq(V1.FRAME_NONCE, size.int("nonceBytes"))
            eq(V1.FRAME_TAG, size.int("tagBytes"))
            eq(listOf(1, 1, 1, 2), listOf(0L, 1L, 262_144L, 262_145L).map { V1.chunkCount(it) })
        }
        val fb = v.obj("file_blob")
        check("file_blob") {
            val node = fb.obj("node")
            val nodeCtx = context("content-key", Purposes.SEAL, node.obj("context"))
            eqHex(suite.seal(nodeCtx, fb.hex("parentContentKey"), node.hex("plaintext"), node.hex("nonce")), node.str("blob"))
            val key = fb.obj("blobKey")
            val keyCtx = context("blob-key", Purposes.SEAL, key.obj("context"))
            eqHex(encodeContext(Purposes.SEAL, keyCtx), key.str("context_bytes"))
            eqHex(suite.seal(keyCtx, node.hex("plaintext"), key.hex("plaintext"), key.hex("nonce")), key.str("blob"))
        }
        val dk = v.obj("drop_key")
        check("drop_key") {
            // the codec's bytes, the public half from the seed, and both seals at their nonces
            val seed = dk.hex("seed")
            eqHex(suite.serializeDropKey(seed), dk.str("serialized"))
            eqHex(suite.dropKeyPublic(seed), dk.str("publicKey"))
            eqHex(suite.serializeHybridPublic(suite.dropKeyPublic(seed)), dk.str("serializedPublic"))
            eqHex(suite.deserializeDropKey(dk.hex("serialized")), dk.str("seed"))
            eqHex(suite.deserializeHybridPublic(dk.hex("serializedPublic")), dk.str("publicKey"))
            val priv = dk.obj("sealedPrivate")
            val privCtx = context("drop-private-key", Purposes.SEAL, priv.obj("context"))
            eqHex(suite.seal(privCtx, priv.hex("kek"), priv.hex("plaintext"), priv.hex("nonce")), priv.str("blob"))
            eqHex(suite.deserializeDropKey(suite.open(privCtx, priv.hex("kek"), priv.hex("blob"))), dk.str("seed"))
            val pub = dk.obj("sealedPublic")
            val pubCtx = context("drop-public-key", Purposes.SEAL, pub.obj("context"))
            eqHex(suite.seal(pubCtx, pub.hex("kek"), pub.hex("plaintext"), pub.hex("nonce")), pub.str("blob"))
            eqHex(suite.deserializeHybridPublic(suite.open(pubCtx, pub.hex("kek"), pub.hex("blob"))), dk.str("publicKey"))
            // the step into a guest's folder: the identity key sealed to this public half opens under this seed
            val up = v.obj("seal").obj("asym").obj("upload-identity-key")
            eqHex(suite.openAs(context("upload-identity-key", Purposes.SEAL_TO, up.obj("context")), suite.xwingKey(seed), up.hex("blob")), up.str("plaintext"))
            // strict reads: trailing bytes and a short field are refused
            for (bad in listOf(dk.hex("serialized") + byteArrayOf(0), dk.hex("serialized").copyOfRange(0, 35))) {
                if (runCatching { suite.deserializeDropKey(bad) }.isSuccess) fail("a malformed drop key read")
            }
        }
        val meta = v.obj("seal").obj("sym").obj("blob-meta")
        check("blob-meta plaintext is the layout's") {
            val opened = BlobMeta.decode(meta.hex("plaintext")) ?: fail("no meta")
            eqHex(BlobMeta.encode(opened.type, opened.name, opened.modifiedAt), meta.str("plaintext"))
        }
    }

    /** `#report_bundle`: a report share's keys, the report's body (NFC, padded) and the contact, byte for byte. */
    private fun reportBundle() {
        val b = v.obj("report_bundle")
        for ((i, el) in b["keys"]!!.jsonArray.withIndex()) {
            val o = el.jsonObject
            check("report_bundle keys $i") {
                val keys = o["entries"]!!.jsonArray.map { e ->
                    val x = e.jsonObject
                    Report.Key(x.str("role"), (x["ref"] as? JsonPrimitive)?.takeIf { it.isString }?.content, x.hex("key"))
                }
                eqHex(Report.encodeKeys(keys), o.str("encoded"))
            }
        }
        val body = b.obj("body")
        check("report_bundle body") { eqHex(Report.encodeBody(body.hex("report_key"), body.str("reason"), body.str("context")), body.str("encoded")) }
        val contact = b.obj("contact")
        check("report_bundle contact") { eqHex(Report.encodeContact(contact.str("contact")), contact.str("encoded")) }
        for ((purpose, el) in v.obj("field_seal")) {
            if (el !is JsonObject) continue
            val c = el.jsonObject
            check("field key of $purpose's envelope") {
                eqHex(suite.fieldKeyOf(context(purpose, Purposes.FIELD, c.obj("context")), c.hex("parent_key"), c.hex("envelope")), c.str("field_key"))
            }
        }
    }

    /** `#share_write_token`: the token and its verifier, and a token bound to its link (another id, another token). */
    private fun shareWrite() {
        val w = v.obj("share_write_token")
        check("share_write_token") {
            val token = suite.shareWriteToken(w.hex("kShare"), w.str("shareId"))
            eqHex(token, w.str("token"))
            eqHex(suite.p.sha256(token), w.str("verifier"))
            eqHex(suite.shareWriteToken(w.hex("kShare"), w.str("other_share_id")), w.str("other_token"))
        }
    }

    /** `#slug_token` (its own NFC step: the slug is stored decomposed) and `#name_folding` (both foldings). */
    private fun slugs() {
        val t = v.obj("slug_token")
        check("slug_token") { eqHex(suite.slugToken(t.hex("contentKey"), t.str("parentSlot"), t.str("slug")), t.str("token")) }
        val folding = v.obj("name_folding")
        for ((section, fold) in listOf<Pair<String, (String) -> String>>("slugify" to Naming::slugify, "fileNameSlug" to Naming::fileNameSlug)) {
            for (case in folding[section]!!.jsonArray) {
                val o = case.jsonObject
                check("name_folding $section ${o.str("exercises")}") { eq(fold(o.str("name")), o.str("slug")) }
            }
        }
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
        check("fingerprint digits") {
            val fp = v.obj("fingerprint")
            eq(SafetyNumbers.fingerprintDigits(fp.hex("hash")), fp.str("digits"))
        }
        v.obj("fingerprint")["chain_code"]!!.jsonArray.forEachIndexed { i, e ->
            val c = e.jsonObject
            check("fingerprint chain_code[$i]") { eq(SafetyNumbers.chainCodeDigits(c.hex("head")), c.str("digits")) }
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

    /**
     * `#emergency_kit_token` in both directions: each case's token read back to its email, Secret Key and server (none
     * where the case has none) and written from those three byte for byte ([EmergencyKit.write]), and each refused token
     * refused — a refused server refused by the writer too.
     */
    private fun emergencyKitToken() {
        val section = v.obj("emergency_kit_token")
        for ((i, e) in section["cases"]!!.jsonArray.withIndex()) {
            val c = e.jsonObject
            check("emergency_kit_token[$i]") {
                val kit = EmergencyKit.read(p, c.str("token")) ?: fail("refused")
                eq(kit.email, c.str("email"))
                eq(SecretKeyText.format(p, kit.secretKey), c.str("secret_key"))
                eq(kit.server, (c["server"] as? JsonPrimitive)?.content)
            }
            check("emergency_kit_token[$i] written") {
                val key = SecretKeyText.read(p, c.str("secret_key")) ?: fail("the case's Secret Key does not read")
                try {
                    eq(EmergencyKit.write(p, c.str("email"), key, (c["server"] as? JsonPrimitive)?.content), c.str("token"))
                } finally {
                    key.fill(0)
                }
            }
        }
        // Not a vector: the writer refuses each server the refused cases name by their `why` — a kit no reader accepts.
        check("emergency_kit_token's writer refuses a server that is no origin") {
            val key = ByteArray(32)
            for (bad in listOf("https://veyq.example.com/login", "https://veyq.example.com/", "https://veyq.example.com:443",
                "https://Veyq.example.com", "veyq.example.com", "javascript:alert(1)", "")) {
                if (runCatching { EmergencyKit.write(p, "a@example.org", key, bad) }.isSuccess) fail("wrote a kit for the server '$bad'")
            }
        }
        for (e in section["refused"]!!.jsonArray) {
            val c = e.jsonObject
            check("emergency_kit_token refuses ${c.str("why")}") {
                if (EmergencyKit.read(p, c.str("token")) != null) fail("read as a kit")
            }
        }
        // Not a vector: the first case's token with its Secret Key's last check symbol changed — the key must be read
        // by `SecretKeyText.read`, whose check refuses it, not merely decoded.
        check("emergency_kit_token refuses a Secret Key with a wrong check symbol") {
            val c = section["cases"]!!.jsonArray[2].jsonObject
            val sk = c.str("secret_key")
            fun token(key: String) = "veyqkit:" + kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
                .encode("""{"v":3,"email":"${c.str("email")}","sk":"$key"}""".encodeToByteArray())
            if (EmergencyKit.read(p, token(sk)) == null) fail("the case rebuilt with its own key did not read")
            if (EmergencyKit.read(p, token(sk.dropLast(1) + (if (sk.last() == 'D') 'E' else 'D'))) != null) fail("a mistyped Secret Key read as a kit")
        }
    }

    /**
     * `#pairing_channel` whole (ADR 0059 §4, §11): the handle and channel key, each MAC's statement bytes and value and
     * what it does not answer for, the check number as `#fingerprint`'s first group, the payload grammar's cases under
     * both markers, and the grant's plaintext — read, written byte for byte, refused.
     */
    private fun pairingChannel() {
        val pc = v.obj("pairing_channel")
        val pairing = Pairing(p)
        val secret = pc.hex("secret")
        val key = pc.hex("channel_key")
        check("pairing_channel handle and channel key") {
            eqHex(pairing.handle(secret), pc.str("handle"))
            eqHex(pairing.channelKey(secret), pc.str("channel_key"))
        }
        val answer = pc.obj("answer")
        val signIn = pc.obj("sign_in_answer")
        val envelope = pc.obj("envelope")
        val transport = answer.hex("transport_key")
        val code = signIn.str("code")
        check("pairing_channel answer") {
            eqHex(encodeContext(Purposes.STATEMENT, Context("pairing-answer", "transportKey" to transport)), answer.str("statement"))
            eqHex(pairing.answerMac(key, transport), answer.str("mac"))
            if (!pairing.answerVerifies(key, transport, answer.hex("mac"))) fail("the vector's MAC did not verify")
        }
        check("pairing_channel sign_in_answer") {
            val t = signIn.hex("transport_key")
            eqHex(encodeContext(Purposes.STATEMENT, Context("pairing-sign-in-answer", "transportKey" to t, "code" to code)), signIn.str("statement"))
            eqHex(pairing.signInAnswerMac(key, t, code), signIn.str("mac"))
            if (!pairing.signInAnswerVerifies(key, t, code, signIn.hex("mac"))) fail("the vector's MAC did not verify")
        }
        check("pairing_channel envelope") {
            val e = envelope.hex("envelope")
            eqHex(encodeContext(Purposes.STATEMENT, Context("pairing-envelope", "envelope" to e)), envelope.str("statement"))
            eqHex(pairing.envelopeMac(key, e), envelope.str("mac"))
            if (!pairing.envelopeVerifies(key, e, envelope.hex("mac"))) fail("the vector's MAC did not verify")
        }
        // Each MAC answers for its own statement only: the other direction's, another code, the handle as a key.
        check("pairing_channel MACs answer for nothing else") {
            val other = if (code == "000000") "000001" else "000000"
            if (pairing.signInAnswerVerifies(key, transport, code, answer.hex("mac"))) fail("an add-a-device answer passed as a sign-in one")
            if (pairing.answerVerifies(key, transport, signIn.hex("mac"))) fail("a sign-in answer passed as an add-a-device one")
            if (pairing.signInAnswerVerifies(key, signIn.hex("transport_key"), other, signIn.hex("mac"))) fail("a sign-in answer passed for another code")
            if (pairing.answerVerifies(pc.hex("handle"), transport, answer.hex("mac"))) fail("the handle served as the channel key")
            if (pairing.envelopeVerifies(key, envelope.hex("envelope") + byteArrayOf(0), envelope.hex("mac"))) fail("a longer envelope verified")
        }
        check("pairing_channel check number is the fingerprint's first group") {
            val fp = v.obj("fingerprint")
            eq(pairing.checkNumber(IdentityPublic(fp.hex("kem"), fp.hex("sign"))), fp.str("digits").split(' ')[0])
        }
        for (e in pc.obj("payloads")["cases"]!!.jsonArray) {
            val c = e.jsonObject
            check("pairing_channel.payloads ${c.str("name")}") {
                for ((marker, field) in listOf(PairingMarker.ADD_DEVICE to "veyqpair", PairingMarker.SIGN_IN to "veyqsignin")) {
                    val expected = (c[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    eq(PairingPayload.parse(c.str("text"), marker)?.let { hex(it) }, expected)
                }
            }
        }
        val grant = pc.obj("grant")
        for (e in grant["accepted"]!!.jsonArray) {
            val c = e.jsonObject
            check("pairing_channel.grant reads ${c.str("name")}") {
                val g = PairingGrant.read(c.str("json")) ?: fail("refused")
                eq(g.email, c.str("email"))
                eqHex(g.masterKey, c.str("master_key"))
                eq(g.refreshToken, c.str("refresh_token"))
                eq(g.label, c.str("label"))
                eq(g.marks, c["marks"]?.jsonObject?.let { m ->
                    PairingMarks(m.long("pins"), m.long("register"), m.obj("epochs").mapValues { (_, n) -> n.jsonPrimitive.long })
                })
                if ((c["written"] as? JsonPrimitive)?.content == "true") eq(PairingGrant.write(g), c.str("json"))
            }
        }
        for (e in grant["refused"]!!.jsonArray) {
            val c = e.jsonObject
            check("pairing_channel.grant refuses ${c.str("name")}") {
                if (PairingGrant.read(c.str("json")) != null) fail("read as a grant")
            }
        }
    }

    private val proofs = AccountProofs(p)

    private fun JsonObject.wrap(k: String) = obj(k).let { AccountProofs.ProofWrap(it.hex("wrapped_key"), it.hex("kdf_params")) }

    /** `#secret_key_proof`: the proof key from the Secret Key, and all four statements with their signatures. */
    private fun secretKeyProof() {
        val s = v.obj("secret_key_proof")
        val key = s.obj("key")
        val secretKey = key.hex("secret_key")
        check("secret_key_proof key") {
            eqHex(proofs.secretKeyProofSeed(secretKey), key.str("private_key"))
            eqHex(proofs.secretKeyProofPublic(secretKey), key.str("public_key"))
        }
        val r = s.obj("rotation")
        val cp = s.obj("change_password")
        val rc = s.obj("recovery")
        val ce = s.obj("change_email")
        val cases = listOf(
            "rotation" to { proofs.rotationProofStatement(
                r.hex("current_identity_public_key"), r.hex("identity_public_key"), r.hex("wrapped_identity_private_key"),
                r.wrap("password_wrap"), r.wrap("recovery_wrap"), r.hex("key_chain_step_statement"),
                r.hex("current_auth_public_key"), r.hex("auth_public_key"), r.hex("secret_key_proof_public_key"),
                r.str("stage_id"), r.hex("staged_grants_digest"),
            ) } to r,
            "change_password" to { proofs.passwordChangeProofStatement(cp.hex("current_auth_public_key"), cp.hex("auth_public_key"), cp.wrap("password_wrap")) } to cp,
            "recovery" to { proofs.recoveryProofStatement(rc.str("token_utf8"), rc.hex("auth_public_key"), rc.wrap("password_wrap")) } to rc,
            "change_email" to { proofs.emailChangeProofStatement(ce.hex("current_auth_public_key"), ce.hex("auth_public_key"), ce.str("email_utf8")) } to ce,
        )
        for ((named, o) in cases) {
            val (name, build) = named
            check("secret_key_proof.$name") {
                eq(o.str("domain"), "veyq/v1/secret-key-proof/${name.replace('_', '-')}")
                val stmt = build()
                eqHex(stmt, o.str("statement"))
                eqHex(proofs.secretKeyProofSign(secretKey, stmt), o.str("signature"))
                if (!p.ed25519Verify(key.hex("public_key"), stmt, o.hex("signature"))) fail("the vector's signature did not verify")
            }
        }
        check("secret_key_proof.rotation possession") {
            eqHex(proofs.secretKeyProofPublic(r.hex("new_secret_key")), r.str("secret_key_proof_public_key"))
            eqHex(proofs.secretKeyProofSign(r.hex("new_secret_key"), cases[0].first.second()), r.str("possession_signature"))
        }
        check("secret_key_proof domains answer for no other flow") {
            if (cases.map { it.second.str("domain") }.toSet().size != 4) fail("two flows share a domain")
        }
    }

    /** `#auth_attestation`: the change-email and erase-account statements and the password change's raw-key signature. */
    private fun authAttestation() {
        val a = v.obj("auth_attestation")
        val ce = a.obj("change_email")
        check("auth_attestation.change_email") {
            val stmt = proofs.emailChangeAttestationStatement(ce.str("user_id"), ce.str("email_utf8"), ce.hex("auth_public_key"))
            eqHex(stmt, ce.str("statement"))
            eqHex(p.ed25519Public(ce.hex("current_auth_private_key")), ce.str("current_auth_public_key"))
            eqHex(p.ed25519Sign(ce.hex("current_auth_private_key"), stmt), ce.str("signature"))
        }
        val ea = a.obj("erase_account")
        check("auth_attestation.erase_account") {
            val stmt = proofs.eraseAccountAttestationStatement(ea.str("user_id"), unb64(ea.str("nonce_b64")))
            eqHex(stmt, ea.str("statement"))
            eqHex(p.ed25519Sign(ea.hex("auth_private_key"), stmt), ea.str("signature"))
        }
        val cp = a.obj("change_password")
        check("auth_attestation.change_password") {
            eq(cp.str("statement"), cp.str("auth_public_key"))
            eqHex(p.ed25519Public(cp.hex("current_auth_private_key")), cp.str("current_auth_public_key"))
            eqHex(proofs.passwordChangeAttestation(cp.hex("current_auth_private_key"), cp.hex("auth_public_key")), cp.str("signature"))
        }
        check("auth_attestation.change_password is no change-email attestation") {
            val asEmail = proofs.emailChangeAttestationStatement(ce.str("user_id"), ce.str("email_utf8"), cp.hex("auth_public_key"))
            if (p.ed25519Verify(cp.hex("current_auth_public_key"), asEmail, cp.hex("signature"))) fail("verified as a change-email statement")
        }
    }

    /** `#rotation_stage`: the digest over the entries in canonical order, and over none. */
    private fun rotationStage() {
        val s = v.obj("rotation_stage")
        check("rotation_stage digest") {
            val entries = s["entries"]!!.jsonArray.map { it.jsonObject }.map { AccountProofs.StageEntry(it.str("scopeId"), it.long("epoch"), it.hex("wrapped_grant")) }
            eqHex(proofs.rotationStageDigest(entries), s.str("digest"))
            eqHex(proofs.rotationStageDigest(entries.reversed()), s.str("digest"))
            eqHex(proofs.rotationStageDigest(emptyList()), s.str("empty"))
        }
    }

    /** `#verification`: the statement, the verifier's signature and the record. */
    private fun verification() {
        val o = v.obj("verification")
        val c = o.obj("context")
        val verifications = Verifications(suite)
        val a = Verifications.Address(c.str("tenantId"), c.str("verifierUserId"), c.str("subjectUserId"))
        check("verification statement and signature") {
            eqHex(p.ed25519Public(o.hex("sign_seed")), o.str("sign_public"))
            eqHex(verifications.statement(a, o.hex("subject_head"), o.hex("verifier_head")), o.str("statement"))
            val signed = verifications.sign(a, o.hex("subject_head"), o.hex("verifier_head"), o.hex("sign_seed"))
            eqHex(signed.signature, o.str("signature"))
            eqHex(verifications.encode(signed), o.str("record"))
            if (!verifications.verifies(a, signed, o.hex("sign_public"))) fail("refused its own signature")
        }
        check("verification answers for no other subject") {
            val back = verifications.decode(o.hex("record")) ?: fail("record not decoded")
            if (verifications.verifies(a.copy(subjectUserId = "c0ffee00-0000-4000-8000-0000000000aa"), back, o.hex("sign_public"))) fail("verified for another subject")
            if (verifications.decode(o.hex("record") + byteArrayOf(0)) != null) fail("a trailing byte decoded")
        }
    }

    /** `#safety_code`: the `veyqid:2` payload of a hash and a head, and every parse and verdict. */
    private fun safetyCode() {
        val s = v.obj("safety_code")
        check("safety_code payload") {
            eq(SafetyNumbers.payload(s.hex("fingerprint_hash"), s.hex("head")), s.str("payload"))
            eq(SafetyNumbers.fingerprintDigits(s.hex("fingerprint_hash")), v.obj("fingerprint").str("digits"))
        }
        val expected = s.obj("expected")
        for (e in s["cases"]!!.jsonArray) {
            val c = e.jsonObject
            check("safety_code ${c.str("name")}") {
                val parsed = (c["parsed"] as? JsonObject)?.let { o ->
                    when (o.int("version")) {
                        2 -> SafetyNumbers.Parsed.Current(o.str("fingerprint"), o.str("head"))
                        1 -> SafetyNumbers.Parsed.Outdated(o.str("fingerprint"))
                        else -> fail("version ${o.int("version")}")
                    }
                }
                eq(SafetyNumbers.parse(c.str("text")), parsed)
                eq(SafetyNumbers.compareScan(c.str("text"), expected.str("fingerprint"), expected.str("head")).name.lowercase(), c.str("verdict"))
            }
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
