// Fuzz seed — the instance the API fuzzing runs against (ADR 0091 decision 9; `scripts/run_api_fuzz.py` calls it).
//
// Schemathesis cannot sign in: a veyq sign-in is client-side Argon2id plus a signature over the server's nonce. So
// this births a FRESH instance with the real client crypto — the shared cold birth in `instanceBirth.mts`, the same
// one `bootstrap-user.mts` uses: the Server Owner on the admin listener, the bundled store chosen and checked, a
// tenant on the fuzzed domain accepted as that owner and sending through Mailpit, its personal scope. Then, through the
// client's own modules (signed in and unlocked as a person is), the objects the run's positive requests are fixed to:
//
//   - in a group scope of the owner's own (only the owner a member) a Memory, one medium in it (a few sealed bytes,
//     original only), a bucket, and a READ link on the Memory;
//   - a second identity, the PEER: a tenant member onboarded the product's way (an owner-minted invite read out of
//     Mailpit, a cold-birth accept), with a personal scope of its own. It owns nothing of the owner's and is a member
//     of none of the owner's scopes, so every request below the owner's scope must be refused to it as to a stranger
//     (`hooks.py` `peer_is_refused`).
//
// It hands the run:
//
//   <out>/seed.json     the credentials (throwaway accounts of a throwaway instance) and the ids: `ids` — the positive
//                       requests' fixed path parameters (`tenantId`, `userId`, `scopeId`, `nodeId`, `mediaId`,
//                       `blobId`, `bucketId`, `shareId`) — and `peer` (`userId`, `scopeId`);
//   <out>/token         the owner's access token (it lives 15 minutes, `SessionService.ACCESS_TTL_MINUTES`);
//   <out>/token-peer    the peer's.
//
// All three are written mode 0600 and NEVER printed: a run's stdout is a CI log. `--login` signs both in again from
// `seed.json` and rewrites only the tokens — the runner calls it before each invocation, because a run outlives a token.
// `--objects` makes the owner a fresh Memory, medium, bucket and link and rewrites their ids in `seed.json` — the runner
// calls it before each later positive invocation, so none reads objects an earlier one trashed, revoked or purged.
//
// Usage (from src/web/): npx tsx scripts/fuzz-seed.mts --out <dir> [--login | --objects]
// Environment: VEYQ_BASE / VEYQ_ADMIN_BASE (apiTransport.mts), VEYQ_HOST (the tenant's domain; default veyq.localhost),
// VEYQ_MAILPIT (src/test/mailpit.ts).
import './clientPrelude.mts'
import 'fake-indexeddb/auto'
import { readFileSync, writeFileSync, chmodSync } from 'node:fs'
import { join } from 'node:path'
import { buildAccount } from '@veyq/crypto/accountBirth'
import { fromB64std } from '@veyq/crypto/base64'
import { MAILPIT_SMTP_PORT, mailCursor, waitForMail } from '../src/test/mailpit'
import { Configuration as TenantsConfiguration, TenantsApi } from '../src/api/generated/tenants'
import { ScopeKind } from '../src/api/generated/scopes'
import { ShareRole } from '../src/api/generated/sharing'
import { session } from '../src/app/session'
import { clearVault } from '../src/app/vault'
import { login } from '../src/features/account/login/login'
import { unlockMasterKey } from '../src/features/account/unlock/unlock'
import { createGroup, ensurePersonalScope, openScope, openScopeById, type OpenedScope } from '../src/features/spaces/scopes'
import { inviteMember } from '../src/features/spaces/members'
import { registerAndAccept } from '../src/features/tenant/tenantInvite'
import { createMemory } from '../src/features/memories/memoryNodes'
import { addCopiedMedia } from '../src/features/memories/media'
import { createBucket } from '../src/features/memories/mediaBuckets'
import { createShare } from '../src/features/memories/share'
import { BASE, tenantChannel } from './apiTransport.mts'
import { awaitBackend, birthPersonalScope, birthServerOwner, birthTenant, ensureBundledStore, orThrow, signIn } from './instanceBirth.mts'

type Account = { email: string; password: string; secretKey: string }
type Seed = {
  owner: Account
  peer: Account
  domain: string
  ids: { tenantId: string; userId: string; scopeId: string; nodeId: string; mediaId: string; blobId: string; bucketId: string; shareId: string }
  peerIds: { userId: string; scopeId: string }
}

