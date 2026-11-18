// The search against contracts/platform/search/v1-vectors.json (ADR 0146 decision 1): one set of vectors the web and the
// apps' core both answer to, so the two implementations of ADR 0125's search cannot drift. Every case names the rule it
// pins (`rule`). The inputs are chosen by hand; each `expected` was recorded by running the web's own functions once, in
// write mode (`VEYQ_WRITE_VECTORS=1 pnpm exec vitest run src/core/search/vectors.test.ts`), and read against its rule
// before it was committed. A rule that changes changes the vectors: this test and the core's `SearchVectorsTest` go red
// together until both follow.
//
// Memories' two parts (a Memory's facets, a listed medium's document) are run through Memories' own functions: a test is
// a consumer of both sides (`validate_web_core_imports_no_applications.py` reads no test).
import { afterAll, describe, expect, it, vi } from 'vitest'

vi.mock('../../features/memories/media', async (orig) => ({
  ...(await orig<typeof import('../../features/memories/media')>()), originalBlobMeta: vi.fn(), unsealMeta: vi.fn(),
}))

import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import * as v1 from '@veyq/crypto/v1'
import { fold, foldMap } from '../../shared/text/fold'
import { sealNodeMeta } from '../node/nodeMeta'
import { originalBlobMeta, unsealMeta } from '../../features/memories/media'
import { closeListing } from '../../features/memories/openedListing'
import { mediaDocuments, memorySearchFacets } from '../../features/memories/search'
import { mediaItem, treeNode } from '../../test/models'
import { searchDocument, type SearchDocument, type SearchFacets } from './model'
import {
  chipKey, createMatcher, matchAll, matchedText, nameParts, reasonKeyword, suggestChips, tailsOf, wordPrefix, type ChipSources,
  type SearchChip, type SearchQuery,
} from './query'
import { searchResults, type SearchContext } from './results'

const FILE = resolve(__dirname, '../../../../../contracts/platform/search/v1-vectors.json')
const WRITE = process.env.VEYQ_WRITE_VECTORS === '1'

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
const sorted = (ms: { doc: SearchDocument; rank: number }[]) => ms.map((m) => `${m.doc.id}:${m.rank}`).sort()

/** Each section's answer for one case: what is recorded in write mode and compared otherwise. */
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
  matcher: (c) => c.steps.map((q: SearchQuery) => sorted(matchAll(docsOf(c.documents), q))),
  nameParts: (c) => nameParts(c.name, matchedText(c.text)),
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
        it(`#${i} ${c.rule}`, () => {
          const got = answer(c)
          if (WRITE) c.expected = got
          else expect(got).toEqual(c.expected)
        })
      })
    })
  }

  // The matcher's recorded answers are fresh scans; the field's matcher, remembering and narrowing, must give each of them.
  describe('matcher, as a field types', () => {
    ;(vectors.matcher as any[]).forEach((c, i) => {
      it(`#${i} ${c.rule}`, () => {
        const match = createMatcher()
        const docs = docsOf(c.documents)
        const got = c.steps.map((q: SearchQuery) => sorted(match(docs, q)))
        expect(got).toEqual(WRITE ? answers.matcher(c) : c.expected)
      })
    })
  })

  afterAll(() => {
    if (WRITE) writeFileSync(FILE, JSON.stringify(vectors, null, 2) + '\n')
  })
})
