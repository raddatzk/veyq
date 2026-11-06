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
import { folderRights, nodeRights, type Access, type NodeRights, type RightsTarget } from '../access'

export interface BookmarkRights extends Pick<NodeRights, 'rename' | 'move' | 'delete'> {
  /** Open the link, and hand its share's report to where the share lives. */
  open: boolean
}

export const NO_BOOKMARK_RIGHTS: Readonly<BookmarkRights> = Object.freeze({ open: false, rename: false, move: false, delete: false })

/** The rights at a bookmark node (`at.kind` SHARE_BOOKMARK). */
export function bookmarkRights(access: Access, at: RightsTarget): BookmarkRights {
  if (access.mode !== 'member' || at.kind === null) return NO_BOOKMARK_RIGHTS
  const n = nodeRights(access, at)
  return { open: access.epochs !== null, rename: n.rename, move: n.move, delete: n.delete }
}

/** Whether `access` may create a bookmark at the level `at` describes (a folder, or a member's scope root). */
export function canCreateBookmark(access: Access, at: RightsTarget): boolean {
  return access.mode === 'member' && folderRights(access, at).createFolder
}
