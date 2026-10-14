// Who uploaded a file's current content, as its page names it (ADR 0083 Addendum, uc.files.view-file step 0). The server
// records the upload's opener on the attachment and serves it to MEMBERS only, as an opaque key (`uploaderKey` on the
// member's listing): `user:<id>` is a member, named by core's display rule (decision 9) and linking to the profile while
// they are in the scope - one who left is a "former member" with no link; `share:<identity>` is a link's guest, read as
// "a guest" (no endpoint serves a member the sealed identity name yet - inbox #431). A share guest's listing carries no
// key at all, so a guest's page names nobody. Files reaches core only (ADR 0079): the people come from `core/people`.
import type { Person } from '../../core/people/directory'

export type FileUploader =
  | { kind: 'member'; userId: string; name: string }
  | { kind: 'former' }
  | { kind: 'guest' }

const USER = 'user:'
const SHARE = 'share:'

/** Whether naming `key` needs the scope's people (a member's key does; a guest's or none does not). */
export const needsPeople = (key: string | null): boolean => !!key && key.startsWith(USER)

/** The uploader `key` names, against the scope's CURRENT members `people` (null: the directory could not be read - a
 *  member is then left unnamed rather than called former, as Memories' lightbox does). Null: nothing to show. */
export function uploaderOf(key: string | null, people: ReadonlyMap<string, Person> | null): FileUploader | null {
  if (!key) return null
  if (key.startsWith(SHARE)) return { kind: 'guest' }
  if (!key.startsWith(USER) || !people) return null
  const userId = key.slice(USER.length)
  const person = people.get(userId)
  return person ? { kind: 'member', userId, name: person.displayName } : { kind: 'former' }
}
