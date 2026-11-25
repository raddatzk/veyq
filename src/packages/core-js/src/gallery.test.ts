import { describe, it, expect } from 'vitest'
import { existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { GalleryDoorShape, GalleryHandle, GalleryItemShape, GalleryStateShape } from '@veyq/core-js/memories'

const here = dirname(fileURLToPath(import.meta.url))
if (!existsSync(resolve(here, '../dist/memories.d.mts'))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}
// The package's own entry, as the web imports it.
const m = await import('@veyq/core-js/memories')

// Memories' gallery model through the shipped module (ADR 0155 decision 8, § The gallery model's move): `GalleryModel`
// over a door this test plays, as the web's `galleryDoor.ts` is one — the grid's order and its direction, the split into
// photos, music and GPX tracks, a member's trash and its Undo, a guest's purge asked first. Its own cases are the core's
// (`GalleryActionsTest`, `GalleryModelTest`, `MemoryMediaTest`); these hold that the compiled model answers them here.

const item = (mediaId: string, capturedAt: number | null, extra: Partial<GalleryItemShape> = {}): GalleryItemShape => ({
  mediaId, type: 'image/jpeg', name: null, blocked: false, ownerKey: 'user:u1', width: 4000, height: 3000, capturedAt,
  capturedAtOverride: null, ...extra,
})

function door(items: GalleryItemShape[], answers: { trash?: string; restore?: string; purge?: boolean; own?: string[] } = {}) {
  const d = {
    items,
    sent: [] as string[],
    gallery: async () => d.items,
    moved: async () => false,
    trash: async (id: string) => {
      d.sent.push(`trash:${id}`)
      if ((answers.trash ?? 'done') === 'done') d.items = d.items.filter((i) => i.mediaId !== id)
      return answers.trash ?? 'done'
    },
    restore: async (id: string) => {
      d.sent.push(`restore:${id}`)
      return answers.restore ?? 'done'
    },
    isOwn: (id: string) => (answers.own ?? []).includes(id),
    purge: async (id: string) => {
      d.sent.push(`purge:${id}`)
      if (answers.purge ?? true) d.items = d.items.filter((i) => i.mediaId !== id)
      return answers.purge ?? true
    },
    settle: async () => false,
  }
  return d satisfies GalleryDoorShape
}

/** The model opened, watched, and read once; [last] the state it said last. */
async function open(d: GalleryDoorShape, role: string | null = null, readonly = false) {
  const h: GalleryHandle = m.memoriesGallery(d, 's', 'n', 'MEMORY', role, readonly)
  let last: GalleryStateShape | null = null
  const w = m.memoriesGalleryWatch(h, (s) => { last = s })
  await m.memoriesGalleryReload(h)
  await new Promise((r) => setTimeout(r, 0))
  return { h, state: () => last!, end: () => { m.memoriesGalleryUnwatch(w); m.memoriesGalleryClose(h) } }
}

describe('the gallery model through the shipped module', () => {
  it('orders the grid newest first, an undated medium last, a tie in the listing order; oldest first on the toggle', async () => {
    const p = await open(door([item('z', 30), item('u', null), item('y', 30), item('a', 10)]))
    expect(p.state()).toMatchObject({ status: 'ready', rows: ['z', 'y', 'a', 'u'] })
    m.memoriesGallerySort(p.h, true)
    await new Promise((r) => setTimeout(r, 0))
    expect(p.state().rows).toEqual(['a', 'z', 'y', 'u'])
    p.end()
  })

  it('splits a photo view\'s media from the music and the GPX tracks, an unknown type staying a photo view\'s', async () => {
    const p = await open(door([
      item('pic', 3), item('song', 2, { type: 'audio/mpeg', name: 'b.mp3' }), item('trk', 1, { type: 'application/gpx+xml' }),
      item('gpx', 1, { type: null, name: 'ride.GPX' }), item('odd', null, { type: 'application/x-unknown' }),
    ]))
    expect(p.state()).toMatchObject({ rows: ['pic', 'odd'], music: ['song'], tracks: ['trk', 'gpx'] })
    p.end()
  })

  it('a member\'s trash: out at once with its Undo; a refused restore named by its place in the grid, with its reason', async () => {
    const d = door([item('a', 30), item('b', 20), item('c', 10)], { restore: 'scope_deleting' })
    const p = await open(d)
    await m.memoriesGalleryTrash(p.h, 'b')
    await new Promise((r) => setTimeout(r, 0))
    expect(p.state()).toMatchObject({ rows: ['a', 'c'], undo: true })
    await m.memoriesGalleryUndo(p.h)
    await new Promise((r) => setTimeout(r, 0))
    expect(d.sent).toEqual(['trash:b', 'restore:b'])
    expect(p.state().undo).toBe(false)
    expect(p.state().said).toEqual({ kind: 'restoreRefused', unnamedAt: 2, reason: 'scope_deleting' })
    p.end()
  })

  it('a guest\'s own medium: asked first, purged without Undo; a purge that failed is said and the tile stays', async () => {
    const d = door([item('mine', 20), item('theirs', 10)], { own: ['mine'], purge: false })
    const p = await open(d, 'READ_WRITE', true)
    await m.memoriesGalleryTrash(p.h, 'theirs')
    await new Promise((r) => setTimeout(r, 0))
    expect(p.state().purging, 'not its own: nothing asked').toBeNull()
    await m.memoriesGalleryTrash(p.h, 'mine')
    await new Promise((r) => setTimeout(r, 0))
    expect(p.state().purging).toBe('mine')
    expect(d.sent).toEqual([])
    await m.memoriesGalleryPurge(p.h, true)
    await new Promise((r) => setTimeout(r, 0))
    expect(d.sent).toEqual(['purge:mine'])
    expect(p.state()).toMatchObject({ rows: ['mine', 'theirs'], undo: false, said: { kind: 'purgeFailed' } })
    p.end()
  })
})
