// @ts-check
// Stryker mutation testing — the SHARED crypto assertion-strength gate (ADR 0045), the half that moved here
// with the code when `@veyq/crypto` was extracted (cr-admin-web-extraction, ADR 0065). `web/` keeps its own
// config for the six files that stayed there; the two run as two jobs because Stryker's test runner is the
// project's own vitest and neither project collects the other's suites.
//
// WHAT is mutated lives in stryker.mutate.json (the canonical scope, shared with the CI meta-check so the two
// never drift) — NOT the argon*.worker wiring layer (ADR 0045 §3); see that file's
// _comment for the measured evidence.
//
// inPlace (not Stryker's default copy-to-sandbox): v1.conformance.test.ts reads the shared crypto vectors at
// ../../../contracts/crypto/vectors/v1.json — OUTSIDE this package's root, so a copied sandbox can't see them
// and the KAT dry-run fails before a single mutant runs. inPlace mutates the real tree and restores it after;
// the files are git-tracked, so an interrupted run is recoverable with `git checkout`.
import { readFileSync } from 'node:fs'

const manifest = JSON.parse(readFileSync(new URL('./stryker.mutate.json', import.meta.url), 'utf8'))

/** @type {import('@stryker-mutator/api/core').PartialStrykerOptions} */
const config = {
  mutate: manifest.mutate,
  testRunner: 'vitest',
  inPlace: true,
  coverageAnalysis: 'perTest',
  reporters: ['clear-text', 'html', 'json'],
  // Incremental report doubles as the CI cache (ADR 0045 §4) — a PR re-runs only changed mutants.
  // Its limit, measured: a mutant whose own line is unchanged keeps its cached verdict even when an edit to the
  // NEXT line changes that verdict (emergencyKit.ts's `if (!m)` stayed "Survived" after `m[1]` moved out of the
  // try). Judge a local fix with `--force`, not with the incremental rerun.
  incremental: true,
  // break: the gate goes red below this. Measured with `--force` (cr-crypto-mutation-gate-green, 2026-10-01): 464
  // mutants, 456 killed, 2 timed out (the fingerprint's loop counters turned into endless loops), 0 survived, 0
  // without coverage, 6 ignored — 100.00 % on every file. Each ignored mutant carries its measured reason on its
  // line: the three `typeof` operands in seal.ts are equivalent, primitives.ts's 'NFC' is a runner artefact, and
  // emergencyKit.ts:41's two are equivalent. A new survivor gets a kill test; a sixth disable needs its own measurement.
  // The 5-point margin is headroom for a FUTURE equivalent mutant, which at break=100 would red the gate with no
  // fix available. 95 is "high" per ADR 0045 §4. What that margin costs, measured: it is 23 mutants, so the gate
  // catches a collapse and not a lost test file. Deleting identity.test.ts, the only tests of the identity codec
  // and the hybrid halves, left 95.20 % and exit 0; `describe.skip` on the KAT conformance suite gives 49.34 % and
  // exit 1. So a new survivor is killed or disabled with its reason, never banked against the margin. Removing the
  // disable on seal.ts's scopeKind `typeof` lists exactly that mutant as Survived (99.78 %).
  // A Timeout counts as detected, and for a STATIC mutant (module-level code: every test runs against it) it is
  // load-dependent: on a busy machine the same unkilled module-level mutant survived in one run and timed out in
  // the next. Read a static mutant's Timeout as unproven until a test kills it.
  thresholds: { high: 100, low: 95, break: 95 },
}
export default config
