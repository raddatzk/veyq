// @vitest-environment happy-dom
// Depot's rights matrix (`rights.ts`, inbox #401; the table in `docs/apps/depot.md` § Guests). Every row names
// the rights it grants; every other right must be false — a default flipped to true, a role or a kind falling through,
// a guest deleting or renaming: each is red here. The registry is the real one `src/test/setup.ts` fills (a DOM
// environment), so the Depot switch is read through core's `applicationWritable`.
import { describe, it, expect } from 'vitest'
import { canUploadFolder, depotRights, NO_DEPOT_RIGHTS, type DepotRights } from './rights'
import { DROPBOX_KIND, FILE_KIND } from './kinds'
import { FOLDER_KIND } from '../../core/node/kind'
import { nodeRights, type Access, type RightsTarget } from '../../core/access'
import { memberAccessOf, guestAccessOf } from '../../test/models'
import { accessShapes, rightsTargets, widerThanCore } from '../../test/nodeRightsShapes'
import { DEPOT, MEMORIES } from '../platform/capabilities'

const at = (kind: string | null, o: Partial<RightsTarget> = {}): RightsTarget => ({ kind, inIdentityFolder: false, shareable: kind === DROPBOX_KIND || kind === FOLDER_KIND || kind === FILE_KIND, ...o })
const grants = (...keys: (keyof DepotRights)[]): DepotRights => ({ ...NO_DEPOT_RIGHTS, ...Object.fromEntries(keys.map((k) => [k, true])) })

const member = memberAccessOf()
const pending = memberAccessOf({ readonly: true })
const depotOff = memberAccessOf({ applicationsOff: new Set([DEPOT]) })
const READ = guestAccessOf('READ')
const RW = guestAccessOf('READ_WRITE')
const WO = guestAccessOf('WRITE_ONLY')

const BOX = ['download', 'move', 'delete', 'moveGuestFolder', 'deleteGuestFolder'] as const
const FILE = ['download', 'replace', 'rename', 'move', 'delete'] as const

