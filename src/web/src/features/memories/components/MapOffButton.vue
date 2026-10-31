<script setup lang="ts">
// "Turn map off" — drawn on every Memories map that loads tiles (a level's and a person's `SpaceMap`, the place editor's and
// the lightbox's `MemoryMap`), at the bottom left, on the attribution's line. It withdraws this browser's consent to the map
// tiles and the place-name lookup at once (GDPR Art. 7(3): as easy as giving it, uc.memory.place A5): the flag is removed,
// `mapsAvailable` turns false and the host unmounts its Leaflet map, so no further tile is requested. The one control for a
// member and a share guest alike — a guest has no settings, so this is where a guest withdraws.
import { useI18n } from 'vue-i18n'
import { setMapsConsent } from '../../../shared/state/useMapConfig'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'

const { t } = useI18n()
</script>

<template>
  <button type="button" class="veyq-map-off" @click="setMapsConsent(false)">
    <VeyqIcon name="eye-slash" />
    {{ t('memory.place.mapOff') }}
  </button>
</template>

<style scoped>
.veyq-map-off {
  position: absolute;
  left: 8px;
  bottom: 8px;
  z-index: 1000; /* above Leaflet's panes and beside its control corners (`.leaflet-bottom` is 1000 too) */
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.2rem 0.55rem;
  border: 1px solid var(--app-line);
  border-radius: 999px;
  background: var(--app-surface);
  color: var(--app-text);
  font: inherit;
  font-size: 0.78rem;
  cursor: pointer;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.18);
}
.veyq-map-off:hover {
  background: var(--app-hover);
}
.veyq-map-off:focus-visible {
  outline: 2px solid var(--app-primary);
  outline-offset: 2px;
}
</style>
