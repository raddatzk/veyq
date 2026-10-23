<script setup lang="ts">
// The app's one date field (ADR 0107), bound to a calendar day as `YYYY-MM-DD` - no time, no zone, exactly what a
// Memory's date range and an expiry store; a caller holding a `Date` converts through `shared/isoDate`. Every date
// field follows the language: `dd.mm.yyyy` in German, `mm/dd/yyyy` in English (cr-own-ui-components-on-reka
// decision 5). The calendar opens on focus and from the calendar button (PrimeVue's `showOnFocus` defaulted to true on
// every date field, not only the Memory's). Days outside `minDate`/`maxDate` (ISO too) cannot be picked. The calendar's month and day names follow the
// language through core's one locale sync (`app/primeVueLocale.ts`). Sheets reach it by `veyq-date-field`,
// `veyq-date-field__input` and its calendar by `veyq-date-panel`.
import { computed } from 'vue'
import DatePicker, { type DatePickerBlurEvent } from 'primevue/datepicker'
import { useI18n } from 'vue-i18n'
import { isoDateToLocalDate, localDateToIsoDate } from '../isoDate'
import type { SupportedLocale } from '../../i18n'

const model = defineModel<string>({ required: true })
const props = defineProps<{
  inputId?: string
  ariaLabel?: string
  required?: boolean
  disabled?: boolean
  minDate?: string
  maxDate?: string
}>()

const { locale } = useI18n()

const dateFormat = computed(() => (locale.value === 'en' ? 'mm/dd/yy' : 'dd.mm.yy'))
const dateValue = computed<Date | null>({
  get: () => isoDateToLocalDate(model.value),
  set: (value) => {
    model.value = localDateToIsoDate(value)
  },
})
const minDateValue = computed(() => isoDateToLocalDate(props.minDate || '') ?? undefined)
const maxDateValue = computed(() => isoDateToLocalDate(props.maxDate || '') ?? undefined)

function updateManualValue(event: DatePickerBlurEvent) {
  const parsed = parseManualDate(event.value, locale.value as SupportedLocale)
  if (parsed) model.value = localDateToIsoDate(parsed)
}

function parseManualDate(value: string, currentLocale: SupportedLocale): Date | null {
  const trimmed = value.trim()
  const isoDate = isoDateToLocalDate(trimmed)
  if (isoDate) return isoDate

  const displayPattern = currentLocale === 'en' ? /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/ : /^(\d{1,2})\.(\d{1,2})\.(\d{4})$/
  const match = displayPattern.exec(trimmed)
  if (!match) return null

  const month = currentLocale === 'en' ? Number(match[1]) : Number(match[2])
  const day = currentLocale === 'en' ? Number(match[2]) : Number(match[1])
  const year = Number(match[3])
  const date = new Date(year, month - 1, day)

  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day ? date : null
}

const PT = {
  pcInputText: { root: { class: 'veyq-input veyq-date-field__input' } },
  panel: { class: 'veyq-date-panel' },
}
</script>

<template>
  <DatePicker
    v-model="dateValue"
    :input-id="inputId"
    :aria-label="ariaLabel"
    :date-format="dateFormat"
    :min-date="minDateValue"
    :max-date="maxDateValue"
    :pt="PT"
    :required="required"
    :disabled="disabled"
    class="veyq-date-field"
    fluid
    icon="pi pi-calendar"
    icon-display="input"
    show-icon
    show-on-focus
    @blur="updateManualValue"
  />
</template>

<style>
/* Global, not scoped: the calendar panel is teleported to the end of the page. The look of the field and its calendar
   on the app's tokens; the panel's parts are the library's, so their rules live here, inside the layer. */
.veyq-date-field .veyq-date-field__input {
  min-height: 2.75rem;
  padding-inline-end: 2.75rem;
  border-radius: var(--app-radius-sm);
}

.veyq-date-field .p-datepicker-input-icon-container {
  color: var(--app-muted);
}

.veyq-date-panel {
  min-width: min(100%, 20rem);
  margin-top: 0.4rem;
  border: 1px solid var(--app-line);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
  color: var(--app-text);
  box-shadow: var(--app-shadow-md);
}

.veyq-date-panel .p-datepicker-header {
  border-color: var(--app-line);
  color: var(--app-text);
}

.veyq-date-panel .p-datepicker-weekday,
.veyq-date-panel .p-datepicker-other-month .p-datepicker-day {
  color: var(--app-muted);
}

.veyq-date-panel .p-datepicker-day,
.veyq-date-panel .p-datepicker-month,
.veyq-date-panel .p-datepicker-year {
  border-radius: 8px;
}

.veyq-date-panel .p-datepicker-day:not(.p-disabled):hover,
.veyq-date-panel .p-datepicker-month:not(.p-disabled):hover,
.veyq-date-panel .p-datepicker-year:not(.p-disabled):hover {
  background: var(--app-hover);
  color: var(--app-text);
}

.veyq-date-panel .p-datepicker-day-selected,
.veyq-date-panel .p-datepicker-month-selected,
.veyq-date-panel .p-datepicker-year-selected {
  background: var(--app-success);
  color: var(--app-on-fill);
}

.veyq-date-panel .p-datepicker-today .p-datepicker-day:not(.p-datepicker-day-selected) {
  color: var(--app-success-text);
  background: var(--app-success-soft);
}
</style>
