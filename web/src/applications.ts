// The composition root (ADR 0079): the ONE file outside an application that
// may import it — exempt from the web core-import guard by this exact path, never by directory. It statically
// imports each application and registers it into the registry; no glob, no computed import (ADR 0079 Decision 5).
// `main.ts` calls it before `boot()` resolves the first route; `src/test/setup.ts` calls it for every suite.
// Write-once: a second call throws at the first re-registered kind, which is the point (the registry's header).
import { registry } from './core/applications/registry'
import { registerMemories } from './features/memory/register'
import { registerDepot } from './features/depot/register'

export function registerApplications(): void {
  registerMemories(registry)
  registerDepot(registry)
}
