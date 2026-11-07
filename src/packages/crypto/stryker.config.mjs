// @ts-check
// Stryker mutation testing — the SHARED crypto assertion-strength gate (ADR 0045), the half that moved here
// with the code when `@veyq/crypto` was extracted (cr-admin-web-extraction, ADR 0065). `src/web/` keeps its own
// config for the six files that stayed there; the two run as two jobs because Stryker's test runner is the
// project's own vitest and neither project collects the other's suites.
//
// WHAT is mutated lives in stryker.mutate.json (the canonical scope, shared with the CI meta-check so the two
// never drift) — NOT the argon*.worker wiring layer (ADR 0045 §3); see that file's
// _comment for the measured evidence.
//
// inPlace (not Stryker's default copy-to-sandbox): v1.conformance.test.ts reads the shared crypto vectors at
// ../../../../contracts/crypto/vectors/v1.json — OUTSIDE this package's root, so a copied sandbox can't see them
// and the KAT dry-run fails before a single mutant runs. inPlace mutates the real tree and restores it after;
// the files are git-tracked, so an interrupted run is recoverable with `git checkout`. And while it runs, the source
// on disk IS a mutant: src/web/ and src/admin-web/ import @veyq/crypto from that source, so their suites go red for nothing
// if they run alongside — run this gate on its own (measured in cr-item-marks-binary-ids).
import { readFileSync } from 'node:fs'

const manifest = JSON.parse(readFileSync(new URL('./stryker.mutate.json', import.meta.url), 'utf8'))

/** @type {import('@stryker-mutator/api/core').PartialStrykerOptions} */
const config = {
  mutate: manifest.mutate,
  testRunner: 'vitest',
  // Named, not discovered: Stryker's default `plugins` glob looks for `@stryker-mutator/*` beside its own core, and
  // pnpm's strict node_modules puts only core's own dependencies there - the runner, this package's dependency, is
  // not among them ("no TestRunner plugins were loaded", the first GitHub run of mutation.yml, 2026-11-05).
  plugins: ['@stryker-mutator/vitest-runner'],
  inPlace: true,
  coverageAnalysis: 'perTest',
  reporters: ['clear-text', 'html', 'json'],
  // Incremental report doubles as the CI cache (ADR 0045 §4) — a PR re-runs only changed mutants.
  // Its limit, measured: a mutant whose own line is unchanged keeps its cached verdict even when an edit to the
  // NEXT line changes that verdict (emergencyKit.ts's `if (!m)` stayed "Survived" after `m[1]` moved out of the
  // try). Judge a local fix with `--force`, not with the incremental rerun.
  incremental: true,
  // break: the gate goes red below this, and here it is 100 — no margin. Measured with `--force` (2026-10-19, a
  // statement's facts sealed once and their key wrapped per member, on top of the identity-pin store's id, set digest and
  // fingerprint digits in v1.ts): 1,020 mutants, 1,012 killed, 2 timed out (the fingerprint's loop counters turned into
  // endless loops), 0 survived, 0 without coverage, 6 ignored — 100.00 % on every file. When pad.ts joined (2026-10-24,
  // with the Expenses seal purposes): 1,147 mutants, 1,138 killed, the same 2 timed out, 0 survived, 7 ignored; its first
  // run had 2 survivors — the size classes' ascending check's loop bound (a read past the end compares `undefined`, so
  // the mutant was equivalent: the check now has no bound to get wrong) and that check's message, which no test read.
  // Stryker 10 (2026-11-07, with vitest 5) removes a call standing as a statement, which 9 never did: its first run
  // had 3 survivors, the three wipes of a decoded Secret Key (`key.fill(0)` in emergencyKit.ts twice, secretKey.ts's
  // typo branch) — not equivalent, a key left in memory is the defect, so the tests now spy the buffer and read zeros.
  // Then: 1,525 mutants, 1,520 killed, 5 timed out (none static: four loop counters, sharesPwToken's params as `{}`),
  // 0 survived, 6 ignored — at a load average above 60. The identity-pin run had 4
  // timeouts, two of them static report.ts mutants at a load average above 30 (read as unproven below); at the same
  // load this run killed both. The identity-pin store's first run had 3 survivors, all in a
  // byte compare that tolerated ids of two lengths: the ids are checked to be 32 bytes BEFORE the sort now, so the
  // compare runs over a fixed 32 and a test orders two ids that differ only in their last byte. report.ts's first run had
  // 9 survivors: the refusal's message and name (asserted through `toThrow(new ReportInvalid(…))`, which builds the same
  // mutated text), boxed-String roles and refs, a UUID with a character before or after it, and a last field running
  // past the end (an earlier field's cut was caught by the next length read instead). The moderation key's run had 1
  // survivor: the bundle compare's length check, equivalent while only the LONGER side was tested; a prefix
  // on the other side kills it. When blobMeta.ts joined (2026-10-09) the first run had 3 survivors, all one cause: the
  // writer's `modifiedAt` bound was asserted through the reader, which drops the same values — so blobMeta.test.ts
  // reads what the writer wrote. Each ignored mutant carries
  // its measured reason on its line: the two `typeof` operands in seal.ts (uint, scope kind) are equivalent,
  // primitives.ts's 'NFC' and report.ts's 'utf-8' are runner artefacts, and emergencyKit.ts's two are equivalent. So a new survivor is killed by a test, or — only once measured to be
  // equivalent — disabled at its line with that measurement; there is no third way. WHY not the 5-point margin ADR
  // 0045 §4 gives other scopes: an equivalent mutant is handled at its line here, so the margin bought no
  // headroom, only blindness — it was 23 mutants, and deleting identity.test.ts (then the only tests of the identity
  // codec and the hybrid halves) left 95.20 % and exit 0. Bite at 100: removing the disable on seal.ts's scopeKind
  // `typeof` lists exactly that mutant as Survived (99.78 %) and exits 1.
  // A Timeout counts as detected, and for a STATIC mutant (module-level code: every test runs against it) it is
  // load-dependent: on a busy machine the same unkilled module-level mutant survived in one run and timed out in
  // the next. Read a static mutant's Timeout as unproven until a test kills it.
  thresholds: { high: 100, low: 95, break: 100 },
}
export default config
