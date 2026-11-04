// The docs site's screenshot capture (ADR 0087 §1), started by scripts/site_capture.py on the host — not lane L1:
// nothing here asserts a use case, and L1's config (../playwright.config.ts) never sees this folder. One test per
// language, each a world of its own (world.ts) that takes its shots in order; a shot that fails is reported and the
// next one still runs (site.shots.ts). The browser is the installed Google Chrome, launched by the spec itself.
import { defineConfig } from '@playwright/test'
import { stack } from '../src/stack'

const booted = process.env.VEYQ_STACK_FILE !== undefined
const outDir = booted ? stack().outDir : 'test-results'

export default defineConfig({
  testDir: '.',
  testMatch: /\.shots\.ts$/,
  outputDir: `${outDir}/shots-playwright`,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  // A whole language: two accounts born in the browser (Argon2id), some forty shots, uploads through the store.
  timeout: 60 * 60_000,
  reporter: [['list']],
  use: { actionTimeout: 20_000, trace: 'off' },
})
