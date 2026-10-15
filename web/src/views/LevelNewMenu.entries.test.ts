// @vitest-environment happy-dom
// The "New" menu's entries as the two real applications declare them (inbox #406): which entry shows to whom is the
// application's capability matrix (`memoryCapabilities`, `depotCapabilities`) read through the descriptor's `allowed`, and
// the order is the registry's. Fail closed: no entry for a READ guest, a pending deletion or a guest's identity folder.
import { describe, it, expect } from 'vitest'
import { createRegistry, type LevelActionContext } from '../core/applications/registry'
import { registerMemories } from '../features/memory/register'
import { registerDepot } from '../features/depot/register'
import { ShareRole } from '../api/generated/sharing'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../test/models'
import { epochsOf } from '../features/spaces/epochsTestUtil'
import { FOLDER_KIND } from '../core/node/kind'
import { folderCapabilities, targetOf, type Access } from '../core/access'
import * as v1 from '@veyq/crypto/v1'

const registry = createRegistry()
registerMemories(registry)
registerDepot(registry)

const FOLDER = treeNode({ id: 'f1', kind: FOLDER_KIND, name: 'Trips', parentId: null, orderIndex: 0, epoch: 0, wrappedContentKey: 'eA==', wrappedMeta: null, metaVersion: 0, placementVersion: 0 })

function ctx(access: Access, over: { identity?: boolean; epochs?: boolean } = {}): LevelActionContext {
  const epochs = over.epochs === false ? null : epochsOf(v1.randomBytes(32))
  const tree = { ...memberTreeOf(() => [FOLDER]), inIdentityFolder: () => over.identity ?? false }
  return { access, tree, folder: FOLDER, epochs: access.mode === 'member' ? epochs : null }
}
/** The ids of the entries shown to this level, in menu order. */
const shown = (c: LevelActionContext) => registry.levelMenu().filter((e) => e.allowed(c)).map((e) => e.id)

describe('the applications\' "New" menu entries', () => {
  it('are ordered Memory, drop box, upload, upload folder (core adds the folder before them; no import entry)', () => {
    expect(registry.levelMenu().map((e) => e.id)).toEqual(['create:MEMORY', 'create:DEPOT_DROPBOX', 'depot.upload', 'depot.uploadFolder'])
    expect(registry.levelMenu().map((e) => e.label)).toEqual(['memory.create', 'depot.dropBox.create', 'depot.upload', 'depot.uploadFolder'])
  })

  it('a chapter is not created from a level: no entry for it', () => {
    expect(registry.levelMenu().some((e) => e.create === 'CHAPTER')).toBe(false)
  })

  it('a member at a folder sees all of them', () => {
    expect(shown(ctx(memberAccessOf()))).toEqual(['create:MEMORY', 'create:DEPOT_DROPBOX', 'depot.upload', 'depot.uploadFolder'])
  })

  it('a pending deletion writes nothing: no entry', () => {
    expect(shown(ctx(memberAccessOf({ readonly: true })))).toEqual([])
  })

  it('a guest\'s identity folder places nothing: no entry, for a member either', () => {
    expect(shown(ctx(memberAccessOf(), { identity: true }))).toEqual([])
  })

  it('a READ guest sees none', () => {
    expect(shown(ctx(guestAccessOf(ShareRole.Read)))).toEqual([])
  })

  it('an unknown role grants nothing (fail closed)', () => {
    expect(shown(ctx(guestAccessOf('SOMETHING_NEW')))).toEqual([])
  })

  // Owner decision 2026-10-14: a guest creates folders and uploads files, never a Memory or a drop box — the server
  // refuses those (`GuestCreatableKinds`), so the menu does not offer them at all: hidden, not greyed out.
  it('a READ_WRITE guest sees core\'s folder entry and both uploads, no Memory and no drop box', () => {
    const c = ctx(guestAccessOf(ShareRole.ReadWrite))
    expect(folderCapabilities(c.access, targetOf(c.tree, c.folder)).createFolder, "core's folder entry").toBe(true)
    expect(shown(c)).toEqual(['depot.upload', 'depot.uploadFolder'])
  })

  it('the create entries are greyed out while the member\'s epoch keys are locked; the upload is the section\'s to enable', () => {
    const locked = ctx(memberAccessOf(), { epochs: false })
    const byId = Object.fromEntries(registry.levelMenu().map((e) => [e.id, e]))
    expect(byId['create:MEMORY'].enabled!(locked)).toBe(false)
    expect(byId['create:DEPOT_DROPBOX'].enabled!(locked)).toBe(false)
    expect(byId['create:MEMORY'].enabled!(ctx(memberAccessOf()))).toBe(true)
    expect(byId['depot.upload'].enabled).toBeUndefined()
  })
})
