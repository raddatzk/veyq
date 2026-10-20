<script setup lang="ts">
// uc.account.change-email (web) — "Change sign-in address", inside the address section (EmailAddressSection): the
// address shown there is the one this changes. Closed, it is one button; open, it is the password change's form
// shape (ChangePasswordPanel: `veyq-form`, `settings-field`, the one SecretKeyField) with the new address in place
// of the new password; sent, it is the "check your mailbox" state. Nothing changes on the account until the link
// mailed to the new address is opened (ConfirmEmailChangeView).
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import Message from 'primevue/message'
import SecretKeyField from '../../../shared/ui/SecretKeyField.vue'
import { requestEmailChange, requestErrorKey } from './emailChange'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'

const props = defineProps<{ currentEmail: string }>()
const { t } = useI18n()

type Phase = 'closed' | 'form' | 'sent'
const phase = ref<Phase>('closed')
const newEmail = ref('')
const password = ref('')
const secretKey = ref('')
const error = ref('')
const sentTo = ref('')

const normalized = (s: string) => s.trim().toLowerCase()
const canSubmit = computed(
  () => newEmail.value.trim().length > 0 && password.value.length > 0 && secretKey.value.trim().length > 0,
)

const { run, pending: busy } = useAsyncAction(
  async () => {
    await requestEmailChange(newEmail.value, password.value, secretKey.value.trim())
    sentTo.value = newEmail.value.trim()
    password.value = ''
    secretKey.value = ''
    phase.value = 'sent'
  },
  {
    onError: (e) => {
      error.value = t(requestErrorKey(e))
    },
  },
)

async function submit() {
  error.value = ''
  // Checked here as well as on the button: Enter submits the form whatever the button's state.
  if (!canSubmit.value) return
  if (normalized(newEmail.value) === normalized(props.currentEmail)) {
    error.value = t('emailChange.unchanged')
    return
  }
  await run()
}

function close() {
  phase.value = 'closed'
  newEmail.value = ''
  password.value = ''
  secretKey.value = ''
  error.value = ''
}
</script>

<template>
  <div class="change-email">
    <Button
      v-if="phase === 'closed'"
      :label="t('emailChange.action')"
      icon="pi pi-pencil"
      severity="secondary"
      outlined
      data-testid="change-email-open"
      @click="phase = 'form'"
    />

    <form v-else-if="phase === 'form'" class="veyq-form" data-testid="change-email-form" @submit.prevent="submit">
      <p class="settings-muted">{{ t('emailChange.formSub') }}</p>
      <label class="settings-field">
        {{ t('emailChange.newEmailLabel') }}
        <InputText v-model="newEmail" type="email" name="new-email" autocomplete="email" :disabled="busy" />
      </label>
      <label class="settings-field">
        {{ t('emailChange.passwordLabel') }}
        <Password v-model="password" :feedback="false" toggle-mask autocomplete="current-password" :disabled="busy" />
      </label>
      <label class="settings-field">
        {{ t('emailChange.secretKeyLabel') }}
        <SecretKeyField v-model="secretKey" :expected-email="currentEmail" :disabled="busy" />
        <small>{{ t('emailChange.secretKeyHint') }}</small>
      </label>

      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

      <div class="change-email-actions">
        <Button
          type="submit"
          :label="busy ? t('emailChange.submitting') : t('emailChange.submit')"
          icon="pi pi-envelope"
          :disabled="!canSubmit || busy"
          :loading="busy"
        />
        <Button type="button" :label="t('emailChange.cancel')" severity="secondary" text :disabled="busy" @click="close" />
      </div>
    </form>

    <template v-else>
      <Message severity="info" :closable="false" data-testid="change-email-sent">{{ t('emailChange.sent', { email: sentTo }) }}</Message>
    </template>
  </div>
</template>

<style scoped>
.change-email {
  margin-top: 1rem;
}
.change-email-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-top: 0.5rem;
}
</style>
