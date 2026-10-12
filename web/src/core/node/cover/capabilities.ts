// What a viewer may do about a node's cover (inbox #411): ONE fail-closed capability function for EVERY kind that opts in
// (`KindDescriptor.cover`: a folder, a drop box, a Memory), because setting a cover is core's action, not an application's.
//
// - **Writers only.** A cover is a write to the node (a blob in its `COVER` slot and its sealed meta), so it needs what a
//   rename needs: a MEMBER of the scope who may write it. A share guest sets none - not in this step, for no guest role: the
//   server has no guest operation for it and the capability says so first, so a guest's view is the member's with the
//   action removed (share view = member view, inbox #401).
// - **Not during a pending deletion.** The space is read-only for its grace window (`access.readonly`), like every write.
// - **Keys loaded.** The cover is sealed under the node's key; while the scope's epoch keys are not unlocked (`epochs` null)
//   nothing can be sealed, so nothing is offered.
// - **A kind that opted in.** The kind's descriptor says `cover`; a kind no application registered (the unsupported row) or
//   one that did not opt in has none. The server's `NodeKindRules.takesCover` is the authority and refuses the rest.
// - **Not in a guest's identity folder.** Those folders belong to the drop box's guests, and a member only deletes or moves
//   them out (ADR 0080 decision 11).
import type { Access, CapabilityTarget } from '../../access'
import { registry } from '../../applications/registry'

export interface CoverCapabilities {
  /** Set or change the node's cover (and remove it). */
  setCover: boolean
}

const NONE: CoverCapabilities = Object.freeze({ setCover: false })

/** The cover capabilities of `access` for the node `at` describes (`targetOf`). Every right defaults to false. */
export function coverCapabilities(access: Access, at: Pick<CapabilityTarget, 'kind' | 'inIdentityFolder'>): CoverCapabilities {
  if (access.mode !== 'member' || access.readonly || !access.epochs) return NONE
  if (!at.kind || at.inIdentityFolder) return NONE
  const info = registry.kind(at.kind)
  return info.supported && info.cover ? { setCover: true } : NONE
}
