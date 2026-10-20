package me.raddatz.veyq.core.admin.tenant

import com.fasterxml.jackson.databind.ObjectMapper
import me.raddatz.veyq.core.http.FailureStatus
import me.raddatz.veyq.core.http.Failures
import me.raddatz.veyq.core.http.HttpFailure
import me.raddatz.veyq.core.http.httpFailure
import me.raddatz.veyq.core.membership.AccessMode.READ
import me.raddatz.veyq.core.membership.AccessMode.WRITE
import me.raddatz.veyq.core.membership.ScopeAccess
import me.raddatz.veyq.core.membership.refusal
import me.raddatz.veyq.core.terms.OperatorSections
import me.raddatz.veyq.core.terms.RenderedSection
import me.raddatz.veyq.core.terms.SectionVariant
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import java.util.TreeMap

/** The decoded per-locale terms markdown (cr-0114). Either locale may be null; both null = no terms. */
data class TermsContent(val de: String?, val en: String?)

/** A tenant's current **public** terms for an accountless reader (uc.sharing.share-accept): the revision, the
 *  decoded per-locale markdown and the operator section after it. No caller, no acceptance — an accountless visitor
 *  has no account. */
data class GuestTerms(val revision: Int, val terms: TermsContent?, val section: RenderedSection)

/** Result of reading a tenant's terms + whether the caller accepted the current triple (cr-0114, ADR 0035 decision 6). */
sealed interface TermsReadOutcome {
    data class Ok(val revision: Int, val accepted: Boolean, val terms: TermsContent?, val section: RenderedSection) : TermsReadOutcome
    data object Suspended : TermsReadOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : TermsReadOutcome, HttpFailure by Failures.tenantNotFound // no such tenant, or the caller is not a member (indistinguishable)
}

/** Result of an owner setting/replacing a tenant's terms (cr-0114). */
sealed interface TermsUpdateOutcome {
    data class Ok(val revision: Int, val accepted: Boolean, val terms: TermsContent?, val section: RenderedSection) : TermsUpdateOutcome
    data object Suspended : TermsUpdateOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : TermsUpdateOutcome, HttpFailure by Failures.tenantNotFound // no such tenant, or the caller is not a member (indistinguishable)
    data object NotOwner : TermsUpdateOutcome, HttpFailure by httpFailure("not_tenant_owner", FailureStatus.FORBIDDEN)
}

/** Result of a member accepting the tenant's current terms (cr-0114). */
sealed interface TermsAcceptOutcome {
    data class Ok(val revision: Int, val accepted: Boolean, val terms: TermsContent?, val section: RenderedSection) : TermsAcceptOutcome
    data object Suspended : TermsAcceptOutcome, HttpFailure by Failures.tenantSuspended
    data object NotFound : TermsAcceptOutcome, HttpFailure by Failures.tenantNotFound
    // The triple moved since it was shown: the space owner bumped the terms, a release changed the operator section, or
    // the Server Owner switched the operating mode.
    data object RevisionChanged : TermsAcceptOutcome, HttpFailure by httpFailure("terms_revision_changed", FailureStatus.CONFLICT)
}

/**
 * Space terms of service (uc.admin.tenant-terms + uc.account.accept-terms, ADR 0035). The tenant **owner**
 * sets per-locale (DE+EN) markdown terms; the server normalizes them and bumps `terms_revision` only on a real
 * change (an idempotent re-save doesn't). A **member** reads the current terms + their accepted revision and
 * accepts the current revision to clear the gate; a later bump re-stales them. Operator-blind: the terms are
 * the space owner's public policy and acceptance is coarse operational metadata — no content key is involved, so
 * this handler does no crypto. The owner/membership gates are code checks here (ADR 0008), like the member
 * invite handler; the Resource is a thin adapter.
 *
 * Every read serves the fixed **operator section** after the space's text ([OperatorSections], ADR 0035 decision 6),
 * and the gate compares a **triple**: the space's revision, the section's revision and its variant (decision 2 of
 * cr-operator-section-of-the-terms). Any of them moving reopens the gate; a changed operator value or the guest-write
 * record switched does not, since neither is in the triple. The member's history keeps one row per accepted triple.
 */
