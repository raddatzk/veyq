// The Annex texts (uc.moderation.authority-order `the-forms-come-out-to-copy`, admin-web): admin-web's copy equals the
// contract text for text — web/'s copy is held to the same file — and a rendering names the block to the second in UTC,
// leaves a line out whose value is missing, and leads with the draft banner while the template is a draft.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { formTime, ORDER_FORMS, renderOrderForm } from './orderForms'

const contract = JSON.parse(
  readFileSync(fileURLToPath(new URL('../../../../../contracts/moderation/authority-order-forms.json', import.meta.url)), 'utf8'),
)

const fields = {
  reference: 'BKA ST-2026-0417',
  orderId: '3e4f5a6b-7c8d-4e9f-8a0b-1c2d3e4f5a6b',
  shareId: '3q2-7v8Xk1p0aZ9bQ4rT6yW-uJ5nD2fL8sB1cE0gH3i',
  receivedAt: new Date('2026-10-18T07:12:00Z'),
  blockedAt: new Date('2026-10-18T07:20:11.734Z'),
  provider: 'Example e.V.',
  contact: 'abuse@example.org',
}

describe('the order forms', () => {
  it('equal the contract, text for text', () => {
    const { note, ...rest } = contract
    expect(typeof note).toBe('string')
    expect(ORDER_FORMS).toEqual(rest)
  })

  it('names the block to the second, in UTC', () => {
    expect(formTime(new Date('2026-10-18T07:20:11.734Z'))).toBe('2026-10-18 07:20:11 UTC')
    const text = renderOrderForm('annexII', 'en', fields)
    expect(text).toContain('Access disabled at: 2026-10-18 07:20:11 UTC')
    expect(text).toContain('Reference of the removal order: BKA ST-2026-0417')
    expect(text).toContain(fields.shareId)
    expect(text).not.toMatch(/\{\w+\}/)
  })

  it('leads with the draft banner while the template is a draft', () => {
    expect(ORDER_FORMS.draft).toBe(true)
    expect(renderOrderForm('annexII', 'de', fields).split('\n')[0]).toBe(ORDER_FORMS.de.draftBanner)
  })

  it('leaves out a line whose value is not set, and the Annex III draft says the share was not found', () => {
    const text = renderOrderForm('annexIII', 'en', { ...fields, provider: null, contact: '', blockedAt: null })
    expect(text).not.toContain('Hosting service provider')
    expect(text).not.toContain('Contact point')
    expect(text).toContain('no shared link with this identifier exists on this service')
  })
})
