// The sealed device register — the operator-blind naming layer over ADR 0004's deliberately unnamed session
// families (en.account.device-register).
//
// ONE blob sealed under the Master Key (`symWrap(masterKey, blob, "device-register")`), synced with the account:
// the server relays one opaque ciphertext + a monotonic `version` for optimistic concurrency, and learns no
// device name, no device count, and above all no device<->family link — the link the unnamed families exist to
// withhold. The sealed plaintext is `{ devices: { <deviceId>: { label, lastSeenAt } }, families: { <familyId>:
// <deviceId> } }`; `version` is the cleartext wire counter, NOT part of the plaintext.
//
// Two maps rather than one because they decay at completely different rates: a family dies at every logout,
// expiry and revoke, while a device persists across all of them. Folding the join into the device entry would
// tie a device's lifetime to its newest family — the exact bug the register exists to avoid.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { accountDeviceRegisterApi } from '../../api/client'
import { withRefresh } from './login/login'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import { masterKey } from '../../app/vault'

/** The AAD context binding the register seal (vector-pinned `sym_device_register`, domain-separate from pins). */
export const DEVICE_REGISTER = 'device-register'

export interface DeviceEntry {
  label: string
  /** When this device last reconciled the register. Bounds growth (eviction) — never shown to the user, who
   *  sees the SERVER's timestamps, which are the authoritative ones. */
  lastSeenAt: number
}

export interface RegisterPlain {
  devices: Record<string, DeviceEntry>
  /** familyId → deviceId. The join that lets a session row find its device. */
  families: Record<string, string>
}

interface OpenedRegister extends RegisterPlain {
  version: number
}

const encoder = new TextEncoder()
const decoder = new TextDecoder()

/** Bounds the re-read/merge/retry loop on a `409 stale_version` — a real conflict is a concurrent device write. */
const MAX_WRITE_ATTEMPTS = 5

/**
 * How many devices the register keeps, least-recently-seen evicted beyond it.
 *
 * Set above the backend's per-method wrap ceiling (20) so a device that legitimately holds a convenience wrap is
 * never evicted by a bound meant for a different thing. A wrong value here costs a **label** — never a session
 * row and never a revoke, both of which come from the server — which is why an approximate bound is the right
 * instrument rather than a policy that has to be exactly right.
 */
export const MAX_DEVICES = 32

const empty = (): RegisterPlain => ({ devices: {}, families: {} })

/** Decrypt the register from a sealed blob; an unreadable/absent blob is an empty register (never throws). */
function unsealRegister(mk: Uint8Array, blobB64: string | null): RegisterPlain {
  if (!blobB64) return empty()
  try {
    const parsed = JSON.parse(decoder.decode(v1.symUnwrap(mk, fromB64std(blobB64), DEVICE_REGISTER))) as Partial<RegisterPlain>
    return {
      devices: parsed?.devices && typeof parsed.devices === 'object' ? parsed.devices : {},
      families: parsed?.families && typeof parsed.families === 'object' ? parsed.families : {},
    }
  } catch {
    // uc.account.sessions §E4: an unreadable register renders rows unnamed and blocks nothing. Degrading to
    // empty here is what makes that a design property instead of an error path.
    return empty()
  }
}

/** Seal a register under the Master Key with a fresh nonce → the opaque base64 blob to PUT. */
function sealRegister(mk: Uint8Array, reg: RegisterPlain): string {
  const json = encoder.encode(JSON.stringify({ devices: reg.devices, families: reg.families }))
  return b64std(v1.symWrap(mk, json, DEVICE_REGISTER, v1.randomBytes(12)))
}

/** Fetch + open the caller's register (born-empty → version 0). Requires an unlocked vault. */
async function loadRegister(): Promise<OpenedRegister> {
  const mk = masterKey()
  if (!mk) throw new Error('device register requires an unlocked vault')
  try {
    const res = await withRefresh(() => accountDeviceRegisterApi().getDeviceRegister())
    return { version: res.version, ...unsealRegister(mk, res.blob) }
  } finally {
    mk.fill(0)
  }
}

function isStaleVersion(e: unknown): Promise<boolean> {
  return isCodedError(e, HttpStatus.conflict, 'stale_version')
}

/**
 * Drop the least-recently-seen devices beyond [MAX_DEVICES], and any family row pointing at a device that just
 * went. **Always recomputed on the map it is about to seal** — see [mutateAndSave].
 */
function evict(reg: RegisterPlain): void {
  const ids = Object.keys(reg.devices)
  if (ids.length <= MAX_DEVICES) return
  const doomed = new Set(
    ids
      .sort((a, b) => (reg.devices[b].lastSeenAt ?? 0) - (reg.devices[a].lastSeenAt ?? 0))
      .slice(MAX_DEVICES),
  )
  for (const id of doomed) delete reg.devices[id]
  for (const [familyId, deviceId] of Object.entries(reg.families)) {
    if (doomed.has(deviceId)) delete reg.families[familyId]
  }
}

