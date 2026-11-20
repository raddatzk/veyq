// What an application registers lazily (ADR 0079 Consequences, "the entry chunk carries the descriptors, not the
// applications"): the registry holds every hook and component from the composition root on, before the first route - but
// the code behind one is fetched only when it is first called or drawn. An application names its own modules by a literal
// `import('./…')` in its `register.ts`: no glob, no computed specifier, so what runs is still exactly what the build saw
// (ADR 0079 Decision 5), only split into chunks the browser loads on first use. A component is Vue's
// `defineAsyncComponent`; an async hook is `lazyHook` below.
//
// Only what answers a Promise can wait for its chunk: the registry's synchronous answers (a kind's rights, its rows, its
// `validateContent`, its search facets, `onScopeReset`) stay in the entry chunk with the descriptor.

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- any hook's parameters: the type is the loaded hook's own
type AsyncHook = (...args: any[]) => Promise<unknown>

/** An async hook whose code is fetched on its first call; `load` is the hook it calls, loaded - what a registration test
 *  compares with the module's own export. */
export type LazyHook<F extends AsyncHook> = F & { readonly load: () => Promise<F> }

/** An async hook whose code is fetched on its first call: `load` answers the real hook (a dynamic import of the module
 *  that holds it). A failed fetch rejects the call, as the hook's own failure would. */
export function lazyHook<F extends AsyncHook>(load: () => Promise<F>): LazyHook<F> {
  const hook = async (...args: Parameters<F>) => (await load())(...args)
  return Object.assign(hook as F, { load })
}
