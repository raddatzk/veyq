// The favorite heart as a CORE action (inbox #409, ADR 0068): one action model, contributed to the two places a node shows
// its actions - a node's header and its row, through `core/node/coreNodeActions.ts` (`core/node/nodeHeader.ts`,
// `core/node/levelRow.ts`) - for EVERY kind, with no change to any application. The answer is
// `favoriteRights` (member only, fail closed); the state is `favoritesStore` (pressed = pinned).
//
// A share guest gets the empty list, so its header and rows are the member's with the heart removed (share view = member
// view, inbox #401) - and `favoritesStore` is never read for it. The action is `first`: the heart is a visible button on a
// row, never tucked in the overflow menu.
import { favoriteRights } from './rights'
import { isFavorite, toggleFavorite } from './favoritesStore'
import type { Access } from '../access'
import type { NodeKind } from '../node/kind'
import type { CoreHeaderAction } from '../node/nodeHeader'
import type { CoreRowAction } from '../node/levelRow'

type Translate = (key: string) => string
/** Shows a failure the user must hear about (the host's `useToast().error`): a pin that did not land says so (inbox #500). */
type Failed = (summary: string) => void

/** The heart of `node` for `access`, or none (a guest, the scope root, a kind this client cannot draw). */
function heart(t: Translate, failed: Failed, access: Access, node: { id: string; kind: NodeKind } | null): (CoreHeaderAction & CoreRowAction)[] {
  if (!node || access.mode !== 'member') return []
  const shown = favoriteRights(access, node).favorite
  if (!shown) return []
  const pinned = isFavorite(node.id)
  return [
    {
      id: 'favorite',
      shown,
      label: pinned ? t('node.unfavorite') : t('node.favorite'),
      icon: pinned ? 'heart-fill' : 'heart',
      pressed: pinned,
      first: true,
      placement: 'before',
      onClick: () => void toggleFavorite(node.id, access.scopeId).catch(() => failed(t('node.favoriteFailed'))),
    },
  ]
}

/** The header's core actions for `node` (a node page's, or the folder a level shows). */
export function favoriteHeaderActions(t: Translate, failed: Failed, access: Access, node: { id: string; kind: NodeKind } | null): CoreHeaderAction[] {
  return heart(t, failed, access, node)
}

/** A row's core actions for `node`. */
export function favoriteRowActions(t: Translate, failed: Failed, access: Access, node: { id: string; kind: NodeKind } | null): CoreRowAction[] {
  return heart(t, failed, access, node)
}
