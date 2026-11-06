// The Art. 18 export as text (uc.moderation.report-to-an-authority `the-export-carries-clear-fields-only`, web): every
// field the server answered is in the block, every time in UTC with the zone stated, a guest's write to the millisecond,
// the request's Origin beside the address, a missing port or origin leaves its field out, and a case with nothing held
// says so.
import { describe, expect, it } from 'vitest'
import type { ModerationCaseExport } from '../../../api/generated/moderation'
import { renderExport, utc, type ExportLabels } from './art18Export'

const labels: ExportLabels = {
  case: 'Case', category: 'Category', origin: 'Opened by', timeZone: 'All times in', reported: 'Reported', measure: 'Measure',
  lifted: 'lifted', endedWithTarget: 'ended when the content was erased', uploaderAccount: 'Uploaded by account', uploaderGuest: 'Uploaded by guest identity', viaLink: 'via link',
  guestWrite: 'Guest write', address: 'address', port: 'source port', requestOrigin: 'Origin header', observed: 'at', object: 'object', noHeld: 'Nothing held.',
}

const exported = (over: Partial<ModerationCaseExport> = {}): ModerationCaseExport => ({
  caseId: '1c2d3e4f-5a6b-4f2c-9a1e-7b3d4e5f9a8b',
  category: 'CHILD_SEXUAL_ABUSE',
  origin: 'REPORT',
  timeZone: 'UTC',
  reportedAt: [new Date('2026-10-17T09:00:00Z')],
  measures: [{ kind: 'CONTENT', targetId: '4f2c9a1e-7b3d-4e5f-9a8b-1c2d3e4f5a6b', takenAt: new Date('2026-10-17T12:00:05Z'), endedWithTarget: false, liftedAt: undefined }],
  uploaders: [
    { kind: 'ACCOUNT', id: '5b6c7c3f-0e2a-4b1d-8a6e-8b2c1f2d3e4a', shareId: undefined },
    { kind: 'GUEST', id: '6c7d8e9f-0a1b-4c2d-8e3f-4a5b6c7d8e9f', shareId: '3q2-7v8Xk1p0aZ9bQ4rT6yW-uJ5nD2fL8sB1cE0gH3i' },
  ],
  guestWrites: [
    {
      identityId: '6c7d8e9f-0a1b-4c2d-8e3f-4a5b6c7d8e9f', objectKind: 'MEDIA', objectId: '4f2c9a1e-7b3d-4e5f-9a8b-1c2d3e4f5a6b',
      address: '203.0.113.77', port: 51234, origin: 'https://pages.attacker.example', observedAt: new Date('2026-10-17T08:59:12.345Z'),
    },
    {
      identityId: '6c7d8e9f-0a1b-4c2d-8e3f-4a5b6c7d8e9f', objectKind: 'NODE', objectId: '7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d',
      address: '2001:db8::7', port: undefined, origin: undefined, observedAt: new Date('2026-10-17T08:58:00.001Z'),
    },
  ],
  held: true,
  ...over,
})

describe('the Art. 18 export block', () => {
  it('carries every field the server answered, every time in UTC with the zone stated', () => {
    const text = renderExport(exported(), labels, 'Child sexual abuse material', 'a report', () => 'Content blocked')
    expect(text).toContain('Case: 1c2d3e4f-5a6b-4f2c-9a1e-7b3d4e5f9a8b')
    expect(text).toContain('Category: Child sexual abuse material')
    expect(text).toContain('All times in: UTC')
    expect(text).toContain('Reported: 2026-10-17 09:00:00')
    expect(text).toContain('Measure: Content blocked 4f2c9a1e-7b3d-4e5f-9a8b-1c2d3e4f5a6b — 2026-10-17 12:00:05')
    expect(text).toContain('Uploaded by account: 5b6c7c3f-0e2a-4b1d-8a6e-8b2c1f2d3e4a')
    expect(text).toContain('Uploaded by guest identity: 6c7d8e9f-0a1b-4c2d-8e3f-4a5b6c7d8e9f (via link 3q2-7v8Xk1p0aZ9bQ4rT6yW-uJ5nD2fL8sB1cE0gH3i)')
    expect(text).toContain('address 203.0.113.77, source port 51234, Origin header https://pages.attacker.example, at 2026-10-17 08:59:12.345')
    expect(text).not.toContain('Nothing held.')
  })

  it('leaves out a port and an origin that were not recorded, and says when nothing is held', () => {
    const text = renderExport(exported({ held: false }), labels, 'c', 'o', (k) => k)
    expect(text).toContain('address 2001:db8::7, at 2026-10-17 08:58:00.001')
    expect(text).toContain('Nothing held.')
    expect(utc(new Date('2026-10-17T08:58:00.001Z'))).toBe('2026-10-17 08:58:00')
  })

  it('a measure that ended with its erased target says so, where one a moderator lifted says lifted (ADR 0093 § 6)', () => {
    const measure = { kind: 'CONTENT' as const, targetId: 't', takenAt: new Date('2026-10-17T12:00:05Z'), liftedAt: new Date('2026-10-18T08:00:00Z') }
    const ended = renderExport(exported({ measures: [{ ...measure, endedWithTarget: true }] }), labels, 'c', 'o', (k) => k)
    expect(ended).toContain('Measure: CONTENT t — 2026-10-17 12:00:05 (ended when the content was erased 2026-10-18 08:00:00)')
    const lifted = renderExport(exported({ measures: [{ ...measure, endedWithTarget: false }] }), labels, 'c', 'o', (k) => k)
    expect(lifted).toContain('Measure: CONTENT t — 2026-10-17 12:00:05 (lifted 2026-10-18 08:00:00)')
  })
})
