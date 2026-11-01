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
// **Core's actions come centrally** (owner 2026-11-01, ADR 0110): the page names the `node` the header heads, and the header
// asks the view around it (`../coreNodeActions.ts`: core's node page `NodePage.vue`, the level `LevelView.vue`) for core's
// own actions of that node - the heart, the cover, "Copy to…", the download, Report - each gated by its core rights
// function, and merges them with the application's (`mergeHeaderActions`). No page builds or forwards one, so none can
// leave Report out. A kind's `order` may still name a core id for its priority.
//
// **Two layouts, picked by the header's own width** (ADR 0110 decisions 1 and 8): `headerLayout` answers `wide` while the
// leading visual's wide width, the name's minimum, the floor's buttons and the ⋯ fit in one row, else `compact`. Its inputs
// are the header's width and sizes no layout changes — the leading visual's `--veyq-node-header-leading-wide` and the
// header's `--veyq-node-header-name-min` (registered lengths, `styles/ui.css`), the gaps, one button — never the name or
// the layout in force, so it cannot flip. The answer is the header's `is-compact` class and the `leading` slot's
// `compact`, so an application's leading visual sizes itself from it without core importing the application.
//
// **As many as fit, the rest in one menu** (ADR 0110): the actions that may be buttons are buttons in `order` as far as
// the row has room — measured on mount and on every resize, `fitHeaderActions` — and the rest are entries of one ⋯ menu,
// whose last group is the menu-only actions (report, then delete) behind a separator. Unmeasured (no `ResizeObserver`, a
// width of 0: a unit test, a hidden tab) the header is wide and every action that may be a button is one. Compact, the
// actions have a row of their own, the whole header wide — the title row then draws no box of its own (`display:
// contents`) and the actions' box is the room. Wide, the name comes first: the room is the title row less the name's
// natural width (its `scrollWidth`, one line) and the gap, but never less than the floor's (`headerFloor`, the kind's
// first two), and the name is shortened only below that.
//
// The title is its page's one `h1` (owner 2026-10-24, `docs/crosscutting/ui-web-styleguide.md` § Heading levels): every
// header opens the page of the node it names — a Memory's, a file's, a drop box's, and a folder's level view.
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useSlots, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import VeyqMenu from '../../../shared/ui/VeyqMenu.vue'
import VeyqTooltip from '../../../shared/ui/VeyqTooltip.vue'
import type { VeyqIconName } from '../../../shared/ui/icons'
import type { VeyqMenuItem } from '../../../shared/ui/menu'
import { actionMenuItems } from '../actionMenu'
import {
  fitHeaderActions,
  headerFloor,
  headerLayout,
  mergeHeaderActions,
  orderHeaderActions,
  partitionHeaderActions,
  type NodeHeaderAction,
} from '../nodeHeader'
import { useCoreHeaderActions } from '../coreNodeActions'
import type { TreeNode } from '../tree'

const props = withDefaults(
  defineProps<{
    title: string
    /** The node this header heads, whose core actions it shows - required, so a page says which (null only for the
     *  space's top level, which is no node and has none). */
    node: TreeNode | null
    /** The icon of the leading visual when no `leading` slot is given. */
    icon?: VeyqIconName
    subtitle?: string
    actions?: readonly NodeHeaderAction[]
    /** The kind's priority: the ids of the actions that come first, in that order (core's included); an unlisted one follows. */
    order?: readonly string[]
  }>(),
  { icon: undefined, subtitle: undefined, actions: () => [], order: () => [] },
)
const slots = useSlots()
const { t } = useI18n()
const coreActions = useCoreHeaderActions(() => props.node)
const parts = computed(() => partitionHeaderActions(orderHeaderActions(mergeHeaderActions(coreActions.value, props.actions).filter((a) => a.shown), props.order)))
const hasLeading = computed(() => !!slots.leading || !!props.icon)

const floor = computed(() => headerFloor(parts.value.buttons.length))

// The layout answer (wide until measured) and what the fit weighs, measured from the rendered row (all zero until measured).
const compact = ref(false)
const measured = ref({ rowWidth: 0, buttonWidth: 0, gap: 0, moreWidth: 0 })
const fit = computed(() =>
  fitHeaderActions({ count: parts.value.buttons.length, menuOnly: parts.value.menuOnly.length > 0, floor: compact.value ? 0 : floor.value, ...measured.value }),
)
const buttons = computed(() => parts.value.buttons.slice(0, fit.value))
const menuItems = computed<VeyqMenuItem[]>(() => actionMenuItems([parts.value.buttons.slice(fit.value), parts.value.menuOnly], 'veyq-node-header'))
const menu = ref<InstanceType<typeof VeyqMenu> | null>(null)

const header = ref<HTMLElement | null>(null)
const titleRow = ref<HTMLElement | null>(null)
const titleEl = ref<HTMLElement | null>(null)
const actionsEl = ref<HTMLElement | null>(null)

