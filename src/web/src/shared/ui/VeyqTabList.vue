<script setup lang="ts">
// The row of tabs of a `VeyqTabs` (see there). `ariaLabel` names the row for a screen reader.
import TabList from 'primevue/tablist'

const props = defineProps<{ ariaLabel?: string }>()

const PT = {
  root: { class: 'veyq-tabs__bar' },
  tabList: { class: 'veyq-tabs__list', 'aria-label': props.ariaLabel },
  activeBar: { class: 'veyq-tabs__ink' },
}
</script>

<template>
  <TabList :pt="PT">
    <slot />
  </TabList>
</template>
