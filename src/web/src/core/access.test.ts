// Core's half of "who looks" (inbox #401, `core/access.ts`): the shapes of `Access` and `TreeSource`, the member's
// factories over the decrypted tree, the rights target core builds from the registry, and core's own fail-closed
// matrix for its one kind, FOLDER. Kinds other than FOLDER are made-up tokens: core knows no application, nor does its
// test. The registry is a fresh one per test behind a delegating export (as `LevelView.test.ts` does).
import { describe, it, expect, expectTypeOf, vi, beforeEach } from 'vitest'
import { defineComponent } from 'vue'

const holder = vi.hoisted(() => ({ reg: null as null | import('./applications/registry').Registry }))
vi.mock('./applications/registry', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./applications/registry')>()
  const r = () => (holder.reg ??= actual.createRegistry())
  return {
    ...actual,
    registry: new Proxy({} as import('./applications/registry').Registry, {
      get: (_t, name: string) => (r() as unknown as Record<string, (...a: unknown[]) => unknown>)[name].bind(r()),
    }),
  }
})
vi.mock('./node/tree', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./node/tree')>()),
  // The chain itself is `tree.test.ts`'s; here: which scope, keys and node the source asks it for.
  nodeContentKey: (scopeId: string, _e: unknown, _t: unknown, nodeId: string) => new TextEncoder().encode(`${scopeId}/${nodeId}`),
}))

import {
  folderRights, memberAccess, memberTreeSource, targetOf,
  type Access, type RightsTarget, type FolderRights, type GuestAccess, type MemberAccess, type TreeSource,
} from './access'
import { createRegistry } from './applications/registry'
import { FOLDER_KIND } from './node/kind'
import type { TreeNode } from './node/tree'
import type { GuestIdentity } from './sharing/guestIdentity'
import type { PublicShareHead } from './sharing/resolvePublicShare'
import type { ShareRole } from '../api/generated/sharing'
import type { EpochKeys } from '../features/spaces/epochs'
import { treeNode, memberAccessOf, guestAccessOf } from '../test/models'
import { nothingOfItsOwnToRekey } from './node/rekey/hooks'

const EPOCHS: EpochKeys = { current: 0, keys: new Map([[0, new Uint8Array(32)]]) } as unknown as EpochKeys
const n = (id: string, kind: string, parentId: string | null, over: Partial<TreeNode> = {}): TreeNode =>
  treeNode({ id, kind, name: id, parentId, ...over })

beforeEach(() => {
  holder.reg = createRegistry()
})

describe('Access: the two shapes', () => {
  it('a member carries its scope, its keys, its pending deletion and the instance\'s sharing switches — nothing of a link', () => {
    const a = memberAccess({ scopeId: 's', epochs: EPOCHS, readonly: true, publicShares: true, publicSharesContribute: false, publicSharesDropBox: true })
    expect(a).toEqual({ mode: 'member', scopeId: 's', epochs: EPOCHS, readonly: true, publicShares: true, publicSharesContribute: false, publicSharesDropBox: true })
    // One boolean per register leaf (ADR 0088), each from one manifest id: no field is an AND of two.
    expectTypeOf<MemberAccess>().toEqualTypeOf<{
      readonly mode: 'member'; readonly scopeId: string; readonly epochs: EpochKeys | null; readonly readonly: boolean
      readonly publicShares: boolean; readonly publicSharesContribute: boolean; readonly publicSharesDropBox: boolean
    }>()
  })

  // S2 builds it (the share page after its gates); here only its shape, so S2 builds against a fixed type.
  it('a guest carries its link, its role, the resolved head, the password token, the fragment key, the one naming flow and the re-resolve — nothing of a scope', () => {
    expectTypeOf<GuestAccess['mode']>().toEqualTypeOf<'guest'>()
    expectTypeOf<GuestAccess['shareId']>().toEqualTypeOf<string>()
    expectTypeOf<GuestAccess['role']>().toEqualTypeOf<ShareRole>()
    expectTypeOf<GuestAccess['pwToken']>().toEqualTypeOf<string | undefined>()
    expectTypeOf<GuestAccess['head']>().toEqualTypeOf<PublicShareHead>()
    expectTypeOf<GuestAccess['fragmentKey']>().toEqualTypeOf<string>()
    expectTypeOf<GuestAccess['requestGuest']>().toEqualTypeOf<() => Promise<GuestIdentity | null>>()
    expectTypeOf<GuestAccess['reload']>().toEqualTypeOf<() => Promise<void>>()
    // no member state is reachable from a guest's access
    expectTypeOf<GuestAccess>().not.toHaveProperty('epochs')
    expectTypeOf<GuestAccess>().not.toHaveProperty('scopeId')
    expectTypeOf<GuestAccess>().not.toHaveProperty('readonly')
    const g = guestAccessOf('READ')
    expect(Object.keys(g).sort()).toEqual(['fragmentKey', 'head', 'mode', 'reload', 'requestGuest', 'role', 'shareId'])
  })

  it('is told apart by `mode` alone (a discriminated union)', () => {
    const which = (a: Access) => (a.mode === 'member' ? a.scopeId : a.shareId)
    expect(which(memberAccessOf({ scopeId: 'scope-x' }))).toBe('scope-x')
    expect(which(guestAccessOf('READ', { shareId: 'link-y' }))).toBe('link-y')
  })
})

