<script setup lang="ts">
// The header of every node page and level (owner design 2026-10-12, inbox #406): ONE core component, so a folder, a file,
// a drop box and a Memory open the same way. A LEADING VISUAL (the `leading` slot — an application's cover image — or, by
// default, the kind's `icon`) beside the NAME (`title`, at most two lines, optionally a `subtitle` and a `meta` slot), and
// the ACTIONS (`NodeHeaderAction`, `../nodeHeader.ts`). The application feeds data, its rights answers and its kind's
// priority (`order`); core owns the look, the spacing, the keyboard (every button a real, labelled `<button>`, the ⋯ the
// layer's menu) and the dark mode (`styles/ui.css` tokens). It decides nothing about what is offered: an action not
// `shown` is not rendered (fail closed is the caller's rights function), and a guest sees this very header with fewer
// actions (inbox #401).
//
// **As many as fit, the rest in one menu** (ADR 0110): the actions that may be buttons are buttons in `order` as far as
// the row has room — measured on mount and on every resize, `fitHeaderActions` — and the rest are entries of one ⋯ menu,
// whose last group is the menu-only actions (report, then delete) behind a separator. Unmeasured (no `ResizeObserver`, a
// width of 0: a unit test, a hidden tab) every action that may be a button is one. Where the row is depends on the width
// (`ui.css`): on a phone the actions have a row of their own, the whole header wide — the title row then draws no box of
// its own (`display: contents`) and the actions' box is the room; wider, they sit at the right end of the title row and
// the room is that row less the title's `flex-basis` (its minimum) and the gap, both read from the computed style.
//
// `level` is the heading level of the title: a node's own page is the page's `h1`, a folder's header inside the level view `h2`.
import { computed, onBeforeUnmount, onMounted, ref, useSlots } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import VeyqMenu from '../../../shared/ui/VeyqMenu.vue'
import VeyqTooltip from '../../../shared/ui/VeyqTooltip.vue'
import type { VeyqIconName } from '../../../shared/ui/icons'
import type { VeyqMenuItem } from '../../../shared/ui/menu'
import { actionMenuItems } from '../actionMenu'
import { fitHeaderActions, mergeHeaderActions, orderHeaderActions, partitionHeaderActions, type CoreHeaderAction, type NodeHeaderAction } from '../nodeHeader'

const props = withDefaults(
  defineProps<{
    title: string
    /** The icon of the leading visual when no `leading` slot is given. */
    icon?: VeyqIconName
    subtitle?: string
    actions?: readonly NodeHeaderAction[]
    /** Core's own actions next to the application's (the favorite heart, the cover…), each gated by a core rights function. */
    coreActions?: readonly CoreHeaderAction[]
    /** The kind's priority: the ids of the actions that come first, in that order (core's included); an unlisted one follows. */
    order?: readonly string[]
    level?: 1 | 2
  }>(),
  { icon: undefined, subtitle: undefined, actions: () => [], coreActions: () => [], order: () => [], level: 2 },
)
const slots = useSlots()
const { t } = useI18n()
const parts = computed(() => partitionHeaderActions(orderHeaderActions(mergeHeaderActions(props.coreActions, props.actions).filter((a) => a.shown !== false), props.order)))
const hasLeading = computed(() => !!slots.leading || !!props.icon)

// What the fit weighs, measured from the rendered row (all zero until measured).
const measured = ref({ rowWidth: 0, buttonWidth: 0, gap: 0, moreWidth: 0 })
const fit = computed(() => fitHeaderActions({ count: parts.value.buttons.length, menuOnly: parts.value.menuOnly.length > 0, ...measured.value }))
const buttons = computed(() => parts.value.buttons.slice(0, fit.value))
const menuItems = computed<VeyqMenuItem[]>(() => actionMenuItems([parts.value.buttons.slice(fit.value), parts.value.menuOnly], 'veyq-node-header'))
const menu = ref<InstanceType<typeof VeyqMenu> | null>(null)

const header = ref<HTMLElement | null>(null)
const titleRow = ref<HTMLElement | null>(null)
const titleEl = ref<HTMLElement | null>(null)
const actionsEl = ref<HTMLElement | null>(null)

