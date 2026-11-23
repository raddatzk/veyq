// The search against contracts/platform/search/v1-vectors.json (ADR 0146 decision 1), through the web's own adapter
// (`query.ts`, `results.ts`) over the Kotlin core's search (`@veyq/core-js/search`, ADR 0155): every pure case the core's
// `SearchVectorsTest` answers, now answered by the same Kotlin, plus what only the web holds - its collator crossing, a
// corpus per document list. The core records the vectors (its `SearchVectorsTest`'s write mode, ADR 0155 § Consequences).
//
// Memories' two parts (a Memory's facets, a listed medium's document) are run through Memories' own functions: a test is
// a consumer of both sides (`validate_web_core_imports_no_applications.py` reads no test).
import { describe, expect, it, vi } from 'vitest'

vi.mock('../../features/memories/media', async (orig) => ({
  ...(await orig<typeof import('../../features/memories/media')>()), originalBlobMeta: vi.fn(), unsealMeta: vi.fn(),
}))

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import * as v1 from '@veyq/crypto/v1'
import { fold, foldMap } from '@veyq/core-js/search'
import { sealNodeMeta } from '../node/nodeMeta'
import { originalBlobMeta, unsealMeta } from '../../features/memories/media'
import { closeListing } from '../../features/memories/openedListing'
import { mediaDocuments, memorySearchFacets } from '../../features/memories/search'
import { mediaItem, treeNode } from '../../test/models'
import { searchDocument, type SearchDocument, type SearchFacets } from './model'
import {
  chipKey, corpusOf, createMatcher, matchedText, matchedValues, nameParts, reasonKeyword, suggestChips, tailsOf, textRank, wordPrefix,
  type ChipSources, type SearchChip, type SearchQuery,
} from './query'
import { isHere, searchResults, type SearchContext } from './results'

const FILE = resolve(__dirname, '../../../../../contracts/platform/search/v1-vectors.json')

/* eslint-disable @typescript-eslint/no-explicit-any -- the vectors are JSON, read case by case */
const vectors = JSON.parse(readFileSync(FILE, 'utf8')) as any

/** A document as the vectors spell it: `opensItself` stands for a document that opens itself (a contribution, a person,
 *  a settings section), whose `open` the web holds as a function. */
function docOf(j: any): SearchDocument {
  return searchDocument({
    scopeId: j.scopeId, kind: j.kind, id: j.id, nodeId: j.nodeId ?? j.id, revision: '', name: j.name, path: j.path ?? [],
    pathIds: j.pathIds, facets: (j.facets ?? {}) as SearchFacets, aliases: j.aliases, keywords: j.keywords, subtitle: j.subtitle,
    ...(j.opensItself ? { open: () => {} } : {}),
  })
}
const corpus: SearchDocument[] = vectors.corpus.map(docOf)
const docsOf = (d: any): SearchDocument[] => (d === 'corpus' ? corpus : (d as any[]).map(docOf))
const collator = new Intl.Collator(vectors.collator.locale, { numeric: vectors.collator.numeric, sensitivity: vectors.collator.sensitivity })

