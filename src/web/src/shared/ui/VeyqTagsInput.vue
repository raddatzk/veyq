<script setup lang="ts" generic="T extends VeyqTag, O extends VeyqTagOption, R extends VeyqTagOption = VeyqTagOption">
// A field that holds several things as chips and suggests more while typing (ADR 0107), on Reka's TagsInput inside its
// Combobox: the chips sit in the field before the text, the suggestions open below it. The caller owns both lists:
// `tags` are the chips (`{ key, label, removeLabel? }`, `key` unique), `options` what to suggest for the current
// `query` (`v-model:query`, the text typed) — the layer filters nothing, so the caller decides what matches and what
// else is offered (an "add what I typed" entry, say). Picking an option emits `add` with it, a chip's × or the keyboard
// emits `remove` with the chip; the caller changes `tags`. An option whose key is already a chip is never added twice.
//
// Keyboard: the text caret walks on into the chips — ← at the text's start highlights the chip before it, ←/→ move the
// highlight, → past the last chip returns to the text (Reka's). Backspace at the text's start removes the chip before
// it at once (Reka's own would first only highlight it — ours, below); Backspace or Delete with a chip highlighted
// removes that chip. ↓/↑ move through the suggestions, Enter picks the highlighted one; Enter never submits the form
// while text is typed, and text alone is never a chip — only an option is. Escape closes the suggestions.
//
// The `tag` slot draws before a chip's label (an avatar, say; `{ tag }`), the `option` slot a suggestion (`{ option }`),
// the `leading` slot before the chips (a search field's magnifier).
//
// **Results under the suggestions** (the shell's search, ADR 0125): `groups` are headed lists of options that are no chip
// - choosing one emits `choose` with it and leaves the text and the list as they are, so the caller decides (it opens the
// result, it shows more); the `result` slot draws one (`{ option, group }`), the `status` slot a line under them that is
// no option (loading, nothing found). With a `status` slot the list shows even while it holds no option. `v-model:open`
// is the list's state, `openOnFocus` opens it when the field gains focus, `listClass` is a class of the caller's on the
// list. The keyboard moves through the suggestions and every group's options alike (Reka's combobox). `optionsLabel` heads
// the suggestions as a group of their own (the search's *Filter by*), so they do not read as results.
//
// **Which entry Enter takes, with results** (uc.platform.search step 4, owner 2026-11-09): the FIRST ENTRY of the list is
// highlighted, a suggestion or a result - Reka's own behaviour - and Enter takes it. Once the member moves the highlight
// (↑/↓/Home/End, or the pointer onto an entry), the field remembers that entry (`stickyKey`) and the group it was listed
// in, and keeps it highlighted while they type on, as long as it is listed: Reka puts the highlight back on the first
// entry whenever the text changes, so the field moves it back with Reka's public `highlightItem`. When the entry drops
// out, the first entry of ITS group is highlighted (the suggestions are a group), or the list's first when that group is
// gone or empty - and the entry stays remembered: listed again (Backspace, say), it is highlighted again, like an undo.
// That fallback is no move of the member's, so it never replaces the memory; a new move does, and choosing an entry
// (a chip or a result) or the list closing forgets it. With nothing remembered the first entry is highlighted - Reka's,
// or ours where Reka left none (its highlighted row went with a chip, or the pointer left the list). Without `groups`
// (the people pickers) the field does none of this: Reka's behaviour unchanged.
//
// Every other attribute (aria-label, placeholder's companions, …) lands on the text field; `inputId` is its id, for a
// `<label for>`. Sheets reach it by `veyq-tags-input`, a chip by `veyq-tags-input__tag`, the list by
// `veyq-tags-input__list`, a suggestion by `veyq-tags-input__option` (their group by `veyq-tags-input__suggestions`), a group's heading by `veyq-tags-input__group-label`
// and the status line by `veyq-tags-input__status` (styled in `layer.css`).
import { computed, nextTick, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ComboboxAnchor,
  ComboboxContent,
  ComboboxGroup,
  ComboboxInput,
  ComboboxItem,
  ComboboxLabel,
  ComboboxPortal,
  ComboboxRoot,
  TagsInputInput,
  TagsInputItem,
  TagsInputItemText,
  TagsInputRoot,
} from 'reka-ui'
import VeyqIcon from './VeyqIcon.vue'