// The header's width the drawn layout was measured at (`data-layout-width`): until a measurement has run after a resize it
// names the old width, so a reader — a browser spec choosing between a button and the ⋯ — can tell a settled header from one
// whose ResizeObserver frame is still pending. Unset until the first measurement.
const layoutWidth = ref<number | null>(null)

const px = (v: string) => Number.parseFloat(v) || 0
async function measure() {
  const head = header.value
  const row = titleRow.value
  const title = titleEl.value
  if (!head || !row || !title) return
  const headerWidth = head.clientWidth
  const actions = actionsEl.value
  // One button stands for all (icon-only, one size); the ⋯ is the same kind of button, so it stands in where none is drawn.
  const button = actions?.querySelector<HTMLElement>('.veyq-node-header-action') ?? actions?.querySelector<HTMLElement>('.veyq-node-header-more') ?? null
  const more = actions?.querySelector<HTMLElement>('.veyq-node-header-more') ?? button
  const headStyle = getComputedStyle(head)
  const rowGap = px(getComputedStyle(row).columnGap)
  const gap = actions ? px(getComputedStyle(actions).columnGap) : 0
  // Layout widths (`clientWidth`, `offsetWidth`, `scrollWidth`), whole pixels: 0 without a layout, which is what unmeasured
  // means — and a suite that stubs `getBoundingClientRect` for a gallery's geometry does not turn it into a measured header.
  const buttonWidth = button?.offsetWidth ?? 0
  const moreWidth = more?.offsetWidth ?? 0
  // The leading visual's WIDE width, whatever size it has now: reading its current box would flip the answer back (64 px).
  const visual = head.querySelector('.veyq-node-header-leading')?.firstElementChild
  const leadingWide = visual ? px(getComputedStyle(visual).getPropertyValue('--veyq-node-header-leading-wide')) : 0
  const layoutMore = parts.value.buttons.length > floor.value || parts.value.menuOnly.length > 0
  const layout = headerLayout({
    headerWidth,
    leadingWide,
    leadingGap: px(headStyle.columnGap),
    nameMin: px(headStyle.getPropertyValue('--veyq-node-header-name-min')),
    rowGap,
    floor: floor.value,
    more: layoutMore,
    buttonWidth,
    gap,
    moreWidth,
  })
  // The layout first, then the fit of that layout's row, in the same frame (a microtask, before the paint).
  if (compact.value !== (layout === 'compact')) {
    compact.value = layout === 'compact'
    await nextTick()
  }
  if (!actions) {
    layoutWidth.value = headerWidth
    return
  }
  // Wide, the room is never less than the floor's (its buttons and the ⋯ beside them): a long name is shortened instead. A
  // pixel of slack on the name: `scrollWidth` rounds the text's width, and a button that overflows it would shorten the name.
  const floorItems = floor.value + (layoutMore ? 1 : 0)
  const floorWidth = floor.value * buttonWidth + (layoutMore ? moreWidth : 0) + Math.max(0, floorItems - 1) * gap
  const rowWidth = compact.value ? actions.clientWidth : Math.max(floorWidth, row.clientWidth - (title.scrollWidth + 1) - rowGap)
  measured.value = { rowWidth: Math.max(0, rowWidth), buttonWidth, gap, moreWidth }
  layoutWidth.value = headerWidth // with the fit, so both reach the DOM in one patch
}

// Measured before the first paint (a mount runs before it), then again on every change of the header's or the name's size
// and on a new name, one measurement per frame. The header's width is the only input of the layout; the name's box changes
// with a rename or a late web font, which moves the wide row's fit. Neither depends on how many buttons are drawn, so this
// settles.
let observer: ResizeObserver | null = null
let frame = 0
function schedule() {
  if (frame || typeof requestAnimationFrame === 'undefined') return
  frame = requestAnimationFrame(() => {
    frame = 0
    void measure()
  })
}
watch(() => props.title, schedule)
onMounted(() => {
  void measure()
  if (typeof ResizeObserver === 'undefined' || !header.value) return
  observer = new ResizeObserver(schedule)
  observer.observe(header.value)
  if (titleEl.value) observer.observe(titleEl.value)
})
onBeforeUnmount(() => {
  observer?.disconnect()
  if (frame) cancelAnimationFrame(frame)
})
</script>

<template>
  <header ref="header" class="veyq-node-header" :class="{ 'has-visual': !!slots.leading, 'has-leading': hasLeading, 'is-compact': compact }" :data-layout-width="layoutWidth ?? undefined">
    <div v-if="hasLeading" class="veyq-node-header-leading">
      <slot name="leading" :compact="compact"><VeyqIcon v-if="icon" :name="icon" class="veyq-node-header-icon" /></slot>
    </div>
    <div class="veyq-node-header-body">
      <div ref="titleRow" class="veyq-node-header-titlerow">
        <h1 ref="titleEl" class="veyq-node-header-title" :title="title">{{ title }}</h1>
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
