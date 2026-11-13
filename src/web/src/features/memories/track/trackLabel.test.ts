// uc.memory.tracks A8 (ADR 0130 decision 9): a track's label as its listed tier carries it, and an activity's dash - the
// old application's patterns, so a line reads the same as it did there.
import { describe, expect, it } from 'vitest'
import { TRACK_ACTIVITIES, activityDash, trackActivityOf, trackLabelOf } from './trackLabel'

describe('trackLabel', () => {
  it('reads a known activity, and none for a missing or a newer client\'s value', () => {
    expect(TRACK_ACTIVITIES.map(trackActivityOf)).toEqual([...TRACK_ACTIVITIES])
    expect([undefined, '', 'HIKING', 'paragliding', 3].map(trackActivityOf)).toEqual([null, null, null, null, null])
  })

  it('reads a blank name as none, so the file names the track', () => {
    expect(trackLabelOf({ trackName: '  Ridge  ', trackActivity: 'water' })).toEqual({ name: 'Ridge', activity: 'water' })
    expect(trackLabelOf({ trackName: '   ' })).toEqual({ name: null, activity: null })
    expect(trackLabelOf(null)).toEqual({ name: null, activity: null })
  })

  // Lever: any pattern in `activityDash`.
  it('draws on foot, other and none solid, and every other activity in the old application\'s dash', () => {
    expect(Object.fromEntries([...TRACK_ACTIVITIES, null].map((a) => [String(a), activityDash(a)]))).toEqual({
      hiking: undefined,
      walking: undefined,
      running: '2 8',
      cycling: '10 7',
      'mountain-biking': '10 5 2 5',
      'road-cycling': '10 7',
      'gravel-cycling': '10 7',
      skiing: '14 6 3 6',
      water: '3 9',
      other: undefined,
      null: undefined,
    })
  })
})
