<script setup lang="ts">
// ONE item of the level list (inbox #407), drawn by core for every kind - a folder, a Memory, a file, a drop box,
// a kind this client cannot show - as a ROW (list mode) or a TILE (grid mode) from the same `LevelRow`
// (`../levelRow.ts`): a leading visual (the kind's icon, or the application's cover component), the name, one meta line,
// a primary click and the actions. The application supplies the data and its rights answers; core owns the look, the
// keyboard (every control is a real, labelled `<button>`), the dark mode (tokens) and the split of the actions: at most two
// FIRST-CLASS buttons on the item, every other shown action in ONE "more" menu (a `VeyqMenu`, a real menu: arrow
// keys, Escape): rename and move first, the others, then - after a separator - report and delete (`splitRowActions`).
// Core's own actions (the favourite heart, inbox #409) plug in as `coreActions`, merged around the application's list by
// `mergeRowActions`; core's default rename, move and delete are already in `row.actions` (the level adds them,
// `standardRowActions.ts`).
//
// The visual is hidden from assistive technology: an item is named by its title (the primary button's `aria-label`).
//
// The look (owner 2026-11-04, `styles/ui.css`): a ROW is a line of its group's one surface - a 16:9 thumbnail (the compact
// header's 4 x 2.25 rem), the name and meta line, the actions at its end. A folder's TILE is compact (`is-compact`): the row's
// layout in a quiet frame, so the level's folders form a short group of their own (`LevelView` lists them apart). Every other
// TILE is a card - the list's surface, frame and radius: the 16:9 cover flush at its top, the name and meta line inside under
// it, and the actions over the cover's top-right corner (a compact tile's over its row's end where a pointer hovers) - a
// tile's shown on hover and while focus is inside (opacity only, so they stay in the Tab order), always where nothing hovers.
// The actions are quiet icon buttons (no frame) at the layer's icon-button size.
//
// In the level's Select mode (inbox #421) the item carries a real `<input type="checkbox">` named by the node's title, drawn
// over the visual's corner as a media tile's (`selected` is then a boolean; omitted: no checkbox; on a tile the cover's
// top-left corner, opposite the actions). The primary click still opens the node: selecting is the checkbox's alone.
//
// Drag and drop (inbox #421, `../nodeDrag.ts`): the item is `draggable` only where the level says the viewer may move it
// (`canDrag`), drawn dimmed while dragged and lit while a drag it takes is over it; the level listens to the drag events on
// the item (they fall through to the `<li>`). A click without movement is never a drag, so the primary click is unchanged.
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import VeyqButton from '../../../shared/ui/VeyqButton.vue'
import VeyqIcon from '../../../shared/ui/VeyqIcon.vue'
import VeyqMenu from '../../../shared/ui/VeyqMenu.vue'
import VeyqTooltip from '../../../shared/ui/VeyqTooltip.vue'
import type { VeyqMenuItem } from '../../../shared/ui/menu'
import { actionMenuItems } from '../actionMenu'
import { levelRowDetails, mergeRowActions, splitRowActions, type CoreRowAction, type LevelRow, type LevelRowAction } from '../levelRow'

const props = withDefaults(
  defineProps<{
    row: LevelRow
    /** The layout: `grid` is a tile, `list` a row. */
    mode: 'grid' | 'list'
    /** The kind token, a styling and testing hook (`data-kind`); core names none. */
    kind?: string
    /** Core's actions next to the application's (see the header). */
    coreActions?: readonly CoreRowAction[]
    /** A folder: its tint, and in the grid the compact tile. */
    folder?: boolean
    /** A kind this client cannot show: not clickable, says so. */
    unsupported?: boolean
    /** The level's Select mode is on and this item is (true) or is not (false) selected; omitted: no checkbox. */
    selected?: boolean
    /** The viewer may move this node, so it may be dragged (`draggable="true"`); otherwise no attribute at all. */
    canDrag?: boolean
    /** It is being dragged (dimmed). */
    dragging?: boolean
    /** A drag is over it that it takes (lit). */
    dropTarget?: boolean
    /** The node's id (`data-node-id`): where the level finds the row it brings back into sight (`returnTo`). */
    nodeId?: string
  }>(),
  {
    kind: undefined, coreActions: () => [], folder: false, unsupported: false, selected: undefined, canDrag: false, dragging: false, dropTarget: false,
    nodeId: undefined,
  },
)
const emit = defineEmits<{ toggleSelect: [] }>()
const { t } = useI18n()

