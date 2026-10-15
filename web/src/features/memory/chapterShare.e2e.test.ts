// cr-node-key-hierarchy 5b.1 live e2e (VEYQ_E2E=1) — a CHAPTER is a share root of its own (ADR 0080 Decisions 9 and
// 13), against a REAL backend + MariaDB + the bundled store, with REAL crypto.
//
// Two actors on one instance, serialized (the `shareContributor.e2e` / `shareUploader.e2e` shape):
//   A          = curator: server owner + tenant owner (from `birthInstance`); one Memory with two chapters — the
//                shared one holding A's photo, a sibling — and a photo on the Memory itself.
//   anonymous  = a guest with NO session — every call goes through `publicSharesApi()` (core's link) or
//                `publicShareMediaApi()` (Memories' guest media), which carry no bearer.
//
// What only this layer can prove: that the server roots what it serves a chapter link at the chapter — the resolve's
// nodes, the media read, the uploads (the server sets `nodeId = rootNodeId`) and the drop key — and that what a guest
// adds through it is opened by the chapter's members through the real read paths. The absences are asserted on the
// raw wire (`…Raw(…).raw.json()`), because the generated client drops a field the spec does not name (web/CLAUDE.md).
// The unit layer (`share.test.ts` § a chapter share) proves the same chain against a server it scripts itself.
//
//   python3 scripts/run_web_e2e.py web/src/features/memory/chapterShare.e2e.test.ts
import { describe, it, expect } from 'vitest'
import { b64url } from '@veyq/crypto/base64'
import { publicSharesApi } from '../../api/client'
import { publicShareMediaApi } from './api'
import { HttpStatus, isHttpStatus } from '../../shared/httpError'
import { birthInstance } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../spaces/scopes'
import { epochsOf } from '../spaces/epochsTestUtil'
import { createChapter, createMemory } from './memoryNodes'
import { addMedia, listMedia, openOriginal } from './media'
import { listBuckets, openBucketName } from './mediaBuckets'
import {
  addSharePhoto,
  createContributorIdentity,
  createShare,
  deleteSharePhoto,
  openShareOriginal,
  resolveShare,
  sessionSealKey,
} from './share'
import { ScopeKind } from '../../api/generated/scopes'
import { ShareRole } from '../../api/generated/sharing'

// Distinctive plaintext for the wire-assertion sweep (ADR 0053) — distinct from the declared tenant name.
const TENANT_NAME = 'E2E Chapter Share' // declared plaintext (veyq_tenant.name) — NOT a canary
const MEMORY_NAME = 'Alpenreise Dolomiten'
const CHAPTER_NAME = 'Etappe Seiser Alm'
const SIBLING_NAME = 'Etappe Drei Zinnen'
const CONTRIBUTOR_NAME = 'Bergfuehrer Anselm'
const UPLOADER_NAME = 'Huettenwirtin Walburga'

/** Deterministic, distinguishable bytes — a different fill per photo, so a mixed-up read-back cannot pass. */
function bytes(fill: number, size = 200 * 1024): Uint8Array {
  const out = new Uint8Array(size)
  for (let i = 0; i < out.length; i++) out[i] = (i * 19 + fill) & 0xff
  return out
}
const photo = (fill: number) => ({ blob: new Blob([bytes(fill) as BlobPart]), mediaType: '' }) // original-only

type RawResolve = { rootKind?: string; nodeId?: string; wrap?: string | null; sealedDropPublicKey?: string | null; nodes: Record<string, unknown>[] }

