<script setup lang="ts">
// The header of every node page and level (owner design 2026-10-12, inbox #406): ONE core component, so a folder, a file,
// a drop box, a Memory and a Pot open the same way. **Core draws it, never an application** (owner 2026-11-03, ADR 0110
// decision 10): the folder level (`../LevelView.vue`) and core's node page (`NodePage.vue`) mount it from the kind's
// descriptor - its `icon`, whether it takes a `cover`, its priority `order` - and from what the page's detail contributes as
// data (`../nodeHeaderContribution.ts`: a subtitle, its actions, the rename in place, its facts, and a banner the page draws
// above). A LEADING
// VISUAL beside the NAME (`title`, then the `subtitle` and the `facts`), and the ACTIONS (`NodeHeaderAction`,
// `../nodeHeader.ts`). Core owns the look, the spacing, the keyboard (every button a real, labelled `<button>`, the ⋯ the
// layer's menu) and the dark mode (`styles/ui.css` tokens). It decides nothing about what is offered: an action not `shown`
// is not rendered (fail closed is the caller's rights function), and a guest sees this very header with fewer actions
// (inbox #401).
//
// **The leading visual is core's** (inbox #411) and **as tall as the rows beside it** (owner 2026-11-04): core knows which rows
// it draws - the title row, the subtitle, each row of chips, of people, of text (`headerRowCounts`) - and each kind of row has
// a fixed block size (`styles/ui.css`, `--veyq-node-header-row-*`); the header hands the counts to that sum as custom
// properties, so the visual's height is computed from the data, never measured. **It is always 16:9** (owner 2026-11-04): a
// kind that takes a cover shows the node's cover at that height - or, without one, the empty 16:9 frame with the kind's icon
// in its middle; a kind without a cover (a file) that same frame, never a button. Compact, the one small size (64 × 36 px). Where whoever
// looks may set the cover (`coverRights`, through the view's cover, `../coreNodeActions.ts`) the cover or the empty frame is
// the button that opens core's cover dialog, with its hint - always on an empty wide frame, else on hover and keyboard
// focus; otherwise a plain frame. The header offers no cover action besides it.
//
// **What is editable at its place has no action** (owner 2026-11-04, ADR 0110 decision 11): the NAME is edited in place where
// the page contributes a `rename` (`NodeHeaderRename`: the rights answer and the page's door) - the `h1` holds a real button
// named "Rename “…”"; a click makes it a field pre-selected as core's name dialog selects (`renameSelectionEnd`), Enter
// writes through the kind's own rename write, Esc cancels, the check and the wording of a refusal are the dialog's
// (`../nodeName.ts`), shown under the field. Blur writes a changed name as Enter does (a click elsewhere keeps what was typed)
// - but not while the window itself lost focus, and not a name the last write just refused; an unchanged or empty name is
// left as it was. Without a `rename` (a guest, a reader) the name is plain text. A people fact with an editor opens it
// from the fact itself (`onEdit`), never from an action.
//
// **Facts** (`NodeHeaderFact`): a date or a place in one chip look (consecutive chips share a row), people
// (`core/people/PersonGroup.vue`, and its editor's button) and a line of text, each a row of its own, under the subtitle.
// People link to a profile and show a member's picture only where `linkPeople` says so - a member's page; it defaults to
// false, so a share page cannot.
//
// **Core's actions come centrally** (owner 2026-11-01, ADR 0110): the header asks the view around it
// (`../coreNodeActions.ts`: core's node page `NodePage.vue`, the level `LevelView.vue`) for core's own actions of the `node`
// it heads - the heart, "Copy to…", the download, Report - each gated by its core rights function, and merges them
// with the application's (`mergeHeaderActions`). No page builds or forwards one, so none can leave Report out. A kind's
// `order` may still name a core id for its priority.
//
// **Two layouts, picked by the header's own width** (ADR 0110 decisions 1 and 8): `headerLayout` answers `wide` while the
// leading visual's wide width, the name's minimum, the floor's buttons and the ⋯ fit in one row, else `compact`. Its inputs
// are the header's width and sizes no layout changes — the leading visual's `--veyq-node-header-leading-wide` and the
// header's `--veyq-node-header-name-min` (registered lengths, `styles/ui.css`), the gaps, one button — never the name or
// the layout in force, so it cannot flip. The answer is the header's `is-compact` class, which sizes the leading visual.
//
// **As many as fit, the rest in one menu** (ADR 0110): the actions that may be buttons are buttons in `order` as far as
// the row has room — measured on mount and on every resize, `fitHeaderActions` — and the rest are entries of one ⋯ menu,
// whose last group is the menu-only actions (move and copy, then report, then delete) behind a separator. Unmeasured (no
// `ResizeObserver`, a width of 0: a unit test, a hidden tab) the header is wide and every action that may be a button is one. Compact, the
// actions have a row of their own, the whole header wide — the title row then draws no box of its own (`display:
// contents`) and the actions' box is the room. Wide, the name comes first: the room is the title row less the name's
// natural width (its `scrollWidth`, one line) and the gap, but never less than the floor's (`headerFloor`, the kind's
// first two), and the name is shortened only below that.
//
// The title is its page's one `h1` (owner 2026-10-24, `docs/crosscutting/ui-web-styleguide.md` § Heading levels): every
// header opens the page of the node it names — a Memory's, a file's, a drop box's, a Pot's, and a folder's level view. Drawn
// under another page's header (a bookmark's share shown in place, `provideNestedNodeHeading`) it is an `h2`, a size smaller.
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch, type CSSProperties } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import VeyqInput from '../../../shared/ui/VeyqInput.vue'
import VeyqMenu from '../../../shared/ui/VeyqMenu.vue'
import VeyqTooltip from '../../../shared/ui/VeyqTooltip.vue'
import type { VeyqIconName } from '../../../shared/ui/icons'
import type { VeyqMenuItem } from '../../../shared/ui/menu'
import NodeCoverImage from '../cover/components/NodeCoverImage.vue'
import PersonGroup from '../../people/PersonGroup.vue'
import { actionMenuItems } from '../actionMenu'
import {
  factRows,
  fitHeaderActions,
  headerFloor,
  headerLayout,
  headerRowCounts,
  mergeHeaderActions,
  orderHeaderActions,
  partitionHeaderActions,
  type NodeHeaderAction,
  type NodeHeaderChipFact,
  type NodeHeaderFact,
  type NodeHeaderRename,
} from '../nodeHeader'
import { nodeNameErrorText, renameSelectionEnd } from '../nodeName'
import { injectCoreNodeActions, useCoreHeaderActions } from '../coreNodeActions'
import { injectNodeHeadingLevel } from '../nodeHeaderContribution'
import type { TreeNode } from '../tree'

