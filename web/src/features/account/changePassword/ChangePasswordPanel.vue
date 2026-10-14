<script setup lang="ts">
// The "Change password" section of the settings hub — uc.account.change-password. One screen, ONE entry
// point: the current password and the Secret Key, both required, both proven against the stored floor before
// anything is sent (ADR 0060 Decision 2 — this screen mints a cold credential). The "I don't remember my
// current password" option that used to stand here was the bypass of exactly that gate; what replaces it is a
// link to recovery, which proves address control instead of waiving the proof.
//
// Markup + classes mirror KeyRotationPanel (veyq-surface + settings-form + settings-section-header) — the
// built panel is the design source.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import SecretKeyField from '../../../shared/ui/SecretKeyField.vue'
import Password from 'primevue/password'
import Message from 'primevue/message'
import { changePassword, ChangePasswordRefused } from './changePassword'
import { enrolDeviceKey, isRememberSupported } from '../rememberMe/deviceKeyWrap'
import { masterKey } from '../../../app/vault'
import { account } from '../../../app/account'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import { useBreachCheck } from '../passwordWarning'
import PasswordBreachNotice from '../PasswordBreachNotice.vue'
import { MIN_PASSWORD_LENGTH, passwordHintKey } from '../../../shared/passwordPolicy'
import { useCapabilities } from '../../../shared/state/useCapabilities'
import { ACCOUNT_DEVICE_TRUST } from '../../platform/capabilities'
import { isHttpStatus, HttpStatus } from '../../../shared/httpError'

const { t } = useI18n()
const { hasCapability } = useCapabilities()

const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const secretKey = ref('')
const error = ref('')
const done = ref(false)

const { state: breachState, useAnyway, checkNow: checkBreaches, proceed: breachCheckPasses } = useBreachCheck(newPassword)

const rememberSupported = isRememberSupported() && hasCapability(ACCOUNT_DEVICE_TRUST)
// The length floor is a CLIENT-side floor and cannot be anything else: under Model B (ADR 0003) the password
// never leaves this device — the server receives an Ed25519 public key derived from it and stores no
// credential at all — so there is no server-side rule this could mirror and none that would catch a bypass.
// It is a courtesy to the person choosing, not a control on the account, and this comment exists so nobody
// later "hardens" it by adding a check the operator-blind boundary forbids.
const canSubmit = computed(
  () =>
    currentPassword.value.length > 0 &&
    newPassword.value.length >= MIN_PASSWORD_LENGTH &&
    secretKey.value.trim().length > 0,
)

const { run, pending: busy } = useAsyncAction(
  async () => {
    if (!(await breachCheckPasses())) return
    await changePassword(newPassword.value, secretKey.value.trim(), currentPassword.value)
    currentPassword.value = ''
    secretKey.value = ''
    confirmPassword.value = ''
    done.value = true
  },
  {
    onError: (e) => {
      // The refusals this screen can produce WITHOUT a request are the shared proof's three. `errorNoFloor`
      // serves both it and the server's 409: one account state, one sentence.
      error.value =
        e instanceof ChangePasswordRefused
          ? e.reason === 'vaultLocked'
            ? t('changePassword.errorLocked')
            : e.reason === 'noFloorWrap'
              ? t('changePassword.errorNoFloor')
              : t('changePassword.errorCredential')
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
  // Checked here as well as on the button. A disabled submit button is a hint, not a gate: the form is
  // submitted by Enter as readily as by a click, and this handler is the only place both paths pass through.
  // Caught by the test below, which submits the form directly and found `changePassword` called with a
  // five-character password while the button sat correctly disabled.
  if (newPassword.value.length < MIN_PASSWORD_LENGTH) return
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
          <label class="settings-field">
            {{ t('changePassword.currentPasswordLabel') }}
            <Password v-model="currentPassword" :feedback="false" toggle-mask autocomplete="current-password" :disabled="busy" />
          </label>
          <RouterLink class="settings-link-action" to="/recover">{{ t('changePassword.forgotLink') }}</RouterLink>

          <label class="settings-field">
            {{ t('changePassword.newPasswordLabel') }}
            <Password
              v-model="newPassword"
              :feedback="false"
              toggle-mask
              autocomplete="new-password"
              :disabled="busy"
              input-id="change-password-new"
              @blur="checkBreaches"
            />
            <small v-if="passwordHintKey(newPassword)" class="field-hint">{{ t(passwordHintKey(newPassword)!) }}</small>
          </label>
          <PasswordBreachNotice v-model:use-anyway="useAnyway" :state="breachState" id-prefix="change-password" :disabled="busy" />
          <label class="settings-field">
            {{ t('changePassword.confirmPasswordLabel') }}
            <Password v-model="confirmPassword" :feedback="false" toggle-mask autocomplete="new-password" :disabled="busy" input-id="change-password-confirm" />
          </label>
          <label class="settings-field">
            {{ t('changePassword.secretKeyLabel') }}
            <SecretKeyField v-model="secretKey" :expected-email="account.email" :disabled="busy" />
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
