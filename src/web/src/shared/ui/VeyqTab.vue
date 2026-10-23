<script setup lang="ts">
// One tab of a `VeyqTabList` (see `VeyqTabs`).
import { TabsTrigger } from 'reka-ui'

defineProps<{ value: string }>()
</script>

<template>
  <TabsTrigger :value="value" class="veyq-tab">
    <slot />
  </TabsTrigger>
</template>
