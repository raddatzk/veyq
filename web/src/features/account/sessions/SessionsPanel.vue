<script setup lang="ts">
// uc.account.sessions (web, cr-account-sessions-list-and-revoke) — the "Devices and sessions" rubric of the
// settings hub. Every place the account is currently signed in, and the remote sign-out that logout cannot do:
// logout only ends the family it is presented from, so a laptop left behind stays signed in until this screen
// exists.
//
// It is also the ONE place that says what this device is. The current device's row mounts `DeviceTrustPanel`
// (uc.account.trust-device § Untrust), which states this device's unlock tier and offers the forget action —
// there is no separate "This device" rubric to disagree with. **The trap that merge introduced, and the reason
// the mount is a child and not a prop: the device-local status read is unbounded IndexedDB work, so it must be
// resolved BESIDE this loader, never inside it.** Pull it into `useAsyncData` below and a hanging read takes
// the whole list — and its revokes — down with it. That is asserted, not assumed.
//
// **Nothing here waits on the vault.** A session is auth, not unlock (ADR 0003), so listing and revoking must
// work on a locked device — that is the whole point: cutting off a lost device is exactly what you do before
// you can unlock anywhere else. Names ("Kevin's iPhone") are the only vault-dependent half: they come from the
// Master-Key-sealed device register (en.account.device-register) and are layered on **after** the list renders,
// never awaited by it. A locked device therefore still shows coarse dates and a short id, which is a complete
// and fully actionable list. The trap this panel must never fall into is disabling the action it exists to offer.
//
// This panel is also the register's **repair** surface (write moment 3): it is the only place holding BOTH
// server-authoritative lists — the families and the served key material — so it reconciles the register: prunes
// families the server no longer lists, prunes wrap rows the server no longer serves, and records the current
// family when the unlock-time write could not (a cold boot restore never learned its own family id). That is
// what makes a failed write a supported state rather than an error path (§E4).
//
// The second, separately named action it offers — *sign out AND remove this device's saved key* — is ADR 0058's
// escalation. Three things about it are load-bearing and easy to undo by accident:
//   - **family first, then wrap.** The revoke stops an attacker who is online *now*; the wrap only matters at a
//     future sign-in, so the urgent half must never be blocked by the other. The price is that a successful
//     revoke removes the row the second half was offered from — which is why § Devices with a saved key exists.
//   - **a half-success is told, not swallowed** (§E5): signed out, saved key NOT removed. The register keeps the
//     join row (it is pruned only from a later read of the server's wrap list, never from a delete we merely
//     attempted), so the device reappears below and the removal is retried there.
//   - **never a checkbox on the revoke, never a default.** The consequence lands on a person holding another
//     device, who then needs their Emergency Kit — right for a lost laptop, wrong for tidying up an old login.
//
// Markup + classes mirror TenantMembersPanel (settings-section-header + veyq-list-row + veyq-badge) — the built
// list panel is the design source. Confirmation is `window.confirm`, as on that panel's per-row destructive
// action; the inline rename mirrors GroupMembersPanel's owner-only inline rename.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import {
  listSessionFamilies,
  revokeOtherSessionFamilies,
  revokeSessionFamily,
  type SessionFamily,
} from './sessions'
import { signOut } from '../login/signOut'
import { useAsyncData, loadErrorKind } from '../../../shared/state/useAsyncData'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import { handle401 } from '../../../shared/handle401'
import { useToast } from '../../../shared/state/useToast'
import { vaultState } from '../../../app/vault'
import { account } from '../../../app/account'
import {
  readRegisterView,
  reconcileWithSessions,
  renameDeviceOfFamily,
  type RegisterView,
  type SavedKeyDevice,
} from '../deviceRegister'
import { deviceId, guessDeviceLabel } from '../deviceIdentity'
import { listSavedKeyWraps, removeSavedKey, type SavedKeyWrap } from '../savedKeys'
import { localWrapId } from '../localWrap'
import DeviceTrustPanel from '../trustDevice/DeviceTrustPanel.vue'