const props = withDefaults(
  defineProps<{
    title: string
    /** The node this header heads, whose core actions it shows - required, so a page says which (null only for the
     *  space's top level, which is no node and has none). */
    node: TreeNode | null
    /** The kind's icon (`KindDescriptor.icon`): in the middle of the 16:9 frame wherever no cover is shown. */
    icon?: VeyqIconName
    /** The kind takes a cover (`KindDescriptor.cover`): the node's cover is the leading visual where it has one. */
    cover?: boolean
    subtitle?: string
    actions?: readonly NodeHeaderAction[]
    /** The kind's priority: the ids of the actions that come first, in that order (core's included); an unlisted one follows. */
    order?: readonly string[]
    /** What the page's detail shows under the name (`NodeHeaderFact`), in order. */
    facts?: readonly NodeHeaderFact[]
    /** A member's page: people link to their profile and show their picture. Default false (fail closed: a share page). */
    linkPeople?: boolean
    /** The name edited in place (`standardNodeRename`); absent or null: the name is plain text. */
    rename?: NodeHeaderRename | null
  }>(),
  { icon: undefined, cover: false, subtitle: undefined, actions: () => [], order: () => [], facts: () => [], linkPeople: false, rename: null },
)
const { t } = useI18n()
const headingTag = injectNodeHeadingLevel() === 2 ? 'h2' : 'h1'
const view = injectCoreNodeActions()
const coreActions = useCoreHeaderActions(() => props.node)
const parts = computed(() => partitionHeaderActions(orderHeaderActions(mergeHeaderActions(coreActions.value, props.actions).filter((a) => a.shown), props.order)))

