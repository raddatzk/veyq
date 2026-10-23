<script setup lang="ts">
// uc.admin.setup-server — first-run bootstrap, and the reason this whole surface can be reached before any
// DNS exists (ADR 0065). It performs a real cold account birth (identity keypair, Master Key wraps, Emergency
// Kit) through @veyq/crypto, the one module shared with `src/web/`: this is the most privileged single action an
// instance ever takes, so it must not run against a second copy of that code.
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../shared/ui/VeyqButton.vue'
import VeyqCard from '../shared/ui/VeyqCard.vue'
import VeyqInput from '../shared/ui/VeyqInput.vue'
import VeyqMessage from '../shared/ui/VeyqMessage.vue'
import VeyqPassword from '../shared/ui/VeyqPassword.vue'
import { createServerOwner, prepareServerOwner, type PreparedServerOwner } from '../features/setup/setupServer'
import OperatorDuties from '../features/instance/OperatorDuties.vue'
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
// The account as minted on this device, not yet sent: it is posted only once the kit is confirmed (E1).
let prepared: PreparedServerOwner | null = null
// Step 4, after the kit and before the post: what a Server Owner may owe (ADR 0093 § 14), as information — nothing is
// chosen or ticked, and every value stays optional, set later under Server → Operator.
const informing = ref(false)

const { run: runSubmit, pending: busy } = useAsyncAction(
  async () => {
    if (!(await breachCheckPasses())) return
    const { request, emergencyKit } = await prepareServerOwner(email.value, password.value)
    prepared = request
    kitEmail = email.value
    secretKey.value = emergencyKit.secretKey
    kitReady.value = false
    informing.value = false
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
    await createServerOwner(prepared!)
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

function toDuties() {
  if (!kitReady.value || !prepared) return
  error.value = ''
  informing.value = true
}

async function proceed() {
  if (!kitReady.value || !prepared) return
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
  <VeyqCard class="login-card">
    <template #title>{{ informing ? t('setup.dutiesTitle') : secretKey ? t('setup.kitTitle') : t('setup.title') }}</template>
    <template #default>
      <template v-if="!secretKey">
        <p class="veyq-form-copy">{{ t('setup.sub') }}</p>
        <form class="veyq-form" @submit.prevent="submit">
          <label>
            {{ t('common.email') }}
            <VeyqInput v-model="email" type="email" autocomplete="username" required autofocus />
          </label>
          <label>
            {{ t('common.password') }}
            <VeyqPassword
              v-model="password"
              input-id="setup-password"
              autocomplete="new-password"
              required
              fluid
              @blur="checkBreaches"
            />
          </label>
          <small v-if="passwordHintKey(password)" class="field-hint">{{ t(passwordHintKey(password)!) }}</small>
          <PasswordBreachNotice v-model:use-anyway="useAnyway" :state="breachState" id-prefix="setup" :disabled="busy" />
          <VeyqButton
            type="submit"
            :label="busy ? t('setup.submitting') : t('setup.submit')"
            icon="server"
            :loading="busy"
            :disabled="password.length < MIN_PASSWORD_LENGTH"
          />
        </form>
      </template>

      <template v-else-if="!informing">
        <EmergencyKitSave :email="kitEmail" :secret-key="secretKey!" v-model="kitReady">
          <p class="veyq-form-copy">{{ t('setup.kitSub') }}</p>
        </EmergencyKitSave>
        <VeyqButton
          type="button"
          class="setup-kit-continue"
          :label="t('setup.submit')"
          icon="arrow-right"
          :disabled="!kitReady"
          @click="toDuties"
        />
      </template>

      <template v-else>
        <OperatorDuties />
        <VeyqButton
          type="button"
          class="setup-create"
          :label="creating ? t('setup.creating') : t('setup.kitProceed')"
          icon="arrow-right"
          :loading="creating"
          :disabled="creating"
          @click="proceed"
        />
      </template>

      <VeyqMessage v-if="error" tone="error" class="form-message">{{ error }}</VeyqMessage>
    </template>
  </VeyqCard>
</template>
