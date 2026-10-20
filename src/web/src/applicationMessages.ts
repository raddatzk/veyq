// The composition root's catalog half (ADR 0079, inbox #513): the ONE other file outside an application that may import
// one — and only its catalog (`features/<owner>/i18n.ts`, data and nothing else), which the web core-import guard holds by
// this exact path. It statically imports each application's catalog and adds it to core's one vue-i18n instance; no
// glob, no computed import (ADR 0079 Decision 5).
//
// **Why apart from `applications.ts`.** Importing the code half loads every application's components (and the map
// library, which needs a window); the catalog half loads data. So every test file gets the whole catalog — the
// node-environment suites too, which `src/test/setup.ts` gives no registry — and `main.ts` calls both.
// Write-once: a second call throws at the first namespace already held (`addApplicationMessages`).
import { addApplicationMessages } from './i18n'
import { memoriesMessages } from './features/memories/i18n'
import { depotMessages } from './features/depot/i18n'

export function registerApplicationMessages(): void {
  addApplicationMessages('memories', memoriesMessages)
  addApplicationMessages('depot', depotMessages)
}
