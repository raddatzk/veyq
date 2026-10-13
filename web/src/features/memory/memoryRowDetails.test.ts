// The details of a Memory's row (date and place): present, absent (left out, never a placeholder), locale-aware, and read
// from the node's own sealed meta under the parent's key.
import { describe, it, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { factsOfMeta, memoryDateLabel, memoryDetails, NO_FACTS, unsealRowFacts } from './memoryRowDetails'
import { sealNodeMeta } from '../../core/node/nodeMeta'
import { treeNode } from '../../test/models'
import { MEMORY_KIND } from './kinds'
import { t } from '../../test/i18n'
import type { MemoryDate } from './memoryDate'

const day = (iso: string): MemoryDate => ({ mode: 'single', from: iso, to: iso })

describe('memoryDetails', () => {
  it('is the date and then the place, each tagged', () => {
    const d = memoryDetails({ date: day('2024-04-02'), placeName: 'Rom' }, t, 'en')
    expect(d).toEqual([{ id: 'date', text: '04/02/2024' }, { id: 'place', text: 'Rom' }])
  })

  it('formats the date for the reader\'s locale (en and de differ)', () => {
    expect(memoryDetails({ date: day('2024-04-02'), placeName: '' }, t, 'en')[0].text).toBe('04/02/2024')
    expect(memoryDetails({ date: day('2024-04-02'), placeName: '' }, t, 'de')[0].text).toBe('02.04.2024')
  })

  it('says a range with both ends, and an ongoing one "since"', () => {
    expect(memoryDateLabel({ mode: 'range', from: '2024-04-02', to: '2024-04-09' }, t, 'de')).toBe('02.04.2024 – 09.04.2024')
    expect(memoryDateLabel({ mode: 'ongoing', from: '2024-04-02' }, t, 'de')).toBe(t('memory.date.since', { day: '02.04.2024' }))
  })

  it('leaves the date out when there is none - not even a "no date yet"', () => {
    expect(memoryDetails({ date: null, placeName: 'Rom' }, t, 'en')).toEqual([{ id: 'place', text: 'Rom' }])
    // a materialized `auto` range with no trustworthy media has neither end; an ongoing one without a start says nothing
    expect(memoryDetails({ date: { mode: 'auto' }, placeName: '' }, t, 'en')).toEqual([])
    expect(memoryDetails({ date: { mode: 'ongoing' }, placeName: '' }, t, 'en')).toEqual([])
  })

  it('leaves the place out when there is none, or only blanks', () => {
    expect(memoryDetails({ date: day('2024-04-02'), placeName: '' }, t, 'en').map((d) => d.id)).toEqual(['date'])
    expect(memoryDetails({ date: day('2024-04-02'), placeName: '   ' }, t, 'en').map((d) => d.id)).toEqual(['date'])
  })

  it('has no details at all for a Memory with neither', () => {
    expect(memoryDetails(NO_FACTS, t, 'en')).toEqual([])
  })

  it('shows the place name as it is, not a coordinate', () => {
    const f = factsOfMeta({ location: { name: ' Berlin ', nameSource: 'manual', derivedMode: 'fixed', derived: { lat: 52.5, lng: 13.4 } } })
    expect(memoryDetails(f, t, 'en')).toEqual([{ id: 'place', text: 'Berlin' }])
  })

  it('shows no place for a pending auto place (a deferred name is no name)', () => {
    const f = factsOfMeta({ location: { name: '', nameSource: 'geocoded', derivedMode: 'auto' } })
    expect(f.placeName).toBe('')
    expect(memoryDetails(f, t, 'en')).toEqual([])
  })
})

describe('unsealRowFacts', () => {
  const SCOPE = '5c09e000-0000-4000-8000-0000000000bb'
  const parentKey = v1.randomBytes(32)
  function memoryWith(meta: Record<string, unknown> | null, under: Uint8Array = parentKey) {
    const id = crypto.randomUUID()
    const key = v1.randomBytes(32)
    return treeNode({
      id, kind: MEMORY_KIND, name: 'M', parentId: null, orderIndex: 0, epoch: 0,
      wrappedContentKey: b64std(v1.seal({ purpose: 'content-key', scopeId: SCOPE, nodeId: id, epoch: 0, placementVersion: 0 }, under, key)),
      wrappedMeta: meta ? sealNodeMeta(key, { scopeId: SCOPE, nodeId: id }, { v: 1, ...meta }) : null,
      metaVersion: 0, placementVersion: 0,
    })
  }

  it('reads the date and the place from one sealed meta', () => {
    const n = memoryWith({ date: day('2024-04-02'), location: { name: 'Rom', nameSource: 'manual', derivedMode: 'fixed' } })
    expect(unsealRowFacts(SCOPE, parentKey, n)).toEqual({ date: { mode: 'single', from: '2024-04-02', to: '2024-04-02' }, placeName: 'Rom' })
  })

  it('has no facts for a Memory without meta, without a parent key, or under the wrong one', () => {
    expect(unsealRowFacts(SCOPE, parentKey, memoryWith(null))).toEqual(NO_FACTS)
    expect(unsealRowFacts(SCOPE, null, memoryWith({ date: day('2024-04-02') }))).toEqual(NO_FACTS)
    expect(unsealRowFacts(SCOPE, v1.randomBytes(32), memoryWith({ date: day('2024-04-02') }))).toEqual(NO_FACTS)
  })

  it('a date alone or a place alone leaves the other absent', () => {
    expect(unsealRowFacts(SCOPE, parentKey, memoryWith({ date: day('2024-04-02') })).placeName).toBe('')
    expect(unsealRowFacts(SCOPE, parentKey, memoryWith({ location: { name: 'Rom', nameSource: 'manual', derivedMode: 'fixed' } })).date).toBeNull()
  })
})
