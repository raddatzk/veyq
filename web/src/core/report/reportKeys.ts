// What a report hands over for a node or a link (cr-report-and-hold decision 3, en.moderation.hold § What a report hands
// over): the keys that open exactly that, role-tagged, for `v1.sealReportShare`. An application's item brings its own
// keys (Memories' medium: `features/memory/media.ts`, `mediumReportKeys`).
//
// A node's NAME is sealed under the key its directory entry lives under — its parent's `K_node`, `K_SC` at the node's
// epoch for a scope-root node, a guest's `K_up` for an identity folder — so `K_node` does not open it, and the report
// hands over the name's own `K_field` beside `K_node` and the meta's. A link's root, seen by its guest, is named by the
// link (`share-memory-name` under `K_share`): its own entry lies above the link (ADR 0080 Decision 9).
//
// Every `K_field` comes from `v1.fieldKeyOf`, which opens the envelope before it hands the key over: a key that would
// not open what it is reported for is never sent, and a field that does not open (or is absent) is left out — the
// node's own key is the one the report cannot do without.
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import type { Access, GuestAccess, TreeSource } from '../access'
import { childEntryKey, type TreeNode } from '../node/tree'
import { keyAt } from '../../features/spaces/epochs'
import { SHARE_MEMORY_NAME, SHARE_NOTE } from '../sharing/resolvePublicShare'

/** The `K_field` of `envelope` for `context` under `parentKey`, or null when there is none or it does not open. */
function fieldKey(context: v1.FieldContext, parentKey: Uint8Array | null, envelope: string | null | undefined): Uint8Array | null {
  if (!parentKey || !envelope) return null
  try {
    return v1.fieldKeyOf(context, parentKey, fromB64std(envelope))
  } catch {
    return null
  }
}

/** The key `node`'s directory entry lives under, as `access` reads the tree; null where this session cannot reach it. */
function entryKeyOf(access: Access, tree: TreeSource, scopeId: string, node: TreeNode): Uint8Array | null {
  try {
    if (node.parentId === null) return access.mode === 'member' && access.epochs ? keyAt(access.epochs, node.epoch) : null
    const parent = tree.node(node.parentId) as TreeNode | null
    const parentKey = tree.keyOf(node.parentId)
    return parent && parentKey ? childEntryKey(scopeId, parent, parentKey, node) : null
  } catch {
    return null
  }
}

/**
 * The keys a report of `node` hands over: `NODE` (its `K_node`, required — null when this session holds none), its name's
 * `K_field` (`NODE_NAME`, or `LINK_NAME` for a guest's link root: the link's `share-memory-name`) and its meta's
 * (`NODE_META`) where they open.
 */
export function nodeReportKeys(access: Access, tree: TreeSource, scopeId: string, node: TreeNode): v1.ReportKey[] | null {
  const key = tree.keyOf(node.id)
  if (!key) return null
  const keys: v1.ReportKey[] = [{ role: 'NODE', ref: null, key }]
  if (access.mode === 'guest' && node.id === tree.rootId) {
    const name = fieldKey(SHARE_MEMORY_NAME, access.head.kShare, access.head.share.sealedName)
    if (name) keys.push({ role: 'LINK_NAME', ref: null, key: name })
  } else {
    const address = { scopeId, nodeId: node.id, epoch: node.epoch, placementRevision: node.placementRevision }
    const name = fieldKey({ purpose: 'node-name', ...address }, entryKeyOf(access, tree, scopeId, node), node.encryptedName)
    if (name) keys.push({ role: 'NODE_NAME', ref: null, key: name })
  }
  const meta = fieldKey({ purpose: 'node-meta', scopeId, nodeId: node.id }, key, node.wrappedMeta)
  if (meta) keys.push({ role: 'NODE_META', ref: null, key: meta })
  return keys
}

/**
 * The keys a report of an upload link hands over (a `WRITE_ONLY` guest's only item, ADR 0093 § 1): its `K_share`, which
 * opens no content, and the `K_field`s of the link's name and note the guest was shown.
 */
export function linkReportKeys(access: GuestAccess): v1.ReportKey[] {
  const { kShare, share } = access.head
  const keys: v1.ReportKey[] = [{ role: 'SHARE', ref: null, key: kShare }]
  const name = fieldKey(SHARE_MEMORY_NAME, kShare, share.sealedName)
  if (name) keys.push({ role: 'LINK_NAME', ref: null, key: name })
  const note = fieldKey(SHARE_NOTE, kShare, share.sealedNote)
  if (note) keys.push({ role: 'LINK_NOTE', ref: null, key: note })
  return keys
}
