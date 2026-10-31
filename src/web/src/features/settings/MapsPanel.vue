<script setup lang="ts">
// Settings → Personal → Map and place names (uc.memory.place A5, ADR 0016): this browser's one consent to the map tiles
// and the reverse geocoder, both client-direct third parties. It shows which services this server configured (the hosts of
// the capabilities `useMapConfig` reads), what they receive, and a switch that gives or withdraws the consent at once —
// the same flag the in-context prompts set and every map's "Turn map off" clears. Device-local (ADR 0022): nothing here
// reaches the server. With no tile service there is nothing to consent to; the switch stays only while an earlier consent
// is still stored, so it can be withdrawn too. A consent names its hosts: one stored for other hosts holds only for them,
// so the switch shows off and the panel names the hosts it was given for (switching on agrees to the current ones).
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqSwitch from '../../shared/ui/VeyqSwitch.vue'
import { useMapConfig, setMapsConsent, serviceHost } from '../../shared/state/useMapConfig'

const { t } = useI18n()
const { mapsConsent, mapsOffered, tileUrl, geocoderUrl, consentGivenFor } = useMapConfig()

// Offered: on while the consent holds for this server's hosts. Not offered: on while any consent is stored, to withdraw it.
const consent = computed({
  get: () => (mapsOffered.value ? mapsConsent.value : consentGivenFor.value !== null),
  set: (on: boolean) => setMapsConsent(on),
})
// A stored consent for other hosts than the ones above (or none recorded): what it was given for.
const givenFor = computed(() => (mapsOffered.value && consentGivenFor.value && !mapsConsent.value ? consentGivenFor.value : null))
const tileHost = computed(() => (tileUrl.value ? serviceHost(tileUrl.value) : ''))
const geocoderHost = computed(() => (geocoderUrl.value ? serviceHost(geocoderUrl.value) : ''))
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('settings.maps') }}</h2>
          <p>{{ t('settings.mapsSub') }}</p>
        </div>
      </div>

      <p v-if="!mapsOffered" class="maps-not-offered">{{ t('settings.mapsNotOffered') }}</p>
      <template v-else>
        <dl class="maps-services">
          <div class="maps-service">
            <dt>{{ t('settings.mapsTiles') }}</dt>
            <dd class="maps-tile-host">{{ tileHost }}</dd>
          </div>
          <div class="maps-service">
            <dt>{{ t('settings.mapsPlaces') }}</dt>
            <dd class="maps-geocoder-host">{{ geocoderHost || t('settings.mapsNone') }}</dd>
          </div>
        </dl>
        <p class="maps-receive">{{ t('settings.mapsReceive') }}</p>
        <p v-if="givenFor" class="maps-given-for">
          <!-- no tile host recorded: the legacy consent, which named no host at all; else no geocoder was served then -->
          {{
            givenFor.tiles === null
              ? t('settings.mapsGivenForUnrecorded')
              : t('settings.mapsGivenFor', { tiles: givenFor.tiles, places: givenFor.places ?? t('settings.mapsNone') })
          }}
        </p>
      </template>

      <div v-if="mapsOffered || consentGivenFor" class="maps-toggle-row">
        <VeyqSwitch v-model="consent" input-id="maps-consent" />
        <label class="maps-toggle-label" for="maps-consent">
          <span>{{ t('settings.mapsSwitch') }}</span>
          <small class="field-hint">{{ t('settings.mapsSwitchHint') }}</small>
        </label>
      </div>
    </section>
  </section>
</template>

<style scoped>
.maps-services {
  display: grid;
  gap: 0.35rem;
  margin: 0 0 0.75rem;
}
.maps-service {
  display: flex;
  gap: 1rem;
  justify-content: space-between;
  padding: 0.5rem 0;
  border-top: 0.5px solid var(--app-line);
}
.maps-service dt {
  font-weight: 500;
}
.maps-service dd {
  margin: 0;
  color: var(--app-muted);
  overflow-wrap: anywhere;
  text-align: right;
}
.maps-receive,
.maps-given-for,
.maps-not-offered {
  margin-bottom: 1rem;
}
.maps-toggle-row {
  display: flex;
  gap: 0.6rem;
  align-items: flex-start;
}
.maps-toggle-label {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  cursor: pointer;
}
</style>
