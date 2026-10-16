// Lane L1's browser suite (ADR 0090, ADR 0091 decision 3). One booted instance per run, so one worker and a fixed
// order: the `setup` project sets the instance up exactly once (a server is single-owner, uc.admin.setup-server) and
// every later project starts from the state it wrote. Retries are 0 on purpose — a flake against a real stack is a
// finding, and a retried pass is a green tick over it. No tiers: the whole suite runs on every run, and each test
// carries the acceptance criterion it covers as a tag (`@uc.<feature>.<use-case>:<criterion-id>`).
//
// It runs inside the Playwright image the overlay pins, on the run's compose network — never on the host — so a
// laptop runs exactly what CI runs (ADR 0090 decision 3): `scripts/run_stack_e2e.py` starts it.
import { defineConfig, devices } from '@playwright/test'
import { stack } from './src/stack'

// `npm run check` lists the tests with no stack booted; everything that needs one reads it at run time.
const booted = process.env.VEYQ_STACK_FILE !== undefined
const outDir = booted ? stack().outDir : 'test-results'

// The browser's resolver knows the instance and nothing else: the domain and every tenant subdomain resolve to
// Traefik on the run's `edge` network (as public DNS resolves an operator's domain to the host), `localhost` stays
// the loopback the admin tunnel listens on, and every other name fails to resolve — the second layer under
// `egress.ts`'s routes, so the run has no route out even for a request a route did not see.
function resolverRules(): string {
  if (!booted) return ''
  const { domain, traefikHost } = stack()
  return `MAP ${domain} ${traefikHost}, MAP *.${domain} ${traefikHost}, EXCLUDE localhost, MAP * ~NOTFOUND`
}

export default defineConfig({
  testDir: './tests',
  outputDir: `${outDir}/playwright`,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  // A cold account birth runs Argon2id in the browser, and the store check round-trips through Traefik to Garage.
  timeout: 120_000,
  globalSetup: './src/tunnel.ts',
  reporter: [
    ['list'],
    ['junit', { outputFile: `${outDir}/junit.xml` }],
    ['html', { outputFolder: `${outDir}/report`, open: 'never' }],
  ],
  use: {
    ...devices['Desktop Chrome'],
    launchOptions: { args: [`--host-resolver-rules=${resolverRules()}`] },
    // The certificate is a real ACME one from the run's Pebble, whose root the container's NSS db trusts
    // (infra/e2e/nss_trust_root.py) and nothing else does: a certificate Traefik failed to obtain — its default one,
    // ADR 0071's silent failure — is a red navigation here, not an ignored warning.
    ignoreHTTPSErrors: false,
    trace: 'on',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'en-US',
    // A locator that matches nothing — a renamed label above all — is red in seconds, naming it, rather than at the
    // test's timeout.
    actionTimeout: 15_000,
  },
  projects: [
    { name: 'setup', testMatch: /setup\/.*\.setup\.ts/ },
    // The scenarios start from the instance the setup project left (instance.json in the run directory).
    { name: 'scenarios', testMatch: /scenarios\/.*\.spec\.ts/, dependencies: ['setup'] },
    // The feature scenarios start from a tenant whose owner is active, which the round trip leaves; each one restores
    // what it switches (capability-off-states.spec.ts ends with every entry back on).
    { name: 'features', testMatch: /features\/.*\.spec\.ts/, dependencies: ['scenarios'] },
  ],
})
