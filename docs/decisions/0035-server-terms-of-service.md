# ADR 0035 — Terms: a space's own terms behind a gate, the operator's fixed section and privacy notice beside them

- Status: Accepted
- Built: partly (not built: decision 5's pre-auth login-page surface of a space's terms)
- Date: 2026-07-10
- Model: [Moderation](../features/moderation/_index.md)

## Context

veyq is **operator-blind** and **self-hostable**: each server is run by an **operator** (the Server Owner; the
central infra is one operator among many), and each server hosts **spaces** (tenants), each with its own **space
owner** (tenant owner). The two are different people wherever a server hosts more than one space. A space needs a
**surface where it states its policy** — acceptable use, prohibited/illegal content, review-and-removal, contact, an
Impressum — and where its members and guests **consent** to it, and the **T&S "decryptable-on-report" corollary** in
the [security model](../crosscutting/security-model.md) (a reported public share is decryptable) needs a place where
the people a space lets in are told.

The **pre-E2EE product** shipped exactly this: `Tenant.termsMarkdownByLocale` (DE+EN) + `Tenant.termsVersion`
(bumped on edit), assembled from a **clause template** (privacy, rights-to-content, prohibited/illegal incl.
CSAM, share-links, review-and-removal, external services, contact-and-deletion) and shown on shares with the
version. We **adopt that model**.

**The terms belong to the space, not the server.** The space owner is who answers for what is shared in their
space and who its members and guests are, so the space owner writes the terms; the operator, who runs the server
for every space on it, does not author them, and the Server-Owner surface (admin-web) has no terms editor.

**The operator's duties are not the space's.** veyq assumes an instance is hosted by clubs and companies too, not
only for a family ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md)
§ Context). Then the Server Owner is the provider and the controller, and the law asks *them* to state the notice
path, the measures and how to object, the policy on terrorist content, a point of contact for users and authorities,
the information on the guest-write record and who they are ([legal register](../crosscutting/legal-register.md) L2).
A space owner can delete any clause of their terms, so their terms cannot carry a duty that is not theirs.

**No tension with operator-blindness.** The terms are the space's **public policy** (public operational content,
never E2EE), and acceptance is **operational metadata** (who accepted which
revision, and when — to the millisecond) — never user content. It is in fact the one honest surface where a space
states its policy to the people it lets in, including what the server it runs on can and cannot see (the
honest-operator caveat) and that a reported public share is decryptable-on-report.

**The acceptance-storage split.** An authenticated **member** has an account, so the
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
   The operator does not write them. The terms are **public operational content**, never E2EE content; their
   pre-auth read on the login page is decision 5's, not built.

2. **A member acceptance gate — server-side.** An authenticated member must **accept the current
   `terms_revision`** before entering; on a revision bump the gate re-appears (**re-accept**). Acceptance is
   recorded server-side as **operational metadata** — what the member accepted, and when (decision 4). A
   **hard gate**: no entry until accepted.

3. **Anonymous share acceptance — client-side** (uc.sharing.share-accept). An accountless public-share visitor
   sees the tenant terms + revision and must accept (**hard gate + re-accept** on a revision change), but acceptance
   is stored **client-side** (localStorage, keyed **per tenant**, holding the pairs accepted — the revision with the
   operator section's revision, decision 6 — accepting on one share of a tenant clears the gate on every
   share of that tenant, since they are the *space's* terms, not the link's) — the server
   records **nothing** about the accountless visitor, preserving anonymity — and its logs name no visitor either: no
   address, no User-Agent, no request URI ([ADR 0092](0092-logs-are-operational-and-name-no-person.md) § 3). This reuses the same tenant terms (the
   T&S decryptable-on-report corollary).

   **Delivery — the terms reach the accountless client from the *share*, not the member endpoint or the Host.** The
   member read (`getTenantTerms`) is `bearerAuth`, membership-gated, keyed by `tenantId` — none of which an
   accountless visitor has. A public share is also **Host-independent** (`shareId` is a global key resolvable on
   any Host), so the tenant cannot be inferred from the request Host. The terms therefore come from a dedicated
   **guest** endpoint keyed on the already non-enumerable `shareId`, with no auth, answering the tenant's id, its
   terms and their revision with the operator section (decision 6), behind the share's own gate, so an unknown,
   disabled or expired share is the same non-enumerable 404 as reading the share. **There is no `tenantId` existence
   oracle** — the
   caller passes only the `shareId` they already hold; the opaque `tenantId` is only *returned* (as the localStorage
   key). This is **not** the pre-auth *login-page* terms surface (§Consequences) — that path is Host-served and
   stays a separate, still-deferred slice.

   **Ordering + fail-open.** On the share page the gate sits **after** the password shell (ADR 0037) and **before**
   the display-name pre-screen (ADR 0030/0031; a contributor names itself at its first contribution, behind the
   gate all the same): **password → terms → name → content**, with the curator note's own gate between
   terms and name where a note asks for it (ADR 0036 point 2). It **fails open** — a
   transient terms-fetch error leaves the visitor viewing (a gate is operational, not enforcement; locking someone
   out on a network blip is worse than none), mirroring the member path's fail-open terms refresh.

