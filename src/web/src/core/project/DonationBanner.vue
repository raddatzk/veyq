<script setup lang="ts">
// The donation banner (uc.platform.support-the-project, ADR 0122): one line at the top of the signed-in app
// (`AppShell.vue`) and of a share page (`ShareView.vue`) — never on the login, invite, join or recovery pages, and never
// twice on one page (a bookmark's share shown in place draws `ShareGates`, not the share page, so it brings none).
//
// One component for a member and a guest (the owner principle that a share looks like the member view): `member` says
// only where the dismissal is kept. A guest's lives in this browser (`donationDismissal.ts`) and the banner calls no
// client at all. A member's lives in this browser AND on the account (`donationBannerDismissed`, the profile update):
// the banner shows only while neither holds one. An account flag found set is copied into this browser, so the next
// page load needs no read. A profile that cannot be read shows the banner (A4); a write that fails leaves the
// browser's dismissal in place.
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import { getMyProfile, updateMyProfile } from '../../features/account/profile/profile'
import { dismissedInThisBrowser, dismissInThisBrowser } from './donationDismissal'
import { PROJECT_LINK_REL, projectPageUrl } from './projectLinks'

const props = defineProps<{ member?: boolean }>()

const { t, locale } = useI18n()
const show = ref(false)
const donateUrl = computed(() => projectPageUrl('donate', locale.value))

async function onAccount(): Promise<boolean> {
  try {
    return (await getMyProfile()).donationBannerDismissed
  } catch {
    return false // A4: undecidable from the account, so the browser decides
  }
}

onMounted(async () => {
  if (dismissedInThisBrowser()) return
  if (props.member && (await onAccount())) {
    dismissInThisBrowser()
    return
  }
  show.value = !dismissedInThisBrowser()
})

async function dismiss() {
  show.value = false
  dismissInThisBrowser()
  if (!props.member) return
  try {
    await updateMyProfile({ donationBannerDismissed: true })
  } catch {
    // the browser's dismissal stands; the account is asked again on the next device
  }
}
</script>

<template>
  <section v-if="show" class="donation-banner" role="region" :aria-label="t('project.banner.label')" data-testid="donation-banner">
    <p class="donation-banner__text">
      {{ t('project.banner.text') }}
      <a class="donation-banner__link" :href="donateUrl" target="_blank" :rel="PROJECT_LINK_REL">{{ t('project.banner.donate') }}</a>
    </p>
    <VeyqButton
      class="veyq-icon-button donation-banner__dismiss"
      icon="times"
      variant="secondary"
      appearance="text"
      size="small"
      :aria-label="t('project.banner.dismiss')"
      :title="t('project.banner.dismiss')"
      data-testid="donation-banner-dismiss"
      @click="dismiss"
    />
  </section>
</template>

<style scoped>
/* The in-app notice's look (`FinishProfilePrompt.vue`): a neutral panel, never a status colour — it asks, it does not warn. */
.donation-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.6rem 1rem;
  margin-bottom: 1rem;
  padding: 0.55rem 0.5rem 0.55rem 0.9rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
  color: var(--app-text);
}
.donation-banner__text {
  flex: 1 1 auto;
  min-width: 0;
  margin: 0;
  font-size: 0.9rem;
}
.donation-banner__link {
  color: var(--app-accent);
  font-weight: 600;
  white-space: nowrap;
}
.donation-banner__dismiss {
  flex: none;
}
</style>
