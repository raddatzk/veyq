package com.veyq.core.membership

import com.veyq.core.scope.ScopeEntity
import io.quarkus.narayana.jta.QuarkusTransaction
import java.util.UUID

/**
 * Opaque admission and grant-signature bytes for backend tests (ADR 0020 move 6, ADR 0028 Decision 6). The server
 * never computes or verifies either — it stores, copies and relays them, and checks only their presence — so any
 * well-formed base64 is a faithful stand-in here; what a genuine tag or signature is, is `packages/crypto`'s to pin.
 */
object TestAdmission {
    /** 32 bytes of base64 — the length of a real HMAC-SHA256 tag. */
    const val TAG = "dGFnLXRhZy10YWctdGFnLXRhZy10YWctdGFnLXRhZy0="

    /** 64 bytes of base64 — the length of a real Ed25519 signature. */
    const val SIGNATURE = "c2lnLXNpZy1zaWctc2lnLXNpZy1zaWctc2lnLXNpZy1zaWctc2lnLXNpZy1zaWctc2lnLXNpZy1zaWctc2k="

    /** A fresh admission at [epoch]. */
    fun admission(epoch: Int = 0, id: String = UUID.randomUUID().toString()) = Admission(id, epoch, TAG)

    /** The three admission fields as JSON members (no braces), for a request body. */
    fun json(epoch: Int = 0, id: String = UUID.randomUUID().toString()) =
        """"admissionId":"$id","admissionEpoch":$epoch,"admissionTag":"$TAG""""

    /** A grant's signature as a JSON member. */
    const val SIGNATURE_JSON = """"grantSignature":"$SIGNATURE""""

    /** A new tag for every open membership admission of [scopeId], as the `admissions` JSON member a rotation must
     *  carry (ADR 0020 move 6) — read at call time, so it names exactly what the server will hold open. */
    fun openJson(scopeId: String): String =
        QuarkusTransaction.requiringNew().call { MembershipEntity.find("scopeId", scopeId).list().mapNotNull { it.admissionId } }
            .joinToString(",", prefix = "\"admissions\":[", postfix = "]") { """{"admissionId":"$it","admissionTag":"$TAG"}""" }

    /** The scope's current epoch — an admission must be stated at it. */
    fun epochOf(scopeId: String): Int = QuarkusTransaction.requiringNew().call { ScopeEntity.findById(scopeId)!!.currentEpoch }
}
