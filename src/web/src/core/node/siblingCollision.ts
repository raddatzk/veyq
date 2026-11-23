// A name a sibling already takes, said by name (inbox #389): the check itself is the platform's (`treeRules.ts`
// `collidingSibling` over `@veyq/core-js/tree`: each name folded by ITS OWN kind, behind its application's route slug, so
// only siblings of one route group meet, ADR 0080 decision 16); this is the sentence a create or rename dialog shows for
// the sibling it found. The server's `409 slug_taken` stays the race backstop.
import { registry } from '../applications/registry'
import type { TreeNode } from './tree'

/** "There is already a file named “x” here." — the sibling's noun from ITS kind (`KindDescriptor.nounWithArticle`), the
 *  neutral `words.node_an_item` for a kind that registers none: core names no application kind in its own strings. */
export function nameTakenMessage(t: (key: string, args?: Record<string, unknown> | unknown[]) => string, sibling: TreeNode): string {
  const info = registry.kind(sibling.kind)
  const noun = (info.supported && info.nounWithArticle) || 'words.node_an_item'
  return t('words.node_name_taken_by', [t(noun), sibling.name])
}
