---
id: cr-vue-i18n-v11
title: vue-i18n 10 → 11 in web and admin-web, and every message is proven to compile
status: planned
satisfies: []
adrs: ["0065"]
invariants: [self-hostable by default]
---

## Goal

`vue-i18n` 10.0.8 is deprecated upstream ("v9 and v10 no longer supported"), and `CLAUDE.md`'s dependency rule
makes replacing it in-scope now. Both `web/` and `admin-web/` move to v11 together (inbox #167). The measurement
at refine says this is a pure version bump: none of v11's three changes touches our code. So the real work is
the proof. Today nothing checks that our catalogs compile at all, and they don't: `invitations.addedBy` renders
the literal `Invited by @{user}` in both languages, because `@` starts a linked message. This CR adds a test
that compiles every message of every catalog in both packages. The test is the gate for the bump, and it is
red on today's tree.

## Context & constraints

- **The dependency rule:** root [`CLAUDE.md`](../CLAUDE.md) § How we work, *No deprecated / unmaintained
  dependencies*.
- **Two catalogs, no shared i18n package** — deliberate, [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md)
  (the header of `admin-web/src/i18n/index.ts` says so). The two packages move together because they pin the
  same dependency, not because they share code.
- **The target is v11, not v12.** Measured 2026-09-23 with `npm view vue-i18n dist-tags`: `latest` is 11.4.12
  and `next` is 12.0.0-alpha.4. v11 is maintained, and its earlier deprecation flag was withdrawn upstream as a
  mistake. Re-run that command before you start: if v12 has gone stable in the meantime, stop and ask. The only
  v12 removal we would hit is the `legacy: false` option in every `createI18n` call.
- **v11's changes, each with the command that shows it misses us** (settled at refine; spot-check the first
  one, because the argument that this is only a bump rests on it):
  - The Legacy API is deprecated, not removed. Every `createI18n` passes `legacy: false`:
    `grep -rn "createI18n(" web/src admin-web/src | grep -v import | wc -l` must equal
    `grep -rn -A4 "createI18n(" web/src admin-web/src | grep -c "legacy: false"`.
  - `tc` / `$tc` are removed: `grep -rnE '\$tc\(|[^a-zA-Z.]tc\(' web/src admin-web/src` must find nothing.
  - `v-t` is deprecated: `grep -rn 'v-t[=.]' web/src admin-web/src` must find nothing.
- **Node:** vue-i18n 11.4.x declares `engines.node >=22`. CI (`grep -n node-version .github/workflows/*.yml`)
  and both images (`grep -n "FROM node" Dockerfile admin-web/Dockerfile`) are on 22.
- **Out of scope:**
  - `web/scripts/extract-admin-i18n.mts` has no referrer (`grep -rn extract-admin-i18n --exclude-dir=node_modules .`).
    It is dead code, so it belongs to inbox #170.
  - The 32 web tests that hand-write stub catalogs are inbox #171. They pass `legacy: false` like the app does,
    so v11 does not touch them.

## Phases

Guard first, because the defect is live: the test must go red on today's tree before anything is fixed.

- [ ] 1 web + admin-web: **the compile test.** Add a test to each package's existing i18n test file
      (`web/src/i18n/setLocale.test.ts`, `admin-web/src/i18n/i18n.test.ts`). It walks every message of every
      locale, compiles it with vue-i18n's own message compiler, and fails on any compile error, naming the key.
      Two constraints:
      - It must not import a package the test's own `package.json` does not declare. If it needs
        `@intlify/message-compiler`, declare it as a devDependency in the same version line as `vue-i18n`.
      - A spy on `console.warn` does not count as the mechanism. It passes the day vue-i18n changes how it
        reports.

      **Bite, web: run it before touching the catalog.** It must be red on `invitations.addedBy` and
      `mail.fromPlaceholder`, EN and DE each. That is the live defect the test exists for, found at refine with
      a probe against the installed 10.0.8. If the test comes up with a different set of keys, the difference
      is the finding: explain it before going on.

      **Bite, admin-web: by injection, because the package has no live defect.** Its copy of `fromPlaceholder`
      went with the `mail` group that #165 deleted. So its test is green on arrival, and green alone cannot
      tell *the test held* from *the test walked nothing*. Snapshot the catalog, put a literal `@` into one
      message, and the test must go red naming that key. Then restore the file, never with `git checkout`
      ([changes/README](README.md) § *Bite-proof a guard by snapshot-and-restore*).
      · Done when: web's test is red on exactly those keys, and admin-web's goes red on the injected key and
      green again after the restore.
- [ ] 2 web: **fix the messages.** Use vue-i18n's literal interpolation for a literal `@`
      (`{'@'}`). `addedBy` must then render the inviter's name. Add an assertion on the rendered string next to
      the view that shows it (`grep -rn "addedBy" web/src --include='*.vue'`), because a compile test proves the
      message parses, not that it says what we meant. `fromPlaceholder` already renders right despite the error
      and gets the same fix so that the compile test can stay strict.
      · Done when: `npm run check` is green in `web/`.
- [ ] 3 web + admin-web: **the bump.** Both `package.json` files go to `"vue-i18n": "^11.4.12"` (or the latest
      11.x on the day), then run `npm install` from the repo root. The lockfile must move every `@intlify/*`
      package with it (`grep -n '"node_modules/\(vue-i18n\|@intlify/[a-z-]*\)"' package-lock.json` before and
      after). No code change is expected. If `npm run check` finds one, it is a finding about the refine
      measurement: record it in the retrospective, don't just patch around it.
      · Done when: `npm run check` is green in both packages, and the phase-1 compile tests stay green on the
      new compiler.

## Definition of done

- Both packages depend on vue-i18n 11, `package-lock.json` carries no `deprecated` entry for `vue-i18n` or
  `@intlify/*` (`grep -n -A8 '"node_modules/vue-i18n"' package-lock.json`), and `npm run check` is green in
  `web/` and `admin-web/`.
- The compile test is the permanent guard: it runs in each package's `npm run check`, and phase 1 proved it
  bites.
- No coverage change: no use-case's behaviour moves. The `addedBy` fix is a bug fix with its own assertion.
- The trust-chain guards are green (`for f in scripts/validate_*.py; do python3 "$f"; done`).

## Retrospective

