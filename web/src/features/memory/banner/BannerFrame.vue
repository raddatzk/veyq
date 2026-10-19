<script setup lang="ts">
// One picture framed as the banner frames it (ADR 0102 decisions 2 and 6): `fill` covers the box and keeps the focal point
// in view (`object-position`, one formula for every aspect); `fit` shows the whole picture, its sides the same picture
// enlarged and blurred (`aria-hidden`; nothing is made or stored for it). Shared by the banner on the page and the dialog's
// two previews, so what the editor sees is what every viewer sees. The box (its aspect, its size) is the caller's.
import { computed } from 'vue'
import { bannerStyle, type BannerFrame, type FocalPoint } from './bannerModel'

const props = defineProps<{ src: string; frame: BannerFrame; focal: FocalPoint; alt?: string }>()
const style = computed(() => (props.frame === 'fill' ? bannerStyle(props.focal) : undefined))
</script>

<template>
  <img v-if="frame === 'fit'" class="banner-frame-backdrop" :src="src" alt="" aria-hidden="true" />
  <img class="banner-frame-media" :class="{ 'is-fit': frame === 'fit' }" :src="src" :alt="alt ?? ''" :style="style" />
</template>

<style scoped>
.banner-frame-media {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.banner-frame-media.is-fit {
  object-fit: contain;
}
/* A static picture: nothing moves, so reduced motion needs nothing from it. */
.banner-frame-backdrop {
  position: absolute;
  inset: -8%;
  width: 116%;
  height: 116%;
  object-fit: cover;
  filter: blur(28px) saturate(1.1);
  opacity: 0.85;
}
</style>
