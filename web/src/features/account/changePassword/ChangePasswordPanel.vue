<script setup lang="ts">
// The "Change password" section of the settings hub — uc.account.change-password. One screen, two entry
// points: deliberate (the caller still knows the current password, default) and forgotten (reached only from
// an already-unlocked session — most often a DEVICE_KEY "remember me" wrap). The client decides which form is
// offered; the server cannot and does not try (ADR 0060 Decision 2's precedent, mirrored from rotation).
//
// Markup + classes mirror KeyRotationPanel (veyq-surface + settings-form + settings-section-header) — the
// built panel is the design source.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Message from 'primevue/message'
import { changePassword, ChangePasswordRefused } from './changePassword'
import { enrolDeviceKey, isRememberSupported } from '../rememberMe/deviceKeyWrap'
import { masterKey } from '../../../app/vault'
import { account } from '../../../app/account'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import { useCapabilities } from '../../../shared/state/useCapabilities'
import { ACCOUNT_DEVICE_TRUST } from '../../platform/capabilities'
import { isHttpStatus, HttpStatus } from '../../../shared/httpError'

const { t } = useI18n()
const { hasCapability } = useCapabilities()

const forgotten = ref(false)
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const secretKey = ref('')
const error = ref('')
const done = ref(false)

const rememberSupported = isRememberSupported() && hasCapability(ACCOUNT_DEVICE_TRUST)
const canSubmit = computed(
  () =>
    (forgotten.value || currentPassword.value.length > 0) &&
    newPassword.value.length > 0 &&
    secretKey.value.trim().length > 0,
)

const { run, pending: busy } = useAsyncAction(
  async () => {
    await changePassword(newPassword.value, secretKey.value.trim(), forgotten.value ? undefined : currentPassword.value)
    currentPassword.value = ''
    secretKey.value = ''
    confirmPassword.value = ''
    done.value = true
  },
  {
    onError: (e) => {
      error.value =
        e instanceof ChangePasswordRefused
          ? t('changePassword.errorLocked')
          : isHttpStatus(e, HttpStatus.forbidden)
            ? t('changePassword.errorAttestation')
            : isHttpStatus(e, HttpStatus.conflict)
              ? t('changePassword.errorNoFloor')
              : t('changePassword.errorFailed')
    },
  },
)

async function submit() {
  error.value = ''
  if (newPassword.value !== confirmPassword.value) {
    error.value = t('changePassword.mismatch')
    return
  }
  await run()
}

// The re-enrol offer (§ the CR's own "offer to re-enrol this device"): password-less, so it needs no re-typed
// secret — the just-changed Master Key is unchanged (only its wrap is), so the vault's copy is still good.
const { run: runRemember, pending: rememberBusy } = useAsyncAction(async () => {
  const mk = masterKey()
  if (!mk) return
  try {
    await enrolDeviceKey(account.email, mk)
  } finally {
    mk.fill(0)
  }
})

function startOver() {
  done.value = false
  forgotten.value = false
  newPassword.value = ''
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('changePassword.title') }}</h2>
          <p>{{ t('changePassword.sub') }}</p>
        </div>
      </div>

      <template v-if="!done">
        <form class="veyq-form" @submit.prevent="submit">
          <label v-if="!forgotten" class="settings-field">
            {{ t('changePassword.currentPasswordLabel') }}
            <Password v-model="currentPassword" :feedback="false" toggle-mask autocomplete="current-password" :disabled="busy" />
          </label>
          <button
            v-if="!forgotten"
            type="button"
            class="settings-link-action"
            :disabled="busy"
            @click="forgotten = true; currentPassword = ''"
          >
            {{ t('changePassword.forgotAction') }}
          </button>
          <Message v-else severity="info" :closable="false">{{ t('changePassword.forgottenNote') }}</Message>

          <label class="settings-field">
            {{ t('changePassword.newPasswordLabel') }}
            <Password v-model="newPassword" :feedback="false" toggle-mask autocomplete="new-password" :disabled="busy" input-id="change-password-new" />
          </label>
          <label class="settings-field">
            {{ t('changePassword.confirmPasswordLabel') }}
            <Password v-model="confirmPassword" :feedback="false" toggle-mask autocomplete="new-password" :disabled="busy" input-id="change-password-confirm" />
          </label>
          <label class="settings-field">
            {{ t('changePassword.secretKeyLabel') }}
            <InputText v-model="secretKey" autocomplete="off" spellcheck="false" :disabled="busy" />
            <small>{{ t('changePassword.secretKeyHint') }}</small>
          </label>

          <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

          <div class="settings-action-row">
            <Button
              type="submit"
              :label="busy ? t('changePassword.submitting') : t('changePassword.submit')"
              icon="pi pi-lock"
              :disabled="!canSubmit || busy"
              :loading="busy"
            />
            <button v-if="forgotten" type="button" class="settings-link-action" :disabled="busy" @click="forgotten = false">
              {{ t('changePassword.rememberAfterAll') }}
            </button>
          </div>
        </form>
      </template>

      <template v-else>
        <Message severity="success" :closable="false">{{ t('changePassword.done') }}</Message>
        <p class="settings-muted">{{ t('changePassword.doneNote') }}</p>

        <div v-if="rememberSupported" class="settings-action-row">
          <Button
            type="button"
            :label="t('changePassword.rememberDevice')"
            icon="pi pi-shield"
            severity="secondary"
            outlined
            :loading="rememberBusy"
            @click="runRemember"
          />
        </div>

        <button type="button" class="settings-link-action" @click="startOver">{{ t('changePassword.changeAgain') }}</button>
      </template>
    </section>
  </section>
</template>

<style scoped>
.settings-link-action {
  align-self: flex-start;
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--app-muted);
  font: inherit;
  font-size: 0.85rem;
  text-decoration: underline;
  cursor: pointer;
}
.settings-action-row {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-top: 0.5rem;
}
</style>
