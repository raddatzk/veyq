// The share guest's tree source and access (inbox #401 S2, `guestAccess.ts`): real crypto over a served subtree — the
// names and keys open through the chain, a guest's identity folder through the drop key — and the four promises a view
// relies on: a link keeps the `#k=` fragment, nothing outside the opened subtree is served, equal names are numbered by
// node id, and the identity-folder flag holds for the folder and everything below it.
import { describe, it, expect, vi } from 'vitest'
import { reactive } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import { b64std, b64url } from '@veyq/crypto/base64'
import { guestAccess, guestTreeSource } from './guestAccess'
import { FOLDER_KIND } from '../node/kind'
import { sealName, wrapContentKey } from '../node/tree'
import { dropKeysForShare, mintIdentityKey, openDropKey } from './dropKey'
import { ShareUnavailableError, type PublicShareHead } from './resolvePublicShare'
import { ShareRole, type PublicShare, type ShareNode } from '../../api/generated/sharing'

const SCOPE_ID = '55555555-5555-4555-8555-555555555555'
const SHARE_ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_s'
const ROOT = '6e5a0000-0000-4000-8000-000000000400'
const LETTERS = '6e5a0000-0000-4000-8000-000000000401'
const NESTED = '6e5a0000-0000-4000-8000-000000000402'
const DROP = '6e5a0000-0000-4000-8000-000000000403'
const ANNA_B = '6e5a0000-0000-4000-8000-000000000404' // the SECOND Anna by id order, though listed first
const ANNA_A = '6e5a0000-0000-4000-8000-0000000003ff' // the FIRST Anna by id order
const ANNA_FILE = '6e5a0000-0000-4000-8000-000000000406'
const BROKEN = '6e5a0000-0000-4000-8000-000000000407'
const BELOW_BROKEN = '6e5a0000-0000-4000-8000-000000000408'
const OUTSIDE = '6e5a0000-0000-4000-8000-000000000409'
const IDENTITY_A = '6e5a0000-0000-4000-8000-0000000004a0'
const IDENTITY_B = '6e5a0000-0000-4000-8000-0000000004a1'
const K_SHARE = v1.randomBytes(32)
const keys = {
  root: v1.randomBytes(32), letters: v1.randomBytes(32), nested: v1.randomBytes(32), drop: v1.randomBytes(32),
  annaA: v1.randomBytes(32), annaB: v1.randomBytes(32), annaFile: v1.randomBytes(32), broken: v1.randomBytes(32),
  belowBroken: v1.randomBytes(32),
}
const HASH = `#k=${b64url(K_SHARE)}`

function child(id: string, parentId: string, entryKey: Uint8Array, key: Uint8Array, name: string, kind: string, extra: Partial<ShareNode> = {}): ShareNode {
  const address = { scopeId: SCOPE_ID, nodeId: id, epoch: 0, placementVersion: 0 }
  return {
    id, parentId, kind, epoch: 0, placementVersion: 0, orderIndex: 0,
    encryptedName: sealName(entryKey, address, name), wrappedContentKey: wrapContentKey(entryKey, address, key), ...extra,
  }
}

const { sealedDropPrivateKey } = dropKeysForShare(SCOPE_ID, DROP, keys.drop, v1.randomBytes(32), null)
const dropPublic = v1.dropKeyPublic(openDropKey(SCOPE_ID, DROP, keys.drop, sealedDropPrivateKey!))
const annaA = mintIdentityKey(SCOPE_ID, DROP, IDENTITY_A, dropPublic)
const annaB = mintIdentityKey(SCOPE_ID, DROP, IDENTITY_B, dropPublic)

function head(): PublicShareHead {
  return {
    shareId: SHARE_ID, kShare: K_SHARE, scopeId: SCOPE_ID, name: 'Familie', noteByLocale: {}, role: ShareRole.Read, rootKind: FOLDER_KIND,
    share: {
      role: ShareRole.Read, scopeId: SCOPE_ID, rootKind: FOLDER_KIND, wrap: b64std(v1.seal({ purpose: 'share-key' }, K_SHARE, keys.root)),
      nodes: [
        { id: ROOT, parentId: null, kind: FOLDER_KIND, epoch: 0, wrappedMeta: 'bWV0YQ==' },
        child(LETTERS, ROOT, keys.root, keys.letters, 'Briefe', FOLDER_KIND),
        child(NESTED, LETTERS, keys.letters, keys.nested, 'Feldpost', FOLDER_KIND, { wrappedMeta: 'c2VhbGVk' }),
        child(DROP, ROOT, keys.root, keys.drop, 'Postfach', 'DEPOT_DROPBOX', { sealedDropPrivateKey }),
        child(ANNA_B, DROP, annaB.identityKey, keys.annaB, 'Anna', FOLDER_KIND, { shareIdentityId: IDENTITY_B, wrappedIdentityKey: annaB.wrappedIdentityKey }),
        child(ANNA_A, DROP, annaA.identityKey, keys.annaA, 'Anna', FOLDER_KIND, { shareIdentityId: IDENTITY_A, wrappedIdentityKey: annaA.wrappedIdentityKey }),
        child(ANNA_FILE, ANNA_A, keys.annaA, keys.annaFile, 'steuer.pdf', 'FILE'),
        // Its key is wrapped under a key the chain does not hold: it, and what hangs below it, are not served.
        child(BROKEN, ROOT, v1.randomBytes(32), keys.broken, 'Kaputt', FOLDER_KIND),
        child(BELOW_BROKEN, BROKEN, keys.broken, keys.belowBroken, 'Darunter', FOLDER_KIND),
      ],
    } as PublicShare,
  }
}

