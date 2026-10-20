// The READER of the upgrade proof (ADR 0057 Decision 7, uc.admin.back-up-and-restore-instance criterion
// `a-predecessors-backup-upgrades-with-its-content-intact`) - the `upgrade` group's second member. It always runs from
// the CANDIDATE's tree, against the candidate's jar, on an instance the orchestrator restored from the seeder's capture:
// under `--upgrade` that capture is the PREDECESSOR's, written by its own jar and its own suite, so the first boot of
// this member is the candidate migrating the predecessor's schema up.
//
// It asserts every item the record names - never only that nothing failed, because a sealed field that no longer opens
// degrades to an empty value, not to an error (a missing date reads as "no date"). In order:
//   - the instance is the restored one (`setupRequired` false), and the owner signs in the app's own way (password +
//     Emergency Kit Secret Key, unwrapped against the RESTORED server's key material);
//   - FIRST, before any content: the candidate reports `configSecretState: OK` - the key that came with the backup is
//     the one the restored rows were sealed for (Decision 4 of cr-upgrade-proof); written for the run's summary too;
//   - Memories: the Memory's name, date, place and cover (its uploaded image and crop), every photo's bytes, the chapter's;
//   - Depot: the folder's name, each file's name, sealed type and modified time, size and bytes;
//   - the drop box: its name, each guest's folder (named after the guest) and the guest's files, as above;
//   - the READ link, opened as its guest: the Memory's title and the bytes of what it serves.
// The record (`src/test/upgradeRecord.ts`) may come from an OLDER seeder: this file reads every version it can be
// handed, and a shape change bumps the version rather than reinterpreting an old one.
import { describe, it, expect } from 'vitest'
import { session } from '../../app/session'
import { setupApi } from '../../api/client'
import { BASE } from '../../test/e2eBootstrap'
import { recordCanaries } from '../../test/canaries'
import { readUpgradeRecord, sealedBytes, writeUpgradeReading, type SealedFile } from '../../test/upgradeRecord'
import { login } from '../account/login/login'
import { unlockMasterKey } from '../account/unlock/unlock'
import { getTenantMailSettings } from '../tenant/tenantMailSettings'
import { openScope } from '../spaces/scopes'
import { ScopeKind } from '../../api/generated/scopes'
import { listTree, nodeContentKey, type TreeNode } from '../../core/node/tree'
import { unsealNodeMeta } from '../../core/node/nodeMeta'
import { listMedia, openOriginal } from '../memories/media'
import { unsealMemoryDate } from '../memories/memoryDate'
import { unsealMemoryLocation } from '../memories/memoryLocation'
import { unsealMemoryCover } from '../memories/memoryCover'
import { openShareOriginal, resolveShare } from '../memories/share'
import { filesApi } from '../depot/api'
import { fetchFileContent, fileFacts } from '../depot/download'

const contentOf = async (b: Blob) => new Uint8Array(await b.arrayBuffer())
const byHash = (a: { sha256: string; size: number }, b: { sha256: string; size: number }) => a.sha256.localeCompare(b.sha256)

