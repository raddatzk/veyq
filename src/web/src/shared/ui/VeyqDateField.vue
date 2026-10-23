<script setup lang="ts">
// The app's one date field (ADR 0107), on Reka's DatePicker, bound to a calendar day as `YYYY-MM-DD` - no time, no
// zone, exactly what a Memory's date range and an expiry store; a caller holding a `Date` converts through
// `shared/isoDate`. The day is typed into segments - day, month and year, each a spinbutton typed as digits or stepped
// by the arrow keys - in the order and with the separators of the language (`tt.mm.jjjj` in German, `mm/dd/yyyy` in
// English; cr-own-ui-components-on-reka decision 5). The calendar opens from its button and, as before, when the field
// gets focus; it steps by month, its week starts on the catalog's first day, and days outside `minDate`/`maxDate`
// (ISO too) cannot be picked or typed. `inputId` names the field for a `<label for>`. Sheets reach it by
// `veyq-date-field`, a segment by `veyq-date-field__segment` and its calendar by `veyq-date-panel` (styled below).
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { parseDate, type DateValue } from '@internationalized/date'
import {
  DatePickerCalendar,
  DatePickerCell,
  DatePickerCellTrigger,
  DatePickerContent,
  DatePickerField,
  DatePickerGrid,
  DatePickerGridBody,
  DatePickerGridHead,
  DatePickerGridRow,
  DatePickerHeadCell,
  DatePickerHeader,
  DatePickerHeading,
  DatePickerInput,
  DatePickerNext,
  DatePickerPrev,
  DatePickerRoot,
  DatePickerTrigger,
} from 'reka-ui'
import VeyqIcon from './VeyqIcon.vue'

const model = defineModel<string>({ required: true })
const props = defineProps<{
  inputId?: string
  ariaLabel?: string
  required?: boolean
  disabled?: boolean
  minDate?: string
  maxDate?: string
}>()

const { t, locale } = useI18n()

function dayOf(iso: string | undefined): DateValue | undefined {
  if (!iso) return undefined
  try {
    return parseDate(iso)
  } catch {
    return undefined
  }
}

const minValue = computed(() => dayOf(props.minDate))
const maxValue = computed(() => dayOf(props.maxDate))
/** A day outside `minDate`/`maxDate` typed into the segments is refused, as the PrimeVue field refused it: the model
 *  never holds it, and once focus leaves the field the field goes back to the day it held when focus came in (typing
 *  "19" passes through the valid "1" first) and the segments show it again - a fresh value object (`redraw`), since
 *  Reka re-reads its segments when the value it is handed is another object. */
let refused = false
let dayOnEntry = ''
const redraw = ref(0)
function withinBounds(day: DateValue): boolean {
  return !(minValue.value && day.compare(minValue.value) < 0) && !(maxValue.value && day.compare(maxValue.value) > 0)
}
const value = computed<DateValue | undefined>({
  get: () => {
    void redraw.value
    return dayOf(model.value)
  },
  set: (day) => {
    if (day && !withinBounds(day)) {
      refused = true
      return
    }
    refused = false
    model.value = day ? day.toString() : ''
  },
})
const language = computed(() => (locale.value === 'de' ? 'de-DE' : 'en-US'))
const weekStartsOn = computed(() => Number(t('ui.firstDayOfWeek')) as 0 | 1 | 2 | 3 | 4 | 5 | 6)

/** Reka writes day and month without a leading zero and names the segments in English; the field shows `12.05.2026`
 *  as before and says "Tag", "Monat", "Jahr" (an attribute passed here wins over Reka's). An empty segment shows the
 *  language's placeholder (`tt`, `mm`, `jjjj`), which is no number. */
const SEGMENT_NAMES: Record<string, string> = { day: 'ui.dateSegment.day', month: 'ui.dateSegment.month', year: 'ui.dateSegment.year' }
const isNumber = (text: string) => /^\d+$/.test(text)
function shown(part: string, text: string): string {
  return (part === 'day' || part === 'month') && isNumber(text) ? text.padStart(2, '0') : text
}
function segmentNames(part: string, text: string): Record<string, string> {
  const names: Record<string, string> = {}
  if (SEGMENT_NAMES[part]) names['aria-label'] = t(SEGMENT_NAMES[part])
  if (!isNumber(text)) names['aria-valuetext'] = t('ui.dateSegment.empty')
  return names
}

const open = ref(false)
const field = ref<{ $el: HTMLElement } | null>(null)
/** The calendar hangs below the whole field, not below its button. */
const fieldElement = computed(() => field.value?.$el)
/** A segment or the button keeps focus while the calendar is open; only a press outside both closes it. */
function insideField(event: Event): boolean {
  const target = (event as CustomEvent<{ originalEvent: Event }>).detail?.originalEvent?.target as Node | null
  return !!(target && field.value?.$el?.contains(target))
}
function keepOpenFromField(event: Event) {
  if (insideField(event)) event.preventDefault()
}

