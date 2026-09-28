// The sealed device register — the operator-blind naming layer over ADR 0004's deliberately unnamed session
// families (en.account.device-register).
//
// ONE blob sealed under the Master Key (`seal({ purpose: "device-register", version }, masterKey, blob)`), synced with the account:
// the server relays one opaque ciphertext + a monotonic `version` for optimistic concurrency, and learns no
// device name, no device count, and above all no device<->family and no device<->wrap link — the links the
// unnamed families and the anonymous wraps exist to withhold. The sealed plaintext is
// `{ devices: { <deviceId>: { label, lastSeenAt } }, families: { <familyId>: <deviceId> },
//    wraps: { <wrapId>: <deviceId> } }`; `version` is the cleartext wire counter, NOT part of the plaintext.
//
// Three maps rather than one because they decay at completely different rates: a family dies at every logout,
// expiry and revoke; a wrap dies at every re-enrolment, including re-enrolling at the SAME tier (ADR 0005 §9);
// a device persists across all of them. Folding either join into the device entry would tie a device's lifetime
// to its newest family or wrap — the exact bug the register exists to avoid. It also keeps a device entry
// single-writer, so retiring another device's wrap never rewrites somebody else's entry.
//
// The wrap join is what makes ADR 0058's escalation possible: a wrap id is known ONLY to the device that minted
// it (it comes back from the add-wrap call and lives in that device's local trust/remember record), so no other
// client could ever derive this mapping — the register is the only place it can be shared, and it is shared
// sealed. Note the direction of the dependency: the enrolment modules know nothing about this file. They RETURN
// the new id and their caller records it, which is what keeps the register off every unlock path.
//
// Every open — before a read and before every write — goes through this device's trust-state marks exactly as
// the pin store's does (`trustMarks.ts`, ADR 0028 Decision 7): an older register, a missing one after this device
// held one, and one that does not open are refused, never read as empty. The sessions view still lists and
// revokes every row without it (uc.account.sessions §E4); what a refusal costs is the names and the writes.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { accountDeviceRegisterApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { isCodedError, HttpStatus } from '../../shared/httpError'
import { masterKey } from '../../app/vault'
import { openAccountDocument, raiseMarks } from './trustMarks'

/** The register's seal context at a stored `version` (ADR 0072, vector `seal.sym.device-register`, domain-separate
 *  from the pins): bound to the version the server stores it at, so a blob served under another does not open. */
const registerContext = (version: number) => ({ purpose: 'device-register', version }) as const

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
  /** wrapId → deviceId. The join that lets ONE device retire ANOTHER's convenience wrap (ADR 0058). */
  wraps: Record<string, string>
}

/** What the sessions view reads back — the annotation layer over the two server-authoritative lists it holds. */
export interface RegisterView {
  /** familyId → device label. A family the register cannot name is simply absent (§E4). */
  names: Record<string, string>
  /**
   * familyId → the **served** wrap ids of that family's device: the handle ADR 0058's escalation deletes.
   * Empty/absent is the normal case for a device the register has never seen — and is exactly when the
   * escalation must not be offered.
   */
  wrapsOfFamily: Record<string, ReadonlyArray<string>>
  /** § Devices with a saved key — a register-known device holding a served wrap with no live family. */
  savedKeyDevices: ReadonlyArray<SavedKeyDevice>
}