export interface VeyqTag {
  key: string
  label: string
  /** The × button's accessible name; core's "Remove {label}" when absent. */
  removeLabel?: string
}
export interface VeyqTagOption {
  key: string
  label: string
}
/** A headed list of options that are no chip (see the header). */
export interface VeyqTagOptionGroup<R extends VeyqTagOption = VeyqTagOption> {
  key: string
  label: string
  options: R[]
}

defineOptions({ inheritAttrs: false })
const query = defineModel<string>('query', { default: '' })
const open = defineModel<boolean>('open', { default: false })
const props = defineProps<{
  tags: T[]
  options: O[]
  groups?: VeyqTagOptionGroup<R>[]
  placeholder?: string
  inputId?: string
  disabled?: boolean
  openOnFocus?: boolean
  listClass?: string
  /** The heading over the suggestions; without one they stand unheaded above the groups. */
  optionsLabel?: string
}>()
const emit = defineEmits<{ add: [option: O]; remove: [tag: T]; choose: [option: R] }>()
const slots = defineSlots<{
  tag?: (scope: { tag: T }) => unknown
  option?: (scope: { option: O }) => unknown
  leading?: () => unknown
  result?: (scope: { option: R; group: VeyqTagOptionGroup<R> }) => unknown
  status?: () => unknown
}>()
const { t } = useI18n()

const keys = computed(() => props.tags.map((x) => x.key))
const root = useTemplateRef<{ $el: HTMLElement }>('root')
const focusField = () => root.value?.$el.querySelector<HTMLInputElement>('.veyq-tags-input__input')?.focus()

function remove(tag: T | undefined) {
  if (!tag || props.disabled) return
  emit('remove', tag)
}

/** Reka removed a chip from its own copy of the keys (Backspace/Delete on the highlighted one): say so; `tags` decide. */
function onRemoveTag(key: unknown) {
  remove(props.tags.find((x) => x.key === key))
}

/** Backspace at the text's start removes the chip before the caret at once. Caught on the way down, before Reka's
 *  handler on the field sees it, so Reka's first-press highlight never runs; with a chip already highlighted, Reka's
 *  own removal of that chip stands. */
function onKeydownCapture(event: KeyboardEvent) {
  navigating = !!props.groups && NAVIGATION_KEYS.has(event.key) && !event.isComposing
  if (event.key !== 'Backspace' || event.isComposing) return
  const field = event.target
  if (!(field instanceof HTMLInputElement) || field.selectionStart !== 0 || field.selectionEnd !== 0) return
  if ((event.currentTarget as HTMLElement).querySelector('.veyq-tags-input__tag[data-state="active"]')) return
  if (!props.tags.length) return
  event.preventDefault()
  event.stopPropagation()
  remove(props.tags.at(-1))
}

/** Enter with text typed never submits the form, and the text never becomes a chip by itself (Reka's TagsInput would
 *  add it): only a highlighted suggestion, which the combobox picks on the same key, adds. */
function onEnter(event: KeyboardEvent) {
  if (query.value) event.preventDefault()
}

// --- the remembered entry while the list holds results (see the header) ----------------------------------------------
const combobox = useTemplateRef<{ highlightItem?: (value: unknown) => void; highlightedElement?: HTMLElement }>('combobox')
const NAVIGATION_KEYS = new Set(['ArrowDown', 'ArrowUp', 'Home', 'End'])
/** The suggestions as a group of their own, for {@link stickyGroup}. */
const SUGGESTIONS = Symbol('suggestions')
/** The key of the entry the member moved the highlight to, listed or not; null: nothing remembered, the first entry. */
let stickyKey: string | null = null
/** The group that entry was listed in when the member moved to it: where the highlight falls back while it is not listed. */
let stickyGroup: string | typeof SUGGESTIONS = SUGGESTIONS
/** A navigation key is on its way through Reka's handlers (set before them, cleared after): the highlight it makes is the
 *  member's. Reka moves it synchronously inside the key's handler. */
