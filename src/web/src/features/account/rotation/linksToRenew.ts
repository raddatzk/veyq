// uc.account.rotate-keys (web) — the result screen's "Links to renew" (cr-secure-the-account). Securing the
// account closes the old identity out of what comes NEXT; a link made before still opens what it opened, because its key
// is the link's own, not the account's. The server marks such a link `reissueRequired` once its scope's key moved (ADR
// 0109 point 4, `ReissuePolicy`), so the list is the caller's own links (`listShares()` without `nodeId`, every tenant)
// with that flag, by space. **Renew** opens that node's share dialog — the kind's own, through the registry's
// `shareDialog` slot, the same dialog the node's page opens — so no second renew UI exists. A link in a space of
// another tenant is opened from that tenant (this page's reads are the active tenant's); a WRITE_ONLY link is replaced by
// a new one (a drop box offers no renew).
import type { Component } from 'vue'
import { sharesApi } from '../../../api/client'
import { ShareRole, type ShareSummary } from '../../../api/generated/sharing'
import { withRefresh } from '../../../app/sessionRefresh'
import { registry, type NodeShareDialogProps } from '../../../core/applications/registry'
import { listTree, nodeContentKey } from '../../../core/node/tree'
import { zeroScopeKey } from '../../spaces/epochs'
import { listMyScopes, openScopeById } from '../../spaces/scopes'

/** One link to renew. `here`: its space is one of the active tenant's, so its dialog opens from this page; `name` is the
 *  node's name as this device opened the space's tree (null when it did not open). */
export type LinkToRenew = { share: ShareSummary; here: boolean; name: string | null; upload: boolean }

/** The caller's own links that need renewing, every tenant's. A WRITE_ONLY link is an upload link (`upload`). */
export async function listLinksToRenew(): Promise<LinkToRenew[]> {
  const mine = (await withRefresh(() => sharesApi().listShares())).filter((s) => s.mine && s.reissueRequired)
  if (!mine.length) return []
  const hereIds = new Set((await listMyScopes().catch(() => [])).map((s) => s.id))
  const names = await nodeNames([...new Set(mine.map((s) => s.scopeId))].filter((id) => hereIds.has(id)))
  return mine.map((share) => ({
    share,
    here: hereIds.has(share.scopeId),
    name: names.get(share.nodeId) ?? null,
    upload: share.role === ShareRole.WriteOnly,
  }))
}

/** The names of the nodes of `scopeIds` as this device opens their trees; a space that does not open names none. The
 *  epoch keys are zeroed once read: the list holds names, never a key. */
async function nodeNames(scopeIds: string[]): Promise<Map<string, string>> {
  const names = new Map<string, string>()
  for (const scopeId of scopeIds) {
    const opened = await openScopeById(scopeId).catch(() => null)
    if (!opened) continue
    try {
      for (const n of await listTree(scopeId, opened.epochs)) names.set(n.id, n.name)
    } catch {
      /* the space's tree did not open: its links read by the neutral label */
    } finally {
      opened.key.fill(0)
      zeroScopeKey(opened.epochs)
    }
  }
  return names
}

/** The share dialog of a link's node, ready to mount: the kind's component and the props its node's page hands it. */
export type RenewDialog = { component: Component; props: NodeShareDialogProps; release: () => void }

/**
 * Open the share dialog of `link`'s node from the account's settings: the space is opened by id, its tree read, and the
 * node's key resolved through the content-key chain — what the node's own page does before it mounts the same dialog.
 * Null when the space or the node does not open here, or its kind brings no share dialog. `release` zeroes the keys the
 * dialog was handed; the caller calls it when the dialog closes. `contribute` stays off (fail closed): a renew keeps
 * the link's role, and only a NEW link's role choice reads it.
 */
export async function openRenewDialog(link: LinkToRenew): Promise<RenewDialog | null> {
  if (!link.here) return null
  const { scopeId, nodeId } = link.share
  const opened = await openScopeById(scopeId)
  if (!opened) return null
  const release = () => {
    opened.key.fill(0)
    zeroScopeKey(opened.epochs)
  }
  try {
    const tree = await listTree(scopeId, opened.epochs)
    const node = tree.find((n) => n.id === nodeId)
    const info = node ? registry.kind(node.kind) : null
    const nodeKey = node ? nodeContentKey(scopeId, opened.epochs, tree, nodeId) : null
    if (!node || !info?.supported || !info.shareDialog || !nodeKey) {
      release()
      return null
    }
    return {
      component: info.shareDialog,
      props: {
        scopeId,
        nodeId,
        nodeKey,
        nodeEpoch: node.epoch,
        epochs: opened.epochs,
        nodeName: node.name,
        nodeKind: node.kind,
        uploader: link.upload,
        contribute: false,
      },
      release: () => {
        nodeKey.fill(0)
        release()
      },
    }
  } catch (e) {
    release()
    throw e
  }
}
