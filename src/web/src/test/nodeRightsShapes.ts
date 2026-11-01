// The "never wider than core" property (owner 2026-11-01): every rights function - an application's, and core's own
// `folderRights` - starts from core's `nodeRights` and may only narrow it. These are the access shapes and rights targets
// such a test walks, and the check itself: the node rights an answer grants that core's answer for the same input denies.
import { nodeRights, type Access, type NodeRights, type RightsTarget } from '../core/access'
import { FOLDER_KIND } from '../core/node/kind'
import { guestAccessOf, memberAccessOf } from './models'

/** Core's node-generic rights, by the one name each carries everywhere. */
export const NODE_RIGHT_KEYS = ['share', 'shareContribute', 'shareUpload', 'rename', 'move', 'delete'] as const satisfies readonly (keyof NodeRights)[]

/** Every shape of `Access` a rights function must answer: a member with each switch and leaf off and a pending deletion
 *  (alone and with an application off), each guest role, an unknown role and an unknown mode. `applications` are the ids
 *  whose switch is tried off (the caller's application, and one that is not). */
export function accessShapes(applications: readonly string[]): [string, Access][] {
  return [
    ['member', memberAccessOf()],
    ['member, pending deletion', memberAccessOf({ readonly: true })],
    ['member, public shares off', memberAccessOf({ publicShares: false, publicSharesContribute: false, publicSharesDropBox: false })],
    ['member, contribute off', memberAccessOf({ publicSharesContribute: false })],
    ['member, drop box off', memberAccessOf({ publicSharesDropBox: false })],
    ...applications.flatMap((id): [string, Access][] => [
      [`member, ${id} off`, memberAccessOf({ applicationsOff: new Set([id]) })],
      [`member, ${id} off and pending deletion`, memberAccessOf({ readonly: true, applicationsOff: new Set([id]) })],
    ]),
    ['READ guest', guestAccessOf('READ')],
    ['READ_WRITE guest', guestAccessOf('READ_WRITE')],
    ['WRITE_ONLY guest', guestAccessOf('WRITE_ONLY')],
    ['an unknown role', guestAccessOf('OWNER')],
    ['an unknown mode', { mode: 'operator' } as unknown as Access],
  ]
}

/** Every rights target over `kinds` (the scope root and FOLDER always): in and out of an identity folder, shareable or not,
 *  with each parent a session may read (unknown, the scope root, a folder, another kind). */
export function rightsTargets(kinds: readonly string[]): RightsTarget[] {
  const out: RightsTarget[] = []
  for (const kind of [null, FOLDER_KIND, ...kinds]) {
    for (const inIdentityFolder of [false, true]) {
      for (const shareable of [false, true]) {
        for (const parentKind of [undefined, null, FOLDER_KIND, ...kinds]) out.push({ kind, inIdentityFolder, shareable, parentKind })
      }
    }
  }
  return out
}

/** The node rights `answer` grants where `core` says no - empty when the answer only narrows. */
export function widerThan(answer: Partial<Record<keyof NodeRights, unknown>>, core: NodeRights): string[] {
  return NODE_RIGHT_KEYS.filter((k) => answer[k] === true && !core[k])
}

/** Every (shape, target) where `rights` grants a node right core's `nodeRights` denies, named for the failure message. */
export function widerThanCore(
  rights: (access: Access, at: RightsTarget) => Partial<Record<keyof NodeRights, unknown>>,
  applications: readonly string[],
  kinds: readonly string[],
): string[] {
  const found: string[] = []
  for (const [name, access] of accessShapes(applications)) {
    for (const at of rightsTargets(kinds)) {
      const wider = widerThan(rights(access, at), nodeRights(access, at))
      if (wider.length) found.push(`${name} at ${JSON.stringify(at)}: ${wider.join(', ')}`)
    }
  }
  return found
}