function inFieldOrPanel(node: Node | null): boolean {
  return !!node && (!!field.value?.$el?.contains(node) || !!document.querySelector('.veyq-date-panel')?.contains(node))
}
/** Focus on a segment opens the calendar; focus on the button leaves it to the button's own click. */
function onFieldFocus(event: FocusEvent) {
  if (!inFieldOrPanel(event.relatedTarget as Node | null)) dayOnEntry = model.value
  if ((event.target as Element | null)?.closest('.veyq-date-field__segment')) open.value = true
}
function onFieldBlur(event: FocusEvent) {
  if (inFieldOrPanel(event.relatedTarget as Node | null)) return
  if (!refused) return
  refused = false
  model.value = dayOnEntry
  redraw.value++
}
/** A `<label>` around the field activates its first labelable element on a click - the calendar button, which then
 *  closed the calendar the focus had just opened. A click inside the field is the field's own; a click on the button
 *  that no press of it caused is the label's, and a label means the first segment, as `<label for>` does. */
let triggerPressed = false
function noteTriggerPress() {
  triggerPressed = true
}
function onFieldClick(event: MouseEvent) {
  const onTrigger = (event.target as Element | null)?.closest('.veyq-date-field__trigger')
  if (!onTrigger) {
    event.preventDefault()
    return
  }
  if (triggerPressed) {
    triggerPressed = false
    return
  }
  event.preventDefault()
  event.stopPropagation()
  field.value?.$el?.querySelector<HTMLElement>('.veyq-date-field__segment')?.focus()
}

/** A `<label for>` names the field: a label cannot point at a group, so the group points back at the label. */
const labelledBy = ref<string | undefined>()
onMounted(() => {
  if (!props.inputId) return
  const label = document.querySelector<HTMLLabelElement>(`label[for="${props.inputId}"]`)
  if (!label) return
  if (!label.id) label.id = `${props.inputId}-label`
  labelledBy.value = label.id
})
</script>

<template>
  <DatePickerRoot
    v-model="value"
    v-model:open="open"
    :locale="language"
    :min-value="minValue"
    :max-value="maxValue"
    :week-starts-on="weekStartsOn"
    :disabled="disabled"
    :required="required"
    granularity="day"
    :modal="false"
  >
    <DatePickerField
      :id="inputId"
      ref="field"
      v-slot="{ segments }"
      class="veyq-date-field"
      :aria-label="ariaLabel"
      :aria-labelledby="ariaLabel ? undefined : labelledBy"
      @focusin="onFieldFocus"
      @focusout="onFieldBlur"
      @click.capture="onFieldClick"
    >
      <span class="veyq-date-field__segments">
        <template v-for="item in segments" :key="item.part">
          <DatePickerInput v-if="item.part === 'literal'" :part="item.part" class="veyq-date-field__literal">{{ item.value }}</DatePickerInput>
          <DatePickerInput v-else :part="item.part" class="veyq-date-field__segment" v-bind="segmentNames(item.part, item.value)">{{ shown(item.part, item.value) }}</DatePickerInput>
        </template>
      </span>
      <DatePickerTrigger
        class="veyq-date-field__trigger"
        :aria-label="t('ui.chooseDate')"
        @pointerdown="noteTriggerPress"
        @keydown.enter.space="noteTriggerPress"
      >
        <VeyqIcon name="calendar" />
      </DatePickerTrigger>
    </DatePickerField>
    <DatePickerContent
      class="veyq-date-panel"
      :reference="fieldElement"
      side="bottom"
      align="start"
      :side-offset="4"
      :collision-padding="8"
      @open-auto-focus.prevent
      @interact-outside="keepOpenFromField"
      @focus-outside="keepOpenFromField"
    >
      <DatePickerCalendar v-slot="{ weekDays, grid }" class="veyq-date-panel__calendar">
        <DatePickerHeader class="veyq-date-panel__header">
          <DatePickerPrev class="veyq-date-panel__nav" :aria-label="t('ui.prevMonth')"><VeyqIcon name="chevron-left" /></DatePickerPrev>
          <DatePickerHeading class="veyq-date-panel__title" />
          <DatePickerNext class="veyq-date-panel__nav" :aria-label="t('ui.nextMonth')"><VeyqIcon name="chevron-right" /></DatePickerNext>
        </DatePickerHeader>
        <DatePickerGrid v-for="month in grid" :key="month.value.toString()" class="veyq-date-panel__grid">
          <DatePickerGridHead>
            <DatePickerGridRow class="veyq-date-panel__row">
              <DatePickerHeadCell v-for="day in weekDays" :key="day" class="veyq-date-panel__weekday">{{ day }}</DatePickerHeadCell>
            </DatePickerGridRow>
          </DatePickerGridHead>
          <DatePickerGridBody>
            <DatePickerGridRow v-for="(week, w) in month.rows" :key="w" class="veyq-date-panel__row">
              <DatePickerCell v-for="day in week" :key="day.toString()" :date="day" class="veyq-date-panel__cell">
                <DatePickerCellTrigger :day="day" :month="month.value" class="veyq-date-panel__day" />
              </DatePickerCell>
            </DatePickerGridRow>
          </DatePickerGridBody>
        </DatePickerGrid>
      </DatePickerCalendar>
    </DatePickerContent>
  </DatePickerRoot>
