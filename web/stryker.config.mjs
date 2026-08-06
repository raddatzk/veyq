// @ts-check
// Stryker mutation testing — the web crypto assertion-strength gate (ADR 0045).
//
// Mutates the pure crypto unit layer and measures whether the tests' assertions actually PIN the output
// — the trust chain's last, otherwise-unmeasured link (validate_coverage.py proves a ref resolves; it
// cannot prove `seal(k,s)=s` would fail the test). Not folded into `npm run check`: this is a heavier
// PR-only job, run via `npm run mutation`.
//
// WHAT is mutated lives in stryker.mutate.json (the canonical scope, shared with the CI meta-check so the
// two never drift). It is v1.ts only — the KAT-pinned primitive tier — NOT the argon*.worker wiring layer
// (ADR 0045 §3); see that file's _comment for the measured evidence.
//
// inPlace (not Stryker's default copy-to-sandbox): v1.conformance.test.ts reads the shared crypto vectors
// at ../../../../contracts/crypto/vectors/v1.json — OUTSIDE the web/ sandbox root, so a copied sandbox
// can't see them and the KAT dry-run fails before a single mutant runs. inPlace mutates the real tree and
// restores it after; the files are git-tracked, so an interrupted run is recoverable with `git checkout`.
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
  incremental: true,
  // break: the gate goes red below this. v1.ts measures 100% (134 mutants, zero survivors, zero
  // equivalents). The 5-point margin is NOT slack for a known gap — there is none — it is deliberate headroom
  // for a FUTURE equivalent mutant: one semantically identical to the original, therefore unkillable by any
  // assertion, which at break=100 would red the gate with no fix available. 95 is "high" per ADR 0045 §4.
  // What that margin costs, stated plainly: the gate catches a COLLAPSE, not a single weakened assertion —
  // and it cannot, because the rest of v1.ts is redundantly pinned by feature-test round-trips (AEAD mutants
  // throw; value mutants fail decryption), so one decayed assertion does not move the score either way.
  // Bite-proven: `describe.skip`-ing the KAT conformance suite drops v1.ts to 86.57% -> Stryker exits 1. The
  // battery uniquely detects ~18 mutants — in the functions no feature round-trip exercises end-to-end
  // (journalSeal/Open, dedupToken, recoveryAuthTag, kekRecovery, deriveAuthSalt, slugToken) plus the three
  // decodeKdfParams length-guard mutants, whose only test lives in this same suite.
  // `high`/`low` only colour the report.
  thresholds: { high: 100, low: 95, break: 95 },
}
export default config
