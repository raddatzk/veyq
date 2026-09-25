---
id: cr-emergency-kit-codec-in-crypto
title: One Emergency Kit codec in @veyq/crypto, pinned by a vector — and a writer test in each package
status: done
satisfies: [uc.account.unlock]
adrs: ["0065", "0045", "0054"]
invariants: [operator-blind E2EE, self-hostable by default]
---

## Goal

The Emergency Kit is a format with two writers and one reader. `admin-web` writes the Server Owner's kit at
setup, `web` writes a member's kit on joining a tenant, and only `web` reads one, at unlock. The codec that
turns a kit into the `veyqkit:` token is copied byte-for-byte into both packages. Each copy has its own
base64url built on `escape`/`unescape`, and each test suite round-trips only its own copy. So changing one copy
keeps every test green and silently breaks the kits the other package writes (inbox #169).

After this CR:
- the codec exists once, in `@veyq/crypto`, next to `accountBirth` whose Secret Key it carries;
- a fixed-literal vector pins it, and the vector is computed from today's code before anything moves;
- its parser rejects a malformed kit instead of accepting any truthy field;
- each package tests that the PDF it writes is one the shared reader finds a kit in;
- the kit PDF's text is localized.

The PDF writer stays per package.

## Context & constraints

- **The format is written down:** [en.account.key-model § The Emergency Kit](../docs/features/account/enablers/en.account.key-model.md#the-emergency-kit)
  (refined 2026-09-23: the token, its three carriers, and why the document-information embedding is part of
  the format). This CR builds exactly that. The *"recovery code"* the setup docs used to name was never in
  the kit and has been removed from them.
- **What may be shared:** [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md) and the
  `package.json` `"//"` of `packages/crypto` allow only the operator-blind crypto suite and the cold account
  birth. The kit codec joins them because it serializes that birth's Secret Key and two copies of it are the
  defect. That makes it a charter change, so phase 4 edits the charter text. **The PDF writer does not move**:
  `@veyq/crypto`'s only dependencies are `@noble/*`, and jspdf/qrcode must not become the shared package's
  dependencies. `EmergencyKitSave.vue` is byte-identical in both packages too, and it stays duplicated (UI,
  ADR 0065).
- **Measured at refine, so no phase re-derives it:**
  - **The codec** is byte-identical in the two copies (web `emergencyKit.ts` lines 8–41 against admin 20–53).
  - **`downloadKitPdf`** is byte-identical too (web 83–127 against admin 56–100).
  - **`admin-web` still exports `parseKit`,** and only its own test calls it. Its header says *"WRITE half
    only"*.
  - **Swapping the base64url for `TextEncoder`/`TextDecoder` is safe on one condition.** Encoding gives the
    same bytes for every input `kitPayload` can produce. The decoder only matches today's behaviour when
    built with `{ fatal: true, ignoreBOM: true }`: the default decoder accepts invalid UTF-8 and strips a
    leading BOM, both of which today's code rejects.
  - **jsPDF keeps the token readable.** Neither `compress: true` nor a title with umlauts touches the
    `/Keywords` literal (probed with jspdf from `node_modules`). Localizing the PDF text therefore does not
    endanger the embedding. Phase 2's test proves that per package rather than trusting this probe.
- **Every site that touches the codec** (source and tests, both packages):
  `grep -rn "kitPayload\|parseKit\|downloadKitPdf\|importKitFromFile\|veyqkit" web/src admin-web/src`.
  **Two of them are tests that mock the module path the codec is leaving:**
  `grep -rn "vi.mock(.*emergencyKit" web/src admin-web/src`. They include `RecoverView.test.ts`, which mocks
  `parseKit` on the web feature module.
- **No re-export.** Callers import the codec from `@veyq/crypto/emergencyKit`. The web feature module keeps
  only its DOM half: `importKitFromFile`, the QR image decode, and the PDF writer. A re-export would give one
  thing two names.
- **Neighbours:**
  - **Inbox #170** lists admin-web's `parseKit` as dead code. This CR deletes it, so #170 drops that item.
  - **Inbox #147** is `shared/ui/SecretKeyField` importing a feature's decoder. The `parseKit` half of that
    import becomes a `@veyq/crypto` import here; the `importKitFromFile` half stays #147's.
  - **Inbox #167 (`cr-vue-i18n-v11`) goes first:** its compile test then also covers the new `kit.*` strings.
- **Out of scope:** a kit re-download for an existing account (web has none today). Also PDF text for
  non-Latin scripts: jsPDF's built-in Helvetica covers WinAnsi only, which is enough for EN and DE.

## Phases

- [x] 1 packages/crypto: **the vector first, then the codec.**
      - **Compute the vector from today's code before you move anything.** Run web's current `kitPayload` on a
        fixed kit: an email with a non-ASCII character (the UTF-8 path is the one place a rewrite can differ)
        and a real-shaped Secret Key (32 fixed bytes, padded standard base64). Write the resulting token into
        the new test as a **string literal**. This literal is the proof that the move is byte-identical. If
        you compute it from the new code, it proves nothing.
      - Then add `src/emergencyKit.ts`, exported as `./emergencyKit`. It holds `kitPayload`, `parseKit` and
        the `KitData` type, and builds on the package's own `b64url`/`fromB64url` plus `TextEncoder`/
        `TextDecoder({ fatal: true, ignoreBOM: true })`.
      - `parseKit` keeps today's contract: find the first token in any text, return `null` for anything it
        cannot read. It also rejects an `email` that is not a string and an `sk` that does not decode to
        exactly 32 bytes.
      - Negative cases, each on its own: `v !== 1`; a non-string email; an `sk` of 31 and of 33 bytes; an `sk`
        that is not base64; invalid UTF-8; a leading BOM; no token.
      - Add the file to `stryker.mutate.json`. Its value is the validation branches, which a round-trip test
        cannot grade, and that is the argument that put `accountBirth.ts` in. Update the manifest's `_comment`
        with that reason.
      - While you are in the package: `src/v1.ts` lines 1 and 319 still name `docs/api/crypto-vectors/v1.json`.
        The file is `contracts/crypto/vectors/v1.json`.

      **Bite:** flip one character of the literal and the vector test must go red. Weaken each new check on
      its own and its negative case must go red.
      · Done when: `cd packages/crypto && npm run check` is green, and `npm run mutation` runs with the new file
      in scope and no surviving mutant in it. Stryker mutates **in place**, so never run it while another
      session's mutation run is live.
- [x] 2 web: **the reader moves, the writer gets a test.**
      - Delete the local codec and base64url from `features/account/emergencyKit/emergencyKit.ts`. Import from
        `@veyq/crypto/emergencyKit` at every site the locator above finds, and fix the `vi.mock` paths it
        finds. A mock left on the old path mocks nothing, and a test that passed by mocking may then pass for
        the wrong reason: check each one.
      - Split `downloadKitPdf` into a render step that returns the PDF bytes and a save step. The render step
        takes its text from the caller: `EmergencyKitSave.vue` already holds `t`, and the strings go into the
        existing `kit.*` group of both catalogs. The file name may stay unlocalized.
      - **New test:** render a kit in each locale, then feed the bytes through the real reader,
        `importKitFromFile(new File([bytes], 'kit.pdf'))`. It must return the kit.
      - **Bite:** drop `keywords` from the render step's `setProperties` and this test must go red.
      · Done when: `npm run check` is green in `web/`.
- [x] 3 admin-web: **the writer only.**
      - Delete the local codec, `parseKit`, and the tests that exercised only them.
      - Take `kitPayload` from `@veyq/crypto/emergencyKit`.
      - Split the PDF writer and localize its text, as in phase 2.
      - **New test:** render in each locale, decode the bytes as text the way `File.text()` does, and
        `parseKit` from `@veyq/crypto` must find the kit. That is the test that would have gone red on the day
        the two copies diverged.
      - **Bite:** drop `keywords` and the test must go red.
      · Done when: `npm run check` is green in `admin-web/` (it builds the CLI bundle too).
- [x] 4 docs + coverage:
      - Widen the charter sentence for `packages/crypto` to include the kit codec, in each of its three places:
        root `CLAUDE.md` § Repository shape; `web/CLAUDE.md` § Conventions, including the import list; and
        the `"//"` in `packages/crypto/package.json`.
      - [ADR 0065](../docs/decisions/0065-admin-ui-cli-separate-service.md) still says `shared/crypto/v1`
        (`grep -n "shared/crypto" docs/decisions/0065-admin-ui-cli-separate-service.md`). Edit those lines to
        name the package.
      - In `docs/coverage/features/account/use-cases/uc.account.unlock.yaml`, criterion
        `secret-key-import-from-kit`: re-point its refs to where the tests now live, and cite the phase-2 test.
        The `packages/crypto` and `admin-web` tests are **not** coverage surfaces (the set is `backend` +
        `web`, [docs/ai/README](../docs/ai/README.md) § What counts as verified). They are this CR's guards,
        not coverage refs.
      · Done when: `python3 scripts/validate_coverage.py` and the whole trust-chain are green.

## Definition of done

- One codec:
  - `grep -rn "'veyqkit:'" web/src admin-web/src packages/crypto/src | grep -v '\.test\.'` finds the prefix
    defined only in `packages/crypto/src/emergencyKit.ts`. Other occurrences of `veyqkit:` are comments that
    name the marker, so the locator matches the quoted literal.
  - `grep -rn "escape(\|unescape(" web/src admin-web/src` finds nothing.
- **The permanent guards:**
  - the literal vector in `@veyq/crypto`;
  - the mutation scope entry;
  - one writer-to-reader test per package.

  Together they close the drift #169 describes. No Python guard is needed: with one copy of the codec,
  nothing is left to diverge, and each writer's embedding is pinned by its own package's test.
- `npm run check` is green in `packages/crypto`, `web` and `admin-web`; the crypto mutation run is clean;
  `validate_coverage.py` and the trust-chain are green.

## Retrospective

- **Planning — the mutation DoD assumed a green baseline it never measured.** "No surviving mutant in it" was
  reachable (emergencyKit.ts: 33/33 killed), but the run exits 1 anyway: `v1.ts` scores 77.99 % on untouched code,
  so `npm run mutation` cannot be green in this package today (inbox #184). Root cause: the refine cited the
  manifest's "100 %" instead of running the gate once. **Next time:** a CR whose done-when is a gate runs that gate
  on `main` during refine and records its exit code, so the implement session is not the one discovering it.
- **Planning — one line-level instruction was already stale.** Phase 1's "`v1.ts` lines 1 and 319 still name
  `docs/api/…`" had been fixed by an earlier wave; the grep settled it in seconds, which is the argument for
  handing over the locator rather than the line.
- **Execution — two of my own survivors were equivalent by construction,** and Stryker's incremental rerun kept
  reporting one after the fix (its cached verdict ignores an edit to the neighbouring line). Harvested as a comment
  at `incremental: true` in `packages/crypto/stryker.config.mjs`; the one genuinely equivalent mutant (`typeof sk`)
  carries a scoped `Stryker disable` with the reason. Also: the stricter parser rejected two web test fixtures whose
  "Secret Key" was 14 bytes — correct, and a reminder that a validation CR's blast radius includes every fixture
  that fed the old lenient path.
- **Process — the brief and `changes/README.md` disagreed on who runs the whole guard glob in a wave.** The README
  says the coordinating session runs it once on the merged tree; the brief asked this session to run it, twice. I
  followed the brief. **Next time:** the wave brief says which of the two it means, so neither is ignored silently.