describe('memberTreeSource: the member\'s tree', () => {
  const nodes = [
    n('box', 'ALPHA', null),
    n('anna-late', FOLDER_KIND, 'box', { name: 'Anna', shareIdentityId: 'i2', createdAt: 2000 }),
    n('anna-early', FOLDER_KIND, 'box', { name: 'Anna', shareIdentityId: 'i1', createdAt: 1000 }),
    n('deep', FOLDER_KIND, 'anna-late'),
    n('trips', FOLDER_KIND, null),
  ]
  let scope = '~me'
  let epochs: EpochKeys | null = EPOCHS
  const tree = memberTreeSource({ nodes: () => nodes, scopeId: () => 'scope-1', epochs: () => epochs, scope: () => scope })

  it('is rooted at the scope root (no node) and finds a node by id, none for an id it does not hold', () => {
    expect(tree.rootId).toBeNull()
    expect(tree.node('deep')?.id).toBe('deep')
    expect(tree.node('nope')).toBeNull()
  })

  it('lists the children of a node and of the scope root', () => {
    expect(tree.children(null).map((c) => c.id)).toEqual(['box', 'trips'])
    expect(tree.children('box').map((c) => c.id)).toEqual(['anna-late', 'anna-early'])
  })

  it('opens a key through the chain with the scope and the CURRENT keys, and none while the scope is locked', () => {
    expect(new TextDecoder().decode(tree.keyOf('deep')!)).toBe('scope-1/deep')
    epochs = null
    expect(tree.keyOf('deep')).toBeNull()
    epochs = EPOCHS
  })

  it('names a node as its siblings tell it apart (creation order), nothing for an unknown id', () => {
    expect(tree.nameOf('anna-late')).toBe('Anna (2)')
    expect(tree.nameOf('anna-early')).toBe('Anna')
    expect(tree.nameOf('nope')).toBe('')
  })

  it('knows a guest\'s identity folder and every node below it, and nothing else', () => {
    expect(tree.inIdentityFolder('anna-late')).toBe(true)
    expect(tree.inIdentityFolder('deep')).toBe(true)
    expect(tree.inIdentityFolder('box')).toBe(false)
    expect(tree.inIdentityFolder('trips')).toBe(false)
    expect(tree.inIdentityFolder(null)).toBe(false)
  })

  it('stops on a served parent cycle instead of spinning', () => {
    const cyc = memberTreeSource({ nodes: () => [n('a', FOLDER_KIND, 'b'), n('b', FOLDER_KIND, 'a')], scopeId: () => 's', epochs: () => null, scope: () => '' })
    expect(cyc.inIdentityFolder('a')).toBe(false)
  })

  it('links a node by its fragment slug path in the CURRENT scope, the root by the bare scope', () => {
    expect(tree.linkTo('deep')).toEqual({ hash: '#~me/box/anna-2/deep/' })
    expect(tree.linkTo(null)).toEqual({ hash: '#~me' })
    scope = ''
    expect(tree.linkTo('trips')).toEqual({ hash: '#trips/' })
    scope = '~me'
  })

  it('is a TreeSource of the member\'s TreeNode, usable where the base type is asked', () => {
    expectTypeOf(tree).toEqualTypeOf<TreeSource<TreeNode>>()
    expectTypeOf(tree).toMatchTypeOf<TreeSource>()
  })
})

