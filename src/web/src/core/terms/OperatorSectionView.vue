<script setup lang="ts">
// The operator section of the terms (ADR 0035 decision 6), as the member gate, the share gate, the space's terms panel
// and the public page show it: after the space's own text, read-only. The guest-write record's passage (always served:
// the record is kept wherever a guest may write, ADR 0093 § 10) is a pointer to the record's Art. 13 information, which is the instance privacy notice's (ADR 0035 decision 8, Q27 of
// cr-instance-privacy-notice), with a link there — in a tab of its own, so a share page's gate keeps its fragment key.
// The markdown is the server's rendering of veyq's fixed text with values the Server Owner typed; `renderMarkdown`
// escapes first (safe subset), so `v-html` is safe.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { renderMarkdown } from '../../shared/markdown'
import { PRIVACY_ANCHOR, privacyLink } from '../privacy/privacyNotice'
import { pickSectionText, RECORD_ANCHOR, type OperatorSection } from './operatorSection'

const props = defineProps<{ section: OperatorSection }>()
const { t, locale } = useI18n()

const rendered = (text: OperatorSection['text'] | null | undefined) => {
  const md = pickSectionText(text, locale.value)
  return md ? renderMarkdown(md) : ''
}
const text = computed(() => rendered(props.section.text))
const record = computed(() => rendered(props.section.record))
</script>

<template>
  <section class="operator-section" data-testid="operator-section">
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div class="markdown-body" v-html="text"></div>
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div v-if="record" :id="RECORD_ANCHOR" class="markdown-body operator-section__record" data-testid="operator-section-record">
      <!-- eslint-disable-next-line vue/no-v-html -->
      <div v-html="record"></div>
      <a class="operator-section__record-link" :href="privacyLink(PRIVACY_ANCHOR.guestWriteRecord)" target="_blank" rel="noopener">{{ t('operatorTerms.recordLink') }}</a>
    </div>
  </section>
</template>

<style scoped>
.operator-section {
  display: grid;
  gap: 0.75rem;
}
.operator-section__record-link {
  color: var(--app-accent);
}
.operator-section :deep(h2) {
  font-size: 1rem;
  margin: 0.9rem 0 0.35rem;
}
.operator-section :deep(h3) {
  font-size: 0.92rem;
  margin: 0.8rem 0 0.3rem;
}
.operator-section :deep(p) {
  margin: 0 0 0.55rem;
}
.operator-section :deep(ul) {
  margin: 0 0 0.55rem;
  padding-left: 1.3rem;
}
</style>