/** Each section's answer for one case. */
const answers: Record<string, (c: any) => unknown> = {
  fold: (c) => ({ fold: fold(c.input), map: foldMap(c.input) }),
  tails: (c) => tailsOf(c.text),
  wordPrefix: (c) => wordPrefix(c.name, c.prefix),
  suggest: (c) => {
    const s = vectors.suggestSources
    const sources: ChipSources = {
      documents: docsOf(s.documents), spaces: s.spaces, people: s.people, kinds: s.kinds, ...(c.months === false ? {} : { months: s.months }),
    }
    return suggestChips(c.text, c.chips as SearchChip[], sources).map((x) => ({ key: chipKey(x.chip), chip: x.chip, text: x.text }))
  },
  match: (c) => {
    const query = c.query as SearchQuery
    const found = searchResults(docsOf(c.documents), query, c.context as SearchContext, collator, c.limit === undefined ? {} : { limit: c.limit })
    const t = matchedText(query.text)
    return {
      results: found.results.map((d) => {
        const reason = reasonKeyword(d, t)
        return reason === null ? { id: d.id } : { id: d.id, reason }
      }),
      total: found.total,
    }
  },
  nameParts: (c) => nameParts(c.name, matchedText(c.text)),
  matchedValues: (c) => matchedValues(docOf(c.document), c.chips as SearchChip[]),
  here: (c) => isHere(docOf(c.document), c.context.activeScopeId, c.context.hereNodeId),
  facets: (c) => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(c.now))
    try {
      const key = v1.randomBytes(32)
      const address = { scopeId: '00000000-0000-4000-8000-000000000101', nodeId: '00000000-0000-4000-8000-000000000011' }
      return memorySearchFacets({
        scopeId: address.scopeId, nodeKey: key, parentKey: new Uint8Array(32),
        node: treeNode({ id: address.nodeId, wrappedMeta: sealNodeMeta(key, address, { v: 1, ...c.meta }) }),
      })
    } finally {
      vi.useRealTimers()
    }
  },
  medium: (c) => {
    const key = v1.randomBytes(32)
    vi.mocked(unsealMeta).mockReturnValue(c.meta)
    vi.mocked(originalBlobMeta).mockReturnValue(c.blobMeta)
    const base = mediaItem({ mediaId: c.item.mediaId, wrappedMeta: 'sealed', metaRevision: c.item.metaRevision, ...(c.item.blocked ? { blocked: true } : {}) })
    const item = { ...base, original: { ...base.original, blobId: c.item.blobId } }
    try {
      const docs = mediaDocuments({
        scopeId: 'scope', key, items: [item], open: () => {}, unnamed: (video) => (video ? vectors.unnamed.video : vectors.unnamed.photo),
      })
      return docs.length ? (({ open: _open, ...doc }) => doc)(docs[0]) : null
    } finally {
      closeListing('scope', key)
    }
  },
}

describe('the search vectors (ADR 0146 decision 1)', () => {
  for (const [section, answer] of Object.entries(answers)) {
    describe(section, () => {
      ;(vectors[section] as any[]).forEach((c, i) => {
        it(`#${i} ${c.rule}`, () => expect(answer(c)).toEqual(c.expected))
      })
    })
  }

  // The matcher's recorded answers are fresh scans; the field's matcher, remembering and narrowing, must list each of them.
  // A contribution lists only under its open node, so each case runs twice, under each of the corpus' two, the other's
  // contribution left out.
  describe('matcher, as a field types', () => {
    ;(vectors.matcher as any[]).forEach((c, i) => {
      for (const [openNodeId, unlisted] of [['rome', 'm2'], ['paris', 'm1']]) {
        it(`#${i} under ${openNodeId}: ${c.rule}`, () => {
          const match = createMatcher()
          const docs = docsOf(c.documents)
          const got = c.steps.map((q: SearchQuery) =>
            searchResults(docs, q, { activeScopeId: 'active', openNodeId }, collator, { match }).results
              .map((d) => `${d.id}:${textRank(d, matchedText(q.text))}`).sort())
          expect(got).toEqual(c.expected.map((step: string[]) => step.filter((e) => !e.startsWith(`${unlisted}:`))))
        })
      }
    })
  })
})

describe('what only the web holds', () => {
  const doc = (id: string, name: string) =>
    searchDocument({ scopeId: 'a', kind: 'MEMORY', id, nodeId: id, revision: '', name, path: [], facets: {} })

  // The name order is the browser's collator in the UI's language (ADR 0155 § Consequences): Swedish sorts "Ä" after
  // "Z", where the core's own order of folded names would not.
  it("orders equal ranks by the UI language's collator", () => {
    const docs = [doc('1', 'Äpfel Trip'), doc('2', 'Zoe Trip')]
    const ids = (locale: string) =>
      searchResults(docs, { chips: [], text: 'trip' }, { activeScopeId: 'a', openNodeId: null }, new Intl.Collator(locale)).results.map((d) => d.id)
    expect(ids('sv')).toEqual(['2', '1'])
    expect(ids('de')).toEqual(['1', '2'])
  })

  // One corpus per document list (the index's generation): asked again over the same list it is the same handle, and a
  // new list - a generation moved - is a new one, so nothing of the old one answers for it.
  it('makes the core one corpus per document list', () => {
    const docs = [doc('1', 'Rome')]
    expect(corpusOf(docs)).toBe(corpusOf(docs))
    const next = [...docs, doc('2', 'Rome again')]
    expect(corpusOf(next)).not.toBe(corpusOf(docs))
    expect(searchResults(next, { chips: [], text: 'rome' }, { activeScopeId: 'a', openNodeId: null }, new Intl.Collator('en')).total).toBe(2)
  })
})
