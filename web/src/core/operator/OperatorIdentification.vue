<script setup lang="ts">
// uc.admin.operator-details — the provider identification on the login and share pages (ADR 0035 decision 7): a quiet
// footer link that opens the values the Server Owner published, in either operating mode, wherever they are set. Absent
// while none is set or while the read fails (fail-open, `operatorDetails.ts`), so it never stands between a visitor and
// the page. A guest-mode mount: the read is the public client, never an authenticated one.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { loadProviderIdentification, type ProviderIdentification } from './operatorDetails'

const { t } = useI18n()
const identification = ref<ProviderIdentification | null>(null)
const open = ref(false)

onMounted(async () => {
  identification.value = await loadProviderIdentification()
})
</script>

<template>
  <span v-if="identification" class="operator-identification">
    <button type="button" class="operator-identification__link" :aria-expanded="open" @click="open = !open">
      {{ t('operatorDetails.link') }}
    </button>
    <address v-if="open" class="operator-identification__values" :aria-label="t('operatorDetails.title')">
      <strong v-if="identification.name">{{ identification.name }}</strong>
      <span v-if="identification.address" class="operator-identification__address">{{ identification.address }}</span>
      <a v-if="identification.email" :href="`mailto:${identification.email}`">{{ identification.email }}</a>
      <span v-if="identification.representative">{{ t('operatorDetails.representative', { name: identification.representative }) }}</span>
    </address>
  </span>
</template>

<style scoped>
.operator-identification__link {
  padding: 0;
  border: none;
  background: none;
  color: var(--app-muted);
  font: inherit;
  font-size: 0.82rem;
  text-decoration: underline;
  cursor: pointer;
}
.operator-identification__values {
  display: grid;
  gap: 0.2rem;
  margin-top: 0.5rem;
  color: var(--app-muted);
  font-size: 0.82rem;
  font-style: normal;
  text-align: center;
}
.operator-identification__address {
  white-space: pre-line;
}
.operator-identification__values a {
  color: var(--app-accent);
}
</style>
