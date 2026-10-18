# ADR 0035 — Space terms of service: owner-set tenant terms + a member acceptance gate (server-side), anonymous acceptance client-side

- Status: Accepted; amended 2026-10-16 by [ADR 0086](0086-one-word-per-meaning.md): the terms' change counter is a
  **revision** (`terms_revision`, `accepted_terms_revision`, `termsRevision`, was `…version`); the pre-E2EE
  product's `Tenant.termsVersion` below keeps its own name. Decisions 6 (the fixed operator section) and 7 (the
  provider identification), added 2026-10-16 from the legal research behind ADR 0093 and made mode-dependent on
  2026-10-17 (ADR 0093 § 14), belong to the moderation work (inbox #462): decision 7 is **built** (set in admin-web
  and the CLI, warned about in the organisation mode, shown on the login and share pages,
  [uc.admin.operator-details](../features/admin/use-cases/uc.admin.operator-details.md)), and so are the values
  decision 6 renders; the operator section itself is **not built**.
- Date: 2026-07-10

## Context

veyq is **operator-blind** and **self-hostable**: each server is run by an **operator** (the Server Owner; the
central infra is one operator among many), and each server hosts **spaces** (tenants), each with its own **space
owner** (tenant owner). The two are different people wherever a server hosts more than one space. There is today
**no surface where a space states its policy** — acceptable use, prohibited/illegal content, review-and-removal,
contact, an Impressum — nor where its members and guests **consent** to it. The
fresh state already earmarks this: the admin feature "**Set tenant branding & legal terms — planned**"
([features/admin/_index](../features/admin/_index.md)), the sharing feature's deferred "**share notices / tenant
terms**" ([features/sharing/_index](../features/sharing/_index.md)), and the **T&S "decryptable-on-report"
corollary** in the [security model](../crosscutting/security-model.md) (a reported public share is decryptable).

The **pre-E2EE product** shipped exactly this: `Tenant.termsMarkdownByLocale` (DE+EN) + `Tenant.termsVersion`
(bumped on edit), assembled from a **clause template** (privacy, rights-to-content, prohibited/illegal incl.
CSAM, share-links, review-and-removal, external services, contact-and-deletion) and shown on shares with the
version. We **adopt that model** (the refine's ruling, like the buckets adoption).

**The terms belong to the space, not the server.** The space owner is who answers for what is shared in their
space and who its members and guests are, so the space owner writes the terms; the operator, who runs the server
for every space on it, does not author them, and the Server-Owner surface (admin-web) has no terms editor.

**The operator's duties are not the space's.** veyq assumes an instance is hosted by clubs and companies too, not
only for a family ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ Context). Then the Server Owner is the provider and the controller, and the law asks *them* to state the notice
path, the measures and how to object (DSA Art. 14, 16, 17), the policy on terrorist content (TCO Art. 7(1)), a
point of contact for users and authorities (DSA Art. 11–12, TCO Art. 15), the information on the guest-write
record (GDPR Art. 13 and 21(4)) and who they are (§ 5 DDG, § 18 MStV). A space owner can delete any clause of
their terms, so their terms cannot carry a duty that is not theirs (research, not legal advice, 2026-10-16;
ADR 0093 § Legal basis).

**No tension with operator-blindness.** The terms are the space's **public policy** (served to everyone,
including pre-auth on the login page), and acceptance is **operational metadata** (who accepted which
revision, and when — to the second) — never user content. It is in fact the one honest surface where a space
states its policy to the people it lets in, including what the server it runs on can and cannot see (the
honest-operator caveat) and that a reported public share is decryptable-on-report.