const px = (v: string) => Number.parseFloat(v) || 0
function measure() {
  const row = titleRow.value
  const actions = actionsEl.value
  const title = titleEl.value
  if (!row || !actions || !title) return
  // One button stands for all (icon-only, one size); the ⋯ is the same kind of button, so it stands in where none is drawn.
  const button = actions.querySelector<HTMLElement>('.veyq-node-header-action') ?? actions.querySelector<HTMLElement>('.veyq-node-header-more')
  const more = actions.querySelector<HTMLElement>('.veyq-node-header-more') ?? button
  const rowStyle = getComputedStyle(row)
  // Layout widths (`clientWidth`, `offsetWidth`), whole pixels: 0 without a layout, which is what unmeasured means — and a
  // suite that stubs `getBoundingClientRect` for a gallery's geometry does not turn it into a measured header.
  const rowWidth = rowStyle.display === 'contents' ? actions.clientWidth : row.clientWidth - px(getComputedStyle(title).flexBasis) - px(rowStyle.columnGap)
  measured.value = {
    rowWidth: Math.max(0, rowWidth),
    buttonWidth: button?.offsetWidth ?? 0,
    gap: px(getComputedStyle(actions).columnGap),
    moreWidth: more?.offsetWidth ?? 0,
  }
}

// Measured before the first paint (a mount runs before it), then again on every change of the header's size, one
// measurement per frame; the header's width does not depend on how many buttons it draws, so this settles.
let observer: ResizeObserver | null = null
let frame = 0
onMounted(() => {
  measure()
  if (typeof ResizeObserver === 'undefined' || !header.value) return
  observer = new ResizeObserver(() => {
    if (frame) return
    frame = requestAnimationFrame(() => {
      frame = 0
      measure()
    })
  })
  observer.observe(header.value)
})
onBeforeUnmount(() => {
  observer?.disconnect()
  if (frame) cancelAnimationFrame(frame)
})
</script>

<template>
  <header ref="header" class="veyq-node-header" :class="{ 'has-visual': !!slots.leading, 'has-leading': hasLeading }">
    <div v-if="hasLeading" class="veyq-node-header-leading">
      <slot name="leading"><VeyqIcon v-if="icon" :name="icon" class="veyq-node-header-icon" /></slot>
    </div>
    <div class="veyq-node-header-body">
      <div ref="titleRow" class="veyq-node-header-titlerow">
        <component :is="`h${level}`" ref="titleEl" class="veyq-node-header-title" :title="title">{{ title }}</component>
        <div v-if="parts.buttons.length || parts.menuOnly.length" ref="actionsEl" class="veyq-node-header-actions">
          <VeyqTooltip v-for="a in buttons" :key="a.id" :text="a.label">
            <VeyqButton
              class="veyq-icon-button veyq-prime-icon-button veyq-node-header-action"
              :class="[a.class, { 'is-pressed': a.pressed === true }]"
              :data-action="a.id"
              :icon="a.icon"
              :aria-label="a.label"
              :aria-pressed="a.pressed === undefined ? undefined : a.pressed"
              :variant="a.severity === 'primary' ? 'primary' : 'secondary'"
              :appearance="a.severity === 'primary' ? 'filled' : 'outlined'"
              size="small"
              :disabled="a.disabled"
              @click="a.onClick()"
            />
          </VeyqTooltip>
          <template v-if="menuItems.length">
            <VeyqTooltip :text="t('node.rowMore', { name: title })">
              <VeyqButton
                class="veyq-icon-button veyq-prime-icon-button veyq-node-header-more"
                icon="ellipsis-h"
                :aria-label="t('node.rowMore', { name: title })"
                aria-haspopup="menu"
                variant="secondary"
                appearance="outlined"
                size="small"
                @click="menu?.toggle($event)"
              />
            </VeyqTooltip>
            <VeyqMenu ref="menu" :items="menuItems" />
          </template>
        </div>
      </div>
      <p v-if="subtitle" class="veyq-node-header-subtitle">{{ subtitle }}</p>
      <slot name="meta" />
    </div>
  </header>
</template>
