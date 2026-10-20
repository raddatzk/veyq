// What a report hands over for a node and a link (en.moderation.hold § What a report hands over), against real
// seals: each key opens the
// field it is tagged for and nothing else is handed over. A node's name is under its PARENT's key (`K_SC` at its epoch for
// a scope-root node), so the report carries the name's own K_field beside K_node; a guest's link root is named by the link.
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { memberAccess, memberTreeSource, type GuestAccess, type TreeSource } from '../access'
import { sealName, wrapContentKey, type TreeNode } from '../node/tree'
import { sealNodeMeta } from '../node/nodeMeta'
import { FOLDER_KIND } from '../node/kind'
import { ShareRole } from '../../api/generated/sharing'
import { linkReportKeys, nameReportKeys, nodeReportKeys } from './reportKeys'

const SCOPE = '5c09e000-0000-4000-8000-0000000000f3'
const ROOT = '5c09e000-0000-4000-8000-0000000000a0'
const CHILD = '5c09e000-0000-4000-8000-0000000000b0'
const K_SC = v1.randomBytes(32)
const K_ROOT = v1.randomBytes(32)
const K_CHILD = v1.randomBytes(32)
const epochs = { current: 0, keys: new Map([[0, K_SC]]) }

function node(id: string, parentId: string | null, entryKey: Uint8Array, key: Uint8Array, name: string, meta = false): TreeNode {
  const address = { scopeId: SCOPE, nodeId: id, epoch: 0, placementRevision: 0 }
  return {
    id, parentId, kind: FOLDER_KIND, orderIndex: 0, epoch: 0, name, createdAt: 0, metaRevision: 0, placementRevision: 0,
    encryptedName: sealName(entryKey, address, name),
    wrappedContentKey: wrapContentKey(entryKey, address, key),
    wrappedMeta: meta ? sealNodeMeta(key, { scopeId: SCOPE, nodeId: id }, { v: 1, date: '2026' }) : null,
  }
}

const nodes = [node(ROOT, null, K_SC, K_ROOT, 'Familie'), node(CHILD, ROOT, K_ROOT, K_CHILD, 'Sommer', true)]
const access = memberAccess({ scopeId: SCOPE, epochs, readonly: false, publicShares: true, publicSharesContribute: true, publicSharesDropBox: true })
const tree = memberTreeSource({ nodes: () => nodes, scopeId: () => SCOPE, epochs: () => epochs, scope: () => '' })
const nameOf = (n: TreeNode, key: Uint8Array) =>
  new TextDecoder().decode(v1.openFieldWith(
    { purpose: 'node-name', scopeId: SCOPE, nodeId: n.id, epoch: 0, placementRevision: 0 }, key, fromB64std(n.encryptedName!),
  ))

describe('nodeReportKeys', () => {
  it('a member\'s node: K_node, its name\'s K_field (under the parent\'s key) and its meta\'s', () => {
    const keys = nodeReportKeys(access, tree, SCOPE, nodes[1])!
    expect(keys.map((k) => k.role)).toEqual(['NODE', 'NODE_NAME', 'NODE_META'])
    expect(() => v1.encodeReportKeys(keys)).not.toThrow()
    expect(keys[0].key).toEqual(K_CHILD)
    expect(nameOf(nodes[1], keys[1].key)).toBe('Sommer')
    expect(() => nameOf(nodes[1], K_CHILD), 'K_node does not open its own name').toThrow()
    const meta = v1.openFieldWith({ purpose: 'node-meta', scopeId: SCOPE, nodeId: CHILD }, keys[2].key, fromB64std(nodes[1].wrappedMeta!))
    expect(new TextDecoder().decode(meta)).toContain('2026')
  })

  it('a scope-root node: its name under K_SC at the node\'s epoch; no meta, no key for it', () => {
    const keys = nodeReportKeys(access, tree, SCOPE, nodes[0])!
    expect(keys.map((k) => k.role)).toEqual(['NODE', 'NODE_NAME'])
    expect(nameOf(nodes[0], keys[1].key)).toBe('Familie')
  })

  it('a node this session holds no key for is nothing to report', () => {
    const blind: TreeSource = { ...tree, keyOf: () => null }
    expect(nodeReportKeys(access, blind, SCOPE, nodes[1])).toBeNull()
  })

  it('a guest\'s link root is named by the link (share-memory-name under K_share), not by its own entry', () => {
    const kShare = v1.randomBytes(32)
    const sealedName = b64std(v1.sealField({ purpose: 'share-memory-name' }, kShare, v1.utf8('Familie')))
    const guest = {
      mode: 'guest', role: ShareRole.Read, shareId: 'x', fragmentKey: '', head: { kShare, share: { sealedName }, scopeId: SCOPE },
    } as unknown as GuestAccess
    const guestTree: TreeSource = { ...tree, rootId: ROOT }
    const keys = nodeReportKeys(guest, guestTree, SCOPE, nodes[0])!
    expect(keys.map((k) => k.role)).toEqual(['NODE', 'LINK_NAME'])
    expect(() => v1.encodeReportKeys(keys), 'every role is a token the sealer takes (16 characters at most)').not.toThrow()
    expect(new TextDecoder().decode(v1.openFieldWith({ purpose: 'share-memory-name' }, keys[1].key, fromB64std(sealedName)))).toBe('Familie')
  })
})

