<script setup lang="ts">
// A trail of places from `home` to the current one (ADR 0107). An entry with a `url` is a real link
// (ADR 0107 § Behaviour changed on purpose): a plain click runs its `command` instead of following the link, a
// modified click (new tab, new window) follows it, so a copied or middle-clicked crumb lands where it says. The `item`
// slot draws an entry itself (a dialog's in-place trail draws buttons). Sheets reach it by `veyq-breadcrumb`,
// `veyq-breadcrumb__list` and `veyq-breadcrumb__link` (styled in `layer.css`). With a `dropTarget` its entries take a drag
// where that answers yes (core's drag and drop of nodes onto an ancestor, `core/node/nodeDrag.ts`): the entry under the drag
// is drawn lit (`is-drop-target`) only while it takes it.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import VeyqIcon from './VeyqIcon.vue'
import type { VeyqDropTarget, VeyqMenuItem } from './menu'

const props = defineProps<{ home?: VeyqMenuItem; items: VeyqMenuItem[]; dropTarget?: VeyqDropTarget }>()
defineSlots<{ item?: (scope: { item: VeyqMenuItem }) => unknown }>()

const trail = computed(() => (props.home ? [props.home, ...props.items] : props.items).filter((i) => i.visible !== false))

/** The entry a drag is over that takes it (its index), or null. */
const lit = ref<number | null>(null)
function over(item: VeyqMenuItem, i: number, event: DragEvent) {
  if (!props.dropTarget) return
  if (props.dropTarget.over(item, event)) lit.value = i
  else if (lit.value === i) lit.value = null
}
function leave(i: number, event: DragEvent) {
  const into = event.relatedTarget as Node | null
  if (into && (event.currentTarget as Node).contains(into)) return // onto a child of the entry: still over it
  if (lit.value === i) lit.value = null
}
function drop(item: VeyqMenuItem, event: DragEvent) {
  if (!props.dropTarget) return
  lit.value = null
  props.dropTarget.drop(item, event)
}
// A drag that ends elsewhere, or is given up over an entry, leaves nothing lit.
const unlight = () => (lit.value = null)
onMounted(() => document.addEventListener('dragend', unlight))
onBeforeUnmount(() => document.removeEventListener('dragend', unlight))

function follow(item: VeyqMenuItem, event: MouseEvent) {
  if (!item.command) return
  if (item.url && (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || event.button !== 0)) return
  event.preventDefault()
  item.command(event)
}
</script>

<template>
  <nav class="veyq-breadcrumb">
    <ol class="veyq-breadcrumb__list">
      <template v-for="(item, i) in trail" :key="item.key ?? i">
        <li v-if="i > 0" class="veyq-breadcrumb__separator" aria-hidden="true"><VeyqIcon name="chevron-right" /></li>
        <li
          :class="['veyq-breadcrumb__item', item.class, { 'is-drop-target': lit === i }]"
          @dragenter="over(item, i, $event)"
          @dragover="over(item, i, $event)"
          @dragleave="leave(i, $event)"
          @drop="drop(item, $event)"
        >
          <slot v-if="$slots.item" name="item" :item="item" />
          <a
            v-else
            class="veyq-breadcrumb__link"
            :href="item.url"
            :target="item.target"
            :aria-current="i === trail.length - 1 ? 'page' : undefined"
            @click="follow(item, $event)"
          >
            <VeyqIcon v-if="item.icon" :name="item.icon" class="veyq-breadcrumb__icon" />
            <span class="veyq-breadcrumb__label">{{ item.label }}</span>
          </a>
        </li>
      </template>
    </ol>
  </nav>
</template>
