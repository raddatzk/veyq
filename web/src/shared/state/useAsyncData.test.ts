// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest'
import { httpError } from '../../test/httpError'
import { defineComponent, h } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { useAsyncData, loadErrorKind } from './useAsyncData'

/** Mount a component that runs `factory()` in setup and exposes the result, so `onMounted` fires. */
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


describe('useAsyncData', () => {
  it('runs the loader on mount, toggling pending and exposing data', async () => {
    const { api } = harness(() => useAsyncData(async () => 'hi'))
    expect(api.pending.value).toBe(true) // onMounted fired reload(), now awaiting the loader
    expect(api.data.value).toBeUndefined()
    await flushPromises()
    expect(api.pending.value).toBe(false)
    expect(api.data.value).toBe('hi')
    expect(api.error.value).toBeNull()
  })

  it('does not run on mount when immediate: false; starts not pending; reload triggers it', async () => {
    const loader = vi.fn(async () => 'later')
    const { api } = harness(() => useAsyncData(loader, { immediate: false }))
    expect(api.pending.value).toBe(false) // immediate:false → not pending until reload is called
    await flushPromises()
    expect(loader).not.toHaveBeenCalled()
    await api.reload()
    expect(api.data.value).toBe('later')
  })

  it('stores the error and calls onError on failure', async () => {
    const err = httpError(401)
    const onError = vi.fn()
    const { api } = harness(() => useAsyncData(async () => Promise.reject(err), { onError }))
    await flushPromises()
    expect(api.error.value).toBe(err)
    expect(api.data.value).toBeUndefined()
    expect(api.pending.value).toBe(false)
    expect(onError).toHaveBeenCalledWith(err)
  })

  it('reload clears a prior error before re-running', async () => {
    let ok = false
    const { api } = harness(() =>
      useAsyncData(async () => {
        if (!ok) throw httpError(500)
        return 'ok'
      }),
    )
    await flushPromises()
    expect(api.error.value).not.toBeNull()
    ok = true
    await api.reload()
    expect(api.error.value).toBeNull()
    expect(api.data.value).toBe('ok')
  })
})

describe('loadErrorKind', () => {
  it('maps status to the panel enum', () => {
    expect(loadErrorKind(null)).toBeNull()
    expect(loadErrorKind(httpError(401))).toBeNull() // redirect handles it
    expect(loadErrorKind(httpError(403))).toBe('notAuthorized')
    expect(loadErrorKind(httpError(500))).toBe('failed')
    expect(loadErrorKind(new Error('network'))).toBe('failed') // status 0
  })
})
