// The web's side of a node's plans (`@veyq/core-js/navigation`, ADR 0154 decision 2, ADR 0155 decision 7.2): which of a
// node's actions a header offers and in which group, and a level row's menu, are the platform's Kotlin
// (`src/mobile/core/…/navigation/NodeHeader.kt`, `LevelPlan.kt`), the plans every app draws, held by
// contracts/platform/plans/v1-vectors.json. This file holds no rule. It hands the core what the page and core's actions
// offer — each standard or core action granted by its presence (`NodeHeader.Rights`, one flag per id), every other action
// the page's own entry — and draws exactly the entries the plan returns, each with the web's own action (its label, icon,
// handler): an action the core does not know, or one it offered and the plan left out, throws (a web entry of its own is
// the defect ADR 0154 decision 2 names). What stays the browser's: how many of a header's buttons fit (`fitHeaderActions`,
// ADR 0110) and which row entries are drawn inline (at most {@link MAX_FIRST_ACTIONS} marked `first`).
import * as core from '@veyq/core-js/navigation'
import type { RightsShape } from '@veyq/core-js/navigation'
import type { NodeHeaderAction } from './nodeHeader'

/** How many `first` entries a row draws as buttons beside its menu (the heart, a file's download); the rest stay in it. */
export const MAX_FIRST_ACTIONS = 2

const NO_RIGHTS: Readonly<RightsShape> = Object.freeze({
  favorite: false, share: false, rename: false, move: false, cover: false, copy: false, download: false, report: false, delete: false, offline: false,
})

/** The offered actions by id, the rights their presence grants and the page's own entries, in the given order. */
function offered<A extends NodeHeaderAction>(actions: readonly A[]): { byId: Map<string, A>; rights: RightsShape; own: string[] } {
  const byId = new Map<string, A>()
  const rights: Record<string, boolean> = { ...NO_RIGHTS }
  const own: string[] = []
  for (const a of actions) {
    if (!a.shown) continue
    if (byId.has(a.id)) throw new Error(`two actions named ${a.id} on one node`)
    byId.set(a.id, a)
    if (Object.hasOwn(NO_RIGHTS, a.id)) rights[a.id] = true
    else own.push(a.id)
  }
  return { byId, rights: rights as unknown as RightsShape, own }
}

/** Each entry of a plan's groups as the web's action, every offered action drawn exactly once. */
function drawn<A>(byId: Map<string, A>, groups: readonly (readonly string[])[]): A[][] {
  const left = new Set(byId.keys())
  const out = groups.map((ids) =>
    ids.map((id) => {
      const a = byId.get(id)
      if (!a || !left.delete(id)) throw new Error(`the plan names ${id}, which no action offers`)
      return a
    }),
  )
  if (left.size) throw new Error(`offered but not in the plan: ${[...left].join(', ')}`)
  return out
}

/** A header's plan over core's and the page's actions (the shown ones) in the kind's `order`: the entries that may be
 *  buttons, in order, then the menu's two groups — the second, after a separator, ends with the delete. */
export function headerPlan<A extends NodeHeaderAction>(actions: readonly A[], order: readonly string[] = []): { buttons: A[]; menu: A[]; last: A[] } {
  const o = offered(actions)
  const p = core.headerPlan(o.rights, order, o.own, null)
  const [buttons, menu, last] = drawn(o.byId, [p.buttons, p.menu, p.last])
  return { buttons: buttons!, menu: menu!, last: last! }
}

/** A row's plan over core's and the row's actions (the shown ones): at most {@link MAX_FIRST_ACTIONS} of the menu's entries
 *  marked `first` drawn as buttons, in the menu's order; the rest of the menu, then after a separator its last group. */
export function rowPlan<A extends NodeHeaderAction & { first?: boolean }>(actions: readonly A[]): { buttons: A[]; menu: A[]; last: A[] } {
  const o = offered(actions)
  const p = core.rowPlan(o.rights, o.own)
  const [menu, last] = drawn(o.byId, [p.menu, p.last])
  const buttons = menu!.filter((a) => a.first === true).slice(0, MAX_FIRST_ACTIONS)
  return { buttons, menu: menu!.filter((a) => !buttons.includes(a)), last: last! }
}
