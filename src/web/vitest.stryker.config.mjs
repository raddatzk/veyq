// @ts-check
// The vitest config the web mutation gate runs (stryker.config.mjs `vitest.configFile`, ADR 0045): the app's own
// vite.config.ts, with the collection narrowed to the mutate scope's own unit tests (stryker.mutate.json `tests`).
// Every other setting — the setup file, the aliases, the environment — is the one `pnpm run test` uses.
//
// WHY the collection, not Stryker's `testFiles` or vitest's `related`, both measured 2026-11-12:
//   - `related` (the vitest runner's default) selects every one of the 583 test files: vitest 5 counts a test as
//     related once a SETUP file reaches the changed file, and src/test/setup.ts loads the whole application registry.
//     So the dry run was the whole suite single-threaded (12.5 min on CI) and each of the 18 static mutants re-ran it.
//   - Stryker's `testFiles` (10.1.1) turns that list into a test filter for every mutant, and a mutant run with a
//     filter is activated at RUNTIME (mutant-test-planner.js, `mutantActivation: testFilter ? 'runtime' : 'static'`):
//     a static mutant (a module-level constant, such as capabilityTags' magic ids) is then switched on after its
//     module was evaluated, so all 15 survived, killed by nothing because nothing could see them.
//   Narrowing the collection leaves no filter on a static mutant, so it is activated before the module loads.
import { readFileSync } from 'node:fs'
import { defineConfig, mergeConfig } from 'vitest/config'
import base from './vite.config.ts'

const manifest = JSON.parse(readFileSync(new URL('./stryker.mutate.json', import.meta.url), 'utf8'))

export default defineConfig((env) => mergeConfig(base(env), { test: { include: manifest.tests } }))