// The leading visual, always 16:9: the node's cover where the kind takes one and the view resolved it, else the empty frame
// with the kind's icon (a kind without a cover too) - the cover or the frame a button that opens core's cover dialog where
// the kind takes one and whoever looks may set it (the dialog's own right).
const takesCover = computed(() => props.cover && !!props.node)
const coverOf = computed(() => (takesCover.value && props.node ? (view?.cover.covers.value.get(props.node.id) ?? null) : null))
const coverEditable = computed(() => !!(takesCover.value && props.node && view?.cover.canSet(props.node)))
const coverLabel = computed(() => (props.node && view?.cover.has(props.node.id) ? t('node.cover.change') : t('node.cover.set')))
const hasLeading = computed(() => takesCover.value || !!props.icon)
function editCover() {
  if (props.node && coverEditable.value) view?.cover.open(props.node)
}

// The rows beside the visual, as counts the sheet sums to the visual's height (`styles/ui.css`): computed from the data the
// header draws, never measured, so the wide width the layout answer reads does not move with a font or a resize.
const rowCounts = computed(() => headerRowCounts(!!props.subtitle, props.facts))
/** The cover button's words fit on a visual three rows tall or more (a Memory's, a chapter's): a lower one (a folder's name
 *  alone) shows its kind's icon and, on hover and focus, the image glyph over the whole frame, the words its tooltip and
 *  accessible name — as on a compact header (owner 2026-11-04). Read from the counts, never measured. */
const roomyVisual = computed(() => 1 + Object.values(rowCounts.value).reduce((a, b) => a + b, 0) >= 3)
const rowStyle = computed<CSSProperties>(() => ({
  '--veyq-node-header-subtitle-rows': String(rowCounts.value.subtitle),
  '--veyq-node-header-chip-rows': String(rowCounts.value.chips),
  '--veyq-node-header-people-rows': String(rowCounts.value.people),
  '--veyq-node-header-text-rows': String(rowCounts.value.text),
}))

// --- the name, edited in place (ADR 0110 decision 11) ------------------------------------------------------------------
const renameLabel = computed(() => t('node.renameNamed', { name: props.title }))
const errorId = `veyq-node-header-rename-error-${useId()}`
const editing = ref(false)
const draft = ref('')
const saving = ref(false)
const renameError = ref('')
/** The name the last write refused: a blur does not send it again (the error under the field says why). */
let refused: string | null = null
/** The field's width as it opens: the name's own, so it stays about where the name was and the row's buttons keep still. */
const editWidth = ref(0)
const nameButton = ref<HTMLElement | null>(null)
const nameInput = ref<{ $el: HTMLInputElement } | null>(null)

async function startRename() {
  if (!props.rename || editing.value) return
  editWidth.value = nameButton.value?.scrollWidth ?? 0
  draft.value = props.title
  renameError.value = ''
  refused = null
  editing.value = true
  await nextTick()
  const input = nameInput.value?.$el
  if (!input) return
  // Pre-selected as core's name dialog selects (a file's name before its extension), else the whole name.
  const end = props.node ? renameSelectionEnd(props.node, draft.value) : undefined
  input.focus()
  input.setSelectionRange(0, end ?? draft.value.length)
}
async function finishRename() {
  editing.value = false
  renameError.value = ''
  refused = null
  await nextTick()
  nameButton.value?.focus()
}
/** Enter (and a blur that keeps what was typed): write a changed, non-empty name through the kind's own write. */
async function commitRename() {
  const rename = props.rename
  if (!rename || !editing.value || saving.value) return
  const name = draft.value.trim()
  if (!name) return // nothing to write: the field stays open, as the dialog's Save stays disabled
  if (name === props.title.trim()) return void finishRename()
  saving.value = true
  renameError.value = ''
  try {
    await rename.save(name)
    await finishRename()
  } catch (e) {
    refused = name
    renameError.value = nodeNameErrorText(t, e)
  } finally {
    saving.value = false
  }
}
function cancelRename() {
  if (saving.value) return
  void finishRename()
}
function onRenameBlur() {
  if (!editing.value || saving.value) return
  // The window lost focus (another app, another tab): the field stays as it is for when the member comes back.
  if (typeof document !== 'undefined' && !document.hasFocus()) return
  const name = draft.value.trim()
  if (!name || name === props.title.trim()) return void finishRename()
  if (name === refused) return
  void commitRename()
}
// The right went away, or another node is on screen: no field is left open.
watch(
  () => [props.rename, props.node?.id] as const,
  ([rename, id], [, before]) => {
    if (!rename || id !== before) {
      editing.value = false
      renameError.value = ''
    }
  },
)

