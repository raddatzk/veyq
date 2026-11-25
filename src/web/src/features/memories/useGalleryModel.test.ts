// @vitest-environment happy-dom
// The web's one composable over a Kotlin model with a flow (ADR 0155 decision 8, § The gallery model's move): Memories'
// `GalleryModel` through `@veyq/core-js/memories`, over a door this test plays. Its state crosses as a plain value into one
// `shallowRef`, replaced whole; the live poll runs while the tab is visible and catches up at once when it shows again (core's
// `LivePoll`, the web's poller's rule); the watch, the poll and the model end with the scope.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { effectScope, isReactive, isShallow } from 'vue'
import type { GalleryDoorShape, GalleryItemShape } from '@veyq/core-js/memories'
import { useGalleryModel } from './useGalleryModel'

const item = (mediaId: string, capturedAt: number | null, type = 'image/jpeg'): GalleryItemShape => ({
  mediaId, type, name: `${mediaId}.jpg`, blocked: false, ownerKey: 'user:u1', width: 4000, height: 3000, capturedAt, capturedAtOverride: null,
})

function door(items: GalleryItemShape[]) {
  const d = {
    items,
    moves: false,
    gallery: vi.fn(async () => d.items),
    moved: vi.fn(async () => d.moves),
    trash: vi.fn(async () => 'done'),
    restore: vi.fn(async () => 'done'),
    isOwn: () => false,
    purge: vi.fn(async () => true),
    settle: vi.fn(async () => false),
  }
  return d satisfies GalleryDoorShape
}

function setVisible(visible: boolean) {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => (visible ? 'visible' : 'hidden') })
  document.dispatchEvent(new Event('visibilitychange'))
}

/** Lets the model's microtasks run (its steps are microtasks, its waits timers). */
const settled = () => vi.advanceTimersByTimeAsync(0)
/** One whole jittered interval of the item lists' cadence (20 s ± 20 %), and a little. */
const INTERVAL = 24_001

afterEach(() => {
  vi.useRealTimers()
  setVisible(true)
})

describe('useGalleryModel', () => {
  it('crosses the state as one plain value, replaced whole: the rows in the grid order, music and tracks apart', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date', 'performance', 'hrtime'] })
    const scope = effectScope()
    const d = door([item('a', 10), item('b', 30), item('song', null, 'audio/mpeg'), item('trk', null, 'application/gpx+xml'), item('u', null)])
    const g = scope.run(() => useGalleryModel())!
    g.open({ door: d, scopeId: 's', nodeId: 'm', kind: 'MEMORY', role: null, readonly: false })
    expect(g.state.value.status).toBe('loading')
    await settled()
    const first = g.state.value
    expect(first).toMatchObject({ status: 'ready', rows: ['b', 'a', 'u'], music: ['song'], tracks: ['trk'], undo: false, purging: null })
    expect(isShallow(g.state) && !isReactive(g.state.value), 'one shallowRef, never deep reactivity over the model').toBe(true)
    expect(d.settle, 'the page opening is a settle point').toHaveBeenCalledTimes(1)
    scope.stop()
  })

  it('polls while the tab is visible, asks nothing while hidden, and catches up at once when it shows again', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date', 'performance', 'hrtime'] })
    setVisible(true)
    const scope = effectScope()
    const d = door([item('a', 10)])
    const g = scope.run(() => useGalleryModel())!
    g.open({ door: d, scopeId: 's', nodeId: 'm', kind: 'MEMORY', role: null, readonly: false })
    await settled()
    expect(d.moved, 'a page just opened has just read').not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(INTERVAL)
    expect(d.moved).toHaveBeenCalledTimes(1)

    setVisible(false)
    await vi.advanceTimersByTimeAsync(INTERVAL * 5)
    expect(d.moved, 'nothing is asked in the background').toHaveBeenCalledTimes(1)

    d.items = [item('a', 10), item('new', 40)]
    d.moves = true
    setVisible(true)
    await settled()
    expect(d.moved, 'shown again: it asks at once').toHaveBeenCalledTimes(2)
    expect(g.state.value.rows).toEqual(['new', 'a'])
    scope.stop()
  })

  it('ends the watch, the poll and the model with its scope', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date', 'performance', 'hrtime'] })
    setVisible(true)
    const scope = effectScope()
    const d = door([item('a', 10)])
    const g = scope.run(() => useGalleryModel())!
    g.open({ door: d, scopeId: 's', nodeId: 'm', kind: 'MEMORY', role: null, readonly: false })
    await settled()
    const last = g.state.value
    scope.stop()
    expect(g.handle()).toBeNull()
    d.items = [item('a', 10), item('b', 20)]
    d.moves = true
    await vi.advanceTimersByTimeAsync(INTERVAL * 3)
    expect(d.moved, 'no poll after the scope ended').not.toHaveBeenCalled()
    expect(g.state.value).toBe(last)
  })
})
