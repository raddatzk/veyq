// Files' capability matrix (`capabilities.ts`, inbox #401; the table in `docs/apps/files.md` § Guests). Every row names
// the rights it grants; every other right must be false — a default flipped to true, a role or a kind falling through,
// a guest deleting or renaming: each is red here.
import { describe, it, expect } from 'vitest'
import { filesCapabilities, NO_FILES_CAPABILITIES, type FilesCapabilities } from './capabilities'
import { DROPBOX_KIND, FILE_KIND } from './kinds'
import { FOLDER_KIND } from '../../core/node/kind'
import type { Access, CapabilityTarget } from '../../core/access'
import { memberAccessOf, guestAccessOf } from '../../test/models'

const at = (kind: string | null, o: Partial<CapabilityTarget> = {}): CapabilityTarget => ({ kind, inIdentityFolder: false, shareable: kind === DROPBOX_KIND || kind === FOLDER_KIND, ...o })
const grants = (...keys: (keyof FilesCapabilities)[]): FilesCapabilities => ({ ...NO_FILES_CAPABILITIES, ...Object.fromEntries(keys.map((k) => [k, true])) })

const member = memberAccessOf()
const pending = memberAccessOf({ readonly: true })
const READ = guestAccessOf('READ')
const RW = guestAccessOf('READ_WRITE')
const WO = guestAccessOf('WRITE_ONLY')

const rows: [string, Access, CapabilityTarget, FilesCapabilities][] = [
  // --- member at a level ---
  ['member at the scope root', member, at(null), grants('download', 'upload', 'replace', 'rename', 'deleteFile', 'createDropBox')],
  ['member in a folder', member, at(FOLDER_KIND), grants('download', 'upload', 'replace', 'rename', 'deleteFile', 'createDropBox')],
  ['member in a guest\'s identity folder: download and delete per file (#391)', member, at(FOLDER_KIND, { inIdentityFolder: true }), grants('download', 'deleteFile')],
  ['member, pending deletion: download only', pending, at(FOLDER_KIND), grants('download')],
  ['member, pending deletion, identity folder', pending, at(FOLDER_KIND, { inIdentityFolder: true }), grants('download')],
  // --- member on a drop box ---
  ['member on a drop box', member, at(DROPBOX_KIND), grants('download', 'share', 'deleteFile', 'deleteNode')],
  ['member on a drop box, public links off', memberAccessOf({ publicLinks: false }), at(DROPBOX_KIND), grants('download', 'deleteFile', 'deleteNode')],
  ['member on a drop box the registry will not share', member, at(DROPBOX_KIND, { shareable: false }), grants('download', 'deleteFile', 'deleteNode')],
  ['member on a drop box, pending deletion: its links are a read', pending, at(DROPBOX_KIND), grants('download', 'share')],
  ['member on a file (never on screen)', member, at(FILE_KIND), grants()],
  ['member on another application\'s kind', member, at('MEMORY'), grants()],
  // --- guest READ ---
  ['READ guest in a folder', READ, at(FOLDER_KIND), grants('download')],
  ['READ guest on a drop box below its link', READ, at(DROPBOX_KIND), grants('download')],
  // --- guest READ_WRITE ---
  ['READ_WRITE guest in a folder: upload and replace, no rename, no delete', RW, at(FOLDER_KIND), grants('download', 'upload', 'replace')],
  ['READ_WRITE guest in an identity folder: nothing placed', RW, at(FOLDER_KIND, { inIdentityFolder: true }), grants('download')],
  ['READ_WRITE guest on a drop box below its link: read-only (#380)', RW, at(DROPBOX_KIND), grants('download')],
  // --- guest WRITE_ONLY ---
  ['WRITE_ONLY guest on its drop box: sends only', WO, at(DROPBOX_KIND), grants('upload')],
  ['WRITE_ONLY guest in a folder (no such root)', WO, at(FOLDER_KIND), grants()],
  // --- fail closed ---
  ['a guest at the scope root (never there)', RW, at(null), grants()],
  ['a guest on a file', RW, at(FILE_KIND), grants()],
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
})
