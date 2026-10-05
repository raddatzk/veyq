package com.veyq.core.admin.tenant

import com.fasterxml.jackson.databind.ObjectMapper
import com.veyq.core.http.FailureStatus
import com.veyq.core.http.Failures
import com.veyq.core.http.HttpFailure
import com.veyq.core.http.httpFailure
import com.veyq.core.membership.AccessMode.READ
import com.veyq.core.membership.AccessMode.WRITE
import com.veyq.core.membership.ScopeAccess
import com.veyq.core.membership.refusal
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.TreeMap

/** The decoded per-locale terms markdown (cr-0114). Either locale may be null; both null = no terms. */
data class TermsContent(val de: String?, val en: String?)

/** A tenant's current **public** terms for an accountless reader (uc.sharing.share-accept): the version + the
 *  decoded per-locale markdown. No caller, no acceptedVersion — an accountless visitor has no account. */
data class GuestTerms(val version: Int, val terms: TermsContent?)

/** Result of reading a tenant's terms + the caller's accepted version (cr-0114). */
sealed interface TermsReadOutcome {
    data class Ok(val version: Int, val acceptedVersion: Int, val terms: TermsContent?) : TermsReadOutcome
    data object Suspended : TermsReadOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : TermsReadOutcome, HttpFailure by Failures.tenantNotFound // no such tenant, or the caller is not a member (indistinguishable)
}

/** Result of an owner setting/replacing a tenant's terms (cr-0114). */
sealed interface TermsUpdateOutcome {
    data class Ok(val version: Int, val acceptedVersion: Int, val terms: TermsContent?) : TermsUpdateOutcome
    data object Suspended : TermsUpdateOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : TermsUpdateOutcome, HttpFailure by Failures.tenantNotFound // no such tenant, or the caller is not a member (indistinguishable)
    data object NotOwner : TermsUpdateOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
}

/** Result of a member accepting the tenant's current terms (cr-0114). */
sealed interface TermsAcceptOutcome {
    data class Ok(val version: Int, val acceptedVersion: Int, val terms: TermsContent?) : TermsAcceptOutcome
    data object Suspended : TermsAcceptOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : TermsAcceptOutcome, HttpFailure by Failures.tenantNotFound
    data object VersionChanged : TermsAcceptOutcome, HttpFailure by httpFailure("terms_version_changed", FailureStatus.CONFLICT) // the operator bumped the terms since they were shown
}

/**
 * Server terms of service (uc.admin.tenant-terms + uc.account.accept-terms, ADR 0035). The tenant **owner**
 * sets per-locale (DE+EN) markdown terms; the server normalizes them and bumps `terms_version` only on a real
 * change (an idempotent re-save doesn't). A **member** reads the current terms + their accepted version and
 * accepts the current version to clear the gate; a later bump re-stales them. Operator-blind: the terms are
 * the operator's public policy and acceptance is coarse operational metadata — no content key is involved, so
 * this handler does no crypto. The owner/membership gates are code checks here (ADR 0008), like the member
 * invite handler; the Resource is a thin adapter.
 */
@ApplicationScoped
class TenantTermsHandler(
    private val store: TenantStore,
    private val mapper: ObjectMapper,
    private val access: ScopeAccess,
) {

    /** Read the tenant's current terms + the caller's accepted version — gated to **members** (else NotFound). */
    fun read(callerId: String, tenantId: String): TermsReadOutcome {
        access.check(callerId, tenantId, READ)
            .refusal(TermsReadOutcome.NotFound, TermsReadOutcome.Suspended)?.let { return it }
        val row = store.termsFor(tenantId, callerId) ?: return TermsReadOutcome.NotFound
        return TermsReadOutcome.Ok(row.version, row.acceptedVersion, decode(row.markdownByLocale))
    }

    /**
     * The tenant's current **public** terms for an **accountless** reader (uc.sharing.share-accept) — version +
     * decoded per-locale markdown, with **no** membership check, **no** userId, and **no** acceptance recorded.
     * The accountless half of the two-tier gate is client-side (ADR 0035 §3): the server keeps no record of an
     * accountless visitor, so this read is deliberately caller-free (contrast [read], member-gated). Null only
     * when the tenant id itself does not resolve; a tenant with no published terms reads back version 0 / null.
     */
    fun guestTerms(tenantId: String): GuestTerms? {
        val row = store.guestTermsFor(tenantId) ?: return null
        return GuestTerms(row.version, decode(row.markdownByLocale))
    }

    /**
     * The **owner** sets/replaces the terms. Membership-gated first, like the sibling [read]/[accept] (a
     * non-member gets the same 404 as a bogus tenant id, ADR 0042), then owner-gated.
     * Normalizes both locales; an **all-blank** update is a no-op (v1 has
     * no "remove terms" — you can't publish an empty gated version). Otherwise stores the canonical serialized map
     * and bumps `terms_version` only if it actually changed.
     */
    @Transactional
    fun update(callerId: String, tenantId: String, de: String?, en: String?): TermsUpdateOutcome {
        access.check(callerId, tenantId, WRITE)
            .refusal(TermsUpdateOutcome.NotFound, TermsUpdateOutcome.Suspended)?.let { return it }
        if (store.findTenant(tenantId) == null) return TermsUpdateOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return TermsUpdateOutcome.NotOwner
        val normalized = normalize(de, en)
        if (normalized.isNotEmpty()) store.updateTerms(tenantId, mapper.writeValueAsString(normalized))
        val row = store.termsFor(tenantId, callerId)!!
        return TermsUpdateOutcome.Ok(row.version, row.acceptedVersion, decode(row.markdownByLocale))
    }

    /**
     * The **member** accepts the version they were shown — recorded only if it still equals the current version,
     * else VersionChanged (the operator bumped the terms meanwhile → the client re-reads + re-presents).
     */
    @Transactional
    fun accept(callerId: String, tenantId: String, version: Int): TermsAcceptOutcome {
        access.check(callerId, tenantId, WRITE)
            .refusal(TermsAcceptOutcome.NotFound, TermsAcceptOutcome.Suspended)?.let { return it }
        val row = store.termsFor(tenantId, callerId) ?: return TermsAcceptOutcome.NotFound
        if (version != row.version) return TermsAcceptOutcome.VersionChanged
        store.recordAcceptance(callerId, tenantId, version)
        val updated = store.termsFor(tenantId, callerId)!!
        return TermsAcceptOutcome.Ok(updated.version, updated.acceptedVersion, decode(updated.markdownByLocale))
    }

    /** A canonical (sorted-key, blank-dropped, newline-normalized) locale→markdown map for stable comparison. */
    private fun normalize(de: String?, en: String?): TreeMap<String, String> {
        val out = TreeMap<String, String>()
        normalizeOne(de)?.let { out["de"] = it }
        normalizeOne(en)?.let { out["en"] = it }
        return out
    }

    private fun normalizeOne(raw: String?): String? {
        val v = raw?.replace("\r\n", "\n")?.replace("\r", "\n")?.trim()
        return if (v.isNullOrEmpty()) null else v
    }

    private fun decode(serialized: String?): TermsContent? {
        if (serialized.isNullOrBlank()) return null
        val map: Map<String, String> = mapper.readValue(serialized, MAP_TYPE)
        return TermsContent(de = map["de"], en = map["en"])
    }

    private companion object {
        private val MAP_TYPE = object : com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}
    }
}
