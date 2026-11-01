<script setup lang="ts">
// One picture framed as the banner frames it (ADR 0102 decisions 2 and 6): the WHOLE picture, never
// cropped, contained in the box; where it does not fill the box, above and below or left and right, the same picture
// enlarged and blurred (`aria-hidden`; nothing is made or stored for it). Shared by the banner on the page and the
// dialog's two previews, so what the editor sees is what every viewer sees. The box (its aspect, its size) is the caller's.
defineProps<{ src: string }>()
</script>

<template>
  <img class="banner-frame-backdrop" :src="src" alt="" aria-hidden="true" />
  <img class="banner-frame-media" :src="src" alt="" />
</template>

<style scoped>
.banner-frame-media {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
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
