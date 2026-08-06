// Bootstrap User — create the local dev principal from .run/.env and record its derived key material back into
// the same file. Reuses the real cold-account-birth crypto (buildAccount) + the existing endpoints, so it is
// operator-blind: only ciphertext + public keys are POSTed; the password never leaves this process.
//
// The human fills the inputs in .run/.env (copy from .run/.env.example):
//   VEYQ_USERNAME, VEYQ_PASSWORD, VEYQ_EMAIL, VEYQ_TENANT, VEYQ_TENANT_DOMAIN, and the optional
//   VEYQ_FIRST_NAME / VEYQ_LAST_NAME (the real name, sealed under K_tenant at accept — operator-blind).
// This script creates the Server-Owner / tenant-owner veyq_user (with a personal scope), and — when the optional
// VEYQ_USER2_* inputs are set — a SECOND user onboarded as a tenant MEMBER with its tenant grant delivered (a
// ready 2-member tenant for multi-member dev). It writes the derived key material back:
//   VEYQ_SECRET_KEY, VEYQ_WRAPPED_MASTERKEY (+ VEYQ_USER2_SECRET_KEY / VEYQ_USER2_WRAPPED_MASTERKEY)
// Run it against a FRESH backend (Bootstrap DB + Garage → Run Backend first). Re-run needs a `down -v` wipe:
// the Secret Key is random per birth, so a second run can't reproduce an existing account.
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { request as httpRequest } from 'node:http'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'
import * as v1 from '../src/shared/crypto/v1'
import { fromB64std, b64std } from '../src/shared/base64'
import { buildAccount } from '../src/features/account/register/accountBirth'
import { sealName } from '../src/features/account/profile/memberName'
import { wrapScopeKeyToMember } from '../src/features/spaces/grant'
import { awaitInvite } from './devSeed.mts'

const ENV_FILE = fileURLToPath(new URL('../../.run/.env', import.meta.url))
const BASE = process.env.VEYQ_BASE ?? 'http://localhost:8080'
const DB_CONTAINER = process.env.VEYQ_DB_CONTAINER ?? 'veyq-mariadb'
const INVITE_FILE = process.env.VEYQ_INVITE_FILE ?? fileURLToPath(new URL('../../backend/build/dev-invites.jsonl', import.meta.url))

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

// --- HTTP: plain fetch for non-tenant calls; node:http for tenant-scoped (fetch drops a manual Host header) -
const json = (r: Response) => r.json() as Promise<any>
const post = (path: string, b: unknown, token?: string) =>
  fetch(`${BASE}${path}`, { method: 'POST', headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(b) })
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const backendPort = new URL(BASE).port || '80'

/** A tenant-scoped call over node:http (honours an explicit Host, which fetch/undici strips). */
function tenant(method: string, path: string, token: string, host: string, body?: unknown): Promise<{ status: number; body: any }> {
  const data = body === undefined ? undefined : JSON.stringify(body)
  return new Promise((resolve, reject) => {
    const req = httpRequest({
      host: '127.0.0.1', port: backendPort, path, method,
      headers: { host, authorization: `Bearer ${token}`, ...(data ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(data) } : {}) },
    }, (res) => {
      let buf = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (buf += c))
      res.on('end', () => resolve({ status: res.statusCode ?? 0, body: buf ? JSON.parse(buf) : undefined }))
    })
    req.on('error', reject)
    if (data) req.write(data)
    req.end()
  })
}