4. **Operator-blind boundary.** The terms text is public; a member's acceptances are kept as a **history** — one
   row per member, tenant and accepted pair (the space's revision and the operator section's, decision 6), each with
   its time — operational metadata (like membership/role). The gate asks whether the current pair has a row (an
   accept names the current pair or is refused); the rows and
   their times are **audit evidence** of *when* a member accepted *which* revision, should a violation or dispute
   arise — including one about a revision since superseded. Each time is the server's own clock at the acceptance,
   never client-supplied, written once per pair (re-accepting a recorded pair keeps its first time; accepting a
   newer one adds a row and leaves the older ones untouched). No API serves the history, so a space owner
   cannot read it either: the operator and support read it from the database. How long it outlives an erasure is
   [ADR 0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md) decision 6's — evidence outlives
   the person only as far as a dispute can reach; the period and its eraser are
   [cc.privacy.retention](../crosscutting/cc.privacy.retention.md). The anonymous acceptance is
   client-only. **No
   user content, no new blindness cost** — recorded in the [security model](../crosscutting/security-model.md).

5. **Two acceptance tiers, one login surface still deferred.** The space owner sets/edits the terms (the
   space's settings in the web client, every saved change a new revision). The **member** accept/re-accept gate is server-side; the
   **accountless-share** accept/re-accept gate is client-side (§3, uc.sharing.share-accept). What stays deferred is
   only the **pre-auth login-page** surface of a *space's* terms (a Host-served public read — see §Consequences), a
   distinct mechanism from the share-keyed guest read above. The operator section (6) does not wait on it: it is
   public at a page of its own that the login page links.

6. **A fixed operator section, instance-wide, beside every space's terms.** veyq ships its text (DE+EN); nobody
   edits it, and admin-web and the CLI set only the values it renders: the instance contact (ADR 0093 § 11), the
   provider identification (7), the competent data-protection supervisory authority, the guest-write record's N
   (ADR 0093 § 10); the backup period B is the privacy notice's (8). It states:
   - **the notice path**: who can report and how, what a report must hold, that a contact is optional and a report
     of child sexual abuse material needs no name, that a human reviews and nothing is blocked by a report;
   - **what is kept**: the hold and its ends (ADR 0093 § 4), and that backups may hold it longer;
   - **the measures**, in steps and reversible, the **statement of reasons**, its deferral only on an authority's
     request, and the **objection** (ADR 0093 §§ 6–7);
   - **authorities**: Art. 18 reports, orders complied with, terrorist content prohibited and blocked once known,
     and the instance contact as the point of contact for users and authorities and the TCO contact point, with
     its languages;
   - **a pointer to the guest-write record's GDPR Art. 13 information**, which is the privacy notice's (decision 8,
     with its right to object apart): one home for every Art. 13 passage.

   It is shown wherever a space's terms are — the member gate, the share gate, the space's terms panel, and the login
   page once that surface exists — and served with them by the same endpoints, after the space's own text. DSA
   Art. 14(1) asks the terms to be publicly available and easily accessible, so it is also served without an account
   and shown at a page of its own, linked from the login page, the share
   page and the signed-in shell. Its revision moves only with
   its text, that is with a veyq release; a bump reopens the gate like a space's revision, and a value changed in
   admin-web renders at once without reopening it. A space with no terms (revision 0) shows the operator section
   without a gate. The template's clause 9 points to this section instead of regulating review itself.

   **The section has one form on every instance** (ADR 0093 § 14). Every value it names — the instance contact, the provider
   identification — shows when set and as "not stated by this operator" when empty, and the pointer to the guest-write
   record's information is always in it (ADR 0093 § 10: the record is kept wherever a guest may write). The section
   carries one revision, moved with its text, and the gate compares it: a moved revision **reopens the gate**, since
   what members accepted changed; a changed value or retention N does not, since that information reaches its reader
   where it applies (ADR 0093 § 10), like a changed value. A member's history keeps one row per accepted pair (space
   revision, section revision), and the gate asks whether the current pair has one; a guest's device keeps the last
   two pairs it accepted (the current revisions and the previous ones, for a rolled-back release), and its gate asks
   the same question.

   **Gaps against the law, named rather than papered over** (a legal duty wins over what an ADR or the code says). The section's text is the text veyq ships, not legally
   reviewed ([ADR 0093](0093-a-report-opens-one-window-for-the-moderator-and-a-hold-copies-never-blocks.md) § Legal
   basis says why). Each gap is a correction owed before the first release, or a question the law leaves open:
   - **A space's own terms are not public before sign-in** (decision 5); whether DSA Art. 14(1) reaches them, as terms
     the provider's service runs under, is such a question, not pursued.

   What the section says, and the gaps, are the Server Owner's to know: the Run docs'
   [Moderation](../../site/src/content/docs/run/moderation.md) page says so.

