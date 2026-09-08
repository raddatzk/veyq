// Async action-state (web) — one owner of the `busy/error + try/finally` a form/dialog submit repeats
// (cr-0155, audit #74/P3d). `run(...args)` toggles `pending`, awaits the action, and on failure stores the raw
// error + calls the optional `onError` hook (compose `handle401(router)` + your message mapping there). It
// returns the action's value on success and `undefined` on failure, so the caller branches without a re-throw.
import { ref, shallowRef, type Ref } from 'vue'

export interface AsyncActionOptions {
  /** Side-effect on failure, awaited before `run` resolves — e.g. `handle401(router)` and/or a message map. */
  onError?: (e: unknown) => void | Promise<void>
}

export function useAsyncAction<Args extends unknown[], T>(
  action: (...args: Args) => Promise<T>,
  options: AsyncActionOptions = {},
): {
  run: (...args: Args) => Promise<T | undefined>
  pending: Ref<boolean>
  error: Ref<unknown>
  reset: () => void
} {
  const pending = ref(false)
  // shallowRef, not ref: the raw error wraps a native `Response`; a deep-reactive proxy over it breaks the
  // later `decodeHttpError(error)` (calling `Response.clone()` through a Proxy throws "Illegal invocation").
  const error = shallowRef<unknown>(null)
  async function run(...args: Args): Promise<T | undefined> {
    error.value = null
    pending.value = true
    try {
      return await action(...args)
    } catch (e) {
      error.value = e
      await options.onError?.(e)
      return undefined
    } finally {
      pending.value = false
    }
  }
  const reset = () => void (error.value = null)
  return { run, pending, error, reset }
}
