package com.veyq.app.shared.tenant

import com.veyq.app.features.admin.tenant.TenantEntity
import jakarta.enterprise.context.ApplicationScoped

/**
 * The one canonical Host→tenant resolution (ADR 0066). veyq addresses a tenant by the domain a request arrives
 * on, so *how* that domain is read off the request is a security-relevant decision — a gate that normalized the
 * Host even slightly differently from the handlers it guards would protect a different tenant than it thinks.
 * Hence a single home for it, used by both [RequestTenant] (which hands every resource its tenant) and
 * `TenantSuspensionFilter` (which refuses a suspended one), rather than a copy per call site.
 * `scripts/validate_lookup_single_home.py` keeps it the only one: no other file reads the Host header or looks a
 * tenant up by domain.
 *
 * Header precedence is the reverse-proxy convention `X-Forwarded-Host` before `Host`, and it inherits that
 * header's trust model exactly: `X-Forwarded-Host` is only meaningful behind a proxy that sets it and strips a
 * client-supplied one. Spoofing it cannot *widen* access here — it can name a tenant the caller is not a member
 * of (every route stays membership-gated behind this) or a suspended one (which only refuses them harder).
 */
@ApplicationScoped
class TenantHostResolver {

    /** The active tenant's host from a header lookup: `X-Forwarded-Host`, else `Host`; first value, port stripped. */
    fun hostOf(header: (String) -> String?): String? =
        (header(FORWARDED_HOST) ?: header(HOST))
            ?.substringBefore(',')?.substringBefore(':')?.trim()?.lowercase()?.ifEmpty { null }

    /** The tenant claiming [host], or null — an unknown or absent host claims no tenant. */
    fun tenantFor(host: String?): TenantEntity? =
        host?.let { TenantEntity.find("domain", it).firstResult() }

    private companion object {
        const val FORWARDED_HOST = "X-Forwarded-Host"
        const val HOST = "Host"
    }
}
