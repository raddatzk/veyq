// The gallery's filter by facts (uc.memory.gallery step 8, `galleryFilter.ts`): each chip's predicate - the day in the
// viewer's zone at its edges, the year, the place, the kind -, the text over the original's name alone, what is suggested
// for a typed text (a year only where the Memory spans two or more), and the one predicate that ANDs the mark filter with
// the facts. The opened listing is stood in for by a table of each medium's opened seals (the listing itself is
// `openedListing.test.ts`'s), so the facts are exactly what the gallery would hand over.
import { describe, it, expect, vi } from 'vitest'

const opened = {
  meta: new Map<string, MediaMeta>(),
  blob: new Map<string, OpenedBlobMeta>(),
}
vi.mock('./openedListing', () => ({ openedMeta: vi.fn(), openedBlobMeta: vi.fn(), openListing: vi.fn(), closeListing: vi.fn() }))

import { mediaItem } from '../../test/models'
import type { MediaItem, MediaMeta } from './media'
import type { OpenedBlobMeta } from '../../core/blob/meta'
import { openedBlobMeta, openedMeta } from './openedListing'
import { PHOTO_DOCUMENT, VIDEO_DOCUMENT } from './search'
import {
  factPredicate, galleryChipKey, galleryFacts, galleryPredicate, suggestGalleryChips, type GalleryChip, type GalleryFacts,
} from './galleryFilter'

const SCOPE = 'scope-1'
const KEY = new Uint8Array(32)
const WORDS = { photo: 'Foto', video: 'Video' }

interface Medium {
  id: string
  at?: number
  override?: number
  place?: string
  name?: string
  video?: boolean
  blocked?: boolean
}
function gallery(media: Medium[], timeZone = 'UTC', locale = 'de'): { items: MediaItem[]; facts: GalleryFacts } {
  opened.meta.clear()
  opened.blob.clear()
  vi.mocked(openedMeta).mockImplementation((_s, _k, item) => opened.meta.get(item.mediaId) ?? null)
  vi.mocked(openedBlobMeta).mockImplementation((_s, _k, item) => opened.blob.get(item.mediaId) ?? null)
  const items = media.map((m) => {
    opened.meta.set(m.id, { ...(m.at !== undefined ? { capturedAt: m.at } : {}), ...(m.override !== undefined ? { capturedAtOverride: m.override } : {}), ...(m.place ? { placeName: m.place } : {}) } as MediaMeta)
    opened.blob.set(m.id, { ...(m.name ? { name: m.name } : {}), type: m.video ? 'video/mp4' : 'image/jpeg' } as OpenedBlobMeta)
    return mediaItem({ mediaId: m.id, ...(m.blocked ? { blocked: true } : {}) })
  })
  return { items, facts: galleryFacts({ scopeId: SCOPE, key: KEY, items, locale, timeZone }) }
}
/** The ids an active filter keeps, in the items' order. */
function kept(g: { items: MediaItem[]; facts: GalleryFacts }, chips: GalleryChip[], text = ''): string[] {
  const keep = factPredicate(() => g.facts, chips, text)
  return g.items.filter((it) => keep?.(it) ?? true).map((it) => it.mediaId)
}
const day = (d: string, label = d): GalleryChip => ({ type: 'day', day: d, label })
const suggested = (g: { facts: GalleryFacts }, text: string, chips: GalleryChip[] = []) =>
  suggestGalleryChips(text, chips, g.facts, WORDS).map((s) => `${s.chip.type}:${s.chip.type === 'year' ? s.chip.year : s.chip.label}|${s.text}`)

