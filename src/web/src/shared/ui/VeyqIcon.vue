<script setup lang="ts">
// The one way a screen draws an icon (ADR 0107 decision 5). Decorative by default (`aria-hidden`): the control that
// carries it owns the accessible name. `spin` turns it, for a busy state.
import type { VeyqIconName } from './icons'

defineProps<{
  name: VeyqIconName
  spin?: boolean
}>()
</script>

<template>
  <span :class="['veyq-icon', 'pi', `pi-${name}`, { 'pi-spin': spin }]" aria-hidden="true" />
</template>
