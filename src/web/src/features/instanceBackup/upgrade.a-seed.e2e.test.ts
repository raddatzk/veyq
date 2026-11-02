// The SEEDER of the upgrade proof (ADR 0057 Decision 7, uc.admin.back-up-and-restore-instance criterion
// `a-predecessors-backup-upgrades-with-its-content-intact`) - a GROUP member, so it only means anything under
// `scripts/run_web_e2e.py`. Under `--upgrade` / `--upgrade-from <ref>` it runs from the PREDECESSOR's worktree against
// the predecessor's jar; the orchestrator then captures the instance, destroys it, restores it onto a freshly installed
// candidate, and the candidate's `upgrade.b-read.e2e.test.ts` opens what this wrote. In an ordinary run both members
// are this tree's, which keeps the pair working between proofs.
//
// It seals real content of every application there is: a Memory with a date, a place, a cover (an uploaded image with
// a crop) and media, a chapter with media; a Depot folder with a member's files (one of several frames); a drop box
// with a guest's file in the guest's folder; and a READ link on the Memory. What it wrote goes into the record
// (`src/test/upgradeRecord.ts`) - the names, the date and place, the cover, and each byte stream's size and SHA-256 -
// with only what a person would still have to sign in.
//
// A new application adds its content here AND its assertions to the reader, and bumps nothing: a new section is a new
// record version only if a predecessor's record must still be read without it (the reader keeps every version).
import { describe, it, expect, beforeAll } from 'vitest'
import { b64url } from '@veyq/crypto/base64'
import { birthInstance } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { sealedBytes, writeUpgradeRecord, type SealedFile, type SealedMedia } from '../../test/upgradeRecord'
import { getTenantMailSettings } from '../tenant/tenantMailSettings'
import { ensurePersonalScope, openScope } from '../spaces/scopes'
import { ScopeKind } from '../../api/generated/scopes'
import { ShareRole } from '../../api/generated/sharing'
import { createFolder, listTree, nodeContentKey } from '../../core/node/tree'
import { unsealNodeMeta, type NodeMetaState } from '../../core/node/nodeMeta'
import { uploadCoverBlob, writeNodeCover } from '../../core/node/cover/coverWrite'
import { uploadedCover } from '../../core/node/cover/coverModel'
import { CHUNK_SIZE } from '../../core/blob/transport'
import { registry } from '../../core/applications/registry'
import { resolvePublicShare } from '../../core/sharing/resolvePublicShare'
import { fileNameSlug } from '../../shared/naming'
import { createChapter, createMemory } from '../memories/memoryNodes'
import { addMedia } from '../memories/media'
import { setMemoryDate } from '../memories/memoryDate'
import { setMemoryLocation } from '../memories/memoryLocation'
import { createShare, openShareOriginal, resolveShare } from '../memories/share'
import { FILE_KIND } from '../depot/kinds'
import { fileUploadJob } from '../depot/uploadFile'
import { createDropBox, dropGuestContext, ensureDropFolder, mintDropBoxLink, nameDropGuest } from '../depot/dropBox'
import { guestFileUploadJob } from '../depot/shareUpload'
import { nothingOfItsOwnToRekey } from '../../core/node/rekey/hooks'
import { testKind } from '../../test/testApplication'

// Distinctive plaintext for the wire sweep (ADR 0053): spaces and umlauts, so no name is a run of base64 letters that
// random ciphertext could contain (inbox #323).
const MEMORY_NAME = 'Lofoten Überfahrt Hamnøy'
const CHAPTER_NAME = 'Etappe Reine Fjord'
const PLACE = 'Lofoten Süd'
const DATE = { mode: 'range' as const, from: '2026-03-02', to: '2026-03-11' }
const CROP = { x: 0.1, y: 0.2, width: 0.5, height: 0.28125 }
const FOLDER_NAME = 'Unterlagen Umzug Nordstadt'
const FILE_A = 'Mietvertrag Nordstadt Ü.pdf'
const FILE_B = 'Kisten Liste Umzug.ods'
const BOX_NAME = 'Einsendungen Polterabend'
const GUEST = 'Gast Wilhelmine Ostrander'
const GUEST_FILE = 'Wilhelmines Grussbotschaft.txt'
const MIME = 'application/x-veyq-upgrade-e2e'
const MODIFIED_AT = 1_700_000_000_000

