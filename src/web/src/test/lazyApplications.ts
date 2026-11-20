// The applications' pages and dialogs are LAZY in the build (`core/applications/lazy.ts`, ADR 0079 Consequences): each is a
// `defineAsyncComponent` that fetches its chunk the first time it is drawn, and draws nothing until then. A test mounts the
// shell and asserts on the page synchronously, as it did while the composition root imported every component statically -
// so the test setup loads them all at registration, where those static imports ran (and under the test file's mocks, which
// are registered by then). After that, Vue draws an async component exactly as the component it loaded: what a browser
// sees once the chunk has arrived. `lazyApplications.test.ts` holds that every registered component is reached here.
import type { Component } from 'vue'
import type { Registry } from '../core/applications/registry'

type AsyncWrapper = { __asyncLoader?: () => Promise<unknown> }

/** Every component the registry holds: each kind's page and dialogs, the drop pages, the person sections, the item reviews. */
export function registeredComponents(registry: Registry): Component[] {
  const out = new Set<Component>()
  for (const kind of registry.kinds()) {
    const d = registry.kind(kind)
    if (!d.supported) continue
    for (const c of [d.detail, d.createDialog, d.shareDialog, registry.shareBodyFor(kind)]) if (c) out.add(c)
  }
  for (const c of [...registry.personSections(), ...registry.itemReviews()]) out.add(c)
  return [...out]
}

/** Load every lazy component the registry holds (a plain component is already loaded), so it draws on its first mount. */
export async function loadRegisteredComponents(registry: Registry): Promise<void> {
  await Promise.all(registeredComponents(registry).map((c) => (c as AsyncWrapper).__asyncLoader?.()))
}

/** What a lazy component or a lazy hook (`core/applications/lazy.ts`) loads - the module's own export, which a registration
 *  test compares with the one it imports; anything else as it is. */
export async function loaded<T>(x: T): Promise<unknown> {
  const lazy = x as (AsyncWrapper & { load?: () => Promise<unknown> }) | null | undefined
  if (typeof lazy?.__asyncLoader === 'function') return lazy.__asyncLoader()
  if (typeof lazy?.load === 'function') return lazy.load()
  return x
}

/** A kind's descriptor with its lazy components and hooks loaded (`loaded`): what a registration test matches. */
export async function loadedKind(registry: Registry, kind: string): Promise<Record<string, unknown>> {
  const d = registry.kind(kind) as unknown as Record<string, unknown>
  const out: Record<string, unknown> = { ...d }
  for (const key of ['detail', 'createDialog', 'shareDialog', 'rekeyContent', 'exportContent', 'importContent']) {
    if (d[key] !== undefined) out[key] = await loaded(d[key])
  }
  const cover = d.cover as { findStored?: unknown } | undefined
  if (cover?.findStored) out.cover = { ...cover, findStored: await loaded(cover.findStored) }
  return out
}
