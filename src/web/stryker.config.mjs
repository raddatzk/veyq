// @ts-check
// Stryker mutation testing — the web crypto assertion-strength gate (ADR 0045).
//
// Mutates the pure crypto unit layer and measures whether the tests' assertions actually PIN the output
// — the trust chain's last, otherwise-unmeasured link (validate_coverage.py proves a ref resolves; it
// cannot prove `seal(k,s)=s` would fail the test). Not folded into `pnpm run check`: this is a heavier
// PR-only job, run via `pnpm run mutation`.
//
// WHAT is mutated lives in stryker.mutate.json (the canonical scope, shared with the CI meta-check so the
// two never drift): the pure crypto-logic files that stayed in src/web/. v1.ts and the rest of the primitive
// tier moved to @veyq/crypto and are gated by src/packages/crypto/stryker.config.mjs. That file's _comment
// carries the measured evidence for what is in and what is out (ADR 0045 §3).
//
// inPlace (not Stryker's default copy-to-sandbox): a suite the gate runs (naming.conformance.test.ts) reads the
// shared crypto vectors at
// ../../../../../contracts/crypto/vectors/v1.json — OUTSIDE the src/web/ sandbox root, so a copied sandbox can't
// see them and the dry-run fails before a single mutant runs. inPlace mutates the real tree and
// restores it after; the files are git-tracked, so an interrupted run is recoverable with `git checkout`.
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
  // Only the scope's own unit tests run (stryker.mutate.json `tests`; vitest.stryker.config.mjs says why the
  // collection is narrowed rather than Stryker's `testFiles` or vitest's `related`). Until 2026-11-12 the whole suite
  // ran: the dry run alone took 12.5 min on CI and each of the 18 static mutants ran all 583 files again, so a run
  // without the incremental cache passed the job's 45 min. Measured locally, cold: the whole suite 18 min 39 s,
  // the scope's own tests 23 s, with the same status for each of the 157 mutants.
  vitest: { configFile: 'vitest.stryker.config.mjs' },
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
