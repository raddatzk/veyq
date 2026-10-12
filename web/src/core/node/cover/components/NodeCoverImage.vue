<script setup lang="ts">
// A node's cover as every core surface draws it (inbox #411; Memories' `MemoryCoverImage` since cr-0072, made kind-blind):
// the decrypted image in an `overflow:hidden` box, expanded and offset by `coverImageStyle` so ONE stored normalized crop reads
// identically at any container size - a level tile, a list row, the node header, the sidebar's Favorites tile. With no `src`
// it draws the kind's icon (`icon`, a PrimeIcons class) - the default look of a node without a cover or while it loads.
import { computed } from 'vue'
import { coverImageStyle, type CoverCrop } from '../coverModel'

const props = defineProps<{ src?: string | null; alt?: string; crop?: CoverCrop | null; icon?: string }>()
const imageStyle = computed(() => coverImageStyle(props.crop))
</script>

<template>
  <span class="veyq-node-cover">
    <img v-if="src" :src="src" :alt="alt ?? ''" :style="imageStyle" draggable="false" />
    <span v-else-if="icon" class="veyq-node-cover-icon" :class="icon" aria-hidden="true" />
  </span>
</template>

<style scoped>
/* The crop box: fills its host and hides what the expanded `<img>` offsets out of frame. */
.veyq-node-cover {
  position: relative;
  display: block;
  width: 100%;
  height: 100%;
  overflow: hidden;
}
.veyq-node-cover img {
  position: absolute;
  display: block;
  max-width: none;
}
.veyq-node-cover-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
}
</style>
