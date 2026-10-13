<script setup lang="ts">
// One person's avatar: today always the letter of their display name; `image` is the spot a profile picture will fill
// once members have one (an `Avatar` image falls back to the letter by itself when it does not load). Decorative: the
// person's name is always printed or named next to it, so the avatar hides from assistive technology.
import Avatar from 'primevue/avatar'

withDefaults(defineProps<{ name: string; image?: string | null; size?: 'normal' | 'large' | 'xlarge' }>(), { image: null, size: 'normal' })
const initialOf = (name: string) => name.trim().charAt(0).toUpperCase() || '?'
</script>

<template>
  <Avatar v-if="image" :image="image" shape="circle" :size="size" aria-hidden="true" />
  <Avatar v-else :label="initialOf(name)" shape="circle" :size="size" aria-hidden="true" />
</template>
