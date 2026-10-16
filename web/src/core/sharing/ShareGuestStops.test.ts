// @vitest-environment happy-dom
// inbox #457 — a share row says why a guest cannot open the link: every reason the server sends, worded in English and
// German, and the fix the hosting dialog offers. The words are pinned as the reader sees them, per language, so a reason
// drawn with another reason's key, a lifetime without its days or a switch without its entry is red.
import { describe, it, expect, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ShareGuestStops from './ShareGuestStops.vue'
import { guestStopLines } from './guestStops'
import type { ShareGuestStop } from '../../api/generated/sharing'
import { realI18n } from '../../test/i18n'
import { i18n } from '../../i18n'

const DAY = 24 * 3600 * 1000

const Button = {
  props: ['label'],
  emits: ['click'],
  template: '<button class="fix" @click="$emit(\'click\')">{{ label }}</button>',
}

function mountStops(stops: ShareGuestStop[], over: { createdAt?: Date; canEdit?: boolean; canRenew?: boolean } = {}) {
  return mount(ShareGuestStops, {
    props: { stops, createdAt: over.createdAt ?? new Date(), canEdit: over.canEdit ?? true, canRenew: over.canRenew ?? true },
    global: { plugins: [realI18n()], stubs: { Button } },
  })
}

const reasons = (w: ReturnType<typeof mountStops>) => w.findAll('.share-guest-stop').map((s) => s.text())

/** Each reason the contract names, alone, with what a reader sees in each language. */
const CASES: { stop: ShareGuestStop; en: string; de: string }[] = [
  {
    stop: { reason: 'expired' },
    en: 'Its end date has passed. A later one keeps this link and its address.',
    de: 'Sein Enddatum ist vorbei. Mit einem späteren bleibt es dieser Link mit seiner Adresse.',
  },
  {
    stop: { reason: 'spaceUnavailable' },
    en: 'This space is paused or being deleted.',
    de: 'Dieser Bereich ist pausiert oder wird gelöscht.',
  },
  {
    stop: { reason: 'instanceSwitch', capabilityId: 'core.public-shares' },
    en: 'Public links are switched off on this server. Only whoever runs the server can switch them back on.',
    de: 'Öffentliche Links sind auf diesem Server ausgeschaltet. Nur wer den Server betreibt, kann sie wieder einschalten.',
  },
  {
    stop: { reason: 'tenantSwitch', capabilityId: 'core.public-shares.contribute' },
    en: 'Links that let guests add are switched off in this space. Only the space’s owner can switch them back on.',
    de: 'Links zum Ansehen und Hinzufügen sind in diesem Bereich ausgeschaltet. Nur wer den Bereich besitzt, kann sie wieder einschalten.',
  },
  {
    stop: { reason: 'tenantSwitch', capabilityId: 'core.public-shares.drop-box' },
    en: 'Upload links are switched off in this space. Only the space’s owner can switch them back on.',
    de: 'Upload-Links sind in diesem Bereich ausgeschaltet. Nur wer den Bereich besitzt, kann sie wieder einschalten.',
  },
  {
    stop: { reason: 'linkPassword' },
    en: 'This space requires a password on every link, and this one has none. Adding one keeps this link and its address.',
    de: 'Dieser Bereich verlangt für jeden Link ein Passwort, und dieser hat keins. Mit einem Passwort bleibt es dieser Link mit seiner Adresse.',
  },
  {
    stop: { reason: 'linkMaxDays', maxDays: 7 },
    en: 'This space requires every link to end within 7 days of being made. An end date within that keeps this link and its address.',
    de: 'Dieser Bereich verlangt, dass jeder Link spätestens 7 Tage nach seiner Erstellung endet. Mit einem Enddatum in dieser Frist bleibt es dieser Link mit seiner Adresse.',
  },
]

/** A link older than its lifetime: only a renewed link — a new address — helps, and the row says so in both languages. */
const OLD = {
  en: 'This space requires every link to end within 7 days of being made, and this one is older. Only a renewed link helps: a new link with a new address to hand out.',
  de: 'Dieser Bereich verlangt, dass jeder Link spätestens 7 Tage nach seiner Erstellung endet, und dieser ist älter. Nur ein erneuerter Link hilft: ein neuer Link mit neuer Adresse, den du weitergibst.',
}

describe('ShareGuestStops', () => {
  afterEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('words every reason in English and in German, under the one badge', async () => {
    for (const c of CASES) {
      const w = mountStops([c.stop])
      expect(w.find('.share-guest-stops-badge').text()).toBe('Guests can’t open it')
      expect(reasons(w), `en ${c.stop.reason}`).toEqual([c.en])
      i18n.global.locale.value = 'de'
      await w.vm.$nextTick()
      expect(w.find('.share-guest-stops-badge').text()).toBe('Gäste können ihn nicht öffnen')
      expect(reasons(w), `de ${c.stop.reason}`).toEqual([c.de])
      i18n.global.locale.value = 'en'
    }
  })

  it('lists several reasons in the server\'s order', () => {
    const w = mountStops(CASES.map((c) => c.stop))
    expect(reasons(w)).toEqual(CASES.map((c) => c.en))
  })

  it('draws nothing for a link that serves guests, and nothing more for a revoked one (the row already says so)', () => {
    expect(mountStops([]).find('.share-guest-stops').exists()).toBe(false)
    expect(mountStops([{ reason: 'revoked' }]).find('.share-guest-stops').exists()).toBe(false)
    // …but a revoked link that also misses a requirement says what reactivating alone would not fix
    expect(reasons(mountStops([{ reason: 'revoked' }, { reason: 'linkPassword' }]))).toEqual([CASES[5]!.en])
  })

  it('names a switch it has no words for plainly, and a reason from a newer server too', () => {
    const w = mountStops([
      { reason: 'instanceSwitch', capabilityId: 'core.public-shares.later' },
      { reason: 'somethingNew' as ShareGuestStop['reason'] },
    ])
    expect(reasons(w)).toEqual([
      'Links like this one are switched off on this server. Only whoever runs the server can switch them back on.',
      'The server doesn’t serve it right now.',
    ])
  })

  it('offers the fix the dialog has: an edit adds the password, and sets an end date while the lifetime leaves a day', async () => {
    const pw = mountStops([{ reason: 'linkPassword' }])
    expect(pw.findAll('.fix').map((b) => b.text())).toEqual(['Add a password'])
    await pw.find('.fix').trigger('click')
    expect(pw.emitted('edit')).toHaveLength(1)

    const young = mountStops([{ reason: 'linkMaxDays', maxDays: 7 }], { createdAt: new Date(Date.now() - 2 * DAY) })
    expect(young.findAll('.fix').map((b) => b.text())).toEqual(['Set an end date'])

    // Both through the one edit: one button, named plainly
    const both = mountStops([{ reason: 'linkPassword' }, { reason: 'linkMaxDays', maxDays: 7 }])
    expect(both.findAll('.fix').map((b) => b.text())).toEqual(['Edit link'])
  })

  it('offers a renew for a link older than the lifetime — no edit can fix it — and says it makes a new address', async () => {
    const old = mountStops([{ reason: 'linkMaxDays', maxDays: 7 }], { createdAt: new Date(Date.now() - 30 * DAY) })
    expect(reasons(old)).toEqual([OLD.en])
    i18n.global.locale.value = 'de'
    await old.vm.$nextTick()
    expect(reasons(old)).toEqual([OLD.de])
    i18n.global.locale.value = 'en'
    await old.vm.$nextTick()
    expect(old.findAll('.fix').map((b) => b.text())).toEqual(['Renew link'])
    await old.find('.fix').trigger('click')
    expect(old.emitted('renew')).toHaveLength(1)
    expect(old.emitted('edit')).toBeUndefined()
  })

  it('offers no fix the host does not have, and none for a switch or a paused space', () => {
    expect(mountStops([{ reason: 'linkPassword' }], { canEdit: false }).findAll('.fix')).toHaveLength(0)
    const old = mountStops([{ reason: 'linkMaxDays', maxDays: 7 }], { createdAt: new Date(Date.now() - 30 * DAY), canRenew: false })
    expect(old.findAll('.fix')).toHaveLength(0)
    expect(mountStops([CASES[1]!.stop, CASES[2]!.stop]).findAll('.fix')).toHaveLength(0)
  })
})

describe('guestStopLines — the lifetime edge', () => {
  it('is fixable by an edit through the last day the date picker offers, and needs a renew after it', () => {
    const made = new Date(2026, 9, 1, 10, 0) // local 1 October, 10:00
    // 7 days from then is 8 October 10:00, so the picker's last day is 7 October (its end must not pass the limit)
    expect(guestStopLines([{ reason: 'linkMaxDays', maxDays: 7 }], made, new Date(2026, 9, 7, 23, 0))[0]!.fix).toBe('edit')
    expect(guestStopLines([{ reason: 'linkMaxDays', maxDays: 7 }], made, new Date(2026, 9, 8, 0, 30))[0]!.fix).toBe('renew')
  })
})
