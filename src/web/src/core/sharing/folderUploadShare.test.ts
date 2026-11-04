// Core's own fail-closed layer of a level's upload-only link (`./folderUploadShare.ts`), proven with an application whose
// `allowed` says yes to everyone: core alone keeps it from a share guest of any role, from a level that is no folder, from
// a guest's identity folder, from an application switched off and from a level its kind cannot live at. Depot's own answer
// over the real registry is `features/depot/uploadShare.test.ts`'s.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent } from 'vue'

const holder = vi.hoisted(() => ({ reg: null as null | import('../applications/registry').Registry }))
vi.mock('../applications/registry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../applications/registry')>()
  const r = () => (holder.reg ??= actual.createRegistry())
  return {
    ...actual,
    registry: new Proxy({} as import('../applications/registry').Registry, {
      get: (_t, name: string) => (r() as unknown as Record<string, (...a: unknown[]) => unknown>)[name].bind(r()),
    }),
  }
})

import { folderUploadShareAt } from './folderUploadShare'
import { createRegistry, type LevelActionContext } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import type { Access } from '../access'
import { ShareRole } from '../../api/generated/sharing'
import { nothingOfItsOwnToRekey } from '../node/rekey/hooks'
import { testApp, testKind } from '../../test/testApplication'
import { treeNode, memberAccessOf, memberTreeOf, guestAccessOf } from '../../test/models'

const FOLDER = treeNode({ id: 'f1', kind: FOLDER_KIND, name: 'Trips', parentId: null })
const OTHER = treeNode({ id: 'o1', kind: 'SHELF', name: 'Shelf', parentId: null })
const allowed = vi.fn(() => true)

function ctx(access: Access, over: { folder?: typeof FOLDER | null; identity?: boolean } = {}): LevelActionContext {
  const tree = { ...memberTreeOf(() => [FOLDER, OTHER]), inIdentityFolder: () => over.identity ?? false }
  return { access, tree, folder: over.folder === undefined ? FOLDER : over.folder, epochs: null }
}

beforeEach(() => {
  holder.reg = createRegistry()
  allowed.mockClear()
  testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'BOX', icon: 'inbox', placement: { root: true, parents: [FOLDER_KIND, 'SHELF'] } })
  testApp(holder.reg).shareBody('BOX', defineComponent({ render: () => null }))
  testApp(holder.reg).folderUploadShare('BOX', { allowed, hint: 'test.hint', create: vi.fn() })
})

describe('folderUploadShareAt', () => {
  it('answers the registration for a member at a folder and at the top level, asking the application', () => {
    expect(folderUploadShareAt(ctx(memberAccessOf()))?.kind).toBe('BOX')
    expect(folderUploadShareAt(ctx(memberAccessOf(), { folder: null }))?.kind).toBe('BOX')
    expect(allowed).toHaveBeenCalledTimes(2)
  })

  it('is null where nobody registered it', () => {
    holder.reg = createRegistry()
    expect(folderUploadShareAt(ctx(memberAccessOf()))).toBeNull()
  })

  it.each<[string, LevelActionContext]>([
    ['a READ guest', ctx(guestAccessOf(ShareRole.Read))],
    ['a READ_WRITE guest', ctx(guestAccessOf(ShareRole.ReadWrite))],
    ['a WRITE_ONLY guest', ctx(guestAccessOf(ShareRole.WriteOnly))],
    ['a level that is no folder, though the kind could live in it', ctx(memberAccessOf(), { folder: OTHER })],
    ['a guest\'s identity folder', ctx(memberAccessOf(), { identity: true })],
    ['the application switched off', ctx(memberAccessOf({ applicationsOff: new Set(['test']) }))],
  ])('is null for %s, whatever the application says', (_, c) => {
    expect(folderUploadShareAt(c)).toBeNull()
  })

  it('is null where the kind cannot live at the level (its placement)', () => {
    holder.reg = createRegistry()
    testKind(holder.reg, { rekeyContent: nothingOfItsOwnToRekey, kind: 'BOX', icon: 'inbox', placement: { root: false, parents: [] } })
    testApp(holder.reg).shareBody('BOX', defineComponent({ render: () => null }))
    testApp(holder.reg).folderUploadShare('BOX', { allowed, hint: 'test.hint', create: vi.fn() })
    expect(folderUploadShareAt(ctx(memberAccessOf()))).toBeNull()
    expect(folderUploadShareAt(ctx(memberAccessOf(), { folder: null }))).toBeNull()
  })
})
