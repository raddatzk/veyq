<script setup lang="ts">
// The moderation surface (ADR 0099 § 6, uc.moderation.serve-as-moderator): one route on every tenant host, outside the
// member shell. It signs the moderator in, takes up the appointment, confirms the address the instance mail reaches,
// and unlocks — with the password AND the Secret Key every time (owner decision Q2) — only to open the moderation
// grants; the vault is dropped before the queue renders (`unlockModeration.ts`). Reports arrive (uc.moderation.report),
// but the queue does not list them yet: that is the review's; reaching it already needs the key proof.
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Card from 'primevue/card'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import SecretKeyField from '../shared/ui/SecretKeyField.vue'
import { LoginVerifyRequestSessionScopeEnum } from '../api/generated/auth'
import { session } from '../app/session'
import { login } from '../features/account/login/login'
import {
  acceptAppointment,
  confirmAddress,
  myAppointment,
  sendAddressCode,
  stepDown,
  type ModeratorAppointment,
} from '../features/moderation/appointment'
import { clearModerationKeys } from '../features/moderation/moderationKeys'
import { ModerationBundleMismatch, unlockModeration, type ModerationUnlock } from '../features/moderation/unlockModeration'
import { decodeHttpError, isUnauthorized } from '../shared/httpError'

const { t } = useI18n()

const appointment = ref<ModeratorAppointment | null>(null)
const unlocked = ref<ModerationUnlock | null>(null)
const busy = ref(false)
// A page that holds a session reads the appointment first: until it is read the page shows neither the sign-in form
// nor a state, so a revived session never flashes the sign-in form (the browser pass clicked into that flash).
const loading = ref(session.accessToken !== null)
const error = ref('')
const notice = ref('')

const email = ref('')
const password = ref('')
const secretKey = ref('')
const code = ref('')

const signedIn = computed(() => session.accessToken !== null && appointment.value !== null)
const state = computed(() => appointment.value?.state ?? null)

async function run(action: () => Promise<void>, failure: (code: string, status: number) => string) {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
  } catch (e) {
    if (e instanceof ModerationBundleMismatch) {
      error.value = t('moderation.bundleMismatch', { epoch: e.epoch })
    } else if (isUnauthorized(e)) {
      appointment.value = null
      error.value = t('moderation.signInFailed')
    } else {
      const { status, code: c } = await decodeHttpError(e)
      error.value = failure(c ?? '', status)
    }
  } finally {
    busy.value = false
  }
}

async function load() {
  if (session.accessToken === null) return
  await run(
    async () => {
      appointment.value = await myAppointment()
    },
    () => t('moderation.loadFailed'),
  )
  loading.value = false
}

const signIn = () =>
  run(
    async () => {
      await login(email.value.trim(), password.value, LoginVerifyRequestSessionScopeEnum.Session)
      password.value = ''
      appointment.value = await myAppointment()
    },
    () => t('moderation.signInFailed'),
  )

const accept = () =>
  run(
    async () => {
      appointment.value = await acceptAppointment()
    },
    () => t('moderation.acceptFailed'),
  )

const resend = () =>
  run(
    async () => {
      appointment.value = await sendAddressCode()
    },
    (c) => t(c === 'rate_limited' ? 'moderation.rateLimited' : 'moderation.codeSendFailed'),
  )

const confirm = () =>
  run(
    async () => {
      appointment.value = await confirmAddress(code.value)
      code.value = ''
    },
    (c) => t(c === 'mail_code_invalid' ? 'moderation.codeInvalid' : c === 'rate_limited' ? 'moderation.rateLimited' : 'moderation.codeFailed'),
  )

const unlock = () =>
  run(
    async () => {
      const result = await unlockModeration(appointment.value!.address, password.value, secretKey.value)
      unlocked.value = result
      if (result.delivered > 0) notice.value = t('moderation.delivered', { count: result.delivered }, result.delivered)
      if (result.declined > 0) error.value = t('moderation.declined', { count: result.declined }, result.declined)
      appointment.value = await myAppointment()
    },
    () => t('moderation.unlockFailed'),
  ).finally(() => {
    password.value = ''
    secretKey.value = ''
  })

const leave = () =>
  run(
    async () => {
      await stepDown()
      unlocked.value = null
      appointment.value = await myAppointment()
      notice.value = t('moderation.steppedDown')
    },
    (c) => t(c === 'moderation_last_holder' ? 'moderation.lastHolder' : 'moderation.stepDownFailed'),
  )

onMounted(load)
// The keys live as long as this page: leaving it zeroes them.
onUnmounted(clearModerationKeys)
</script>

