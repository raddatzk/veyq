---
id: cr-admin-web-own-confirm-dialog
title: admin-web gets its own copy of the confirmation dialog, and the styleguide stops undercounting the dedicated ones
status: in-progress
satisfies: ["uc.admin.create-tenant", "uc.admin.suspend-tenant"]
adrs: ["0065"]
invariants:
  - "the port is a COPY, not an import — ADR 0065 gives `admin-web` no shared UI, and `useToast.ts` is the byte-identical precedent"
  - "`no-alert` and the test rewrite land in ONE commit — the rule must never redden the suite it protects"
  - "`web/src` is not touched; the only `web`-side file is the styleguide doc"
  - "behaviour-preserving: the same three actions stay confirm-gated, the same two do not"
---

## Goal

`cr-web-own-confirm-dialog` gave `web/` one shared confirmation dialog and an ESLint `no-alert` that keeps
`window.confirm` out. It deliberately left two things, both raised as inbox row **144**.

**(a) The port.** `admin-web/src/features/tenants/TenantDetailPanel.vue` still confirms through the browser's
grey box at three sites — suspend a tenant (`:94`), change a tenant's address (`:113`, the one whose copy
interpolates `{domain}`) and revoke a pending tenant (`:132`) [M: 2026-09-20,
`grep -n "window\.confirm" admin-web/src`]. The Server-Owner console is the surface where a mis-read
confirmation costs the most — taking a tenant offline locks every one of its members out at once — and it is
currently the surface with the least product voice around that decision.

**(b) The three hand-rolled dialogs `web` still carries.** Settled at refine, and the answer is **do not
merge**: this half is a docs correction, not code. See § Decision 5.

## Context & constraints

- **[ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md) makes this a copy, not an import.**
  `admin-web` is its own package with its own container; nothing but `@veyq/crypto` is shared. The house
  precedent is exact and already in this same directory: `diff web/src/shared/state/useToast.ts
  admin-web/src/shared/state/useToast.ts` is **empty** — a byte-identical copy, header comment included
  [M: 2026-09-20].
- **No CSS port is needed.** `.veyq-dialog-action-row` and its `@media` single-column override are already
  byte-identical in `admin-web/src/styles/ui.css` (lines 99–127 and 491–502, same as `web`) [M: 2026-09-20,
  `grep -n -A14 veyq-dialog-action-row` on both]. Only the component's own scoped `.dialog-copy` travels, and
  its `max-width` comment is web-specific (it names the saved-key escalation, a sentence this package does not
  own) — so it is re-justified here, not copied.
- **The gate that stands in for a fresh implement session.** This is the named same-session refine+implement
  exception in [`docs/ai/README.md`](../docs/ai/README.md) § *Session topology*: tightly-scoped, mechanical,
  behaviour-preserving, and backed by three automatic gates —
  `admin-web/src/i18n/i18n.test.ts` (EN/DE key-set parity **and** every literal `t('…')` resolves, so a
  half-added header key fails), the existing `TenantDetailPanel.test.ts` behaviour suite, and the new
  `no-alert` rule itself.
- **Surfaces touched:** `admin-web/` only for code — two new `shared/` files + their two tests, one component,
  one component test, `App.vue`, `i18n/index.ts` (both locale blocks), `eslint.config.js`. Plus exactly one
  `web`-side file, and it is a doc: `docs/crosscutting/ui-web-styleguide.md`. No contracts, no backend, no
  `web/src`.

## Decisions

### Settled

1. **Copy `ConfirmDialog.vue` + `useConfirm.ts` verbatim, adjusting only what is package-specific.** The two
   files carry their own rationale in their header comments (own component rather than PrimeVue's
   `ConfirmationService`; `ask()` resolves and never runs the mutation; single-flight; cancel/Escape/dismiss are
   one answer). None of that reasoning is web-specific, so none of it is rewritten — only the `(cr-…)`
   attribution and the `.dialog-copy` justification change. Re-deriving the shape here would be the *worse*
   outcome: two dialogs that drift.
2. **Mount once in `admin-web/src/App.vue`, beside `<Toast />`.** The analogue of `web/src/App.vue`, and for
   the same reason: it is outside both shells (card and console), because a confirmation may be asked from
   either. Here it sits after `<Toast />` at the root, next to the layout `<main>`s, not inside one.