const { t, locale } = useI18n()
const router = useRouter()
const toast = useToast()
const on401 = handle401(router)

const families = ref<SessionFamily[]>([])
const actionError = ref('')

const EMPTY_VIEW: RegisterView = { names: {}, wrapsOfFamily: {}, savedKeyDevices: [] }
/** What the register can say about the two lists below. Empty while locked, and empty per family it cannot name (§E4). */
const view = ref<RegisterView>(EMPTY_VIEW)
/**
 * The server's device-local saved keys — `null` means "we do not hold that list" (locked, or the fetch failed),
 * which is deliberately NOT the same as the empty array. Only a successful fetch may claim there are none; the
 * difference is what keeps a transport error from pruning every join row.
 */
const savedWraps = ref<SavedKeyWrap[] | null>(null)
/** This device's own saved key, from its LOCAL record — the one thing the exclusion-shaped action subtracts. */
const myWrapId = ref<string | null>(null)

const { pending: loading, error: loadErr, reload } = useAsyncData(async () => {
  families.value = await listSessionFamilies()
  void loadRegisterHalf()
}, { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

/**
 * Reconcile the register against the two authoritative lists we are holding, then read back what it can say.
 *
 * Deliberately **not awaited** by the loader and deliberately silent on failure: this is the cosmetic +
 * escalation half, and a register that is missing, stale or unwritable must leave the list rendering and every
 * revoke working. Skipped entirely while locked — the register is sealed under the Master Key, so there is
 * nothing to read and nothing to write, and the escalation is absent there by construction (§E3).
 */
async function loadRegisterHalf(): Promise<void> {
  if (!vaultState.unlocked) {
    view.value = EMPTY_VIEW
    savedWraps.value = null
    myWrapId.value = null
    return
  }
  // A failed key-material fetch leaves this null on purpose: no wrap is claimed to exist, no wrap row is pruned,
  // and no wrap-shaped action is offered — rather than the whole naming half going down with it.
  savedWraps.value = await listSavedKeyWraps().catch(() => null)
  myWrapId.value = await localWrapId(account.email).catch(() => null)
  const liveFamilyIds = families.value.map((f) => f.id)
  const servedWrapIds = savedWraps.value?.map((w) => w.id) ?? null
  try {
    await reconcileWithSessions(
      liveFamilyIds,
      servedWrapIds,
      deviceId(),
      guessDeviceLabel(t('sessions.unknownDevice')),
      families.value.find((f) => f.current)?.id ?? null,
    )
    view.value = await readRegisterView(liveFamilyIds, servedWrapIds, deviceId())
  } catch {
    /* unnamed rows and a missing escalation are supported states — never an error the user must act on */
  }
}

/** The row's heading: the device's name when the register knows it, else the anonymous fallback. */
function rowTitle(family: SessionFamily): string {
  return view.value.names[family.id] ?? t(family.current ? 'sessions.thisDevice' : 'sessions.otherDevice')
}

/** The served wraps of this row's device — the escalation's handle, and empty wherever it must not be offered. */
function wrapsOf(family: SessionFamily): ReadonlyArray<string> {
  return view.value.wrapsOfFamily[family.id] ?? []
}

// --- rename (the device, not the session) ---

const renamingFamily = ref<string | null>(null)
const nameDraft = ref('')

function startRename(family: SessionFamily): void {
  renamingFamily.value = family.id
  nameDraft.value = view.value.names[family.id] ?? ''
}

const { run: runRename, pending: renameBusy } = useAsyncAction(
  async (familyId: string, label: string) => {
    await renameDeviceOfFamily(familyId, label)
    view.value = await readRegisterView(
      families.value.map((f) => f.id),
      savedWraps.value?.map((w) => w.id) ?? null,
      deviceId(),
    )
    renamingFamily.value = null
    toast.success(t('sessions.renamed'))
  },
  { onError: (e) => { on401(e); actionError.value = t('sessions.renameFailed') } },
)

async function saveRename(): Promise<void> {
  const familyId = renamingFamily.value
  const label = nameDraft.value.trim()
  if (!familyId || !label) return
  actionError.value = ''
  await runRename(familyId, label)
}

// Offered only when there is something else to end — the button is honest about being a no-op otherwise.
const hasOthers = computed(() => families.value.some((f) => !f.current))

const dateFormat = computed(
  () => new Intl.DateTimeFormat(locale.value === 'de' ? 'de-DE' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }),
)

/** A coarse timestamp, or an em dash — the server always sends both, so absence is a shape change, not a state. */
function when(value: Date | undefined): string {
  return value ? dateFormat.value.format(value) : '—'
}

/**
 * The handle a nameless session goes by. The full id is opaque and long, so the row shows its head and carries
 * the whole thing in a `title`: without a name it is the only thing that tells two same-day logins apart.
 */
function shortId(id: string): string {
  return id.slice(0, 8)
}

const { run: runRevoke, pending: busy } = useAsyncAction(
  async (family: SessionFamily) => {
    await revokeSessionFamily(family.id)
    // E2 — revoking the family you are sitting on means "sign me out here too", so the response is treated as a
    // logout rather than left to the next request to discover with dead tokens.
    if (family.current) return void (await signOut(router))
    toast.success(t('sessions.revoked'))
    await reload()
  },
  { onError: (e) => { on401(e); actionError.value = t('sessions.revokeFailed') } },
)

const { run: runRevokeOthers, pending: busyOthers } = useAsyncAction(
  async () => {
    await revokeOtherSessionFamilies()
    toast.success(t('sessions.revokedOthers'))
    await reload()
  },
  { onError: (e) => { on401(e); actionError.value = t('sessions.revokeFailed') } },
)

async function revoke(family: SessionFamily) {
  const question = family.current ? t('sessions.revokeSelfConfirm') : t('sessions.revokeConfirm')
  if (!window.confirm(question)) return
  actionError.value = ''
  await runRevoke(family)
}

async function revokeOthers() {
  if (!window.confirm(t('sessions.revokeOthersConfirm'))) return
  actionError.value = ''
  await runRevokeOthers()
}

// --- ADR 0058's escalation: sign out AND remove that device's saved key ---

/**
 * Family first, then wrap. The revoke is what stops an attacker who is online now, so it must not be blocked by
 * the wrap deletion; the wrap only matters at a future sign-in. If the revoke throws, `onError` reports it and
 * the wrap is never touched — the reverse half-failure cannot occur.
 *
 * The second half is **not** allowed to look like the first succeeded. §E5: on failure the user is told exactly
 * what happened, and because the register prunes a join row only from a later read of the server's wrap list
 * (never from a delete we merely attempted), the device reappears under § Devices with a saved key with the
 * removal still to do.
 */
const { run: runEscalate, pending: escalating } = useAsyncAction(
  async (family: SessionFamily) => {
    const wrapIds = wrapsOf(family)
    await revokeSessionFamily(family.id)
    try {
      for (const id of wrapIds) await removeSavedKey(id)
      toast.success(t('sessions.escalated'))
    } catch {
      actionError.value = t('sessions.escalateHalfFailed')
    }
    await reload()
  },
  { onError: (e) => { on401(e); actionError.value = t('sessions.revokeFailed') } },
)

async function escalate(family: SessionFamily) {
  if (!window.confirm(t('sessions.escalateConfirm'))) return
  actionError.value = ''
  await runEscalate(family)
}

// --- § Devices with a saved key: the retry surface, and the one handle on a wrap the register never knew ---

/**
 * The saved keys this client can act on: every device-local wrap the server serves, minus **this** device's own.
 *
 * Defined by **exclusion of what this client can verify locally**, never by a claim about the others (ADR 0058
 * §5). The wrap table has no timestamps, so nothing here can tell a genuine orphan from a device that simply has
 * not signed in since the join shipped — a "wraps no device recognises" list would be a guess wearing the
 * server's authority, and must not be offered. `myWrapId` comes from this device's local trust/remember record;
 * when it is null the exclusion subtracts nothing, which is correct — a device holding no saved key has none to
 * spare.
 */
const otherSavedKeys = computed(() => (savedWraps.value ?? []).filter((w) => w.id !== myWrapId.value))

/** The section is worth showing exactly when there is a saved key here that is not this device's own. */
const hasOtherSavedKeys = computed(() => otherSavedKeys.value.length > 0)

const { run: runRemoveKey, pending: removingKey } = useAsyncAction(
  async (device: SavedKeyDevice) => {
    for (const id of device.wrapIds) await removeSavedKey(id)
    toast.success(t('sessions.keyRemoved'))
    await reload()
  },
  { onError: (e) => { on401(e); actionError.value = t('sessions.keyRemoveFailed') } },
)

async function removeKeyOf(device: SavedKeyDevice) {
  if (!window.confirm(t('sessions.removeKeyConfirm', { device: device.label }))) return
  actionError.value = ''
  await runRemoveKey(device)
}

const { run: runRemoveOtherKeys, pending: removingOthers } = useAsyncAction(
  async () => {
    // The client filters to the device methods for honesty; the SERVER's whitelist (DEVICE / DEVICE_KEY) is the
    // guarantee, and the floor wrap is not removable through this endpoint at all.
    for (const wrap of otherSavedKeys.value) await removeSavedKey(wrap.id)
    toast.success(t('sessions.otherKeysRemoved'))
    await reload()
  },
  { onError: (e) => { on401(e); actionError.value = t('sessions.keyRemoveFailed') } },
)

async function removeOtherKeys() {
  if (!window.confirm(t('sessions.removeOtherKeysConfirm'))) return
  actionError.value = ''
  await runRemoveOtherKeys()
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('sessions.title') }}</h2>
          <p>{{ t('sessions.sub') }}</p>
        </div>
        <Button
          v-if="hasOthers"
          :label="t('sessions.revokeOthers')"
          icon="pi pi-sign-out"
          severity="danger"
          outlined
          size="small"
          :loading="busyOthers"
          @click="revokeOthers"
        />
      </div>

      <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>

      <p v-if="loading" class="settings-muted">{{ t('sessions.loading') }}</p>
      <Message v-else-if="loadError === 'failed'" severity="error" :closable="false">
        {{ t('sessions.loadFailed') }}
      </Message>
      <p v-else-if="families.length === 0" class="settings-muted">{{ t('sessions.empty') }}</p>

      <div v-else class="veyq-list management-list">
        <div class="veyq-list management-list-items">
          <div v-for="family in families" :key="family.id" class="veyq-list-row session-row">
            <div class="veyq-row-main">
              <template v-if="renamingFamily === family.id">
                <InputText
                  v-model="nameDraft"
                  class="session-name-input"
                  autocomplete="off"
                  :placeholder="t('sessions.renamePlaceholder')"
                  @keyup.enter="saveRename"
                />
                <span class="veyq-row-subtitle">{{ t('sessions.renameHint') }}</span>
              </template>
              <strong v-else class="veyq-row-title">{{ rowTitle(family) }}</strong>
              <span class="veyq-row-subtitle">
                {{ t('sessions.lastUsed', { when: when(family.lastUsedAt) }) }} ·
                {{ t('sessions.signedIn', { when: when(family.createdAt) }) }}
              </span>
              <div class="veyq-row-meta">
                <span v-if="family.current" class="veyq-badge success">{{ t('sessions.currentBadge') }}</span>
                <span class="veyq-badge session-id" :title="family.id">{{ shortId(family.id) }}</span>
              </div>
              <!-- The one row that can say how THIS device unlocks, so it is the one place that says it. The
                   component reads its own status on mount and is never awaited here: a hanging or throwing
                   device-local read costs this line, never the list or its revokes. -->
              <DeviceTrustPanel v-if="family.current && renamingFamily !== family.id" />
            </div>
            <div class="management-actions">
              <template v-if="renamingFamily === family.id">
                <Button :label="t('common.save')" icon="pi pi-check" size="small" :loading="renameBusy" @click="saveRename" />
                <Button
                  :label="t('common.cancel')"
                  severity="secondary"
                  outlined
                  size="small"
                  :disabled="renameBusy"
                  @click="renamingFamily = null"
                />
              </template>
              <!-- Renaming needs the register open, and there must be a device to rename: a family the register
                   cannot name has none. Its absence is a naming gap, never a reason to hide the revoke. -->
              <Button
                v-else-if="view.names[family.id]"
                icon="pi pi-pencil"
                text
                size="small"
                :aria-label="t('sessions.rename')"
                :title="t('sessions.rename')"
                @click="startRename(family)"
              />
              <!-- Never disabled by the vault: this is the action the screen exists for, and a locked device is
                   exactly the device that needs it. -->
              <Button
                v-if="renamingFamily !== family.id"
                icon="pi pi-sign-out"
                severity="danger"
                outlined
                size="small"
                :loading="busy"
                :aria-label="family.current ? t('sessions.revokeSelf') : t('sessions.revoke')"
                :title="family.current ? t('sessions.revokeSelf') : t('sessions.revoke')"
                @click="revoke(family)"
              />
              <!-- ADR 0058's escalation, offered ONLY where the register names a served wrap for that device —
                   never as a checkbox on the revoke above, and separately named because the consequence lands on
                   whoever is holding that device. A locked panel reaches this never: the join is sealed. -->
              <Button
                v-if="renamingFamily !== family.id && wrapsOf(family).length > 0"
                class="session-escalate"
                icon="pi pi-shield"
                severity="danger"
                size="small"
                :loading="escalating"
                :aria-label="t('sessions.escalate')"
                :title="t('sessions.escalate')"
                @click="escalate(family)"
              />
            </div>
          </div>
        </div>
      </div>

      <p class="settings-muted">{{ t('sessions.hint') }}</p>
    </section>

    <!-- § Devices with a saved key. Derived from the two lists this view already holds, so it invents nothing:
         the register's own device↔wrap join and the server's family list. It is the retry surface for an
         escalation whose wrap deletion failed (§E5), and the only place a device that is signed out but still
         password-unlockable becomes visible at all. -->
    <section v-if="hasOtherSavedKeys" class="settings-form saved-keys">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('sessions.savedKeysTitle') }}</h2>
          <p>{{ t('sessions.savedKeysSub') }}</p>
        </div>
        <!-- Defined by EXCLUDING this device's own saved key, never by a claim about the others (ADR 0058 §5).
             Its consequence is in the confirmation, not in a tooltip. -->
        <Button
          :label="t('sessions.removeOtherKeys')"
          icon="pi pi-key"
          severity="danger"
          outlined
          size="small"
          :loading="removingOthers"
          @click="removeOtherKeys"
        />
      </div>

      <div v-if="view.savedKeyDevices.length" class="veyq-list management-list">
        <div class="veyq-list management-list-items">
          <div v-for="device in view.savedKeyDevices" :key="device.deviceId" class="veyq-list-row saved-key-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">{{ device.label }}</strong>
              <span class="veyq-row-subtitle">{{ t('sessions.savedKeyRowHint') }}</span>
            </div>
            <div class="management-actions">
              <Button
                icon="pi pi-key"
                severity="danger"
                outlined
                size="small"
                :loading="removingKey"
                :aria-label="t('sessions.removeKey')"
                :title="t('sessions.removeKey')"
                @click="removeKeyOf(device)"
              />
            </div>
          </div>
        </div>
      </div>

      <p class="settings-muted">{{ t('sessions.savedKeysHint') }}</p>
    </section>
  </section>
</template>

<style scoped>
.session-id {
  font-family: var(--app-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}
.session-name-input {
  max-width: 22rem;
}
</style>