describe('targetOf: the node as a rights function is asked about it', () => {
  const tree = memberTreeSource({ nodes: () => [n('f', FOLDER_KIND, null, { shareIdentityId: 'i' }), n('x', 'ALPHA', null)], scopeId: () => 's', epochs: () => null, scope: () => '' })
  const Dialog = defineComponent({ render: () => null })

  it('at the scope root: no kind, no identity folder, nothing to share', () => {
    expect(targetOf(tree, null)).toEqual({ kind: null, inIdentityFolder: false, shareable: false })
  })

  it('a kind is shareable only when its descriptor says so AND brings a share dialog (FOLDER: core\'s own)', () => {
    expect(targetOf(tree, tree.node('f'))).toEqual({ kind: FOLDER_KIND, inIdentityFolder: true, shareable: true, parentKind: null })
    expect(targetOf(tree, tree.node('x')).shareable, 'a kind nobody registered').toBe(false)
    holder.reg!.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'file', shareable: true })
    expect(targetOf(tree, tree.node('x')).shareable, 'shareable, but no dialog to mount').toBe(false)
    holder.reg = createRegistry()
    holder.reg.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'file', shareable: false, shareDialog: Dialog })
    expect(targetOf(tree, tree.node('x')).shareable, 'a dialog, but the descriptor says no').toBe(false)
    holder.reg = createRegistry()
    holder.reg.registerKind({ rekeyContent: nothingOfItsOwnToRekey, kind: 'ALPHA', icon: 'file', shareable: true, shareDialog: Dialog })
    expect(targetOf(tree, tree.node('x')).shareable, 'control: both').toBe(true)
  })

  // inbox #358: a guest moves only what a FOLDER holds, and never the link's root, whose parent lies above the link.
  it('carries the parent\'s kind as the tree reads it: null at the scope root, unknown at the view\'s root', () => {
    const nodes = [n('top', FOLDER_KIND, null), n('m', 'ALPHA', 'top'), n('c', 'BETA', 'm')]
    const member = memberTreeSource({ nodes: () => nodes, scopeId: () => 's', epochs: () => null, scope: () => '' })
    expect(targetOf(member, member.node('top')).parentKind, 'a member\'s top level').toBeNull()
    expect(targetOf(member, member.node('m')).parentKind).toBe(FOLDER_KIND)
    expect(targetOf(member, member.node('c')).parentKind).toBe('ALPHA')
    // A guest's view rooted at `m`: the served root carries parentId null, the scope root's marker — it must not read so.
    const guestNodes = [n('m', 'ALPHA', null), n('c', 'BETA', 'm')]
    const guest: TreeSource = {
      ...memberTreeSource({ nodes: () => guestNodes, scopeId: () => 's', epochs: () => null, scope: () => '' }),
      rootId: 'm',
    }
    expect(targetOf(guest, guest.node('m')).parentKind, 'the link\'s root').toBeUndefined()
    expect(targetOf(guest, guest.node('c')).parentKind).toBe('ALPHA')
    // a parent the tree does not serve is unknown, never the scope root
    const orphan = memberTreeSource({ nodes: () => [n('o', FOLDER_KIND, 'gone')], scopeId: () => 's', epochs: () => null, scope: () => '' })
    expect(targetOf(orphan, orphan.node('o')).parentKind).toBeUndefined()
  })
})

