# UC: Share accept — an accountless visitor accepts the tenant's terms before viewing

- **ID:** uc.sharing.share-accept
- **Primary actor:** The **accountless share visitor** — someone who opened a public share link `/s/{shareId}#k=…`
  with **no veyq account** (contrast [uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md),
  the *member* half of the same gate).
- **Goal:** Before the visitor can **view** a share (or **contribute** to it), they must **accept the operator's
  current terms** for the tenant that serves the share — the anonymous, client-side half of the two-tier terms
  gate ([ADR 0035](../../../decisions/0035-server-terms-of-service.md) §3). A hard gate, **re-accept on a revision
  bump**.
- **Preconditions:** A resolvable share (existing, enabled, unexpired). The tenant that owns the share has
  **published terms** (`termsRevision > 0`); a tenant with no terms (revision 0) never gates. If the share is
  password-protected ([uc.sharing.share-password](uc.sharing.share-password.md)) the visitor has already unlocked
  it — the terms gate sits **after** the password shell.
- **Postconditions (success):** the visitor's acceptance is recorded **client-side only** (localStorage, keyed by
  the tenant, holding the pairs accepted there, the latest two); the gate clears and the visitor proceeds to the content — or, for a WRITE_ONLY share, to
  the display-name pre-screen ([uc.sharing.share-uploader](uc.sharing.share-uploader.md)); a READ_WRITE visitor is
  asked its name at its first contribution ([uc.sharing.share-contributor](uc.sharing.share-contributor.md)). **The server records nothing** about the accountless
  visitor — anonymity is preserved.

## The mechanism here

The terms are the **space's public policy** — the same per-locale markdown, at the same revision, a *member*
accepts ([uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md), [ADR 0035](../../../decisions/0035-server-terms-of-service.md)),
including the CSAM / illegal-content and review-and-removal clauses that govern a reported public share (the T&S
"decryptable-on-report" corollary in the [security model](../../../crosscutting/security-model.md)). They are
**public operational content, never E2EE** — so serving them to an accountless visitor costs no blindness.

**How the terms reach an accountless client — and why not the member endpoint.** The member read
(`getTenantTerms`) is `bearerAuth`, membership-gated, and keyed by `tenantId` — none of which an accountless
visitor has. The member endpoint's own contract already earmarks this: *"a pre-auth public-serving path is a later
slice."* A public share is also **Host-independent** — `shareId` is a global key resolvable on any Host, so the
tenant **cannot** be inferred from the request Host. The terms must therefore come from the **share** itself, via
a dedicated **guest** endpoint keyed on the (already non-enumerable) `shareId`:

- **`GET /api/shares/{shareId}/terms`** (no auth) → `{ tenantId, termsRevision, terms }`. It reuses the share's
  existing `activeShare()` gate, so an unknown / disabled / expired share is the **same non-enumerable 404** as
  `resolveShare` — there is **no `tenantId` existence oracle** (the caller never passes a `tenantId`; the opaque
  `tenantId` is only *returned*, and only to a caller already holding a valid `shareId`). It serves only public
  terms content, independent of the share password.

**The operator section comes with them** ([ADR 0035](../../../decisions/0035-server-terms-of-service.md) decision 6):
the same endpoint serves, after the space's text, the fixed section the Server Owner fills with values, in its one form
(ADR 0093 § 14), with a pointer to the guest-write record's information — the instance privacy notice's, with its right
to object apart (ADR 0035 decision 8). What is accepted is the **pair** of the space's `termsRevision` and the section's
revision, as for a member ([uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md)). A space with no terms
(revision 0) does not gate: the visitor reads the section behind the page's *Terms* link, and the privacy notice behind
its *Privacy* link, which the guest-write record's first-layer sentence opens at the record's passage.

