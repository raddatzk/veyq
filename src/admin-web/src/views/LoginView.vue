<script setup lang="ts">
// Server-Owner sign-in. Deliberately the plainest possible form: email + password, nothing else. `src/web/`'s
// login card carries passkeys, "remember me", a Secret-Key field and a recovery link; none of that belongs on
// a surface that holds no vault and unlocks nothing (ADR 0065 — this is a pure Admin-API client).
//
// A failure says the same thing for a wrong password and an unknown account: the backend's 401 is
// non-enumerable by design (ADR 0003), and repeating that distinction in the UI would undo it.
//
// Arriving here because the server ended the session (`app/sessionEnd.ts`, inbox #449) is said once, above the
// form: a `warn`, not an `error` — nothing failed that signing in again does not cure.
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Card from 'primevue/card'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import Password from 'primevue/password'
import { login } from '../features/auth/login'
import { takeAuthPrefill } from '../app/authPrefill'
import { takeSessionExpired } from '../app/sessionEnd'
import { useAsyncAction } from '../shared/state/useAsyncAction'

const router = useRouter()
const { t } = useI18n()
const email = ref('')
const password = ref('')
const error = ref('')
const expired = ref(takeSessionExpired())

// Straight after setup the owner's email is handed over so they don't retype it. The Secret Key the prefill
// also carries is ignored here: this surface never unlocks a vault, so it has nothing to do with it.
onMounted(() => {
  const prefill = takeAuthPrefill()
  if (prefill) email.value = prefill.email
})

const { run: submit, pending: busy } = useAsyncAction(
  async () => {
    error.value = ''
    expired.value = false
    await login(email.value, password.value)
    await router.push({ name: 'console' })
  },
  { onError: () => { error.value = t('admin.signInFailed') } },
)
</script>

<template>
  <Card class="login-card">
    <template #title>{{ t('admin.title') }}</template>
    <template #content>
      <Message v-if="expired" severity="warn" :closable="false" class="form-message">{{ t('admin.sessionExpired') }}</Message>
      <p class="veyq-form-copy">{{ t('admin.signInSub') }}</p>
      <form class="veyq-form" @submit.prevent="submit()">
        <label>
          {{ t('common.email') }}
          <InputText v-model="email" type="email" autocomplete="username" required autofocus />
        </label>
        <label>
          {{ t('common.password') }}
          <Password
            v-model="password"
            input-id="admin-password"
            :input-props="{ autocomplete: 'current-password' }"
            required
            :feedback="false"
            toggle-mask
            fluid
          />
        </label>
        <Button
          type="submit"
          :label="busy ? t('admin.signingIn') : t('admin.signIn')"
          icon="pi pi-sign-in"
          :loading="busy"
        />
      </form>
      <Message v-if="error" severity="error" :closable="false" class="form-message">{{ error }}</Message>
    </template>
  </Card>
</template>
