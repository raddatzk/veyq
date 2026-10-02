<script setup lang="ts">
// en.spaces.member-identity (web, cr-0083 / ADR 0023) — the "Your profile" rubric of the settings hub: edit your
// per-tenant `username` (plaintext handle) + your real name (first + last), which is **sealed under K_tenant**
// on-device before it leaves the browser (operator-blind). The tenant key is opened on demand (openOwnNameKey)
// so the panel works from any active space; a member without a tenant grant yet can still set their username.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { getMyProfile, updateMyProfile, sealName, unsealName, openOwnNameKey } from './profile'
import { useAsyncData, loadErrorKind } from '../../../shared/state/useAsyncData'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import { useToast } from '../../../shared/state/useToast'
import { handle401 } from '../../../shared/handle401'
import { isUnauthorized, decodeHttpError } from '../../../shared/httpError'

const { t } = useI18n()
const toast = useToast()
const on401 = handle401(useRouter())

const username = ref('')
const firstName = ref('')
const lastName = ref('')
// Account-global "ask before adding me to groups" preference (cr-0094) — a server-visible boolean, not a key.
const requireGroupAddConsent = ref(false)
const error = ref('')
// K_tenant (and the address our name is sealed at) is held outside any reactive proxy — key material must not flow
// through Vue reactivity.
let nameKey: Awaited<ReturnType<typeof openOwnNameKey>> = null

const { pending: loading, error: loadErr } = useAsyncData(async () => {
  const profile = await getMyProfile()
  username.value = profile.username
  requireGroupAddConsent.value = profile.requireGroupAddConsent
  nameKey = await openOwnNameKey()
  if (nameKey) {
    const name = unsealName(nameKey.key, nameKey.address, profile.sealedName)
    firstName.value = name?.firstName ?? ''
    lastName.value = name?.lastName ?? ''
  }
}, { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value) !== null)

const { run: runSave, pending: saving } = useAsyncAction(
  async () => {
    // Seal whatever the fields hold whenever we hold K_tenant — an EMPTY name included: that is how a member
    // removes their real name (an omitted field would leave the old one on the server, per the contract). Without
    // the key only the username is updated; the name inputs are disabled then, so nothing typed is dropped.
    const sealedName = nameKey
      ? sealName(nameKey.key, nameKey.address, { firstName: firstName.value, lastName: lastName.value })
      : undefined
    await updateMyProfile({
      username: username.value.trim().toLowerCase(),
      sealedName,
      requireGroupAddConsent: requireGroupAddConsent.value,
    })
    toast.success(t('profile.saved'))
  },
  {
    onError: async (e) => {
      on401(e)
      if (isUnauthorized(e)) return
      const { code } = await decodeHttpError(e)
      error.value = code === 'username_taken' ? t('profile.usernameTaken') : t('profile.saveFailed')
    },
  },
)

async function save() {
  if (!username.value.trim()) return
  error.value = ''
  await runSave()
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('profile.title') }}</h2>
          <p>{{ t('profile.sub') }}</p>
        </div>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('profile.loading') }}</p>
      <Message v-else-if="loadError" severity="error" :closable="false">{{ t('profile.loadFailed') }}</Message>

      <form v-else class="veyq-form" @submit.prevent="save">
        <label>{{ t('profile.username') }}
          <InputText v-model="username" name="username" autocomplete="username" required />
          <small class="field-hint">{{ t('profile.usernameHint') }}</small>
        </label>
        <div class="name-row">
          <label>{{ t('profile.firstName') }}<InputText v-model="firstName" autocomplete="given-name" :disabled="!nameKey" /></label>
          <label>{{ t('profile.lastName') }}<InputText v-model="lastName" autocomplete="family-name" :disabled="!nameKey" /></label>
        </div>
        <small v-if="!nameKey" class="field-hint">{{ t('profile.noNameYet') }}</small>

        <!-- Account-global consent preference (cr-0094). Applies in every space you belong to. -->
        <div class="consent-row">
          <Checkbox v-model="requireGroupAddConsent" input-id="require-group-add-consent" binary />
          <label class="consent-label" for="require-group-add-consent">
            <span>{{ t('profile.consentGroupAdd') }}</span>
            <small class="field-hint">{{ t('profile.consentGroupAddHint') }}</small>
          </label>
        </div>

        <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
        <div class="veyq-dialog-action-row">
          <Button type="submit" :label="t('profile.save')" icon="pi pi-check" :loading="saving" :disabled="!username.trim()" />
        </div>
      </form>
    </section>
  </section>
</template>

<style scoped>

.consent-row {
  display: flex;
  gap: 0.6rem;
  align-items: flex-start;
}
.consent-label {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  cursor: pointer;
}
</style>
