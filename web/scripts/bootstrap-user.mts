// Bootstrap User — create the local dev principal from .run/.env and record its derived key material back into
// the same file. Reuses the real cold-account-birth crypto (buildAccount) and calls the API through the generated
// clients (apiTransport.mts), so it is operator-blind — only ciphertext + public keys are POSTed; the password never
// leaves this process — and a contract change turns it red at `npm run typecheck`, not at its next run.
//
// The human fills the inputs in .run/.env (copy from .run/.env.example):
//   VEYQ_USERNAME, VEYQ_PASSWORD, VEYQ_EMAIL, VEYQ_TENANT, VEYQ_TENANT_DOMAIN, and the optional
//   VEYQ_FIRST_NAME / VEYQ_LAST_NAME (the real name, sealed under K_tenant at accept — operator-blind).
// This script creates the Server-Owner / tenant-owner veyq_user (with a personal scope), and — when the optional
// VEYQ_USER2_* inputs are set — a SECOND user onboarded as a tenant MEMBER with its tenant grant delivered (a
// ready 2-member tenant for multi-member dev). It writes the derived key material back:
//   VEYQ_SECRET_KEY, VEYQ_WRAPPED_MASTERKEY (+ VEYQ_USER2_SECRET_KEY / VEYQ_USER2_WRAPPED_MASTERKEY)
// Run it against a FRESH backend (Bootstrap DB + Garage → Run Backend first); a re-run needs a `down -v` wipe.
//
// Two properties this file exists to keep, both learned the hard way:
//  - **The Secret Key in .run/.env survives a re-seed.** A cold birth mints one at random, so re-seeding used to
//    invalidate the kit the human had already copied into a browser/password manager. When .run/.env already
//    carries one, we hand it to `buildAccount` and only the rest of the account is born fresh.
//  - **The owner's material is written the moment it is complete** (step 4a) — before any optional step can
//    fail. It used to be written last, so a failure in the OPTIONAL user2 block destroyed the keys of a
//    successfully-born owner: the account existed in the DB, its Secret Key existed only in this process, and
//    .run/.env still held the previous run's. Anything after 4a therefore warns instead of exiting.
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std, b64std } from '@veyq/crypto/base64'
import { buildAccount } from '@veyq/crypto/accountBirth'
import { sealName } from '../src/features/account/profile/memberName'
import { admissionVerifies, sealGrant, tagAdmission } from '../src/features/spaces/grant'
import { MAILPIT, MAILPIT_SMTP_PORT, mailCursor, mailpitReachable, waitForMail } from '../src/test/mailpit'
import { Configuration as SetupConfiguration, SetupApi } from '../src/api/generated'
import { Configuration as AuthConfiguration, LoginApi } from '../src/api/generated/auth'
import { AccountApi, Configuration as AccountConfiguration } from '../src/api/generated/account'
import { Configuration as TenantsConfiguration, TenantsApi } from '../src/api/generated/tenants'
import { Configuration as ScopesConfiguration, ScopesApi } from '../src/api/generated/scopes'
import { Configuration as TasksConfiguration, TasksApi, type ClaimedJob } from '../src/api/generated/tasks'
import { BASE, adminChannel, describeFailure, publicChannel, tenantChannel } from './apiTransport.mts'

const ENV_FILE = fileURLToPath(new URL('../../.run/.env', import.meta.url))
/** Where the backend reaches Mailpit's SMTP. Dotted on purpose: `TenantMailSettingsUpdate.host` is pinned to
 *  `^([a-z0-9](-?[a-z0-9])*\.)+[a-z]{2,}$`, so a bare `localhost` is a 400. `*.localhost` resolves to loopback. */
const SMTP_HOST = process.env.VEYQ_SMTP_HOST ?? 'mailpit.localhost'

function fail(msg: string): never { console.error(`bootstrap-user: ${msg}`); process.exit(1) }

