<script setup lang="ts">
// Tabs (ADR 0107): `VeyqTabs` holds the picked tab's value, `VeyqTabList` the row (`veyq-tabs__bar`, its scroller `veyq-tabs__list`) of `VeyqTab`s, and each
// `VeyqTabPanel` the content shown while its value is picked (inside one `VeyqTabPanels`). A row of tabs with no
// panels is a filter. Sheets reach the parts by `veyq-tabs`, `veyq-tabs__list`, `veyq-tabs__ink`, `veyq-tab` (the
// picked one carries `aria-selected="true"`), `veyq-tab-panels` and `veyq-tab-panel`.
import Tabs from 'primevue/tabs'

const value = defineModel<string>('value', { required: true })
</script>

<template>
  <Tabs v-model:value="value" class="veyq-tabs">
    <slot />
  </Tabs>
</template>
