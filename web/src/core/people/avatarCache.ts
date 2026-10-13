// The members' profile pictures as the interface draws them (ADR 0084 decision 5, uc.spaces.set-profile-picture): one
// per-user entry `{ version, objectUrl }`. The directory carries only the small `avatarVersion` integer; the picture is
// fetched (`getMemberAvatar`) ONLY when that version is non-zero and differs from the version this cache already holds,
// revalidated with `If-None-Match`, opened under `K_tenant` on the device and held as ONE object URL per user.
//
// The rules this module keeps, each pinned by `avatarCache.test.ts`:
// - version 0 = no picture: no request, and a held entry is dropped (revoked);
// - a changed version replaces the URL and revokes the old one; a reset (sign-out) revokes every URL;
// - concurrent syncs of the same (user, version) share one request;
// - `404 avatar_not_found` drops the entry (E4), a blob that does not open or is no image shows the letter (E3) - the
//   version is still remembered, so a broken or vanished picture is not re-fetched on every directory read;
// - a failed request (offline, 5xx) leaves the entry as it was and is retried by the next sync.
// Members only: the share page has no people and never calls into this module.
import { tenantsApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { HttpStatus, httpStatus } from '../../shared/httpError'
import { useObjectUrls } from '../../shared/state/useObjectUrls'
import { openAvatar } from '../../features/account/profile/memberAvatar'
import { sniffImage } from '../blob/sniff'

/** Where the pictures of one tenant are sealed: its scope (the seal address) and its id (the read path), plus `K_tenant`. */
export interface AvatarSource {
  tenantId: string
  scopeId: string
  /** `K_tenant`; null when this device holds no tenant grant - then no picture is read at all (the letter shows). */
  key: Uint8Array | null
}

/** What the directory tells about one member: only the integer. */
export interface AvatarRow {
  userId: string
  avatarVersion: number
}

// One owned object URL per user: `set` revokes a replaced URL, `drop` one, `clear` all. A module-level store, so no
// unmount hook - `resetAvatars` is its lifetime (sign-out).
const urls = useObjectUrls()
/** The version this device has settled for a user (a URL, or a remembered "shows the letter"). */
const settled = new Map<string, number>()
const inflight = new Map<string, Promise<void>>()
let generation = 0

/** A member's picture as an object URL, or null (the letter). Reactive: a component reading it re-renders on a change. */
export function avatarUrlOf(userId: string | null | undefined): string | null {
  return userId ? (urls.urls.value.get(userId) ?? null) : null
}

/** Forget every picture and revoke every object URL (sign-out, a test). In-flight fetches of the old generation land nowhere. */
export function resetAvatars(): void {
  generation++
  urls.clear()
  settled.clear()
  inflight.clear()
}

function forget(userId: string): void {
  urls.drop(userId)
  settled.delete(userId)
}

async function fetchOne(source: AvatarSource, key: Uint8Array, userId: string, version: number, gen: number): Promise<void> {
  const known = settled.get(userId)
  const alive = () => gen === generation && (settled.get(userId) ?? 0) <= version
  try {
    const sealed = await withRefresh(() =>
      tenantsApi().getMemberAvatar({ tenantId: source.tenantId, userId, ifNoneMatch: known ? `"${known}"` : undefined }),
    )
    if (!alive()) return
    const bytes = openAvatar(key, { scopeId: source.scopeId, userId }, sealed.sealedAvatar)
    const type = bytes ? sniffImage(bytes.subarray(0, 32)) : undefined
    if (bytes && type) urls.set(userId, new Blob([bytes as BlobPart], { type }))
    else urls.drop(userId)
    settled.set(userId, version)
  } catch (e) {
    if (!alive()) return
    const status = httpStatus(e)
    if (status === HttpStatus.notModified) settled.set(userId, version)
    else if (status === HttpStatus.notFound) {
      // cleared since the version was seen (E4), or no longer a member: the letter, and do not ask again for this version
      urls.drop(userId)
      settled.set(userId, version)
    }
    // anything else (offline, 5xx): leave the entry; the next sync retries
  }
}

/**
 * Bring the cache in line with the directory: for each row with `avatarVersion` > 0 that differs from the held version, one
 * fetch; for each row at 0 that still holds an entry, drop it. Resolves when the fetches settle - never rejects.
 * `key` is read only here; the caller may zero it after the returned promise settles.
 */
export async function syncAvatars(source: AvatarSource, rows: readonly AvatarRow[]): Promise<void> {
  const key = source.key
  const gen = generation
  const work: Promise<void>[] = []
  for (const { userId, avatarVersion } of rows) {
    if (avatarVersion <= 0) {
      if (settled.has(userId) || urls.get(userId)) forget(userId)
      continue
    }
    if (!key || settled.get(userId) === avatarVersion) continue
    const id = `${userId}:${avatarVersion}`
    let run = inflight.get(id)
    if (!run) {
      run = fetchOne(source, key, userId, avatarVersion, gen).finally(() => {
        if (inflight.get(id) === run) inflight.delete(id)
      })
      inflight.set(id, run)
    }
    work.push(run)
  }
  await Promise.all(work)
}
