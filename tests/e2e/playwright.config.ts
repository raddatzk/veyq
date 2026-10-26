// Lane L1's browser suite (ADR 0090, ADR 0091 decision 3). One booted instance per run, so one worker and a fixed
// order: the `setup` project sets the instance up exactly once (a server is single-owner, uc.admin.setup-server) and
// every later project starts from the state it wrote. Retries are 0 on purpose — a flake against a real stack is a
// finding, and a retried pass is a green tick over it. No tiers: the whole suite runs on every run, and each test
// carries the acceptance criterion it covers as a tag (`@uc.<feature>.<use-case>:<criterion-id>`). Every flow runs in
// English; which German passes it keeps, and why, is ADR 0090 decision 9.
//
// CI splits the run into shards (`ci.yml`'s `stack` job, `-- --shard <i>/<n>`), each on an instance of its own. Playwright
// shards only the projects no other project depends on, and runs every dependency project whole in every shard: so the
// state a feature scenario needs from another one lives in a dependency project (`instance`, below), and `features` is
// the one project that is split — every spec in it starts from what `setup`, `scenarios` and `instance` left, never from
// another feature spec, whichever shard it lands in.
//
// It runs inside the Playwright image the overlay pins, on the run's compose network — never on the host — so a
// laptop runs exactly what CI runs (ADR 0090 decision 3): `scripts/run_stack_e2e.py` starts it.
import { defineConfig, devices } from '@playwright/test'
import { stack } from './src/stack'

// `npm run check` lists the tests with no stack booted; everything that needs one reads it at run time.
const booted = process.env.VEYQ_STACK_FILE !== undefined
const outDir = booted ? stack().outDir : 'test-results'
// The instance-wide state other feature scenarios read, set up in every shard (the `instance` project, below).
const INSTANCE = /features\/(instance-mail|moderation)\.spec\.ts/

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
    // (tests/e2e/stack/nss_trust_root.py) and nothing else does: a certificate Traefik failed to obtain — its default one,
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
    // The instance-wide state the feature scenarios read, in every shard: instance-mail.spec.ts sets up the instance mail
    // (the notices of moderation, erasure and application-erasure.spec.ts go out through it) and ends with the release
    // check switched off, the state an offline run's check belongs in (ADR 0097); then moderation.spec.ts appoints the
    // deputy whom statement-and-objection.spec.ts and takedown-orders-and-art18.spec.ts sign in as (`deputy.json`) and
    // confirms the moderator report.spec.ts mails. In this order: moderation's code mail goes through the instance mail.
    { name: 'instance', testMatch: INSTANCE, dependencies: ['scenarios'] },
    // The feature scenarios start from a tenant whose owner is active, which the round trip leaves, and from the instance
    // the `instance` project set up; each one restores what it switches (capability-off-states.spec.ts ends with every
    // entry back on), and none reads what another feature spec left — the project a shard splits.
    { name: 'features', testMatch: /features\/.*\.spec\.ts/, testIgnore: INSTANCE, dependencies: ['instance'] },
  ],
})
