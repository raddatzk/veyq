# ADR 0035 — Server terms of service: operator-set tenant terms + a member acceptance gate (server-side), anonymous acceptance client-side

- Status: Accepted
- Date: 2026-07-10

## Context

veyq is **operator-blind** and **self-hostable**: each server is run by an **operator** (the central infra is
one operator among many). There is today **no surface where an operator states their policy** — acceptable use,
prohibited/illegal content, review-and-removal, contact, an Impressum — nor where users **consent** to it. The
fresh state already earmarks this: the admin feature "**Set tenant branding & legal terms — planned**"
([features/admin/_index](../features/admin/_index.md)), the sharing feature's deferred "**share notices / tenant
terms**" ([features/sharing/_index](../features/sharing/_index.md)), and the **T&S "decryptable-on-report"
corollary** in the [security model](../crosscutting/security-model.md) (a reported public share is decryptable).

The **pre-E2EE product** shipped exactly this: `Tenant.termsMarkdownByLocale` (DE+EN) + `Tenant.termsVersion`
(bumped on edit), assembled from a **clause template** (privacy, rights-to-content, prohibited/illegal incl.
CSAM, share-links, review-and-removal, external services, contact-and-deletion) and shown on shares with the
version. We **adopt that model** (the refine's ruling, like the buckets adoption).

**No tension with operator-blindness.** The terms are the operator's **public policy** (served to everyone,
including pre-auth on the login page), and acceptance is **operational metadata** (who accepted which
version, and when — to the second) — never user content. It is in fact the one honest surface where the
content-blind operator states policy + the honest-operator caveat + the public-share decryptable-on-report.

**The acceptance-storage split (the refine's key nuance).** An authenticated **member** has an account, so the
server records their accepted version. An anonymous **public-share visitor** has **no account**, so the server
cannot attribute acceptance without breaking anonymity — their acceptance is **client-side**. So acceptance
storage splits by principal.

## Decision

Adopt a tenant-terms model, operator-blind, with a **two-tier acceptance gate**.

1. **Operator-set per-locale terms + a version.** Each tenant carries **`terms_markdown_by_locale`** (DE+EN
   markdown) + **`terms_version`** (int, bumped on any edit). The **operator** (tenant owner) assembles/edits the
   terms from a **clause template** — a curated set of ready-made clauses (privacy, rights-to-content,
   prohibited/illegal content incl. **CSAM**, share-links, review-and-removal, external services,
   contact-and-deletion, and an **Impressum** clause) — into their own ToS, and can edit freely. The terms are
   **public operational content** (served, incl. pre-auth on the login page); never E2EE content.

2. **A member acceptance gate — server-side (v1).** An authenticated member must **accept the current
   `terms_version`** before entering; on a version bump the gate re-appears (**re-accept**). Acceptance is
   recorded server-side as **operational metadata** — the member's **`accepted_terms_version`** (+ when). A
   **hard gate**: no entry until accepted.

3. **Anonymous share acceptance — client-side** (uc.sharing.share-accept). An accountless public-share visitor
   sees the tenant terms + version and must accept (**hard gate + re-accept** on a version change), but acceptance
   is stored **client-side** (localStorage, keyed **per tenant + version** — accepting on one share of a tenant
   clears the gate on every share of that tenant, since it is the *operator's* terms, not the link's) — the server
   records **nothing** about the accountless visitor, preserving anonymity. This reuses the same tenant terms (the
   T&S decryptable-on-report corollary).

   **Delivery — the terms reach the accountless client from the *share*, not the member endpoint or the Host.** The
   member read (`getTenantTerms`) is `bearerAuth`, membership-gated, keyed by `tenantId` — none of which an
   accountless visitor has. A public share is also **Host-independent** (`shareId` is a global key resolvable on
   any Host), so the tenant cannot be inferred from the request Host. The terms therefore come from a dedicated
   **guest** endpoint keyed on the already non-enumerable `shareId`: **`GET /api/shares/{shareId}/terms`** (no
   auth) → `{ tenantId, termsVersion, terms }`, reusing the share's `activeShare()` gate so unknown/disabled/
   expired is the same non-enumerable 404 as `resolveShare`. **There is no `tenantId` existence oracle** — the
   caller passes only the `shareId` they already hold; the opaque `tenantId` is only *returned* (as the localStorage
   key). This is **not** the pre-auth *login-page* terms surface (§Consequences) — that path is Host-served and
   stays a separate, still-deferred slice.

   **Ordering + fail-open.** On the share page the gate sits **after** the password shell (ADR 0037) and **before**
   the display-name pre-screen (ADR 0030/0031): **password → terms → name → content**. It **fails open** — a
   transient terms-fetch error leaves the visitor viewing (a gate is operational, not enforcement; locking someone
   out on a network blip is worse than none), mirroring the member path's fail-open terms refresh.

4. **Operator-blind boundary.** The terms text is public; a member's `accepted_terms_version` is operational
   metadata (like membership/role) — the `accepted_at` it once carried was never read and is no longer stored
   (inbox #318: the version alone is what the gate reads and what says which text was accepted); the anonymous
   acceptance is client-only. **No
   user content, no new blindness cost** — recorded in the [security model](../crosscutting/security-model.md).

5. **Two acceptance tiers, one login surface still deferred.** The operator sets/edits terms (admin UI, versioned).
   The **member** accept/re-accept gate is server-side (v1, cr-0114); the **accountless-share** accept/re-accept
   gate is client-side (§3, uc.sharing.share-accept). What stays deferred is only the **pre-auth login-page** terms
   surface (a Host-served public read — see §Consequences), a distinct mechanism from the share-keyed guest read
   above.

## Consequences

- **New operator-visible surface, all public/operational:** the tenant `terms_markdown_by_locale` + `terms_version`
  (public), and per-member `accepted_terms_version` (operational metadata). No content, no key.
- **The terms ride pre-auth** — the login page shows/links them — so they live on the tenant surface reachable
  **before** login (alongside the branding/`/api/setup`-style pre-auth data) and on an **authed tenant endpoint**
  for editing + a member **accept** endpoint.
- **The clause template gives a legally-meaningful starting ToS** (esp. the CSAM/illegal-content + review-removal
  clauses) matching the pre-E2EE product; self-hosted operators own their terms, the central infra ships a
  default. DE strings follow [cc.inclusive-language](../crosscutting/cc.inclusive-language.md).
- **Anonymous client-side acceptance is honest about its limits** (no server non-repudiation for an accountless
  visitor) — a stated property of the deferred share slice.
- **Ties into the T&S "decryptable-on-report" corollary** — the terms are where the operator states that a
  reported **public** share is decryptable-on-report, and the illegal-content/review-removal policy that governs it.

## Alternatives considered

- **Server-side acceptance for anonymous share visitors too.** Rejected — attributing acceptance to an accountless
  visitor needs a cookie/fingerprint that either **breaks anonymity** or is unreliable; client-side localStorage
  is the honest choice for an accountless visitor.
- **Shown-only (no acceptance gate).** Rejected — no consent record; a **hard gate + re-accept on change** is the
  legally-meaningful choice, matching the pre-E2EE product.
- **Free-text markdown (no clause template).** Rejected — the template gives operators a sound starting ToS (the
  CSAM/illegal + review-removal clauses matter) and matches the pre-E2EE UX; the operator can still edit freely.
- **One global ToS (central infra only).** Rejected — veyq is **self-hostable**; each operator sets their own; the
  central infra is one tenant among many.
