// @ts-check
// Stryker mutation testing — the web crypto assertion-strength gate (ADR 0045).
//
// Mutates the pure crypto unit layer and measures whether the tests' assertions actually PIN the output
// — the trust chain's last, otherwise-unmeasured link (validate_coverage.py proves a ref resolves; it
// cannot prove `seal(k,s)=s` would fail the test). Not folded into `npm run check`: this is a heavier
// PR-only job, run via `npm run mutation`.
//
// WHAT is mutated lives in stryker.mutate.json (the canonical scope, shared with the CI meta-check so the
// two never drift): the pure crypto-logic files that stayed in web/. v1.ts and the rest of the primitive
// tier moved to @veyq/crypto and are gated by packages/crypto/stryker.config.mjs. That file's _comment
// carries the measured evidence for what is in and what is out (ADR 0045 §3).
//
// inPlace (not Stryker's default copy-to-sandbox): web suites (identityPins.test.ts,
// argonParams.conformance.test.ts) read the shared crypto vectors at
// ../../../../../contracts/crypto/vectors/v1.json — OUTSIDE the web/ sandbox root, so a copied sandbox can't
// see them and the dry-run fails before a single mutant runs. inPlace mutates the real tree and
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
  // break: the gate goes red below this. No score is restated here: what each file measured lives in
  // stryker.mutate.json's _comment, and a number copied into this comment drifts from it. The 5-point margin
  // is deliberate headroom for a FUTURE equivalent mutant (one semantically identical to the original, so
  // unkillable by any assertion), which at break=100 would red the gate with no fix available; 95 is "high"
  // per ADR 0045 §4. What it costs: the gate catches a COLLAPSE, not a single weakened assertion. Where every
  // equivalent is instead disabled at its own line, the margin buys only blindness, which is why
  // packages/crypto runs at break=100 (ADR 0045 §4).
  // `high`/`low` only colour the report.
  thresholds: { high: 100, low: 95, break: 95 },
}
export default config
