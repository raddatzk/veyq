# UC: Share accept — an accountless visitor accepts the tenant's terms before viewing

- **ID:** uc.sharing.share-accept
- **Primary actor:** The **accountless share visitor** — someone who opened a public share link `/s/{shareId}#k=…`
  with **no veyq account** (contrast [uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md),
  the *member* half of the same gate).
- **Goal:** Before the visitor can **view** a share (or **contribute** to it), they must **accept the operator's
  current terms** for the tenant that serves the share — the anonymous, client-side half of the two-tier terms
  gate ([ADR 0035](../../../decisions/0035-server-terms-of-service.md) §3). A hard gate, **re-accept on a version
  bump**.
- **Preconditions:** A resolvable share (existing, enabled, unexpired). The tenant that owns the share has
  **published terms** (`termsVersion > 0`); a tenant with no terms (version 0) never gates. If the share is
  password-protected ([uc.sharing.share-password](uc.sharing.share-password.md)) the visitor has already unlocked
  it — the terms gate sits **after** the password shell.
- **Postconditions (success):** the visitor's acceptance is recorded **client-side only** (localStorage, keyed by
  the tenant + version); the gate clears and the visitor proceeds to the content — or, for a CONTRIBUTOR/UPLOADER
  share, to the display-name pre-screen ([uc.sharing.share-contributor](uc.sharing.share-contributor.md) /
  [uc.sharing.share-uploader](uc.sharing.share-uploader.md)). **The server records nothing** about the accountless
  visitor — anonymity is preserved.

## The mechanism here

The terms are the **operator's public policy** — the same versioned per-locale markdown a *member* accepts
([uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md), [ADR 0035](../../../decisions/0035-server-terms-of-service.md)),
including the CSAM / illegal-content and review-and-removal clauses that govern a reported public share (the T&S
"decryptable-on-report" corollary in the [security model](../../../crosscutting/security-model.md)). They are
**public operational content, never E2EE** — so serving them to an accountless visitor costs no blindness.

**How the terms reach an accountless client — and why not the member endpoint.** The member read
(`getTenantTerms`) is `bearerAuth`, membership-gated, and keyed by `tenantId` — none of which an accountless
visitor has. The member endpoint's own contract already earmarks this: *"a pre-auth public-serving path is a later
slice."* A public share is also **Host-independent** — `shareId` is a global key resolvable on any Host, so the
tenant **cannot** be inferred from the request Host. The terms must therefore come from the **share** itself, via
a dedicated **guest** endpoint keyed on the (already non-enumerable) `shareId`:

- **`GET /api/shares/{shareId}/terms`** (no auth) → `{ tenantId, termsVersion, terms }`. It reuses the share's
  existing `activeShare()` gate, so an unknown / disabled / expired share is the **same non-enumerable 404** as
  `resolveShare` — there is **no `tenantId` existence oracle** (the caller never passes a `tenantId`; the opaque
  `tenantId` is only *returned*, and only to a caller already holding a valid `shareId`). It serves only public
  terms content, independent of the share password.

**Acceptance is client-only.** On accept, the client writes the accepted `termsVersion` to **localStorage keyed by
`tenantId`** — so accepting on one share of a tenant clears the gate on **every** share of that tenant (you accept
the *operator's* terms, not the link). The returned `tenantId` is the localStorage key; the endpoint's `shareId`
keying is what removes the oracle. When the operator bumps `termsVersion`, the stored value is now below current →
the gate re-fires (re-accept).

**Honest about its limits — this is an operational gate, not enforcement.** A client-side localStorage gate is
**bypassable** by an accountless visitor (devtools, a fresh profile, or resolving the share directly). It is the
same class of gate as the member terms gate, which the code frames as *"an operational gate, not a confidentiality
control"* ([activeSpace.ts](../../../../web/src/app/activeSpace.ts)) — it records consent and surfaces the policy,
it does not *prevent* access, and it is **not** a legal-non-repudiation record for an accountless visitor (ADR 0035
§3 states this limit). Because the terms carry the legally-meaningful clauses, the operator's real protection is the
**decryptable-on-report** review-and-removal path (security model), not this prompt. Consistent with that, the gate
**fails open**: a transient terms-fetch error leaves the visitor viewing (a gate that locks people out on a network
blip is worse than none, and it is not enforcement anyway) — mirroring the member path's fail-open
`refreshTenantTerms`.

## Main flow

1. A visitor opens `/s/{shareId}#k=…`. The share resolves (past the password shell, if any).
2. The client fetches `GET /api/shares/{shareId}/terms`. If `termsVersion > 0` **and** the locally-stored accepted
   version for that `tenantId` is lower, the **terms gate** shows: the operator's terms (viewer's locale, rendered
   with the safe markdown renderer reused from member terms + share notes) and an **Accept** button.
