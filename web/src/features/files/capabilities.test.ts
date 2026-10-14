// Files' capability matrix (`capabilities.ts`, inbox #401; the table in `docs/apps/files.md` § Guests). Every row names
// the rights it grants; every other right must be false — a default flipped to true, a role or a kind falling through,
// a guest deleting or renaming: each is red here.
import { describe, it, expect } from 'vitest'
import { canUploadFolder, filesCapabilities, NO_FILES_CAPABILITIES, type FilesCapabilities } from './capabilities'
import { DROPBOX_KIND, FILE_KIND } from './kinds'
import { FOLDER_KIND } from '../../core/node/kind'
import type { Access, CapabilityTarget } from '../../core/access'
import { memberAccessOf, guestAccessOf } from '../../test/models'

const at = (kind: string | null, o: Partial<CapabilityTarget> = {}): CapabilityTarget => ({ kind, inIdentityFolder: false, shareable: kind === DROPBOX_KIND || kind === FOLDER_KIND || kind === FILE_KIND, ...o })
const grants = (...keys: (keyof FilesCapabilities)[]): FilesCapabilities => ({ ...NO_FILES_CAPABILITIES, ...Object.fromEntries(keys.map((k) => [k, true])) })

const member = memberAccessOf()
const pending = memberAccessOf({ readonly: true })
const READ = guestAccessOf('READ')
const RW = guestAccessOf('READ_WRITE')
const WO = guestAccessOf('WRITE_ONLY')