describe.skipIf(!process.env.VEYQ_E2E)('upgrade proof - read', () => {
  it('the candidate opens every item the seeder sealed, with the keys the owner holds', async () => {
    const record = readUpgradeRecord()
    session.apiBase = BASE

    // NOT a fresh instance: a restore that quietly produced an empty one would otherwise look like a clean start.
    const setup = await setupApi().getSetupStatus()
    expect(setup.setupRequired, 'the reader runs against the RESTORED instance').toBe(false)
    await login(record.owner.email, record.owner.password)
    await unlockMasterKey(record.owner.password, record.owner.secretKey)

    // Before any content: the config-secret key that came with the backup is the one the restored rows name.
    const { configSecretState } = await getTenantMailSettings(record.owner.tenantId)
    writeUpgradeReading({ configSecretState })
    expect(configSecretState, 'the candidate reads the config-secret key the backup carried (leg c)').toBe('OK')

    const { scopeId, epochs } = (await openScope(ScopeKind.Personal))!
    expect(scopeId, 'the personal scope is the one the seeder wrote into').toBe(record.scopeId)
    const tree = await listTree(scopeId, epochs)
    const node = (id: string, what: string): TreeNode => {
      const found = tree.find((n) => n.id === id)
      expect(found, `${what} is in the restored tree`).toBeDefined()
      return found!
    }
    const keyOf = (id: string, what: string) => {
      const key = nodeContentKey(scopeId, epochs, tree, id)
      expect(key, `${what}'s content key re-derives from the restored tree`).toBeTruthy()
      return key!
    }
    let items = 0

    // --- Memories --------------------------------------------------------------------------------------------------
    const m = record.memory
    const memoryNode = node(m.nodeId, 'the Memory')
    expect(memoryNode.name, 'the Memory name').toBe(m.name)
    const memoryKey = keyOf(m.nodeId, 'the Memory')
    expect(unsealMemoryDate(scopeId, memoryKey, memoryNode), 'the Memory date').toEqual(m.date)
    expect(unsealMemoryLocation(scopeId, memoryKey, memoryNode)?.name, 'the Memory place').toBe(m.place)
    const cover = unsealMemoryCover(scopeId, memoryKey, memoryNode)
    if (m.cover.mediaId) {
      // A seed from before covers were set only by an upload named one of its photos: not migrated, it reads as no cover.
      expect(cover, 'an older photo reference is no cover').toBeNull()
    } else {
      expect(cover?.source.ref.blobId, 'the Memory cover is still its uploaded image').toBe(m.cover.blobId)
      expect(cover?.crop, 'the Memory cover crop').toEqual(m.cover.crop)
    }
    items += 4

    const media = async (nodeId: string, key: Uint8Array, expected: typeof m.media, what: string) => {
      const listed = await listMedia(scopeId, nodeId)
      expect(listed.map((i) => i.mediaId).sort(), `${what}: the media rows`).toEqual(expected.map((e) => e.mediaId).sort())
      for (const e of expected) {
        const item = listed.find((i) => i.mediaId === e.mediaId)!
        expect(await sealedBytes(await openOriginal(scopeId, key, item)), `${what}: the bytes of media ${e.mediaId}`)
          .toEqual({ size: e.size, sha256: e.sha256 })
        items += 1
      }
    }
    await media(m.nodeId, memoryKey, m.media, 'the Memory')
    const chapterNode = node(m.chapter.nodeId, 'the chapter')
    expect(chapterNode.name, 'the chapter name').toBe(m.chapter.name)
    expect(chapterNode.parentId, 'the chapter is in its Memory').toBe(m.nodeId)
    await media(m.chapter.nodeId, keyOf(m.chapter.nodeId, 'the chapter'), m.chapter.media, 'the chapter')
    items += 1

    // --- Depot -----------------------------------------------------------------------------------------------------
    const files = async (folderId: string, expected: SealedFile[], what: string) => {
      const listed = (await filesApi().listFiles({ scopeId, parentId: folderId })).items
      expect(listed.map((i) => i.nodeId).sort(), `${what}: the files listed`).toEqual(expected.map((e) => e.nodeId).sort())
      for (const e of expected) {
        const fileNode = node(e.nodeId, `${what}: file ${e.name}`)
        expect(fileNode.name, `${what}: a file name`).toBe(e.name)
        expect(fileNode.parentId, `${what}: ${e.name} is in its folder`).toBe(folderId)
        const fileKey = keyOf(e.nodeId, `${what}: ${e.name}`)
        const blob = listed.find((i) => i.nodeId === e.nodeId)!.fileBlob!
        expect(fileFacts(scopeId, fileKey, e.nodeId, blob), `${what}: ${e.name}'s size and sealed meta`)
          .toMatchObject({ size: e.size, meta: { type: e.type, name: e.name, modifiedAt: e.modifiedAt } })
        expect(await sealedBytes(await contentOf(await fetchFileContent(scopeId, fileKey, e.nodeId, blob))), `${what}: ${e.name}'s bytes`)
          .toEqual({ size: e.size, sha256: e.sha256 })
        items += 1
      }
    }
    const f = record.files
    const folderNode = node(f.folderId, 'the Depot folder')
    expect(folderNode.name, 'the Depot folder name').toBe(f.name)
    await files(f.folderId, f.files, 'the Depot folder')
    items += 1

    // --- the drop box ----------------------------------------------------------------------------------------------
    const d = record.dropBox
    expect(node(d.nodeId, 'the drop box').name, 'the drop box name').toBe(d.name)
    expect(tree.filter((n) => n.parentId === d.nodeId).map((n) => n.name).sort(), "the drop box's guest folders")
      .toEqual(d.guests.map((g) => g.name).sort())
    for (const g of d.guests) {
      const folder = node(g.folderId, `the drop folder of ${g.name}`)
      expect(folder.name, "a guest's folder is named after the guest").toBe(g.name)
      expect(folder.parentId, `${g.name}'s folder is in the drop box`).toBe(d.nodeId)
      await files(g.folderId, g.files, `${g.name}'s drop folder`)
      items += 1
    }
    items += 1

    // --- the READ link, as its guest opens it ----------------------------------------------------------------------
    const s = record.share
    const resolved = await resolveShare(s.shareId, s.fragmentKey)
    expect(resolved.memoryName, "the link's guest reads the Memory title").toBe(s.name)
    const served = await Promise.all(resolved.items.map(async (item) => sealedBytes(await openShareOriginal(s.shareId, item))))
    expect(served.sort(byHash), "the bytes the link's guest opens").toEqual([...s.media].sort(byHash))
    items += 1 + served.length

    writeUpgradeReading({ configSecretState, items })

    // The canaries against the RESTORED instance (ADR 0053): every name of the tree and the Memory's meta envelope.
    const envelope = unsealNodeMeta(memoryKey, { scopeId, nodeId: m.nodeId }, memoryNode.wrappedMeta)
    if (envelope.cover && typeof envelope.cover === 'object') delete (envelope.cover as { source?: unknown }).source
    recordCanaries(tree.map((n) => n.name), envelope, resolved.memoryName)
  }, 300_000)
})