**Acceptance is client-only.** On accept, the client adds the accepted pair to those kept in **localStorage keyed
by `tenantId`** — so accepting on one share of a tenant clears the gate on **every** share of that tenant (you accept
the *space's* terms, not the link). The returned `tenantId` is the localStorage key; the endpoint's `shareId`
keying is what removes the oracle. The gate asks whether the current pair is among those kept, the same question a
member's gate asks of their history: when the space owner bumps `termsRevision` or a release changes the section's
text, it is not → the gate re-fires (re-accept); a changed value does not. The device keeps the **last two** pairs:
both revisions only grow, so the current pair and the previous one (a rolled-back release) are all that can come back.
A value an earlier client stored that is no pair (a bare revision, or a triple with the retired variant) counts as
nothing, so that visitor is asked once more, and the next accept drops it.

**Honest about its limits — this is an operational gate, not enforcement.** A client-side localStorage gate is
**bypassable** by an accountless visitor (devtools, a fresh profile, or resolving the share directly). It is the
same class of gate as the member terms gate, which the code frames as *"an operational gate, not a confidentiality
control"* ([activeSpace.ts](../../../../src/web/src/app/activeSpace.ts)) — it records consent and surfaces the policy,
it does not *prevent* access, and it is **not** a legal-non-repudiation record for an accountless visitor (ADR 0035
§3 states this limit). Because the terms carry the legally-meaningful clauses, the operator's real protection is the
**decryptable-on-report** review-and-removal path (security model), not this prompt. Consistent with that, the gate
**fails open**: a transient terms-fetch error leaves the visitor viewing (a gate that locks people out on a network
blip is worse than none, and it is not enforcement anyway) — mirroring the member path's fail-open
`refreshTenantTerms`.

## Main flow

1. A visitor opens `/s/{shareId}#k=…`. The share resolves (past the password shell, if any).
2. The client fetches `GET /api/shares/{shareId}/terms`. If `termsRevision > 0` **and** the current pair is not
   among those stored for that `tenantId`, the **terms gate** shows: the space's terms, then the operator
   section (viewer's locale, rendered with the safe markdown renderer reused from member terms + share notes) and an
   **Accept** button.
3. The visitor accepts → the client adds the pair to `localStorage[tenant]` → the gate clears.
4. The visitor proceeds: straight to the content (READ, READ_WRITE — a contributor names itself at its first
   contribution), or to the mandatory display-name pre-screen (WRITE_ONLY) and then contributing.

## Alternate / exception flows

- **Tenant has no terms** (`termsRevision == 0`) → no gate; step 2 falls straight through to the content / name
  pre-screen.
- **Already accepted this pair** → it is among those stored, so the gate does not fire (a reload does not
  re-prompt).
- **The space owner bumped the revision, or a release changed the section** → the current pair is not among those
  stored → the gate re-fires; the visitor re-reads and re-accepts.
- **Password-protected share** → the password shell ([uc.sharing.share-password](uc.sharing.share-password.md))
  comes **first**; the terms gate only appears once the share is unlocked. Order: **password → terms → note → name →
  content**, the note's gate only where the curator asked for it ([uc.sharing.share-note](uc.sharing.share-note.md)).
- **Transient terms-fetch error** → **fail open**: treat as no gate (revision 0) and let the visitor view; a later
  reload retries.
- **Revoked / expired / unknown share** → `GET …/terms` is the same non-enumerable `404` as `resolveShare`; there
  is nothing to gate.

## User impact

Someone opening your link sees the space's terms of use first and accepts them once, without giving up their
anonymity.

## Related

- Enabler: [en.sharing.public-link](../enablers/en.sharing.public-link.md) (the record, keys, gates and lifecycle)
- **Feature:** [sharing](../_index.md)
- **Use-cases:** [uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md) (the *member* half,
  server-side), [uc.sharing.share-public-viewer](uc.sharing.share-public-viewer.md),
  [uc.sharing.share-contributor](uc.sharing.share-contributor.md),
  [uc.sharing.share-uploader](uc.sharing.share-uploader.md) (the roles the gate precedes),
  [uc.sharing.share-password](uc.sharing.share-password.md) (the shell that precedes this gate),
  [uc.admin.tenant-terms](../../admin/use-cases/uc.admin.tenant-terms.md) (where the space owner sets the terms).
- **Decisions:** [ADR 0035](../../../decisions/0035-server-terms-of-service.md) §3 (the anonymous, client-side
  acceptance tier), [ADR 0029](../../../decisions/0029-public-viewer-share.md) (the share model + the anonymous
  access path), [ADR 0038](../../../decisions/0038-contracts-shared-components-bundling.md) (`LocalizedMarkdown`
  shared via `_common`).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md) (terms are public operational
  content; the decryptable-on-report / review-and-removal path is the operator's real remedy, not this prompt).
- End-user view (docs site): [What a guest sees](../../../../site/src/content/docs/sharing/what-guests-see.mdx)