**The acceptance-storage split (the refine's key nuance).** An authenticated **member** has an account, so the
server records their accepted revision. An anonymous **public-share visitor** has **no account**, so the server
cannot attribute acceptance without breaking anonymity — their acceptance is **client-side**. So acceptance
storage splits by principal.

## Decision

Adopt a tenant-terms model, operator-blind, with a **two-tier acceptance gate**.

1. **Owner-set per-locale terms + a revision.** Each tenant carries **`terms_markdown_by_locale`** (DE+EN
   markdown) + **`terms_revision`** (int, bumped on any edit). The **space owner** (tenant owner, owner-gated —
   anyone else is refused) writes the terms, starting where they like from a **clause template** — a curated set
   of ready-made clauses (privacy, rights-to-content, prohibited/illegal content incl. **CSAM**, share-links,
   review-and-removal, external services, contact-and-deletion, and an **Impressum** clause) — and edits freely.
   The operator does not write them. The terms are **public operational content** (served, incl. pre-auth on the
   login page); never E2EE content.

2. **A member acceptance gate — server-side (v1).** An authenticated member must **accept the current
   `terms_revision`** before entering; on a revision bump the gate re-appears (**re-accept**). Acceptance is
   recorded server-side as **operational metadata** — the member's **`accepted_terms_revision`** (+ when). A
   **hard gate**: no entry until accepted.

3. **Anonymous share acceptance — client-side** (uc.sharing.share-accept). An accountless public-share visitor
   sees the tenant terms + revision and must accept (**hard gate + re-accept** on a revision change), but acceptance
   is stored **client-side** (localStorage, keyed **per tenant + revision** — accepting on one share of a tenant
   clears the gate on every share of that tenant, since they are the *space's* terms, not the link's) — the server
   records **nothing** about the accountless visitor, preserving anonymity. This reuses the same tenant terms (the
   T&S decryptable-on-report corollary).

   **Delivery — the terms reach the accountless client from the *share*, not the member endpoint or the Host.** The
   member read (`getTenantTerms`) is `bearerAuth`, membership-gated, keyed by `tenantId` — none of which an
   accountless visitor has. A public share is also **Host-independent** (`shareId` is a global key resolvable on
   any Host), so the tenant cannot be inferred from the request Host. The terms therefore come from a dedicated
   **guest** endpoint keyed on the already non-enumerable `shareId`: **`GET /api/shares/{shareId}/terms`** (no
   auth) → `{ tenantId, termsRevision, terms }`, reusing the share's `activeShare()` gate so unknown/disabled/
   expired is the same non-enumerable 404 as `resolveShare`. **There is no `tenantId` existence oracle** — the
   caller passes only the `shareId` they already hold; the opaque `tenantId` is only *returned* (as the localStorage
   key). This is **not** the pre-auth *login-page* terms surface (§Consequences) — that path is Host-served and
   stays a separate, still-deferred slice.

   **Ordering + fail-open.** On the share page the gate sits **after** the password shell (ADR 0037) and **before**
   the display-name pre-screen (ADR 0030/0031; since inbox #401 S5 a contributor names itself at its first
   contribution, behind the gate all the same): **password → terms → name → content**, with the curator note's own gate between
   terms and name where a note asks for it (ADR 0036 point 2). It **fails open** — a
   transient terms-fetch error leaves the visitor viewing (a gate is operational, not enforcement; locking someone
   out on a network blip is worse than none), mirroring the member path's fail-open terms refresh.

4. **Operator-blind boundary.** The terms text is public; a member's acceptances are kept as a **history** — one
   row per (member, tenant, accepted revision), each with its `accepted_at` — operational metadata (like
   membership/role). The highest accepted revision is what the gate reads (an accept names the current revision or
   is refused, and the revision only grows, so "highest = current" is "accepted the current revision"); the rows and
   their times are **audit evidence** of *when* a member accepted *which* revision, should a violation or dispute
   arise — including one about a revision since superseded. Each time is the server's own clock at the acceptance,
   never client-supplied, written once per revision (re-accepting a recorded revision keeps its first time; accepting
   a newer revision adds a row and leaves the older ones untouched). No API serves the history, so a space owner
   cannot read it either: the operator and support read it from the database. The rows go with their tenant
   (`ON DELETE CASCADE`); the user reference has no cascade, like the tenant profile's, so an account erasure, when it exists, must name the table. (Inbox #318
   dropped the time as unread on 2026-10-09; the owner restored it the same day as that evidence; #402 on
   2026-10-10 turned the single latest row into the history — a coordinator decision on the owner's behalf, to be
   reviewed.) The anonymous acceptance is client-only. **No
   user content, no new blindness cost** — recorded in the [security model](../crosscutting/security-model.md).

