// @vitest-environment happy-dom
// The settings hub's door to the moderation surface (uc.moderation.serve-as-moderator step 1, ADR 0099 § 6). The door
// must be a real link that loads `/moderation` afresh in a tab of its own: the hub's tab holds the member's open vault,
// and the page that opens reported content must not. So the panel is mounted with no router at all — a router push
// could not happen — and the rendered element is what is checked, not a handler.
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ModerationEntryPanel from './ModerationEntryPanel.vue'
import type { ModeratorAppointment } from './appointment'
import { MODERATION_PATH } from '../../app/moderationPath'
import { realI18n, t } from '../../test/i18n'

const appointmentIn = (state: ModeratorAppointment['state']): ModeratorAppointment => ({
  state,
  address: 'deputy@example.com',
  confirmation: 'NOT_SENT',
  awaitingKey: false,
})

const mountPanel = (state: ModeratorAppointment['state']) =>
  mount(ModerationEntryPanel, { props: { appointment: appointmentIn(state) }, global: { plugins: [realI18n()] } })

describe('ModerationEntryPanel', () => {
  it('opens the moderation page as a full load in a new tab, never inside this one', () => {
    const link = mountPanel('ACCEPTED').get('[data-test="moderation-entry-open"]')
    expect(link.element.tagName).toBe('A')
    expect(link.attributes('href')).toBe(MODERATION_PATH)
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toContain('noopener')
  })

  it('a pending appointee is told to accept there', () => {
    const w = mountPanel('PENDING')
    expect(w.text()).toContain(t('moderation.entry.pending'))
    expect(w.get('[data-test="moderation-entry-open"]').text()).toContain(t('moderation.entry.openPending'))
    expect(w.text()).not.toContain(t('moderation.entry.accepted'))
  })

  it('an accepted moderator is told the cases wait there, and where the page is', () => {
    const w = mountPanel('ACCEPTED')
    expect(w.text()).toContain(t('moderation.entry.accepted'))
    expect(w.get('[data-test="moderation-entry-open"]').text()).toContain(t('moderation.entry.open'))
    expect(w.text()).toContain(`${location.origin}${MODERATION_PATH}`)
  })
})
