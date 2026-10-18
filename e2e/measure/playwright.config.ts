// The volume harness's browser leg (cr-volume-measurement-local) — NOT lane L1: it lives outside `tests/`
// and has its own config, so L1's run never collects it. `scripts/measure_volume.py` starts it in the Playwright image
// the overlay pins, on the measured instance's own networks and on cores outside the instance's (the overlay's
// `cpuset`), with `-c measure/playwright.config.ts`. TEMPORARY: `cr-volume-measurement-cax` deletes `e2e/measure/`.
import { defineConfig, devices } from '@playwright/test'

const out = process.env.VEYQ_MEASURE_OUT ?? '/out'

export default defineConfig({
  testDir: '.',
  testMatch: 'volume.ts',
  outputDir: `${out}/measure-playwright`,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  // A leg is many runs of a big list on a slow CPU; each wait inside it ends on a state or on a stall, never on this.
  timeout: 4 * 60 * 60_000,
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    // The instance's domain resolves to its Traefik on the run's `edge` network, as in L1 (e2e/playwright.config.ts).
    launchOptions: { args: ['--host-resolver-rules=MAP veyq.test traefik, MAP *.veyq.test traefik, MAP * ~NOTFOUND'] },
    ignoreHTTPSErrors: false,
    trace: 'off',
    video: 'off',
    screenshot: 'off',
    locale: 'en-US',
    actionTimeout: 60_000,
  },
})