function fail(msg: string): never { console.error(`fuzz-seed: ${msg}`); process.exit(1) }

const args = process.argv.slice(2)
const outAt = args.indexOf('--out')
const out = outAt >= 0 ? args[outAt + 1] : undefined
if (!out) fail('--out <dir> is required')
const relogin = args.includes('--login')
const objects = args.includes('--objects')

/** Write a secret the way only its owner may read it: created 0600, and chmod'ed in case the file already existed. */
function writeSecret(path: string, body: string): void {
  writeFileSync(path, body, { mode: 0o600 })
  chmodSync(path, 0o600)
}

/** The tenant's own origin: the client modules address the tenant by its domain, as the browser does. */
function tenantBase(domain: string): string {
  const url = new URL(BASE)
  url.hostname = domain
  return url.origin
}

/** Sign in and unlock through the client's own login and its PASSWORD-floor unlock — as a person at the login page. */
async function become(a: Account): Promise<void> {
  clearVault()
  await login(a.email, a.password)
  await unlockMasterKey(a.password, a.secretKey, { noteDevice: false })
}

async function personalScope(): Promise<OpenedScope> {
  const scope = await openScope(ScopeKind.Personal)
  if (!scope) throw new Error('the signed-in account cannot open its personal scope')
  return scope
}