async function awaitBackend(timeoutMs = 90_000): Promise<any> {
  const deadline = Date.now() + timeoutMs
  let last = ''
  while (Date.now() < deadline) {
    try { const r = await fetch(`${BASE}/api/setup`); if (r.ok) return await json(r); last = `status ${r.status}` }
    catch (e) { last = (e as Error).message }
    await sleep(750)
  }
  fail(`backend did not answer ${BASE}/api/setup within ${timeoutMs}ms (${last})`)
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

const numeric = /^[0-9.]+$/.test(domain)
const createDomain = numeric ? (process.env.VEYQ_CREATE_DOMAIN ?? 'local.veyq.test') : domain

const setup = await awaitBackend()
if (!setup.setupRequired) fail('instance already set up — wipe it first (Bootstrap DB + Garage: `docker compose … down -v`), the Secret Key is random per birth')

// 1 — cold-birth the Server Owner
const built = buildAccount(email, password, fromB64std(setup.instanceSalt))
const m = built.material
const ownerRes = await post('/api/setup/owner', {
  email,
  suiteId: m.suiteId, identityPublicKey: m.identityPublicKey, wrappedIdentityPrivateKey: m.wrappedIdentityPrivateKey,
  authPublicKey: m.authPublicKey, masterKeyWraps: m.masterKeyWraps, recoveryMasterKeyTag: m.recoveryMasterKeyTag,
})
if (!ownerRes.ok) fail(`setup/owner failed: ${ownerRes.status} ${await ownerRes.text()}`)
console.log(`owner: created ${email}`)

// 2 — log in (account-level token)
const ch = await json(await post('/api/login/challenge', { email }))
const auth = v1.deriveAuthKeypair(password, v1.deriveAuthSalt(fromB64std(ch.instanceSalt), email), v1.ARGON)
const verify = await post('/api/login/verify', { email, signature: b64std(v1.authSign(auth.privateKey, fromB64std(ch.nonce))) })
if (!verify.ok) fail(`login failed: ${verify.status} ${await verify.text()}`)
const token = (await json(verify)).accessToken as string

// 3 — the configured-domain tenant, accepted as the existing user (TENANT grant sealed to our own identity)
const created = await post('/api/tenants', { tenantName, domain: createDomain, ownerEmail: email }, token)
if (!created.ok) fail(`createTenant failed: ${created.status} ${await created.text()}`)
const { tenantId } = await json(created)
const bootstrapSecret = await awaitInvite(INVITE_FILE, email)
if (numeric) {
  execFileSync('docker', ['exec', DB_CONTAINER, 'mariadb', '-uveyq', '-pchange-me', 'veyq',
    '-e', `UPDATE veyq_tenant SET domain='${domain}' WHERE id='${tenantId}'`], { stdio: 'inherit' })
}
// Keep the freshly-minted TENANT scope key (K_tenant): the grant wraps it, and — holding it here — we can seal
// the optional real name at accept, exactly as TenantJoinView does at an owner cold-birth (cr-0106).
const tenantScopeKey = v1.randomBytes(32)
const tenantGrant = b64std(v1.asymWrap(v1.hybridPublic(built.identity.priv), tenantScopeKey, 'scope-grant', { nonce: v1.randomBytes(12) }))
const sealedName = firstName || lastName ? sealName(tenantScopeKey, { firstName, lastName }) : undefined
const accepted = await tenant('POST', '/api/tenants/accept', token, domain, { bootstrapSecret, username, grant: tenantGrant, ...(sealedName ? { sealedName } : {}) })
if (accepted.status >= 300) fail(`accept failed: ${accepted.status} ${JSON.stringify(accepted.body)}`)
console.log(`tenant: ${domain} created + accepted${sealedName ? ' + name sealed' : ''}`)

// 4 — the owner's PERSONAL scope
const personalGrant = b64std(v1.asymWrap(v1.hybridPublic(built.identity.priv), v1.randomBytes(32), 'scope-grant', { nonce: v1.randomBytes(12) }))
const scopeRes = await tenant('POST', '/api/scopes', token, domain, { wrappedGrant: personalGrant })
if (scopeRes.status >= 300) fail(`createPersonalScope failed: ${scopeRes.status} ${JSON.stringify(scopeRes.body)}`)
console.log(`scope: personal scope ${scopeRes.body?.scopeId ?? ''} created`)

// 4b — optional SECOND user: a tenant MEMBER, fully granted so it is usable immediately (no "log the owner in
// first" step). The owner mints a MEMBER invite; user2 cold-births + register-and-accepts grant-less; then — as
// the online key-holder — we drain the grant-delivery job the backend originated (claim → wrap K_tenant to
// user2's identity → complete), exactly what a key-holder's device does in the web client (cr-0076).
let u2SecretKey = ''
let u2WrappedKey = ''
if (u2Username && u2Email && u2Password) {
  const inv = await tenant('POST', `/api/tenants/${tenantId}/member-invites`, token, domain, { email: u2Email })
  if (inv.status >= 300) fail(`inviteTenantMember failed: ${inv.status} ${JSON.stringify(inv.body)}`)
  const u2Secret = await awaitInvite(INVITE_FILE, u2Email)
  const u2 = buildAccount(u2Email, u2Password, fromB64std(setup.instanceSalt))
  const u2Accept = await post('/api/tenants/accept/register', {
    bootstrapSecret: u2Secret,
    username: u2Username,
    account: {
      suiteId: u2.material.suiteId,
      identityPublicKey: u2.material.identityPublicKey,
      wrappedIdentityPrivateKey: u2.material.wrappedIdentityPrivateKey,
      authPublicKey: u2.material.authPublicKey,
      masterKeyWraps: u2.material.masterKeyWraps,
      recoveryMasterKeyTag: u2.material.recoveryMasterKeyTag,
    },
  })
  if (!u2Accept.ok) fail(`user2 accept/register failed: ${u2Accept.status} ${await u2Accept.text()}`)
  // Deliver the tenant grant: claim the grant-delivery job the backend originated for the new grant-less
  // membership (retry briefly in case origination lags), wrap K_tenant to user2's identity, complete.
  let job: any = null
  for (let i = 0; i < 5 && !job; i++) {
    const claim = await tenant('POST', '/api/tasks/claim', token, domain, { capabilityTags: ['02f271ff'], scopeId: tenantId })
    if (claim.status === 200 && claim.body?.grantDelivery) job = claim.body
    else await sleep(400)
  }
  if (!job) fail('grant-delivery job for user2 was not claimable')
  const wrap = wrapScopeKeyToMember(tenantScopeKey, job.grantDelivery.identityPublicKey)
  const done = await tenant('POST', `/api/tasks/${job.jobId}/complete`, token, domain, { sealedResult: wrap })
  if (done.status >= 300) fail(`grant-delivery complete failed: ${done.status} ${JSON.stringify(done.body)}`)
  u2SecretKey = u2.emergencyKit.secretKey
  u2WrappedKey = u2.material.masterKeyWraps.find((w) => w.method === 'PASSWORD')!.wrappedKey
  console.log(`user2: ${u2Email} joined ${domain} as MEMBER (@${u2Username}) + tenant grant delivered`)
}

// 5 — record the derived material back into .run/.env
const passwordWrap = m.masterKeyWraps.find((w) => w.method === 'PASSWORD')!
writeEnv({
  VEYQ_USERNAME: username, VEYQ_EMAIL: email, VEYQ_PASSWORD: password,
  VEYQ_FIRST_NAME: firstName, VEYQ_LAST_NAME: lastName,
  VEYQ_SECRET_KEY: built.emergencyKit.secretKey, VEYQ_WRAPPED_MASTERKEY: passwordWrap.wrappedKey,
  VEYQ_TENANT: tenantName, VEYQ_TENANT_DOMAIN: domain,
  VEYQ_USER2_USERNAME: u2Username, VEYQ_USER2_EMAIL: u2Email, VEYQ_USER2_PASSWORD: u2Password,
  VEYQ_USER2_SECRET_KEY: u2SecretKey, VEYQ_USER2_WRAPPED_MASTERKEY: u2WrappedKey,
})
console.log(`\nbootstrap-user complete → ${ENV_FILE}`)
console.log(`  ${email} @ ${domain} (owner) — log in with VEYQ_EMAIL / VEYQ_PASSWORD / VEYQ_SECRET_KEY`)
if (u2SecretKey) console.log(`  ${u2Email} @ ${domain} (member) — log in with VEYQ_USER2_EMAIL / VEYQ_USER2_PASSWORD / VEYQ_USER2_SECRET_KEY`)