/** Deterministic, distinguishable bytes - a different salt per stream, so a mixed-up read-back cannot pass. */
const bytesOf = (n: number, salt: number) => new Uint8Array(n).map((_, i) => (i * 19 + salt) % 251)
const fileOf = (bytes: Uint8Array, name: string) => new File([bytes as BlobPart], name, { type: MIME, lastModified: MODIFIED_AT })
const ctx = () => ({ onProgress: () => {}, signal: new AbortController().signal })

beforeAll(() => {
  // A node-environment suite gets no application registry (`src/test/setup.ts`): FILE with its own folding, as Depot's
  // `register.ts` registers it, so the guest's slug is the one the page computes.
  if (!registry.kind(FILE_KIND).supported) testKind(registry, { rekeyContent: nothingOfItsOwnToRekey, kind: FILE_KIND, icon: 'file', slug: fileNameSlug })
})

describe.skipIf(!process.env.VEYQ_E2E)('upgrade proof - seed', () => {
  it('seals content of every application and records what it sealed', async () => {
    const { owner, tenant } = await birthInstance('E2E Upgrade')
    await ensurePersonalScope()
    const { scopeId, epochs } = (await openScope(ScopeKind.Personal))!

    // The config-secret control, as the restore rehearsal reads it: with no fingerprint stored the guard ADOPTS the key
    // on this first read, so only after it can the candidate's read tell the restored key (OK) from another (MISMATCH).
    expect((await getTenantMailSettings(tenant.tenantId)).configSecretState, 'the pre-capture control').toBe('OK')

    // --- Memories: a Memory with media, a chapter with media, then its date, place and cover ------------------------
    const memory = await createMemory(scopeId, epochs, MEMORY_NAME)
    // A node is born at its own epoch 0, its key minted under the scope's current epoch (ADR 0114 decisions 3-4); nothing
    // re-keys it here.
    const birth = { epoch: 0, scopeEpoch: epochs.epoch }
    const birthEpoch = birth.epoch
    const memoryMedia: SealedMedia[] = []
    for (const salt of [1, 2]) {
      const data = bytesOf(200 * 1024 + salt, salt)
      // No MIME => the original-only path: what an upgrade must keep is the sealed bytes and their row.
      const { mediaId } = await addMedia(scopeId, memory.nodeId, memory.memoryKey, { blob: new Blob([data as BlobPart]), mediaType: '' }, undefined, { nodeEpoch: birthEpoch })
      memoryMedia.push({ mediaId, ...(await sealedBytes(data)) })
    }
    const chapter = await createChapter(scopeId, epochs, memory.memoryKey, CHAPTER_NAME, memory.nodeId, birth)
    const chapterData = bytesOf(150 * 1024, 3)
    const { mediaId: chapterMediaId } = await addMedia(scopeId, chapter.nodeId, chapter.chapterKey, { blob: new Blob([chapterData as BlobPart]), mediaType: '' }, undefined, { nodeEpoch: birthEpoch })
    const chapterMedia: SealedMedia[] = [{ mediaId: chapterMediaId, ...(await sealedBytes(chapterData)) }]

    let meta: NodeMetaState = { ...(await setMemoryDate(scopeId, memory.nodeId, memory.memoryKey, DATE, { wrappedMeta: null, metaRevision: 0, nodeEpoch: birthEpoch })), nodeEpoch: birthEpoch }
    meta = { ...(await setMemoryLocation(scopeId, memory.nodeId, memory.memoryKey, { name: PLACE, nameSource: 'manual', derivedMode: 'fixed' }, meta)), nodeEpoch: birthEpoch }
    // The cover: an uploaded image (a cover is set only by an upload), sealed as it is - no decode in this suite.
    const coverUp = await uploadCoverBlob(scopeId, memory.nodeId, memory.memoryKey, new Blob([bytesOf(4 * 1024, 7) as BlobPart]), { type: 'image/jpeg' })
    await writeNodeCover({ scopeId, nodeId: memory.nodeId, nodeKey: memory.memoryKey, current: meta, cover: uploadedCover(coverUp.plan, CROP), uploadId: coverUp.uploadId })

    // --- Depot: a folder with two of a member's files, one of several frames ----------------------------------------
    const folderId = await createFolder(scopeId, epochs, FOLDER_NAME)
    const folderKey = nodeContentKey(scopeId, epochs, await listTree(scopeId, epochs), folderId)!
    const memberFiles: SealedFile[] = []
    for (const [name, data] of [[FILE_A, bytesOf(CHUNK_SIZE + 1234, 4)], [FILE_B, bytesOf(4321, 5)]] as const) {
      const { nodeId } = await fileUploadJob({ scopeId, epochs, parent: { id: folderId, key: folderKey, ...birth } }, fileOf(data, name)).run(ctx())
      memberFiles.push({ nodeId, name, type: MIME, modifiedAt: MODIFIED_AT, ...(await sealedBytes(data)) })
    }

    // --- Depot: a drop box in that folder, and a guest's file in the guest's own folder -----------------------------
    const boxId = await createDropBox(scopeId, epochs, { id: folderId, key: folderKey, ...birth }, BOX_NAME)
    const boxKey = nodeContentKey(scopeId, epochs, await listTree(scopeId, epochs), boxId)!
    const link = await mintDropBoxLink(scopeId, boxId, { key: boxKey, epoch: birthEpoch }, epochs, BOX_NAME)
    const head = await resolvePublicShare(link.shareId, b64url(link.kShare))
    const guest = await nameDropGuest(head, { name: GUEST })
    const guestFolder = await ensureDropFolder(head, guest, GUEST)
    const guestData = bytesOf(999, 6)
    const { nodeId: guestFileId } = await guestFileUploadJob(
      { ctx: dropGuestContext(head, guest, guestFolder), parentId: guestFolder.nodeId }, fileOf(guestData, GUEST_FILE),
    ).run(ctx())
    const guestFiles: SealedFile[] = [{ nodeId: guestFileId, name: GUEST_FILE, type: MIME, modifiedAt: MODIFIED_AT, ...(await sealedBytes(guestData)) }]

    // --- a READ link on the Memory, recorded as its guest sees it now ------------------------------------------------
    const minted = await createShare(scopeId, memory.nodeId, { key: memory.memoryKey, epoch: birthEpoch }, epochs, MEMORY_NAME, ShareRole.Read)
    const fragmentKey = b64url(minted.kShare)
    const resolved = await resolveShare(minted.shareId, fragmentKey)
    expect(resolved.memoryName, 'the guest reads the Memory title before the upgrade').toBe(MEMORY_NAME)
    expect(resolved.items.length, 'the guest sees the Memory media before the upgrade').toBeGreaterThan(0)
    const shareMedia = await Promise.all(resolved.items.map(async (item) => sealedBytes(await openShareOriginal(minted.shareId, item))))

    writeUpgradeRecord({
      version: 1,
      owner: { email: owner.email, password: owner.password, secretKey: owner.built.emergencyKit.secretKey, tenantId: tenant.tenantId },
      scopeId,
      memory: {
        nodeId: memory.nodeId, name: MEMORY_NAME, date: DATE, place: PLACE,
        cover: { blobId: coverUp.plan.blobId, crop: CROP },
        media: memoryMedia,
        chapter: { nodeId: chapter.nodeId, name: CHAPTER_NAME, media: chapterMedia },
      },
      files: { folderId, name: FOLDER_NAME, files: memberFiles },
      dropBox: { nodeId: boxId, name: BOX_NAME, guests: [{ name: GUEST, folderId: guestFolder.nodeId, files: guestFiles }] },
      share: { shareId: minted.shareId, fragmentKey, name: resolved.memoryName, media: shareMedia },
    })

    // The canaries (ADR 0053): what the server's own responses unseal to - every node name of the tree, the Memory's
    // meta envelope (its cover's `source` dropped: a blob id, legitimately in the database - memoryCover.e2e says why).
    const tree = await listTree(scopeId, epochs)
    const memoryNode = tree.find((n) => n.id === memory.nodeId)!
    const envelope = unsealNodeMeta(memory.memoryKey, { scopeId, nodeId: memory.nodeId }, memoryNode.wrappedMeta)
    if (envelope.cover && typeof envelope.cover === 'object') delete (envelope.cover as { source?: unknown }).source
    recordCanaries(tree.map((n) => n.name), envelope, resolved.memoryName, MIME)
  }, 300_000)
})