/** Onboard the peer as a member is onboarded: the owner's invite, its token read out of the mailbox, a cold accept. */
async function onboardPeer(tenantId: string, peer: Account, salt: Uint8Array): Promise<string> {
  const built = await buildAccount(peer.email, peer.password, salt)
  peer.secretKey = built.emergencyKit.secretKey
  const cursor = await mailCursor(peer.email)
  await inviteMember(tenantId, peer.email)
  const link = (await waitForMail(peer.email, { after: cursor })).match(/\/tenant\/accept#([A-Za-z0-9_-]+)/)
  if (!link) throw new Error(`no invite link in the mail to the peer`)
  const accepted = await registerAndAccept(link[1], crypto.randomUUID(), 'fuzzpeer', built.material)
  return accepted.userId
}

async function birth(): Promise<void> {
  const domain = (process.env.VEYQ_HOST ?? 'veyq.localhost').toLowerCase()
  const setup = await awaitBackend()
  if (!setup.setupRequired) fail('the instance is already set up — the fuzzing runs against a FRESH one')
  // Random per birth and never printed: the accounts are throwaway, their passwords still nobody's to read in a log.
  const owner: Account = { email: 'fuzz-owner@fuzz.test', password: `Fz-${crypto.randomUUID()}-9x!`, secretKey: '' }
  const peer: Account = { email: 'fuzz-peer@fuzz.test', password: `Fz-${crypto.randomUUID()}-9y!`, secretKey: '' }
  const built = await birthServerOwner(owner.email, owner.password, setup)
  owner.secretKey = built.emergencyKit.secretKey
  const token = await signIn(owner.email, owner.password)
  await ensureBundledStore(token)
  const tenant = await birthTenant(token, built, { tenantName: 'Fuzz', domain, email: owner.email, username: 'fuzzowner' })
  await birthPersonalScope(token, domain, tenant.ownerId, built)
  // The tenant sends through the run's Mailpit, as every e2e suite's does (`e2eBootstrap.birthInstance`): an
  // instance that cannot mail answers `503 mail_unavailable` to every operation that mails, which is a statement about
  // the instance, not about the API — and the peer's invite travels by mail only. `username: null` keeps it off the
  // sealed-password path (no config-secret KEK).
  await orThrow('updateTenantMailSettings', new TenantsApi(new TenantsConfiguration(tenantChannel(domain, token))).updateTenantMailSettings({
    tenantId: tenant.tenantId,
    tenantMailSettingsUpdate: { host: domain, port: MAILPIT_SMTP_PORT, from: `noreply@${domain}`, username: null, tls: false, enabled: true },
  }))

  // --- the owner's objects, through the client's own modules ------------------------------------------------------
  session.apiBase = tenantBase(domain)
  await become(owner)
  const made = await ownerObjects()

  // --- the peer: a member of the tenant, of nothing of the owner's --------------------------------------------------
  const peerUserId = await onboardPeer(tenant.tenantId, peer, fromB64std(setup.instanceSalt))
  await become(peer)
  await ensurePersonalScope()
  const peerScope = await personalScope()
  clearVault()

  const seed: Seed = {
    owner, peer, domain,
    ids: { tenantId: tenant.tenantId, userId: tenant.ownerId, ...made },
    peerIds: { userId: peerUserId, scopeId: peerScope.scopeId },
  }
  writeSecret(join(out!, 'seed.json'), `${JSON.stringify(seed, null, 2)}\n`)
  await writeTokens(seed)
  console.log(`fuzz-seed: instance born (owner, tenant ${domain}, a Memory with a medium, a bucket and a link; a peer member) — seed and tokens written to ${out}`)
}

/** For the signed-in owner, in a GROUP scope of its own that only the owner is a member of: a Memory, one medium in it (a
 *  few sealed bytes as the original, no tier — the server never sees more than ciphertext of any of them), a bucket, and
 *  a READ link on the Memory. A group per call, not the personal scope: a positive write may rotate the scope it is fixed
 *  to with keys nobody holds (the server cannot tell them from real ones), after which nobody opens that scope again —
 *  measured, the second `--objects` of a run could no longer open the personal scope the first run's writes rotated. */
async function ownerObjects(): Promise<Pick<Seed['ids'], 'scopeId' | 'nodeId' | 'mediaId' | 'blobId' | 'bucketId' | 'shareId'>> {
  // A name of its own per call: names are unique among a parent's children (`409 slug_taken`).
  const name = `Fuzz ${crypto.randomUUID().slice(0, 8)}`
  const groupId = await createGroup(name)
  const own = await openScopeById(groupId)
  if (!own) throw new Error('the owner cannot open the group it just created')
  const memory = await createMemory(own.scopeId, own.epochs, name)
  const bytes = new Uint8Array(4096).map((_, i) => (i * 31 + 7) % 251)
  const medium = await addCopiedMedia(own.scopeId, memory.nodeId, memory.memoryKey, {
    original: { source: new Blob([bytes as BlobPart], { type: 'image/jpeg' }), meta: { type: 'image/jpeg', name: 'fuzz.jpg' } },
    tiers: [],
    meta: null,
    exif: null,
  })
  const bucket = await createBucket(own.scopeId, memory.nodeId, memory.memoryKey, 'Fuzz bucket')
  const share = await createShare(own.scopeId, memory.nodeId, memory.memoryKey, own.epochs, name, ShareRole.Read)
  return {
    scopeId: own.scopeId, nodeId: memory.nodeId, mediaId: medium.mediaId, blobId: medium.blobId,
    bucketId: bucket.bucketId, shareId: share.shareId,
  }
}

/** Fresh objects for the owner, their ids rewritten in `seed.json` (the rest of it as it was). */
async function renewObjects(seed: Seed): Promise<void> {
  session.apiBase = tenantBase(seed.domain)
  await become(seed.owner)
  const made = await ownerObjects()
  clearVault()
  writeSecret(join(out!, 'seed.json'), `${JSON.stringify({ ...seed, ids: { ...seed.ids, ...made } }, null, 2)}\n`)
}

async function writeTokens(seed: Seed): Promise<void> {
  writeSecret(join(out!, 'token'), await signIn(seed.owner.email, seed.owner.password))
  writeSecret(join(out!, 'token-peer'), await signIn(seed.peer.email, seed.peer.password))
}

try {
  if (relogin) {
    await writeTokens(JSON.parse(readFileSync(join(out, 'seed.json'), 'utf8')) as Seed)
    console.log('fuzz-seed: signed in again, tokens rewritten')
  } else if (objects) {
    await renewObjects(JSON.parse(readFileSync(join(out, 'seed.json'), 'utf8')) as Seed)
    console.log('fuzz-seed: fresh objects for the owner, their ids rewritten')
  } else {
    await birth()
  }
} catch (e) {
  const response = (e as { response?: unknown } | null)?.response
  fail(response instanceof Response ? `${response.status} ${await response.text().catch(() => '')}` : (e as Error).message)
}
process.exit(0)
