<script setup lang="ts">
// The view-mode switch (cr-0063; core's since inbox #370): one icon button per presentation in `views`, over the
// `useViewMode` preference (persisted, per account / one for share guests) — core's level-view toolbar hands it the presentations on screen
// (`levelViews.ts`), Memories' chapters grid its grid and list. Every place drives the one value. The button pressed is
// the mode shown there — the stored one where `views` offers it, else the grid (`effectiveViewMode`, inbox #404) — so a
// stored map never leaves a grid-and-list switch with nothing pressed.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useViewMode, setViewMode } from '../../shared/state/useViewMode'
import { effectiveViewMode, type LevelViewOption } from './levelViews'
import VeyqIcon from '../../shared/ui/VeyqIcon.vue'
import VeyqTooltip from '../../shared/ui/VeyqTooltip.vue'

const props = defineProps<{ views: readonly LevelViewOption[] }>()

const { viewMode } = useViewMode()
const active = computed(() => effectiveViewMode(viewMode.value, props.views))
const { t } = useI18n()
</script>

<template>
  <div class="view-mode-toggle" role="group" :aria-label="t('node.viewMode')">
    <VeyqTooltip v-for="view in views" :key="view.mode" :text="t(view.label)">
      <button
        type="button"
        class="view-mode-button"
        :class="{ 'is-active': active === view.mode }"
        :aria-label="t(view.label)"
        :aria-pressed="active === view.mode"
        @click="setViewMode(view.mode)"
      >
        <VeyqIcon :name="view.icon" />
      </button>
    </VeyqTooltip>
  </div>
</template>

<style scoped>
/* In the level's head the switch is that head's one control height (`--veyq-level-control-height`, `styles/ui.css`), its
   buttons filling it; elsewhere its own size. */
.view-mode-toggle {
  display: inline-flex;
  box-sizing: border-box;
  height: var(--veyq-level-control-height, auto);
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
  min-height: calc(var(--veyq-level-control-height, calc(2.3rem + 2px)) - 0.3rem - 2px);
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
.view-mode-button .veyq-icon {
  font-size: 0.9rem;
}
</style>