@ApplicationScoped
class TenantTermsHandler(
    private val store: TenantStore,
    private val mapper: ObjectMapper,
    private val access: ScopeAccess,
    private val sections: OperatorSections,
) {

    /** Read the tenant's current terms + the caller's accepted revision — gated to **members** (else NotFound). */
    fun read(callerId: String, tenantId: String): TermsReadOutcome {
        access.check(callerId, tenantId, READ)
            .refusal(TermsReadOutcome.NotFound, TermsReadOutcome.Suspended)?.let { return it }
        val row = store.termsFor(tenantId) ?: return TermsReadOutcome.NotFound
        val section = sections.render(tenantId)
        return TermsReadOutcome.Ok(row.revision, accepted(callerId, tenantId, row.revision, section), decode(row.markdownByLocale), section)
    }

    /**
     * The tenant's current **public** terms for an **accountless** reader (uc.sharing.share-accept) — revision +
     * decoded per-locale markdown, with **no** membership check, **no** userId, and **no** acceptance recorded.
     * The accountless half of the two-tier gate is client-side (ADR 0035 §3): the server keeps no record of an
     * accountless visitor, so this read is deliberately caller-free (contrast [read], member-gated). Null only
     * when the tenant id itself does not resolve; a tenant with no published terms reads back revision 0 / null.
     */
    fun guestTerms(tenantId: String): GuestTerms? {
        val row = store.guestTermsFor(tenantId) ?: return null
        return GuestTerms(row.revision, decode(row.markdownByLocale), sections.render(tenantId))
    }

    /**
     * The **owner** sets/replaces the terms. Membership-gated first, like the sibling [read]/[accept] (a
     * non-member gets the same 404 as a bogus tenant id, ADR 0042), then owner-gated.
     * Normalizes both locales; an **all-blank** update is a no-op (v1 has
     * no "remove terms" — you can't publish an empty gated revision). Otherwise stores the canonical serialized map
     * and bumps `terms_revision` only if it actually changed.
     */
    @Transactional
    fun update(callerId: String, tenantId: String, de: String?, en: String?): TermsUpdateOutcome {
        access.check(callerId, tenantId, WRITE)
            .refusal(TermsUpdateOutcome.NotFound, TermsUpdateOutcome.Suspended)?.let { return it }
        if (store.findTenant(tenantId) == null) return TermsUpdateOutcome.NotFound
        if (!store.isOwner(callerId, tenantId)) return TermsUpdateOutcome.NotOwner
        val normalized = normalize(de, en)
        if (normalized.isNotEmpty()) store.updateTerms(tenantId, mapper.writeValueAsString(normalized))
        val row = store.termsFor(tenantId)!!
        val section = sections.render(tenantId)
        return TermsUpdateOutcome.Ok(row.revision, accepted(callerId, tenantId, row.revision, section), decode(row.markdownByLocale), section)
    }

    /**
     * The **member** accepts the triple they were shown — recorded only if every part still equals the current one,
     * else RevisionChanged (the space owner bumped the terms, a release changed the operator section, or the operating
     * mode changed meanwhile → the client re-reads + re-presents).
     */
    @Transactional
    fun accept(callerId: String, tenantId: String, revision: Int, operatorRevision: Int, variant: SectionVariant): TermsAcceptOutcome {
        access.check(callerId, tenantId, WRITE)
            .refusal(TermsAcceptOutcome.NotFound, TermsAcceptOutcome.Suspended)?.let { return it }
        val row = store.termsFor(tenantId) ?: return TermsAcceptOutcome.NotFound
        val section = sections.render(tenantId)
        if (revision != row.revision || operatorRevision != section.revision || variant != section.variant) {
            return TermsAcceptOutcome.RevisionChanged
        }
        store.recordAcceptance(callerId, tenantId, revision, operatorRevision, variant.name)
        return TermsAcceptOutcome.Ok(row.revision, accepted = true, decode(row.markdownByLocale), section)
    }

    /** Whether [callerId]'s history holds the current triple — what the member gate reads. */
    private fun accepted(callerId: String, tenantId: String, revision: Int, section: RenderedSection): Boolean =
        store.hasAccepted(callerId, tenantId, revision, section.revision, section.variant.name)

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
