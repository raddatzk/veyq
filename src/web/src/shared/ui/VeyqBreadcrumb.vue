<script setup lang="ts">
// A trail of places from `home` to the current one (ADR 0107). An entry with a `url` is a real link: its `command`
// runs on a plain click and may prevent the default, a modified click opens the link elsewhere. The `item` slot draws
// an entry itself (a dialog's in-place trail draws buttons). Sheets reach it by `veyq-breadcrumb`,
// `veyq-breadcrumb__list` and `veyq-breadcrumb__link`.
import { computed } from 'vue'
import Breadcrumb from 'primevue/breadcrumb'
import type { MenuItem } from 'primevue/menuitem'
import type { VeyqMenuItem } from './menu'

const props = defineProps<{ home?: VeyqMenuItem; items: VeyqMenuItem[] }>()
defineSlots<{ item?: (scope: { item: VeyqMenuItem }) => unknown }>()

/** The library hands back its own copy of an entry; this keeps ours beside it, for the `item` slot. */
const OURS = Symbol('veyq-item')
type Entry = MenuItem & { [OURS]: VeyqMenuItem }

function toLibrary(item: VeyqMenuItem): Entry {
  return {
    key: item.key,
    label: item.label,
    url: item.url,
    class: item.class,
    command: item.command ? ({ originalEvent }) => item.command?.(originalEvent) : undefined,
    [OURS]: item,
  }
}
const homeEntry = computed(() => (props.home ? toLibrary(props.home) : undefined))
const model = computed(() => props.items.map(toLibrary))

const PT = {
  list: { class: 'veyq-breadcrumb__list' },
  itemLink: { class: 'veyq-breadcrumb__link' },
}
</script>

<template>
  <Breadcrumb class="veyq-breadcrumb" :home="homeEntry" :model="model" :pt="PT">
    <template v-if="$slots.item" #item="{ item }"><slot name="item" :item="(item as Entry)[OURS]" /></template>
  </Breadcrumb>
</template>
