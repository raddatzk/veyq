// The shape of a Memory's and a chapter's content from a download (Memories' own contracts/export/memories/content.schema.json),
// checked before an import writes anything (ADR 0082 decision 10): the registry's `validateContent` for both kinds, which
// core's dry run (`planImport`) calls synchronously - so it lives apart from `exportImport.ts`, whose download, import and copy
// are fetched on first use (`register.ts`). Pure: no network, no key.
import type { ContentProblem } from '../../core/applications/registry'
import type { ManifestContent } from '../../core/export/manifest'
import { trackActivityOf } from './track/trackLabel'

export const MEMORY_CONTENT = 'memories.memory'
export const CHAPTER_CONTENT = 'memories.chapter'
/** The version of the content schema this build writes and reads; its application is Memories' registered id (`register.ts`), the
 *  `<application>` part of both content types above. */
export const MEMORIES_CONTENT_VERSION = 1

/** The contract's `maxLength` / `maxItems` of Memories' fields (Memories' own `contracts/export/memories/content.schema.json`);
 *  `exportImport.test.ts` pins each to the schema. */
export const MEMORIES_LIMITS = { name: 1024, contributor: 1024, place: 1024, participant: 1024, participants: 100, trackName: 1024 } as const

const DATE = /^\d{4}-\d{2}-\d{2}$/
export const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const isPayload = (v: unknown): boolean => isObj(v) && typeof v.path === 'string' && typeof v.size === 'number'
const isNum = (v: unknown, min: number, max: number) => typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max

/** A `memories.memory` / `memories.chapter` content from a download, checked against its contract (decision 10) before the
 *  import writes anything: the shape of every field the import reads, and each string at its `maxLength`. Unknown fields are
 *  ignored, as the format promises. */
export function validateMemoryContent(content: ManifestContent): ContentProblem | null {
  const shape = (message: string): ContentProblem => ({ code: 'invalid_manifest', message })
  const long = (what: string, v: unknown, max: number): ContentProblem | null =>
    v === undefined ? null : typeof v !== 'string' ? shape(`${what} is not text`) : v.length > max ? { code: 'too_large', message: `${what} is longer than ${max}` } : null
  if (content.type !== MEMORY_CONTENT && content.type !== CHAPTER_CONTENT) return shape(`not a Memory or chapter: ${content.type}`)
  if (!Array.isArray(content.media)) return shape('media is not a list')
  for (const [i, m] of content.media.entries()) {
    if (!isObj(m) || !isPayload(m.file)) return shape(`medium ${i} has no file`)
    const problem = long(`medium ${i} name`, m.name, MEMORIES_LIMITS.name) ?? long(`medium ${i} contributor`, m.contributor, MEMORIES_LIMITS.contributor)
    if (problem) return problem
    if (m.capturedAt !== undefined && (typeof m.capturedAt !== 'string' || Number.isNaN(Date.parse(m.capturedAt)))) return shape(`medium ${i} capturedAt`)
    if (m.capturedAtCorrected !== undefined && m.capturedAtCorrected !== true) return shape(`medium ${i} capturedAtCorrected`)
    if (m.exif !== undefined && !isObj(m.exif)) return shape(`medium ${i} exif`)
    if (m.location !== undefined) {
      const l = m.location
      if (!isObj(l) || (l.lat !== undefined && !isNum(l.lat, -90, 90)) || (l.lon !== undefined && !isNum(l.lon, -180, 180))) return shape(`medium ${i} location`)
      const p = long(`medium ${i} place`, l.place, MEMORIES_LIMITS.place)
      if (p) return p
    }
    if (m.track !== undefined) {
      const tr = m.track
      if (!isObj(tr) || (tr.activity !== undefined && trackActivityOf(tr.activity) === null)) return shape(`medium ${i} track`)
      const n = long(`medium ${i} track name`, tr.name, MEMORIES_LIMITS.trackName)
      if (n) return n
    }
    if (m.audio !== undefined) {
      const a = m.audio
      const int = (v: unknown) => typeof v === 'number' && Number.isInteger(v) && v >= 0
      if (!isObj(a) || !int(a.durationMs) || !int(a.trimStartMs) || !int(a.trimEndMs)) return shape(`medium ${i} audio`)
    }
  }
  const d = content.date
  if (d !== undefined && (!isObj(d) || !['auto', 'single', 'range', 'ongoing'].includes(d.mode as string)
    || (d.from !== undefined && !(typeof d.from === 'string' && DATE.test(d.from))) || (d.to !== undefined && !(typeof d.to === 'string' && DATE.test(d.to))))) {
    return shape('date')
  }
  const l = content.location
  if (l !== undefined) {
    if (!isObj(l)) return shape('location')
    const p = long('place', l.name, MEMORIES_LIMITS.place)
    if (p) return p
  }
  const people = content.participants
  if (people !== undefined) {
    if (!Array.isArray(people)) return shape('participants is not a list')
    if (people.length > MEMORIES_LIMITS.participants) return { code: 'too_large', message: `more than ${MEMORIES_LIMITS.participants} participants` }
    for (const [i, p] of people.entries()) {
      const problem = long(`participant ${i}`, p, MEMORIES_LIMITS.participant)
      if (problem) return problem
    }
  }
  // A cover without a file (an older archive's photo reference by `mediaIndex`) is not restored, and not a refusal.
  const c = content.cover
  if (c !== undefined && (!isObj(c) || (c.file !== undefined && !isPayload(c.file)))) return shape('cover')
  const b = content.banner
  if (b !== undefined) {
    if (!isObj(b) || !isPayload(b.file) || (b.medium !== 'image' && b.medium !== 'video')) return shape('banner')
    if ((b.poster !== undefined && !isPayload(b.poster)) || (b.frame !== undefined && b.frame !== 'fill' && b.frame !== 'fit')) return shape('banner')
    if (b.focal !== undefined && (!isObj(b.focal) || !isNum(b.focal.x, 0, 1) || !isNum(b.focal.y, 0, 1))) return shape('banner focal')
  }
  return null
}
