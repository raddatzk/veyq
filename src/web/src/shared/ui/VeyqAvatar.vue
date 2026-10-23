<script setup lang="ts">
// A round avatar (ADR 0107): a picture, or a short label (an initial, "+3") where there is none. Decorative: the name
// it stands for is said beside it. `error` fires when the picture does not load. Draw a PERSON with
// `core/people/PersonAvatar.vue`, never with this directly. Plain markup, not Reka's Avatar: that one shows the picture
// only after its own preload, so the `<img>` (and its `error`) would arrive a tick late. Sheets reach it by
// `veyq-avatar` and its size class `veyq-avatar--<size>` (styled in `layer.css`).
withDefaults(defineProps<{ image?: string | null; label?: string; size?: 'normal' | 'large' | 'xlarge' }>(), {
  image: null,
  label: undefined,
  size: 'normal',
})
const emit = defineEmits<{ error: [Event] }>()
</script>

<template>
  <div :class="['veyq-avatar', `veyq-avatar--${size}`, { 'veyq-avatar--image': image }]" aria-hidden="true">
    <img v-if="image" :src="image" alt="" @error="emit('error', $event)" />
    <span v-else-if="label" class="veyq-avatar__label">{{ label }}</span>
  </div>
</template>
