// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest'
import { httpError } from '../../test/httpError'
import { defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { useAsyncAction } from './useAsyncAction'

/** Mount a component that runs `factory()` in setup and exposes the result. */
function harness<T>(factory: () => T) {
  const wrapper = mount(
    defineComponent({
      setup: (_, { expose }) => {
        const api = factory()
        expose({ api })
        return () => h('div')
      },
    }),
  )
  return { api: (wrapper.vm as unknown as { api: T }).api, wrapper }
}

/** A promise the test resolves/rejects by hand, to observe `pending` mid-flight. */
function deferred<T>() {
  let resolve!: (v: T) => void
  let reject!: (e: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('useAsyncAction', () => {
  it('returns the value on success and toggles pending across the call', async () => {
    const d = deferred<string>()
    const { api } = harness(() => useAsyncAction(async () => d.promise))
    expect(api.pending.value).toBe(false)
    const running = api.run()
    expect(api.pending.value).toBe(true)
    d.resolve('done')
    await expect(running).resolves.toBe('done')
    expect(api.pending.value).toBe(false)
    expect(api.error.value).toBeNull()
  })

  it('returns undefined on failure, stores the error, and calls onError', async () => {
    const err = httpError(409)
    const onError = vi.fn()
    const { api } = harness(() => useAsyncAction(async () => Promise.reject(err), { onError }))
    const result = await api.run()
    expect(result).toBeUndefined()
    expect(api.error.value).toBe(err)
    expect(api.pending.value).toBe(false)
    expect(onError).toHaveBeenCalledWith(err)
  })

  it('clears a prior error at the start of the next run', async () => {
    let fail = true
    const { api } = harness(() =>
      useAsyncAction(async () => {
        if (fail) throw new Error('boom')
        return 'ok'
      }),
    )
    await api.run()
    expect(api.error.value).not.toBeNull()
    fail = false
    await api.run()
    expect(api.error.value).toBeNull()
  })

  it('reset clears the error', async () => {
    const { api } = harness(() => useAsyncAction(async () => Promise.reject(new Error('x'))))
    await api.run()
    expect(api.error.value).not.toBeNull()
    api.reset()
    expect(api.error.value).toBeNull()
  })

  it('forwards run arguments to the action', async () => {
    const action = vi.fn(async (a: number, b: number) => a + b)
    const { api } = harness(() => useAsyncAction(action))
    await expect(api.run(2, 3)).resolves.toBe(5)
    expect(action).toHaveBeenCalledWith(2, 3)
    await flushPromises()
  })
})
