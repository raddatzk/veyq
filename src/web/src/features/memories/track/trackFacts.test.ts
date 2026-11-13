// uc.memory.tracks (ADR 0130 decision 4): the numbers beside a track, in the reader's words.
import { describe, expect, it } from 'vitest'
import { i18n } from '../../../i18n'
import { formatDistance, trackFacts } from './trackFacts'

const t = (key: string, named: Record<string, unknown>) => i18n.global.t(key, named)

describe('trackFacts', () => {
  it('says the distance, the duration, the climb and the descent', () => {
    i18n.global.locale.value = 'en'
    expect(trackFacts({ distanceMeters: 12_345, durationMs: (3 * 60 + 12) * 60_000, ascentMeters: 420.4, descentMeters: 410 }, 'en', t)).toEqual([
      '12.3 km', '3 h 12 min', '420 m up', '410 m down',
    ])
  })

  it('leaves out what the file does not give, and says a short time in minutes', () => {
    i18n.global.locale.value = 'en'
    expect(trackFacts({ distanceMeters: 800 }, 'en', t)).toEqual(['800 m'])
    expect(trackFacts({ distanceMeters: 800, durationMs: 25 * 60_000 }, 'en', t)).toEqual(['800 m', '25 min'])
  })

  it("speaks the reader's locale", () => {
    i18n.global.locale.value = 'de'
    try {
      expect(formatDistance(12_345, 'de')).toBe('12,3 km')
      expect(trackFacts({ distanceMeters: 1000, durationMs: 90 * 60_000, ascentMeters: 5, descentMeters: 0 }, 'de', t)).toEqual([
        '1 km', '1 Std. 30 Min.', '5 m bergauf', '0 m bergab',
      ])
    } finally {
      i18n.global.locale.value = 'en'
    }
  })
})
