// A test's own application in a registry (`core/applications/registry.ts`): every kind is registered through an
// application's registrar, so a test that registers made-up kinds does so through `testApp` - one application per id and
// registry, `test` unless a test names another (the real one's id, `MEMORIES`, where the host reads the capability
// register's switch for it).
//
// The registrar is remembered per registry object AND re-asked of it: a test file that swaps the registry behind a
// delegating `Proxy` per test (`holder.reg = createRegistry()`) hands the same object each time, and a fresh registry
// behind it takes the application again - the registrar of the previous one would write into a registry gone.
import type { ApplicationRegistrar, KindDescriptor, Registry } from '../core/applications/registry'

const known = new WeakMap<Registry, Map<string, ApplicationRegistrar>>()

/** The registrar of test application `id` in `registry`, named on first use (`name`: its name's i18n key). */
export function testApp(registry: Registry, id = 'test', name = `${id}.name`): ApplicationRegistrar {
  const mine = known.get(registry) ?? new Map<string, ApplicationRegistrar>()
  known.set(registry, mine)
  if (!registry.applications().some((a) => a.id === id)) mine.set(id, registry.registerApplication({ id, name }))
  const app = mine.get(id)
  if (!app) throw new Error(`application ${id} was named in this registry by someone else than testApp`)
  return app
}

/** Register `descriptor` (with its `kind`, as the registry stores it) as a kind of test application `id`. */
export function testKind(
  registry: Registry,
  descriptor: Omit<KindDescriptor, 'contentSchema'> & { contentSchema?: { application?: string; version: number } },
  id = 'test',
  name?: string,
): void {
  const { kind, contentSchema, ...rest } = descriptor
  testApp(registry, contentSchema?.application ?? id, name).kind(kind, { ...rest, ...(contentSchema ? { contentSchema: { version: contentSchema.version } } : {}) })
}