const rows: [string, Access, RightsTarget, DepotRights][] = [
  // --- member at a level: what the level takes (a file's rename, move and delete are its own row's, below) ---
  ['member at the scope root', member, at(null), grants('download', 'upload', 'replace', 'createDropBox')],
  ['member in a folder', member, at(FOLDER_KIND), grants('download', 'upload', 'replace', 'createDropBox')],
  // nothing is placed in a guest's identity folder (its files are deleted and moved out one by one: the FILE rows)
  ['member in a guest\'s identity folder: download only at the level', member, at(FOLDER_KIND, { inIdentityFolder: true }), grants('download')],
  ['member, pending deletion: download only', pending, at(FOLDER_KIND), grants('download')],
  ['member at the scope root, drop box off: no createDropBox (#440 gap 2)', memberAccessOf({ publicSharesDropBox: false }), at(null), grants('download', 'upload', 'replace')],
  ['member in a folder, drop box off: no createDropBox', memberAccessOf({ publicSharesDropBox: false }), at(FOLDER_KIND), grants('download', 'upload', 'replace')],
  ['member, pending deletion, identity folder', pending, at(FOLDER_KIND, { inIdentityFolder: true }), grants('download')],
  // --- member on a drop box ---
  ['member on a drop box: moves it, and its guests\' folders out', member, at(DROPBOX_KIND), grants(...BOX, 'shareUpload')],
  // drop box leaf: its links are WRITE_ONLY, so the READ leaf does not touch them (D9)
  ['member on a drop box, drop box off', memberAccessOf({ publicSharesDropBox: false }), at(DROPBOX_KIND), grants(...BOX)],
  ['member on a drop box, public shares off: its WRITE_ONLY links are not the READ leaf\'s', memberAccessOf({ publicShares: false }), at(DROPBOX_KIND), grants(...BOX, 'shareUpload')],
  ['member on a drop box the registry will not share', member, at(DROPBOX_KIND, { shareable: false }), grants(...BOX)],
  ['member on a drop box, pending deletion: its links are a read', pending, at(DROPBOX_KIND), grants('download', 'shareUpload')],
  // --- member on a file (its row, FileDetail) ---
  ['member on a file: everything', member, at(FILE_KIND), grants(...FILE, 'share', 'shareContribute')],
  ['member on a file, public shares off', memberAccessOf({ publicShares: false }), at(FILE_KIND), grants(...FILE, 'shareContribute')],
  ['member on a file, contribute off: READ link stays, no READ_WRITE role', memberAccessOf({ publicSharesContribute: false }), at(FILE_KIND), grants(...FILE, 'share')],
  ['member on a file, drop box off: file share untouched', memberAccessOf({ publicSharesDropBox: false }), at(FILE_KIND), grants(...FILE, 'share', 'shareContribute')],
  ['member on a file the registry will not share', member, at(FILE_KIND, { shareable: false }), grants(...FILE)],
  ['member on a file, pending deletion: download and its links (a read)', pending, at(FILE_KIND), grants('download', 'share', 'shareContribute')],
  ['member on a guest\'s file in an identity folder: download, delete, move out; no replace, rename, share (#391, #377 (a))', member, at(FILE_KIND, { inIdentityFolder: true }), grants('download', 'delete', 'move')],
  // --- member, Depot switched off (uc.platform.switch-application-off): read-only, but a delete and its restore stay ---
  ['member in a folder, Depot off: download only at the level', depotOff, at(FOLDER_KIND), grants('download')],
  ['member on a drop box, Depot off: download, delete it and its guests\' folders; no link, no move', depotOff, at(DROPBOX_KIND), grants('download', 'delete', 'deleteGuestFolder')],
  ['member on a file, Depot off: download and delete it; no replace, rename, move or link', depotOff, at(FILE_KIND), grants('download', 'delete')],
  ['member on a file, Memories off: untouched (another application\'s switch)', memberAccessOf({ applicationsOff: new Set([MEMORIES]) }), at(FILE_KIND), grants(...FILE, 'share', 'shareContribute')],
  ['member on another application\'s kind', member, at('MEMORY'), grants()],
  // --- guest READ ---
  ['READ guest in a folder', READ, at(FOLDER_KIND), grants('download')],
  ['READ guest on a drop box below its link', READ, at(DROPBOX_KIND), grants('download')],
  ['READ guest on a file (a single-file link, or inside a folder link): download only', READ, at(FILE_KIND), grants('download')],
  // --- guest READ_WRITE ---
  ['READ_WRITE guest in a folder: upload and replace, no rename, no delete', RW, at(FOLDER_KIND), grants('download', 'upload', 'replace')],
  ['READ_WRITE guest in an identity folder: nothing placed (#377 (a))', RW, at(FOLDER_KIND, { inIdentityFolder: true }), grants('download')],
  ['READ_WRITE guest on a drop box below its link: read-only, no move (#380)', RW, at(DROPBOX_KIND, { parentKind: FOLDER_KIND }), grants('download')],
  ['READ_WRITE guest on the root of a single-file link: download and replace, no move (the root stays)', RW, at(FILE_KIND), grants('download', 'replace')],
  ['READ_WRITE guest on a file inside a folder link: download, replace, move between folders', RW, at(FILE_KIND, { parentKind: FOLDER_KIND }), grants('download', 'replace', 'move')],
  ['READ_WRITE guest on a file in an identity folder: download only (#377 (a))', RW, at(FILE_KIND, { parentKind: FOLDER_KIND, inIdentityFolder: true }), grants('download')],
  ['READ_WRITE guest on a file whose parent is no folder: not moved', RW, at(FILE_KIND, { parentKind: DROPBOX_KIND }), grants('download', 'replace')],
  // --- guest WRITE_ONLY ---
  ['WRITE_ONLY guest on its drop box: sends only', WO, at(DROPBOX_KIND), grants('upload')],
  ['WRITE_ONLY guest in a folder (no such root)', WO, at(FOLDER_KIND), grants()],
  // --- fail closed ---
  ['a guest at the scope root (never there)', RW, at(null), grants()],
  ['a WRITE_ONLY guest on a file (the backend refuses that link)', WO, at(FILE_KIND), grants()],
  ['an unknown role', guestAccessOf('OWNER'), at(FOLDER_KIND), grants()],
  ['an unknown mode', { mode: 'operator' } as unknown as Access, at(FOLDER_KIND), grants()],
]

