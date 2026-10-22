<script setup lang="ts">
// The content of one tab, shown while its `value` is picked (see `VeyqTabs`).
import TabPanel from 'primevue/tabpanel'

defineProps<{ value: string }>()
</script>

<template>
  <TabPanel :value="value" class="veyq-tab-panel">
    <slot />
  </TabPanel>
</template>