describe.skipIf(!process.env.VEYQ_E2E)('chapter share e2e (live, real crypto, the link is rooted at the chapter)', () => {
  let scopeId: string
  let scopeKey: Uint8Array
  let memoryId: string
  let siblingId: string
  let chapterId: string
  let chapterKey: Uint8Array

  it('a contributor link on a chapter serves only the chapter; the guest adds a photo there and deletes only its own', async () => {
    // --- A: cold-birth the instance; a Memory with a photo, two chapters, a photo in the one to share -------------
    await birthInstance(TENANT_NAME)
    await ensurePersonalScope()
    const personal = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)
    expect(personal, 'the personal scope was born').toBeDefined()
    scopeId = personal!.id
    scopeKey = (await openPersonalScope())!
    const memory = await createMemory(scopeId, epochsOf(scopeKey), MEMORY_NAME)
    memoryId = memory.nodeId
    const chapter = await createChapter(scopeId, epochsOf(scopeKey), memory.memoryKey, CHAPTER_NAME, memoryId)
    chapterId = chapter.nodeId
    chapterKey = chapter.chapterKey
    siblingId = (await createChapter(scopeId, epochsOf(scopeKey), memory.memoryKey, SIBLING_NAME, memoryId)).nodeId
    const onMemory = await addMedia(scopeId, memoryId, memory.memoryKey, photo(1))
    const inChapter = await addMedia(scopeId, chapterId, chapterKey, photo(2))

    const minted = await createShare(scopeId, chapterId, chapterKey, epochsOf(scopeKey), CHAPTER_NAME, ShareRole.ReadWrite)
    const shareId = minted.shareId
    const fragmentKey = b64url(minted.kShare)

    // --- anonymous: the link opens the chapter, under the chapter's title, with the chapter's photo --------------
    const resolved = await resolveShare(shareId, fragmentKey)
    expect(resolved.role).toBe(ShareRole.ReadWrite)
    expect(resolved.memoryName, "the guest reads the chapter's title under K_share").toBe(CHAPTER_NAME)
    expect(resolved.items.map((i) => i.item.mediaId), "the chapter's photo and nothing of the Memory").toEqual([inChapter.mediaId])
    expect(Array.from(await openShareOriginal(shareId, resolved.items[0])), "the guest opens the chapter's photo")
      .toEqual(Array.from(bytes(2)))

    // --- the raw wire: rooted at the chapter, nothing of its Memory or its sibling ---------------------------------
    const wire = (await (await publicSharesApi().resolveShareRaw({ shareId })).raw.json()) as RawResolve
    expect(wire.rootKind, 'the server names the chapter as the root kind').toBe('CHAPTER')
    expect(wire.nodes.map((n) => n.id), 'the resolved subtree is the chapter alone').toEqual([chapterId])
    expect(wire.nodes[0].parentId ?? null, "the root's parent id is not served").toBeNull()
    expect(wire.nodes[0].wrappedContentKey ?? null, "nor its wrap under the Memory's key").toBeNull()
    expect(wire.nodes[0].encryptedName ?? null, "nor its name under the Memory's key").toBeNull()
    const wireText = JSON.stringify(wire)
    expect(wireText.includes(memoryId), "the Memory's id appears nowhere in the resolve").toBe(false)
    expect(wireText.includes(siblingId), "the sibling chapter's id appears nowhere in the resolve").toBe(false)
    const mediaWire = (await (await publicShareMediaApi().listShareMediaRaw({ shareId })).raw.json()) as { media: { mediaId: string; nodeId: string }[] }
    expect(mediaWire.media.map((m) => m.mediaId), "the raw media read serves the chapter's photo only").toEqual([inChapter.mediaId])
    expect(mediaWire.media.some((m) => m.mediaId === onMemory.mediaId), "never the Memory's own photo").toBe(false)

    // --- anonymous: name yourself, add a photo — it lands in the chapter ------------------------------------------
    const session = await createContributorIdentity(shareId, resolved.scopeId, resolved.access, CONTRIBUTOR_NAME, fragmentKey)
    const upload = { ...session, shareId, scopeId: resolved.scopeId, sealKey: sessionSealKey(session, resolved.access)! }
    const added = await addSharePhoto(upload, photo(3))
    const chapterItems = await listMedia(scopeId, chapterId)
    const contributed = chapterItems.find((i) => i.mediaId === added.mediaId)
    expect(contributed, "the guest's photo is in the chapter's own listing").toBeDefined()
    expect(Array.from(await openOriginal(scopeId, chapterKey, contributed!)), 'the member opens it under the chapter key')
      .toEqual(Array.from(bytes(3)))
    expect((await listMedia(scopeId, memoryId)).map((i) => i.mediaId), 'and it is not on the Memory').not.toContain(added.mediaId)
    const primary = (await listBuckets(scopeId, chapterId)).find((b) => b.isPrimary && b.sealedName)
    const recoveredName = primary ? openBucketName(scopeId, chapterId, chapterKey, primary) : null
    expect(recoveredName, "the member recovers the guest's name on the chapter").toBe(CONTRIBUTOR_NAME)

    // --- anonymous: deletes only its own — the member's photo is a non-enumerable 404 -----------------------------
    const refused = await deleteSharePhoto(shareId, session.contributorToken, inChapter.mediaId).then(() => null, (e: unknown) => e)
    expect(isHttpStatus(refused, HttpStatus.notFound), "deleting the member's photo is a 404").toBe(true)
    await deleteSharePhoto(shareId, session.contributorToken, added.mediaId)
    const after = (await listMedia(scopeId, chapterId)).map((i) => i.mediaId)
    expect(after, "the guest's own photo is gone").not.toContain(added.mediaId)
    expect(after, "the member's photo stays (the live control)").toContain(inChapter.mediaId)

    // Every value recovered by UNSEALING what the server returned (ADR 0053).
    recordCanaries(resolved.memoryName, recoveredName)
  }, 240_000)

  it('an uploader link on a chapter seals to the chapter; the guest uploads and the member opens it', async () => {
    const minted = await createShare(scopeId, chapterId, chapterKey, epochsOf(scopeKey), CHAPTER_NAME, ShareRole.WriteOnly)
    const shareId = minted.shareId
    const fragmentKey = b64url(minted.kShare)

    const wire = (await (await publicSharesApi().resolveShareRaw({ shareId })).raw.json()) as RawResolve
    expect(wire.rootKind).toBe('CHAPTER')
    expect(wire.nodeId, "the drop key the guest seals to is the chapter's").toBe(chapterId)
    expect(wire.wrap ?? null, 'an uploader link carries no K_node wrap').toBeNull()
    expect(wire.sealedDropPublicKey, 'only the sealed drop public key').toBeTruthy()

    const resolved = await resolveShare(shareId, fragmentKey)
    expect(resolved.memoryName).toBe(CHAPTER_NAME)
    expect(resolved.access).toMatchObject({ kind: 'drop', nodeId: chapterId })
    const session = await createContributorIdentity(shareId, resolved.scopeId, resolved.access, UPLOADER_NAME, fragmentKey)
    const uploaded = await addSharePhoto(
      { ...session, shareId, scopeId: resolved.scopeId, sealKey: sessionSealKey(session, resolved.access)! },
      photo(4),
    )

    const item = (await listMedia(scopeId, chapterId)).find((i) => i.mediaId === uploaded.mediaId)
    expect(item?.uploader, "the upload is in the chapter, with the drop path that opens it").toBeDefined()
    expect(Array.from(await openOriginal(scopeId, chapterKey, item!)), 'the member opens the guest upload')
      .toEqual(Array.from(bytes(4)))
  }, 240_000)
})
