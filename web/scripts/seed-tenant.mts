// Local dev seeder (cr-0042): mint a tenant for a given Host domain + a fresh tenant-owner account with a
// KNOWN password + Secret Key, so the native/web clients can reach the Memory shell against a local instance
// (the personal scope is tenant-scoped by Host — see web/CLAUDE.md). Closes the seed-tenant gap flagged in
// cr-0039's retrospective. Operator-blind: only ciphertext + public keys are POSTed; no password leaves here.
//
// Flow (Server-Owner must already exist — run seed-owner.mts first):
//   1. log in as the Server Owner (challenge-response, Model B auth) to get an admin session,
//   2. POST /api/tenants → a PENDING tenant for `domain`, inviting `ownerEmail` (the dev mailer appends the
//      bootstrap secret to veyq.tenant.invite.dev-file — we read it back from there, cr-0046),
//   3. cold-birth the tenant-owner account + seal the first scope grant to its own identity, and
//      POST /api/tenants/accept/register → activates the tenant (ACTIVE) with the OWNER membership.
//
// Persists the result (creds + raw key material) into web/scripts/.seed/dev-seed.json (cr-0046).
//
//   cd web && npx tsx scripts/seed-tenant.mts   # backend must run with veyq.tenant.invite.dev-file set
//
// Env: VEYQ_BASE (http://localhost:8080), VEYQ_ADMIN_BASE (http://127.0.0.1:8090), VEYQ_OWNER_EMAIL/PASSWORD (the Server Owner, default the
// seed-owner values), VEYQ_DOMAIN (127.0.0.1 — must be a dotted hostname; `localhost` is rejected by the
// backend's mailer hostname guard), VEYQ_TENANT_OWNER_EMAIL/PASSWORD/NAME (the new tenant owner),
// VEYQ_INVITE_FILE (the mailer's dev-invite file; default ../backend/build/dev-invites.jsonl).
import { fileURLToPath } from 'node:url'
import { fromB64std, b64std } from '@veyq/crypto/base64'
import { buildAccount } from '@veyq/crypto/accountBirth'
import * as v1 from '@veyq/crypto/v1'
import { sealGrant, tagAdmission } from '../src/features/spaces/grant'
import { writeTenant, materializeKeys, awaitInvite } from './devSeed.mts'

const BASE = process.env.VEYQ_BASE ?? 'http://localhost:8080'
// The backend's admin listener: the only door the Server-Owner operations answer on — the public port 404s them
// (ADR 0065). 8090 is its dev default (veyq.admin-channel.port).
const ADMIN_BASE = process.env.VEYQ_ADMIN_BASE ?? 'http://127.0.0.1:8090'
const ownerEmail = process.env.VEYQ_OWNER_EMAIL ?? 'kevin@veyq.test'
const ownerPassword = process.env.VEYQ_OWNER_PASSWORD ?? 'correct horse battery staple'
const domain = process.env.VEYQ_DOMAIN ?? '127.0.0.1'
const tenantOwnerEmail = process.env.VEYQ_TENANT_OWNER_EMAIL ?? 'owner@veyq.test'
const tenantOwnerPassword = process.env.VEYQ_TENANT_OWNER_PASSWORD ?? 'correct horse battery staple'
const tenantName = process.env.VEYQ_TENANT_NAME ?? 'local'
// The mailer's dev-invite file (veyq.tenant.invite.dev-file), default the backend's build/ path (cr-0046).
const inviteFile = process.env.VEYQ_INVITE_FILE ?? fileURLToPath(new URL('../../backend/build/dev-invites.jsonl', import.meta.url))

const json = (r: Response) => r.json() as Promise<any>
const post = (path: string, body: unknown, token?: string, base = BASE) =>
  fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
  })

// 1 — log in as the Server Owner (sync argon — no Web Worker needed off the main app).
const challenge = await json(await post('/api/login/challenge', { email: ownerEmail }))
const instanceSalt = fromB64std(challenge.instanceSalt)
const auth = v1.deriveAuthKeypair(ownerPassword, v1.deriveAuthSalt(instanceSalt, ownerEmail), v1.ARGON)
const signature = v1.authSign(auth.privateKey, fromB64std(challenge.nonce))
const verify = await post('/api/login/verify', { email: ownerEmail, nonce: challenge.nonce, signature: b64std(signature) })
if (!verify.ok) { console.error('server-owner login failed', verify.status, await verify.text()); process.exit(1) }
const token = (await json(verify)).accessToken as string

// 2 — create a PENDING tenant for the Host domain, inviting the new owner.
const created = await post('/api/tenants', { tenantName, domain, ownerEmail: tenantOwnerEmail }, token, ADMIN_BASE)
if (!created.ok) { console.error('createTenant failed', created.status, await created.text()); process.exit(1) }
const { tenantId } = await json(created)

// Read the bootstrap secret from the dev-invite file the mailer appends to (veyq.tenant.invite.dev-file) —
// deterministic, no console-log scrape / ANSI / flush race (cr-0046). Polls until the line appears.
const bootstrapSecret = await awaitInvite(inviteFile, tenantOwnerEmail)

// 3 — cold-birth the tenant owner + seal the first scope grant to its own identity, then accept (public).
const built = await buildAccount(tenantOwnerEmail, tenantOwnerPassword, instanceSalt)
const scopeRootKey = v1.randomBytes(32)
// Bound to the tenant scope (its id IS the tenant id) at the birth epoch 0 (ADR 0072); the account id is minted here.
// The birth is signed by the owner's identity and self-tags the owner's admission (ADR 0028 D6, ADR 0020 move 6).
const ownerId = crypto.randomUUID()
const birthGrant = sealGrant(
  scopeRootKey, { scopeId: tenantId, kind: 'TENANT', epoch: 0 },
  { userId: ownerId, identityPublicKeyB64: built.material.identityPublicKey }, built.identity.priv.sign,
)
const birth = {
  grant: birthGrant.wrappedGrant,
  grantSignature: birthGrant.grantSignature,
  ...tagAdmission(scopeRootKey, { scopeId: tenantId, kind: 'TENANT' }, 0, null),
}
const accepted = await post('/api/tenants/accept/register', {
  bootstrapSecret,
  userId: ownerId,
  username: (process.env.VEYQ_TENANT_OWNER_USERNAME ?? tenantOwnerEmail.split('@')[0]).toLowerCase(),
  account: {
    suiteId: built.material.suiteId,
    identityPublicKey: built.material.identityPublicKey,
    wrappedIdentityPrivateKey: built.material.wrappedIdentityPrivateKey,
    authPublicKey: built.material.authPublicKey,
    masterKeyWraps: built.material.masterKeyWraps,
    secretKeyProofPublicKey: built.material.secretKeyProofPublicKey,
  },
  ...birth,
})
if (!accepted.ok) { console.error('accept/register failed', accepted.status, await accepted.text()); process.exit(1) }

// Persist the seed (creds + raw keys) so a harness can reuse this tenant owner without re-seeding (cr-0046).
writeTenant(BASE, domain, {
  email: tenantOwnerEmail, password: tenantOwnerPassword, secretKey: built.emergencyKit.secretKey,
  ...materializeKeys(built, tenantOwnerPassword), tenantId, domain,
})

console.log(JSON.stringify({
  ok: true, tenantId, domain, base: BASE, seedFile: true,
  email: tenantOwnerEmail, password: tenantOwnerPassword, secretKey: built.emergencyKit.secretKey,
}, null, 2))