describe('the day chip', () => {
  // Auckland is UTC+12 in May: 23:30 UTC on 2 May is the morning of 3 May there, 12:30 UTC on 3 May is past its midnight.
  const media: Medium[] = [
    { id: 'early', at: Date.UTC(2024, 4, 2, 23, 30) },
    { id: 'late', at: Date.UTC(2024, 4, 3, 11, 30) },
    { id: 'past-midnight', at: Date.UTC(2024, 4, 3, 12, 30) },
    { id: 'undated' },
  ]

  it('is the capture day in the viewer\'s zone, both edges of it', () => {
    const g = gallery(media, 'Pacific/Auckland')
    expect([...g.facts.days]).toEqual([['2024-05-03', '3. Mai'], ['2024-05-04', '4. Mai']])
    expect(kept(g, [day('2024-05-03')])).toEqual(['early', 'late'])
    expect(kept(g, [day('2024-05-04')])).toEqual(['past-midnight'])
  })

  it('control: in UTC the same instants fall on other days', () => {
    const g = gallery(media, 'UTC')
    expect(kept(g, [day('2024-05-03')])).toEqual(['late', 'past-midnight'])
    expect(kept(g, [day('2024-05-02')])).toEqual(['early'])
  })

  it('reads the corrected capture time first, as the gallery shows it', () => {
    const g = gallery([{ id: 'fixed', at: Date.UTC(2024, 4, 3, 12), override: Date.UTC(2024, 4, 9, 12) }], 'UTC')
    expect(kept(g, [day('2024-05-09')])).toEqual(['fixed'])
    expect(kept(g, [day('2024-05-03')])).toEqual([])
  })

  it('a medium without a capture time matches no day and no year', () => {
    const g = gallery(media, 'Pacific/Auckland')
    expect(kept(g, [day('2024-05-03')])).not.toContain('undated')
    expect(kept(g, [{ type: 'year', year: 2024 }])).not.toContain('undated')
  })

  it('is suggested by a word its label begins, at most a few, none already a chip, the rest of the text kept', () => {
    const g = gallery([3, 4, 5, 6].map((d) => ({ id: `d${d}`, at: Date.UTC(2024, 4, d, 12) })), 'UTC')
    expect(suggested(g, '4. mai')).toEqual(['day:4. Mai|'])
    expect(suggested(g, 'strand 4.')).toEqual(['day:4. Mai|strand'])
    expect(suggested(g, 'Mai')).toEqual(['day:3. Mai|', 'day:4. Mai|', 'day:5. Mai|'])
    expect(suggested(g, 'mai', [day('2024-05-03', '3. Mai')])).toEqual(['day:4. Mai|', 'day:5. Mai|', 'day:6. Mai|'])
    expect(suggested(g, '14.'), 'no word of a label begins so').toEqual([])
  })

  it('names its year once the Memory spans more than one, so two of one date never read alike', () => {
    const g = gallery([{ id: 'a', at: Date.UTC(2023, 4, 3, 12) }, { id: 'b', at: Date.UTC(2024, 4, 3, 12) }], 'UTC')
    expect([...g.facts.days.values()]).toEqual(['3. Mai 2023', '3. Mai 2024'])
    expect(gallery([{ id: 'a', at: Date.UTC(2024, 4, 3, 12) }], 'UTC', 'en').facts.days.get('2024-05-03')).toBe('May 3')
  })
})

describe('the year chip', () => {
  it('is offered only where the Memory spans two or more years, and only a year that occurs', () => {
    const multi = gallery([{ id: 'a', at: Date.UTC(2023, 6, 1) }, { id: 'b', at: Date.UTC(2024, 6, 1) }], 'UTC')
    expect(suggested(multi, '2024')[0]).toBe('year:2024|')
    expect(suggested(multi, '2019').filter((s) => s.startsWith('year'))).toEqual([])
    const single = gallery([{ id: 'a', at: Date.UTC(2024, 6, 1) }, { id: 'b', at: Date.UTC(2024, 7, 1) }], 'UTC')
    expect(suggested(single, '2024').filter((s) => s.startsWith('year'))).toEqual([])
  })

  it('takes the year in the viewer\'s zone, as the day does', () => {
    // noon UTC on New Year's Eve is already New Year's Day in Auckland
    const g = gallery([{ id: 'eve', at: Date.UTC(2023, 11, 31, 12) }, { id: 'later', at: Date.UTC(2024, 5, 1) }], 'Pacific/Auckland')
    expect(kept(g, [{ type: 'year', year: 2024 }])).toEqual(['eve', 'later'])
    expect(kept(g, [{ type: 'year', year: 2023 }])).toEqual([])
  })
})

