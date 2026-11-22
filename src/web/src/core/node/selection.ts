// The selection of a level (inbox #421, owner decisions 2026-10-14): a set of node ids the level holds while its Select mode
// is on. Core's, like the level itself: it names no kind, and what a selected node may do is its application's rights
// answer (`LevelRow.selection`), read where an action is offered - never here.
//
// The set belongs to the LEVEL, not to a tab (`LevelView.vue`): switching tabs keeps it, "All" adds what the tab on screen
// shows and "None" takes exactly that away again, so a part another tab hides stays selected and the count says so. The level
// clears it when the folder changes or the mode is left, and drops an id whose node is no longer at the level (moved away,
// deleted - here or by someone else) with `retain`.
//
// Memories' media selection (`MemoryBuckets.vue`) is a different set over a different list and is not built on this (inbox #427).
import { computed, shallowRef, type ComputedRef } from 'vue'
import type { TreeNode } from './tree'

/** A node the selection's delete reaches, with its kind's own write where it has one (`LevelRowSelection.remove`). */
export interface SelectedForDelete {
  node: TreeNode
  remove?: (nodeId: string) => Promise<void>
}

export interface Selection {
  /** The selected ids (a new set on every change: the view re-renders on it). */
  readonly ids: ComputedRef<ReadonlySet<string>>
  /** How many are selected. */
  readonly size: ComputedRef<number>
  has(id: string): boolean
  /** Select `id` if it is not, else take it away. */
  toggle(id: string): void
  /** Add every one of `ids` (what the tab on screen shows); the rest stays as it is. */
  all(ids: Iterable<string>): void
  /** Take every one of `ids` away (what the tab on screen shows); the rest stays as it is. */
  none(ids: Iterable<string>): void
  /** Empty the selection. */
  clear(): void
  /** Keep only the ids among `ids` (the nodes still at the level). */
  retain(ids: Iterable<string>): void
}

export function useSelection(): Selection {
  const set = shallowRef<ReadonlySet<string>>(new Set())
  const write = (next: Set<string>) => {
    if (next.size !== set.value.size || [...next].some((id) => !set.value.has(id))) set.value = next
  }
  return {
    ids: computed(() => set.value),
    size: computed(() => set.value.size),
    has: (id) => set.value.has(id),
    toggle(id) {
      const next = new Set(set.value)
      if (!next.delete(id)) next.add(id)
      set.value = next
    },
    all(ids) {
      write(new Set([...set.value, ...ids]))
    },
    none(ids) {
      const next = new Set(set.value)
      for (const id of ids) next.delete(id)
      write(next)
    },
    clear() {
      write(new Set())
    },
    retain(ids) {
      const keep = new Set(ids)
      write(new Set([...set.value].filter((id) => keep.has(id))))
    },
  }
}

/** The actions of a selection's bar, in its order (the apps' `SelectionAction`, inbox #1049). */
export const SELECTION_ACTIONS = ['move', 'copy', 'report', 'delete'] as const
export type SelectionActionId = (typeof SELECTION_ACTIONS)[number]

/** What one node of the level lets its selection do: its rights answer joined with the page's door for each action. */
export type SelectionRights = Readonly<Record<SelectionActionId, boolean>>

/** One action of the bar: shown, `enabled` where some selected node allows it, `cannot` of the selected it does not reach. */
export interface SelectionEntry {
  action: SelectionActionId
  enabled: boolean
  cannot: number
}

/**
 * The bar of a level's selection (`LevelView.vue`), over numbers only — the rule `contracts/platform/plans/v1-vectors.json`'s
 * `selection` cases hold here and in the apps' core (`LevelSelection.bar`): an action is shown where some node of the level
 * allows it (`rows`, every node at the level, whichever tab shows), enabled where some SELECTED node does, and says how many
 * of the selected it cannot reach (the report says nothing: anyone who sees a node reports it). `count` is how many of the
 * level are selected (an id no longer there counts nowhere), `hidden` how many of them the tab on screen (`shown`) hides;
 * `offered` whether the level offers Select at all.
 */
export function selectionBar(
  rows: ReadonlyMap<string, SelectionRights>, selected: Iterable<string>, shown: Iterable<string>,
): { offered: boolean; count: number; hidden: number; entries: SelectionEntry[] } {
  const chosen = [...new Set(selected)].filter((id) => rows.has(id))
  const on = new Set(shown)
  const entries = SELECTION_ACTIONS.filter((a) => [...rows.values()].some((r) => r[a])).map((a) => {
    const reach = chosen.filter((id) => rows.get(id)![a]).length
    return { action: a, enabled: reach > 0, cannot: a === 'report' ? 0 : chosen.length - reach }
  })
  return { offered: entries.length > 0, count: chosen.length, hidden: chosen.filter((id) => !on.has(id)).length, entries }
}

/** Several names or nouns as one phrase of `locale` ("“a”, “b” and “c”" / "„a“, „b“ und „c“"), for the selection's
 *  questions and summaries. */
export function joinList(locale: string, items: readonly string[]): string {
  return new Intl.ListFormat(locale, { style: 'long', type: 'conjunction' }).format(items)
}
