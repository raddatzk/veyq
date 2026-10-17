// @vitest-environment happy-dom
// The guest-write record's first-layer notice (ADR 0093 § 10): what is kept, for the N served, and the right to object
// as a sentence of its own (GDPR Art. 21(4)); nothing without an N. In English and German, one day and many.
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import GuestWriteRecordNotice from './GuestWriteRecordNotice.vue'
import { realI18n, t } from '../../test/i18n'

function mountNotice(days: number | null | undefined, locale: 'en' | 'de' = 'en') {
  const i18n = realI18n()
  i18n.global.locale.value = locale
  return mount(GuestWriteRecordNotice, { props: { days }, global: { plugins: [i18n] } })
}

describe('GuestWriteRecordNotice', () => {
  it('names N and keeps the objection apart', () => {
    const w = mountNotice(7)
    expect(w.find('.guest-write-record-kept').text()).toBe(t('share.guest.recordKept', { days: 7 }, 7))
    expect(w.find('.guest-write-record-kept').text()).toContain('7 days')
    expect(w.find('.guest-write-record-kept').text()).toContain('source port')
    expect(w.find('.guest-write-record-objection').text()).toBe(t('share.guest.recordObjection'))
  })

  it('says one day for N = 1, in German too', () => {
    expect(mountNotice(1).find('.guest-write-record-kept').text()).toContain('one day')
    const de = mountNotice(1, 'de')
    expect(de.find('.guest-write-record-kept').text()).toContain('einen Tag')
    expect(mountNotice(30, 'de').find('.guest-write-record-kept').text()).toContain('30 Tage')
    expect(de.find('.guest-write-record-objection').text()).toContain('widersprechen')
  })

  it.each([null, undefined, 0])('renders nothing without an N (%s): nothing is recorded, so nothing is said', (days) => {
    expect(mountNotice(days).find('[data-testid="guest-write-record-notice"]').exists()).toBe(false)
  })
})
