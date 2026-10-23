<script setup lang="ts">
// A short text shown below a control on hover and keyboard focus (ADR 0107), on Reka's Tooltip:
// `<VeyqTooltip :text="…"><button …/></VeyqTooltip>`. It wraps exactly one element and renders no element of its own
// around it, so it changes no layout. The text is a hint, never the control's only name: an icon-only control still
// carries its `aria-label`. Styled in `layer.css` (`veyq-tooltip`).
import { TooltipArrow, TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger } from 'reka-ui'

defineProps<{ text?: string }>()
</script>

<template>
  <TooltipProvider :delay-duration="0" :skip-delay-duration="0">
    <TooltipRoot :disabled="!text">
      <TooltipTrigger as-child><slot /></TooltipTrigger>
      <TooltipPortal>
        <TooltipContent class="veyq-tooltip" side="bottom" :side-offset="4" :collision-padding="8">
          <span class="veyq-tooltip__text">{{ text }}</span>
          <TooltipArrow class="veyq-tooltip__arrow" :width="8" :height="4" />
        </TooltipContent>
      </TooltipPortal>
    </TooltipRoot>
  </TooltipProvider>
</template>
