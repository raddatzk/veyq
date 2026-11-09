<script setup lang="ts">
// The offer to turn the map services on (ADR 0016 / 0018): where a map would be while this browser's map services are
// off, or after they were turned off ("Turn map services off", Settings → Map services). Worded as a setting, not as a
// cookie banner (ADR 0016, owner 2026-11-09); in law turning them on is still a consent (GDPR Art. 4(11), 7), so the offer
// says what the services receive and names the hosts this server configured. One offer for a level's and a person's map
// (`SpaceMap`) and the place editor's (`MemoryLocationEditor`, uc.memory.place A6), so the map comes back where it went. A
// consent stored for other hosts is asked again here, naming the new ones (`MapConsentRenewal`, which then names the hosts
// in place of the line below). `headingLevel` is the host's (styleguide § Heading levels); without one the offer carries
// no heading (inside a titled dialog).
import { useI18n } from 'vue-i18n'
import { setMapsConsent, useMapConfig } from '../../../shared/state/useMapConfig'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import MapConsentRenewal from './MapConsentRenewal.vue'

defineProps<{ headingLevel?: 2 | 3 }>()
const { t } = useI18n()
const { currentHosts, consentRenewal } = useMapConfig()
</script>

<template>
  <div class="map-consent-prompt">
    <VeyqIcon name="map" class="map-consent-prompt-icon" />
    <component :is="`h${headingLevel}`" v-if="headingLevel" class="map-consent-prompt-title">{{ t('memory.place.consentTitle') }}</component>
    <p>{{ t('memory.place.consentBody') }}</p>
    <p v-if="!consentRenewal && currentHosts.tiles" class="map-consent-hosts">
      {{
        currentHosts.places
          ? t('memory.place.consentHosts', { tiles: currentHosts.tiles, places: currentHosts.places })
          : t('memory.place.consentHostsTiles', { tiles: currentHosts.tiles })
      }}
    </p>
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
