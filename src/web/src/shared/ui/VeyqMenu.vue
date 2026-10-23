<script setup lang="ts">
// A popup menu of actions (ADR 0107), opened by its owner: `toggle(event)` opens it at the element the event came from
// (`currentTarget`) or closes it, `hide()` closes it. An entry with `items` opens a submenu. Each entry's `class` lands
// on its list item (`veyq-menu__item`, `aria-disabled` when disabled); sheets and tests reach the parts by
// `veyq-menu__item-content` (what a pick clicks), `veyq-menu__link`, `veyq-menu__item-icon` and `veyq-menu__item-label`.
import { computed, ref } from 'vue'
import Menu from 'primevue/menu'
import TieredMenu from 'primevue/tieredmenu'
import type { MenuItem } from 'primevue/menuitem'
import type { VeyqMenuItem } from './menu'

const props = defineProps<{ items: VeyqMenuItem[] }>()

function toLibrary(item: VeyqMenuItem): MenuItem {
  return {
    key: item.key,
    label: item.label,
    icon: item.icon ? `pi pi-${item.icon}` : undefined,
    url: item.url,
    target: item.target,
    separator: item.separator,
    disabled: item.disabled,
    visible: item.visible,
    class: item.class,
    badge: item.badge,
    command: item.command ? ({ originalEvent }) => item.command?.(originalEvent) : undefined,
    items: item.items?.map(toLibrary),
  }
}

const model = computed(() => props.items.map(toLibrary))
const tiered = computed(() => props.items.some((i) => i.items?.length))

const PT = {
  item: { class: 'veyq-menu__item' },
  itemContent: { class: 'veyq-menu__item-content' },
  itemLink: { class: 'veyq-menu__link' },
  itemIcon: { class: 'veyq-menu__item-icon' },
  itemLabel: { class: 'veyq-menu__item-label' },
}

const menu = ref<{ toggle: (e: Event) => void; hide: () => void } | null>(null)
defineExpose({
  toggle: (event: Event) => menu.value?.toggle(event),
  hide: () => menu.value?.hide(),
})
</script>

<template>
  <TieredMenu v-if="tiered" ref="menu" class="veyq-menu" :model="model" popup :pt="PT" />
  <Menu v-else ref="menu" class="veyq-menu" :model="model" popup :pt="PT" />
</template>
