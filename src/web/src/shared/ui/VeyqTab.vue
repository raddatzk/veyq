<script setup lang="ts">
// One tab of a `VeyqTabList` (see `VeyqTabs`).
import Tab from 'primevue/tab'

defineProps<{ value: string }>()
</script>

<template>
  <Tab :value="value" class="veyq-tab">
    <slot />
  </Tab>
</template>