describe('nameReportKeys (a name reported on its own, ADR 0093 § 9)', () => {
  const sibling = node('5c09e000-0000-4000-8000-0000000000c0', ROOT, K_ROOT, v1.randomBytes(32), 'Winter')
  const withSibling: TreeSource = memberTreeSource({ nodes: () => [...nodes, sibling], scopeId: () => SCOPE, epochs: () => epochs, scope: () => '' })

  it('hands over that name\'s K_field and nothing else', () => {
    const keys = nameReportKeys(access, withSibling, SCOPE, nodes[1])!
    expect(keys.map((k) => k.role)).toEqual(['NODE_NAME'])
    expect(() => v1.encodeReportKeys(keys)).not.toThrow()
    expect(nameOf(nodes[1], keys[0].key), 'the control: it opens the reported name').toBe('Sommer')
  })

  it('the key opens neither the node, nor its meta, nor a sibling\'s name', () => {
    const [{ key }] = nameReportKeys(access, withSibling, SCOPE, nodes[1])!
    expect(key).not.toEqual(K_CHILD)
    expect(() => v1.openFieldWith({ purpose: 'node-meta', scopeId: SCOPE, nodeId: CHILD }, key, fromB64std(nodes[1].wrappedMeta!)), 'not the meta').toThrow()
    expect(() => nameOf(sibling, key), 'not a sibling\'s name').toThrow()
    expect(nameOf(sibling, nameReportKeys(access, withSibling, SCOPE, sibling)![0].key), 'the sibling\'s own key does').toBe('Winter')
  })

  it('a guest\'s link root has no name of its own to report: the link names it', () => {
    const guest = { mode: 'guest', role: ShareRole.Read, shareId: 'x', fragmentKey: '', head: { kShare: v1.randomBytes(32), share: {}, scopeId: SCOPE } } as unknown as GuestAccess
    expect(nameReportKeys(guest, { ...tree, rootId: ROOT }, SCOPE, nodes[0])).toBeNull()
    // The control: below the root the guest reads the name under the root's key, and reports it.
    expect(nameReportKeys(guest, { ...tree, rootId: ROOT }, SCOPE, nodes[1])!.map((k) => k.role)).toEqual(['NODE_NAME'])
  })
})

describe('linkReportKeys', () => {
  it('K_share, and the K_fields of the name and the note the guest was shown', () => {
    const kShare = v1.randomBytes(32)
    const sealedName = b64std(v1.sealField({ purpose: 'share-memory-name' }, kShare, v1.utf8('Drop')))
    const sealedNote = b64std(v1.sealField({ purpose: 'share-note' }, kShare, v1.utf8('{"en":"hi"}')))
    const guest = { mode: 'guest', head: { kShare, share: { sealedName, sealedNote } } } as unknown as GuestAccess
    const keys = linkReportKeys(guest)
    expect(keys.map((k) => k.role)).toEqual(['SHARE', 'LINK_NAME', 'LINK_NOTE'])
    expect(() => v1.encodeReportKeys(keys)).not.toThrow()
    expect(keys[0].key).toEqual(kShare)
    expect(new TextDecoder().decode(v1.openFieldWith({ purpose: 'share-note' }, keys[2].key, fromB64std(sealedNote)))).toBe('{"en":"hi"}')
  })

  it('a link without a note hands over no note key', () => {
    const kShare = v1.randomBytes(32)
    const guest = { mode: 'guest', head: { kShare, share: {} } } as unknown as GuestAccess
    expect(linkReportKeys(guest).map((k) => k.role)).toEqual(['SHARE'])
  })
})