// Core's matrix for FOLDER (`folderRights`). Every row names the rights it grants; every other right must be
// false — so a right that turns true by accident (a default flipped, a role or kind falling through) is red.
describe('folderRights: core\'s fail-closed matrix', () => {
  const NONE: FolderRights = { navigate: false, createFolder: false, rename: false, deleteNode: false, share: false, shareContribute: false, nodeTrash: false, move: false }
  const at = (kind: string | null, o: Partial<RightsTarget> = {}): RightsTarget => ({ kind, inIdentityFolder: false, shareable: kind === FOLDER_KIND, ...o })
  const grants = (...keys: (keyof FolderRights)[]) => ({ ...NONE, ...Object.fromEntries(keys.map((k) => [k, true])) })
  const member = memberAccessOf()
  const pending = memberAccessOf({ readonly: true })
  // `core.public-shares` off: by the server's AND along the path its two children are absent from the manifest too.
  const noLinks = memberAccessOf({ publicShares: false, publicSharesContribute: false, publicSharesDropBox: false })
  // Only `core.public-shares.contribute` off: READ links stay, the contribute role is not offered.
  const noContribute = memberAccessOf({ publicSharesContribute: false })

  const rows: [string, Access, RightsTarget, FolderRights][] = [
    // member
    ['member at the scope root', member, at(null), grants('navigate', 'createFolder', 'nodeTrash')],
    ['member in a folder', member, at(FOLDER_KIND), grants('navigate', 'createFolder', 'rename', 'deleteNode', 'share', 'shareContribute', 'nodeTrash', 'move')],
    ['member in a folder at the scope root\'s top level', member, at(FOLDER_KIND, { parentKind: null }), grants('navigate', 'createFolder', 'rename', 'deleteNode', 'share', 'shareContribute', 'nodeTrash', 'move')],
    ['member in a folder, public links off', noLinks, at(FOLDER_KIND), grants('navigate', 'createFolder', 'rename', 'deleteNode', 'nodeTrash', 'move')],
    ['member in a folder, contribute off: shares, offers no READ_WRITE', noContribute, at(FOLDER_KIND), grants('navigate', 'createFolder', 'rename', 'deleteNode', 'share', 'nodeTrash', 'move')],
    ['member in a folder, kind not shareable', member, at(FOLDER_KIND, { shareable: false }), grants('navigate', 'createFolder', 'rename', 'deleteNode', 'nodeTrash', 'move')],
    ['member, pending deletion, root', pending, at(null), grants('navigate')],
    ['member, pending deletion, folder (links are a read)', pending, at(FOLDER_KIND), grants('navigate', 'share', 'shareContribute')],
    // a member's move OUT of a drop box is the identity folder's one placement (inbox #358, #377 (a))
    ['member in a guest\'s identity folder: moves it out', member, at(FOLDER_KIND, { inIdentityFolder: true }), grants('navigate', 'move')],
    ['member, pending deletion, identity folder', pending, at(FOLDER_KIND, { inIdentityFolder: true }), grants('navigate')],
    ['member at an application\'s kind', member, at('ALPHA', { shareable: true }), NONE],
    // guest
    ['READ guest in a folder', guestAccessOf('READ'), at(FOLDER_KIND), grants('navigate')],
    ['READ guest in a folder a folder holds', guestAccessOf('READ'), at(FOLDER_KIND, { parentKind: FOLDER_KIND }), grants('navigate')],
    ['READ_WRITE guest at its link\'s root (parent above the link)', guestAccessOf('READ_WRITE'), at(FOLDER_KIND), grants('navigate', 'createFolder')],
    ['READ_WRITE guest in a folder a folder holds: moves it', guestAccessOf('READ_WRITE'), at(FOLDER_KIND, { parentKind: FOLDER_KIND }), grants('navigate', 'createFolder', 'move')],
    ['READ_WRITE guest in a folder a non-folder holds', guestAccessOf('READ_WRITE'), at(FOLDER_KIND, { parentKind: 'ALPHA' }), grants('navigate', 'createFolder')],
    ['READ_WRITE guest in a folder at the scope root\'s top level (never the guest\'s)', guestAccessOf('READ_WRITE'), at(FOLDER_KIND, { parentKind: null }), grants('navigate', 'createFolder')],
    ['READ_WRITE guest in an identity folder', guestAccessOf('READ_WRITE'), at(FOLDER_KIND, { inIdentityFolder: true, parentKind: FOLDER_KIND }), grants('navigate')],
    ['WRITE_ONLY guest in a folder a folder holds', guestAccessOf('WRITE_ONLY'), at(FOLDER_KIND, { parentKind: FOLDER_KIND }), NONE],
    ['WRITE_ONLY guest in a folder (no such root)', guestAccessOf('WRITE_ONLY'), at(FOLDER_KIND), NONE],
    ['an unknown role', guestAccessOf('ADMIN'), at(FOLDER_KIND), NONE],
    ['a guest at the scope root (never there)', guestAccessOf('READ_WRITE'), at(null), NONE],
    ['a guest at an application\'s kind', guestAccessOf('READ_WRITE'), at('ALPHA'), NONE],
    ['an unknown mode', { mode: 'operator' } as unknown as Access, at(FOLDER_KIND), NONE],
  ]
  it.each(rows)('%s', (_label, access, target, expected) => {
    expect(folderRights(access, target)).toEqual(expected)
  })

  it('never grants anything to a guest beyond navigate, create-folder and a READ_WRITE move, in any role or position', () => {
    for (const role of ['READ', 'READ_WRITE', 'WRITE_ONLY', 'X']) {
      for (const kind of [null, FOLDER_KIND, 'ALPHA']) {
        for (const inIdentityFolder of [false, true]) {
          for (const parentKind of [undefined, null, FOLDER_KIND, 'ALPHA']) {
            const c = folderRights(guestAccessOf(role), { kind, inIdentityFolder, shareable: true, parentKind })
            expect([c.rename, c.deleteNode, c.share, c.shareContribute, c.nodeTrash]).toEqual([false, false, false, false, false])
            // a move only for READ_WRITE, only of a folder a folder holds, never in an identity folder (#358, #377 (a))
            const mayMove = role === 'READ_WRITE' && kind === FOLDER_KIND && !inIdentityFolder && parentKind === FOLDER_KIND
            expect(c.move, `${role} at ${kind} in ${parentKind}, identity ${inIdentityFolder}`).toBe(mayMove)
          }
        }
      }
    }
  })
})