const rows: [string, Access, CapabilityTarget, FilesCapabilities][] = [
  // --- member at a level ---
  ['member at the scope root', member, at(null), grants('download', 'upload', 'replace', 'rename', 'deleteFile', 'createDropBox', 'moveFile')],
  ['member in a folder', member, at(FOLDER_KIND), grants('download', 'upload', 'replace', 'rename', 'deleteFile', 'createDropBox', 'moveFile')],
  // …and moves a file out of it (a move out of a drop box is a member's, #358, #377 (a)); nothing is placed in it
  ['member in a guest\'s identity folder: download, delete and move out per file (#391, #358)', member, at(FOLDER_KIND, { inIdentityFolder: true }), grants('download', 'deleteFile', 'moveFile')],
  ['member, pending deletion: download only', pending, at(FOLDER_KIND), grants('download')],
  ['member, pending deletion, identity folder', pending, at(FOLDER_KIND, { inIdentityFolder: true }), grants('download')],
  // --- member on a drop box ---
  ['member on a drop box: moves it, and its guests\' folders out', member, at(DROPBOX_KIND), grants('download', 'share', 'deleteFile', 'deleteNode', 'moveFile', 'moveNode')],
  ['member on a drop box, public links off', memberAccessOf({ publicLinks: false }), at(DROPBOX_KIND), grants('download', 'deleteFile', 'deleteNode', 'moveFile', 'moveNode')],
  ['member on a drop box the registry will not share', member, at(DROPBOX_KIND, { shareable: false }), grants('download', 'deleteFile', 'deleteNode', 'moveFile', 'moveNode')],
  ['member on a drop box, pending deletion: its links are a read', pending, at(DROPBOX_KIND), grants('download', 'share')],
  // --- member on an opened file (FileDetail) ---
  ['member on an opened file: everything', member, at(FILE_KIND), grants('download', 'replace', 'rename', 'share', 'deleteNode', 'moveNode')],
  ['member on an opened file, public links off', memberAccessOf({ publicLinks: false }), at(FILE_KIND), grants('download', 'replace', 'rename', 'deleteNode', 'moveNode')],
  ['member on an opened file the registry will not share', member, at(FILE_KIND, { shareable: false }), grants('download', 'replace', 'rename', 'deleteNode', 'moveNode')],
  ['member on an opened file, pending deletion: download and its links (a read)', pending, at(FILE_KIND), grants('download', 'share')],
  ['member on a guest\'s file in an identity folder: download, delete, move out; no replace, rename, share', member, at(FILE_KIND, { inIdentityFolder: true }), grants('download', 'deleteNode', 'moveNode')],
  ['member on another application\'s kind', member, at('MEMORY'), grants()],
  // --- guest READ ---
  ['READ guest in a folder', READ, at(FOLDER_KIND), grants('download')],
  ['READ guest on a drop box below its link', READ, at(DROPBOX_KIND), grants('download')],
  ['READ guest on an opened file (a single-file link, or inside a folder link): download only', READ, at(FILE_KIND), grants('download')],
  // --- guest READ_WRITE ---
  ['READ_WRITE guest in a folder: upload, replace and move, no rename, no delete', RW, at(FOLDER_KIND), grants('download', 'upload', 'replace', 'moveFile')],
  ['READ_WRITE guest in an identity folder: nothing placed, nothing moved out (#377 (a))', RW, at(FOLDER_KIND, { inIdentityFolder: true }), grants('download')],
  ['READ_WRITE guest on a drop box below its link: read-only, no move (#380)', RW, at(DROPBOX_KIND), grants('download')],
  ['READ_WRITE guest on the root of a single-file link: download and replace, no move (the root stays)', RW, at(FILE_KIND), grants('download', 'replace')],
  ['READ_WRITE guest on a file inside a folder link: download, replace, move between folders', RW, at(FILE_KIND, { parentKind: FOLDER_KIND }), grants('download', 'replace', 'moveNode')],
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

describe('filesCapabilities: the matrix', () => {
  it.each(rows)('%s', (_label, access, target, expected) => {
    expect(filesCapabilities(access, target)).toEqual(expected)
  })

  it('defaults to nothing', () => {
    for (const [k, v] of Object.entries(NO_FILES_CAPABILITIES)) expect(v, k).toBe(false)
    expect(Object.isFrozen(NO_FILES_CAPABILITIES)).toBe(true)
  })

  it('no guest ever renames, deletes, shares or creates a drop box', () => {
    for (const role of ['READ', 'READ_WRITE', 'WRITE_ONLY', 'X']) {
      for (const kind of [null, FOLDER_KIND, DROPBOX_KIND, FILE_KIND]) {
        for (const inIdentityFolder of [false, true]) {
          const c = filesCapabilities(guestAccessOf(role), { kind, inIdentityFolder, shareable: true })
          expect([c.rename, c.deleteFile, c.deleteNode, c.share, c.createDropBox], `${role} at ${kind}`).toEqual([false, false, false, false, false])
        }
      }
    }
  })

  // inbox #358 / #377 (a): a guest moves a file only as a contributor, only in a folder, never in or out of a drop box.
  it('a guest moves only a READ_WRITE folder\'s files, never a drop box nor in an identity folder', () => {
    for (const role of ['READ', 'READ_WRITE', 'WRITE_ONLY', 'X']) {
      for (const kind of [null, FOLDER_KIND, DROPBOX_KIND, FILE_KIND]) {
        for (const inIdentityFolder of [false, true]) {
          for (const parentKind of [undefined, FOLDER_KIND]) {
            const c = filesCapabilities(guestAccessOf(role), { kind, inIdentityFolder, shareable: true, parentKind })
            expect(c.moveFile, `${role} at ${kind}, identity ${inIdentityFolder}`).toBe(role === 'READ_WRITE' && kind === FOLDER_KIND && !inIdentityFolder)
            // the opened file itself: only out of a folder it was read in (never the link's root), never in an identity folder
            expect(c.moveNode, `${role} at ${kind}, parent ${parentKind}, identity ${inIdentityFolder}`)
              .toBe(role === 'READ_WRITE' && kind === FILE_KIND && parentKind === FOLDER_KIND && !inIdentityFolder)
          }
        }
      }
    }
  })
})

// inbox #424: "Upload folder…" needs Files' upload AND core's folder create at the level - a member and a READ_WRITE guest alike
// (share view = member view), never a reader, a drop box's own guest, a guest's identity folder or a pending deletion.
describe('canUploadFolder', () => {
  const cases: [string, Access, CapabilityTarget, boolean][] = [
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
