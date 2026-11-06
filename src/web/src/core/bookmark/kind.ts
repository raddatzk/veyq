// Core's bookmark kind (ADR 0120 decision 1), registered by core beside FOLDER (`core/applications/registry.ts`
// `createRegistry`): it belongs to no application, so no switch turns it off and it has no route segment of its own (its
// nodes are named among the plain names, as a folder's sibling Depot file is). Its rows, page and create dialog are core's;
// it has no share dialog (it roots no link), no cover, and no export content - so a download and a copy leave it out and
// say so (decision 8), and its re-key is core's re-seal of its meta alone.
import type { KindRegistration, LevelActionContext } from '../applications/registry'
import { FOLDER_KIND } from '../node/kind'
import { nothingOfItsOwnToRekey } from '../node/rekey/hooks'
import { targetOf } from '../access'
import { canCreateBookmark } from './rights'
import { bookmarkRows } from './bookmarkRows'
import BookmarkCreateDialog from './BookmarkCreateDialog.vue'
import BookmarkDetail from './BookmarkDetail.vue'

/** Where "Bookmark" sits in the level's "New" menu: after core's folder (0) and the applications' entries (10-40). */
const NEW_BOOKMARK_ORDER = 50

export const shareBookmarkKind: KindRegistration = {
  icon: 'link',
  // The header's priority (ADR 0110): the heart, then editing and moving it; Report (the share's) stays the menu's last group.
  header: { order: ['favorite', 'move'] },
  detail: BookmarkDetail,
  deleteTitle: 'node.bookmark.delete',
  nounWithArticle: 'node.aBookmark',
  list: { rows: bookmarkRows },
  createDialog: BookmarkCreateDialog,
  createEntry: {
    label: 'node.bookmark.newEntry', icon: 'link', order: NEW_BOOKMARK_ORDER,
    allowed: (ctx: LevelActionContext) => canCreateBookmark(ctx.access, targetOf(ctx.tree, ctx.folder)),
    enabled: (ctx: LevelActionContext) => ctx.epochs !== null,
  },
  // At the root or directly in a folder (the backend's `ShareBookmarkNodeRules`, contracts/platform/kinds/core.json).
  placement: { root: true, parents: [FOLDER_KIND] },
  rekeyContent: nothingOfItsOwnToRekey,
}