/**
 * Apply a change and store it, version-guarded. On a `409 stale_version` (another device wrote first) re-read the
 * server's current register, **re-apply the same change on top of it**, and retry.
 *
 * This is where the register's correctness lives, and the rule is not the pin store's. A union-merge would
 * ordinarily resurrect a removed device — deletion here is *real*. It does not, because **every removal in this
 * register is derived, never a user's intent**: the server's family list is the authority, and a client that
 * prunes is only recomputing what the server already decided. A resurrected row names nothing and the next
 * reconcile removes it again.
 *
 * That safety holds only under one discipline, and `apply` callers must honour it:
 *
 *   - A removal whose truth is **external** (the server's family list, snapshotted when it was read) is applied
 *     as a **frozen id-delta** — "remove exactly these ids". Never as "keep only what I saw": a family created by
 *     another device *between our read and our write* is absent from our list without ever having been dropped,
 *     and the second formulation deletes it.
 *   - A removal whose truth is **internal** (the register's own contents, i.e. eviction) is **recomputed on the
 *     merged map**. That is why [evict] is called here, after `apply`, and not inside any caller.
 *
 * There is deliberately no manual "forget this entry": that would be the one genuinely user-*intended* deletion,
 * which a union-merge resurrects from any concurrent device. Adding it needs tombstones.
 */
async function mutateAndSave(base: OpenedRegister, apply: (reg: RegisterPlain) => void): Promise<void> {
  let current = base
  for (let attempt = 0; ; attempt++) {
    const next: RegisterPlain = {
      devices: { ...current.devices },
      families: { ...current.families },
    }
    apply(next)
    evict(next) // internal truth ⇒ recomputed on the merged map, every attempt
    const mk = masterKey()
    if (!mk) throw new Error('device register requires an unlocked vault')
    try {
      const blob = sealRegister(mk, next)
      await withRefresh(() =>
        accountDeviceRegisterApi().putDeviceRegister({ deviceRegisterUpdate: { blob, version: current.version } }),
      )
      return
    } catch (e) {
      if (attempt + 1 < MAX_WRITE_ATTEMPTS && (await isStaleVersion(e))) {
        current = await loadRegister() // re-base on the server's current register, then re-apply + retry
        continue
      }
      throw e
    } finally {
      mk.fill(0)
    }
  }
}

/** The label for each family the register can name. Families it cannot name are simply absent (§E4). */
export async function deviceNames(): Promise<Record<string, string>> {
  const reg = await loadRegister()
  const names: Record<string, string> = {}
  for (const [familyId, deviceId] of Object.entries(reg.families)) {
    const entry = reg.devices[deviceId]
    if (entry) names[familyId] = entry.label
  }
  return names
}

/**
 * Write moment 1 — record this device and the family this login created. Purely **additive**, so a union-merge
 * is trivially correct and a concurrent device's entry always survives.
 *
 * Note it runs after the **unlock**, not the login: the blob is Master-Key-sealed and at login there is no Master
 * Key. An existing label is preserved — a rename must not be undone by the next sign-in.
 */
export async function rememberThisDevice(
  thisDeviceId: string,
  fallbackLabel: string,
  familyId: string | null,
  now: number = Date.now(),
): Promise<void> {
  const base = await loadRegister()
  await mutateAndSave(base, (reg) => {
    const prior = reg.devices[thisDeviceId]
    reg.devices[thisDeviceId] = { label: prior?.label ?? fallbackLabel, lastSeenAt: now }
    if (familyId) reg.families[familyId] = thisDeviceId
  })
}

/**
 * Write moment 2 — reconcile against the authoritative family list the sessions view is holding.
 *
 * `liveFamilyIds` is the server's answer, so the prune it implies is **external truth** and is frozen into an
 * explicit id-delta *before* any retry can re-base. `currentFamilyId` covers the path where write moment 1 could
 * not name the family (a cold boot restore, where no login happened in this page load and the client never
 * learned its own family id): the server's own current-marker supplies it here.
 *
 * Returns whether anything was written, so callers can skip a needless re-read.
 */
export async function reconcileWithSessions(
  liveFamilyIds: ReadonlyArray<string>,
  thisDeviceId: string,
  fallbackLabel: string,
  currentFamilyId: string | null,
  now: number = Date.now(),
): Promise<boolean> {
  const base = await loadRegister()
  const live = new Set(liveFamilyIds)

  // FROZEN id-delta: exactly the families this client saw and the server did not list. Computed once, from the
  // pre-merge map, and applied verbatim on every retry — so a family another device created after our read is
  // not in this set and therefore survives.
  const doomedFamilies = Object.keys(base.families).filter((id) => !live.has(id))

  const needsCurrent = currentFamilyId !== null && base.families[currentFamilyId] !== thisDeviceId
  const needsDevice = !base.devices[thisDeviceId]
  if (doomedFamilies.length === 0 && !needsCurrent && !needsDevice) return false

  await mutateAndSave(base, (reg) => {
    for (const id of doomedFamilies) delete reg.families[id]
    if (needsDevice || needsCurrent) {
      const prior = reg.devices[thisDeviceId]
      reg.devices[thisDeviceId] = { label: prior?.label ?? fallbackLabel, lastSeenAt: now }
    }
    if (currentFamilyId) reg.families[currentFamilyId] = thisDeviceId
  })
  return true
}

/**
 * Rename the device a family belongs to. A device is one-to-many with its sessions, so this reaches every session
 * of that device at once — the UI copy must say so.
 *
 * Last write wins on a conflict: a rename is a deliberate act, and whoever renamed last meant it. Renaming a
 * family the register cannot name is a no-op, not an error — there is no device to rename.
 */
export async function renameDeviceOfFamily(familyId: string, label: string): Promise<void> {
  const base = await loadRegister()
  const target = base.families[familyId]
  if (!target) return
  await mutateAndSave(base, (reg) => {
    const prior = reg.devices[target]
    if (prior) reg.devices[target] = { ...prior, label }
  })
}