let navigating = false
/** Every entry the list draws, in its order: the suggestions, then each group's options. */
const listed = computed(() => [...props.options.map((o) => o.key), ...(props.groups ?? []).flatMap((g) => g.options.map((o) => o.key))])

/** The member moved to `key`: it replaces whatever was remembered. */
function remember(key: string) {
  stickyKey = key
  stickyGroup = props.options.some((o) => o.key === key) ? SUGGESTIONS : (props.groups?.find((g) => g.options.some((o) => o.key === key))?.key ?? SUGGESTIONS)
}
function forget() {
  stickyKey = null
}
/** Where the highlight belongs while something is remembered: the entry itself while listed, else the first entry of its
 *  group, else the list's first; undefined with nothing remembered (Reka's first entry stands). */
function wanted(): string | undefined {
  if (stickyKey === null) return undefined
  if (listed.value.includes(stickyKey)) return stickyKey
  const group = stickyGroup === SUGGESTIONS ? props.options : props.groups?.find((g) => g.key === stickyGroup)?.options
  return group?.[0]?.key ?? listed.value[0]
}
/** Put the highlight on `key` unless it is there already. */
function highlight(key: string | undefined) {
  if (key === undefined) return
  const el = combobox.value?.highlightedElement
  if (el?.isConnected && el.dataset.optionKey === key) return
  combobox.value?.highlightItem?.(key)
}
function onHighlight(item: { value: unknown } | undefined) {
  if (!props.groups) return
  const value = item?.value
  if (navigating && typeof value === 'string') return remember(value)
  if (!open.value) return
  const want = wanted()
  if (want !== undefined && value !== want) highlight(want)
}
/** The pointer onto an entry: the member's move, like ↓ (caught before the item's own handler highlights it). */
function onPointerMove(event: PointerEvent) {
  if (!props.groups) return
  const key = (event.target as Element | null)?.closest?.<HTMLElement>('[data-option-key]')?.dataset.optionKey
  if (key !== undefined && key !== stickyKey) remember(key)
}
/** After the list re-rendered, and when the pointer left it: the highlight where {@link wanted} says (once Reka settled -
 *  its first-entry highlight runs on a `nextTick` of its own); with nothing remembered, the first entry where no entry is
 *  highlighted - Reka's went with its row (a chip was added, the text cleared without an input), or the pointer took it
 *  along. */
function settle() {
  if (!props.groups || !open.value) return
  const want = wanted()
  if (want !== undefined) return highlight(want)
  if (!combobox.value?.highlightedElement?.isConnected) highlight(listed.value[0])
}
watch(open, forget)
watch(listed, () => void nextTick(settle), { flush: 'post' })

/** A group's option: the caller's to act on; the text and the list stay as they are. */
function choose(event: Event, option: R) {
  event.preventDefault()
  if (props.disabled) return
  forget()
  emit('choose', option)
}

/** Put the focus in the text field (the shell's search shortcut). */
defineExpose({ focus: () => focusField() })

function pick(event: Event, option: O) {
  // ours to add: the combobox keeps no value of its own
  event.preventDefault()
  if (props.disabled || keys.value.includes(option.key)) return
  forget()
  query.value = '' // before the add: a caller may set what stays of the text there (`v-model:query`)
  emit('add', option)
  // a field with results under its suggestions keeps them on screen: the new chip narrows what they show
  if (!props.groups) open.value = false
  focusField()
}
</script>

