// The favorite heart as a CORE action (inbox #409, ADR 0068): one action model, contributed to the two places a node shows
// its actions - `NodeHeader`'s `coreActions` and the level list's `coreRowActions` (`core/node/nodeHeader.ts`,
// `core/node/levelRow.ts`) - for EVERY kind, with no change to any application. The answer is
// `favoriteCapabilities` (member only, fail closed); the state is `favoritesStore` (pressed = pinned).
//
// A share guest gets the empty list, so its header and rows are the member's with the heart removed (share view = member
// view, inbox #401) - and `favoritesStore` is never read for it. The action is `first`: the heart is a visible button on a
// row, never tucked in the overflow menu.
import { favoriteCapabilities } from './capabilities'
import { isFavorite, toggleFavorite } from './favoritesStore'
import type { Access } from '../access'
import type { NodeKind } from '../node/kind'
import type { CoreHeaderAction } from '../node/nodeHeader'
import type { CoreRowAction } from '../node/levelRow'

type Translate = (key: string) => string

/** The heart of `node` for `access`, or none (a guest, the scope root, a kind this client cannot draw). */
function heart(t: Translate, access: Access, node: { id: string; kind: NodeKind } | null): (CoreHeaderAction & CoreRowAction)[] {
  if (!node || !favoriteCapabilities(access, node).favorite || access.mode !== 'member') return []
  const pinned = isFavorite(node.id)
  return [
    {
      id: 'favorite',
      label: pinned ? t('node.unfavorite') : t('node.favorite'),
      icon: pinned ? 'pi pi-heart-fill' : 'pi pi-heart',
      pressed: pinned,
      first: true,
      placement: 'before',
      onClick: () => void toggleFavorite(node.id, access.scopeId),
    },
  ]
}

/** The header's core actions for `node` (a node page's, or the folder a level shows). */
export function favoriteHeaderActions(t: Translate, access: Access, node: { id: string; kind: NodeKind } | null): CoreHeaderAction[] {
  return heart(t, access, node)
}

/** A row's core actions for `node`. */
export function favoriteRowActions(t: Translate, access: Access, node: { id: string; kind: NodeKind } | null): CoreRowAction[] {
  return heart(t, access, node)
}
