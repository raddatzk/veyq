<script setup lang="ts">
// The panels of a `VeyqTabs` (see there).
import TabPanels from 'primevue/tabpanels'
</script>

<template>
  <TabPanels class="veyq-tab-panels">
    <slot />
  </TabPanels>
</template>
