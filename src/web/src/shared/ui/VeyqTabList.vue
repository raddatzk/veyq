<script setup lang="ts">
// The row of tabs of a `VeyqTabs` (see there). `ariaLabel` names the row for a screen reader.
import { TabsIndicator, TabsList } from 'reka-ui'

defineProps<{ ariaLabel?: string }>()
</script>

<template>
  <div class="veyq-tabs__bar">
    <TabsList class="veyq-tabs__list" :aria-label="ariaLabel">
      <slot />
      <TabsIndicator class="veyq-tabs__ink" />
    </TabsList>
  </div>
</template>