// --- .run/.env I/O (a tiny dotenv: KEY=VALUE lines) -------------------------------------------------------
function readEnv(): Record<string, string> {
  if (!existsSync(ENV_FILE)) fail(`no ${ENV_FILE} — copy .run/.env.example to .run/.env and fill it in`)
  const out: Record<string, string> = {}
  for (const line of readFileSync(ENV_FILE, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/)
    if (m && !line.trimStart().startsWith('#')) out[m[1]] = m[2].trim()
  }
  return out
}
function writeEnv(fields: Record<string, string>): void {
  const order = ['VEYQ_USERNAME', 'VEYQ_EMAIL', 'VEYQ_PASSWORD', 'VEYQ_FIRST_NAME', 'VEYQ_LAST_NAME', 'VEYQ_SECRET_KEY', 'VEYQ_WRAPPED_MASTERKEY', 'VEYQ_TENANT', 'VEYQ_TENANT_DOMAIN', 'VEYQ_USER2_USERNAME', 'VEYQ_USER2_EMAIL', 'VEYQ_USER2_PASSWORD', 'VEYQ_USER2_SECRET_KEY', 'VEYQ_USER2_WRAPPED_MASTERKEY']
  const body = order.map((k) => `${k}=${fields[k] ?? ''}`).join('\n')
  writeFileSync(ENV_FILE, `# Local dev principal (gitignored). Inputs filled by you; SECRET_KEY + WRAPPED_MASTERKEY\n# filled by \`npm run bootstrap:user\`. Throwaway dev creds for a local instance — never committed.\n${body}\n`)
}

// --- API calls: the generated clients, over the three channels apiTransport.mts configures ----------------------
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Run one generated call, or end the run with what the backend answered. Only for steps before 4a. */
async function orFail<T>(what: string, call: Promise<T>): Promise<T> {
  try { return await call } catch (e) { return fail(`${what} failed: ${await describeFailure(e)}`) }
}
/** The same, for the optional steps after 4a: throw, and let the caller downgrade it to a warning. */
async function orThrow<T>(what: string, call: Promise<T>): Promise<T> {
  try { return await call } catch (e) { throw new Error(`${what} failed: ${await describeFailure(e)}`) }
}

async function awaitBackend(timeoutMs = 90_000) {
  const setupApi = new SetupApi(new SetupConfiguration(publicChannel()))
  const deadline = Date.now() + timeoutMs
  let last = ''
  while (Date.now() < deadline) {
    try { return await setupApi.getSetupStatus() } catch (e) { last = await describeFailure(e) }
    await sleep(750)
  }
  return fail(`backend did not answer ${BASE}/api/setup within ${timeoutMs}ms (${last})`)
}

// --- run --------------------------------------------------------------------------------------------------
const env = readEnv()
const username = (env.VEYQ_USERNAME || fail('VEYQ_USERNAME missing in .run/.env')).trim().toLowerCase()
const password = env.VEYQ_PASSWORD || fail('VEYQ_PASSWORD missing in .run/.env')
const email = env.VEYQ_EMAIL || fail('VEYQ_EMAIL missing in .run/.env')
const tenantName = env.VEYQ_TENANT || fail('VEYQ_TENANT missing in .run/.env')
const domain = (env.VEYQ_TENANT_DOMAIN || fail('VEYQ_TENANT_DOMAIN missing in .run/.env')).toLowerCase()
const firstName = (env.VEYQ_FIRST_NAME ?? '').trim()
const lastName = (env.VEYQ_LAST_NAME ?? '').trim()
// Optional second user (a tenant MEMBER) — onboarded only when all three are set.
const u2Username = (env.VEYQ_USER2_USERNAME ?? '').trim().toLowerCase()
const u2Email = (env.VEYQ_USER2_EMAIL ?? '').trim()
const u2Password = env.VEYQ_USER2_PASSWORD ?? ''

// An IP literal is refused HERE, before anything is born. The contract's hostname pattern refuses it too, but only
// at `POST /api/tenants` — after the owner exists and before step 4a persists its Secret Key, which would strand
// an owner nobody can unlock. The dev tenant is a `*.localhost` domain instead: valid under that pattern, loopback
// by RFC 6761, and forwarded as-is by the dev proxy. This is the only copy of the shape the seeder keeps; every
// other shape error is the contract's 400.
if (/^[0-9.]+$/.test(domain)) {
  fail(`VEYQ_TENANT_DOMAIN=${domain} is an IP literal, which no tenant can have — use a *.localhost domain (e.g. veyq.localhost)`)
}

/**
 * The Emergency Kit already in .run/.env, when there is a usable one — so a re-seed after `down -v` keeps the
 * Secret Key the human has already copied somewhere. Anything unusable (blank, not base64, wrong length) falls
 * back to a fresh mint rather than failing the run; a dev kit is not worth a blocked bootstrap.
 */