</template>

<style>
/* Global, not scoped: the calendar panel is teleported to the end of the page. The field looks like a text field
   (`layer.css` .veyq-input), with the calendar button inside it on the right. */
.veyq-date-field {
  position: relative;
  display: flex;
  align-items: center;
  width: 100%;
  min-height: 2.75rem;
  padding: 0.5rem 2.75rem 0.5rem 0.75rem;
  border: 1px solid var(--app-control-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-field);
  color: var(--app-control-text);
  font-size: 1rem;
  box-shadow: var(--app-shadow-sm);
  transition: border-color 0.2s;
}

.veyq-date-field:hover:not([data-disabled]) {
  border-color: var(--app-muted);
}

.veyq-date-field:focus-within {
  border-color: var(--app-focus);
}

.veyq-date-field[data-invalid] {
  border-color: var(--app-danger);
}

.veyq-date-field[data-disabled] {
  background: var(--app-surface-muted);
  color: var(--app-muted);
}

.veyq-date-field__segments {
  display: inline-flex;
  align-items: center;
}

.veyq-date-field__segment {
  padding: 0 0.1rem;
  border-radius: 4px;
  outline: 0;
  caret-color: transparent;
}

.veyq-date-field__segment[data-placeholder] {
  color: var(--app-muted);
}

.veyq-date-field__segment:focus {
  background: var(--app-success-soft);
  color: var(--app-primary);
}

.veyq-date-field__literal {
  color: var(--app-muted);
}

.veyq-date-field__trigger {
  position: absolute;
  top: 50%;
  inset-inline-end: 0.45rem;
  display: inline-grid;
  place-items: center;
  width: 2rem;
  height: 2rem;
  margin-top: -1rem;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--app-muted);
  font-size: 1rem;
  cursor: pointer;
}

.veyq-date-field__trigger:hover:not(:disabled) {
  color: var(--app-control-text);
}

.veyq-date-field__trigger:focus-visible {
  outline: 1px solid var(--app-focus);
  outline-offset: 1px;
}

.veyq-date-panel {
  z-index: 1100;
  min-width: min(100%, 20rem);
  padding: 0.75rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
  color: var(--app-text);
  box-shadow: var(--app-shadow-md);
}

.veyq-date-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0 0.5rem;
  border-bottom: 1px solid var(--app-line);
  color: var(--app-text);
}

.veyq-date-panel__title {
  font-weight: 500;
}

.veyq-date-panel__nav {
  display: inline-grid;
  place-items: center;
  width: 1.75rem;
  height: 1.75rem;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--app-muted);
  cursor: pointer;
}

.veyq-date-panel__nav:hover:not([data-disabled]) {
  background: var(--app-hover);
  color: var(--app-text);
}

.veyq-date-panel__nav:focus-visible {
  outline: 1px solid var(--app-focus);
  outline-offset: 1px;
}

.veyq-date-panel__grid {
  width: 100%;
  margin: 0.5rem 0 0;
  border-collapse: collapse;
  font-size: 1rem;
}

.veyq-date-panel__weekday {
  padding: 0.25rem;
  color: var(--app-muted);
  font-weight: 500;
}

.veyq-date-panel__cell {
  padding: 0.25rem;
  text-align: center;
}

.veyq-date-panel__day {
  display: inline-grid;
  place-items: center;
  width: 2rem;
  height: 2rem;
  margin: 0 auto;
  border-radius: 8px;
  color: var(--app-text);
  cursor: pointer;
  outline: 0;
}

.veyq-date-panel__day[data-outside-view] {
  color: var(--app-muted);
}

.veyq-date-panel__day:not([data-disabled]):not([data-selected]):hover {
  background: var(--app-hover);
  color: var(--app-text);
}

.veyq-date-panel__day[data-today]:not([data-selected]) {
  color: var(--app-success-text);
  background: var(--app-success-soft);
}

.veyq-date-panel__day[data-selected] {
  background: var(--app-success);
  color: var(--app-on-fill);
}

.veyq-date-panel__day[data-disabled],
.veyq-date-panel__day[data-unavailable] {
  cursor: default;
  opacity: 0.6;
}

.veyq-date-panel__day:focus-visible {
  outline: 1px solid var(--app-focus);
  outline-offset: 1px;
}
</style>
