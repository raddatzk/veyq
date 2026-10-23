// The layer's menu model (ADR 0107): what `VeyqMenu` lists and `VeyqBreadcrumb` trails. Only the keys the app uses.
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
