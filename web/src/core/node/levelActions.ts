// The seam between the level view's "New" menu and an application's section (inbox #406). A menu entry that is no create
// dialog - Files' "Upload files" needs the section's hidden file input and its upload flow - cannot be run by core, which
// imports no application. So the level provides a small handler registry (`provideLevelActions`), and the application's
// section, while it is mounted, registers the handler for the action id its kind's `levelActions` entry declared
// (`useLevelAction`). Core only calls the handler of the entry the viewer chose.
//
// A section that is not mounted (another application's tab is shown) has no handler: the entry stays in the menu but
// DISABLED (`handles` false), never a click that does nothing. A section mounted without a level (a test mounting it
// alone) registers nothing and breaks nothing.
import { inject, onBeforeUnmount, provide, shallowReactive } from 'vue'

export interface LevelActionHandler {
  /** Whether the action can run right now (e.g. the session's keys are unlocked). */
  enabled: () => boolean
  run: () => void
  /** Files dropped onto the level (the entry declared `acceptsDrop`): core calls it only while `enabled()` says yes. */
  drop?: (files: File[]) => void
}

export interface LevelActions {
  /** The handler registered under `id`, or undefined (the section is not mounted). Reactive. */
  get(id: string): LevelActionHandler | undefined
  register(id: string, handler: LevelActionHandler): () => void
}

const KEY = Symbol('veyq-level-actions')

/** Create the level's handler registry and provide it to its sections. Called once by the level view. */
export function provideLevelActions(): LevelActions {
  const handlers = shallowReactive(new Map<string, LevelActionHandler>())
  const actions: LevelActions = {
    get: (id) => handlers.get(id),
    register(id, handler) {
      handlers.set(id, handler)
      return () => {
        if (handlers.get(id) === handler) handlers.delete(id)
      }
    },
  }
  provide(KEY, actions)
  return actions
}

/** Register `handler` for the level action `id` for the calling component's life. A no-op outside a level view. */
export function useLevelAction(id: string, handler: LevelActionHandler): void {
  const actions = inject<LevelActions | null>(KEY, null)
  if (!actions) return
  const unregister = actions.register(id, handler)
  onBeforeUnmount(unregister)
}
