<script setup lang="ts">
// Tabs (ADR 0107), on Reka's Tabs: `VeyqTabs` holds the picked tab's value, `VeyqTabList` the row of `VeyqTab`s (arrow
// keys move between them), and each `VeyqTabPanel` the content shown while its value is picked (inside one
// `VeyqTabPanels`). A row of tabs with no panels is a filter. Sheets reach the parts by `veyq-tabs`, `veyq-tabs__bar`,
// `veyq-tabs__list`, `veyq-tabs__ink`, `veyq-tab` (the picked one carries `aria-selected="true"`), `veyq-tab-panels`
// and `veyq-tab-panel` (styled in `layer.css`).
import { TabsRoot } from 'reka-ui'

const value = defineModel<string>('value', { required: true })
</script>

<template>
  <TabsRoot v-model="value" class="veyq-tabs">
    <slot />
  </TabsRoot>
</template>
