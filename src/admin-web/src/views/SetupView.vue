<script setup lang="ts">
// uc.admin.setup-server — first-run bootstrap, and the reason this whole surface can be reached before any
// DNS exists (ADR 0065). It performs a real cold account birth (identity keypair, Master Key wraps, Emergency
// Kit) through @veyq/crypto, the one module shared with `src/web/`: this is the most privileged single action an
// instance ever takes, so it must not run against a second copy of that code.
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../shared/ui/VeyqButton.vue'
import VeyqCheckbox from '../shared/ui/VeyqCheckbox.vue'
import VeyqCard from '../shared/ui/VeyqCard.vue'
import VeyqInput from '../shared/ui/VeyqInput.vue'
import VeyqMessage from '../shared/ui/VeyqMessage.vue'
import VeyqPassword from '../shared/ui/VeyqPassword.vue'
import {
  createServerOwner,
  listSetupApplications,
  prepareServerOwner,
  type PreparedServerOwner,
} from '../features/setup/setupServer'
import type { SetupApplication } from '../api/generated'
import { inLocale, type CapabilityLocale } from '../features/instance/capabilities'
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
const { t, locale } = useI18n()
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
// Step 5, after what may be owed and before the post: which applications this server offers (ADR 0088 decision 12),
// every one ticked. Their words are the register's, read before sign-in (decision 13); what is unticked rides the post.
const choosing = ref(false)
const applications = ref<SetupApplication[] | null>(null)
const offered = ref<Record<string, boolean>>({})
const applicationsOff = computed(() => (applications.value ?? []).filter((a) => !offered.value[a.id]).map((a) => a.id))
const noneOffered = computed(() => applications.value !== null && applicationsOff.value.length === applications.value.length)
const capabilitiesPage = computed(() => `${t('admin.server')} → ${t('capabilities.navTitle')}`)
const words = (text: SetupApplication['title']) => inLocale(text, locale.value as CapabilityLocale)

const { run: runSubmit, pending: busy } = useAsyncAction(
  async () => {
    if (!(await breachCheckPasses())) return
    const { request, emergencyKit } = await prepareServerOwner(email.value, password.value)
    prepared = request
    kitEmail = email.value
    secretKey.value = emergencyKit.secretKey
    kitReady.value = false
    informing.value = false
    choosing.value = false
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
    await createServerOwner(prepared!, applicationsOff.value)
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

const { run: runLoadApplications, pending: loadingApplications } = useAsyncAction(
  async () => {
    const listed = await listSetupApplications()
    offered.value = Object.fromEntries(listed.map((a) => [a.id, true]))
    applications.value = listed
  },
  {
    onError: async (e) => {
      const { code } = await decodeHttpError(e)
      error.value = t(code === 'setup_already_complete' ? 'setup.alreadyComplete' : 'setup.applicationsFailed')
    },
  },
)

async function toApplications() {
  if (!kitReady.value || !prepared) return
  error.value = ''
  choosing.value = true
  if (applications.value === null) await runLoadApplications()
}

async function retryApplications() {
  error.value = ''
  await runLoadApplications()
}

async function proceed() {
  if (!kitReady.value || !prepared || applications.value === null || noneOffered.value) return
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
    <template #title>
      {{ choosing ? t('setup.applicationsTitle') : informing ? t('setup.dutiesTitle') : secretKey ? t('setup.kitTitle') : t('setup.title') }}
    </template>
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

      <template v-else-if="!choosing">
        <OperatorDuties />
        <VeyqButton
          type="button"
          class="setup-duties-continue"
          :label="t('setup.submit')"
          icon="arrow-right"
          @click="toApplications"
        />
      </template>

      <template v-else>
        <p v-if="loadingApplications" class="veyq-form-copy">{{ t('setup.applicationsLoading') }}</p>
        <template v-else-if="applications">
          <ul class="setup-applications" data-test="setup-applications">
            <li v-for="app in applications" :key="app.id" class="setup-application">
              <label class="setup-application__choice">
                <VeyqCheckbox v-model="offered[app.id]" :input-id="`setup-app-${app.id}`" :disabled="creating" />
                <span class="setup-application__words">
                  <span class="setup-application__title">{{ words(app.title) }}</span>
                  <span class="setup-application__purpose">{{ words(app.purpose) }}</span>
                </span>
              </label>
            </li>
          </ul>
          <p class="setup-applications__later">{{ t('setup.applicationsLater', { page: capabilitiesPage }) }}</p>
          <VeyqMessage v-if="noneOffered" tone="info" class="setup-applications__none">{{ t('setup.applicationsAtLeastOne') }}</VeyqMessage>
          <VeyqButton
            type="button"
            class="setup-create"
            :label="creating ? t('setup.creating') : t('setup.kitProceed')"
            icon="arrow-right"
            :loading="creating"
            :disabled="creating || noneOffered"
            @click="proceed"
          />
        </template>
        <VeyqButton
          v-else
          type="button"
          class="setup-applications-retry"
          :label="t('setup.applicationsRetry')"
          icon="refresh"
          @click="retryApplications"
        />
      </template>

      <VeyqMessage v-if="error" tone="error" class="form-message">{{ error }}</VeyqMessage>
    </template>
  </VeyqCard>
</template>

<style scoped>
.setup-applications {
  list-style: none;
  margin: 0 0 0.85rem;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.setup-application__choice {
  display: flex;
  align-items: flex-start;
  gap: 0.6rem;
  cursor: pointer;
}
.setup-application__words {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}
.setup-application__title {
  font-weight: 600;
}
.setup-application__purpose,
.setup-applications__later {
  color: var(--app-muted);
  font-size: 0.92rem;
  line-height: 1.45;
}
.setup-applications__later {
  margin: 0 0 0.85rem;
}
.setup-applications__none {
  margin-bottom: 0.85rem;
}
</style>
