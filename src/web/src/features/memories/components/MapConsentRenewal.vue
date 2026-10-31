<script setup lang="ts">
// The line a consent prompt adds when a consent is stored but names other recipients — another tile or geocoder host,
// or none at all (a consent from before hosts were recorded): it names the hosts the member would agree to now
// (uc.memory.place A5, `useMapConfig`'s `consentRenewal`). Nothing is requested from them before that.
import { useI18n } from 'vue-i18n'
import { useMapConfig } from '../../../shared/state/useMapConfig'

const { t } = useI18n()
const { consentRenewal } = useMapConfig()
</script>

<template>
  <p v-if="consentRenewal" class="map-consent-renewal">
    {{
      consentRenewal.places
        ? t('memory.place.consentRenewBoth', { tiles: consentRenewal.tiles, places: consentRenewal.places })
        : t('memory.place.consentRenewTiles', { tiles: consentRenewal.tiles })
    }}
  </p>
</template>