function reusableSecretKey(raw: string | undefined, who: string): Uint8Array | undefined {
  const s = (raw ?? '').trim()
  if (!s) return undefined
  const bytes = (() => { try { return fromB64std(s) } catch { return undefined } })()
  if (!bytes || bytes.length !== 32) {
    console.warn(`bootstrap-user: ${who} secret key in .run/.env is unusable — minting a fresh one`)
    return undefined
  }
  console.log(`${who}: re-using the Secret Key already in .run/.env`)
  return bytes
}

const setup = await awaitBackend()
if (!setup.setupRequired) fail('instance already set up — wipe it first (Bootstrap DB + Garage: `docker compose … down -v`), the Secret Key is random per birth')

// 1 — cold-birth the Server Owner
const built = await buildAccount(email, password, fromB64std(setup.instanceSalt), {
  secretKey: reusableSecretKey(env.VEYQ_SECRET_KEY, 'owner'),
})
const m = built.material
await orFail('setup/owner', new SetupApi(new SetupConfiguration(adminChannel())).createServerOwner({
  createServerOwnerRequest: {
    email,
    suiteId: m.suiteId, identityPublicKey: m.identityPublicKey, wrappedIdentityPrivateKey: m.wrappedIdentityPrivateKey,
    authPublicKey: m.authPublicKey, masterKeyWraps: m.masterKeyWraps, secretKeyProofPublicKey: m.secretKeyProofPublicKey,
  },
}))
console.log(`owner: created ${email}`)

// 2 — log in (account-level token)
const loginApi = new LoginApi(new AuthConfiguration(publicChannel()))
const ch = await orFail('login challenge', loginApi.requestLoginChallenge({ loginChallengeRequest: { email } }))
const auth = v1.deriveAuthKeypair(password, v1.deriveAuthSalt(fromB64std(ch.instanceSalt), email), v1.ARGON)
const { accessToken: token } = await orFail('login', loginApi.verifyLogin({
  loginVerifyRequest: { email, nonce: ch.nonce, signature: b64std(v1.authSign(auth.privateKey, fromB64std(ch.nonce))) },
}))
const onTenant = tenantChannel(domain, token)
const tenantsApi = new TenantsApi(new TenantsConfiguration(onTenant))

// 3 — the configured-domain tenant, accepted as the existing user (TENANT grant sealed to our own identity)
// The owner invite's secret comes back to the Server Owner who minted it (`TenantCreated.bootstrapSecret`), so no
// mailbox or dev-invite file is read for it.
const { tenantId, bootstrapSecret } = await orFail('createTenant', new TenantsApi(new TenantsConfiguration(adminChannel(token)))
  .createTenant({ createTenantRequest: { tenantName, domain, ownerEmail: email } }))
// Keep the freshly-minted TENANT scope key (K_tenant): the grant wraps it, and — holding it here — we can seal
// the optional real name at accept, exactly as TenantJoinView does at an owner cold-birth (cr-0106).
const tenantScopeKey = v1.randomBytes(32)
// Bound to the tenant scope (its id IS the tenant id) at the birth epoch 0; the name to (tenant scope, our id) (ADR 0072).
// The birth is what TenantJoinView sends: the grant signed by our identity (ADR 0028 Decision 6) and our own
// admission self-tagged under E_0 (ADR 0020 move 6).
const { userId: ownerId } = await orFail('key-material', new AccountApi(new AccountConfiguration(onTenant)).getAccountKeyMaterial())
const ownerPub = b64std(v1.serializeIdentityPublic(built.identity.pub))
const tenantBirth = sealGrant(tenantScopeKey, { scopeId: tenantId, kind: 'TENANT', epoch: 0 }, { userId: ownerId, identityPublicKeyB64: ownerPub }, built.identity.priv.sign)
const ownerAdmission = tagAdmission(tenantScopeKey, { scopeId: tenantId, kind: 'TENANT' }, 0, null)
const sealedName = firstName || lastName ? sealName(tenantScopeKey, { scopeId: tenantId, userId: ownerId }, { firstName, lastName }) : undefined
await orFail('accept', tenantsApi.acceptTenantInvite({
  acceptInviteRequest: {
    bootstrapSecret, username, grant: tenantBirth.wrappedGrant, grantSignature: tenantBirth.grantSignature, ...ownerAdmission,
    ...(sealedName ? { sealedName } : {}),
  },
}))
console.log(`tenant: ${domain} created + accepted${sealedName ? ' + name sealed' : ''}`)

