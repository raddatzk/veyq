<script setup lang="ts">
// The public page of the instance privacy notice (ADR 0035 decision 8, GDPR Art. 13; uc.admin.privacy-notice), at
// `/privacy` on every tenant host, no session: Art. 13 applies at collection — the invite acceptance, the report dialog,
// a guest's first write — so the notice is readable before any account. Linked from the footer of every page
// (`OperatorIdentification`) and, at the passage that concerns them, from the places that collect (`privacyLink`); the
// passage a link names is scrolled into view once rendered. Each right to object stands apart (Art. 21(4)). Read
// through the PUBLIC client; a failed read says so, names the instance contact where it loads, and blocks nothing.
// The markdown is the server's rendering of veyq's fixed text with values the Server Owner typed; `renderMarkdown`
// escapes first (safe subset), so `v-html` is safe.
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import VeyqCard from '../shared/ui/VeyqCard.vue'
import VeyqMessage from '../shared/ui/VeyqMessage.vue'
import OperatorIdentification from '../core/operator/OperatorIdentification.vue'
import { loadPublishedOperator } from '../core/operator/operatorDetails'
import { loadPrivacyNotice, type PrivacyNotice } from '../core/privacy/privacyNotice'
import { pickSectionText, type SectionText } from '../core/terms/operatorSection'
import { renderMarkdown } from '../shared/markdown'

const { t, locale } = useI18n()
const route = useRoute()
const phase = ref<'loading' | 'ready' | 'failed'>('loading')
const notice = ref<PrivacyNotice | null>(null)
const contact = ref<string | null>(null)

const rendered = (text: SectionText | null | undefined) => {
  const md = pickSectionText(text, locale.value)
  return md ? renderMarkdown(md) : ''
}
const passages = computed(() => (notice.value?.passages ?? []).map((p) => ({ id: p.id, separate: p.separate, html: rendered(p.text) })))

onMounted(async () => {
  notice.value = await loadPrivacyNotice()
  phase.value = notice.value ? 'ready' : 'failed'
  if (!notice.value) {
    contact.value = (await loadPublishedOperator()).contact?.email ?? null
    return
  }
  // The anchor a link named: it renders only now, so the browser's own jump came too early.
  const anchor = route.hash.startsWith('#') ? route.hash.slice(1) : ''
  if (anchor) {
    await nextTick()
    document.getElementById(anchor)?.scrollIntoView()
  }
})
</script>

<template>
  <VeyqCard class="privacy-notice-card">
    <template #title>{{ t('privacyNotice.title') }}</template>
    <template #default>
      <p v-if="phase === 'loading'" class="privacy-notice-muted">{{ t('privacyNotice.loading') }}</p>
      <template v-else-if="phase === 'failed'">
        <VeyqMessage tone="error">{{ t('privacyNotice.loadFailed') }}</VeyqMessage>
        <p v-if="contact" class="veyq-form-copy" data-testid="privacy-notice-contact">
          {{ t('privacyNotice.askContact') }} <a :href="`mailto:${contact}`">{{ contact }}</a>
        </p>
      </template>
      <section v-else class="privacy-notice" data-testid="privacy-notice">
        <template v-for="p in passages" :key="p.id">
          <!-- eslint-disable-next-line vue/no-v-html -->
          <aside v-if="p.separate" :id="p.id" class="markdown-body privacy-notice__objection" v-html="p.html"></aside>
          <!-- eslint-disable-next-line vue/no-v-html -->
          <div v-else :id="p.id" class="markdown-body" v-html="p.html"></div>
        </template>
      </section>
    </template>
    <template #footer>
      <div class="privacy-notice-footer">
        <OperatorIdentification />
      </div>
    </template>
  </VeyqCard>
</template>

<style scoped>
.privacy-notice-muted {
  opacity: 0.7;
}
.privacy-notice {
  display: grid;
  gap: 0.75rem;
}
.privacy-notice__objection {
  padding: 0.6rem 0.85rem;
  border: 1px solid var(--app-line);
  border-left: 3px solid var(--app-accent);
  border-radius: 8px;
  background: var(--app-surface-muted);
}
.privacy-notice :deep(h2) {
  font-size: 1rem;
  margin: 0.9rem 0 0.35rem;
}
.privacy-notice :deep(h3) {
  font-size: 0.92rem;
  margin: 0.8rem 0 0.3rem;
}
.privacy-notice :deep(p) {
  margin: 0 0 0.55rem;
}
.privacy-notice :deep(ul) {
  margin: 0 0 0.55rem;
  padding-left: 1.3rem;
}
.privacy-notice-footer {
  display: flex;
  justify-content: center;
}
</style>
