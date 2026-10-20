<script setup lang="ts">
// One person's avatar (uc.spaces.set-profile-picture, ADR 0084): their profile picture when they have one, else the letter of
// their display name. The picture comes from the directory's cache by `userId` (an object URL of the picture opened on this
// device; reactive, so it appears and changes without a reload); `image` overrides it (a preview). A picture that does not
// load falls back to the letter by itself. Members only: a share view passes no `userId` and always shows the letter.
// Decorative: the person's name is always printed or named next to it, so the avatar hides from assistive technology.
import { computed, ref, watch } from 'vue'
import Avatar from 'primevue/avatar'
import { avatarUrlOf } from './avatarCache'

const props = withDefaults(
  defineProps<{ name: string; userId?: string | null; image?: string | null; size?: 'normal' | 'large' | 'xlarge' | 'xxl' }>(),
  { userId: null, image: null, size: 'normal' },
)
// PrimeVue's largest step is `xlarge` (4rem); `xxl` (8rem = 128 px, the profile page's picture) is ours, so the 256 px stored
// picture is shown at half its pixels and stays sharp on a 2x screen.
const primeSize = computed(() => (props.size === 'xxl' ? 'xlarge' : props.size))
const initialOf = (name: string) => name.trim().charAt(0).toUpperCase() || '?'

const src = computed(() => props.image ?? avatarUrlOf(props.userId))
// A URL that failed to decode shows the letter (E3: never a broken image); a new URL gets a fresh try.
const broken = ref<string | null>(null)
watch(src, () => (broken.value = null))
const shown = computed(() => (src.value && src.value !== broken.value ? src.value : null))
</script>

<template>
  <Avatar v-if="shown" :image="shown" shape="circle" :size="primeSize" :class="{ 'is-xxl': size === 'xxl' }" aria-hidden="true" data-avatar-image @error="broken = shown" />
  <Avatar v-else :label="initialOf(name)" shape="circle" :size="primeSize" :class="{ 'is-xxl': size === 'xxl' }" aria-hidden="true" />
</template>

<style scoped>
/* An avatar keeps its size in a flex row: a shrinking circle becomes an oval. */
.p-avatar {
  flex: none;
}
.p-avatar.is-xxl {
  width: 8rem;
  height: 8rem;
  font-size: 4rem;
}
</style>
