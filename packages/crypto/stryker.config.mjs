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
  // break: the gate goes red below this. Carried over unchanged from web/stryker.config.mjs, where both files
  // were measured: v1.ts 100% (134 mutants, zero survivors, zero equivalents), accountBirth.ts 100%. The
  // 5-point margin is NOT slack for a known gap — there is none — it is deliberate headroom for a FUTURE
  // equivalent mutant: one semantically identical to the original, therefore unkillable by any assertion,
  // which at break=100 would red the gate with no fix available. 95 is "high" per ADR 0045 §4.
  // What that margin costs, stated plainly: the gate catches a COLLAPSE, not a single weakened assertion —
  // and it cannot, because the rest of v1.ts is redundantly pinned by feature-test round-trips (AEAD mutants
  // throw; value mutants fail decryption), so one decayed assertion does not move the score either way.
  // Bite-proven (in web/, before the move): `describe.skip`-ing the KAT conformance suite drops v1.ts to
  // 86.57% -> Stryker exits 1.
  thresholds: { high: 100, low: 95, break: 95 },
}
export default config
