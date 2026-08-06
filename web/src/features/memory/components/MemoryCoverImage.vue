<script setup lang="ts">
// en.memory.cover (web) — the pure display of a Memory's chosen cover (uc.memory.cover, cr-0072). Given a
// decrypted source URL + a normalized crop, it renders that ONE crop at ANY container size: the `<img>` sits in an
// `overflow:hidden` box and is expanded + offset by `coverImageStyle`, so a single stored `0..1` crop reads
// identically on the browse card, the detail header, and the map pin. No `src` ⇒ the placeholder — the soft-accent
// `pi pi-images` tile, or a `placeholder` slot the host supplies. Ported component-for-component from the design
// source's `MemoryCoverImage`; only the `src` (a decrypted object URL under K_mem) and the crop model are ours.
import { computed } from 'vue'
import { coverImageStyle, type CoverCrop } from '../memoryCover'

const props = defineProps<{ src?: string | null; alt?: string; crop?: CoverCrop | null }>()

const imageStyle = computed(() => coverImageStyle(props.crop))
</script>

<template>
  <span class="memory-cover-image">
    <img v-if="src" :src="src" :alt="alt ?? ''" :style="imageStyle" draggable="false" />
    <slot v-else name="placeholder">
      <span class="memory-cover-image-ph pi pi-images" />
    </slot>
  </span>
</template>

<style scoped>
/* The crop box: fills its host, hides everything the expanded `<img>` offsets out of frame (design source verbatim). */
.memory-cover-image {
  position: relative;
  display: block;
  width: 100%;
  height: 100%;
  overflow: hidden;
}
.memory-cover-image img {
  position: absolute;
  display: block;
  max-width: none;
}
/* The default placeholder — the soft images tile, filling the box (the same tokens as `.memory-card-cover`). */
.memory-cover-image-ph {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background: var(--app-info-soft);
  color: var(--app-info-text);
  font-size: 1.5rem;
}
</style>