7. **A provider identification, set in admin-web.** Name, an address for service, an email address, and for a
   legal person who represents it (a club's board). Required by § 5 DDG of a commercial operator and by § 18 MStV
   of every operator not serving exclusively personal or family purposes. **Never required by veyq, and nothing
   warns** (ADR 0093 § 14): which of those duties reaches an instance is the Server Owner's assessment, and an empty
   identification shows as "not stated by this operator" in the operator section. Where it is set it appears there
   and on the login and share pages. It is public operational data, like the terms.

8. **A fixed instance privacy notice, public, from the Server Owner** (GDPR Art. 13(1), (2)). Like the operator section,
   veyq ships its text (DE+EN), nobody edits it, and it renders instance values: the controller (the provider
   identification, 7) and the contact (ADR 0093 § 11), a data-protection officer where one is set, the supervisory
   authority, the recipients the Server Owner names (who hosts the server, the object store and each mail relay, with
   their country), N and B. It covers what the server processes about a person, each with its purpose, basis and period:
   the account and its address; the session cookies and what the browser keeps; the terms acceptance history (4); the
   moderation data about a person and a reporter's contact (ADR 0093 §§ 4, 11); the mail relays (the instance's, and a
   space's own, which its owner sets); the object store (which, outside the bundled one, sees a browser's address when
   it fetches); the breached-password check the browser makes, its recipient and its switch, and likewise Expenses' day
   exchange rates ([ADR 0108](0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 8); the
   backups and B ([ADR 0056](0056-instance-durable-state-and-cold-logical-backup.md) decision 8); the account erasure
   and what outlives it ([ADR 0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md)); the
   guest-write record, with its right to object set apart; and the rights, with the complaint to the supervisory
   authority. It states what the operator cannot read — content is end-to-end encrypted — as the security model holds
   it.

   It is **public at its own address on every tenant host, with no sign-in**, because Art. 13 applies at
   collection — the invite acceptance that births an account, the report dialog that takes a contact, a guest's
   first write — and each of those links to it, as do the login page, the share page and the signed-in app
   (the provider identification's footer in the app shell). It is information, not consent: no gate, no
   acceptance, and its revision moves only with its text. It is shown on every instance, in one form, an empty value showing as "not stated by this
   operator" and nothing warning (ADR 0093 § 14). Whether a tenant owner is a joint controller (Art. 26) and whether the breached-password check may
   run by default are left open and not pursued (ADR 0093 Still open 6 and 7); the notice names the Server Owner as the
   controller and states the check as it runs. Its text is not legally reviewed either (ADR 0093 § Legal basis), and
   the questions the law leaves open for it are ADR 0093 Still open 6, 7 and 10.

   The notice is shown on **every instance**, every passage being a fact of the software true on every instance (for a
   household the exemption only makes it not owed); it is the **one home of every Art. 13 passage**, the guest-write
   record's included, its right to object set apart, and the operator section keeps a pointer; the recipients are a
   **list** the Server Owner keeps — name, what they do, country — with suggested rows for what the instance knows (an
   external store's host, the instance mail relay's host) that publish nothing until saved, since fixed slots miss a
   backup host or a second relay. What they do is a **fixed kind** — hosting, object storage, mail relay, backups, other
   — which the notice's own text names in each language, so the German and the English notice never mix languages; only
   *other* takes a short description of the operator's own, shown as typed and quoted on both pages, the one place the
   operator's language shows through, and visibly as theirs. The country is an **ISO 3166-1 alpha-2 code** picked from a
   list — the backend's JDK's, copied for the clients — and each page names it in its own language from the JDK's locale
   data ("Deutschland" on the German page, "Germany" on the English one), so a country typed in one language never
   reaches the other's page; a code the backend does not know is refused. A suggested row comes with its kind. A space's
   own mail relay is named generically, as the space owner's choice: who controls it is the joint-control question. A
   guest objecting to the record names the identity whose rows are erased by a **guest id** its own view shows to copy
   (Art. 21(1) with 12(2)), the share identity's random id; an objection erases the rows so far and does not stop later
   writes from being recorded, and the notice says so.

## Consequences

- **New operator-visible surface, all public/operational:** the tenant's terms and their revision (public), and each
  member's acceptance history (operational metadata). No content, no key.
- **The terms are read where they are accepted** — the member gate and the share gate — and edited on an **authed
  tenant endpoint** beside a member **accept** endpoint; their pre-auth read on the login page stays decision 5's
  open slice.
- **The clause template gives a starting ToS** (esp. the CSAM/illegal-content + review-removal clauses) matching the
  pre-E2EE product. It is not legally reviewed: the space owner who publishes it answers for it. No server ships default terms: a new tenant has none (revision 0) until
  its owner writes them, the template inserted only on request. DE strings follow
  [cc.inclusive-language](../crosscutting/cc.inclusive-language.md).
- **Anonymous client-side acceptance is honest about its limits** (no server non-repudiation for an accountless
  visitor) — a stated property of the share gate.
- **Ties into the T&S "decryptable-on-report" corollary** — that a reported **public** share is
  decryptable-on-report is a property of every public share on every server (the report carries the link's
  secret, [security model](../crosscutting/security-model.md)), not something a space chooses. The terms are
  where the space tells its members and guests so; how a report is handled is the operator section's (6), which
  every space carries. The report intake, the measures, the statement of reasons and the objection are ADR 0093's
  (§§ 1–7).
- **The operator section and the provider identification are new public surface** (6, 7): instance settings in
  admin-web and the CLI ([ADR 0089](0089-instance-settings-live-in-admin-web-the-environment-only-bootstraps.md)),
  rendered into the terms endpoints and served publicly. A Server Owner who leaves the identification empty is
  neither warned nor refused; the section shows the gap (decision 6). The identification is shown with the instance
  contact on the login and share pages and in the signed-in shell
  ([uc.admin.operator-details](../features/admin/use-cases/uc.admin.operator-details.md)); the section is rendered into
  every terms read after the space's text, with the gate on the pair
  ([uc.account.accept-terms](../features/account/use-cases/uc.account.accept-terms.md)). Decision 6 names the gaps
  against the law that remain.
- **The privacy notice is a second fixed text** (8), with its own public route and endpoint, the recipients list and
  an optional data-protection officer as new instance values, and links from every place that collects. The space
  template's privacy clause stays the space owner's description of their space, never the operator's notice. The
  guest-write record's Art. 13 passage is the notice's; the operator section (6) points to it. Where it is built:
  [uc.admin.privacy-notice](../features/admin/use-cases/uc.admin.privacy-notice.md).

## Alternatives considered

- **Server-side acceptance for anonymous share visitors too.** Rejected — attributing acceptance to an accountless
  visitor needs a cookie/fingerprint that either **breaks anonymity** or is unreliable; client-side localStorage
  is the honest choice for an accountless visitor.
- **Shown-only (no acceptance gate).** Rejected — no consent record; a **hard gate + re-accept on change** is the
  choice that records a consent, matching the pre-E2EE product.
- **Free-text markdown (no clause template).** Rejected — the template gives space owners a sound starting ToS
  (the CSAM/illegal + review-removal clauses matter, unreviewed as they are) and matches the pre-E2EE UX; the owner can still edit freely.
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
