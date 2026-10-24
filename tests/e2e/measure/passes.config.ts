// The heavy stage's browser passes on the volume instance (passes.ts beside it) — NOT lane L1, and not the measuring
// leg either: `scripts/measure_volume.py pass <name>` starts it the way it starts volume.ts (the overlay's Playwright
// image, the instance's own networks), with `-c measure/passes.config.ts`. A pass needs what only that instance has: a
// mark set seeded to its cap, a pin store of a thousand members and its database within reach of the driver.
// TEMPORARY, with the rest of `tests/e2e/measure/` (`cr-volume-measurement-cax` deletes it).
import { defineConfig, devices } from '@playwright/test'

const out = process.env.VEYQ_MEASURE_OUT ?? '/out'

export default defineConfig({
  testDir: '.',
  testMatch: 'passes.ts',
  outputDir: `${out}/passes-playwright`,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  // Signing in on a fresh device runs Argon2id, and a 12,285-photo Memory opens in seconds, not minutes.
  timeout: 20 * 60_000,
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    // The instance's domain resolves to its Traefik on the run's `edge` network, as in L1 (tests/e2e/playwright.config.ts).
    launchOptions: { args: ['--host-resolver-rules=MAP veyq.test traefik, MAP *.veyq.test traefik, MAP * ~NOTFOUND'] },
    ignoreHTTPSErrors: false,
    trace: 'retain-on-failure',
    video: 'off',
    screenshot: 'only-on-failure',
    locale: 'en-US',
    actionTimeout: 60_000,
  },
})
