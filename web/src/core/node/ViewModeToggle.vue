<script setup lang="ts">
// The view-mode switch (cr-0063; core's since inbox #370): one icon button per presentation in `views`, over the
// app-wide `useViewMode` preference (persisted) — core's level-view toolbar hands it the presentations on screen
// (`levelViews.ts`), Memories' chapters grid its grid and list. Every place drives the one value.
import { useI18n } from 'vue-i18n'
import { useViewMode, setViewMode } from '../../shared/state/useViewMode'
import type { LevelViewOption } from './levelViews'

defineProps<{ views: readonly LevelViewOption[] }>()

const { viewMode } = useViewMode()
const { t } = useI18n()
</script>

<template>
  <div class="view-mode-toggle" role="group" :aria-label="t('node.viewMode')">
    <button
      v-for="view in views"
      :key="view.mode"
      v-tooltip.bottom="t(view.label)"
      type="button"
      class="view-mode-button"
      :class="{ 'is-active': viewMode === view.mode }"
      :aria-label="t(view.label)"
      :aria-pressed="viewMode === view.mode"
      @click="setViewMode(view.mode)"
    >
      <span :class="view.icon" aria-hidden="true" />
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
