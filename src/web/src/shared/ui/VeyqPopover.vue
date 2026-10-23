<script setup lang="ts">
// A panel that opens beside an element and closes on an outside click or Escape (ADR 0107), on Reka's Popover, opened
// by its owner: `toggle(event)` / `show(event)` at the element the event came from (`currentTarget`), `hide()`. Focus
// returns to that element when it closes. `inPlace` renders it where it is declared instead of at the end of the
// page, so it keeps the tab order. The caller's classes and listeners land on the panel; sheets reach its body by
// `veyq-popover__content` (styled in `layer.css`).
import { ref, shallowRef } from 'vue'
import { PopoverContent, PopoverPortal, PopoverRoot } from 'reka-ui'

defineOptions({ inheritAttrs: false })
defineProps<{ inPlace?: boolean }>()
const emit = defineEmits<{ hide: [] }>()

const open = ref(false)
const anchor = shallowRef<HTMLElement | null>(null)

function show(event: Event) {
  anchor.value = (event.currentTarget ?? event.target) as HTMLElement | null
  open.value = true
}
function toggle(event: Event) {
  const target = (event.currentTarget ?? event.target) as HTMLElement | null
  if (open.value && target === anchor.value) open.value = false
  else show(event)
}
function onOpen(value: boolean) {
  open.value = value
  if (!value) emit('hide')
}
function onOutside(event: CustomEvent<{ originalEvent: PointerEvent }>) {
  const target = event.detail?.originalEvent?.target as Node | null
  if (anchor.value && target && anchor.value.contains(target)) event.preventDefault()
}
function onCloseFocus(event: Event) {
  event.preventDefault()
  anchor.value?.focus()
}

defineExpose({ show, toggle, hide: () => onOpen(false) })
</script>

<template>
  <PopoverRoot :open="open" :modal="false" @update:open="onOpen">
    <PopoverPortal :disabled="inPlace">
      <PopoverContent
        v-if="anchor"
        v-bind="$attrs"
        class="veyq-popover"
        :reference="anchor"
        side="bottom"
        align="start"
        :side-offset="10"
        :collision-padding="8"
        @pointer-down-outside="onOutside"
        @close-auto-focus="onCloseFocus"
      >
        <div class="veyq-popover__content"><slot /></div>
      </PopoverContent>
    </PopoverPortal>
  </PopoverRoot>
</template>