<template>
  <Card class="login-card moderation-card">
    <template #title>{{ t('moderation.title') }}</template>
    <template #content>
      <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
      <Message v-if="notice" severity="success" :closable="false" class="form-message">{{ notice }}</Message>

      <p v-if="loading" class="veyq-form-copy moderation-loading">{{ t('moderation.loading') }}</p>

      <!-- Signed out: sign in here, never through the member login (it would open a device wrap). -->
      <form v-else-if="!signedIn" class="veyq-form moderation-sign-in-form" @submit.prevent="signIn">
        <p class="veyq-form-copy">{{ t('moderation.signInCopy') }}</p>
        <label>{{ t('common.email') }}<InputText v-model="email" type="email" autocomplete="username" required /></label>
        <label for="moderation-sign-in-password">{{ t('moderation.password') }}</label>
        <Password v-model="password" input-id="moderation-sign-in-password" :feedback="false" toggle-mask fluid :input-props="{ autocomplete: 'current-password' }" />
        <Button type="submit" class="moderation-sign-in" :label="t('moderation.signIn')" icon="pi pi-sign-in" :loading="busy" />
      </form>

      <p v-else-if="state === 'NONE'" class="veyq-form-copy moderation-none">{{ t('moderation.notAppointed') }}</p>

      <!-- Pending: what accepting means, before it is accepted (ADR 0099 § 1). -->
      <div v-else-if="state === 'PENDING'" class="veyq-form moderation-accept">
        <p class="veyq-form-copy">{{ t('moderation.acceptIntro') }}</p>
        <p class="veyq-form-copy">{{ t('moderation.acceptCsam') }}</p>
        <p class="veyq-form-copy">{{ t('moderation.acceptLogged') }}</p>
        <Button class="moderation-accept-button" :label="t('moderation.accept')" icon="pi pi-check" :loading="busy" @click="accept" />
      </div>

      <template v-else-if="state === 'ACCEPTED'">
        <!-- The address the instance mail reaches (ADR 0097 decision 5). -->
        <form v-if="appointment!.confirmation !== 'CONFIRMED'" class="veyq-form moderation-address" @submit.prevent="confirm">
          <p class="veyq-form-copy">{{ t('moderation.addressCopy', { address: appointment!.address }) }}</p>
          <Message v-if="appointment!.confirmation === 'SEND_FAILED'" severity="warn" :closable="false" class="form-message">{{ t('moderation.codeSendFailed') }}</Message>
          <label>{{ t('moderation.code') }}<InputText v-model="code" class="moderation-code" inputmode="numeric" autocomplete="one-time-code" required /></label>
          <div class="moderation-actions">
            <Button type="submit" :label="t('moderation.confirm')" icon="pi pi-check" :loading="busy" />
            <Button type="button" severity="secondary" :label="t('moderation.resend')" icon="pi pi-send" :disabled="busy" @click="resend" />
          </div>
        </form>

        <!-- Locked: the password and the Secret Key, every time (owner decision Q2). -->
        <form v-if="unlocked === null" class="veyq-form moderation-unlock" @submit.prevent="unlock">
          <p class="veyq-form-copy">{{ t('moderation.unlockCopy') }}</p>
          <label for="moderation-password">{{ t('moderation.password') }}</label>
          <Password v-model="password" input-id="moderation-password" :feedback="false" toggle-mask fluid :input-props="{ autocomplete: 'current-password' }" />
          <label for="moderation-secret-key">{{ t('moderation.secretKey') }}</label>
          <SecretKeyField v-model="secretKey" input-id="moderation-secret-key" required :expected-email="appointment!.address" />
          <small class="field-hint">{{ t('moderation.noTrustedDevice') }}</small>
          <Button type="submit" class="moderation-unlock-button" :label="t('moderation.unlock')" icon="pi pi-lock-open" :loading="busy" />
        </form>

        <template v-else>
          <p v-if="!unlocked.holdsCurrent" class="veyq-form-copy moderation-awaiting">{{ t('moderation.awaitingKey') }}</p>
          <section v-else class="moderation-queue">
            <h3>{{ t('moderation.queue') }}</h3>
            <p class="veyq-form-copy moderation-queue-empty">{{ t('moderation.queueEmpty') }}</p>
            <Button severity="secondary" class="moderation-step-down" :label="t('moderation.stepDown')" icon="pi pi-sign-out" :loading="busy" @click="leave" />
          </section>
        </template>
      </template>
    </template>
  </Card>
</template>

<style scoped>
.moderation-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}
.moderation-queue {
  display: grid;
  gap: 0.75rem;
}
</style>
