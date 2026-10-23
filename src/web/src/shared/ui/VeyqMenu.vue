<script setup lang="ts">
// A popup menu of actions (ADR 0107), on Reka's DropdownMenu, opened by its owner: `toggle(event)` opens it at the
// element the event came from (`currentTarget`) or closes it, `hide()` closes it. Arrow keys move through the entries,
// Enter or Space picks one, Escape closes it and focus returns to the opener. An entry with `items` opens a submenu
// (to the right, or by ArrowRight). Each entry's `class` lands on it (`veyq-menu__item`; disabled: `aria-disabled`);
// sheets and tests reach the parts by `veyq-menu__item-content` (what a pick clicks), `veyq-menu__link`,
// `veyq-menu__item-icon` and `veyq-menu__item-label` (styled in `layer.css`).
import { computed, ref, shallowRef } from 'vue'
import { DropdownMenuContent, DropdownMenuPortal, DropdownMenuRoot } from 'reka-ui'
import VeyqMenuEntries from './VeyqMenuEntries.vue'
import type { VeyqMenuItem } from './menu'

const props = defineProps<{ items: VeyqMenuItem[] }>()

const open = ref(false)
const anchor = shallowRef<HTMLElement | null>(null)
const shown = computed(() => props.items.filter((i) => i.visible !== false))

function toggle(event: Event) {
  const target = (event.currentTarget ?? event.target) as HTMLElement | null
  if (open.value && target === anchor.value) {
    open.value = false
    return
  }
  anchor.value = target
  open.value = true
}

/** A press on the opener is the opener's own toggle, never an outside dismissal that would reopen it at once. */
function onOutside(event: CustomEvent<{ originalEvent: PointerEvent }>) {
  const target = event.detail?.originalEvent?.target as Node | null
  if (anchor.value && target && anchor.value.contains(target)) event.preventDefault()
}
function onCloseFocus(event: Event) {
  event.preventDefault()
  anchor.value?.focus()
}

defineExpose({ toggle, hide: () => (open.value = false) })
</script>

<template>
  <DropdownMenuRoot v-model:open="open" :modal="false">
    <DropdownMenuPortal>
      <DropdownMenuContent
        v-if="anchor"
        class="veyq-menu"
        :reference="anchor"
        align="start"
        :side-offset="2"
        :collision-padding="8"
        @pointer-down-outside="onOutside"
        @close-auto-focus="onCloseFocus"
      >
        <VeyqMenuEntries :items="shown" />
      </DropdownMenuContent>
    </DropdownMenuPortal>
  </DropdownMenuRoot>
</template>