5. **Two acceptance tiers, one login surface still deferred.** The space owner sets/edits the terms (the
   space's settings in the web client, every saved change a new revision). The **member** accept/re-accept gate is server-side (v1, cr-0114); the
   **accountless-share** accept/re-accept gate is client-side (§3, uc.sharing.share-accept). What stays deferred is
   only the **pre-auth login-page** terms surface (a Host-served public read — see §Consequences), a distinct
   mechanism from the share-keyed guest read above.

6. **A fixed operator section, instance-wide, beside every space's terms.** veyq ships its text (DE+EN); nobody
   edits it, and admin-web and the CLI set only the values it renders: the instance contact (ADR 0093 § 11), the
   provider identification (7), the competent data-protection supervisory authority, the guest-write record's N
   and, where it applies, the backup period B (ADR 0093 § 10). It states:
   - **the notice path**: who can report and how, what a report must hold, that a contact is optional and a report
     of child sexual abuse material needs no name, that a human reviews and nothing is blocked by a report;
   - **what is kept**: the hold and its ends (ADR 0093 § 4), and that backups may hold it longer;
   - **the measures**, in steps and reversible, the **statement of reasons**, its deferral only on an authority's
     request, and the **objection** (ADR 0093 §§ 6–7);
   - **authorities**: Art. 18 reports, orders complied with, terrorist content prohibited and blocked once known,
     and the instance contact as the point of contact for users and authorities and the TCO contact point, with
     its languages;
   - **the guest-write record's GDPR Art. 13 information**, with the **right to object (Art. 21) as a notice of its
     own**, separate from the rest; the first-layer sentence on every guest write page links here (ADR 0093 § 10).

   It is shown wherever a space's terms are — the member gate, the share gate, and the login page once that surface
   exists — and served with them by the same endpoints, after the space's own text. Its revision moves only with
   its text, that is with a veyq release; a bump reopens the gate like a space's revision, and a value changed in
   admin-web renders at once without reopening it. A space with no terms (revision 0) shows the operator section
   without a gate. The clauses' DE and EN wording is written in the CR that builds this, from the drafts of the
   2026-10-16 research; the template's clause 9 then points to this section instead of regulating review itself.

   **The section has two variants, chosen by the instance's operating mode** (ADR 0093 § 14, owner 2026-10-17). The
   **full** section above is the *club, organisation or company* mode's. The **reduced** section is the *private*
   mode's, for an operator who assessed that the GDPR, the DSA and the TCO Regulation do not reach their instance; it
   keeps what tells a member or a guest how to report and what happens then, and drops what only those laws ask for
   (ADR 0093 § Legal basis, items 1, 2 and 6):
   - **stays:** the notice path, whole; what is kept (the hold and its ends, backups); the measures in steps and
     reversible; that the affected are told what was blocked and why, and may object in the app once;
   - **drops:** the authorities bullet — Art. 18 reports, orders, the terrorist-content policy (TCO Art. 7(1)) and the
     contact point for users and authorities with its languages (DSA Art. 11–12, TCO Art. 15); the statement's
     deferral on an authority's request (TCO Art. 11(3)); and the statement's further content, reduced as ADR 0093
     § 7 says;
   - **follows its value, not the mode:** the guest-write record's information is in the section exactly while
     `core.public-shares.guest-write-record` is on, in either variant, since its first-layer sentence and Art. 13
     come with the recording; a contact or a provider identification the operator set is shown, and one left empty is
     simply absent — the full section shows that gap as "not stated by this operator".

   Both variants carry one revision, moved with the text of either, and the gate compares the variant with it:
   switching the mode **reopens the gate**, since what members accepted changed. Switching the record does not: its
   information reaches a guest at the write itself (ADR 0093 § 10), like a changed value.

7. **A provider identification, set in admin-web.** Name, an address for service, an email address, and for a
   legal person who represents it (a club's board). Required by § 5 DDG of a commercial operator and by § 18 MStV
   of every operator not serving exclusively personal or family purposes. **Required in the *club, organisation or
   company* mode**: admin-web warns while it is empty, and never refuses (ADR 0093 § 14). **Optional in the
   *private* mode**, with no warning. Where it is set it appears, in either mode, in the operator section and on the
   login and share pages. It is public operational data, like the terms.

## Consequences

- **New operator-visible surface, all public/operational:** the tenant `terms_markdown_by_locale` + `terms_revision`
  (public), and per-member `accepted_terms_revision` (operational metadata). No content, no key.
- **The terms ride pre-auth** — the login page shows/links them — so they live on the tenant surface reachable
  **before** login (alongside the branding/`/api/setup`-style pre-auth data) and on an **authed tenant endpoint**
  for editing + a member **accept** endpoint.
- **The clause template gives a legally-meaningful starting ToS** (esp. the CSAM/illegal-content + review-removal
  clauses) matching the pre-E2EE product. No server ships default terms: a new tenant has none (revision 0) until
  its owner writes them, the template inserted only on request. DE strings follow
  [cc.inclusive-language](../crosscutting/cc.inclusive-language.md).
- **Anonymous client-side acceptance is honest about its limits** (no server non-repudiation for an accountless
  visitor) — a stated property of the deferred share slice.
- **Ties into the T&S "decryptable-on-report" corollary** — that a reported **public** share is
  decryptable-on-report is a property of every public share on every server (the report carries the link's
  secret, [security model](../crosscutting/security-model.md)), not something a space chooses. The terms are
  where the space tells its members and guests so; how a report is handled is the operator section's (6), which
  every space carries. The report intake and an operator's measures on a single share, item or guest are built
  (ADR 0093 §§ 1-6, uc.moderation.take-a-measure), and so are the statement of reasons and the objection (§ 7,
  uc.moderation.object-to-a-measure); the operator section is not (security model § Sharing).
- **The operator section and the provider identification are new public surface** (6, 7): instance settings in
  admin-web and the CLI ([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)),
  rendered into the terms endpoints. A Server Owner in the *club, organisation or company* mode who leaves the
  identification empty sees a warning, not a refusal; one in the *private* mode sees nothing and gets the reduced
  section (decision 6). None of it is built.

## Alternatives considered

- **Server-side acceptance for anonymous share visitors too.** Rejected — attributing acceptance to an accountless
  visitor needs a cookie/fingerprint that either **breaks anonymity** or is unreliable; client-side localStorage
  is the honest choice for an accountless visitor.
- **Shown-only (no acceptance gate).** Rejected — no consent record; a **hard gate + re-accept on change** is the
  legally-meaningful choice, matching the pre-E2EE product.
- **Free-text markdown (no clause template).** Rejected — the template gives space owners a sound starting ToS
  (the CSAM/illegal + review-removal clauses matter) and matches the pre-E2EE UX; the owner can still edit freely.
- **One global ToS (central infra only).** Rejected — veyq is **self-hostable**, and the central infra is one
  operator among many.
- **One ToS per server, written by its operator.** Rejected — a server hosts many spaces, each answering for its
  own content and people; the space owner is who can state that space's policy, so every space writes its own.
  The operator section (6) is not this: it states only the operator's own duties, in fixed text the operator
  fills with values but does not write.
- **The operator's duties as clauses of the space's template.** Rejected — a space owner can delete any clause,
  and on a server with many spaces the space owner is not the one the duty falls on.
- **An operator-editable section** in admin-web. Rejected — the duties' content is fixed by law and the same on
  every instance; free text invites dropping the parts a supervisor checks, and admin-web stays without a terms
  editor.
