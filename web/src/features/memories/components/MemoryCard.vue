<script setup lang="ts">
// A chapter tile (cr-0030). Template + classes are the design source's MemoryCard verbatim (so memory.css
// styles it identically); only the data is ours — a decrypted TreeNode. The sealed date range (cr-0067) shows
// under the title, unsealed by the parent from the node's `wrappedMeta` (no media load). The card is open-only: the
// favorite heart is core's action (inbox #409) and a chapter's lives in its own header.
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Card from 'primevue/card'
import type { TreeNode } from '../../../core/node/tree'
import type { MemoryDate } from '../memoryDate'
import type { CoverCrop } from '../memoryCover'
import { formatClosedRange, formatDay } from '../memoryDateRange'
import MemoryCoverImage from './MemoryCoverImage.vue'

// `date` is the node's sealed range (already unsealed by the parent), or null when it carries none. `coverSrc` +
// `coverCrop` are the node's chosen cover (a decrypted thumbnail URL + its crop, resolved by the parent, cr-0072);
// absent ⇒ the placeholder images tile.
const props = withDefaults(
  defineProps<{
    memory: TreeNode
    date?: MemoryDate | null
    coverSrc?: string | null
    coverCrop?: CoverCrop | null
  }>(),
  { date: null, coverSrc: null, coverCrop: null },
)
defineEmits<{ open: [memory: TreeNode] }>()

const { t, locale } = useI18n()

const dateLabel = computed(() => {
  const d = props.date
  if (!d) return ''
  if (d.mode === 'ongoing') return d.from ? t('memory.date.since', { day: formatDay(d.from, locale.value) }) : ''
  return formatClosedRange(d.from, d.to, locale.value)
})
</script>

<template>
  <Card class="veyq-card veyq-card-interactive memory-card">
    <template #content>
      <button class="memory-card-main" type="button" @click="$emit('open', memory)">
        <span class="memory-card-cover">
          <MemoryCoverImage :src="coverSrc" :crop="coverCrop" :alt="memory.name">
            <template #placeholder>
              <span class="memory-card-icon pi pi-images" />
            </template>
          </MemoryCoverImage>
        </span>
        <span class="memory-card-text">
          <strong>{{ memory.name }}</strong>
          <small v-if="dateLabel" class="memory-card-date">{{ dateLabel }}</small>
        </span>
      </button>
    </template>
  </Card>
</template>

<style scoped>
.memory-card :deep(.p-card-content) {
  position: relative;
}
/* The sealed date range under the title (cr-0067) — muted, single line, ellipsized. */
.memory-card-date {
  display: block;
  margin-top: 0.15rem;
  color: var(--app-muted);
  font-size: 0.8rem;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
