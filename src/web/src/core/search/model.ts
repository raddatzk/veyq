// The search's documents (ADR 0125 decisions 2 and 6): what a member can find, as plaintext derived from what the client
// already opened - never a key, never a second source of truth. Core makes one document per node of every space it can open
// (its name and where it lives); an application adds facets to its own kinds' documents through the registry
// (`KindDescriptor.search`) and contributes documents of its own for the node on screen (`contribution.ts`: Memories' photos
// of the open Memory).
import { fold } from '../../shared/text/fold'
import type { VeyqIconName } from '../../shared/ui/icons'

/** Calendar years, both ends included: a Memory dated 2023-12-30 to 2024-01-02 is `{ from: 2023, to: 2024 }`. */
export interface YearSpan {
  from: number
  to: number
}

/** What a document carries beyond its name - each one a chip's predicate (`query.ts`). Every field is optional: a fact the
 *  object does not have is left out, never a placeholder. */
export interface SearchFacets {
  /** The years the object spans (a Memory's date range). */
  years?: YearSpan
  /** When it was captured, epoch milliseconds (a photo): its year matches a year chip, and the result row shows the day. */
  capturedAt?: number
  /** The places it names, as named (a Memory's place, a photo's place). */
  places?: readonly string[]
  /** The people in it, by user id (a Memory's participants) - never a name: names come from the directory when shown. */
  people?: readonly string[]
}

/** One searchable object. */
export interface SearchDocument {
  /** The space it lives in. */
  readonly scopeId: string
  /** A node's kind, or a contributed kind (`memories.photo`). */
  readonly kind: string
  /** A node's id, or the contributed object's (a medium's id). */
  readonly id: string
  /** The revision it was derived at: a moved revision rebuilds the document. */
  readonly revision: string
  readonly name: string
  /** The names of the nodes above it, outermost first - what a result row shows as where it lives. */
  readonly path: readonly string[]
  readonly facets: SearchFacets
  /** The node a contributed document belongs to (the open Memory); a node's own document: its own id. */
  readonly nodeId: string
  /** A contributed document's own opening (a photo's lightbox); a node opens through the navigation. */
  readonly open?: () => void
  /** A contributed document's icon; a node's comes from its kind. */
  readonly icon?: VeyqIconName
  /** `name` folded (`shared/text/fold.ts`), once. */
  readonly foldedName: string
  /** `facets.places` folded, once. */
  readonly foldedPlaces: readonly string[]
}

/** What an application contributes for the node on screen (`contribution.ts`): a document as the application knows it. */
export interface ContributedDocument {
  kind: string
  id: string
  revision: string
  name: string
  facets: SearchFacets
  icon?: VeyqIconName
  open: () => void
}

/** Core's one way to make a document, so its folded fields are always there. */
export function searchDocument(d: Omit<SearchDocument, 'foldedName' | 'foldedPlaces'>): SearchDocument {
  return { ...d, foldedName: fold(d.name), foldedPlaces: (d.facets.places ?? []).map(fold) }
}

/** The years a document spans: its own span, else its capture year (local time, as the result row shows the day). */
export function yearsOf(doc: Pick<SearchDocument, 'facets'>): YearSpan | null {
  if (doc.facets.years) return doc.facets.years
  const at = doc.facets.capturedAt
  if (at === undefined || !Number.isFinite(at)) return null
  const year = new Date(at).getFullYear()
  return { from: year, to: year }
}
