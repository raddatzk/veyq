<script setup lang="ts">
// A popup menu of actions (ADR 0107), on Reka's DropdownMenu, opened by its owner: `toggle(event)` opens it at the
// element the event came from (`currentTarget`) or closes it, `hide()` closes it. Arrow keys move through the entries,
// Enter or Space picks one, Escape closes it and focus returns to the opener. A pick closes it and gives focus back to the
// opener BEFORE the entry's command runs, so a dialog the command opens records the opener as where its own close gives
// focus back, never the entry the pick removes (the WAI-ARIA menu button pattern). An entry with `items` opens a submenu
// (to the right, or by ArrowRight). Each entry's `class` lands on it (`veyq-menu__item`; disabled: `aria-disabled`);
// sheets and tests reach the parts by `veyq-menu__item-content` (what a pick clicks), `veyq-menu__link`,
// `veyq-menu__item-icon` and `veyq-menu__item-label` (styled in `layer.css`).
import { computed, provide, ref, shallowRef } from 'vue'
import { DropdownMenuContent, DropdownMenuPortal, DropdownMenuRoot } from 'reka-ui'
import VeyqMenuEntries from './VeyqMenuEntries.vue'
import { MENU_PICK, type VeyqMenuItem } from './menu'

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
/** Focus goes back to the opener when the menu closes with focus in it (Escape) or nowhere (its entry removed); focus a
 *  pick's command moved on — a dialog's field — stays where it is. */
function onCloseFocus(event: Event) {
  event.preventDefault()
  const at = document.activeElement
  const menu = event.target instanceof Node ? event.target : null
  if (!at || at === document.body || menu?.contains(at)) anchor.value?.focus()
}
/** A pick: close, give focus back to the opener, then run the entry's command (see the head of this file). */
provide(MENU_PICK, (run) => {
  open.value = false
  anchor.value?.focus()
  run()
})

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
