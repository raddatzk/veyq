// The seam between the level view's "New" menu (and its drop target) and an application's row source (inbox #406, #407). A
// menu entry that is no create dialog - Depot's "Upload files" needs the picker and the upload flow - cannot be run by core,
// which imports no application. So the level keeps a small handler registry (`createLevelActions`), and the application's row
// factory registers, through the host it is handed (`LevelRowsHost.registerAction`), the handler for the action id its kind's
// `levelActions` entry declared. Core only calls the handler of the entry the viewer chose - or, for what is dropped, the one
// of an entry that declared `acceptsDrop`: dropped files go to a handler's `drop` (Depot's "Upload files"), a dropped directory
// to a handler's `dropDirectory` (Depot's "Upload folder…", inbox #424).
//
// The row factory is set up once per level view, in the level's setup, so its handlers exist for the level's whole life and do
// NOT depend on which application tab is shown: "Upload files" is never greyed out because another tab is open. The level
// removes them when it unmounts.
import { shallowReactive } from 'vue'
import type { DirectoryInput } from '../export/importSource'

export interface LevelActionHandler {
  /** Whether the action can run right now (e.g. the session's keys are unlocked, the matrix grants it). */
  enabled: () => boolean
  run: () => void
  /** Depot dropped onto the level (the entry declared `acceptsDrop`): core calls it only while `enabled()` says yes. */
  drop?: (files: File[]) => void
  /** A directory dropped onto the level that is no download to import (the entry declared `acceptsDrop`): core reads its
   *  files and calls it only while `enabled()` says yes. */
  dropDirectory?: (dir: DirectoryInput) => void
}

export interface LevelActions {
  /** The handler registered under `id`, or undefined. Reactive. */
  get(id: string): LevelActionHandler | undefined
  /** Register `handler` under `id`; the returned function removes it (only if it is still the registered one). */
  register(id: string, handler: LevelActionHandler): () => void
}

/** The level's handler registry. Called once by the level view. */
export function createLevelActions(): LevelActions {
  const handlers = shallowReactive(new Map<string, LevelActionHandler>())
  return {
    get: (id) => handlers.get(id),
    register(id, handler) {
      handlers.set(id, handler)
      return () => {
        if (handlers.get(id) === handler) handlers.delete(id)
      }
    },
  }
}