3. **Three new imperative header keys, EN + DE.** The existing `tenant.suspendConfirm` /
   `tenant.changeDomainConfirm` / `tenant.revokeConfirm` are already question-voice **bodies** carrying the full
   consequence ("Take this tenant offline? Nobody — including its members — …"), so they stay the `message`
   untouched — they are the most consequential copy this surface owns. What is missing is the header, and it is
   **imperative**, per § Decision 5's convention: `tenant.suspendConfirmTitle`,
   `tenant.changeDomainConfirmTitle`, `tenant.revokeConfirmTitle`. Naming follows `web`'s `*ConfirmTitle`.
4. **`acceptLabel` reuses the label of the button that opened the dialog** — `tenant.suspend` /
   `tenant.changeDomainSubmit` / `tenant.revoke`, with that button's own icon. A dialog whose primary reads
   differently from the control that summoned it is a new way to be wrong (`web` Decision 6).
5. **(b) Do NOT grow a mutation-owning `ConfirmDialog` variant.** The measurement that settles it:
   - The row says three dedicated dialogs; there are **five** — `DeleteNodeDialog.vue`, `DeviceTrustControl.vue`
     (untrust), `MemoryDetail.vue`'s trash purge, `KeyRotationPanel.vue`, and `MemoryBuckets.vue`'s
     type-the-name delete.
   - They share **no contract**. One has no inline error at all; one carries a data payload and pluralised copy;
     one closes itself only after a state re-read; one is a form built from a different button family. A shared
     component whose five callers agree on nothing is five props and five branches.
   - What is actually duplicated is ~12 lines of dialog **chrome** plus a `.dialog-copy` rule copy-pasted five
     times. What *differs* is precisely the mutation ownership the shared dialog exists to refuse.
   So half (b) is a docs change: correct the undercount, and write down the convention the measurement found —
   **imperative headers on the shared `ask()`, question headers on the mutation-owning dialogs. The voice
   follows the mechanism.** That is a rule worth stating, not an inconsistency to flatten; stating it is what
   stops the next reader "fixing" it.
6. **A chrome extraction is explicitly out of scope** and is not authorized by this CR. If it is worth doing it
   is its own proposal to the owner.

### The one trap

`admin-web/eslint.config.js` has no `no-alert` today, and
`admin-web/src/features/tenants/TenantDetailPanel.test.ts` **assigns `window.confirm` at seven places**
(lines 99, 104, 156, 161, 176, 181, 212) [M: 2026-09-20]. The `web` rule is deliberately **not** exempted for
test files — a fixture is the easiest place to quietly re-introduce a native dialog — and that stays. So the
rule and the test rewrite **must land in the same commit**, or the guard reddens the suite it was added to
protect. The rewrite follows `web`'s own precedent (`SessionsPanel.test.ts:46`): hoist a `confirmAsk` mock and
`vi.mock` the `useConfirm` module, then answer with `mockResolvedValue(false|true)`. Stubbing `window.confirm`
after the port would pass every test by confirming *nothing* — which is the failure this rewrite is really
guarding against.

## Definition of done

- [ ] `admin-web/src/shared/state/useConfirm.ts` + `admin-web/src/shared/ui/ConfirmDialog.vue` exist, with
      their own tests ported (`useConfirm.test.ts`, `ConfirmDialog.test.ts` — the latter stubbing PrimeVue
      `Dialog` the way `CreateTenantDialog.test.ts` already does).
- [ ] `TenantDetailPanel.vue` has **zero** `window.confirm`; all three sites keep their guard clause as
      `if (!(await ask({ … }))) return`, and reactivate stays ungated.
- [ ] `ConfirmDialog` mounted once in `admin-web/src/App.vue`.
- [ ] Six new i18n entries (three keys × EN/DE); `i18n.test.ts` green.
- [ ] `no-alert` in `admin-web/eslint.config.js` **with its comment**, not exempted for tests, in the **same
      commit** as the test rewrite.
- [ ] `docs/crosscutting/ui-web-styleguide.md` names five dedicated dialogs, not three, and carries the
      header-voice convention.
- [ ] `cd admin-web && npm run check` green; `python3 scripts/validate_doc_links.py` green.
- [ ] Guard bite-test: the `no-alert` rule was proven to **fail** on a deliberately re-introduced
      `window.confirm` before being trusted.
