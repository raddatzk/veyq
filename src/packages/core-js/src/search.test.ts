import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../dist')
if (!existsSync(resolve(dist, 'kotlin_veyq_mobile_core.mjs'))) {
  throw new Error('dist/ is missing: run `pnpm run build` (the core compiled by Gradle) before the check')
}
// The package's own entry, as the web imports it.
const s = await import('@veyq/core-js/search')
type DocumentShape = import('@veyq/core-js/search').DocumentShape
type ChipShape = import('@veyq/core-js/search').ChipShape

/* eslint-disable @typescript-eslint/no-explicit-any -- the vectors are JSON, read case by case */
// repo contracts/platform/search/v1-vectors.json (4 levels up from src/packages/core-js/src/)
const vectors = JSON.parse(readFileSync(resolve(here, '../../../../contracts/platform/search/v1-vectors.json'), 'utf8')) as any

// The search's façade against the vectors' pure sections (ADR 0146 decision 1, ADR 0155 decision 6): the shipped module
// answers every case the core's SearchVectorsTest answers on the JVM and natively. Memories' `facets` and `medium` run the
// web's own functions and stay in the web's vectors test.
const docOf = (j: any): DocumentShape => ({ ...j, nodeId: j.nodeId ?? j.id, path: j.path ?? [], facets: j.facets ?? {} })
const corpusDocs: DocumentShape[] = vectors.corpus.map(docOf)
const docsOf = (d: any): DocumentShape[] => (d === 'corpus' ? corpusDocs : (d as any[]).map(docOf))
const collator = new Intl.Collator(vectors.collator.locale, { numeric: vectors.collator.numeric, sensitivity: vectors.collator.sensitivity })

const answers: Record<string, (c: any) => unknown> = {
  fold: (c) => ({ fold: s.fold(c.input), map: s.foldMap(c.input) }),
  tails: (c) => s.tailsOf(c.text),
  wordPrefix: (c) => s.wordPrefix(c.name, c.prefix),
  suggest: (c) => {
    const src = vectors.suggestSources
    const sources = { corpus: s.corpus(docsOf(src.documents)), spaces: src.spaces, people: src.people, kinds: src.kinds, ...(c.months === false ? {} : { months: src.months }) }
    return s.suggestChips(c.text, c.chips, sources).map((x) => ({ key: s.chipKey(x.chip), chip: x.chip, text: x.text }))
  },
  match: (c) => {
    const docs = docsOf(c.documents)
    const found = s.search(s.corpus(docs), c.query, c.context, c.limit ?? null, null, collator.compare)
    const t = s.matchedText(c.query.text)
    return {
      results: found.positions.map((p) => {
        const reason = s.reasonKeyword(docs[p], t)
        return reason === null ? { id: docs[p].id } : { id: docs[p].id, reason }
      }),
      total: found.total,
    }
  },
  nameParts: (c) => s.nameParts(c.name, s.matchedText(c.text)),
}

describe('the search vectors through the façade (ADR 0155 decision 6)', () => {
  for (const [section, answer] of Object.entries(answers)) {
    describe(section, () => {
      ;(vectors[section] as any[]).forEach((c, i) => {
        it(`#${i} ${c.rule}`, () => expect(answer(c)).toEqual(c.expected))
      })
    })
  }

  // The recorded answers are fresh scans; a field's matcher, remembering and narrowing, must list each of them. A
  // contribution lists only under its open node, so each of the corpus' two runs opens one and leaves the other's out.
  describe('matcher, as a field types', () => {
    ;(vectors.matcher as any[]).forEach((c, i) => {
      for (const [openNodeId, unlisted] of [['rome', 'm2'], ['paris', 'm1']]) {
        it(`#${i} under ${openNodeId}: ${c.rule}`, () => {
          const docs = docsOf(c.documents)
          const corpus = s.corpus(docs)
          const m = s.matcher()
          const context = { activeScopeId: 'active', openNodeId }
          const got = c.steps.map((q: any) => {
            const t = s.matchedText(q.text)
            return s.search(corpus, q, context, null, m, collator.compare).positions.map((p) => `${docs[p].id}:${s.textRank(docs[p], t)}`).sort()
          })
          expect(got).toEqual(c.expected.map((step: string[]) => step.filter((e) => !e.startsWith(`${unlisted}:`))))
        })
      }
    })
  })
})

describe('what the web passes crosses', () => {
  // The web orders equal ranks by its browser's collator (ADR 0155 § Consequences): Swedish sorts "Ä" after "Z", where the
  // core's own NameOrder (folded names, "apfel" before "zoe") would not. A façade that ignored the comparison is red here.
  it("orders equal ranks and places by the caller's comparison, not the core's", () => {
    const doc = (id: string, name: string): DocumentShape => ({ scopeId: 'a', kind: 'MEMORY', id, nodeId: id, name, path: [], facets: {} })
    const docs = [doc('1', 'Äpfel Trip'), doc('2', 'Zoe Trip')]
    const ids = (compare: (a: string, b: string) => number) =>
      s.search(s.corpus(docs), { chips: [], text: 'trip' }, { activeScopeId: 'a', openNodeId: null }, null, null, compare).positions.map((p) => docs[p].id)
    expect(ids(new Intl.Collator('sv').compare)).toEqual(['2', '1'])
    expect(ids(new Intl.Collator('de').compare)).toEqual(['1', '2']) // control: German sorts "Ä" as "A"
  })

  it('a capture time that is no finite number is none, as the web read it', () => {
    expect(s.yearsOf({ facets: { capturedAt: Number.NaN } })).toBeNull()
    expect(s.yearsOf({ facets: { capturedAt: Date.UTC(2024, 5, 15, 12) } })).toEqual({ from: 2024, to: 2024 })
  })

  it('a chip the web spells is the chip the core reads', () => {
    const chip: ChipShape = { type: 'in', scopeId: 's', nodeId: 'n', label: 'Rome', path: ['Trips'] }
    expect(s.chipKey(chip)).toBe('in:s:n')
    expect(() => s.chipKey({ type: 'day' })).toThrow(/not a search chip/)
  })
})

// The two handles are opaque (ADR 0155 decision 5): nothing in their definitions a caller could read or build.
describe('the handles', () => {
  const dts = readFileSync(resolve(dist, 'veyq-mobile-composition.d.mts'), 'utf8')
  for (const handle of ['SearchCorpus', 'SearchMatcher']) {
    it(`${handle} declares no member`, () => {
      const body = dts.match(new RegExp(`export declare class ${handle} \\{([^}]*)\\}`))?.[1]
      expect(body?.trim()).toBe('private constructor();')
    })
  }
})
