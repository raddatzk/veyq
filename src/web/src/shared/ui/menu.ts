// The layer's menu model (ADR 0107): what `VeyqMenu` lists and `VeyqBreadcrumb` trails. Only the keys the app uses.
import type { InjectionKey } from 'vue'
import type { VeyqIconName } from './icons'

export interface VeyqMenuItem {
  key?: string
  label?: string
  icon?: VeyqIconName
  /** Runs on a pick; handed the DOM event, so a link-like entry can let a modified click through. */
  command?: (event: Event) => void
  /** A real link: a modified click opens it elsewhere. */
  url?: string
  target?: string
  /** A submenu. */
  items?: VeyqMenuItem[]
  separator?: boolean
  disabled?: boolean
  visible?: boolean
  class?: string
  badge?: string
}

/** A `VeyqBreadcrumb`'s entries as drop targets (its `drop-target`): `over` answers a `dragenter`/`dragover` on an entry - true
 *  where it takes this drag (and it has told the event so), and the entry is drawn lit -, `drop` a drop on it. */
export interface VeyqDropTarget {
  over(item: VeyqMenuItem, event: DragEvent): boolean
  drop(item: VeyqMenuItem, event: DragEvent): void
}

/** How an entry of a `VeyqMenu` (or of its submenus) runs its pick: the menu closes and gives focus back to its opener
 *  first, then `run` runs (`VeyqMenu.vue`). */
export const MENU_PICK: InjectionKey<(run: () => void) => void> = Symbol('veyq-menu-pick')
