<script setup lang="ts">
// Grid / list switch for the memory card grid (cr-0063). Icon buttons over the app-wide `useViewMode` preference
// (persisted) — used in the browse toolbar and above the Chapters-tab grid; both drive the one value. The browse
// adds a third **Karte** button (`show-map`, cr-0071) — the map is browse-only, so the Chapters grid omits it.
import { useI18n } from 'vue-i18n'
import { useViewMode, setViewMode } from '../../../shared/state/useViewMode'

withDefaults(defineProps<{ showMap?: boolean }>(), { showMap: false })

const { viewMode } = useViewMode()
const { t } = useI18n()
</script>

<template>
  <div class="view-mode-toggle" role="group" :aria-label="t('memory.viewMode')">
    <button
      v-tooltip.bottom="t('memory.viewGrid')"
      type="button"
      class="view-mode-button"
      :class="{ 'is-active': viewMode === 'grid' }"
      :aria-label="t('memory.viewGrid')"
      :aria-pressed="viewMode === 'grid'"
      @click="setViewMode('grid')"
    >
      <span class="pi pi-th-large" aria-hidden="true" />
    </button>
    <button
      v-tooltip.bottom="t('memory.viewList')"
      type="button"
      class="view-mode-button"
      :class="{ 'is-active': viewMode === 'list' }"
      :aria-label="t('memory.viewList')"
      :aria-pressed="viewMode === 'list'"
      @click="setViewMode('list')"
    >
      <span class="pi pi-bars" aria-hidden="true" />
    </button>
    <button
      v-if="showMap"
      v-tooltip.bottom="t('memory.viewMap')"
      type="button"
      class="view-mode-button"
      :class="{ 'is-active': viewMode === 'map' }"
      :aria-label="t('memory.viewMap')"
      :aria-pressed="viewMode === 'map'"
      @click="setViewMode('map')"
    >
      <span class="pi pi-map" aria-hidden="true" />
    </button>
  </div>
</template>

<style scoped>
.view-mode-toggle {
  display: inline-flex;
  gap: 0.15rem;
  padding: 0.15rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-control-bg);
}
.view-mode-button {
  display: grid;
  place-items: center;
  width: 2rem;
  height: 2rem;
  padding: 0;
  border: 0;
  border-radius: calc(var(--app-radius-sm) - 2px);
  background: transparent;
  color: var(--app-muted);
  cursor: pointer;
}
.view-mode-button:hover {
  color: var(--app-text);
}
.view-mode-button.is-active {
  background: var(--app-surface);
  color: var(--app-accent);
  box-shadow: var(--app-shadow-sm);
}
.view-mode-button .pi {
  font-size: 0.9rem;
}
</style>
