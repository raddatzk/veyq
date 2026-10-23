// The profile driver's browser leg (cr-profile-where-the-time-goes) — NOT lane L1: it lives outside `tests/` and has
// its own config, so L1's run never collects it. `scripts/profile_hot_paths.py` starts it once per run and throttle,
// in the Playwright image the overlay pins, on the profiled instance's own networks, with `-c profile/playwright.config.ts
// <scenario>`; the instance was set up by L1's own `setup` and `scenarios` projects first (the e2e bootstrap).
//
// Nothing here may cost the page time it would not cost a member: no Playwright trace, video or screenshot, and no
// request routes (a route pauses every request through the Playwright protocol). The browser is still confined to the
// instance by its resolver, as in L1 — every other name fails to resolve.
import { defineConfig, devices } from '@playwright/test'

const domain = process.env.VEYQ_PROFILE_DOMAIN ?? 'veyq.test'
const out = process.env.VEYQ_PROFILE_OUT ?? '/out'

export default defineConfig({
  testDir: '.',
  testMatch: '*.profile.ts',
  outputDir: `${out}/profile-playwright`,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  // One run is one unlock (or, later, one upload or one open); under a CPU throttle of 4 Argon2id alone takes long.
  timeout: 10 * 60_000,
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    launchOptions: { args: [`--host-resolver-rules=MAP ${domain} traefik, MAP *.${domain} traefik, MAP * ~NOTFOUND`] },
    ignoreHTTPSErrors: false,
    trace: 'off',
    video: 'off',
    screenshot: 'off',
    locale: 'en-US',
    actionTimeout: 60_000,
  },
})
