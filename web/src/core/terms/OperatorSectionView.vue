<script setup lang="ts">
// The operator section of the terms (ADR 0035 decision 6), as the member gate, the share gate, the space's terms panel
// and the public page show it: after the space's own text, read-only. The guest-write record's information carries the
// anchor its first-layer sentence links (`#guest-write-record`), and its right to object (GDPR Art. 21) stands apart
// from the rest (Art. 21(4)). The markdown is the server's rendering of veyq's fixed text with values the Server Owner
// typed; `renderMarkdown` escapes first (safe subset), so `v-html` is safe.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { renderMarkdown } from '../../shared/markdown'
import { pickSectionText, RECORD_ANCHOR, type OperatorSection } from './operatorSection'

const props = defineProps<{ section: OperatorSection }>()
const { locale } = useI18n()

const rendered = (text: OperatorSection['text'] | null | undefined) => {
  const md = pickSectionText(text, locale.value)
  return md ? renderMarkdown(md) : ''
}
const text = computed(() => rendered(props.section.text))
const record = computed(() => rendered(props.section.record))
const objection = computed(() => rendered(props.section.objection))
</script>

<template>
  <section class="operator-section" data-testid="operator-section">
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div class="markdown-body" v-html="text"></div>
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div v-if="record" :id="RECORD_ANCHOR" class="markdown-body operator-section__record" data-testid="operator-section-record" v-html="record"></div>
    <!-- eslint-disable-next-line vue/no-v-html -->
    <aside v-if="objection" id="right-to-object" class="markdown-body operator-section__objection" data-testid="operator-section-objection" v-html="objection"></aside>
  </section>
</template>

<style scoped>
.operator-section {
  display: grid;
  gap: 0.75rem;
}
.operator-section__objection {
  padding: 0.6rem 0.85rem;
  border: 1px solid var(--app-line);
  border-left: 3px solid var(--app-accent);
  border-radius: 8px;
  background: var(--app-surface-muted);
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