<template>
  <ComboboxRoot
    ref="combobox"
    v-model:open="open"
    class="veyq-tags-input-root"
    ignore-filter
    :reset-search-term-on-blur="false"
    :disabled="disabled"
    :open-on-focus="openOnFocus"
    @highlight="onHighlight"
    @leave="settle"
  >
    <ComboboxAnchor as-child>
      <TagsInputRoot ref="root" :model-value="keys" delimiter="" :disabled="disabled" class="veyq-tags-input" @remove-tag="onRemoveTag" @keydown.capture="onKeydownCapture" @keydown="navigating = false">
        <slot name="leading" />
        <TagsInputItem v-for="tag in tags" :key="tag.key" :value="tag.key" class="veyq-tags-input__tag" :data-tag="tag.key">
          <slot name="tag" :tag="tag" />
          <TagsInputItemText class="veyq-tags-input__label">{{ tag.label }}</TagsInputItemText>
          <!-- our button, not Reka's TagsInputItemDelete: its aria-labelledby (the chip's text) would win over the
               label that says what it does. Out of the tab order like Reka's: the keyboard removes by Backspace/Delete;
               a press keeps the focus in the text. -->
          <button
            type="button"
            class="veyq-tags-input__remove"
            tabindex="-1"
            :aria-label="tag.removeLabel ?? t('ui.removeTag', { label: tag.label })"
            :disabled="disabled"
            @mousedown.prevent
            @click="remove(tag), focusField()"
          >
            <VeyqIcon name="times" />
          </button>
        </TagsInputItem>
        <ComboboxInput v-model="query" as-child>
          <TagsInputInput :id="inputId" v-bind="$attrs" class="veyq-tags-input__input" :placeholder="placeholder" @keydown.enter="onEnter" />
        </ComboboxInput>
      </TagsInputRoot>
    </ComboboxAnchor>
    <ComboboxPortal>
      <ComboboxContent :class="['veyq-tags-input__list', listClass]" position="popper" :side-offset="2" :collision-padding="8" :hide-when-empty="!slots.status">
        <!-- a plain scroller, not ComboboxViewport: that one renders a <style> (hiding the scrollbar) the CSP would have
             to admit by hash (csp.ts), and a long member list should show its scrollbar -->
        <div class="veyq-tags-input__viewport" role="presentation" @pointermove.capture="onPointerMove">
          <!-- `data-label`: Reka memoizes an item on its value and attributes, so a label that changes under the same
               key would otherwise stay stale -->
          <ComboboxGroup v-if="options.length" class="veyq-tags-input__group veyq-tags-input__suggestions">
            <ComboboxLabel v-if="optionsLabel" class="veyq-tags-input__group-label">{{ optionsLabel }}</ComboboxLabel>
            <ComboboxItem
              v-for="o in options"
              :key="o.key"
              :value="o.key"
              :text-value="o.label"
              :data-label="o.label"
              :data-option-key="o.key"
              class="veyq-tags-input__option"
              @select="(e: Event) => pick(e, o)"
            >
              <slot name="option" :option="o">{{ o.label }}</slot>
            </ComboboxItem>
          </ComboboxGroup>
          <ComboboxGroup v-for="g in groups ?? []" :key="g.key" class="veyq-tags-input__group">
            <ComboboxLabel class="veyq-tags-input__group-label">{{ g.label }}</ComboboxLabel>
            <ComboboxItem
              v-for="o in g.options"
              :key="o.key"
              :value="o.key"
              :text-value="o.label"
              :data-label="o.label"
              :data-option-key="o.key"
              class="veyq-tags-input__option"
              @select="(e: Event) => choose(e, o)"
            >
              <slot name="result" :option="o" :group="g">{{ o.label }}</slot>
            </ComboboxItem>
          </ComboboxGroup>
          <div v-if="slots.status" class="veyq-tags-input__status"><slot name="status" /></div>
        </div>
      </ComboboxContent>
    </ComboboxPortal>
  </ComboboxRoot>
</template>
