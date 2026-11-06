// Creating a bookmark (uc.sharing.keep-a-received-link, ADR 0120): core's node create with the bookmark's kind, its link and
// password sealed into the node's meta in the same request - the node never exists without what it keeps.
import { createContentNode, type CreateNodeOptions, type NodeParent } from '../node/tree'
import { sealNodeMeta } from '../node/nodeMeta'
import { SHARE_BOOKMARK_KIND } from '../node/kind'
import type { ScopeKey } from '../../features/spaces/epochs'
import { bookmarkMetaObject } from './bookmarkMeta'
import type { ShareLink } from './bookmarkLink'

/** Create a bookmark named [name] under [parent] keeping [link] and [password]; its node id. */
export async function createBookmark(
  scopeId: string, epochs: ScopeKey, parent: NodeParent, name: string, link: ShareLink, password: string | null,
  opts: CreateNodeOptions = {},
): Promise<string> {
  const meta = bookmarkMetaObject(link, password)
  const created = await createContentNode(scopeId, epochs, parent, SHARE_BOOKMARK_KIND, name,
    (key, node) => sealNodeMeta(key, { scopeId: node.scopeId, nodeId: node.nodeId }, meta), opts)
  return created.nodeId
}