const rows = computed(() => factRows(props.facts))
/** A fact row's kind, for its block size (`is-chips`, `is-people`, `is-text`). */
const rowKind = (row: readonly NodeHeaderFact[]) => (row[0]!.kind === 'people' ? 'is-people' : row[0]!.kind === 'text' ? 'is-text' : 'is-chips')
const CHIP_ICONS: Record<NodeHeaderChipFact['kind'], VeyqIconName> = { date: 'calendar', place: 'map-marker' }

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
  // The name's natural width: the rename button's where the name is one (it carries the ellipsis then), else the title's.
  const name = nameButton.value ?? title
  const rowWidth = compact.value ? actions.clientWidth : Math.max(floorWidth, row.clientWidth - (name.scrollWidth + 1) - rowGap)
  measured.value = { rowWidth: Math.max(0, rowWidth), buttonWidth, gap, moreWidth }
  layoutWidth.value = headerWidth // with the fit, so both reach the DOM in one patch
}

// Measured before the first paint (a mount runs before it), then again on every change of the header's or the name's size
// and on a new name, one measurement per frame. The header's width is the only input of the layout; the name's box changes
// with a rename or a late web font, which moves the wide row's fit; a cover that arrives or goes changes the leading
// visual's wide width - so does a row that comes or goes, and the name turning into its field. Neither depends on how many
// buttons are drawn, so this settles.
let observer: ResizeObserver | null = null
let frame = 0
function schedule() {
  if (frame || typeof requestAnimationFrame === 'undefined') return
  frame = requestAnimationFrame(() => {
    frame = 0
    void measure()
  })
}
watch([() => props.title, () => Object.values(rowCounts.value).join(), editing], schedule)
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
  <header
    ref="header"
    class="veyq-node-header"
    :class="{ 'has-leading': hasLeading, 'is-compact': compact, 'is-nested': headingTag === 'h2' }"
    :style="rowStyle"
    :data-layout-width="layoutWidth ?? undefined"
  >
    <div v-if="hasLeading" class="veyq-node-header-leading">
      <!-- The visual's first element carries its wide width (`--veyq-node-header-leading-wide`), which the layout answer reads. -->
      <button
        v-if="coverEditable"
        type="button"
        class="veyq-node-header-visual-button veyq-node-header-cover"
        :class="{ 'is-empty': !coverOf, 'is-roomy': roomyVisual }"
        :aria-label="coverLabel"
        :title="coverLabel"
        data-testid="node-header-cover"
        @click="editCover"
      >
        <NodeCoverImage v-if="coverOf" :src="coverOf.url" :crop="coverOf.crop" :alt="title" />
        <VeyqIcon v-else-if="icon" :name="icon" class="veyq-node-header-cover-icon" />
        <span class="veyq-node-header-cover-hint">
          <VeyqIcon name="image" />
          <span class="veyq-node-header-cover-hint-label">{{ coverLabel }}</span>
        </span>
      </button>
      <!-- A plain frame: the cover or the empty frame where it may not be set, and a kind without a cover's icon in the same
           16:9 frame (never a button: it takes none). -->
      <span
        v-else
        class="veyq-node-header-cover"
        :class="{ 'is-empty': !coverOf }"
        :data-testid="takesCover ? 'node-header-cover-frame' : 'node-header-icon-frame'"
      >
        <NodeCoverImage v-if="coverOf" :src="coverOf.url" :crop="coverOf.crop" :alt="title" />
        <VeyqIcon v-else-if="icon" :name="icon" class="veyq-node-header-cover-icon" />
      </span>
    </div>
    <div class="veyq-node-header-body">
      <div ref="titleRow" class="veyq-node-header-titlerow">
        <!-- The page's one h1 (h2 where nested). Where the name may be renamed it holds a real button that turns it into a field (Enter
             writes, Esc cancels) - with no tooltip (owner 2026-11-04): the field shows the whole name; a name that cannot be
             renamed has it as the title's tooltip. The heading is named by the name itself (`aria-label`), so the button's
             "Rename “…”" never becomes the page's heading. -->
        <component :is="headingTag" ref="titleEl" class="veyq-node-header-title" :class="{ 'is-editing': editing }" :title="rename ? undefined : title" :aria-label="title">
          <VeyqInput
            v-if="editing"
            ref="nameInput"
            v-model="draft"
            class="veyq-node-header-name-input"
            :style="{ width: `calc(${editWidth}px + 2rem)` }"
            :aria-label="t('node.nameLabel')"
            :aria-invalid="renameError ? true : undefined"
            :aria-describedby="renameError ? errorId : undefined"
            :aria-busy="saving || undefined"
            :readonly="saving"
            autocomplete="off"
            data-testid="node-header-name-input"
            @keydown.enter.prevent="commitRename"
            @keydown.esc.prevent.stop="cancelRename"
            @blur="onRenameBlur"
          />
          <button v-else-if="rename" ref="nameButton" type="button" class="veyq-node-header-rename" :aria-label="renameLabel" data-testid="node-header-rename" @click="startRename">
            {{ title }}
          </button>
          <template v-else>{{ title }}</template>
        </component>
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
      <!-- A refused name, under the field (the name dialog's words, `nodeName.ts`). -->
      <p v-if="editing && renameError" :id="errorId" class="veyq-node-header-rename-error" role="alert">{{ renameError }}</p>
      <p v-if="subtitle" class="veyq-node-header-subtitle">{{ subtitle }}</p>
      <!-- The facts: consecutive chips one row, people and text a row each; each kind of row one block size (the visual's sum). -->
      <div v-for="(row, r) in rows" :key="r" class="veyq-node-header-facts" :class="rowKind(row)">
        <template v-for="(f, i) in row" :key="i">
          <template v-if="f.kind === 'date' || f.kind === 'place'">
            <button
              v-if="f.onClick"
              type="button"
              class="veyq-node-header-chip"
              :class="{ 'is-empty': f.empty }"
              :data-fact="f.kind"
              :disabled="f.disabled"
              :aria-label="f.actionLabel ?? f.label"
              :title="f.actionLabel ?? f.label"
              @click="f.onClick()"
            >
              <VeyqIcon :name="CHIP_ICONS[f.kind]" />
              <span>{{ f.label }}</span>
            </button>
            <span v-else class="veyq-node-header-chip is-static" :class="{ 'is-empty': f.empty }" :data-fact="f.kind">
              <VeyqIcon :name="CHIP_ICONS[f.kind]" />
              <span>{{ f.label }}</span>
            </span>
          </template>
          <template v-else-if="f.kind === 'people'">
            <PersonGroup
              v-if="f.people.length || f.status"
              class="veyq-node-header-people"
              data-fact="people"
              :label="f.label"
              :people="f.people"
              :caption="f.caption"
              :status="f.status"
              :link="linkPeople"
            />
            <!-- The people's editor (Memories' picker): beside them, or - with nobody yet - the empty chip that is the way in. -->
            <button
              v-if="f.onEdit && !f.people.length"
              type="button"
              class="veyq-node-header-chip is-empty"
              data-fact="people-edit"
              :aria-label="f.editLabel ?? f.label"
              :title="f.editLabel ?? f.label"
              @click="f.onEdit()"
            >
              <VeyqIcon name="users" />
              <span>{{ f.emptyLabel ?? f.editLabel ?? f.label }}</span>
            </button>
            <VeyqTooltip v-else-if="f.onEdit" :text="f.editLabel ?? f.label">
              <button
                type="button"
                class="veyq-node-header-chip veyq-node-header-people-edit"
                data-fact="people-edit"
                :aria-label="f.editLabel ?? f.label"
                @click="f.onEdit()"
              >
                <VeyqIcon name="pencil" />
              </button>
            </VeyqTooltip>
          </template>
          <p v-else-if="f.kind === 'text'" class="veyq-node-header-text" data-fact="text">{{ f.text }}</p>
        </template>
      </div>
    </div>
  </header>
</template>
