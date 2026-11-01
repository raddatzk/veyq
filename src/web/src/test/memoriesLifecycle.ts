// The Memories application's lifecycle hooks in the REAL registry of a node-environment test file, where
// `src/test/setup.ts` registers no application: the application is named first, as its own `register.ts` does - hooks
// go in only through an application's registrar - and then its real hooks go in, so the test proves the platform
// reaches them THROUGH the registry.
import { registry } from '../core/applications/registry'
import { MEMORIES } from '../features/platform/capabilities'
import { memoriesLifecycle } from '../features/memories/lifecycle'
import { testApp } from './testApplication'

export function registerMemoriesLifecycle(): void {
  testApp(registry, MEMORIES).lifecycle(memoriesLifecycle)
}
