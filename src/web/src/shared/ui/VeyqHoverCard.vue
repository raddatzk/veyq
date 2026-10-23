<script setup lang="ts">
// A card that opens while the pointer rests on its trigger or the trigger has focus, and on a tap (ADR 0107), on
// Reka's HoverCard: `openDelay` and `closeDelay` keep crossing the gap between the trigger and the card from closing
// it. The `trigger` slot is the element itself (it renders no wrapper); the default slot is the card. `v-model:open`
// lets the owner open or close it too (a click, Escape). `inPlace` keeps the card where it is declared, in the tab
// order. Sheets reach it by `veyq-hover-card` (styled in `layer.css`).
import { HoverCardContent, HoverCardPortal, HoverCardRoot, HoverCardTrigger } from 'reka-ui'

defineOptions({ inheritAttrs: false })
const open = defineModel<boolean>('open', { default: false })
withDefaults(defineProps<{ openDelay?: number; closeDelay?: number; inPlace?: boolean }>(), {
  openDelay: 150,
  closeDelay: 250,
  inPlace: false,
})
</script>

<template>
  <HoverCardRoot v-model:open="open" :open-delay="openDelay" :close-delay="closeDelay" enable-touch>
    <HoverCardTrigger as-child><slot name="trigger" /></HoverCardTrigger>
    <HoverCardPortal :disabled="inPlace">
      <HoverCardContent v-bind="$attrs" class="veyq-hover-card" side="bottom" align="start" :side-offset="10" :collision-padding="8">
        <div class="veyq-popover__content"><slot /></div>
      </HoverCardContent>
    </HoverCardPortal>
  </HoverCardRoot>
</template>
