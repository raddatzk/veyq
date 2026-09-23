---
id: cr-client-hygiene
title: Client hygiene — invite links, a secret left in the address bar, and claims nothing backs
status: in-progress
satisfies: [cc.security.csp]
adrs: ["0005", "0048", "0054", "0055", "0065"]
invariants: [operator-blind E2EE, self-hostable by default]
---

## Goal

Five small defects from the 2026-09-23 security/reuse review (inbox #165), none with a design question left:
- **(a)** admin-web builds the tenant owner's invite link from `window.location.protocol`. admin-web runs on
  `http://localhost`, so the link's first hop is plaintext; the mailer's links use `https://`.
- **(b)** `TenantJoinView` leaves the live invite secret in the address bar.
- **(c)** Strings still promise the copy-link invite that ADR 0055 retired.
- **(d)** admin-web's `<meta>` CSP carries a `frame-ancestors` that a meta policy ignores, and a comment that
  leans on it.
- **(e)** Docs and headers make claims the code does not back.

Refined and implemented in one session under the named exception in
[docs/ai/README.md](../docs/ai/README.md) § Session topology (owner's call, 2026-09-23). Each item is
mechanical and is pinned by `npm run check` in `web/` and `admin-web/`, a test that bites, or a grep.

## Context & constraints

- **ADR 0055 §3:** the Server Owner's invite to a tenant's first owner is the **one surviving copy-link**. Member
  invites are mail-only, and without a mailer inviting and recovery are both refused (`503 mail_unavailable`).
- **ADR 0065:** web and admin-web share only `packages/crypto`, so each app fixes its own copy.
- **Second-angle sweeps at refine found more sites than the row named.** They are listed per item below,
  each with the command that finds them.

## Phases

- [ ] 1 (a) + (b) — the invite link and the address bar.
      - admin-web `buildInviteLink(secret, domain)`: the domain is **required**, because `TenantSummary.domain`
        is required in the contract and both callers pass one. The link is always `https://${domain}`. The dead
        same-host branch and its test go. The test that pinned protocol-keeping now asserts `https:` under
        happy-dom's `http:` origin, which is red on the old code.
      - `web/src/shared/inviteLink.ts` + its test are **deleted**: `grep -rn inviteLink web/src` finds no
        importer, only its own test and a stale `vi.mock` in `TenantMembersPanel.test.ts`, which goes too. That
        file's stub i18n keys are inbox #171's.
      - `TenantJoinView` strips the fragment once it has read the secret, and `RecoverView` stops replacing the
        router's state with `null`. Both call `history.replaceState(history.state, '', <path without hash>)`.
        vue-router 5 warns about a `null` state (`grep -n 'manually replaced' node_modules/vue-router/dist/vue-router.js`).
        A reload after the strip reaches the join page with no secret. That case gets its own message,
        *open the invite link again*, in EN + DE, instead of "this invite link is invalid". Tests: the join page
        clears the fragment and keeps `history.state`; with no fragment it shows the new message.
      · Done when: `npm run check` green in both packages.
- [ ] 2 (c) — no string or claim promises a member copy-link.
      - web `mail.editorSub` / `mail.enabledHint` / `mail.keyMismatch`, EN + DE: without a mailer, invitations
        and password-reset emails **cannot be sent**, per ADR 0055. They are not "shared as a copy link".
      - admin-web's `mail` and `terms` catalog blocks are **deleted**. No admin-web source asks for a key in
        either one: `grep -rnE "t\('(mail|terms)\." admin-web/src --include=*.vue --include=*.ts`. Their panels
        stayed in web (ADR 0065), and the catalog's own header says only the keys that moved with their views
        were lifted.
      - Stale comments: `TenantMailSettingsPanel.vue`'s header and `TenantMailSettingsPanel.test.ts`, both
        "falls back to copy-link". ADR 0048's decision headline says *"No tenant SMTP configured ⇒ the copy-link
        path, unchanged"*, while its own §4/§5 say the opposite; the headline is edited to match.
      - Sweep: `grep -rn -i -E 'copy[- ]link|kopierlink' --include='*.ts' --include='*.vue' --include='*.md' . | grep -v node_modules`.
        Every remaining hit is the owner-bootstrap invite, a share link, or a statement that there is none.
      · Done when: `npm run check` green in both packages.
- [ ] 3 (d) — admin-web's CSP. The policy moves from `admin-web/vite.config.ts` into
      `admin-web/src/shared/security/csp.ts`, the shape web already has, so it can be tested. `frame-ancestors`
      is dropped: a `<meta>` policy ignores it, and nginx's `X-Frame-Options: DENY` is the real guard. A test
      pins `script-src 'self'` with no `unsafe-*`, `object-src 'none'`, `connect-src` exactly `'self'` + the
      HIBP origin, and no `frame-ancestors`. The `admin-web/nginx.conf.template` comment stops citing the meta
      policy as belt-and-braces. [cc.security.csp](../docs/crosscutting/cc.security.csp.md) names admin-web,
      its policy module, its anti-framing header, and its test.
      · Done when: `npm run check` (admin-web) green; `npm run build` (admin-web) emits the meta tag without
        `frame-ancestors`.
- [ ] 4 (e) — claims nothing backs.
      - **"Recovery code":** there is none; the Secret Key is the recovery factor (ADR 0054 §1). Sites:
        `grep -rn -i 'recovery code\|wiederherstellungscode' docs --include='*.md' | grep -v -e docs/decisions/ -e docs/inbox.md`.
        That finds security-model § Recovery, setup-server (EN+DE), the architecture diagram and glossary, and
        unlock A1. The account `_index.md` hit is a correct statement and stays. The crypto vectors' field name
        `recovery_code` and the parameter of `kekRecovery` stay: they are contract, and the KEK also serves the
        invite temp key.
      - **Dead pointers:** `packages/crypto/src/v1.ts` cites `docs/api/crypto-vectors/v1.json` twice and a
        `crypto-spec.md` that does not exist. The vectors live at `contracts/crypto/vectors/v1.json`. Three docs
        point at a *"crypto wire-format spec"* that does not exist either:
        `grep -rn -i 'wire-format spec' docs`. They point at the vectors + `packages/crypto/src/v1.ts` instead.
      - **The device key "cannot be carried off":** a non-extractable key stops a script from **exporting its
        bytes**. It does not stop the script copying what the key computes, and the DEVICE factor is a fixed
        HMAC, so one run of a script copies a permanent stand-in. What holds the wrap after that is the password.
        The reasoning is written once, in ADR 0005 §5, and the others point at it. Sites:
        `grep -rn -i 'exfiltrat\|carry the key\|carry it off\|off-device' docs web/src/features/account/trustDevice`.
      · Done when: the trust-chain guards are green.
- [ ] 5 close — inbox #165 marked done, what binds moved into the tree, this file deleted.

## Definition of done

- `npm run check` green in `web/` and `admin-web/`; trust-chain guards green.
- The https test and the fragment test were seen red against the old code.
- The three sweeps in phases 2 and 4 return only the hits named there as staying.

## Retrospective
