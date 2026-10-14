// Who uploaded a file's current content, as its page names it (ADR 0083 Addendum, uc.files.view-file step 0). The server
// records the upload's opener on the attachment and serves it to MEMBERS only, as an opaque key (`uploaderKey` on the
// member's listing): `user:<id>` is a member, named by core's display rule (decision 9) and linking to the profile while
// they are in the scope - one who left is a "former member" with no link; `share:<identity>` is a link's guest, named by
// the name it gave itself (inbox #431, {@link guestUploaderName}) - the page marks it as a guest's, "Ben (guest)" (core's
// `guestDisplayName`, inbox #433) - and "a guest" where that name does not open. A share
// guest's listing carries no key and no name at all, so a guest's page names nobody. Files reaches core only (ADR 0079):
// the people come from `core/people`, a guest's name opens through `core/sharing/guestIdentity`.
import type { Person } from '../../core/people/directory'
import type { TreeSource } from '../../core/access'
import type { TreeNode } from '../../core/node/tree'
import { openIdentityName } from '../../core/sharing/guestIdentity'
import type { FileUploaderName } from '../../api/generated/files'

export type FileUploader =
  | { kind: 'member'; userId: string; name: string }
  | { kind: 'former' }
  /** A link's guest: `name` as it named itself, bare (the page marks it, "Ben (guest)"), or null where that does not open
   *  (the page then says "a guest"). */
  | { kind: 'guest'; name: string | null }

const USER = 'user:'
const SHARE = 'share:'

/** Whether naming `key` needs the scope's people (a member's key does; a guest's or none does not). */
export const needsPeople = (key: string | null): boolean => !!key && key.startsWith(USER)

/** The uploader `key` names, against the scope's CURRENT members `people` (null: the directory could not be read - a
 *  member is then left unnamed rather than called former, as Memories' lightbox does); a guest's name is `guestName`'s
 *  answer for its identity id (none: unnamed). Null: nothing to show. */
export function uploaderOf(
  key: string | null,
  people: ReadonlyMap<string, Person> | null,
  guestName: (identityId: string) => string | null = () => null,
): FileUploader | null {
  if (!key) return null
  if (key.startsWith(SHARE)) return { kind: 'guest', name: guestName(key.slice(SHARE.length)) || null }
  if (!key.startsWith(USER) || !people) return null
  const userId = key.slice(USER.length)
  const person = people.get(userId)
  return person ? { kind: 'member', userId, name: person.displayName } : { kind: 'former' }
}

/**
 * The name of the guest `identityId` who uploaded the file `fileId`, as a member's `tree` reads it (inbox #431), or null:
 *  - **inside a drop box**, the identity folder the file lies in names it - the folder of THAT identity (ADR 0080 decision
 *    11), as its row in the drop box says it; the guest's own sealed name is under its `K_up`, which this does not open;
 *  - else the sealed name the member's listing served (`sealed`, with the share it is bound to), opened under the file's
 *    key or the nearest ancestor's (core's `openIdentityName`): a READ_WRITE guest named itself under its link's ROOT,
 *    which is the file itself (a single-file link) or a folder above it. A guest of a deleted link is still served.
 */
export function guestUploaderName(
  tree: TreeSource<TreeNode>, scopeId: string, fileId: string, identityId: string, sealed: FileUploaderName | null,
): string | null {
  const path = upward(tree, fileId)
  const folder = path.find((n) => n.shareIdentityId === identityId)
  if (folder) return tree.nameOf(folder.id) || null
  if (!sealed) return null
  return openIdentityName(scopeId, sealed.shareId, identityId, sealed.sealedName, keysOf(tree, path))
}

/** `id` and its ancestors as `tree` serves them, nearest first; a served parent cycle stops. */
function upward(tree: TreeSource<TreeNode>, id: string): TreeNode[] {
  const path: TreeNode[] = []
  const seen = new Set<string>()
  for (let n = tree.node(id); n && !seen.has(n.id); n = n.parentId ? tree.node(n.parentId) : null) {
    seen.add(n.id)
    path.push(n)
  }
  return path
}

/** The content keys of `path` the session holds, opened one at a time as the name asks for the next one up; a chain that
 *  does not open yields nothing for that node. */
function* keysOf(tree: TreeSource<TreeNode>, path: readonly TreeNode[]): Generator<Uint8Array> {
  for (const n of path) {
    let key: Uint8Array | null
    try {
      key = tree.keyOf(n.id)
    } catch {
      key = null // a wrap that does not open (`OpenRefused`): no key here
    }
    if (key) yield key
  }
}
