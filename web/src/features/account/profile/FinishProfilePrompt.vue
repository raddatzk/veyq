<script setup lang="ts">
// The one-time "finish your profile" prompt (ADR 0084 decision 7, uc.spaces.set-profile-picture A3). A member who joined
// grant-less could not seal a name or picture at join; once this device holds the tenant key and neither is set, a small
// dismissible notice points at Settings > Your profile. Shown once: "Not now" and following the link both store the
// dismissal per user (`finishProfileDismissed`), and a profile that already has a name or a picture shows nothing. Reads
// the profile and the key once per document; stores no image.
import { ref, watchEffect } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import { activeSpace } from '../../../app/activeSpace'
import { account } from '../../../app/account'
import { getMyProfile, openOwnNameKey, unsealName } from './profile'
import { finishProfileDismissed } from './finishProfileDismissed'

const { t } = useI18n()
const router = useRouter()
const show = ref(false)
let checked = false

async function check() {
  checked = true
  try {
    const [profile, own] = await Promise.all([getMyProfile(), openOwnNameKey()])
    if (!own) return // no tenant key yet: nothing could be sealed, so nothing to ask for
    const name = unsealName(own.key, own.address, profile.sealedName)
    const hasName = Boolean(name && `${name.firstName}${name.lastName}`.trim())
    show.value = !hasName && profile.avatarVersion === 0
  } catch {
    // best-effort: a prompt that cannot be decided is not shown
  }
}

// Decide once the member is known, the tenant resolved and the prompt not already dismissed.
watchEffect(() => {
  if (checked || !account.userId || activeSpace.status !== 'ready' || !activeSpace.tenantId) return
  if (finishProfileDismissed.value.value === '1') return
  void check()
})

function dismiss() {
  finishProfileDismissed.set('1')
  show.value = false
}
function openProfile() {
  dismiss()
  void router.push('/settings/profile')
}
</script>

<template>
  <section v-if="show" class="finish-profile" role="region" :aria-label="t('profilePicture.finish.title')" data-testid="finish-profile">
    <div class="finish-profile-text">
      <strong>{{ t('profilePicture.finish.title') }}</strong>
      <span>{{ t('profilePicture.finish.body') }}</span>
    </div>
    <div class="finish-profile-actions">
      <Button type="button" :label="t('profilePicture.finish.open')" icon="pi pi-user" size="small" data-testid="finish-profile-open" @click="openProfile" />
      <Button type="button" :label="t('profilePicture.finish.dismiss')" severity="secondary" text size="small" data-testid="finish-profile-dismiss" @click="dismiss" />
    </div>
  </section>
</template>

<style scoped>
.finish-profile {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.6rem 1rem;
  margin-bottom: 1rem;
  padding: 0.7rem 0.9rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
  color: var(--app-text);
}
.finish-profile-text {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  min-width: 0;
  flex: 1 1 18rem;
}
.finish-profile-text span {
  color: var(--app-muted);
  font-size: 0.9rem;
}
.finish-profile-actions {
  display: flex;
  gap: 0.4rem;
}
</style>