// 4 — the owner's PERSONAL scope
const personalScopeId = crypto.randomUUID() // client-minted: the grant is bound to it (ADR 0072)
const personalGrant = sealGrant(v1.randomBytes(32), { scopeId: personalScopeId, kind: 'PERSONAL', epoch: 0 }, { userId: ownerId, identityPublicKeyB64: ownerPub }, built.identity.priv.sign)
const scope = await orFail('createPersonalScope', new ScopesApi(new ScopesConfiguration(onTenant)).createPersonalScope({
  createPersonalScopeRequest: { scopeId: personalScopeId, ...personalGrant },
}))
console.log(`scope: personal scope ${scope.scopeId} created`)

// 4a — the owner is complete: record its material NOW. Everything below is optional, and an optional step must
// never be able to take the owner's Secret Key down with it (it exists nowhere but this process until here).
let u2SecretKey = ''
let u2WrappedKey = ''
const passwordWrap = m.masterKeyWraps.find((w) => w.method === 'PASSWORD')!
const persist = (): void => writeEnv({
  VEYQ_USERNAME: username, VEYQ_EMAIL: email, VEYQ_PASSWORD: password,
  VEYQ_FIRST_NAME: firstName, VEYQ_LAST_NAME: lastName,
  VEYQ_SECRET_KEY: built.emergencyKit.secretKey, VEYQ_WRAPPED_MASTERKEY: passwordWrap.wrappedKey,
  VEYQ_TENANT: tenantName, VEYQ_TENANT_DOMAIN: domain,
  VEYQ_USER2_USERNAME: u2Username, VEYQ_USER2_EMAIL: u2Email, VEYQ_USER2_PASSWORD: u2Password,
  VEYQ_USER2_SECRET_KEY: u2SecretKey, VEYQ_USER2_WRAPPED_MASTERKEY: u2WrappedKey,
})
persist()
console.log(`env: owner key material written to ${ENV_FILE}`)

// 4b — point the tenant at Mailpit, so it can actually send. Not a nicety: since ADR 0055 a member invite
// travels by mail or not at all, so a tenant with no sendable settings refuses `POST /member-invites` with
// `503 mail_unavailable` BEFORE minting anything — which is what a hand-run instance always looked like. The
// `username: null` shape keeps it off the sealed-password path (no config-secret KEK needed), and the host is a
// dotted name that resolves to loopback because the contract's host pattern rejects a bare `localhost`.
async function configureMail(): Promise<boolean> {
  if (!(await mailpitReachable())) {
    console.warn(`mail: no Mailpit at ${MAILPIT} — skipping mail settings (start it: docker compose -f infra/docker-compose.yml --profile mail up -d)`)
    return false
  }
  try {
    await tenantsApi.updateTenantMailSettings({
      tenantId,
      tenantMailSettingsUpdate: { host: SMTP_HOST, port: MAILPIT_SMTP_PORT, from: `noreply@${domain}`, username: null, tls: false, enabled: true },
    })
  } catch (e) {
    console.warn(`mail: updateTenantMailSettings failed: ${await describeFailure(e)}`)
    return false
  }
  console.log(`mail: ${domain} sends via Mailpit (${SMTP_HOST}:${MAILPIT_SMTP_PORT}) — read it at ${MAILPIT}`)
  return true
}
const mailReady = await configureMail()

