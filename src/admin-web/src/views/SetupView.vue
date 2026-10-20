<script setup lang="ts">
// uc.admin.setup-server — first-run bootstrap, and the reason this whole surface can be reached before any
// DNS exists (ADR 0065). It performs a real cold account birth (identity keypair, Master Key wraps, Emergency
// Kit) through @veyq/crypto, the one module shared with `src/web/`: this is the most privileged single action an
// instance ever takes, so it must not run against a second copy of that code.
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { createServerOwner, prepareServerOwner, type PreparedServerOwner } from '../features/setup/setupServer'
import OperatingModeChoice from '../features/instance/OperatingModeChoice.vue'
import { needsAcceptance, type OperatingMode } from '../features/instance/operator'
import { decodeHttpError } from '../shared/httpError'
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
// The account as minted on this device, not yet sent: it is posted only once the kit is confirmed (E1) and the
// operating mode chosen (step 4, ADR 0093 § 14).
let prepared: PreparedServerOwner | null = null
// Step 4: the mode, after the kit and before the post. No preselection: the notice is read before a mode is picked.
const choosingMode = ref(false)
const mode = ref<OperatingMode | null>(null)
const accepted = ref(false)
const modeReady = computed(() => mode.value !== null && (!needsAcceptance(mode.value) || accepted.value))

const { run: runSubmit, pending: busy } = useAsyncAction(
  async () => {
    if (!(await breachCheckPasses())) return
    const { request, emergencyKit } = await prepareServerOwner(email.value, password.value)
    prepared = request
    kitEmail = email.value
    secretKey.value = emergencyKit.secretKey
    kitReady.value = false
    choosingMode.value = false
  },
  { onError: () => { error.value = t('setup.failed') } },
)

async function submit() {
  if (password.value.length < MIN_PASSWORD_LENGTH) return
  error.value = ''
  await runSubmit()
}

// uc.admin.setup-server E1: the owner is created only now, with the kit confirmed — so a tab closed on the kit
// screen leaves setup open, not an owner nobody can unlock. A failed post keeps the kit: it is still this account's.
const { run: runCreate, pending: creating } = useAsyncAction(
  async () => {
    await createServerOwner(prepared!, mode.value!, accepted.value)
    setSetupCompleted(true)
    finish()
  },
  {
    onError: async (e) => {
      const { code } = await decodeHttpError(e)
      error.value = t(code === 'setup_already_complete' ? 'setup.alreadyComplete' : 'setup.createFailed')
    },
  },
)

function toMode() {
  if (!kitReady.value || !prepared) return
  error.value = ''
  choosingMode.value = true
}

async function proceed() {
  if (!kitReady.value || !prepared || !modeReady.value) return
  error.value = ''
  await runCreate()
}

function finish() {
  // src/web/'s version cleared this browser's device-trust and device-key wraps here, because there the setup page
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
    <template #title>{{ choosingMode ? t('setup.modeTitle') : secretKey ? t('setup.kitTitle') : t('setup.title') }}</template>
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

      <template v-else-if="!choosingMode">
        <EmergencyKitSave :email="kitEmail" :secret-key="secretKey!" v-model="kitReady">
          <p class="veyq-form-copy">{{ t('setup.kitSub') }}</p>
        </EmergencyKitSave>
        <Button
          type="button"
          class="setup-kit-continue"
          :label="t('setup.submit')"
          icon="pi pi-arrow-right"
          :disabled="!kitReady"
          @click="toMode"
        />
      </template>

      <template v-else>
        <OperatingModeChoice v-model:mode="mode" v-model:accepted="accepted" :disabled="creating" />
        <Button
          type="button"
          class="setup-create"
          :label="creating ? t('setup.creating') : t('setup.kitProceed')"
          icon="pi pi-arrow-right"
          :loading="creating"
          :disabled="!modeReady || creating"
          @click="proceed"
        />
      </template>

      <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
    </template>
  </Card>
</template>

<style scoped>
/* The same gap EmergencyKitSave leaves below its confirm row, so both steps end alike. */
.setup-create {
  margin-top: 1.25rem;
}
</style>
