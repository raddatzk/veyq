<script setup lang="ts">
// The drop boxes of a folder level (cr-files-application F3): a row per `FILES_DROPBOX` child that opens it (its
// `detail`, `DropBoxDetail.vue`), and a "New drop box" action (its `createDialog`). Files' file rows' markup (the
// `veyq-list` rows of `FilesSection.vue`); hidden in map mode like the folders and the files (the mode the level hands in, inbox #404). With none yet, it says
// what a drop box is (inbox #385), as the files section says how to start. The create action is Files' capability
// matrix's (`../capabilities.ts`, inbox #401): not during a pending deletion, never in a guest's identity folder.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LevelSectionEmits, LevelSectionProps } from '../../../core/applications/registry'
import { DROPBOX_KIND } from '../kinds'
import { filesCapabilities } from '../capabilities'
import { targetOf } from '../../../core/access'

const props = defineProps<LevelSectionProps>()
const emit = defineEmits<LevelSectionEmits>()
const { t } = useI18n()

const boxes = computed(() => props.nodes.filter((n) => n.kind === DROPBOX_KIND))
const caps = computed(() =>
  filesCapabilities(props.access, targetOf(props.tree, props.folder)),
)
</script>

<template>
  <section v-if="viewMode !== 'map' && (boxes.length || caps.createDropBox)" class="veyq-section dropbox-section">
    <div class="veyq-section-header">
      <h2>{{ t('files.dropBox.section') }}</h2>
      <span>{{ boxes.length }}</span>
    </div>
    <p v-if="!boxes.length" class="veyq-empty veyq-empty-inline">{{ t('files.dropBox.sectionEmpty') }}</p>
    <ul v-else class="veyq-list">
      <li v-for="box in boxes" :key="box.id" class="veyq-list-row">
        <button type="button" class="dropbox-row-open" :aria-label="t('files.dropBox.open', { name: box.name })" @click="emit('openNode', box)">
          <span class="pi pi-inbox" aria-hidden="true" />
          <span class="veyq-row-main">
            <span class="veyq-row-title">{{ box.name }}</span>
          </span>
        </button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.dropbox-row-open {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  min-width: 0;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.dropbox-row-open:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
  border-radius: var(--app-radius-sm);
}
</style>