describe('depotRights: the matrix', () => {
  it.each(rows)('%s', (_label, access, target, expected) => {
    expect(depotRights(access, target)).toEqual(expected)
  })

  it('defaults to nothing', () => {
    for (const [k, v] of Object.entries(NO_DEPOT_RIGHTS)) expect(v, k).toBe(false)
    expect(Object.isFrozen(NO_DEPOT_RIGHTS)).toBe(true)
  })

  it('no guest ever renames, deletes, shares or creates a drop box', () => {
    for (const role of ['READ', 'READ_WRITE', 'WRITE_ONLY', 'X']) {
      for (const kind of [null, FOLDER_KIND, DROPBOX_KIND, FILE_KIND]) {
        for (const inIdentityFolder of [false, true]) {
          const c = depotRights(guestAccessOf(role), { kind, inIdentityFolder, shareable: true })
          expect([c.rename, c.delete, c.deleteGuestFolder, c.share, c.shareUpload, c.createDropBox], `${role} at ${kind}`).toEqual([false, false, false, false, false, false])
        }
      }
    }
  })

  // inbox #358 / #377 (a): a guest moves a file only as a contributor, only out of a folder, never in or out of a drop box.
  it('a guest moves only a READ_WRITE folder\'s files, never a drop box nor in an identity folder', () => {
    for (const role of ['READ', 'READ_WRITE', 'WRITE_ONLY', 'X']) {
      for (const kind of [null, FOLDER_KIND, DROPBOX_KIND, FILE_KIND]) {
        for (const inIdentityFolder of [false, true]) {
          for (const parentKind of [undefined, FOLDER_KIND]) {
            const c = depotRights(guestAccessOf(role), { kind, inIdentityFolder, shareable: true, parentKind })
            // a file: only out of a folder it was read in (never the link's root), never in an identity folder
            expect(c.move, `${role} at ${kind}, parent ${parentKind}, identity ${inIdentityFolder}`)
              .toBe(role === 'READ_WRITE' && kind === FILE_KIND && parentKind === FOLDER_KIND && !inIdentityFolder)
            expect(c.moveGuestFolder, `${role} at ${kind}`).toBe(false)
          }
        }
      }
    }
  })

  // Core owns the node-generic rights (`nodeRights`); Depot may only narrow them - a drop box's guests' folders included,
  // each an identity folder whose parent is the drop box.
  it('is never wider than core\'s node rights, for every access shape and target', () => {
    expect(widerThanCore(depotRights, [DEPOT, MEMORIES], [FILE_KIND, DROPBOX_KIND, 'MEMORY'])).toEqual([])
    const guestFolder: RightsTarget = { kind: FOLDER_KIND, inIdentityFolder: true, shareable: false, parentKind: DROPBOX_KIND }
    for (const [name, access] of accessShapes([DEPOT])) {
      const folder = nodeRights(access, guestFolder)
      for (const target of rightsTargets([DROPBOX_KIND])) {
        const c = depotRights(access, target)
        expect(!c.moveGuestFolder || folder.move, `${name}: moveGuestFolder`).toBe(true)
        expect(!c.deleteGuestFolder || folder.delete, `${name}: deleteGuestFolder`).toBe(true)
      }
    }
  })
})

// inbox #424: "Upload folder…" needs Depot's upload AND core's folder create at the level - a member and a READ_WRITE guest alike
// (share view = member view), never a reader, a drop box's own guest, a guest's identity folder or a pending deletion.
describe('canUploadFolder', () => {
  const cases: [string, Access, RightsTarget, boolean][] = [
    ['member at the scope root', member, at(null), true],
    ['member in a folder', member, at(FOLDER_KIND), true],
    ['member in a guest\'s identity folder', member, at(FOLDER_KIND, { inIdentityFolder: true }), false],
    ['member, pending deletion', pending, at(FOLDER_KIND), false],
    ['member on a drop box', member, at(DROPBOX_KIND), false],
    ['READ_WRITE guest in a folder', RW, at(FOLDER_KIND), true],
    ['READ_WRITE guest in an identity folder', RW, at(FOLDER_KIND, { inIdentityFolder: true }), false],
    ['READ guest in a folder', READ, at(FOLDER_KIND), false],
    ['WRITE_ONLY guest on its drop box', WO, at(DROPBOX_KIND), false],
  ]
  it.each(cases)('%s', (_name, access, target, expected) => {
    expect(canUploadFolder(access, target)).toBe(expected)
  })
})
