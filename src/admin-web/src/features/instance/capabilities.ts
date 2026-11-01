import { instanceApi } from '../../api/client'
import type { InstanceCapability, InstanceCapabilityRequirement, InstanceCapabilityText } from '../../api/generated/instance'
import { decodeHttpError } from '../../shared/httpError'

export type { InstanceCapability, InstanceCapabilityRequirement, InstanceCapabilityText } from '../../api/generated/instance'

/**
 * The capability register at the instance level (ADR 0088 decision 12, uc.admin.switch-capabilities) —
 * Server-Owner only. Every entry in catalog order, each parent before its children, with its own switch (`off`),
 * the AND along its path (`effective`), and its title, purpose and effect-when-off in English and German.
 *
 * Those words are the catalog's (`contracts/platform/capabilities.json`, ADR 0088 decision 13), served by the
 * backend. The console and the CLI show them as served and never keep a copy: a text written here would be a
 * second source that drifts from what the switch actually does.
 */
export function listInstanceCapabilities(): Promise<InstanceCapability[]> {
  return instanceApi().listInstanceCapabilities()
}

/**
 * Switch one entry off or back on, without a restart, and resolve to the entry as it now stands. Idempotent; the
 * last writer wins. Refusals: 404 `capability_not_found` (nothing is stored), 400 `invalid_request`.
 */
export function setInstanceCapability(capabilityId: string, off: boolean): Promise<InstanceCapability> {
  return instanceApi().setInstanceCapability({ capabilityId, instanceCapabilityUpdate: { off } })
}

/**
 * Set one requirement on an entry for the instance (ADR 0088 decision 8): `1` for a flag, else a number of days (1 to
 * 3650). It acts at once, existing links and sessions included as its `whenSet` says. Refusals: 404
 * `requirement_not_found`, 400 `invalid_request`.
 */
export function setInstanceCapabilityRequirement(capabilityId: string, requirement: string, value: number): Promise<InstanceCapability> {
  return instanceApi().setInstanceCapabilityRequirement({ capabilityId, requirement, instanceCapabilityRequirementUpdate: { value } })
}

/** Clear the instance's value of one requirement; a tenant's own stays in effect for that tenant. */
export function clearInstanceCapabilityRequirement(capabilityId: string, requirement: string): Promise<InstanceCapability> {
  return instanceApi().clearInstanceCapabilityRequirement({ capabilityId, requirement })
}

/** A requirement's value as the CLI prints it: `on`, `<n> days`, or `not set` — fixed tokens, as the state token is. */
export function requirementValue(r: InstanceCapabilityRequirement): string {
  if (r.value === null || r.value === undefined) return 'not set'
  return r.kind === 'flag' ? 'on' : `${r.value} days`
}

/** Decode a failed capability call into `{ status, code }` for UI mapping (the refusals named above). */
export function capabilityError(e: unknown): Promise<{ status: number; code: string | null }> {
  return decodeHttpError(e)
}

/** A language the catalog writes every entry's words in. */
export type CapabilityLocale = keyof InstanceCapabilityText

/** One of an entry's served texts in the given language. */
export function inLocale(text: InstanceCapabilityText, locale: CapabilityLocale): string {
  return text[locale]
}

/**
 * Where an entry stands, as the console and the CLI show it:
 *   - `on`         — it and every ancestor are on;
 *   - `off`        — its own switch is off;
 *   - `offThrough` — its own switch is on, but an ancestor's is off; `through` is the NEAREST such ancestor, the
 *                    one whose switch brings this entry back.
 */
export type CapabilityState =
  | { kind: 'on' }
  | { kind: 'off' }
  | { kind: 'offThrough'; through: InstanceCapability }
  | { kind: 'notConfigured' }

/** An entry placed in the tree: how many of its ancestors are entries themselves, and its state. */
export type CapabilityNode = { entry: InstanceCapability; depth: number; state: CapabilityState }

/** The ancestors of an entry that are entries themselves, nearest first. The id is the path: each dotted prefix
 *  that is an entry is an ancestor, a one-segment one included — an application (`memories`) is the parent of its
 *  functions (`memories.places`), while `core` is no entry and so no parent. The backend's `selfAndAncestors` reads
 *  the same prefixes; stopping at two segments hung every application's functions flat beside it. */
function ancestorsOf(id: string, byId: Map<string, InstanceCapability>): InstanceCapability[] {
  const segments = id.split('.')
  const found: InstanceCapability[] = []
  for (let n = segments.length - 1; n >= 1; n -= 1) {
    const ancestor = byId.get(segments.slice(0, n).join('.'))
    if (ancestor) found.push(ancestor)
  }
  return found
}

/**
 * The register as a tree, in the served order (a parent before its children, so a flat list with a depth IS the
 * tree). The state follows the server's `effective`, never an AND recomputed here: the client only names which
 * ancestor it is off through. A server that said `effective: false` with no ancestor switched off would be out
 * of contract; that entry reads as off rather than on, the half of the answer that is certainly true.
 */
export function capabilityTree(entries: InstanceCapability[]): CapabilityNode[] {
  const byId = new Map(entries.map((e) => [e.id, e]))
  return entries.map((entry) => {
    const ancestors = ancestorsOf(entry.id, byId)
    return { entry, depth: ancestors.length, state: stateOf(entry, ancestors) }
  })
}

function stateOf(entry: InstanceCapability, ancestors: InstanceCapability[]): CapabilityState {
  if (entry.off) return { kind: 'off' }
  // An external service with no address (ADR 0088 decision 7) is not served whatever its switch says.
  if (entry.serviceConfigured === false) return { kind: 'notConfigured' }
  if (entry.effective) return { kind: 'on' }
  const through = ancestors.find((a) => a.off)
  return through ? { kind: 'offThrough', through } : { kind: 'off' }
}

/** Whether any entry hangs below this one — its descendants' `effective` changes when it is switched. */
export function hasDescendants(id: string, entries: InstanceCapability[]): boolean {
  return entries.some((e) => e.id.startsWith(`${id}.`))
}
