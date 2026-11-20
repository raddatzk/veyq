<script setup lang="ts">
// The *Open in the app* banner (ADR 0150 § O2; the rule in `openInApp.ts`): one line, one per page — `AppShell.vue` draws
// it on the signed-in app's and the public card's pages, `ShareView.vue` on a link's page (not in a review) —, dismissible,
// the dismissal kept in this browser. Its link is a real link with a constant href (`veyq://open` or `veyq://link`), so
// the browser's own handling of a click opens the app (or says it cannot); on the share page the same click first puts
// the page's link on the clipboard, for the app's *Open a link*. Hidden where Safari draws Apple's Smart App Banner.
// It calls no client.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import VeyqButton from '../../shared/ui/VeyqButton.vue'
import { bannerForm, hrefOf, linkToCopy, mailedStepDone } from './openInApp'
import { dismissOpenInApp, openInAppDismissed } from './openInAppDismissal'
import { APP_STORE_ID, safariDrawsItsBanner } from './smartAppBanner'

const { t } = useI18n()
const route = useRoute()

const dismissed = ref(openInAppDismissed())
const safari = typeof navigator !== 'undefined' && safariDrawsItsBanner(APP_STORE_ID, navigator.userAgent, navigator.maxTouchPoints ?? 0)
const form = computed(() => (dismissed.value || safari ? null : bannerForm(route.name, route.hash, mailedStepDone())))
const href = computed(() => (form.value ? hrefOf(form.value) : ''))

/**
 * On the share page: the link onto the clipboard, started inside the click so the browser grants it, before the link's own
 * default opens the app. A clipboard that refuses still opens the app; its *Open a link* then waits for a paste.
 */
function onOpen() {
  if (form.value !== 'link') return
  const link = linkToCopy(window.location.href)
  if (link === null) return
  try {
    void navigator.clipboard?.writeText(link).catch(() => undefined)
  } catch {
    // no clipboard here (an insecure page): the app opens all the same
  }
}

function dismiss() {
  dismissed.value = true
  dismissOpenInApp()
}
</script>

<template>
  <section v-if="form" class="open-in-app" role="region" :aria-label="t('openInApp.label')" data-testid="open-in-app">
    <p class="open-in-app__text">
      <a class="open-in-app__link" :href="href" data-testid="open-in-app-link" @click="onOpen">{{
        form === 'link' ? t('openInApp.copyAndOpen') : t('openInApp.open')
      }}</a>
      <span class="open-in-app__line">{{ t('openInApp.line') }}</span>
    </p>
    <VeyqButton
      class="veyq-icon-button open-in-app__dismiss"
      icon="times"
      variant="secondary"
      appearance="text"
      size="small"
      :aria-label="t('openInApp.dismiss')"
      :title="t('openInApp.dismiss')"
      data-testid="open-in-app-dismiss"
      @click="dismiss"
    />
  </section>
</template>

<style scoped>
/* The donation banner's look (`core/project/DonationBanner.vue`): a neutral panel, never a status colour — it offers. */
.open-in-app {
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
.open-in-app__text {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.2rem 0.6rem;
  flex: 1 1 auto;
  min-width: 0;
  margin: 0;
  font-size: 0.9rem;
}
.open-in-app__link {
  color: var(--app-accent);
  font-weight: 600;
}
.open-in-app__line {
  color: var(--app-muted);
}
.open-in-app__dismiss {
  flex: none;
}
</style>