// The meta line is real text in reading order: the kind, then each detail the application supplied ("Memory · 12 Oct 2026 ·
// Berlin"), the " · " a literal part of the text. Absent details leave no hole and no separator.
const details = computed(() => levelRowDetails(props.row))

const split = computed(() => splitRowActions(mergeRowActions(props.coreActions, props.row.actions)))
const menu = ref<InstanceType<typeof VeyqMenu> | null>(null)
const menuItems = computed<VeyqMenuItem[]>(() => actionMenuItems([split.value.menu, split.value.menuOnly], 'veyq-row'))
const hasMenu = computed(() => split.value.menu.length + split.value.menuOnly.length > 0)
// A folder's tile is compact: the level lists its folders as a group of their own (`LevelView`), short above the cards.
const compact = computed(() => props.mode === 'grid' && props.folder)
const variantOf = (a: LevelRowAction): 'primary' | 'secondary' | 'danger' => (a.severity === 'danger' ? 'danger' : a.severity === 'primary' ? 'primary' : 'secondary')
</script>

<template>
  <li
    class="veyq-level-item"
    :class="[
      `is-${mode}`,
      {
        'is-compact': compact, 'has-cover': !!row.leading, 'is-folder': folder, 'is-unsupported': unsupported, 'is-selected': selected === true,
        'is-dragging': dragging, 'is-drop-target': dropTarget,
      },
    ]"
    :data-kind="kind"
    :data-node-id="nodeId"
    :draggable="canDrag ? 'true' : undefined"
  >
    <label v-if="selected !== undefined" class="veyq-level-item-check" :class="{ 'is-on': selected }">
      <input type="checkbox" :checked="selected" :aria-label="row.title" @change="emit('toggleSelect')" />
      <VeyqIcon name="check" />
    </label>
    <div v-if="unsupported" class="veyq-level-item-main">
      <span class="veyq-level-item-visual" aria-hidden="true"><VeyqIcon class="veyq-level-item-icon" :name="row.icon" /></span>
      <span class="veyq-level-item-text">
        <strong class="veyq-level-item-title">{{ row.title }}</strong>
        <small class="veyq-level-item-meta">{{ row.meta }}</small>
      </span>
    </div>
    <button
      v-else
      type="button"
      class="veyq-level-item-main"
      :aria-label="row.primary.label"
      :disabled="row.primary.disabled"
      @click="row.primary.onClick()"
    >
      <span class="veyq-level-item-visual" aria-hidden="true">
        <component :is="row.leading.component" v-if="row.leading" v-bind="row.leading.props" />
        <VeyqIcon v-else class="veyq-level-item-icon" :name="row.icon" />
      </span>
      <span class="veyq-level-item-text">
        <strong class="veyq-level-item-title">{{ row.title }}</strong>
        <small v-if="row.meta || details.length" class="veyq-level-item-meta"
          ><template v-if="row.meta">{{ row.meta }}</template
          ><template v-for="(d, i) in details" :key="d.id"
            >{{ row.meta || i > 0 ? ' · ' : '' }}<span class="veyq-level-item-detail" :data-detail="d.id">{{ d.text }}</span></template
          ></small
        >
      </span>
    </button>
    <span v-if="split.buttons.length || hasMenu" class="veyq-level-item-actions">
      <VeyqTooltip v-for="a in split.buttons" :key="a.id" :text="a.label">
        <VeyqButton
          class="veyq-icon-button veyq-prime-icon-button veyq-level-item-action"
          :class="[a.class, { 'veyq-danger-action': a.severity === 'danger', 'is-pressed': a.pressed === true }]"
          :data-action="a.id"
          :icon="a.icon"
          :aria-label="a.label"
          :aria-pressed="a.pressed === undefined ? undefined : a.pressed"
          :variant="variantOf(a)"
          appearance="text"
          size="small"
          :loading="a.loading"
          :disabled="a.disabled"
          @click="a.onClick()"
        />
      </VeyqTooltip>
      <template v-if="hasMenu">
        <VeyqTooltip :text="t('node.rowMore', { name: row.title })">
          <VeyqButton
            class="veyq-icon-button veyq-prime-icon-button veyq-level-item-more"
            icon="ellipsis-h"
            :aria-label="t('node.rowMore', { name: row.title })"
            aria-haspopup="menu"
            variant="secondary"
            appearance="text"
            size="small"
            @click="menu?.toggle($event)"
          />
        </VeyqTooltip>
        <VeyqMenu ref="menu" :items="menuItems" />
      </template>
    </span>
  </li>
</template>
