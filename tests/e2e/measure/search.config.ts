// The search's measurement on the volume instance (cr-search-at-volume, search.ts beside it) — NOT lane L1, and not the
// list leg either: `scripts/measure_volume.py search` starts it the way it starts volume.ts (the overlay's Playwright
// image, the instance's own networks), with `-c measure/search.config.ts`. TEMPORARY, with the rest of
// `tests/e2e/measure/` (`cr-volume-measurement-cax` deletes it).
import { defineConfig, devices } from '@playwright/test'

const out = process.env.VEYQ_MEASURE_OUT ?? '/out'

export default defineConfig({
  testDir: '.',
  testMatch: 'search.ts',
  outputDir: `${out}/search-playwright`,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  // Four fresh devices, each signing in (Argon2id) and building the index of ~7,000 nodes, two of them at a 4x throttle.
  timeout: 60 * 60_000,
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    // The instance's domain resolves to its Traefik on the run's `edge` network, as in L1 (tests/e2e/playwright.config.ts).
    launchOptions: { args: ['--host-resolver-rules=MAP veyq.test traefik, MAP *.veyq.test traefik, MAP * ~NOTFOUND'] },
    ignoreHTTPSErrors: false,
    trace: 'off',
    video: 'off',
    screenshot: 'off',
    locale: 'en-US',
    actionTimeout: 5 * 60_000,
  },
})
