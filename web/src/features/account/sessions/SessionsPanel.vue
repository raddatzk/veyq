<script setup lang="ts">
// uc.account.sessions (web, cr-account-sessions-list-and-revoke) — the "Sessions" rubric of the settings hub.
// Every place the account is currently signed in, and the remote sign-out that logout cannot do: logout only
// ends the family it is presented from, so a laptop left behind stays signed in until this screen exists.
//
// **Nothing here waits on the vault.** A session is auth, not unlock (ADR 0003), so listing and revoking must
// work on a locked device — that is the whole point: cutting off a lost device is exactly what you do before
// you can unlock anywhere else. Names ("Kevin's iPhone") are the only vault-dependent half and they are a later
// slice; until then a row shows coarse dates and a short id, which is a complete and fully actionable list.
// The trap this panel must never fall into is disabling the action it exists to offer.
//
// Markup + classes mirror TenantMembersPanel (settings-section-header + veyq-list-row + veyq-badge) — the built
// list panel is the design source. Confirmation is `window.confirm`, as on that panel's per-row destructive
// action.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
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

const { t, locale } = useI18n()
const router = useRouter()
const toast = useToast()
const on401 = handle401(router)

const families = ref<SessionFamily[]>([])
const actionError = ref('')

const { pending: loading, error: loadErr, reload } = useAsyncData(async () => {
  families.value = await listSessionFamilies()
}, { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value))

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
              <strong class="veyq-row-title">
                {{ family.current ? t('sessions.thisDevice') : t('sessions.otherDevice') }}
              </strong>
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
              <!-- Never disabled by the vault: this is the action the screen exists for, and a locked device is
                   exactly the device that needs it. -->
              <Button
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
</style>
