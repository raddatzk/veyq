// @vitest-environment happy-dom
// Who is offered a level's upload-only link ("Add files only", owner 2026-11-04) over the REAL registry: core's fail-closed
// answer (`core/sharing/folderUploadShare.ts`) ANDed with the one Depot registered (`app.folderUploadShare`: where a drop box
// may be created, `depotRights(...).createDropBox`) - the answer today's "New drop box" entry gave. A member writer at a
// folder or the space's top level; never a guest of any role, never with the instance's drop-box leaf off, never with Depot
// switched off for the tenant, never during a pending deletion, never in a guest's identity folder.
import { describe, it, expect } from 'vitest'
import { folderUploadShareAt } from '../../core/sharing/folderUploadShare'
import type { LevelActionContext } from '../../core/applications/registry'
import { FOLDER_KIND } from '../../core/node/kind'
import type { Access } from '../../core/access'
import { ShareRole } from '../../api/generated/sharing'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../test/models'
import { epochsOf } from '../spaces/epochsTestUtil'
import { DROPBOX_KIND, FILE_KIND } from './kinds'
import { DEPOT } from '../platform/capabilities'

const FOLDER = treeNode({ id: 'f1', kind: FOLDER_KIND, name: 'Trips', parentId: null })
const FILE = treeNode({ id: 'x1', kind: FILE_KIND, name: 'a.pdf', parentId: null })
const EPOCHS = epochsOf(new Uint8Array(32).fill(1))

function ctx(access: Access, over: { folder?: typeof FOLDER | null; identity?: boolean } = {}): LevelActionContext {
  const tree = { ...memberTreeOf(() => [FOLDER, FILE]), inIdentityFolder: () => over.identity ?? false }
  return { access, tree, folder: over.folder === undefined ? FOLDER : over.folder, epochs: access.mode === 'member' ? EPOCHS : null }
}
const offered = (c: LevelActionContext) => folderUploadShareAt(c)?.kind ?? null

describe('the upload-only link of a level', () => {
  it('is Depot\'s drop box for a member who may create one: at a folder and at the space\'s top level', () => {
    expect(offered(ctx(memberAccessOf()))).toBe(DROPBOX_KIND)
    expect(offered(ctx(memberAccessOf(), { folder: null }))).toBe(DROPBOX_KIND)
    expect(folderUploadShareAt(ctx(memberAccessOf()))?.hint).toBe('depot.dropBox.uploadShareHint')
  })

  it.each<[string, LevelActionContext]>([
    ['the instance\'s drop-box leaf is off', ctx(memberAccessOf({ publicSharesDropBox: false }))],
    ['the instance\'s drop-box leaf is off, at the top level', ctx(memberAccessOf({ publicSharesDropBox: false }), { folder: null })],
    ['Depot is switched off for the tenant', ctx(memberAccessOf({ applicationsOff: new Set([DEPOT]) }))],
    ['the scope is pending deletion', ctx(memberAccessOf({ readonly: true }))],
    ['the level is a guest\'s identity folder', ctx(memberAccessOf(), { identity: true })],
    ['the node is no folder', ctx(memberAccessOf(), { folder: FILE })],
    ['the viewer is a READ guest', ctx(guestAccessOf(ShareRole.Read))],
    ['the viewer is a READ_WRITE guest', ctx(guestAccessOf(ShareRole.ReadWrite))],
    ['the viewer is a WRITE_ONLY guest', ctx(guestAccessOf(ShareRole.WriteOnly))],
    ['the role is unknown', ctx(guestAccessOf('SOMETHING_NEW'))],
  ])('is not offered where %s (fail closed)', (_, c) => {
    expect(offered(c)).toBeNull()
  })

  it('stays offered where only the READ and READ_WRITE leaves are off: it needs the drop-box leaf alone', () => {
    expect(offered(ctx(memberAccessOf({ publicShares: false, publicSharesContribute: false })))).toBe(DROPBOX_KIND)
  })
})
