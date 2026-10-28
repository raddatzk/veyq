---
id: cr-who-is-who
title: An overview "who's who" per tenant - whose key is confirmed, who verified whom, who has a new identity
status: done
satisfies: ["uc.spaces.enforce-verified-identities"]
adrs: ["0111", "0028", "0104", "0022"]
invariants:
  - operator-blind - the overview reads only what the members list and the profile already read (the tenant directory with its join records, the key chains, the pin store, the verification rows); nothing new reaches the server, and the only write is the pin move every anchor check already makes
  - an anomaly is never shown as verified - a verification counts only for a member this device anchors in the tenant, at their current head
  - display only - nothing in the overview gates a wrap or anybody's write
  - share view = member view - the overview is the same for an owner and a member; a link guest has no tenant and never reaches it
---

## Goal

Inbox #562. Today a member learns who is who in a space one person at a time: the members list badges *Verified*,
*New* and the anomaly, and a person's profile says *Verified by …*, *New identity, signed by their previous key*, and
offers the compare. This CR adds one page that answers it for everyone at once, per tenant: for every other member,
whether this device confirms their key there, who verified them, whether they are new to this account, and whether
they secured their account recently - with the way into the compare. It is the member list plus what `tenantBadges`
and `personInTenant` already read ([ADR 0111](../docs/decisions/0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
§ Consequences: "cheap once the trust records exist").

## Context & constraints

- Trust model and what a member sees: [keys, grants and trust](../docs/crosscutting/keys-grants-and-trust.md) § Trust;
  ADR 0111 decisions 12-16.
- Use case: [uc.spaces.enforce-verified-identities](../docs/features/spaces/use-cases/uc.spaces.enforce-verified-identities.md),
  whose goal already names *"see who is verified wherever members are listed"*; this CR adds the step and criterion.
- Built code it reads, unchanged: `src/web/src/features/account/tenantTrust.ts` (`loadTenantView`,
  `listAllVerifications`, `countVerifications`, `NEW_FOR_MS`), `identityPins.ts` (`anchorIdentity`, `fetchKeyChain`,
  `pinStoreDocument`). A parallel CR (#565, #568-#572) edits both files, so this CR adds a module of its own and edits
  neither.
- The settings hub's rail and gates: `src/web/src/views/SettingsHubView.vue` (ADR 0022).

## Decisions (assistant, 2026-10-28; the owner may veto each)

1. **Where: Settings → Shared space → "Who's who", right below Members**, shown to every member of the tenant, owner or
   not, under the same gate as Members (the tenant category, while the tenant space is the active one). *Why:* trust is
   per tenant (decision 15) and the tenant's own pages live in that category; every member reads the same records, so
   there is nothing an owner sees that a member does not (share view = member view: rights only remove functions, and
   here none differ). A link guest has no tenant membership, no pins and no profile links, so the page has no guest
   form. A group lives in its tenant and its members are anchored there, so there is no per-group overview.
2. **Its own page, not more lines in the Members list.** *Why:* the Members list is the management list, shared in
   shape with every group's, and decision 15 keeps *not verified* silent there on purpose; the overview is the one
   place that says *Not verified* and names the verifiers. Folding it in would change that rule for both lists.
3. **The active tenant only.** *Why:* every read here is the active tenant's (its scope list, its names), and a
   verification counts only in its own tenant; a page across tenants would open each one and set side by side what
   cannot be compared. Switching the space switches the page, as for Members.
4. **Who is listed:** every member the directory lists, except this account and pending proposals (`pendingConsent`),
   as `tenantBadges` does. *Why:* the page is about other people's keys; your own number and chain code are on your
   own profile.
5. **Per person:** avatar and name (a link to their profile), `@username`; one state - *Can't confirm this key* (the
   anomaly, with its note), *Verified*, or *Not verified*; *New* beside it while this account first pinned them under
   14 days ago (never beside an anomaly); *Verified by {names}.* (this account as "you"); *New identity, signed by
   their previous key, on {date}.* while that rotation is under 14 days old; and the replacement note when a join
   record replaced a pin that was not theirs. *Why:* exactly decision 15's display, read the way the profile reads it;
   the 14-day window for the rotation line is the *New* window - the page flags what is recent, the profile keeps the
   full record, and a rotation that dropped *Verified* still shows as *Not verified* for as long as nobody re-compared.
6. **Way into the compare:** a "Compare safety numbers" button to the person's profile on every row this account has
   not verified at their current head, the anomaly included (its note asks for an in-person compare, and the profile
   shows the digits for it, never an accept). *Why:* the compare lives on the profile (uc.spaces.enforce-verified-identities
   main flow 1-3); one way in, no second compare UI.
7. **Order:** anomalies first, then by display name. *Why:* the anomaly is the only state that asks for action.
8. **A member whose check failed for a request** (not for the key) gets no state, as in the members list - never a
   false anomaly. A tenant this device cannot open, or a pin store it refuses, says so instead of a list (the refusal
   with the reset link, as the members list).
9. **Cost.** The anchor check runs as `tenantBadges` runs it: one view, every member anchored in turn (the pin store's
   writes must not race). On top of it the overview reads the pin store **once** for every pin (not once per member),
   lists the tenant's verifications once, and reads a member's key chain for the rotation date **only** when their
   anchored head is not their chain's first (a pin at index 0 is a genesis: nothing rotated), a few at a time.
   Measured at 50 members in Phase 3; the anchor check's own cost is the members list's too, so what it shows there is
   an inbox row for both, not a second path here.

## Inventory

| What | Where |
|---|---|
| The overview's read (new) | `src/web/src/features/account/peopleOverview.ts` + `peopleOverview.test.ts` |
| The page (new) | `src/web/src/features/spaces/PeopleOverviewPanel.vue` + test |
| The rail entry | `src/web/src/views/SettingsHubView.vue` (+ its test) |
| Copy EN+DE | `src/web/src/i18n/index.ts` (`whoIsWho.*`; reuses `safety.*` and `trust.*`) |
| Use case + coverage | `uc.spaces.enforce-verified-identities` (`.md`, coverage `.yaml`) |
| Model | `docs/crosscutting/keys-grants-and-trust.md` § Verifications and what a member sees; ADR 0111 § Consequences bullet |
| Site (Use, EN+DE) | `site/src/content/docs/account/verify-a-person.mdx`, `site/src/content/docs/de/account/verify-a-person.mdx` |

Locators: `git grep -n "tenantBadges\|personInTenant" -- src/web/src` (the two reads this mirrors);
`git grep -ln "enforce-verified-identities" -- site docs` (the pages and docs that speak for the use case).

## Phases

- [x] 1 web, the read - `peopleOverview.ts` with tests first; bite tests: an anomaly with a valid verification row of
      its served head is shown as the anomaly with nobody in *verified by*; a verification at the subject's old head
      (they rotated) does not count; a rotation older than 14 days shows no line (control: younger does); this account
      and a pending proposal are not listed; a failed chain read is no anomaly; a genesis head reads no chain.
      · Done when: `npm run check` green in `src/web`.
- [x] 2 web, the page - panel, rail entry, copy. · Done when: `npm run check` green.
- [x] 3 measure - the overview at 50 members (requests and time), the load recorded. · Done when: the numbers are in
      this CR's retrospective and, if the anchor check dominates, an inbox row.
- [x] 4 browser - own fast-jar and database: three members, one verification, one rotation, the overview clicked in
      headless Chrome (German UI). · Done when: the overview shows the three states as decided.
- [x] 5 docs - use case step + criterion, coverage, model, ADR bullet, Use page EN+DE; delete row #562 and this CR.
      · Done when: every `scripts/validate_*.py` (but `validate_guard_gaps.py`) and the site check green.

## Definition of done

`npm run check` green in `src/web`; the coverage criterion has its refs and `validate_coverage.py` is green; the Use page
says where the overview is, EN+DE, and `npm run check` in `site/` is green; the page was clicked in a real browser.

## Retrospective

- **Phase 3's numbers** (load named per run): faked server, 50 members, load ~9: Who's who 154 requests warm (99 pin-store
  head reads, 54 key chains, 1 verification list), ~0.2 s CPU, 3.8 s at 20 ms per request; the members list
  (`tenantBadges`) on the same tenant 197 (147 head reads), 4.6 s at 20 ms. Headless Chrome on this worktree's fast-jar,
  49 others, load ~16: 206 requests and 1.75 s with 47 first contacts, 158 and 1.2-1.6 s after. The overview adds
  nothing over the members list (decision 9 saves a pin read per member); the cost is the anchor check's own - a pin
  head read per `anchorIdentity`/`anchorChainKey` and the inviting owner anchored again per joined member - in the two
  files the trust-risk CR edits: inbox #710.
- **Planning:** the row carried a performance note as a question ("measure, cache if needed") and no number. A counting
  fake server answered it in minutes and showed the cost was not where the row put it. *Next time:* a refine that cites
  a cost note counts requests per endpoint with the existing fakes before it decides the scope.
- **Execution:** the first full `npm run check` ran while the next phase's files were being written (the substrate rule
  in `docs/ai/README.md` step 3); and two test-only type errors (an untyped `vi.fn()` in `fakeApi`, `exists` on a
  `get()` wrapper) were found by `vue-tsc`, not by the focused `vitest` runs. *Next time:* `npm run typecheck` beside the
  focused test run, and no edits in the tree a check reads. The first bite of the anomaly rule stayed green because the
  rule held at a second layer; the comment now names the gate and the bite hits both layers.
- **Process:** under a shared load of 25-99 the full check was red twice with timeouts only; the re-run at a lower load
  is the one recorded. Nothing in the brief was missing.
