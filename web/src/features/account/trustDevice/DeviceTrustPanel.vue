<script setup lang="ts">
// This device's unlock tier + its forget action (uc.account.trust-device § Untrust) — mounted by the SESSIONS
// panel's own row for the current device, not by the settings hub. There is one rubric about a device now
// ("Devices and sessions"), and the fact "this device holds a saved key" is stated there once.
//
// Still a component of its own, and deliberately so: everything below is device-LOCAL (IndexedDB trust record,
// IndexedDB device key, the DEVICE-wrap endpoints) and knows nothing about session families. Keeping it out of
// the sessions code is what lets the row mount it **beside** its data rather than inside its loader — the
// status reads are never awaited by the list (see SessionsPanel's header: the panel must never disable the
// action it exists to offer).
//
// Operator-blind: reuses the already-built trustDevice logic, never re-encrypts content, and (endpoint
// whitelist) can only ever touch DEVICE wraps, never the 2SKD floor or a recovery path.
//
// **This is not the escalation, and must never be collapsed into it** (ADR 0058). Forgetting reaches this
// device's LOCAL key as well as its server wrap and leaves the session alone; the row's neighbouring
// `sessions.escalate` ends a session and can only ever reach the server's wrap. The server cannot reach a
// remote device's local key — that asymmetry is what forces two names, two confirms and two severities.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Message from 'primevue/message'
import { isTrusted, untrustThisDevice, isTrustSupported } from './trustDevice'
import { isRemembered, clearDeviceKey } from '../rememberMe/deviceKeyWrap'
import { account } from '../../../app/account'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'

const { t } = useI18n()

// A device holds at most one convenience tier (login drops the other first): 'remembered' = the password-less
// DEVICE_KEY wrap ("stay signed in"), 'trusted' = the password-only DEVICE wrap ("trust this device"), or 'none'
// = the PASSWORD floor only (Secret Key needed here). Forgetting drops whichever this device has.
type DeviceState = 'remembered' | 'trusted' | 'none'

const supported = isTrustSupported()
const state = ref<DeviceState>('none')
const confirmOpen = ref(false)
const error = ref('')

const hasConvenience = computed(() => state.value !== 'none')

// Reflect this device's local unlock tier for the signed-in account. Remember-me (the stronger, password-less
// tier) wins if both somehow exist. Best-effort: any read error reads as 'none' (the safe default — the forget
// action is only offered when we positively know a convenience wrap is here).
async function refresh() {
  if (!account.email) { state.value = 'none'; return }
  if (await isRemembered().catch(() => false)) { state.value = 'remembered'; return }
  state.value = (await isTrusted(account.email).catch(() => false)) ? 'trusted' : 'none'
}
// Fired here and never returned to the parent: the row renders complete without it, and a read that hangs or
// throws costs this line its status — never the list its revokes.
onMounted(refresh)

const { run: runUntrust, pending: busy } = useAsyncAction(
  async () => {
    // Drop BOTH convenience tiers — each is an idempotent no-op when absent, so this forgets the device
    // regardless of which tier it held.
    await untrustThisDevice(account.email)
    await clearDeviceKey()
    await refresh()
    confirmOpen.value = false
  },
  { onError: () => { error.value = t('sessions.untrustFailed') } },
)

async function confirmUntrust() {
  error.value = ''
  await runUntrust()
}
</script>

<template>
  <div class="device-trust">
    <p class="device-status">
      <i
        class="pi"
        :class="!supported ? 'pi-info-circle' : hasConvenience ? 'pi-shield' : 'pi-lock'"
        aria-hidden="true"
      />
      <span>{{
        !supported
          ? t('sessions.deviceUnsupported')
          : state === 'remembered'
            ? t('sessions.deviceRemembered')
            : state === 'trusted'
              ? t('sessions.deviceTrusted')
              : t('sessions.deviceUntrusted')
      }}</span>
    </p>

    <!-- Offered only where a convenience tier is positively known to be here — the catch-path default is
         'none', so a failed read offers nothing rather than an action with no subject. -->
    <div v-if="supported && hasConvenience" class="device-action">
      <Button
        type="button"
        :label="t('unlock.untrust')"
        icon="pi pi-lock"
        severity="danger"
        outlined
        size="small"
        @click="confirmOpen = true"
      />
    </div>

    <!-- A dialog, where the neighbouring session actions use window.confirm: the two are not the same act and
         must not look like it. -->
    <Dialog
      v-model:visible="confirmOpen"
      :header="t('sessions.untrustConfirmTitle')"
      modal
      :closable="!busy"
      :close-on-escape="!busy"
    >
      <p class="dialog-copy">{{ t('sessions.untrustConfirmBody') }}</p>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" icon="pi pi-times" severity="secondary" outlined :disabled="busy" @click="confirmOpen = false" />
        <Button type="button" :label="t('unlock.untrust')" icon="pi pi-lock" severity="danger" :loading="busy" @click="confirmUntrust" />
      </div>
    </Dialog>
  </div>
</template>

<style scoped>
.device-trust {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.5rem 0.75rem;
  margin-top: 0.35rem;
}
.device-status {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin: 0;
  color: var(--app-muted);
  font-size: 0.9rem;
}
.device-status .pi {
  color: var(--app-muted);
}
.device-action {
  display: flex;
}
.dialog-copy {
  margin: 0 0 0.75rem;
}
</style>
