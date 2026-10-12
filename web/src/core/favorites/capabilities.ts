// What a viewer may do about favorites on a node (en.memory.favorites, ADR 0068; core's since inbox #409): ONE fail-closed
// capability function for EVERY kind - a folder, a file, a Memory, a drop box, whatever an application registers - because
// a pin is a preference about a node, not an application's data.
//
// - **Member only.** The pin set is sealed under the member's Master Key (ADR 0068 section 2): a share guest has no key to seal
//   it with and the server has no guest route for it, so a guest's view is the member's view with the heart removed.
// - **A node, not the scope root.** The scope root is no node (`null`): nothing to pin.
// - **A kind this client can draw.** A kind no application registered renders as the explicit unsupported row; a pin on it
//   would list a thing the sidebar cannot open, so it is refused here, not hidden later.
// - **A pending deletion keeps the heart.** A pin is the member's own preference, not a write to the scope's tree, and
//   Memories' matrix kept it through the grace window since cr-0090 (`docs/apps/memories.md`). The server's put takes
//   the READ gate for the same reason (`FavoritesHandler`, ADR 0068 §10), so the toggle in that window is stored.
import type { Access } from '../access'
import { registry } from '../applications/registry'
import type { NodeKind } from '../node/kind'

export interface FavoriteCapabilities {
  /** Pin or unpin the node. */
  favorite: boolean
}

const NONE: FavoriteCapabilities = Object.freeze({ favorite: false })

/** The favorite capabilities of `access` for `node` (null: the scope root). Every right defaults to false. */
export function favoriteCapabilities(access: Access, node: { id: string; kind: NodeKind } | null): FavoriteCapabilities {
  if (!node || access.mode !== 'member') return NONE
  return registry.kind(node.kind).supported ? { favorite: true } : NONE
}
