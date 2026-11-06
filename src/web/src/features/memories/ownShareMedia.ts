// Which listed media a READ_WRITE guest added itself (inbox #361) — kept on the guest's device, beside its session.
//
// The server lets a guest delete only its own contributions (`deleteShareMedia`: the media's identity must be the
// token's), but it does not tell a guest which listed media those are: a READ_WRITE guest's media open under the node's
// key and carry no identity on the guest listing, so no guest learns which uploads came from one person (ADR 0030
// point 8). The guest's own device knows, because it made them: every upload it completes is remembered here, bound to
// the identity that made it. That is no less than the server would grant — the contributor token that authorizes a
// delete lives in the same store (`core/sharing/guestIdentity.ts`), so a device that lost this record lost the token
// too — and nothing of it leaves the device.
//
// Bound to the identity: after the link's session is forgotten and a new name given, the old identity's media are no
// longer this guest's to delete, and the record starts afresh.

// Keyed by the share - and, for another instance's link a bookmark's page shows in place, its origin (ADR 0121,
// `core/sharing/shareAt.ts`): this origin's key is unchanged.
import { deviceKey, type ShareAt } from '../../core/sharing/shareAt'

type Stored = { identityId: string; mediaIds: string[] }

const storageKey = (at: ShareAt) => deviceKey('veyq:share-own-media', at)

function read(at: ShareAt, identityId: string): string[] {
  try {
    const raw = localStorage.getItem(storageKey(at))
    const stored = raw ? (JSON.parse(raw) as Stored) : null
    return stored?.identityId === identityId && Array.isArray(stored.mediaIds) ? stored.mediaIds : []
  } catch {
    return [] // an unreadable store: nothing is known to be this guest's
  }
}

function write(at: ShareAt, identityId: string, mediaIds: string[]): void {
  try {
    localStorage.setItem(storageKey(at), JSON.stringify({ identityId, mediaIds } satisfies Stored))
  } catch {
    /* a store that refuses: the media stay, only the delete control is not offered for them */
  }
}

/** The media this device added to `shareId` under `identityId`. */
export function loadOwnShareMedia(at: ShareAt, identityId: string): Set<string> {
  return new Set(read(at, identityId))
}

/** Remember that this device added `mediaId` to `shareId` under `identityId`. */
export function rememberOwnShareMedia(at: ShareAt, identityId: string, mediaId: string): void {
  const ids = read(at, identityId)
  if (!ids.includes(mediaId)) write(at, identityId, [...ids, mediaId])
}

/** Forget `mediaId` once it is deleted. */
export function forgetOwnShareMedia(at: ShareAt, identityId: string, mediaId: string): void {
  write(at, identityId, read(at, identityId).filter((id) => id !== mediaId))
}
