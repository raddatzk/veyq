// The Memories application's lifecycle hooks in the REAL registry of a node-environment test file, where
// `src/test/setup.ts` registers no application: the application is named first, as its own `register.ts` does - the
// registry refuses hooks under an id no application holds - and then its real hooks go in, so the test proves the
// platform reaches them THROUGH the registry.
import { registry } from '../core/applications/registry'
import { MEMORIES } from '../features/platform/capabilities'
import { CHAPTER_KIND, MEMORY_KIND } from '../features/memories/kinds'
import { memoriesLifecycle } from '../features/memories/lifecycle'

export function registerMemoriesLifecycle(): void {
  registry.registerApplication({ id: MEMORIES, name: 'memory.memories', kinds: [MEMORY_KIND, CHAPTER_KIND] })
  registry.registerLifecycle(MEMORIES, memoriesLifecycle)
}
