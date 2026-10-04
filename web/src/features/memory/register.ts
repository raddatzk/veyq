// The Memories application's registration with the platform registry (ADR 0079). Called once, from the
// composition root (`web/src/applications.ts`), which is the only core file allowed to import this application.
// The registry is a parameter, not an import of the singleton, so a test can register into a fresh one.
import { NodeKind } from '../../api/generated/content'
import type { Registry } from '../../core/applications/registry'

export function registerMemories(registry: Registry): void {
  // A Memory is shareable (cr-0104); a chapter is a Memory of its own that is not (the backend rejects it).
  registry.registerKind({ kind: NodeKind.Memory, icon: 'pi pi-images', shareable: true })
  registry.registerKind({ kind: NodeKind.Chapter, icon: 'pi pi-images', shareable: false })
}
