<script setup lang="ts">
// A Memory's cover as the unified level list draws it (inbox #407): the leading visual core's row item puts in its visual
// box (a tile shows it large, a row small). The cover is the node's chosen image with its crop (`MemoryCoverImage`, the one
// formula every surface uses), or the soft `pi pi-images` placeholder while it has none or is still loading.
import type { CoverCrop } from '../memoryCover'
import MemoryCoverImage from './MemoryCoverImage.vue'

defineProps<{ src: string | null; crop: CoverCrop | null; alt: string }>()
</script>

<template>
  <MemoryCoverImage :src="src" :crop="crop" :alt="alt" />
</template>
