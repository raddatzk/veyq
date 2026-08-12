<script setup lang="ts">
// uc.account.sessions (web, cr-account-sessions-list-and-revoke) — the "Sessions" rubric of the settings hub.
// Every place the account is currently signed in, and the remote sign-out that logout cannot do: logout only
// ends the family it is presented from, so a laptop left behind stays signed in until this screen exists.
//
// **Nothing here waits on the vault.** A session is auth, not unlock (ADR 0003), so listing and revoking must
// work on a locked device — that is the whole point: cutting off a lost device is exactly what you do before
// you can unlock anywhere else. Names ("Kevin's iPhone") are the only vault-dependent half: they come from the
// Master-Key-sealed device register (en.account.device-register) and are layered on **after** the list renders,
// never awaited by it. A locked device therefore still shows coarse dates and a short id, which is a complete
// and fully actionable list. The trap this panel must never fall into is disabling the action it exists to offer.
//
// This panel is also the register's **repair** surface (write moment 2): it is the only place holding the
// server's authoritative family list, so it reconciles the register — prunes families the server no longer
// lists, and records the current family when the unlock-time write could not (a cold boot restore never learned
// its own family id). That is what makes a failed write a supported state rather than an error path (§E4).
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
import { deviceNames, reconcileWithSessions, renameDeviceOfFamily } from '../deviceRegister'
import { deviceId, guessDeviceLabel } from '../deviceIdentity'

const { t, locale } = useI18n()
const router = useRouter()
const toast = useToast()
const on401 = handle401(router)

const families = ref<SessionFamily[]>([])
const actionError = ref('')
/** familyId → device label. Empty while locked, and empty for any family the register cannot name (§E4). */
const names = ref<Record<string, string>>({})

const { pending: loading, error: loadErr, reload } = useAsyncData(async () => {
  families.value = await listSessionFamilies()
  void loadNames()
}, { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

/**
 * Reconcile the register against the list we are holding, then read the labels back.
 *
 * Deliberately **not awaited** by the loader and deliberately silent on failure: the names are the cosmetic
 * half, and a register that is missing, stale or unwritable must leave the list rendering and every revoke
 * working. Skipped entirely while locked — the register is sealed under the Master Key, so there is nothing to
 * read and nothing to write.
 */
async function loadNames(): Promise<void> {
  if (!vaultState.unlocked) return void (names.value = {})
  try {
    await reconcileWithSessions(
      families.value.map((f) => f.id),
      deviceId(),
      guessDeviceLabel(t('sessions.unknownDevice')),
      families.value.find((f) => f.current)?.id ?? null,
    )
    names.value = await deviceNames()
  } catch {
    /* unnamed rows are a supported state — never an error the user must act on */
  }
}

/** The row's heading: the device's name when the register knows it, else the anonymous fallback. */
function rowTitle(family: SessionFamily): string {
  return names.value[family.id] ?? t(family.current ? 'sessions.thisDevice' : 'sessions.otherDevice')
}

// --- rename (the device, not the session) ---

const renamingFamily = ref<string | null>(null)
const nameDraft = ref('')

function startRename(family: SessionFamily): void {
  renamingFamily.value = family.id
  nameDraft.value = names.value[family.id] ?? ''
}

const { run: runRename, pending: renameBusy } = useAsyncAction(
  async (familyId: string, label: string) => {
    await renameDeviceOfFamily(familyId, label)
    names.value = await deviceNames()
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
                v-else-if="names[family.id]"
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
            </div>
          </div>
        </div>
      </div>

      <p class="settings-muted">{{ t('sessions.hint') }}</p>
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
