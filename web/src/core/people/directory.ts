// The people of the space, as the interface names them (ADR 0083 decision 9): a member's **real name** when it opens,
// else the **username**. The real name is sealed under `K_tenant` (`features/account/profile/memberName.ts`, bound to
// (tenant scope, member)), so it is read from the TENANT scope's member directory whatever scope is on screen: a group's
// roster carries usernames, and the group's Memory chips and picker take their names from the tenant's. All on device,
// the server serves ciphertext.
//
// Two reads: {@link loadScopePeople} - the CURRENT members of one scope (a Memory's picker and chips), and
// {@link loadTenantPeople} - every member of the tenant (a profile page). A person a roster does not list is a former
// member to whoever asks (the caller decides how it renders).
import { shallowRef } from 'vue'
import { activeScopeKey, activeSpace } from '../../app/activeSpace'
import { listScopeMembers } from '../../features/spaces/members'
import { openScope } from '../../features/spaces/scopes'
import { zeroEpochKeys } from '../../features/spaces/epochs'
import { unsealName, type MemberName } from '../../features/account/profile/memberName'
import { ScopeKind } from '../../api/generated/scopes'
import { syncAvatars, resetAvatars } from './avatarCache'

export { avatarUrlOf } from './avatarCache'

/** One person as the interface shows them. */
export interface Person {
  userId: string
  username: string
  /** The sealed real name when it opened; null otherwise (awaiting access, an unreadable blob). */
  realName: string | null
  /** The real name, else the username - the one display rule (ADR 0083 decision 9). */
  displayName: string
}

/** The display rule: the real name when it opens (non-blank), else the username. */
export function displayNameOf(username: string, name: MemberName | null | undefined): string {
  const real = name ? `${name.firstName} ${name.lastName}`.trim() : ''
  return real || username
}

/** The latest picture sync, started by a roster read and NOT awaited by it: names show at once, pictures follow reactively. */
let avatarSync: Promise<void> = Promise.resolve()
const NAMES_TTL_MS = 60_000
/** One tenant member as the roster read holds it: the username, the opened real name, and the identity bundle the directory
 *  serves for them (base64) - the key a profile's safety-number compare starts from (ADR 0028 Addendum). */
interface TenantRow {
  username: string
  name: MemberName | null
  identityPublicKey: string
}
let namesCache: { at: number; tenantScopeId: string; names: Map<string, TenantRow> } | null = null

/** Every member of the tenant as last read (null before the first read): a reactive window onto the cache, so a place that
 *  names a person (a profile page, its breadcrumb) can answer at once from what is already on the device. */
const snapshot = shallowRef<Map<string, Person> | null>(null)

/** The member `userId` as already read, or undefined while the roster has not been read yet (or the user is no member).
 *  Synchronous and reactive; {@link loadTenantPeople} fills it, and the shell starts that read when a tenant space opens. */
export function personNow(userId: string): Person | undefined {
  return snapshot.value?.get(userId)
}

/** Forget the cached tenant names and every cached profile picture, revoking their object URLs (a sign-out, a test). */
export function resetPeopleDirectory(): void {
  namesCache = null
  snapshot.value = null
  resetAvatars()
}

/** Re-read the tenant roster now (not at the minute's end) and bring the pictures in line - after the caller changed their
 *  own name or picture, so every avatar on screen follows. Resolves when the pictures settled; never rejects. */
export async function refreshPeopleDirectory(): Promise<void> {
  namesCache = null
  await tenantNames().catch(() => undefined)
  await avatarSync
}

/** The tenant's members with their sealed names opened, read once a minute: the tenant scope is the active one (its key is
 *  held) or is opened for this read and zeroed after it. Empty when the caller holds no tenant grant. */
async function tenantNames(): Promise<Map<string, TenantRow>> {
  if (namesCache && Date.now() - namesCache.at < NAMES_TTL_MS) return namesCache.names
  let scopeId: string
  let tenantId: string | null
  let key: Uint8Array | null
  let release: (() => void) | null = null
  if (activeSpace.kind === ScopeKind.Tenant && activeSpace.scopeId) {
    scopeId = activeSpace.scopeId
    tenantId = activeSpace.tenantId
    key = activeScopeKey()
  } else {
    const opened = await openScope(ScopeKind.Tenant)
    if (!opened) return new Map()
    scopeId = opened.scopeId
    tenantId = opened.tenantId
    key = opened.key
    release = () => zeroEpochKeys(opened.epochs)
  }
  try {
    const rows = await listScopeMembers(scopeId)
    const names = new Map(
      rows.map((m) => [m.userId, {
        username: m.username,
        name: key ? unsealName(key, { scopeId, userId: m.userId }, m.sealedName) : null,
        identityPublicKey: m.identityPublicKey,
      }] as const),
    )
    namesCache = { at: Date.now(), tenantScopeId: scopeId, names }
    snapshot.value = new Map([...names].map(([userId, v]) => [userId, personOf(userId, v.username, v.name)]))
    // The pictures, in step with the roster: only the version integers were read; a picture is fetched when its version is new.
    // The key is copied for the fetches (the scope's own may be zeroed below) and the copy zeroed when they settle.
    if (key && tenantId) {
      const copy = key.slice()
      avatarSync = syncAvatars({ tenantId, scopeId, key: copy }, rows).finally(() => copy.fill(0))
    }
    return names
  } finally {
    release?.()
  }
}

const personOf = (userId: string, username: string, name: MemberName | null): Person => ({
  userId,
  username,
  realName: name ? displayNameOf('', name) || null : null,
  displayName: displayNameOf(username, name),
})

/** The CURRENT members of `scopeId` (a proposed-but-unaccepted group member is not one yet), named by the display rule. */
export async function loadScopePeople(scopeId: string): Promise<Person[]> {
  const [rows, names] = await Promise.all([listScopeMembers(scopeId), tenantNames().catch(() => new Map<string, TenantRow>())])
  return rows
    .filter((m) => !m.pendingConsent)
    .map((m) => personOf(m.userId, m.username, names.get(m.userId)?.name ?? null))
}

/** Every member of the tenant by user id, named by the display rule. A user it does not list is a former member. */
export async function loadTenantPeople(): Promise<Map<string, Person>> {
  const names = await tenantNames()
  return new Map([...names].map(([userId, v]) => [userId, personOf(userId, v.username, v.name)]))
}

/** The identity bundle (base64) the tenant's directory serves for `userId`, or null for a user it does not list (a former
 *  member). Read with the roster, so it is as fresh as the names: a profile's compare starts from it when no pin exists. */
export async function loadIdentityKey(userId: string): Promise<string | null> {
  return (await tenantNames()).get(userId)?.identityPublicKey ?? null
}
