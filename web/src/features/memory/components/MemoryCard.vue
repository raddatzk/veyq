<script setup lang="ts">
// A Memory tile (cr-0030/0031). Template + classes are the design source's MemoryCard verbatim (so memory.css
// styles it identically); only the data is ours — a decrypted TreeNode. The sealed date range (cr-0067) shows
// under the title, unsealed by the parent from the node's `wrappedMeta` (no media load). A favorite star sits
// top-left over the cover (cr-0031); it toggles on click without opening the Memory (the card itself is the
// open button, so the star is a sibling — no nested button).
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Card from 'primevue/card'
import type { TreeNode } from '../tree'
import type { MemoryDate } from '../memoryDate'
import type { CoverCrop } from '../memoryCover'
import { formatClosedRange, formatDay } from '../memoryDateRange'
import MemoryCoverImage from './MemoryCoverImage.vue'

// `favoritable` (default true) shows the favorite pin; chapters (cr-0062) render the card open-only without it.
// `date` is the node's sealed range (already unsealed by the parent), or null when it carries none. `coverSrc` +
// `coverCrop` are the node's chosen cover (a decrypted thumbnail URL + its crop, resolved by the parent, cr-0072);
// absent ⇒ the placeholder images tile.
const props = withDefaults(
  defineProps<{
    memory: TreeNode
    favorite?: boolean
    favoritable?: boolean
    date?: MemoryDate | null
    coverSrc?: string | null
    coverCrop?: CoverCrop | null
  }>(),
  { favoritable: true, date: null, coverSrc: null, coverCrop: null },
)
defineEmits<{ open: [memory: TreeNode]; toggleFavorite: [memory: TreeNode] }>()

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
      <button
        v-if="favoritable"
        class="memory-card-pin"
        :class="{ 'is-favorite': favorite }"
        type="button"
        :aria-pressed="favorite"
        :aria-label="favorite ? t('memory.unfavorite') : t('memory.favorite')"
        @click.stop="$emit('toggleFavorite', memory)"
      >
        <span class="pi" :class="favorite ? 'pi-heart-fill' : 'pi-heart'" />
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
/* The favorite pin reuses the design's icon-tile language: a soft rounded tile. Inactive = a Surface tile so
   it stays legible over the (accent-tinted) cover; active = the accent-soft tile + filled accent heart, the
   same purple tile the sidebar icons use. */
.memory-card-pin {
  --fav-heart: var(--app-favorite);
  position: absolute;
  top: 0.55rem;
  left: 0.55rem;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 2rem;
  height: 2rem;
  padding: 0;
  border: 1px solid var(--app-line);
  border-radius: 10px;
  background: var(--app-surface);
  color: var(--app-muted);
  cursor: pointer;
  box-shadow: var(--app-shadow-sm);
  transition:
    color 0.15s ease,
    background 0.15s ease,
    border-color 0.15s ease,
    transform 0.1s ease;
}
.memory-card-pin:hover {
  color: var(--app-accent);
  border-color: color-mix(in srgb, var(--app-accent) 28%, var(--app-line));
}
.memory-card-pin:active {
  transform: scale(0.9);
}
.memory-card-pin.is-favorite {
  background: var(--app-accent-soft);
  border-color: color-mix(in srgb, var(--fav-heart) 30%, var(--app-line));
  color: var(--fav-heart);
}
.memory-card-pin .pi {
  font-size: 0.9rem;
}
</style>
