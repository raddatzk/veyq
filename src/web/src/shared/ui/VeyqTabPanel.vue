<script setup lang="ts">
// The content of one tab, shown while its `value` is picked (see `VeyqTabs`).
import { TabsContent } from 'reka-ui'

defineProps<{ value: string }>()
</script>

<template>
  <TabsContent :value="value" class="veyq-tab-panel">
    <slot />
  </TabsContent>
</template>
