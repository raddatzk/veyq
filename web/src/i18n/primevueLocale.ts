// PrimeVue locale tables (cr-0067) — so the DatePicker calendar panel (month/day names, header, first day of
// week) reads in the active language. Ported from the design source
// `legacy/frontend-noe2ee/src/i18n/primevueLocale.ts`, with the legacy's ASCII folding ("Maerz", "waehlen")
// restored to proper German (veyq has no folding constraint here). `applyPrimeVueLocale` mutates the global
// PrimeVue config's `locale` — PrimeVue's config is reactive, so every mounted DatePicker updates.
import type { PrimeVueConfiguration, PrimeVueLocaleOptions } from 'primevue/config'
import type { SupportedLocale } from './index'

// Partial: we override only the calendar-relevant strings and merge them into PrimeVue's default locale.
const primeVueLocales: Record<SupportedLocale, Partial<PrimeVueLocaleOptions>> = {
  de: {
    firstDayOfWeek: 1,
    dayNames: ['Sonntag', 'Montag', 'Dienstag', 'Mittwoch', 'Donnerstag', 'Freitag', 'Samstag'],
    dayNamesShort: ['So', 'Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa'],
    dayNamesMin: ['S', 'M', 'D', 'M', 'D', 'F', 'S'],
    monthNames: [
      'Januar', 'Februar', 'März', 'April', 'Mai', 'Juni',
      'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember',
    ],
    monthNamesShort: ['Jan', 'Feb', 'Mär', 'Apr', 'Mai', 'Jun', 'Jul', 'Aug', 'Sep', 'Okt', 'Nov', 'Dez'],
    today: 'Heute',
    clear: 'Leeren',
    dateFormat: 'dd.mm.yy',
    weekHeader: 'KW',
    chooseDate: 'Datum wählen',
    prevMonth: 'Vorheriger Monat',
    nextMonth: 'Nächster Monat',
    chooseMonth: 'Monat wählen',
    chooseYear: 'Jahr wählen',
  },
  en: {
    firstDayOfWeek: 0,
    dayNames: ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'],
    dayNamesShort: ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'],
    dayNamesMin: ['S', 'M', 'T', 'W', 'T', 'F', 'S'],
    monthNames: [
      'January', 'February', 'March', 'April', 'May', 'June',
      'July', 'August', 'September', 'October', 'November', 'December',
    ],
    monthNamesShort: ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'],
    today: 'Today',
    clear: 'Clear',
    dateFormat: 'mm/dd/yy',
    weekHeader: 'Wk',
    chooseDate: 'Choose date',
    prevMonth: 'Previous month',
    nextMonth: 'Next month',
    chooseMonth: 'Choose month',
    chooseYear: 'Choose year',
  },
}

export function primeVueLocale(locale: SupportedLocale): Partial<PrimeVueLocaleOptions> {
  return primeVueLocales[locale]
}

/** Merge the language's calendar strings into the global PrimeVue config (its `locale` is reactive). */
export function applyPrimeVueLocale(config: PrimeVueConfiguration, locale: SupportedLocale): void {
  // config.locale is the full PrimeVue default at runtime; merging our calendar overrides keeps every required field.
  config.locale = { ...config.locale, ...primeVueLocale(locale) } as PrimeVueLocaleOptions
}
