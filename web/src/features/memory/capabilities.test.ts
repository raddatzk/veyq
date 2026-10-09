// Memories' capability matrix (`capabilities.ts`, inbox #401; the table in `docs/apps/memories.md` § What a guest may
// do). Every row names the rights it grants; every other right must be false — a default flipped to true, a role or a
// kind falling through, a guest reaching a member-only function: each is red here.
import { describe, it, expect } from 'vitest'
import { memoryCapabilities, NO_MEMORY_CAPABILITIES, type MemoryCapabilities } from './capabilities'
import { MEMORY_KIND, CHAPTER_KIND } from './kinds'
import { FOLDER_KIND } from '../../core/node/kind'
import type { Access, CapabilityTarget } from '../../core/access'
import { memberAccessOf, guestAccessOf } from '../../test/models'

const FILE = 'FILE'
const at = (kind: string | null, o: Partial<CapabilityTarget> = {}): CapabilityTarget => ({ kind, inIdentityFolder: false, shareable: kind !== null, ...o })
type Grant = Partial<MemoryCapabilities>
const grants = (g: Grant): MemoryCapabilities => ({ ...NO_MEMORY_CAPABILITIES, ...g })

const member = memberAccessOf()
const pending = memberAccessOf({ readonly: true })
const READ = guestAccessOf('READ')
const RW = guestAccessOf('READ_WRITE')
const WO = guestAccessOf('WRITE_ONLY')

/** A member on an opened Memory or chapter, as the detail has always offered it (cr-0049 … cr-0113). */
const MEMBER_ON_NODE: Grant = {
  navigate: true, download: true, upload: true, deleteMedia: 'any', editMeta: true, setCover: true, favorite: true, mark: true,
  share: true, shareUpload: true, rename: true, deleteNode: true, mediaTrash: true, buckets: true,
}

const rows: [string, Access, CapabilityTarget, Grant][] = [
  // --- member, at a level (the section) ---
  ['member at the scope root', member, at(null), { navigate: true, favorite: true, createMemory: true }],
  ['member in a folder', member, at(FOLDER_KIND), { navigate: true, favorite: true, createMemory: true }],
  ['member in a guest\'s identity folder: no Memory there', member, at(FOLDER_KIND, { inIdentityFolder: true }), { navigate: true, favorite: true }],
  ['member, pending deletion, in a folder: the pin stays', pending, at(FOLDER_KIND), { navigate: true, favorite: true }],
  // --- member, on a node (the detail) ---
  ['member on a Memory', member, at(MEMORY_KIND), { ...MEMBER_ON_NODE, createChapter: true }],
  ['member on a chapter: no chapter inside a chapter', member, at(CHAPTER_KIND), MEMBER_ON_NODE],
  ['member, public links off', memberAccessOf({ publicLinks: false }), at(MEMORY_KIND), { ...MEMBER_ON_NODE, createChapter: true, share: false, shareUpload: false }],
  ['member, uploader links off', memberAccessOf({ uploaderLinks: false }), at(MEMORY_KIND), { ...MEMBER_ON_NODE, createChapter: true, shareUpload: false }],
  ['member, a kind the registry will not share', member, at(MEMORY_KIND, { shareable: false }), { ...MEMBER_ON_NODE, createChapter: true, share: false, shareUpload: false }],
  ['member, pending deletion, on a Memory: reads, links, pins, marks only', pending, at(MEMORY_KIND), {
    navigate: true, download: true, favorite: true, mark: true, share: true, shareUpload: true, mediaTrash: true, buckets: true,
  }],
  ['member on another application\'s kind', member, at(FILE), {}],
  ['member on an unknown kind', member, at('ZULU'), {}],
  // --- guest READ ---
  ['READ guest in a folder', READ, at(FOLDER_KIND), { navigate: true }],
  ['READ guest on a Memory', READ, at(MEMORY_KIND), { navigate: true, download: true }],
  ['READ guest on a chapter', READ, at(CHAPTER_KIND), { navigate: true, download: true }],
  // --- guest READ_WRITE ---
  ['READ_WRITE guest in a folder: creates a Memory', RW, at(FOLDER_KIND), { navigate: true, createMemory: true }],
  ['READ_WRITE guest in an identity folder', RW, at(FOLDER_KIND, { inIdentityFolder: true }), { navigate: true }],
  ['READ_WRITE guest on a Memory: uploads, deletes its own', RW, at(MEMORY_KIND), { navigate: true, download: true, upload: true, deleteMedia: 'own' }],
  ['READ_WRITE guest on a chapter', RW, at(CHAPTER_KIND), { navigate: true, download: true, upload: true, deleteMedia: 'own' }],
  // --- guest WRITE_ONLY ---
  ['WRITE_ONLY guest on a Memory: sends, removes its own, reads nothing', WO, at(MEMORY_KIND), { upload: true, deleteMedia: 'own' }],
  ['WRITE_ONLY guest on a chapter', WO, at(CHAPTER_KIND), { upload: true, deleteMedia: 'own' }],
  ['WRITE_ONLY guest in a folder (no such root)', WO, at(FOLDER_KIND), {}],
  // --- fail closed ---
  ['a guest at the scope root (never there)', RW, at(null), {}],
  ['a guest on another application\'s kind', RW, at(FILE), {}],
  ['an unknown role on a Memory', guestAccessOf('OWNER'), at(MEMORY_KIND), {}],
  ['an unknown mode', { mode: 'operator' } as unknown as Access, at(MEMORY_KIND), {}],
]

describe('memoryCapabilities: the matrix', () => {
  it.each(rows)('%s', (_label, access, target, expected) => {
    expect(memoryCapabilities(access, target)).toEqual(grants(expected))
  })

  it('defaults to nothing: every right of the empty set is false or none', () => {
    for (const [k, v] of Object.entries(NO_MEMORY_CAPABILITIES)) expect(v, k).toBe(k === 'deleteMedia' ? 'none' : false)
    expect(Object.isFrozen(NO_MEMORY_CAPABILITIES)).toBe(true)
  })
})

// Rule 2 of #401: pins (Master Key), item marks (K_SC at E_0), the node's sealed meta, the media trash, the organizer, the
// links and the node's own rename/delete never reach a guest — in any role, at any kind, wherever it stands.
describe('memoryCapabilities: member-only functions', () => {
  const MEMBER_ONLY: (keyof MemoryCapabilities)[] = [
    'favorite', 'mark', 'editMeta', 'setCover', 'share', 'shareUpload', 'rename', 'deleteNode', 'mediaTrash', 'buckets', 'createChapter',
  ]
  it('no guest role grants any of them', () => {
    for (const role of ['READ', 'READ_WRITE', 'WRITE_ONLY', 'X']) {
      for (const kind of [null, FOLDER_KIND, MEMORY_KIND, CHAPTER_KIND, FILE]) {
        for (const inIdentityFolder of [false, true]) {
          const c = memoryCapabilities(guestAccessOf(role), { kind, inIdentityFolder, shareable: true })
          for (const k of MEMBER_ONLY) expect(c[k], `${role} at ${kind}: ${k}`).toBe(false)
          expect(c.deleteMedia, `${role} at ${kind}`).not.toBe('any')
        }
      }
    }
  })
})
