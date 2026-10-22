<script setup lang="ts">
// The public page of the operator section of the terms (ADR 0035 decision 6), at `/operator-terms`, no session: DSA
// Art. 14(1) asks the terms to be publicly available and easily accessible, so anyone reads here what every space's
// terms carry after their own text — without the gate, which only a space's terms raise. Linked from the footer of the
// login page, the share page and the signed-in shell (`OperatorIdentification`), and at its record passage
// (`#guest-write-record`) from the guest-write record's first-layer sentence on every guest write page. Read through
// the PUBLIC client; a failed read says so and blocks nothing else.
import { nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import VeyqCard from '../shared/ui/VeyqCard.vue'
import VeyqMessage from '../shared/ui/VeyqMessage.vue'
import OperatorSectionView from '../core/terms/OperatorSectionView.vue'
import OperatorIdentification from '../core/operator/OperatorIdentification.vue'
import { loadPublicOperatorSection, type OperatorSection } from '../core/terms/operatorSection'

const { t } = useI18n()
const route = useRoute()
const phase = ref<'loading' | 'ready' | 'failed'>('loading')
const section = ref<OperatorSection | null>(null)

onMounted(async () => {
  section.value = await loadPublicOperatorSection()
  phase.value = section.value ? 'ready' : 'failed'
  // The anchor a link named (the record's passage): it renders only now, so the browser's own jump came too early.
  const anchor = route.hash.startsWith('#') ? route.hash.slice(1) : ''
  if (anchor && section.value) {
    await nextTick()
    document.getElementById(anchor)?.scrollIntoView()
  }
})
</script>

<template>
  <VeyqCard class="operator-terms-card">
    <template #title>{{ t('operatorTerms.title') }}</template>
    <template #default>
      <p v-if="phase === 'loading'" class="operator-terms-muted">{{ t('operatorTerms.loading') }}</p>
      <VeyqMessage v-else-if="phase === 'failed'" tone="error">{{ t('operatorTerms.loadFailed') }}</VeyqMessage>
      <template v-else-if="section">
        <p class="veyq-form-copy">{{ t('operatorTerms.intro') }}</p>
        <OperatorSectionView :section="section" />
      </template>
    </template>
    <template #footer>
      <div class="operator-terms-footer">
        <OperatorIdentification />
      </div>
    </template>
  </VeyqCard>
</template>

<style scoped>
.operator-terms-muted {
  opacity: 0.7;
}
.operator-terms-footer {
  display: flex;
  justify-content: center;
}
</style>
