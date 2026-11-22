// What a bookmark offers to whoever looks (ADR 0120): one pure, FAIL-CLOSED function over core's `Access` and the node.
// A bookmark is a member's: no link serves one (decision 7), so a guest is offered nothing here, whatever a page hands it.
//
// |                                          | member | guest |
// |------------------------------------------|--------|-------|
// | open it, report its share (the hand-off) | ✓      | ✗     |
// | create one at a level                    | ✓ (1)  | ✗     |
// | rename, move, delete (core's answer)     | ✓ (2)  | ✗     |
//
// (1) where core's folder create is offered: a writer, not during a pending deletion, never in a guest's identity folder.
// (2) `nodeRights` as it stands: a pending deletion writes none of them. No share: a bookmark roots no link.
//
// The rule is the platform's (`CoreRights.bookmark`, `@veyq/core-js/navigation`, held by contracts/platform/plans/v1-vectors.json);
// this file reads the session and core's node rights into it.
import { bookmarkRights as coreBookmarkRights } from '@veyq/core-js/navigation'
import { folderRights, nodeRights, type Access, type NodeRights, type RightsTarget } from '../access'

export interface BookmarkRights extends Pick<NodeRights, 'rename' | 'move' | 'delete'> {
  /** Open the link, and hand its share's report to where the share lives. */
  open: boolean
}

export const NO_BOOKMARK_RIGHTS: Readonly<BookmarkRights> = Object.freeze({ open: false, rename: false, move: false, delete: false })

/** The rights at a bookmark node (`at.kind` SHARE_BOOKMARK). */
export function bookmarkRights(access: Access, at: RightsTarget): BookmarkRights {
  const n = nodeRights(access, at)
  const r = coreBookmarkRights(access.mode === 'member', at.kind !== null, access.mode === 'member' && access.epochs !== null, n.rename, n.move, n.delete)
  return { open: r.open, rename: r.rename, move: r.move, delete: r.delete }
}

/** Whether `access` may create a bookmark at the level `at` describes (a folder, or a member's scope root). */
export function canCreateBookmark(access: Access, at: RightsTarget): boolean {
  return access.mode === 'member' && folderRights(access, at).createFolder
}
