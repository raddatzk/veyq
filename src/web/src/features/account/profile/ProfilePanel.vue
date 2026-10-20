<script setup lang="ts">
// uc.spaces.edit-profile (web, cr-0083 / ADR 0023) — the "Your profile" rubric of the settings hub: edit your
// per-tenant `username` (plaintext handle) + your real name (first + last) + your profile picture (ADR 0084), the last two
// **sealed under K_tenant** on-device before they leave the browser (operator-blind). Name and picture are the shared
// {@link NamePictureFields} (the member's own profile page edits the same). The tenant key is opened on demand
// (openOwnNameKey) so the panel works from any active space; a member without a tenant grant yet can still set their username.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Checkbox from 'primevue/checkbox'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { getMyProfile, updateMyProfile } from './profile'
import NamePictureFields from './NamePictureFields.vue'
import { useNameAndPicture } from './useNameAndPicture'
import { useAsyncData, loadErrorKind } from '../../../shared/state/useAsyncData'
import { useAsyncAction } from '../../../shared/state/useAsyncAction'
import { useToast } from '../../../shared/state/useToast'
import { handle401 } from '../../../shared/handle401'
import { isUnauthorized, decodeHttpError } from '../../../shared/httpError'
import { useCapabilities } from '../../../shared/state/useCapabilities'
import { CORE_GROUP_SPACE } from '../../platform/capabilities'

const { t } = useI18n()
// The open space may require consent for everyone (ADR 0088 decision 8, `addConsent`): the preference still holds in the
// other spaces, so it stays editable, and the line says that here a group add asks anyway.
const consentRequiredHere = computed(() => useCapabilities().capabilityValues(CORE_GROUP_SPACE)?.addConsent === true)
const toast = useToast()
const on401 = handle401(useRouter())

const username = ref('')
const identity = useNameAndPicture()
// Account-global "ask before adding me to groups" preference (cr-0094) — a server-visible boolean, not a key.
const requireGroupAddConsent = ref(false)
const error = ref('')
const { pending: loading, error: loadErr } = useAsyncData(async () => {
  const profile = await getMyProfile()
  username.value = profile.username
  requireGroupAddConsent.value = profile.requireGroupAddConsent
  await identity.load(profile)
}, { onError: on401 })
const loadError = computed(() => loadErrorKind(loadErr.value) !== null)

const { run: runSave, pending: saving } = useAsyncAction(
  async () => {
    // Seal whatever the fields hold whenever we hold K_tenant — an EMPTY name included: that is how a member
    // removes their real name (an omitted field would leave the old one on the server, per the contract). Without
    // the key only the username is updated; the name inputs are disabled then, so nothing typed is dropped.
    await updateMyProfile({
      username: username.value.trim().toLowerCase(),
      sealedName: identity.sealedName(),
      requireGroupAddConsent: requireGroupAddConsent.value,
    })
    // The picture is its own request after the profile: a failure there leaves the saved profile saved and says so.
    try {
      await identity.savePicture()
    } catch (e) {
      on401(e)
      if (!isUnauthorized(e)) error.value = t('profilePicture.saveFailed')
      return
    }
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
        <NamePictureFields :model="identity" :display-name="`${identity.firstName} ${identity.lastName}`.trim() || username" />

        <!-- Account-global consent preference (cr-0094). Applies in every space you belong to. -->
        <div class="consent-row">
          <Checkbox v-model="requireGroupAddConsent" input-id="require-group-add-consent" binary />
          <label class="consent-label" for="require-group-add-consent">
            <span>{{ t('profile.consentGroupAdd') }}</span>
            <small class="field-hint">{{ t('profile.consentGroupAddHint') }}</small>
            <small v-if="consentRequiredHere" class="field-hint consent-required-here">{{ t('profile.consentRequiredHere') }}</small>
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
