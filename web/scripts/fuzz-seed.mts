// Fuzz seed — the instance the API fuzzing runs against (ADR 0091 decision 9; `scripts/run_api_fuzz.py` calls it).
//
// Schemathesis cannot sign in: a veyq sign-in is client-side Argon2id plus a signature over the server's nonce. So
// this births a FRESH instance with the real client crypto — the shared cold birth in `instanceBirth.mts`, the same
// one `bootstrap-user.mts` uses: the Server Owner on the admin listener, the bundled store chosen and checked, a
// tenant on the fuzzed domain accepted as that owner and sending through Mailpit, its personal scope — and hands
// the run what it needs:
//
//   <out>/seed.json   the credentials (a throwaway account of a throwaway instance) and the ids the positive `GET`s
//                     are fixed to: `tenantId`, `userId` (the owner), `scopeId` (its personal scope);
//   <out>/token       a fresh access token (it lives 15 minutes, `SessionService.ACCESS_TTL_MINUTES`).
//
// Both are written mode 0600 and NEVER printed: a run's stdout is a CI log. `--login` signs in again from
// `seed.json` and rewrites only `token` — the runner calls it before each invocation, because a run outlives a token.
//
// Usage (from web/): npx tsx scripts/fuzz-seed.mts --out <dir> [--login]
// Environment: VEYQ_BASE / VEYQ_ADMIN_BASE (apiTransport.mts), VEYQ_HOST (the tenant's domain; default veyq.localhost).
import { readFileSync, writeFileSync, chmodSync } from 'node:fs'
import { join } from 'node:path'
import { MAILPIT_SMTP_PORT } from '../src/test/mailpit'
import { Configuration as TenantsConfiguration, TenantsApi } from '../src/api/generated/tenants'
import { tenantChannel } from './apiTransport.mts'
import { awaitBackend, birthPersonalScope, birthServerOwner, birthTenant, ensureBundledStore, orThrow, signIn } from './instanceBirth.mts'

type Seed = { email: string; password: string; domain: string; ids: { tenantId: string; userId: string; scopeId: string } }

function fail(msg: string): never { console.error(`fuzz-seed: ${msg}`); process.exit(1) }

const args = process.argv.slice(2)
const outAt = args.indexOf('--out')
const out = outAt >= 0 ? args[outAt + 1] : undefined
if (!out) fail('--out <dir> is required')
const login = args.includes('--login')

/** Write a secret the way only its owner may read it: created 0600, and chmod'ed in case the file already existed. */
function writeSecret(path: string, body: string): void {
  writeFileSync(path, body, { mode: 0o600 })
  chmodSync(path, 0o600)
}

try {
  if (login) {
    const seed = JSON.parse(readFileSync(join(out, 'seed.json'), 'utf8')) as Seed
    writeSecret(join(out, 'token'), await signIn(seed.email, seed.password))
    console.log('fuzz-seed: signed in again, token rewritten')
  } else {
    const domain = (process.env.VEYQ_HOST ?? 'veyq.localhost').toLowerCase()
    const setup = await awaitBackend()
    if (!setup.setupRequired) fail('the instance is already set up — the fuzzing runs against a FRESH one')
    const email = 'fuzz-owner@fuzz.test'
    // Random per birth and never printed: the account is throwaway, its password is still nobody's to read in a log.
    const password = `Fz-${crypto.randomUUID()}-9x!`
    const built = await birthServerOwner(email, password, setup)
    const token = await signIn(email, password)
    await ensureBundledStore(token)
    const tenant = await birthTenant(token, built, { tenantName: 'Fuzz', domain, email, username: 'fuzzowner' })
    const scopeId = await birthPersonalScope(token, domain, tenant.ownerId, built)
    // The tenant sends through the run's Mailpit, as every e2e suite's does (`e2eBootstrap.birthInstance`): an
    // instance that cannot mail answers `503 mail_unavailable` to every operation that mails, which is a statement about
    // the instance, not about the API. `username: null` keeps it off the sealed-password path (no config-secret KEK).
    await orThrow('updateTenantMailSettings', new TenantsApi(new TenantsConfiguration(tenantChannel(domain, token))).updateTenantMailSettings({
      tenantId: tenant.tenantId,
      tenantMailSettingsUpdate: { host: domain, port: MAILPIT_SMTP_PORT, from: `noreply@${domain}`, username: null, tls: false, enabled: true },
    }))
    const seed: Seed = { email, password, domain, ids: { tenantId: tenant.tenantId, userId: tenant.ownerId, scopeId } }
    writeSecret(join(out, 'seed.json'), `${JSON.stringify(seed, null, 2)}\n`)
    writeSecret(join(out, 'token'), token)
    console.log(`fuzz-seed: instance born (owner, tenant ${domain}, personal scope) — seed and token written to ${out}`)
  }
} catch (e) {
  fail((e as Error).message)
}
