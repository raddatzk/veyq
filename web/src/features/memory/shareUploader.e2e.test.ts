// uc.sharing.share-uploader live e2e (VEYQ_E2E=1) — the UPLOADER guest never holds K_mem (ADR 0031 point 7),
// against a REAL backend + MariaDB + the bundled store, with REAL crypto.
//
// Two actors on one instance, serialized (the `shareContributor.e2e` shape):
//   A          = curator: server owner + tenant owner (from `birthInstance`), mints the shares, reads them back.
//   anonymous  = an uploader with NO session — every call goes through `publicSharesApi()`, which carries no bearer.
//
// What only this layer can prove: that the resolve the SERVER actually sends an uploader carries no wrap, and that
// what a guest sealed through the real share transport — under its own key, wrapped to the Memory's drop key — is
// opened by every reader of the Memory through the real read paths: the curator's gallery, the curator's buckets,
// and a viewer link of the same Memory. The unit layer (`shareUploader.test.ts`) proves the same chain against a
// server it scripts itself; here the server's own storage and serving of the three new fields is in the loop.
//
//   python3 scripts/run_web_e2e.py web/src/features/memory/shareUploader.e2e.test.ts
import { describe, it, expect } from 'vitest'
import { b64url } from '@veyq/crypto/base64'
import { publicSharesApi } from '../../api/client'
import { birthInstance } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { createMemory } from './tree'
import { listMedia, openOriginal } from './media'
import { listBuckets, openBucketName } from './mediaBuckets'
import {
  addSharePhoto,
  createContributorIdentity,
  createShare,
  openShareOriginal,
  resolveShare,
  sessionSealKey,
} from './share'
import { ScopeKind } from '../../api/generated/scopes'
import { ShareRole } from '../../api/generated/sharing'

// Distinctive plaintext for the wire-assertion sweep (ADR 0053) — distinct from the declared tenant name.
const TENANT_NAME = 'E2E Uploader Share' // declared plaintext (veyq_tenant.name) — NOT a canary
const MEMORY_NAME = 'Hochzeitsfeier Seeufer'
const UPLOADER_NAME = 'Trauzeugin Mathilde'

/** Deterministic, distinguishable bytes. */
function bytes(fill: number, size = 300 * 1024): Uint8Array {
  const out = new Uint8Array(size)
  for (let i = 0; i < out.length; i++) out[i] = (i * 13 + fill) & 0xff
  return out
}

/** Every Uint8Array reachable from [value] — what a resolved session actually holds, whatever its fields are named. */
function heldBytes(value: unknown, seen = new Set<unknown>()): Uint8Array[] {
  if (value instanceof Uint8Array) return [value]
  if (!value || typeof value !== 'object' || seen.has(value)) return []
  seen.add(value)
  return Object.values(value).flatMap((v) => heldBytes(v, seen))
}
const sameBytes = (a: Uint8Array, b: Uint8Array) => a.length === b.length && a.every((x, i) => x === b[i])

describe.skipIf(!process.env.VEYQ_E2E)('uploader share e2e (live, real crypto, the guest never holds K_mem)', () => {
  it('a guest uploads through an UPLOADER link without K_mem, and every reader of the Memory opens it', async () => {
    // --- A: cold-birth the instance, personal scope, one Memory, one UPLOADER share --------------------------
    await birthInstance(TENANT_NAME)
    await ensurePersonalScope()
    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)
    expect(personal, 'the personal scope was born').toBeDefined()
    const scopeId = personal!.id
    const scopeKey = (await openPersonalScope())!
    const memory = await createMemory(scopeId, epochsOf(scopeKey), MEMORY_NAME)
    const nodeId = memory.nodeId
    const kMem = memory.memoryKey

    const minted = await createShare(scopeId, nodeId, kMem, MEMORY_NAME, ShareRole.Uploader)
    const fragmentKey = b64url(minted.kShare)

    // --- anonymous: what the server sends the guest carries no wrap, only the sealed drop public key ----------
    const raw = await publicSharesApi().resolveShare({ shareId: minted.shareId })
    expect(raw.role, 'the share resolves as UPLOADER').toBe(ShareRole.Uploader)
    expect(raw.wrap ?? null, 'the resolve the guest received carries no K_mem wrap').toBeNull()
    expect(raw.sealedDropPublicKey, 'it carries the sealed drop public key instead').toBeTruthy()

    const resolved = await resolveShare(minted.shareId, fragmentKey)
    expect(resolved.memoryName, 'the guest still reads the title under K_share').toBe(MEMORY_NAME)
    expect(resolved.access.kind, 'the guest holds the drop public key').toBe('drop')
    expect(heldBytes(resolved).some((b) => sameBytes(b, kMem)), 'the resolved session holds no Memory key').toBe(false)

    // --- anonymous: name yourself (under your own key) and upload through the share transport ----------------
    const session = await createContributorIdentity(minted.shareId, resolved.scopeId, resolved.access, UPLOADER_NAME)
    const sealKey = sessionSealKey(session, resolved.access)
    expect(sealKey, 'the uploader session carries its own key').not.toBeNull()
    expect(sameBytes(sealKey!, kMem), 'and that key is not the Memory key').toBe(false)
    const photo = bytes(7)
    const uploaded = await addSharePhoto(
      { ...session, shareId: minted.shareId, scopeId: resolved.scopeId, sealKey: sealKey! },
      { blob: new Blob([photo as BlobPart]), mediaType: '' },
    )
    expect(uploaded.mediaId, 'the upload attached').toBeTruthy()

    // --- A: the curator's gallery opens it, through the drop key the node keeps -----------------------------
    const curatorItems = await listMedia(scopeId, nodeId)
    const item = curatorItems.find((i) => i.mediaId === uploaded.mediaId)
    expect(item, "the upload is in the curator's gallery").toBeDefined()
    expect(item!.uploader, 'the listing served the path that opens it').toBeDefined()
    expect(Array.from(await openOriginal(scopeId, kMem, item!)), 'the curator opens the guest upload').toEqual(Array.from(photo))

    // the curator's buckets: the guest's primary name opens through its wrapped key
    const primary = (await listBuckets(scopeId, nodeId)).find((b) => b.isPrimary && b.uploader)
    expect(primary, "the uploader's primary bucket carries its wrapped key").toBeDefined()
    const recoveredName = openBucketName(scopeId, nodeId, kMem, primary!)
    expect(recoveredName, 'the curator recovered the uploader display name').toBe(UPLOADER_NAME)

    // a second UPLOADER link on the same Memory seals to the drop key the Memory already has
    const second = await createShare(scopeId, nodeId, kMem, MEMORY_NAME, ShareRole.Uploader)
    const secondResolved = await resolveShare(second.shareId, b64url(second.kShare))
    expect(secondResolved.access.kind, 'the second uploader link resolves to a drop key too').toBe('drop')

    // --- a viewer link of the same Memory opens the guest's upload too --------------------------------------
    const viewer = await createShare(scopeId, nodeId, kMem, MEMORY_NAME, ShareRole.Viewer)
    const viewed = await resolveShare(viewer.shareId, b64url(viewer.kShare))
    const shared = viewed.items.find((i) => i.item.mediaId === uploaded.mediaId)
    expect(shared, "the upload is in the viewer link's gallery").toBeDefined()
    expect(Array.from(await openShareOriginal(viewer.shareId, shared!)), 'a viewer opens the guest upload')
      .toEqual(Array.from(photo))

    // Every value recovered by UNSEALING what the server returned (ADR 0053): the title and the guest's name.
    recordCanaries(resolved.memoryName, recoveredName)
  }, 240_000)
})
