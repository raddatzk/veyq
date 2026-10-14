<script setup lang="ts">
// uc.admin.setup-server — first-run bootstrap, and the reason this whole surface can be reached before any
// DNS exists (ADR 0065). It performs a real cold account birth (identity keypair, Master Key wraps, Emergency
// Kit) through @veyq/crypto, the one module shared with `web/`: this is the most privileged single action an
// instance ever takes, so it must not run against a second copy of that code.
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { registerServerOwner } from '../features/setup/setupServer'
import EmergencyKitSave from '../features/setup/EmergencyKitSave.vue'
import { useBreachCheck } from '../features/account/passwordWarning'
import PasswordBreachNotice from '../features/account/PasswordBreachNotice.vue'
import { MIN_PASSWORD_LENGTH, passwordHintKey } from '../shared/passwordPolicy'
import { setAuthPrefill } from '../app/authPrefill'
import { useServerState } from '../shared/state/useServerState'
import { useAsyncAction } from '../shared/state/useAsyncAction'

const router = useRouter()
const { t } = useI18n()
const { setSetupCompleted } = useServerState()
const email = ref('')
const password = ref('')
const { state: breachState, useAnyway, checkNow: checkBreaches, proceed: breachCheckPasses } = useBreachCheck(password)
const error = ref('')
const secretKey = ref<string | null>(null)
const kitReady = ref(false)
let kitEmail = ''

const { run: runSubmit, pending: busy } = useAsyncAction(
  async () => {
    if (!(await breachCheckPasses())) return
    const { emergencyKit } = await registerServerOwner(email.value, password.value)
    kitEmail = email.value
    secretKey.value = emergencyKit.secretKey
    kitReady.value = false
    setSetupCompleted(true)
  },
  { onError: () => { error.value = t('setup.failed') } },
)

async function submit() {
  if (password.value.length < MIN_PASSWORD_LENGTH) return
  error.value = ''
  await runSubmit()
}

async function proceed() {
  if (!kitReady.value) return
  // web/'s version cleared this browser's device-trust and device-key wraps here, because there the setup page
  // shares an origin with an app a PREVIOUS account may have used. This origin serves nothing but the admin
  // surface and has never held a wrap of any kind — there is nothing stale to drop, so the clear is gone
  // rather than carried over as a no-op that reads like it does something.
  //
  // Hand the email over so the owner doesn't retype it on the sign-in card that follows.
  setAuthPrefill(kitEmail, secretKey.value!)
  void router.push({ name: 'login' })
}
</script>

<template>
  <Card class="login-card">
    <template #title>{{ secretKey ? t('setup.kitTitle') : t('setup.title') }}</template>
    <template #content>
      <template v-if="!secretKey">
        <p class="veyq-form-copy">{{ t('setup.sub') }}</p>
        <form class="veyq-form" @submit.prevent="submit">
          <label>
            {{ t('common.email') }}
            <InputText v-model="email" type="email" autocomplete="username" required autofocus />
          </label>
          <label>
            {{ t('common.password') }}
            <Password
              v-model="password"
              input-id="setup-password"
              :input-props="{ autocomplete: 'new-password' }"
              required
              :feedback="false"
              toggle-mask
              fluid
              @blur="checkBreaches"
            />
          </label>
          <small v-if="passwordHintKey(password)" class="field-hint">{{ t(passwordHintKey(password)!) }}</small>
          <PasswordBreachNotice v-model:use-anyway="useAnyway" :state="breachState" id-prefix="setup" :disabled="busy" />
          <Button
            type="submit"
            :label="busy ? t('setup.submitting') : t('setup.submit')"
            icon="pi pi-server"
            :loading="busy"
            :disabled="password.length < MIN_PASSWORD_LENGTH"
          />
        </form>
      </template>

      <template v-else>
        <EmergencyKitSave :email="kitEmail" :secret-key="secretKey!" v-model="kitReady">
          <p class="veyq-form-copy">{{ t('setup.kitSub') }}</p>
        </EmergencyKitSave>
        <Button
          type="button"
          :label="t('setup.kitProceed')"
          icon="pi pi-arrow-right"
          :disabled="!kitReady"
          @click="proceed"
        />
      </template>

      <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
    </template>
  </Card>
</template>
