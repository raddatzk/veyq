// The volume seeder (cr-volume-measurement-local): fills a THROWAWAY production-recipe instance with a household's
// volume along the three lists the client loads whole — photos in one Chapter, nodes in one space, members of one
// tenant — through the app's own client code, so everything is sealed by the real crypto and every member is a real
// account onboarded by the product's own invite flow. Nothing is written to the database or the store by hand: every
// key is wrapped with AAD bound to its address, so a copied row would not even decrypt.
//
// TEMPORARY: `cr-volume-measurement-cax` deletes this file after its run, with the rest of the harness.
//
// It is driven by `scripts/measure_volume.py`, which boots the instance and hands over (environment):
//   VEYQ_HOST, VEYQ_BASE         the instance's domain and `https://<domain>` (e2eBootstrap reads both)
//   VEYQ_ADMIN_BASE              the admin console's port on this host (its nginx proxies the Server-Owner API)
//   VEYQ_MAILPIT                 the instance's mailbox API on this host (a member invite travels by mail only)
//   VEYQ_STAMP                   short and alphanumeric: the actors' addresses and passwords derive from it
//   VEYQ_MEASURE_DIR             the instance directory: `pebble-root.pem`, `fixtures/`, and what this writes —
//                                `accounts.json` (generated test credentials) and `manifest.json` (where things are)
//   VEYQ_MEASURE_CONNECT         where the domain is reached, `127.0.0.1:443` locally
//   VEYQ_MEASURE_SMTP            the mail relay as the APP reaches it, `mail.relay.test:1025`
//
//   seed-volume.mts birth                     first-run setup of a FRESH instance (refused on any other)
//   seed-volume.mts photos --target N         the 'All photos' Chapter in the tenant space, to exactly N media
//   seed-volume.mts photos --bare --target N  the 'No derivatives' Chapter: media with no derivative tier
//   seed-volume.mts tree --target N           the 'Volume tree' group, realistic shape, to exactly N nodes
//   seed-volume.mts tree --wide N             the 'Volume wide' group: one Memory holding N Chapters
//   seed-volume.mts members --target N        the tenant, to exactly N members (owner included), each complete
//   seed-volume.mts upload --count N          scenario: the first member uploads N phone-sized photos, one at a time
//
// Every seeder is told a target, counts what is there and fills the gap, so a stopped run resumes and the next stage
// only adds.
import './volumePrelude.mts'
import 'fake-indexeddb/auto'
import { readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { buildAccount } from '@veyq/crypto/accountBirth'
import { fromB64std } from '@veyq/crypto/base64'
import { DIR, accounts, die, manifest, need, signIn, tenantScope, writeJson, type Accounts, type Manifest, type Member } from './volumeHarness.mts'
import { birthInstance, HOST, mkActor } from '../src/test/e2eBootstrap'
import { mailCursor, waitForMail } from '../src/test/mailpit'
import { setupApi } from '../src/api/client'
import { updateTenantMailSettings } from '../src/features/tenant/tenantMailSettings'
import { registerAndAccept } from '../src/features/tenant/tenantInvite'
import { inviteMember, listScopeMembers } from '../src/features/spaces/members'
import { createGroup, listMyGroups, openScopeById, type OpenedScope } from '../src/features/spaces/scopes'
import { ScopeKind } from '../src/api/generated/scopes'
import { createContentNode, listTree, nodeContentKey, type TreeNode, type NodeParent } from '../src/core/node/tree'
import { FOLDER_KIND } from '../src/core/node/kind'
import { hashForNode, TENANT_SCOPE } from '../src/core/node/location'
import { registry } from '../src/core/applications/registry'
import { MEMORY_KIND, CHAPTER_KIND } from '../src/features/memories/kinds'
import { createChapter, createMemory } from '../src/features/memories/memoryNodes'
import { addCopiedMedia, listMedia, type CopiedMediaSource } from '../src/features/memories/media'
import { runGrantDeliveryWorker } from '../src/features/tasks/grantDeliveryWorker'
import { openOwnNameKey, sealAvatar, sealName, setMyAvatar, updateMyProfile } from '../src/features/account/profile/profile'
import { httpStatus } from '../src/shared/httpError'
import { backingOff } from './backingOff.mts'

// The Memories application's route slug, as its registration (`features/memories/register.ts`, which imports Vue
// components and so cannot load here) claims it — only so `hashForNode` builds the URL the browser leg opens.
registry.claimRouteSlug('memories', [MEMORY_KIND, CHAPTER_KIND])

function remember(update: Partial<Manifest>): void {
  writeJson('manifest.json', { ...manifest(), ...update })
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Run `count` jobs with at most `width` in flight; the first failure stops the run. */
async function pool(count: number, width: number, job: (i: number) => Promise<void>): Promise<void> {
  let next = 0
  let failed: unknown = null
  const lane = async () => {
    while (failed === null && next < count) {
      const i = next++
      try { await job(i) } catch (e) { failed ??= e }
    }
  }
  await Promise.all(Array.from({ length: Math.min(width, count) }, lane))
  if (failed !== null) throw failed
}

function progress(what: string, unit: string) {
  const started = Date.now()
  let done = 0
  return {
    tick(): void {
      done++
      if (done % 500 === 0) {
        const perMin = (done / ((Date.now() - started) / 60_000)).toFixed(0)
        console.log(`${what}: ${done} added, ${perMin} ${unit} per minute`)
      }
    },
    end(): string {
      const mins = (Date.now() - started) / 60_000
      return `${done} in ${mins.toFixed(1)} min (${(done / Math.max(mins, 1e-9)).toFixed(1)} ${unit} per minute)`
    },
  }
}

function args(): { cmd: string; flags: Map<string, string | true> } {
  const [cmd = '', ...rest] = process.argv.slice(2)
  const flags = new Map<string, string | true>()
  for (let i = 0; i < rest.length; i++) {
    const key = rest[i]
    if (!key.startsWith('--')) die(`unexpected argument ${key}`)
    const value = rest[i + 1] && !rest[i + 1].startsWith('--') ? rest[++i] : true
    flags.set(key.slice(2), value)
  }
  return { cmd, flags }
}
function intFlag(flags: Map<string, string | true>, name: string): number {
  const v = flags.get(name)
  const n = typeof v === 'string' ? Number(v) : NaN
  if (!Number.isInteger(n) || n < 0) die(`--${name} <non-negative integer> is required`)
  return n
}

// --- the tree helpers: find or create by name -----------------------------------------------------------------------

function childNamed(tree: TreeNode[], parentId: string | null, kind: string, name: string): TreeNode | undefined {
  return tree.find((n) => n.parentId === parentId && n.kind === kind && n.name === name)
}

async function ensureMemoryAndChapter(scope: OpenedScope, memoryName: string, chapterName: string): Promise<{ chapterId: string; chapterKey: Uint8Array; chapterEpoch: number; hash: string }> {
  let tree = await listTree(scope.scopeId, scope.epochs)
  let memory = childNamed(tree, null, MEMORY_KIND, memoryName)
  if (!memory) {
    await createMemory(scope.scopeId, scope.epochs, memoryName)
    tree = await listTree(scope.scopeId, scope.epochs)
    memory = childNamed(tree, null, MEMORY_KIND, memoryName)!
  }
  let chapter = childNamed(tree, memory.id, CHAPTER_KIND, chapterName)
  if (!chapter) {
    const memoryKey = nodeContentKey(scope.scopeId, scope.epochs, tree, memory.id)!
    await createChapter(scope.scopeId, scope.epochs, memoryKey, chapterName, memory.id, memory.epoch)
    tree = await listTree(scope.scopeId, scope.epochs)
    chapter = childNamed(tree, memory.id, CHAPTER_KIND, chapterName)!
  }
  return {
    chapterId: chapter.id,
    chapterKey: nodeContentKey(scope.scopeId, scope.epochs, tree, chapter.id)!,
    chapterEpoch: chapter.epoch, // what a write under the chapter's key states (ADR 0109 point 6)
    hash: hashForNode(tree, chapter.id, TENANT_SCOPE),
  }
}

// --- fixtures (written by `measure_volume.py fixtures`) --------------------------------------------------------------

const fixture = (name: string): Uint8Array => {
  const path = join(DIR, 'fixtures', name)
  if (!existsSync(path)) die(`fixture ${path} is missing — run \`measure_volume.py fixtures\` first`)
  return new Uint8Array(readFileSync(path))
}
type Shape = { original: Uint8Array; thumbnail: Uint8Array; preview: Uint8Array; w: number; h: number }
function shapes(original: 'original' | 'phone'): { landscape: Shape; portrait: Shape } {
  const dims = JSON.parse(readFileSync(join(DIR, 'fixtures', 'dims.json'), 'utf8')) as Record<string, [number, number]>
  const of = (o: 'landscape' | 'portrait'): Shape => ({
    original: fixture(`${original}-${o}.jpg`),
    thumbnail: fixture(`thumb-${o}.jpg`),
    preview: fixture(`preview-${o}.jpg`),
    w: dims[`${original}-${o}`][0],
    h: dims[`${original}-${o}`][1],
  })
  return { landscape: of('landscape'), portrait: of('portrait') }
}

// Capture times spread over several years (2019-01-01 .. ~2025), deterministic per index.
const CAPTURE_FROM = Date.UTC(2019, 0, 1)
const captureAt = (i: number): number => CAPTURE_FROM + ((i * 7_919_993_011) % (6 * 365 * 86_400_000))

/** One photo as `addCopiedMedia` takes it: the fixture's bytes (re-sealed under a fresh K_blob per photo by the upload),
 *  a tenth of them portrait, with the listed tier carrying geometry and a capture time. */
function photo(i: number, s: { landscape: Shape; portrait: Shape }, derivatives: boolean): CopiedMediaSource {
  const shape = i % 10 === 9 ? s.portrait : s.landscape
  const jpeg = (bytes: Uint8Array) => new Blob([bytes as BlobPart], { type: 'image/jpeg' })
  return {
    original: { source: jpeg(shape.original), meta: { type: 'image/jpeg', name: `IMG_${String(i).padStart(5, '0')}.jpg` } },
    tiers: derivatives
      ? [
          { kind: 'THUMBNAIL', source: jpeg(shape.thumbnail), meta: { type: 'image/jpeg' } },
          { kind: 'PREVIEW', source: jpeg(shape.preview), meta: { type: 'image/jpeg' } },
        ]
      : [],
    meta: { v: 1, w: shape.w, h: shape.h, capturedAt: captureAt(i), capturedAtSource: 'exif' },
    exif: null,
  }
}

// --- birth ----------------------------------------------------------------------------------------------------------

async function birth(): Promise<void> {
  // birthInstance asserts `setupRequired === true` first: the harness never sets up an instance it did not start fresh.
  const { owner, tenant } = await birthInstance('Volume')
  // birthInstance points the tenant at a mailbox on the instance's own domain; the app reaches this recipe's relay by
  // its name on the compose network instead.
  const [smtpHost, smtpPort] = need('VEYQ_MEASURE_SMTP').split(':')
  await updateTenantMailSettings(tenant.tenantId, { host: smtpHost, port: Number(smtpPort), from: `noreply@${HOST}`, username: null, tls: false, enabled: true })
  writeJson('accounts.json', {
    owner: { email: owner.email, password: owner.password, username: owner.username, secretKey: owner.built.emergencyKit.secretKey, userId: owner.userId },
    tenantId: tenant.tenantId,
    members: [],
  } satisfies Accounts)
  console.log(`birth: instance set up — owner ${owner.email}, tenant ${tenant.tenantId} on ${HOST}; credentials in ${join(DIR, 'accounts.json')}`)
}

// --- photos ---------------------------------------------------------------------------------------------------------

async function photos(target: number, bare: boolean, width: number): Promise<void> {
  const a = accounts()
  await signIn(a.owner)
  const scope = await tenantScope()
  const chapterName = bare ? 'No derivatives' : 'All photos'
  const { chapterId, chapterKey, chapterEpoch, hash } = await ensureMemoryAndChapter(scope, 'Volume photos', chapterName)
  remember({ [bare ? 'bare' : 'photos']: { scopeId: scope.scopeId, nodeId: chapterId, hash, name: chapterName } })
  const have = (await listMedia(scope.scopeId, chapterId)).length
  const gap = target - have
  console.log(`photos: '${chapterName}' holds ${have}, target ${target}`)
  if (gap <= 0) {
    console.log(gap === 0 ? 'photos: nothing to add' : `photos: already ${-gap} above the target — nothing removed`)
    return
  }
  const s = shapes('original')
  const p = progress('photos', 'photos')
  await pool(gap, width, async (k) => {
    await addCopiedMedia(scope.scopeId, chapterId, chapterKey, photo(have + k, s, !bare), { nodeEpoch: chapterEpoch })
    p.tick()
  })
  const now = (await listMedia(scope.scopeId, chapterId)).length
  console.log(`photos: ${p.end()}; '${chapterName}' now holds ${now}`)
  if (now !== target) die(`the Chapter holds ${now}, not the target ${target}`)
}

// --- tree -----------------------------------------------------------------------------------------------------------

async function ensureGroup(name: string): Promise<OpenedScope> {
  let group = (await listMyGroups()).find((g) => g.name === name)
  if (!group) {
    await createGroup(name)
    group = (await listMyGroups()).find((g) => g.name === name)
    if (!group) die(`created the group '${name}' and could not find it again`)
  }
  const opened = await openScopeById(group.scopeId)
  if (!opened) die(`cannot open the group '${name}'`)
  return opened
}

type Spec = { kind: string; name: string; parent: string | null }
const pad = (n: number) => String(n).padStart(3, '0')

/** The realistic shape: Folders holding Memories holding Chapters, 1 : 5 : 20 — in creation order, so a
 *  stopped run has created a prefix of it. */
function* realistic(): Generator<Spec> {
  for (let f = 0; ; f++) {
    const folder = `Folder ${pad(f)}`
    yield { kind: FOLDER_KIND, name: folder, parent: null }
    for (let m = 0; m < 5; m++) {
      const memory = `Memory ${pad(f)}-${m}`
      yield { kind: MEMORY_KIND, name: memory, parent: folder }
      for (let c = 0; c < 4; c++) yield { kind: CHAPTER_KIND, name: `Chapter ${pad(f)}-${m}-${c}`, parent: memory }
    }
  }
}
/** The wide case: one Memory holding every Chapter — the worst case for the client's per-sibling list building. */
function* wide(): Generator<Spec> {
  yield { kind: MEMORY_KIND, name: 'Wide memory', parent: null }
  for (let c = 0; ; c++) yield { kind: CHAPTER_KIND, name: `Chapter ${String(c).padStart(4, '0')}`, parent: 'Wide memory' }
}

async function tree(target: number, shape: 'realistic' | 'wide'): Promise<void> {
  const a = accounts()
  await signIn(a.owner)
  const group = await ensureGroup(shape === 'wide' ? 'Volume wide' : 'Volume tree')
  const rows = await listTree(group.scopeId, group.epochs)
  console.log(`tree: '${shape}' group holds ${rows.length} nodes, target ${target}`)
  // name -> (id, content key) of every node already there; names are unique by construction.
  const byName = new Map<string, { id: string; key: Uint8Array; epoch: number }>()
  for (const n of rows) byName.set(n.name, { id: n.id, key: nodeContentKey(group.scopeId, group.epochs, rows, n.id)!, epoch: n.epoch })
  let count = rows.length
  const p = progress('tree', 'nodes')
  for (const spec of shape === 'wide' ? wide() : realistic()) {
    if (count >= target) break
    if (byName.has(spec.name)) continue
    const parentRef = spec.parent === null ? null : byName.get(spec.parent)
    if (spec.parent !== null && !parentRef) die(`parent '${spec.parent}' of '${spec.name}' is missing — the group was not filled by this seeder`)
    const parent: NodeParent = parentRef ? { id: parentRef.id, key: parentRef.key, epoch: parentRef.epoch } : null
    const made = await createContentNode(group.scopeId, group.epochs, parent, spec.kind as typeof FOLDER_KIND, spec.name)
    byName.set(spec.name, { id: made.nodeId, key: made.contentKey, epoch: made.epoch })
    count++
    p.tick()
  }
  const after = await listTree(group.scopeId, group.epochs)
  console.log(`tree: ${p.end()}; the group now holds ${after.length}`)
  if (after.length !== target) die(`the group holds ${after.length}, not the target ${target}`)
  const groupHash = `#@${group.scopeId}`
  if (shape === 'wide') {
    const last = after.filter((n) => n.kind === CHAPTER_KIND).sort((x, y) => x.createdAt - y.createdAt).at(-1)
    remember({ wide: { scopeId: group.scopeId, memoryName: 'Wide memory', hash: groupHash } })
    if (last) remember({ wideDeep: { scopeId: group.scopeId, nodeId: last.id, hash: hashForNode(after, last.id, `@${group.scopeId}`), name: last.name } })
  } else {
    const deep = after.filter((n) => n.kind === CHAPTER_KIND).sort((x, y) => x.createdAt - y.createdAt).at(-1)
    remember({ tree: { scopeId: group.scopeId, folderName: 'Folder 000', hash: groupHash } })
    if (deep) remember({ treeDeep: { scopeId: group.scopeId, nodeId: deep.id, hash: hashForNode(after, deep.id, `@${group.scopeId}`), name: deep.name } })
  }
}

// --- members --------------------------------------------------------------------------------------------------------

const FIRST_NAMES = ['Ada', 'Ben', 'Cleo', 'Dara', 'Emil', 'Fenja', 'Gil', 'Hanna', 'Ivo', 'Jule']
const LAST_NAMES = ['Arendt', 'Becker', 'Conrad', 'Dietz', 'Ebert', 'Fink', 'Graf', 'Hahn', 'Imhof', 'Jung']

function saveMember(m: Member): void {
  const a = accounts()
  const i = a.members.findIndex((x) => x.email === m.email)
  if (i >= 0) a.members[i] = m
  else a.members.push(m)
  writeJson('accounts.json', a)
}

/** Deliver every pending tenant grant as the owner's key-holder device does: the app's own grant-delivery worker. */
async function deliverGrants(tenant: OpenedScope, expect: number): Promise<void> {
  let delivered = 0
  for (let i = 0; i < 25 && delivered < expect; i++) {
    delivered += await runGrantDeliveryWorker({ scopeId: tenant.scopeId, kind: ScopeKind.Tenant, key: tenant.key, epoch: tenant.epochs.current })
    if (delivered < expect) await sleep(400)
  }
  if (delivered < expect) die(`the grant-delivery worker delivered ${delivered} of ${expect} grants`)
}

async function members(target: number): Promise<void> {
  const a = accounts()
  await signIn(a.owner)
  let tenant = await tenantScope()
  // The owner is a row of the directory too: named like every member, so "every row named" holds for the whole list.
  const ownName = await openOwnNameKey()
  if (!ownName) die('the owner holds no tenant grant')
  await updateMyProfile({ sealedName: sealName(ownName.key, ownName.address, { firstName: 'Volume', lastName: 'Owner' }) })
  const salt = fromB64std((await setupApi().getSetupStatus()).instanceSalt)
  const listed = await listScopeMembers(tenant.scopeId)
  const inTenant = new Set(listed.map((m) => m.username))
  console.log(`members: the tenant has ${listed.length}, target ${target}`)
  const joinRate = progress('members', 'accounts')
  // 1 — the owner's half: invite, the invitee's cold birth and register-and-accept, then the grant delivery.
  let count = listed.length
  let invites = 0
  for (let i = 0; count < target; i++) {
    const actor = mkActor(`m${String(i).padStart(4, '0')}`)
    const known = a.members.find((m) => m.email === actor.email)
    if (known && inTenant.has(known.username)) continue
    // A cold birth, as the invitee's browser does at the accept page. A record from a stopped run keeps its Secret Key:
    // the rest of the account is born again, which is sound because that account never reached the server.
    const built = await buildAccount(actor.email, actor.password, salt, known ? { secretKey: fromB64std(known.secretKey) } : {})
    const member: Member = { email: actor.email, password: actor.password, username: actor.username, secretKey: built.emergencyKit.secretKey, stage: 'built' }
    saveMember(member) // the Secret Key exists nowhere else: written before anything can fail
    const cursor = await mailCursor(member.email)
    try {
      await inviteMember(tenant.tenantId, member.email)
      invites++
    } catch (e) {
      if (httpStatus(e) === 429) {
        die(`invite ${invites + 1} of this run (member ${count + 1} of ${target}) was refused with 429: invites are ` +
          `limited per tenant per hour (veyq.ratelimit.invite.per-tenant-max-requests, default 20) — raise it while ` +
          `seeding members. The tenant holds ${count} members, NOT the target ${target}.`, 3)
      }
      throw e
    }
    const body = await waitForMail(member.email, { after: cursor })
    const link = body.match(/\/tenant\/accept#([A-Za-z0-9_-]+)/)
    if (!link) die(`no invite link in the mail to ${member.email}`)
    const accepted = await registerAndAccept(link[1], crypto.randomUUID(), member.username, built.material)
    member.userId = accepted.userId
    member.stage = 'joined'
    saveMember(member)
    await deliverGrants(tenant, 1)
    member.stage = 'granted'
    saveMember(member)
    count++
    joinRate.tick()
  }
  console.log(`members: joined ${joinRate.end()}`)
  // 2 — each member's own half: its sealed name, and every fifth a picture.
  const pending = accounts().members.filter((m) => m.stage === 'joined' || m.stage === 'granted')
  if (pending.some((m) => m.stage === 'joined')) {
    await backingOff('the owner signs in', () => signIn(a.owner))
    tenant = await tenantScope()
    await deliverGrants(tenant, pending.filter((m) => m.stage === 'joined').length)
  }
  const avatars = [0, 1, 2, 3, 4].map((k) => fixture(`avatar-${k}.webp`))
  const profileRate = progress('profiles', 'accounts')
  for (const m of pending) {
    const index = Number(m.email.match(/^m(\d{4})-/)?.[1] ?? '0')
    // One member's whole half, run again from its sign-in after a 429: writing the same sealed name and picture twice
    // leaves what writing them once leaves.
    await backingOff(`profile of ${m.username}`, async () => {
      await signIn(m)
      const own = await openOwnNameKey()
      if (!own) die(`${m.email} holds no tenant grant after the delivery`)
      const name = { firstName: FIRST_NAMES[index % 10], lastName: `${LAST_NAMES[Math.floor(index / 10) % 10]} ${index}` }
      await updateMyProfile({ sealedName: sealName(own.key, own.address, name) })
      if (index % 5 === 0) await setMyAvatar(sealAvatar(own.key, own.address, avatars[(index / 5) % 5]))
    })
    saveMember({ ...m, stage: 'profiled' })
    profileRate.tick()
  }
  console.log(`members: profiled ${profileRate.end()}`)
  await backingOff('the owner signs in', () => signIn(a.owner))
  const now = (await listScopeMembers((await tenantScope()).scopeId)).length
  console.log(`members: the tenant now has ${now}`)
  if (now !== target) die(`the tenant has ${now}, not the target ${target}`)
}

// --- upload scenario ------------------------------------------------------------------------------------------------

async function upload(count: number): Promise<void> {
  const a = accounts()
  const member = a.members.find((m) => m.stage === 'profiled')
  if (!member) die('no seeded member — seed members first')
  // The owner makes the Chapter (a member could too; the owner is who makes it in every other place here).
  await signIn(a.owner)
  const { chapterId, hash } = await ensureMemoryAndChapter(await tenantScope(), 'Volume upload', 'Phone upload')
  remember({ upload: { scopeId: a.tenantId, nodeId: chapterId, hash, name: 'Phone upload' } })
  await signIn(member)
  const scope = await tenantScope()
  const tree = await listTree(scope.scopeId, scope.epochs)
  const key = nodeContentKey(scope.scopeId, scope.epochs, tree, chapterId)
  if (!key) die('the member cannot open the upload Chapter')
  const nodeEpoch = tree.find((n) => n.id === chapterId)!.epoch
  const have = (await listMedia(scope.scopeId, chapterId)).length
  const s = shapes('phone')
  const p = progress('upload', 'photos')
  const started = Date.now()
  // One at a time, as the web client's upload queue runs (`uploadQueue.ts`: the sequential runner).
  for (let k = 0; k < count; k++) {
    await addCopiedMedia(scope.scopeId, chapterId, key, photo(have + k, s, true), { nodeEpoch })
    p.tick()
  }
  const bytes = count * (s.landscape.original.length + s.landscape.thumbnail.length + s.landscape.preview.length)
  console.log(`upload: ${p.end()}; ~${(bytes / 1e6 / ((Date.now() - started) / 1000)).toFixed(1)} MB/s of plaintext`)
}

// --- main -----------------------------------------------------------------------------------------------------------

const { cmd, flags } = args()
try {
  switch (cmd) {
    case 'birth': await birth(); break
    case 'photos': await photos(intFlag(flags, 'target'), flags.has('bare'), flags.has('width') ? intFlag(flags, 'width') : 6); break
    case 'tree':
      if (flags.has('wide')) await tree(intFlag(flags, 'wide') + 1, 'wide')
      else await tree(intFlag(flags, 'target'), 'realistic')
      break
    case 'members': await members(intFlag(flags, 'target')); break
    case 'upload': await upload(intFlag(flags, 'count')); break
    default: die('usage: seed-volume.mts birth | photos --target N [--bare] | tree --target N | tree --wide N | members --target N | upload --count N')
  }
} catch (e) {
  const response = (e as { response?: unknown } | null)?.response
  const detail = response instanceof Response ? `${response.status} ${await response.text().catch(() => '')}` : (e as Error)?.stack ?? String(e)
  die(`${cmd} failed: ${detail}`)
}
process.exit(0)