export interface SavedKeyDevice {
  deviceId: string
  label: string
  /** The served wrap ids this device holds. Non-empty by construction — that is what puts it in this list. */
  wrapIds: ReadonlyArray<string>
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

const empty = (): RegisterPlain => ({ devices: {}, families: {}, wraps: {} })

const mapOr = (value: unknown): Record<string, string> =>
  value && typeof value === 'object' ? (value as Record<string, string>) : {}

// NOTE for anyone adding a fourth map: [unsealRegister] and [sealRegister] enumerate the maps BY NAME. A map
// added to `RegisterPlain`, `empty`, `evict` and `mutateAndSave`'s spread but not to these two is dropped
// silently on write — and an in-memory test stays green, because the object it asserts on never went through
// the seal. `the sealed round-trip carries all three maps` is the test that pins it.

/** Decrypt the register from a sealed blob. Throws when it does not open; the caller refuses it as unreadable
 *  (ADR 0028 Decision 7). A map missing from a register that did open reads as empty — an older writer's shape. */
function unsealRegister(mk: Uint8Array, blobB64: string, version: number): RegisterPlain {
  const parsed = JSON.parse(decoder.decode(v1.open(registerContext(version), mk, fromB64std(blobB64)))) as Partial<RegisterPlain>
  if (!parsed || typeof parsed !== 'object') throw new Error('device_register_unreadable')
  return {
    devices: parsed.devices && typeof parsed.devices === 'object' ? parsed.devices : {},
    families: mapOr(parsed.families),
    wraps: mapOr(parsed.wraps),
  }
}

/** Open a served register through this device's marks — refused if rolled back, missing after a mark, or
 *  unreadable. uc.account.sessions §E4 still holds above it: the sessions view renders rows unnamed. */
function openRegister(mk: Uint8Array, served: { blob: string | null; version: number }): Promise<RegisterPlain> {
  return openAccountDocument('register', mk, served, (blob) => unsealRegister(mk, blob, served.version), empty)
}

/** Seal a register under the Master Key for the version it will be stored at — the PUT's `version + 1`, since the
 *  server bumps the version it accepts by one → the opaque base64 blob to PUT. */
function sealRegister(mk: Uint8Array, reg: RegisterPlain, storedVersion: number): string {
  const json = encoder.encode(JSON.stringify({ devices: reg.devices, families: reg.families, wraps: reg.wraps }))
  return b64std(v1.seal(registerContext(storedVersion), mk, json))
}

/** Fetch + open the caller's register (born-empty → version 0). Requires an unlocked vault. Throws
 *  `TrustStateRefused` for a register this device must not accept. */
async function loadRegister(): Promise<OpenedRegister> {
  const mk = masterKey()
  if (!mk) throw new Error('device register requires an unlocked vault')
  try {
    const res = await withRefresh(() => accountDeviceRegisterApi().getDeviceRegister())
    return { version: res.version, ...(await openRegister(mk, res)) }
  } finally {
    mk.fill(0)
  }
}

function isStaleVersion(e: unknown): Promise<boolean> {
  return isCodedError(e, HttpStatus.conflict, 'stale_version')
}

/**
 * Drop the least-recently-seen devices beyond [MAX_DEVICES], and any family **or wrap** row pointing at a device
 * that just went — an unresolvable join is worse than no join. **Always recomputed on the map it is about to
 * seal** — see [mutateAndSave]. This is also what bounds the wrap map: it has no ceiling of its own.
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
  for (const [wrapId, deviceId] of Object.entries(reg.wraps)) {
    if (doomed.has(deviceId)) delete reg.wraps[wrapId]
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
 *   - A removal whose truth is **external** (the server's family list or its served key material, snapshotted
 *     when it was read) is applied as a **frozen id-delta** — "remove exactly these ids". Never as "keep only
 *     what I saw": a family or a wrap created by another device *between our read and our write* is absent from
 *     our list without ever having been dropped, and the second formulation deletes it.
 *   - A removal whose truth is **internal** (the register's own contents, i.e. eviction) is **recomputed on the
 *     merged map**. That is why [evict] is called here, after `apply`, and not inside any caller.
 *
 * A **user-intended** removal — ADR 0058's "cut this device off completely" — is safe under the same rule only
 * because the intent is executed against the server first: the DELETE on the wrap endpoint is what makes it
 * true, and the join row's disappearance is *derived* from the next read of key material. So a wrap row is never
 * pruned from a DELETE this client merely issued (that would make the removal internal again, and would destroy
 * the handle a half-succeeded escalation is retried from) — only from [reconcileWithSessions]'s frozen delta.
 *
 * There is deliberately no manual "forget this entry": that would be the one removal with *no* server act behind
 * it, which a union-merge resurrects from any concurrent device and nothing ever heals. Adding it needs
 * tombstones.
 */
async function mutateAndSave(base: OpenedRegister, apply: (reg: RegisterPlain) => void): Promise<void> {
  let current = base
  for (let attempt = 0; ; attempt++) {
    const next: RegisterPlain = {
      devices: { ...current.devices },
      families: { ...current.families },
      wraps: { ...current.wraps },
    }
    apply(next)
    evict(next) // internal truth ⇒ recomputed on the merged map, every attempt
    const mk = masterKey()
    if (!mk) throw new Error('device register requires an unlocked vault')
    try {
      const blob = sealRegister(mk, next, current.version + 1)
      await withRefresh(() =>
        accountDeviceRegisterApi().putDeviceRegister({ deviceRegisterUpdate: { blob, version: current.version } }),
      )
      await raiseMarks(mk, { register: current.version + 1 })
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

/**
 * Re-seal the device register from the **old** Master Key to a **new** one — key rotation (ADR 0060, step 4).
 * The sibling of `resealPinsUnderNewMasterKey`, and it exists for the same reason: mid-rotation the vault still
 * holds the old Master Key, so [mutateAndSave] would seal under the key being retired.
 *
 * It opens through the same marks as every read, under the old key's slot, and raises the new key's — the pin
 * store's sibling rule (`resealPinsUnderNewMasterKey`). Best-effort: a re-seal that never lands leaves a register
 * the new key cannot open, which every later read refuses as `trust_state_unreadable`. That costs display names
 * and register writes, not access — rows still list and revoke unnamed (uc.account.sessions §E4) — but it no
 * longer repairs itself by starting over empty (ADR 0028 § What is still open).
 *
 * The contents cross unchanged, including the wrap join rows. That looks wrong for a moment and is right: the
 * rotation deletes every device wrap server-side, so those rows are already stale — and pruning them **here**
 * would be an internal removal (the rule above forbids it), where the honest path is the next
 * [reconcileWithSessions], whose frozen delta against the server's served wrap list removes them as derived
 * truth.
 */
export async function resealRegisterUnderNewMasterKey(oldMk: Uint8Array, newMk: Uint8Array): Promise<void> {
  const res = await withRefresh(() => accountDeviceRegisterApi().getDeviceRegister())
  const reg = await openRegister(oldMk, res)
  await withRefresh(() =>
    accountDeviceRegisterApi().putDeviceRegister({
      deviceRegisterUpdate: { blob: sealRegister(newMk, reg, res.version + 1), version: res.version },
    }),
  )
  await raiseMarks(newMk, { register: res.version + 1 })
}

/**
 * Everything the sessions view reads out of the register, against the two **server-authoritative** lists it is
 * holding — the live families and the served wrap ids. The register annotates those lists; it never replaces
 * them, so anything it does not know still renders and is still revocable (§E4).
 *
 * `servedWrapIds` is `null` when the caller does **not** hold the authoritative wrap list (the key-material
 * fetch failed, or the panel is locked). That is emphatically not the same as "the server serves no wraps": with
 * `null` no wrap is claimed to exist and no escalation is offered, whereas an empty array would say "the server
 * serves none", which is a fact only a successful fetch can assert.
 *
 * **`thisDeviceId` is excluded from both wrap-bearing results on purpose.** Every action they feed reaches into
 * *another* device; the local equivalent is untrust/forget on the device panel, which also drops the local key.
 * Offering the remote-only half here would leave this browser holding a local record whose wrap is gone — a
 * state uc.account.trust-device E1 tolerates after an operator withholds a wrap, but that we must not create on
 * purpose.
 */
export async function readRegisterView(
  liveFamilyIds: ReadonlyArray<string>,
  servedWrapIds: ReadonlyArray<string> | null,
  thisDeviceId: string,
): Promise<RegisterView> {
  const reg = await loadRegister()
  const live = new Set(liveFamilyIds)
  const served = servedWrapIds === null ? null : new Set(servedWrapIds)

  const names: Record<string, string> = {}
  for (const [familyId, deviceId] of Object.entries(reg.families)) {
    const entry = reg.devices[deviceId]
    if (entry) names[familyId] = entry.label
  }

  // deviceId → its served wrap ids. One convenience wrap per device is the rule (ADR 0005 §9), but a row a
  // reconcile has not caught up with yet makes this a list rather than a value — and "remove this device's saved
  // key" means all of them, not an arbitrary one.
  const servedWrapsOfDevice: Record<string, string[]> = {}
  if (served) {
    for (const [wrapId, deviceId] of Object.entries(reg.wraps)) {
      if (!served.has(wrapId) || deviceId === thisDeviceId || !reg.devices[deviceId]) continue
      ;(servedWrapsOfDevice[deviceId] ??= []).push(wrapId)
    }
  }

  const wrapsOfFamily: Record<string, ReadonlyArray<string>> = {}
  const devicesWithLiveFamily = new Set<string>()
  for (const [familyId, deviceId] of Object.entries(reg.families)) {
    if (!live.has(familyId)) continue
    devicesWithLiveFamily.add(deviceId)
    const wrapIds = servedWrapsOfDevice[deviceId]
    if (wrapIds?.length) wrapsOfFamily[familyId] = wrapIds
  }

  const savedKeyDevices = Object.entries(servedWrapsOfDevice)
    .filter(([deviceId]) => !devicesWithLiveFamily.has(deviceId))
    .map(([deviceId, wrapIds]) => ({ deviceId, label: reg.devices[deviceId].label, wrapIds }))

  return { names, wrapsOfFamily, savedKeyDevices }
}

/**
 * Write moment 1 — record this device, the family this login created, and the convenience wrap this device
 * currently holds according to its **local** trust/remember record. Purely **additive**, so a union-merge is
 * trivially correct and a concurrent device's entry always survives.
 *
 * Note it runs after the **unlock**, not the login: the blob is Master-Key-sealed and at login there is no Master
 * Key. An existing label is preserved — a rename must not be undone by the next sign-in.
 *
 * Re-asserting the wrap row from local state on every unlock is what makes the join **self-healing**: a row lost
 * to a prune, a failed write, or an operator that briefly withheld the wrap comes back without anyone noticing
 * it was gone. It is also why the id must come from the local record and never from the register — the register
 * is on no unlock path, in either direction.
 */
export async function rememberThisDevice(
  thisDeviceId: string,
  fallbackLabel: string,
  familyId: string | null,
  wrapId: string | null,
  now: number = Date.now(),
): Promise<void> {
  const base = await loadRegister()
  await mutateAndSave(base, (reg) => {
    const prior = reg.devices[thisDeviceId]
    reg.devices[thisDeviceId] = { label: prior?.label ?? fallbackLabel, lastSeenAt: now }
    if (familyId) reg.families[familyId] = thisDeviceId
    if (wrapId) reg.wraps[wrapId] = thisDeviceId
  })
}

/**
 * Write moment 2 — right after enrolling a convenience wrap, the **only** moment the new wrap id exists
 * anywhere. Every enrolment mints a fresh id (ADR 0005 §9), so without this write the register trails a session
 * behind and the device that most needs naming — one signed into exactly once, on someone else's machine — is
 * the one it could not name.
 *
 * The device entry is upserted alongside because write moment 1 is fire-and-forget: this can land first, and a
 * wrap row pointing at a device with no entry is an unresolvable join. Purely additive, like moment 1.
 */
export async function rememberThisDeviceWrap(
  thisDeviceId: string,
  fallbackLabel: string,
  wrapId: string,
  now: number = Date.now(),
): Promise<void> {
  const base = await loadRegister()
  await mutateAndSave(base, (reg) => {
    const prior = reg.devices[thisDeviceId]
    reg.devices[thisDeviceId] = { label: prior?.label ?? fallbackLabel, lastSeenAt: now }
    reg.wraps[wrapId] = thisDeviceId
  })
}

/**
 * Write moment 3 — reconcile against the two authoritative lists the sessions view is holding.
 *
 * `liveFamilyIds` and `servedWrapIds` are the server's answers, so both prunes are **external truth** and are
 * frozen into explicit id-deltas *before* any retry can re-base. `currentFamilyId` covers the path where write
 * moment 1 could not name the family (a cold boot restore, where no login happened in this page load and the
 * client never learned its own family id): the server's own current-marker supplies it here.
 *
 * `servedWrapIds === null` means "I do not hold the wrap list" (the fetch failed) — then no wrap row is pruned
 * at all. An empty array is the opposite claim, and only a successful fetch may make it.
 *
 * Returns whether anything was written, so callers can skip a needless re-read.
 */
export async function reconcileWithSessions(
  liveFamilyIds: ReadonlyArray<string>,
  servedWrapIds: ReadonlyArray<string> | null,
  thisDeviceId: string,
  fallbackLabel: string,
  currentFamilyId: string | null,
  now: number = Date.now(),
): Promise<boolean> {
  const base = await loadRegister()
  const live = new Set(liveFamilyIds)

  // FROZEN id-deltas: exactly the ids this client saw and the server did not list. Computed once, from the
  // pre-merge map, and applied verbatim on every retry — so a family or a wrap another device created after our
  // read is not in either set and therefore survives.
  const doomedFamilies = Object.keys(base.families).filter((id) => !live.has(id))
  const doomedWraps =
    servedWrapIds === null
      ? []
      : (() => {
          const served = new Set(servedWrapIds)
          return Object.keys(base.wraps).filter((id) => !served.has(id))
        })()

  const needsCurrent = currentFamilyId !== null && base.families[currentFamilyId] !== thisDeviceId
  const needsDevice = !base.devices[thisDeviceId]
  if (doomedFamilies.length === 0 && doomedWraps.length === 0 && !needsCurrent && !needsDevice) return false

  await mutateAndSave(base, (reg) => {
    for (const id of doomedFamilies) delete reg.families[id]
    for (const id of doomedWraps) delete reg.wraps[id]
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
