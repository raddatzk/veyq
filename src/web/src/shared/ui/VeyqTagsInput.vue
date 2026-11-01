<script setup lang="ts" generic="T extends VeyqTag, O extends VeyqTagOption">
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
// The `tag` slot draws before a chip's label (an avatar, say; `{ tag }`), the `option` slot a suggestion (`{ option }`).
// Every other attribute (aria-label, placeholder's companions, …) lands on the text field; `inputId` is its id, for a
// `<label for>`. Sheets reach it by `veyq-tags-input`, a chip by `veyq-tags-input__tag`, the list by
// `veyq-tags-input__list` and a suggestion by `veyq-tags-input__option` (styled in `layer.css`).
import { computed, ref, useTemplateRef } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  ComboboxAnchor,
  ComboboxContent,
  ComboboxInput,
  ComboboxItem,
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

defineOptions({ inheritAttrs: false })
const query = defineModel<string>('query', { default: '' })
const props = defineProps<{
  tags: T[]
  options: O[]
  placeholder?: string
  inputId?: string
  disabled?: boolean
}>()
const emit = defineEmits<{ add: [option: O]; remove: [tag: T] }>()
defineSlots<{
  tag?: (scope: { tag: T }) => unknown
  option?: (scope: { option: O }) => unknown
}>()
const { t } = useI18n()

const open = ref(false)
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

function pick(event: Event, option: O) {
  // ours to add: the combobox keeps no value of its own
  event.preventDefault()
  if (props.disabled || keys.value.includes(option.key)) return
  emit('add', option)
  query.value = ''
  open.value = false
  focusField()
}
</script>

<template>
  <ComboboxRoot v-model:open="open" class="veyq-tags-input-root" ignore-filter :reset-search-term-on-blur="false" :disabled="disabled">
    <ComboboxAnchor as-child>
      <TagsInputRoot ref="root" :model-value="keys" delimiter="" :disabled="disabled" class="veyq-tags-input" @remove-tag="onRemoveTag" @keydown.capture="onKeydownCapture">
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
      <ComboboxContent class="veyq-tags-input__list" position="popper" :side-offset="2" :collision-padding="8" hide-when-empty>
        <!-- a plain scroller, not ComboboxViewport: that one renders a <style> (hiding the scrollbar) the CSP would have
             to admit by hash (csp.ts), and a long member list should show its scrollbar -->
        <div class="veyq-tags-input__viewport" role="presentation">
          <!-- `data-label`: Reka memoizes an item on its value and attributes, so a label that changes under the same
               key would otherwise stay stale -->
          <ComboboxItem
            v-for="o in options"
            :key="o.key"
            :value="o.key"
            :text-value="o.label"
            :data-label="o.label"
            class="veyq-tags-input__option"
            @select="(e: Event) => pick(e, o)"
          >
            <slot name="option" :option="o">{{ o.label }}</slot>
          </ComboboxItem>
        </div>
      </ComboboxContent>
    </ComboboxPortal>
  </ComboboxRoot>
</template>
