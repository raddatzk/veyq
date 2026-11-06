// @vitest-environment happy-dom
// The device's record of a guest's own uploads, keyed by where the share lives (ADR 0121 decision 5): this origin's key is
// the very string it was, and another instance's link of the same id keeps its own record.
import { describe, it, expect, beforeEach } from 'vitest'
import { loadOwnShareMedia, rememberOwnShareMedia } from './ownShareMedia'

const ID = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_0'
const AWAY = { shareId: ID, apiBase: 'https://photos.example.org' }

beforeEach(() => localStorage.clear())

describe('ownShareMedia', () => {
  it('keeps this origin\'s record under its former key, and another instance\'s apart', () => {
    rememberOwnShareMedia(ID, 'i-1', 'm-1')
    expect(JSON.parse(localStorage.getItem(`veyq:share-own-media:${ID}`)!)).toEqual({ identityId: 'i-1', mediaIds: ['m-1'] })
    expect([...loadOwnShareMedia(AWAY, 'i-1')]).toEqual([])
    rememberOwnShareMedia(AWAY, 'i-1', 'm-2')
    expect([...loadOwnShareMedia(ID, 'i-1')]).toEqual(['m-1'])
    expect([...loadOwnShareMedia(AWAY, 'i-1')]).toEqual(['m-2'])
  })
})