describe('guestTreeSource', () => {
  it('serves the opened subtree as TreeNodes: the root has no parent and is named after the share', () => {
    const tree = guestTreeSource(head(), { hash: HASH })
    expect(tree.rootId).toBe(ROOT)
    expect(tree.node(ROOT)).toMatchObject({ id: ROOT, parentId: null, kind: FOLDER_KIND, name: 'Familie', wrappedMeta: 'bWV0YQ==', createdAt: 0 })
    expect(tree.node(NESTED)).toMatchObject({ parentId: LETTERS, name: 'Feldpost', wrappedMeta: 'c2VhbGVk', metaVersion: 0, placementVersion: 0 })
    expect(tree.children(ROOT).map((n) => n.id).sort()).toEqual([DROP, LETTERS].sort())
    expect(Array.from(tree.keyOf(NESTED)!), "a node's key, as the chain opened it").toEqual(Array.from(keys.nested))
    expect(tree.nameOf(ROOT)).toBe('Familie')
    expect(tree.nameOf(LETTERS)).toBe('Briefe')
  })

  it('serves nothing outside the opened subtree: not above the root, not a node the chain does not open, nor below it', () => {
    const tree = guestTreeSource(head(), { hash: HASH })
    expect(tree.children(null), 'a guest is never at the scope root').toEqual([])
    for (const id of [BROKEN, BELOW_BROKEN, OUTSIDE]) {
      expect(tree.node(id), id).toBeNull()
      expect(tree.keyOf(id), id).toBeNull()
      expect(tree.nodes.some((n) => n.id === id), id).toBe(false)
    }
    expect(tree.children(ROOT).some((n) => n.id === BROKEN)).toBe(false)
  })

  it('numbers equal names among siblings by node id — the guest resolve serves no creation time', () => {
    const tree = guestTreeSource(head(), { hash: HASH })
    expect(tree.nameOf(ANNA_A), 'the lower id keeps the name').toBe('Anna')
    expect(tree.nameOf(ANNA_B)).toBe('Anna (2)')
    expect(tree.node(ANNA_B)!.name, 'the node keeps its own name; the label is nameOf’s').toBe('Anna')
  })

  it("flags a guest's identity folder and everything below it, nothing else", () => {
    const tree = guestTreeSource(head(), { hash: HASH })
    expect(tree.inIdentityFolder(ANNA_A)).toBe(true)
    expect(tree.inIdentityFolder(ANNA_FILE)).toBe(true)
    expect(tree.inIdentityFolder(DROP)).toBe(false)
    expect(tree.inIdentityFolder(ROOT)).toBe(false)
    expect(tree.inIdentityFolder(null)).toBe(false)
  })

  it('links to ?node=<id> keeping the CURRENT #k= fragment; the root has no query', () => {
    const route = reactive({ hash: HASH })
    const tree = guestTreeSource(head(), route)
    expect(tree.linkTo(NESTED)).toEqual({ query: { node: NESTED }, hash: HASH })
    expect(tree.linkTo(ROOT)).toEqual({ query: {}, hash: HASH })
    expect(tree.linkTo(null)).toEqual({ query: {}, hash: HASH })
    route.hash = `${HASH}&x=1`
    expect(tree.linkTo(LETTERS), 'read on every link, not when the source was built').toEqual({ query: { node: LETTERS }, hash: `${HASH}&x=1` })
  })

  it('a share the fragment key does not open is unavailable', () => {
    const bad = head()
    bad.share = { ...bad.share, wrap: b64std(v1.seal({ purpose: 'share-key' }, v1.randomBytes(32), keys.root)) }
    expect(() => guestTreeSource(bad, { hash: HASH })).toThrow(ShareUnavailableError)
  })
})

describe('guestAccess', () => {
  it("is a guest's access: the link's id and role, its password token, its head, the fragment key, the page's naming flow and re-resolve", async () => {
    const h = { ...head(), role: ShareRole.ReadWrite }
    const requestGuest = async () => ({ identityId: 'i', contributorToken: 't' })
    const reload = vi.fn(async () => {})
    const access = guestAccess({ head: h, pwToken: 'pw', fragmentKey: 'frag', requestGuest, reload })
    expect(access).toMatchObject({ mode: 'guest', shareId: SHARE_ID, role: ShareRole.ReadWrite, pwToken: 'pw', head: h, fragmentKey: 'frag' })
    expect(await access.requestGuest()).toEqual({ identityId: 'i', contributorToken: 't' })
    await access.reload()
    expect(reload).toHaveBeenCalledOnce()
  })
})