// 4c — optional SECOND user: a tenant MEMBER, fully granted so it is usable immediately (no "log the owner in
// first" step). The owner mints a MEMBER invite; user2 cold-births + register-and-accepts grant-less; then — as
// the online key-holder — we drain the grant-delivery job the backend originated (claim → wrap K_tenant to
// user2's identity → complete), exactly what a key-holder's device does in the web client (cr-0076).
//
// The invite token is read out of the MAILBOX, not the dev-invite file: a member invite is minted for the
// invited person and never returned to the caller (ADR 0055 §2), and the mailer's dev-file append only ever
// carried the owner-bootstrap invite it falls back on. Throws rather than `fail()`s — the caller downgrades any
// failure here to a warning, because the owner above is already usable and already persisted.
async function onboardUser2(): Promise<void> {
  if (!mailReady) throw new Error('no sendable mail settings — a MEMBER invite has no other channel (ADR 0055)')
  // A cursor, not a cleared mailbox: the e2e sweep reads the whole mailbox afterwards (cr-wire-assertion-history-and-mail).
  const cursor = await mailCursor(u2Email)
  // The invite carries our admission tag under K_tenant (ADR 0020 move 6) — without it no worker delivers to user2.
  const invite = tagAdmission(tenantScopeKey, { scopeId: tenantId, kind: 'TENANT' }, 0, null)
  await orThrow('inviteTenantMember', tenantsApi.inviteTenantMember({ tenantId, memberInviteRequest: { email: u2Email, ...invite } }))
  const body = await waitForMail(u2Email, { after: cursor })
  const link = body.match(/\/tenant\/accept#([A-Za-z0-9_-]+)/)
  if (!link) throw new Error(`no invite link in the mail to ${u2Email}`)
  const u2 = await buildAccount(u2Email, u2Password, fromB64std(setup.instanceSalt), {
    secretKey: reusableSecretKey(env.VEYQ_USER2_SECRET_KEY, 'user2'),
  })
  await orThrow('user2 accept/register', new TenantsApi(new TenantsConfiguration(publicChannel())).registerAndAcceptTenantInvite({
    registerAndAcceptRequest: {
      bootstrapSecret: link[1],
      userId: crypto.randomUUID(),
      username: u2Username,
      account: {
        suiteId: u2.material.suiteId,
        identityPublicKey: u2.material.identityPublicKey,
        wrappedIdentityPrivateKey: u2.material.wrappedIdentityPrivateKey,
        authPublicKey: u2.material.authPublicKey,
        masterKeyWraps: u2.material.masterKeyWraps,
        secretKeyProofPublicKey: u2.material.secretKeyProofPublicKey,
      },
    },
  }))
  // Deliver the tenant grant: claim the grant-delivery job the backend originated for the new grant-less
  // membership (retry briefly in case origination lags), wrap K_tenant to user2's identity, complete.
  const tasksApi = new TasksApi(new TasksConfiguration(onTenant))
  let job: ClaimedJob | null | undefined = null
  for (let i = 0; i < 5 && !job?.grantDelivery; i++) {
    job = await orThrow('claimTask', tasksApi.claimTask({ claimRequest: { capabilityTags: ['02f271ff'], scopeId: tenantId } }))
    if (!job?.grantDelivery) await sleep(400)
  }
  const target = job?.grantDelivery
  if (!job || !target) throw new Error('grant-delivery job for user2 was not claimable')
  // The same checks a key-holder's worker runs: the admission verifies under K_tenant, then a signed wrap.
  if (!admissionVerifies(tenantScopeKey, 0, { scopeId: tenantId, kind: 'TENANT' }, target.targetUserId, target)) {
    throw new Error('user2\'s admission does not verify under K_tenant — refusing to wrap to it')
  }
  const signed = sealGrant(tenantScopeKey, { scopeId: tenantId, kind: 'TENANT', epoch: 0 }, { userId: target.targetUserId, identityPublicKeyB64: target.identityPublicKey }, built.identity.priv.sign)
  await orThrow('grant-delivery complete', tasksApi.completeTask({
    jobId: job.jobId,
    completeRequest: { sealedResult: signed.wrappedGrant, epoch: 0, grantSignature: signed.grantSignature },
  }))
  u2SecretKey = u2.emergencyKit.secretKey
  u2WrappedKey = u2.material.masterKeyWraps.find((w) => w.method === 'PASSWORD')!.wrappedKey
  console.log(`user2: ${u2Email} joined ${domain} as MEMBER (@${u2Username}) + tenant grant delivered`)
}
if (u2Username && u2Email && u2Password) {
  try { await onboardUser2(); persist() } catch (e) {
    console.warn(`\nbootstrap-user: user2 was NOT onboarded — ${(e as Error).message}`)
    console.warn(`  the owner below is complete and already written to ${ENV_FILE}; re-run after fixing it (needs a fresh instance).`)
  }
}

console.log(`\nbootstrap-user complete → ${ENV_FILE}`)
console.log(`  ${email} @ ${domain} (owner) — log in with VEYQ_EMAIL / VEYQ_PASSWORD / VEYQ_SECRET_KEY`)
if (u2SecretKey) console.log(`  ${u2Email} @ ${domain} (member) — log in with VEYQ_USER2_EMAIL / VEYQ_USER2_PASSWORD / VEYQ_USER2_SECRET_KEY`)
