import { ref, watch, type Ref } from 'vue'
import type { TreeSource } from '../access'

/**
 * The row to come back to when a view goes UP the tree: the child of `to` on the path from `from` (from a Memory three
 * levels down to the space's top level: the top-level folder it lies in). Null when `to` is no ancestor of `from` - a
 * step down or sideways, a first view, or a `from` the tree no longer holds (deleted, moved out of a guest's view).
 * `to` null is the member's scope root. Bounded by the tree's depth: a served cycle ends the walk.
 */
export function childTowards(tree: TreeSource, from: string | null, to: string | null): string | null {
  if (from === null || from === to) return null
  let child = tree.node(from)
  for (let steps = 0; child && steps < 10_000; steps++) {
    const parent = child.parentId ?? null
    if (parent === to) return child.id
    if (parent === null) return null
    child = tree.node(parent)
  }
  return null
}

/**
 * The host's half of "back to where I was" (a breadcrumb or the browser's Back to a level above): follows the node on
 * screen and answers, after each move, the child of the new place the view came up from - what `LevelView` brings back
 * into sight and focus as its `returnTo`. Set synchronously with the move, so the level renders with it. `realm` is the
 * tree the ids belong to (the member's scope): a move into another one is no way up, whatever the old tree still says.
 */
export function useReturnTo(
  current: () => string | null,
  tree: () => TreeSource | null,
  realm: () => unknown = () => null,
): Ref<string | null> {
  const returnTo = ref<string | null>(null)
  watch(
    [current, realm], // two sources: a re-read that keeps both (a live poll) is no move
    ([now, nowRealm], [before, beforeRealm]) => {
      const tr = tree()
      returnTo.value = tr && nowRealm === beforeRealm ? childTowards(tr, before, now) : null
    },
    { flush: 'sync' },
  )
  return returnTo
}

/**
 * Bring `row` (a level's `<li>`) back into sight and give its main button the focus. A row already wholly in sight stays
 * where it is - the browser's own restore on Back put it there, or it never left - and one that is not is centred, so
 * its neighbours show too. Below the sticky top bar counts as out of sight (`scroll-margin-top`, the bar's height). A row
 * with no button (one this client cannot show) is only brought into sight. Answers whether the focus moved.
 */
export function bringIntoView(row: HTMLElement): boolean {
  const box = row.getBoundingClientRect()
  const top = parseFloat(getComputedStyle(row).scrollMarginTop) || 0
  if (box.top < top || box.bottom > window.innerHeight) row.scrollIntoView({ block: 'center' })
  const main = row.querySelector<HTMLElement>('button.veyq-level-item-main')
  if (!main) return false
  main.focus({ preventScroll: true })
  return true
}
