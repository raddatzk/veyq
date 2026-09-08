// Async load-state (web) — one owner of the hand-rolled `loading/error + try/finally` a load-on-mount panel
// repeats (cr-0155, audit #74/P3d). Wraps a loader in `pending`/`error` tracking, runs it on mount by default,
// and calls an optional `onError` hook (e.g. `handle401(router)`) so the 401-redirect isn't re-hand-rolled.
// Thin on purpose: a loader may return a value (`data`) OR just set its own refs; callers derive their own
// `loadError` view from `error` — the shape differs (a bool here, the `notAuthorized | failed` enum there, see
// `loadErrorKind`), so the primitive stays un-opinionated about it.
import { ref, shallowRef, onMounted, type Ref } from 'vue'
import { httpStatus, HttpStatus } from '../httpError'

export interface AsyncDataOptions {
  /** Run the loader on component mount (default true). Pass false to trigger it yourself via `reload`. */
  immediate?: boolean
  /** Side-effect on failure, awaited before settling — e.g. `handle401(router)`. The raw error is also in `error`. */
  onError?: (e: unknown) => void | Promise<void>
}

export function useAsyncData<T>(
  loader: () => Promise<T>,
  options: AsyncDataOptions = {},
): { data: Ref<T | undefined>; pending: Ref<boolean>; error: Ref<unknown>; reload: () => Promise<void> } {
  const data = ref() as Ref<T | undefined>
  // Pending from creation when it will auto-run, so the first render shows the loading state (what every call
  // site's `loading = ref(true)` encoded) rather than a flash of the empty/undefined result before `onMounted`.
  const pending = ref(options.immediate !== false)
  // shallowRef, not ref: the raw error wraps a native `Response`; a deep-reactive proxy over it breaks the
  // later `decodeHttpError(error)` (calling `Response.clone()` through a Proxy throws "Illegal invocation").
  const error = shallowRef<unknown>(null)
  async function reload() {
    error.value = null
    pending.value = true
    try {
      data.value = await loader()
    } catch (e) {
      error.value = e
      await options.onError?.(e)
    } finally {
      pending.value = false
    }
  }
  if (options.immediate !== false) onMounted(reload)
  return { data, pending, error, reload }
}

/**
 * Classify a load failure into a panel's `loadError` enum: `notAuthorized` for a 403 (signed in and inside the
 * scope, but lacking the capability — e.g. a member who holds no grant yet), `failed` for anything else, and
 * `null` for no error OR a 401 (the redirect handles that, so the panel shows nothing). The 403-vs-other split
 * the member/detail panels repeat, in one place.
 *
 * A caller who is **not a member** never lands on `notAuthorized`: the server answers 404 for them, identical to
 * a scope that does not exist (ADR 0042), so they fall into `failed`. That is correct — do not "fix" it by
 * mapping 404 to `notAuthorized`, which would re-create client-side the existence oracle the server closed.
 */
export function loadErrorKind(e: unknown): 'notAuthorized' | 'failed' | null {
  if (e == null) return null
  const status = httpStatus(e)
  if (status === HttpStatus.unauthorized) return null
  if (status === HttpStatus.forbidden) return 'notAuthorized'
  return 'failed'
}
