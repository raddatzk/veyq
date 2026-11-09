---
id: cr-search-e2e
title: Lane L1 drives the search and the gallery filter in a browser, the place chip included
status: done
satisfies: [uc.platform.search, uc.memory.gallery]
adrs: ["0090", "0091", "0125"]
invariants:
  - lane L1 runs on its own instance (`scripts/run_stack_e2e.py`, project `veyq-e2e-<stamp>`); the owner's `infra` stack and the `veyq-search` stack are never stopped, recreated or reused
  - a stage that needs a host resource held by the owner's stack (ports 80/443) is not forced; the run is reported as blocked, naming the holder
  - the wire assertion (canaries) still holds: no typed value reaches the server in the clear
---

## Goal

The search has unit and component tests, and several hand-driven browser passes on a throwaway stack. No CI lane drives
it. Add L1 specs (`tests/e2e/tests/features/platform/` for the search, `…/memories/` for the gallery filter, the
implementer places them) that run against the release candidate, as every other feature's specs do. Item 8 of the
owner's search follow-ups, 2026-11-09.

The specs cover:

- **Search:**
  - a Memory in another space found by name, by a year chip, by a month chip, by a person chip, and **by a place chip**;
  - a photo of another space found by a place and opened in its Memory's lightbox;
  - a person result opening the profile;
  - a settings result opening its section;
  - the sticky highlight (the owner's Ken/Kevin example);
  - no search field on a share page.
- **The gallery filter:** a day chip narrows the grid; the lightbox pages only those items; no field for a guest.
- **The place chip:** it was never driven in a browser, because setting a Memory's place asks for the maps opt-in.
  - The spec sets a place the way a member does.
  - If that needs the third-party map or geocoder, which a hermetic L1 run cannot reach (`tests/e2e/src/egress.ts`),
    use the path the existing Memories place specs use: `rg -ln "place" tests/e2e/tests/features/memories`.
  - If none exists, seal the place into the Memory's meta through the e2e's client-crypto helpers. A seeded place is
    not a UI-set one; say which it is.

## Context (substrate: branch `worktree-search-design`; this CR runs after `cr-search-photos-of-every-space`, `cr-gallery-filter-by-facts` and `cr-search-polish`)

- **Lane L1:** `scripts/run_stack_e2e.py` (its module docstring is the procedure: images, assets, Pebble, `veyqctl
  install`, the suite, the canary sweep), the overlay in `tests/e2e/stack/`, and the Playwright config
  `tests/e2e/playwright.config.ts`.
  - The suite reaches the header through `tests/e2e/src/header.ts`; accounts through `accounts.ts`; mail through
    `mail.ts`.
  - The canaries (`canaries.ts`) must cover the search queries typed. A query is a typed value, so the sweep must find
    none of them on the server.
- **Preflight:** stage 0 wants ports 80, 443 and the admin port free. The owner's dev `infra` stack runs a Traefik
  (`veyq-traefik`). Check whether it holds 80/443 (`docker port veyq-traefik`).
  - If it does, L1 cannot run on this machine while the owner's stack is up. Do not stop it. Write the specs, prove
    they compile and lint (`tests/e2e`'s own check), and report the run as blocked, naming the holder.
  - The owner then runs it, or allows a stop.
- **Machine:** L1 is heavy. Run it alone, with no other build or check on the machine. Check `uptime` first.
- **The UI words:** read them from `src/web/src/i18n/index.ts` and the applications' catalogs. The suite's locale is
  the one the other specs use.

## Phases

Single phase.

- **Build:** the specs and any helper they need (`tests/e2e/src/`), including the canary entries for the typed
  queries.
- **Run:** L1 once, or report it as blocked as above. If it runs, read the log to confirm the new specs executed, not
  only that the job passed (`validate_e2e_discovery.py` exists for that question).
- **Coverage:**
  - the `stack` refs on `uc.platform.search` and `uc.memory.gallery` criteria the specs prove;
  - the criterion tags `validate_stack_criterion_tags.py` reads.

  A stack ref counts only once a run has executed it. Follow `docs/coverage/README.md` on `stack` and on a surface
  awaiting its first run.
- **Done when:** `tests/e2e`'s check is green; L1 has run green, or is reported as blocked with the reason; the
  trust-chain guards are green (`/usr/bin/python3`, guard_gaps skipped).

## Retrospective

Implemented 2026-11-09 in the worktree `search-design`, on `1cb55ea2a` (the coordinator's "a result names its space"
landed while the specs were written; the images were built after it). Two spec files and one helper move:

- `tests/e2e/tests/features/platform/search.spec.ts` (seven tests, the `platform` leg);
- `tests/e2e/tests/features/memories/gallery-filter.spec.ts` (two tests, the `memories` leg);
- `writeZip` moved from `export-import.spec.ts` into `tests/e2e/src/zip.ts`, beside `readZip`, so the search spec can
  rewrite a download without a second copy.

`tests/e2e`'s `pnpm run check` green (measured: lint, typecheck, spec-urls, shards, `playwright test --list` = 181 tests in
40 files); every `scripts/validate_*.py` but `validate_guard_gaps.py` green with `/usr/bin/python3` (69 run, 0 red; no
validator changed), `validate_stack_criterion_tags.py` among them (181 tests, 305 tags, 63 use-cases).

### Where the specs drive the current search, not the CR's list (re-derived from the use-case and the retrospectives)

- **One list after *Filter by*.** The CR predates it. The first test asserts that *Filter by* is the list's only heading
  (with an *In:* suggestion present, so the heading is there to count) and that each row says where it lies; a row of
  another space names that space first (and, since `1cb55ea2a`, a row of this space does too - the photo row is asserted
  whole: `Personal · Lissabonreise … · May 3, 2022`).
- **A chip is a predicate over every document.** The person chip is asserted to list the Memory the owner took part in
  and **not their profile**, and the year chip to list no person at all - the CR's "found by a person chip" read as
  "Person: Kevin" lists Kevin; the current rule is the opposite for the person document.
- **People and settings are kinds of the one list.** The person result is asserted beside the person suggestion of the
  same word (the chip filters, the row opens). The settings section is found by one of its hidden words, not its
  label, because that is the rule that can go wrong (word-start only); "dark mode", not the criterion's "E-Mail", which
  the recorder refuses (six characters of the base64 alphabet) - and not "mail server" either, a canary generic enough
  to sit in a relay's log line.
- **Added, not in the CR:** the bold match (a `strong` holding exactly the typed word); *In:* chips, with the A3 case -
  two Memories of one name, one per space, are two suggestions each naming its space, and the personal one narrows to
  its own photo; the month chip ("May 2022" within the July 2021 - May 2022 range).
- **The share page's missing field** is asserted in the gallery-filter spec, whose guest opens a link anyway; one test
  carries both criteria (`uc.memory.gallery:gallery-narrows-by-facts`, `uc.platform.search:nothing-leaves-or-persists`).
  The member's page shows both fields first, so the guest's absences are not a renamed label.
- **The sticky highlight** uses "Kältekammer Nord" / "Kältekammer Süd", not Ken/Kevin. "Ke" cannot be a canary (the
  recorder refuses it), and the store sweep matches RAW BYTES (`e2e_sweeps.sweep_store`): "Kä" is three UTF-8 bytes, which
  megabytes of ciphertext hold by chance - the collision rule's exemption for a non-alphabet character assumes text, not
  binary objects (see the process observation below). The query starts at "Kältek" (seven bytes) and the test moves the
  highlight with ↓ until it sits on "Süd", whatever the tenant holds besides, so it does not depend on the other specs'
  names. Its control is in the test: after typing on, the list's first entry is NOT the highlighted one, which is what
  the layer alone would do (Reka's behaviour, measured in cr-search-sticky-highlight).

### The place chip: which path, and what it is

- **A Memory's place is set in the UI, the way a member does on a server without a map.** The CR offered two paths
  (an existing Memories place spec, none exists; or seal the place through test helpers). Neither was needed: the
  tenant owner switches **Maps** off for the space (Settings → Capabilities, the confirmation's `whenOff` read word for
  word from the catalog), and the place editor is then the name field alone (uc.memory.place A4) - no map, no consent,
  no third-party request. The spec names the place, saves, and switches Maps back on in a `finally` (and `afterAll`
  switches it on if a test died between), so no later spec of the leg runs with Maps off. A UI-set place, not a seeded
  one.
- **A photo's place is one the member's import sealed.** The CR assumed a photo "found by a place"; a photo's place is
  only ever reverse-geocoded in the lightbox, behind the map consent and a geocoder the hermetic run cannot reach
  (`src/egress.ts`). The import restores a download's place names (`exportImport.ts` `restoredFacts`), so the spec
  downloads "Lissabonreise" through the row's Download, writes `location.place` into the manifest's medium in Node,
  and the owner imports it into the personal space through New → Upload files. The place is sealed by the product's own
  import, from a value the test wrote - not typed into an editor, and not sealed by test helpers. It is recorded as a
  canary like every typed value.

### Technical choices

- **Image tags.** Built as `veyq:e2e-search` / `veyq-admin:e2e-search` with plain `docker build` (what `backend:image`
  and `admin:image` run), not `:local`: another session's lane run reads `:local`, and this one must not move it.
- **The run's selection.** `-- --project platform --project memories search.spec.ts gallery-filter.spec.ts`: one
  instance for both files. Playwright runs a dependency project whole whatever the file filter (read in
  `playwright@1.64.1/lib/runner/index.js`: `collectProjectsAndTestFiles` loads every file of a `dependency` project;
  `createRootSuite` builds the dependency suites from the unfiltered project suites), so `setup`, `scenarios` and
  `instance` ran as in a leg.
- **One bite, on the test most able to pass vacuously.** The sticky highlight was bitten with the product defect: the
  tags input's `remember()` made a no-op (snapshot of `VeyqTagsInput.vue`, the injection's marker asserted in the file
  and its bytes differing from the snapshot, `docker build` as `veyq:e2e-search-bite`, the file restored and compared
  byte-equal to the snapshot right after the build), then `run_stack_e2e.py … -- search.spec.ts --project=platform`:
  RED, exactly that test, at `search.spec.ts:467` - the highlight on "Kältekammer Süd" after typing on - with the other
  six search tests green; the bite image was removed afterwards. The other assertions a defect could pass silently carry
  their positive control in the test instead (both fields present for the member before their absence for the guest;
  the twin Memory listed by the text before each chip leaves it out).

### The run

One instance (`veyq-e2e-20261109-153546`), the images `veyq:e2e-search` / `veyq-admin:e2e-search` built at `1cb55ea2a`:
`/usr/bin/python3 scripts/run_stack_e2e.py --app veyq:e2e-search --admin veyq-admin:e2e-search -- search.spec.ts
gallery-filter.spec.ts --project=platform --project=memories` - **GREEN** (116.7 s; preflight found 80, 443 and 18081
free - the owner's Traefik publishes only `127.0.0.1:18080`, `docker port veyq-traefik`). The log names each new test
`✓` (`gallery-filter.spec.ts` 2 tests, 3.8 s; `search.spec.ts` 7 tests, 12.5 s; with `setup` 6, `scenarios` 5,
`instance` 8 before them), the sweep read 39 typed canaries including `canaries-search.spec.json` and
`canaries-gallery-filter.spec.json` and found none, the teardown ran `down -v` by the project name, and the host check
reported "6 pre-existing containers and 20 volumes unchanged". The first attempt was red in 1.3 s before any test: the
file filters stood after `--project`, which Playwright reads as more project names (it is variadic) - the filters go
first, or `--project=<name>`.

### Coverage

`stack` is still outside `ADMITTED` (`validate_coverage.py`: no CI run has executed the lane), so no `stack` ref was
added. `uc.platform.search` gained a use-case `gaps.stack` naming `search.spec.ts` and `gallery-filter.spec.ts` and the
criteria their tests are tagged with (the guard `validate_stack_criterion_tags.py` requires it once a tag names the
use-case); `uc.memory.gallery`'s existing `gaps.stack` names `gallery-filter.spec.ts` and `gallery-narrows-by-facts`.
On the first CI run whose log shows the suites executed, each file becomes a ref on its tagged criteria.

### Owner questions

None blocking. One the spec surfaced and does not decide: on a server that offers a map, a member who declines the map
consent cannot name a Memory's place by hand at all - "Set location" opens the consent dialog, whose only choices are
Cancel and Enable maps (`useMemoryLocation.openLocationEditor`). The name-only editor exists, but only where the map is
not offered (A4). Whether a declining member should get it too is the owner's call; the specs keep today's behaviour.

### Process observations

- **The canary collision rule is written for text, and the store sweep reads bytes.** `canaryRule.ts` exempts any value
  with a character outside the base64 alphabet "at every length", but `sweep_store` matches a canary's UTF-8 bytes
  inside each object's raw bytes, which are binary ciphertext, not base64. A three-byte canary ("Kä") would be found
  there by chance in a run that stores tens of megabytes; nothing refuses it today. A floor by UTF-8 byte length for
  the bytes sweep (or for every canary) belongs in `canaryRule.ts` - an inbox item, not done here.
- **A coordinator's change mid-implementation** (the second line naming the space) was cheap to absorb because the
  message named what to expect in the row; the specs assert the photo row whole, so a later change to that line turns
  them red where it should.
