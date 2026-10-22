<script setup lang="ts">
// A round avatar (ADR 0107): a picture, or a short label (an initial, "+3") where there is none. Decorative: the name
// it stands for is said beside it. `error` fires when the picture does not load. Draw a PERSON with
// `core/people/PersonAvatar.vue`, never with this directly. Sheets reach it by `veyq-avatar` and its size class
// `veyq-avatar--<size>`.
import Avatar from 'primevue/avatar'

withDefaults(defineProps<{ image?: string | null; label?: string; size?: 'normal' | 'large' | 'xlarge' }>(), {
  image: null,
  label: undefined,
  size: 'normal',
})
const emit = defineEmits<{ error: [Event] }>()
</script>

<template>
  <Avatar
    :image="image ?? undefined"
    :label="image ? undefined : label"
    shape="circle"
    :size="size === 'normal' ? undefined : size"
    :class="['veyq-avatar', `veyq-avatar--${size}`]"
    aria-hidden="true"
    @error="emit('error', $event)"
  />
</template>
