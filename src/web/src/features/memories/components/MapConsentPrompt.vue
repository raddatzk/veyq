<script setup lang="ts">
// The map consent prompt (ADR 0016 / 0018): where a map would be while this browser has not consented, or after the
// consent was withdrawn ("Turn map off", Settings → Map and place names). One prompt for a level's and a person's map
// (`SpaceMap`) and the place editor's (`MemoryLocationEditor`), so the map comes back where it went. A consent stored for
// other hosts is asked again here, naming the new ones (`MapConsentRenewal`). `headingLevel` is the host's (styleguide
// § Heading levels); without one the prompt carries no heading (inside a titled dialog).
import { useI18n } from 'vue-i18n'
import { setMapsConsent } from '../../../shared/state/useMapConfig'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import MapConsentRenewal from './MapConsentRenewal.vue'

defineProps<{ headingLevel?: 2 | 3 }>()
const { t } = useI18n()
</script>

<template>
  <div class="map-consent-prompt">
    <VeyqIcon name="map" class="map-consent-prompt-icon" />
    <component :is="`h${headingLevel}`" v-if="headingLevel" class="map-consent-prompt-title">{{ t('memory.place.consentTitle') }}</component>
    <p>{{ t('memory.place.consentBody') }}</p>
    <MapConsentRenewal />
    <VeyqButton :label="t('memory.place.consentEnable')" icon="map" @click="setMapsConsent(true)" />
  </div>
</template>

<style scoped>
.map-consent-prompt {
  display: grid;
  justify-items: center;
  gap: 0.6rem;
  text-align: center;
  padding: 2.5rem 1.5rem;
  border: 1px dashed var(--app-line);
  border-radius: var(--app-radius);
  background: var(--app-control-bg);
  color: var(--app-muted);
}
.map-consent-prompt-title {
  margin: 0;
  color: var(--app-text);
  font-size: 1.05rem;
}
.map-consent-prompt p {
  margin: 0;
  max-width: 42ch;
  font-size: 0.9rem;
  line-height: 1.5;
}
.map-consent-prompt-icon {
  font-size: 2rem;
  color: var(--app-accent);
}
</style>
