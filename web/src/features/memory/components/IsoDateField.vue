<script setup lang="ts">
// A PrimeVue DatePicker bound to an `YYYY-MM-DD` string (cr-0067). Ported from the design source
// `legacy/frontend-noe2ee/src/shared/components/IsoDateField.vue`; the only adaptation is the import path for
// the ISO helpers. The model is a calendar day with no time/zone — exactly what a Memory's sealed date range
// stores. The calendar panel's month/day names follow the active language via applyPrimeVueLocale (the global
// PrimeVue config's `locale` is reactive); the `dateFormat` prop drives the input mask + on-blur manual parse.
import { computed, watch } from 'vue'
import DatePicker, { type DatePickerBlurEvent } from 'primevue/datepicker'
import { usePrimeVue } from 'primevue/config'
import { useI18n } from 'vue-i18n'
import { isoDateToLocalDate, localDateToIsoDate } from '../../../shared/isoDate'
import { applyPrimeVueLocale } from '../../../i18n/primevueLocale'
import type { SupportedLocale } from '../../../i18n'

const model = defineModel<string>({ required: true })
const props = defineProps<{
  inputId?: string
  ariaLabel?: string
  required?: boolean
  minDate?: string
  maxDate?: string
}>()

const { locale } = useI18n()
const primevue = usePrimeVue()
// Localize the calendar panel (month/day names) to the active language; re-apply when the locale switches.
watch(locale, (l) => applyPrimeVueLocale(primevue.config, l as SupportedLocale), { immediate: true })

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
</script>

<template>
  <DatePicker
    v-model="dateValue"
    :input-id="inputId"
    :aria-label="ariaLabel"
    :date-format="dateFormat"
    :min-date="minDateValue"
    :max-date="maxDateValue"
    :pt="{ panel: { class: 'veyq-date-panel' } }"
    :required="required"
    class="veyq-date-field"
    fluid
    icon="pi pi-calendar"
    icon-display="input"
    show-icon
    show-on-focus
    @blur="updateManualValue"
  />
</template>
