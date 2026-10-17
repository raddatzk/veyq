// The ONE way a live e2e suite (`VEYQ_E2E=1`) gets a usable instance: cold-birth it over the real HTTP API.
//
// Before this existed there were two bootstrap contracts, and they were incompatible. `scopeRotation.e2e` asserted
// `setupRequired === true` and birthed everything itself; the three memory suites read `web/scripts/.seed/
// dev-seed.json`, whose producer had to reach into the dev DB with `docker exec … UPDATE veyq_tenant` because
// `POST /api/tenants` rejects a numeric domain. That DB poke is not executable from CI, so the seeded contract
// could never run under `scripts/run_web_e2e.py` — which is why every suite now takes this path instead.
//
// The block is short but pulls eight symbols from five modules, so it is extracted rather than copied three
// times. The local dev principal is born by `web/scripts/bootstrap-user.mts` on the same `*.localhost` footing.
//
// One tenant domain serves every suite: `veyq.localhost` is a VALID dotted domain (so `POST /api/tenants` and the
// invite mailer's hostname check both accept it) AND resolves to loopback per RFC 6761 (so it works as the fetch
// Host with no `/etc/hosts` entry). It has to be a real host in the URL, not a spoofed header: tenant resolution
// is server-side from the request `Host`, and fetch/undici strips a manually set one.
import { expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { session } from '../app/session'
import { setUnlocked } from '../app/vault'
import { login } from '../features/account/login/login'
import { buildAccount, type BuiltAccount } from '@veyq/crypto/accountBirth'
import { moderationEpochOne } from './moderationEpochOne'
import { setupApi } from '../api/client'
import { Configuration as SetupConfiguration, SetupApi } from '../api/generated'
import { Configuration as TenantsConfiguration, TenantsApi } from '../api/generated/tenants'
import { updateTenantMailSettings } from '../features/tenant/tenantMailSettings'
import { acceptInvite, registerAndAccept, scopeGrant } from '../features/tenant/tenantInvite'
import { inviteMember } from '../features/spaces/members'
import { myUserId } from '../features/account/profile/profile'
import { mailCursor, MAILPIT_SMTP_PORT, waitForMail } from './mailpit'

/** Tenant domain and API base — see the module header for why this exact host. */
export const HOST = process.env.VEYQ_HOST ?? 'veyq.localhost'
export const BASE = process.env.VEYQ_BASE ?? `http://${HOST}:8080`

/**
 * The backend's admin listener — the only door the Server-Owner operations answer on; the public `BASE` 404s them
 * (ADR 0065). Addressed by loopback IP rather than by `HOST`, the way the admin container reaches it: a
 * Server-Owner call is tenant-independent, and a tenant's own `Host` would put it under that tenant's suspension.
 */
export const ADMIN_BASE = process.env.VEYQ_ADMIN_BASE ?? 'http://127.0.0.1:8090'

/**
 * The two Server-Owner calls this harness makes — first-run setup and a PENDING tenant with its owner invite —
 * through the admin listener. They are spelled against the generated clients rather than a facade because `web/`
 * has none: that surface lives in `admin-web` (ADR 0065). A test harness birthing a whole instance legitimately
 * needs them; product code in `web/` does not, and a facade here would give it one to reach for.
 */
function createServerOwner(m: BuiltAccount['material'], email: string) {
  return new SetupApi(new SetupConfiguration({ basePath: ADMIN_BASE })).createServerOwner({
    createServerOwnerRequest: {
      email, suiteId: m.suiteId, identityPublicKey: m.identityPublicKey,
      wrappedIdentityPrivateKey: m.wrappedIdentityPrivateKey, authPublicKey: m.authPublicKey,
      masterKeyWraps: m.masterKeyWraps, secretKeyProofPublicKey: m.secretKeyProofPublicKey,
      moderationEpoch: moderationEpochOne(m.identityPublicKey),
    },
  })
}

function createTenant(tenantName: string, domain: string, ownerEmail: string) {
  const config = new TenantsConfiguration({ basePath: ADMIN_BASE, accessToken: () => session.accessToken ?? '' })
  return new TenantsApi(config).createTenant({ createTenantRequest: { tenantName, domain, ownerEmail } })
}

/**
 * The object store is a Server-Owner setting that must have PASSED its check before the server creates a tenant (ADR 0089
 * decision 6; `409 store_not_ready`), and the environment only carries the bundled store's HINT — so a fresh instance
 * chooses the bundled store and checks it, which is the whole of what an operator does too. A hand-built call, not a
 * generated client: `web/` generates none for `/api/instance/store` (that surface is `admin-web`'s, ADR 0065) and a
 * client for one harness step would hand product code a Server-Owner door.
 */
async function chooseBundledStore(): Promise<void> {
  const call = async (method: string, path: string, body?: unknown) => {
    const res = await fetch(`${ADMIN_BASE}${path}`, {
      method,
      headers: { authorization: `Bearer ${session.accessToken ?? ''}`, ...(body === undefined ? {} : { 'content-type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
    return { status: res.status, json: (await res.json().catch(() => null)) as { bundledAvailable?: boolean; passed?: boolean; steps?: unknown } | null }
  }
  const now = await call('GET', '/api/instance/store')
  expect(now.status, 'GET /api/instance/store').toBe(200)
  expect(now.json?.bundledAvailable, 'the e2e profile carries the bundled-store hint').toBe(true)
  const put = await call('PUT', '/api/instance/store', { bundled: true })
  expect(put.status, 'PUT /api/instance/store {bundled}').toBe(200)
  const checked = await call('POST', '/api/instance/store/check')
  expect({ status: checked.status, passed: checked.json?.passed, steps: checked.json?.steps }, 'the bundled store passes its check').toMatchObject({ status: 200, passed: true })
}

/**
 * Varies emails/usernames per run so a re-run against a non-fresh DB does not collide — and, just as important,
 * so the per-recipient invite budget (3 per recipient / 3600 s) sees a new recipient each run instead of tripping.
 * `scripts/run_web_e2e.py` sets it per run; a hand run gets the stable default.
 */
export const stamp = process.env.VEYQ_STAMP ?? 'e2e'

export type Actor = {
  built: BuiltAccount
  email: string
  password: string
  username: string
  userId?: string
}

/** An actor's identity, derived from a short local part so a suite can name its actors `a`, `b`, `c`. */
export function mkActor(local: string): Actor {
  return {
    built: null as unknown as BuiltAccount,
    email: `${local}-${stamp}@e2e.test`,
    password: `Pw-${local}-${stamp}-9xQ!`,
    username: `${local}${stamp}`,
  }
}

/**
 * Swap the process-wide session + vault to this actor (the suites run serialized, single-actor).
 *
 * Re-derives the Master Key from the account's PASSWORD wrap rather than calling the app's `unlockMasterKey`,
 * for a reason specific to a cold-birthed actor: `unlockMasterKey` opens the vault from the SERVER's key
 * material, while the identity private key here is the one `buildAccount` already holds in memory. Going
 * through the server path would re-unwrap what we already have and couple every actor swap to the device-tier
 * logic in `openVault`, which no suite is testing.
 */
export async function become(a: Actor): Promise<void> {
  await login(a.email, a.password)
  const wrap = a.built.material.masterKeyWraps.find((w) => w.method === 'PASSWORD')!
  const { params, salt } = v1.decodeKdfParams(fromB64std(wrap.kdfParams))
  const kek = v1.kekPasswordSecret(fromB64std(a.built.emergencyKit.secretKey), a.password, salt, params)
  const masterKey = v1.open({ purpose: 'master-key' }, kek, fromB64std(wrap.wrappedKey))
  // It opened the PASSWORD wrap with password + Secret Key, so `cold` is what actually happened here. The vault
  // takes ownership of what it is handed and zeroes it on `clearVault()` — so it gets a COPY of the actor's
  // identity, or a suite that locks the vault (a password-only session) would wipe the actor for every later
  // `become()`, and its grants would stop verifying under an all-zero key.
  const p = a.built.identity.priv
  setUnlocked(masterKey, { kemSeed: p.kemSeed.slice(), sign: p.sign.slice() }, 'cold')
}

export type BirthedInstance = {
  /** The server owner, who is also the tenant's owner — signed in and unlocked when this resolves. */
  owner: Actor
  /** `tenantId` for member invites; `bootstrapSecret` is already spent on the owner's own accept. */
  tenant: Awaited<ReturnType<typeof createTenant>>
  /** The instance salt every further `buildAccount` in this suite must use. */
  salt: Uint8Array
}

/**
 * Cold-birth a fresh instance: assert it IS fresh, mint the server owner, create the tenant, accept it as that
 * owner and point the tenant's mail at Mailpit. Leaves `session`/vault unlocked as the owner.
 *
 * The freshness assertion is load-bearing, not defensive: a suite that silently ran against a warm instance
 * would be asserting against another run's state. `run_web_e2e.py` gives each file its own database, so
 * `setupRequired` is the cheap end-to-end confirmation that it actually did.
 *
 * **Mail is configured unconditionally, for every suite, not opt-in per suite.** A mailer is a base function of
 * an instance and a tenant without one is misconfigured rather than a valid variant (ADR 0055 §1) — so a helper
 * whose whole job is "a usable instance" must not hand back one that cannot invite anybody. The cost is real and
 * accepted: every suite now depends on Mailpit being up, so a dead mailbox fails the whole run instead of two
 * files. That is the honest signal, since the harness requires it either way. `username: null` keeps the tenant
 * off the sealed-password path, which would need the server KEK (`mail_secret_unavailable`).
 */
export async function birthInstance(tenantName: string): Promise<BirthedInstance> {
  session.apiBase = BASE
  const setup = await setupApi().getSetupStatus()
  expect(setup.setupRequired, 'run against a FRESH instance (setupRequired=true)').toBe(true)
  const salt = fromB64std(setup.instanceSalt)

  const owner = mkActor('a')
  owner.built = await buildAccount(owner.email, owner.password, salt)

  await createServerOwner(owner.built.material, owner.email)
  await become(owner)
  await chooseBundledStore()
  const tenant = await createTenant(tenantName, HOST, owner.email)
  // The owner's birth is signed by its identity and names its account (ADR 0028 Decision 6) — read it first.
  owner.userId = await myUserId()
  await acceptInvite(tenant.bootstrapSecret, owner.username, scopeGrant({ userId: owner.userId, identity: owner.built.identity }, tenant.tenantId))
  await updateTenantMailSettings(tenant.tenantId, {
    host: HOST,
    port: MAILPIT_SMTP_PORT,
    from: `noreply@${HOST}`,
    username: null,
    tls: false,
    enabled: true,
  })
  return { owner, tenant, salt }
}

/**
 * Onboard a tenant MEMBER: an owner-minted invite, redeemed by a grant-less cold-birth accept. The caller must
 * currently `become()` the owner (the invite endpoint is owner-gated). Leaves the session as the OWNER, not the
 * new member — the caller decides who acts next.
 *
 * **The token is read out of the mailbox, because that is the only channel it travels on.** The invite response
 * no longer carries `bootstrapSecret`: a member invite is minted for the invited person (ADR 0055 §2). The
 * dev-invite file is not an alternative — a booted fast-jar never writes it (`TenantInviteMailer` early-returns
 * in `LaunchMode.NORMAL` before the append). So this helper onboards a member exactly the way a member is
 * onboarded, which is the point: nothing here is a test-only shortcut around the product's own routing.
 *
 * The regex anchors on the accept path + **fragment** (`SmtpTenantInviteMailer`: `https://$domain/tenant/accept#…`),
 * never on a base URL — the mail's link is `https` with no port, while the suite talks to `http://…:8080`, so a
 * pattern built from the suite's own base would match nothing. A mail cursor is taken for this recipient first,
 * so a leftover from an earlier hand run (which shares the default `VEYQ_STAMP`, hence the same address) fails as
 * a clean timeout instead of as an expired token three calls later.
 */
export async function onboardMember(tenantId: string, actor: Actor, salt: Uint8Array): Promise<Actor> {
  actor.built = await buildAccount(actor.email, actor.password, salt)
  const cursor = await mailCursor(actor.email)
  await inviteMember(tenantId, actor.email)
  const body = await waitForMail(actor.email, { after: cursor })
  const link = body.match(/\/tenant\/accept#([A-Za-z0-9_-]+)/)
  expect(link, `no invite link in the mail to ${actor.email}:\n${body}`).toBeTruthy()
  const accepted = await registerAndAccept(link![1], crypto.randomUUID(), actor.username, actor.built.material)
  actor.userId = accepted.userId
  return actor
}
