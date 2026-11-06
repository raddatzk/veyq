// The bookmark's part of the unified level list (inbox #407, ADR 0120): its ROW DATA, which core draws. The row names the
// host the link points at as its one detail, read from the sealed meta under the bookmark's own key; a plain click opens
// the link where it lives (`openBookmark.ts`), a bookmark whose meta does not read opens its page instead, which says so.
// Its actions are core's: rename, move and delete are core's defaults, granted by `./rights.ts` for THAT node, and Report is
// core's action, which for a bookmark hands the share's report to the share page (`core/node/coreNodeActions.ts`). A guest
// never draws one: no link serves a bookmark (decision 7), and `./rights.ts` grants a guest nothing were one to arrive.
import type { Ref } from 'vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LevelRow, LevelRowSource, LevelRowsContext, LevelRowsHost } from '../node/levelRow'
import type { TreeNode } from '../node/tree'
import { SHARE_BOOKMARK_KIND } from '../node/kind'
import { rowRightsOf, targetOf } from '../access'
import { bookmarkRights } from './rights'
import { bookmarkOfNode, openBookmark, useBookmarkDoors } from './openBookmark'
import { hostOf } from './bookmarkLink'

export function bookmarkRows(ctx: Readonly<Ref<LevelRowsContext>>, host: LevelRowsHost): LevelRowSource {
  const { t } = useI18n()
  const doors = useBookmarkDoors()
  const bookmarks = computed(() => ctx.value.nodes.filter((n) => n.kind === SHARE_BOOKMARK_KIND))

  function bookmarkRow(node: TreeNode): LevelRow {
    const r = bookmarkRights(ctx.value.access, targetOf(ctx.value.tree, node))
    const kept = r.open ? bookmarkOfNode(ctx.value.access, ctx.value.tree, node) : null
    return {
      icon: 'link',
      title: node.name,
      meta: t('node.bookmark.kind'),
      details: kept ? [{ id: 'host', text: hostOf(kept.link) }] : [],
      primary: {
        label: t('node.bookmark.open', { name: node.name }),
        onClick: () => (kept ? openBookmark(kept, false, doors) : host.open?.(node)),
        disabled: !r.open,
      },
      actions: [],
      ...rowRightsOf(r),
    }
  }

  const rows = computed(() => new Map(bookmarks.value.map((n) => [n.id, bookmarkRow(n)] as const)))
  return { row: (node) => rows.value.get(node.id) ?? null }
}