3. The visitor accepts → the client writes `termsVersion` to `localStorage[tenant]` → the gate clears.
4. The visitor proceeds: straight to the read-only gallery (VIEWER), or to the mandatory display-name pre-screen
   (CONTRIBUTOR / UPLOADER) and then contributing.

## Alternate / exception flows

- **Tenant has no terms** (`termsVersion == 0`) → no gate; step 2 falls straight through to the content / name
  pre-screen.
- **Already accepted this version** → the stored version equals current, so the gate does not fire (a reload does
  not re-prompt).
- **Operator bumped the version** → stored `<` current → the gate re-fires; the visitor re-reads and re-accepts.
- **Password-protected share** → the password shell ([uc.sharing.share-password](uc.sharing.share-password.md))
  comes **first**; the terms gate only appears once the share is unlocked. Order: **password → terms → name →
  content**.
- **Transient terms-fetch error** → **fail open**: treat as no gate (version 0) and let the visitor view; a later
  reload retries.
- **Revoked / expired / unknown share** → `GET …/terms` is the same non-enumerable `404` as `resolveShare`; there
  is nothing to gate.

## What this means for you / Was das für dich bedeutet

**EN —** When you open a shared link, the person running the server may ask you to accept their terms of use first —
the house rules for what may be shared (no illegal content, how to report something). You accept once per server; a
reload won't ask again unless the operator updates the terms. Your acceptance stays **on your device** — the server
records nothing about you, so opening a share stays anonymous. It's a courtesy prompt, not a lock: it shows you the
rules, it doesn't police them.

**DE —** Wenn du einen geteilten Link öffnest, kann die Person, die den Server betreibt, dich bitten, zuerst ihre
Nutzungsbedingungen zu akzeptieren — die Hausordnung dafür, was geteilt werden darf (keine illegalen Inhalte, wie
man etwas meldet). Du akzeptierst einmal pro Server; ein Neuladen fragt nicht erneut, außer die betreibende Person
ändert die Bedingungen. Deine Zustimmung bleibt **auf deinem Gerät** — der Server speichert nichts über dich, das
Öffnen eines Shares bleibt anonym. Es ist ein Hinweis, keine Sperre: Er zeigt dir die Regeln, erzwingt sie nicht.

## Related

- **Feature:** [sharing](../_index.md)
- **Use-cases:** [uc.account.accept-terms](../../account/use-cases/uc.account.accept-terms.md) (the *member* half,
  server-side), [uc.sharing.share-public-viewer](uc.sharing.share-public-viewer.md),
  [uc.sharing.share-contributor](uc.sharing.share-contributor.md),
  [uc.sharing.share-uploader](uc.sharing.share-uploader.md) (the roles the gate precedes),
  [uc.sharing.share-password](uc.sharing.share-password.md) (the shell that precedes this gate),
  [uc.admin.tenant-terms](../../admin/use-cases/uc.admin.tenant-terms.md) (where the operator sets the terms).
- **Decisions:** [ADR 0035](../../../decisions/0035-server-terms-of-service.md) §3 (the anonymous, client-side
  acceptance tier), [ADR 0029](../../../decisions/0029-public-viewer-share.md) (the share model + the anonymous
  access path), [ADR 0038](../../../decisions/0038-contracts-shared-components-bundling.md) (`LocalizedMarkdown`
  shared via `_common`).
- **Cross-cutting:** [security-model](../../../crosscutting/security-model.md) (terms are public operational
  content; the decryptable-on-report / review-and-removal path is the operator's real remedy, not this prompt).
