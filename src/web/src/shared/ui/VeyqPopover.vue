<script setup lang="ts">
// A panel that opens beside an element and closes on an outside click or Escape (ADR 0107), opened by its owner:
// `toggle(event)` / `show(event)` at the element the event came from (`currentTarget`), `hide()`. `inPlace` renders it
// where it is declared instead of at the end of the page, so it keeps the tab order. The caller's classes and
// listeners land on the panel; sheets reach its body by `veyq-popover__content`.
import { ref } from 'vue'
import Popover from 'primevue/popover'

defineProps<{ inPlace?: boolean }>()
const emit = defineEmits<{ hide: [] }>()

const PT = { content: { class: 'veyq-popover__content' } }

const pop = ref<{ toggle: (e: Event) => void; show: (e: Event, target?: HTMLElement) => void; hide: () => void } | null>(null)
defineExpose({
  toggle: (event: Event) => pop.value?.toggle(event),
  show: (event: Event) => pop.value?.show(event, (event.currentTarget as HTMLElement | null) ?? undefined),
  hide: () => pop.value?.hide(),
})
</script>

<template>
  <Popover ref="pop" class="veyq-popover" :append-to="inPlace ? 'self' : 'body'" :pt="PT" @hide="emit('hide')">
    <slot />
  </Popover>
</template>
