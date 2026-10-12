// The order of the unified level list (inbox #407): folders first, then every other node MIXED BY NAME — a Memory, a
// file and a drop box sort among each other, not by kind. Names compare the way a person reads them: locale-aware,
// case-insensitive and accent-insensitive (`sensitivity: 'base'`), digits as numbers ("Foto 2" before "Foto 10").
// Equal names fall back to the creation order that numbers equal names everywhere else (`compareByCreation`: the
// server's instant, then the node id), so the order never depends on how the tree happened to be listed.
//
// Pure, so it is tested without a DOM (`levelList.test.ts`).
import type { TreeNode } from './tree'
import { compareByCreation } from './location'

/** The comparison of two nodes by display name in `locale` (a BCP 47 tag), ties by creation. */
export function compareByName(locale: string): (a: TreeNode, b: TreeNode) => number {
  const collator = new Intl.Collator(locale, { numeric: true, sensitivity: 'base' })
  return (a, b) => collator.compare(a.name, b.name) || compareByCreation(a, b)
}

/** The list in the order it is drawn: `folders` (by name), then `others` (by name, all kinds mixed). Does not mutate. */
export function orderLevel(folders: readonly TreeNode[], others: readonly TreeNode[], locale: string): TreeNode[] {
  const cmp = compareByName(locale)
  return [...[...folders].sort(cmp), ...[...others].sort(cmp)]
}