describe('the place chip', () => {
  const g = () => gallery([{ id: 'rom', at: 1, place: 'Roma' }, { id: 'nap', at: 1, place: 'Napoli' }, { id: 'none', at: 1 }])

  it('is suggested from the places the items carry, folded', () => {
    expect(suggested(g(), 'RÖ')).toEqual(['place:Roma|'])
  })

  it('keeps the items of that place', () => {
    expect(kept(g(), [{ type: 'place', place: 'roma', label: 'Roma' }])).toEqual(['rom'])
  })
})

describe('the kind chip', () => {
  it('is offered only where photos and videos both occur, and keeps that kind', () => {
    const both = gallery([{ id: 'p', at: 1 }, { id: 'v', at: 1, video: true }])
    expect(suggested(both, 'vid')).toEqual(['kind:Video|'])
    expect(suggested(both, 'fot')).toEqual(['kind:Foto|'])
    expect(kept(both, [{ type: 'kind', kinds: [VIDEO_DOCUMENT], label: 'Video' }])).toEqual(['v'])
    expect(kept(both, [{ type: 'kind', kinds: [PHOTO_DOCUMENT], label: 'Foto' }])).toEqual(['p'])
    expect(suggested(gallery([{ id: 'p', at: 1 }, { id: 'q', at: 1 }]), 'fot')).toEqual([])
  })
})

describe('the text', () => {
  const g = () => gallery([
    { id: 'strand', at: 1, name: 'Strand am Meer.jpg', place: 'Roma' },
    { id: 'img', at: 1, name: 'IMG_0001.jpg', place: 'Roma' },
    { id: 'unnamed', at: 1 },
  ])

  it('matches the original\'s name, folded, by any of its words', () => {
    expect(kept(g(), [], 'STRÄND')).toEqual(['strand'])
    expect(kept(g(), [], 'meer strand')).toEqual(['strand'])
    expect(kept(g(), [], 'img')).toEqual(['img'])
  })

  it('never the place (that is a chip\'s) and never a word for an unnamed medium', () => {
    expect(kept(g(), [], 'roma')).toEqual([])
    expect(kept(g(), [], 'foto')).toEqual([])
  })

  it('combines with every chip', () => {
    expect(kept(g(), [{ type: 'place', place: 'roma', label: 'Roma' }], 'img')).toEqual(['img'])
  })
})

describe('what an active filter leaves out', () => {
  it('a medium blocked by the operator (its facts are not what it shows); nothing without a chip or text', () => {
    const g = gallery([{ id: 'a', at: Date.UTC(2024, 4, 3, 12) }, { id: 'blocked', at: Date.UTC(2024, 4, 3, 12), blocked: true }])
    expect(kept(g, [day('2024-05-03')])).toEqual(['a'])
    expect(factPredicate(() => g.facts, [], '  '), 'no chip, blank text: no predicate').toBeNull()
  })

  it('asks for the facts only once a chip or text narrows', () => {
    const facts = vi.fn(() => null)
    factPredicate(facts, [], '')
    expect(facts).not.toHaveBeenCalled()
  })

  it('names each chip by its own key', () => {
    expect(galleryChipKey(day('2024-05-03'))).toBe('day:2024-05-03')
    expect(galleryChipKey({ type: 'year', year: 2024 })).toBe('year:2024')
  })
})

describe('the gallery\'s one predicate: the mark filter AND the facts', () => {
  const items = ['a', 'b', 'c'].map((id) => mediaItem({ mediaId: id }))
  const marked = (ids: string[]) => (id: string) => ids.includes(id)
  const ofDay = (ids: string[]) => (it: MediaItem) => ids.includes(it.mediaId)
  const keep = (p: ((it: MediaItem) => boolean) | null) => items.filter((it) => p?.(it) ?? true).map((it) => it.mediaId)

  it('is null where neither narrows, so the unfiltered gallery pays nothing', () => {
    expect(galleryPredicate(null, null)).toBeNull()
  })

  it('narrows by each alone and by both together', () => {
    expect(keep(galleryPredicate(marked(['b', 'c']), null))).toEqual(['b', 'c'])
    expect(keep(galleryPredicate(null, ofDay(['a', 'b'])))).toEqual(['a', 'b'])
    expect(keep(galleryPredicate(marked(['b', 'c']), ofDay(['a', 'b'])))).toEqual(['b'])
  })
})
