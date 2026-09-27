// seed:dev (cr-0046) — one command to bring up a reproducible local instance and persist it (credentials +
// raw key material) to web/scripts/.seed/dev-seed.json: a Server Owner + a 127.0.0.1 tenant owner WITH its
// personal scope. Run against a FRESH instance:
//
//   docker compose -f infra/docker-compose.yml down -v && docker compose -f infra/docker-compose.yml up -d
//   # start the backend with the dev-invite file, e.g.:
//   VEYQ_TENANT_INVITE_DEV_FILE=$PWD/backend/build/dev-invites.jsonl ./gradlew -p backend quarkusDev
//   cd web && npm run seed:dev
//
// A harness then loads it via scripts/devSeed.mts → loadDevSeed()/vaultFromSeed() (login+unlock OR vault inject).
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std, b64std } from '@veyq/crypto/base64'
import { sealGrant } from '../src/features/spaces/grant'
import { readSeed, writeTenant, removeTenant, seedFilePath, type SeedEntry } from './devSeed.mts'

const BASE = process.env.VEYQ_BASE ?? 'http://localhost:8080'
// The web dev server presents Host `127.0.0.1` to the backend, so the dev tenant must resolve at that Host.
// But `POST /api/tenants` rejects a numeric domain (it requires a hostname with a letter TLD), so we create
// the tenant under a hostname and then remap its domain to 127.0.0.1 directly in the dev DB (docker exec) —
// automating the manual workaround the native/web shells used.
const DOMAIN = process.env.VEYQ_DOMAIN ?? '127.0.0.1'
const CREATE_DOMAIN = process.env.VEYQ_CREATE_DOMAIN ?? 'local.veyq.test'
const DB_CONTAINER = process.env.VEYQ_DB_CONTAINER ?? 'veyq-mariadb'
// Scope listing/creation resolves the active tenant from Host, so address the backend by the final domain.
const HOST_BASE = process.env.VEYQ_HOST_BASE ?? `http://${DOMAIN}:8080`
const here = (f: string) => fileURLToPath(new URL(f, import.meta.url))

const json = (r: Response) => r.json() as Promise<any>
const post = (path: string, body: unknown, token: string | undefined, base: string) =>
  fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
  })
const get = (path: string, token: string, base: string) =>
  fetch(`${base}${path}`, { headers: { authorization: `Bearer ${token}` } })

function runScript(script: string, env: Record<string, string> = {}): void {
  execFileSync('npx', ['tsx', here(script)], { stdio: 'inherit', env: { ...process.env, ...env } })
}

// 1 — Server Owner (only when the instance is fresh; seed-owner refuses an already-set-up instance)
const setup = await json(await fetch(`${BASE}/api/setup`))
if (setup.setupRequired) runScript('seed-owner.mts')
else console.log('owner: instance already set up — reusing the existing Server Owner')

// 2 — the tenant owner (created under a hostname the API accepts; writes its entry + raw keys to the seed file)
runScript('seed-tenant.mts', { VEYQ_DOMAIN: CREATE_DOMAIN })

// 2b — remap the tenant's domain to the web dev Host (127.0.0.1) directly in the dev DB, then re-key the entry
let entry: SeedEntry | undefined = readSeed(BASE).tenants[CREATE_DOMAIN]
if (!entry) { console.error('seed-tenant did not persist the tenant entry'); process.exit(1) }
if (CREATE_DOMAIN !== DOMAIN) {
  execFileSync('docker', [
    'exec', DB_CONTAINER, 'mariadb', '-uveyq', '-pchange-me', 'veyq',
    '-e', `UPDATE veyq_tenant SET domain='${DOMAIN}' WHERE id='${entry.tenantId}'`,
  ], { stdio: 'inherit' })
  entry = { ...entry, domain: DOMAIN }
}

// 3 — ensure the tenant owner's PERSONAL scope (Host-resolved → address by the final domain)

const ch = await json(await post('/api/login/challenge', { email: entry.email }, undefined, HOST_BASE))
const auth = v1.deriveAuthKeypair(entry.password, v1.deriveAuthSalt(fromB64std(ch.instanceSalt), entry.email), v1.ARGON)
const sig = v1.authSign(auth.privateKey, fromB64std(ch.nonce))
const verify = await post('/api/login/verify', { email: entry.email, nonce: ch.nonce, signature: b64std(sig) }, undefined, HOST_BASE)
if (!verify.ok) { console.error('tenant-owner login failed', verify.status, await verify.text()); process.exit(1) }
const token = (await json(verify)).accessToken as string

let scopes = await json(await get('/api/scopes', token, HOST_BASE))
let personal = scopes.find((s: { kind: string }) => s.kind === 'PERSONAL')
if (!personal) {
  const priv = v1.deserializeIdentityPrivate(fromB64std(entry.identityPrivate))
  const scopeKey = v1.randomBytes(32)
  const scopeId = crypto.randomUUID() // client-minted: the grant is bound to it at epoch 0 (ADR 0072)
  // Self-signed, kind PERSONAL (ADR 0028 Decision 6) — the only personal grant the client will open.
  const me = (await json(await get('/api/account/key-material', token, HOST_BASE))).userId as string
  const pub = b64std(v1.serializeIdentityPublic(v1.identityPublicFromPrivate(priv)))
  const grant = sealGrant(scopeKey, { scopeId, kind: 'PERSONAL', epoch: 0 }, { userId: me, identityPublicKeyB64: pub }, priv.sign)
  const created = await post('/api/scopes', { scopeId, ...grant }, token, HOST_BASE)
  if (!created.ok) { console.error('createPersonalScope failed', created.status, await created.text()); process.exit(1) }
  scopes = await json(await get('/api/scopes', token, HOST_BASE))
  personal = scopes.find((s: { kind: string }) => s.kind === 'PERSONAL')
}
writeTenant(BASE, DOMAIN, { ...entry, scopeId: personal.id })
if (CREATE_DOMAIN !== DOMAIN) removeTenant(BASE, CREATE_DOMAIN) // drop the transient creation-hostname entry

console.log(`\nseed:dev complete → ${seedFilePath}`)
console.log(`  tenant: ${entry.email} @ ${DOMAIN}  (scope ${personal.id})`)
