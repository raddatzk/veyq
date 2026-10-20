// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { useUndo } from './useUndo'

/** Mount a component that runs `factory()` in setup and exposes the result (so onBeforeUnmount fires on unmount). */
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

describe('useUndo', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('arms a window, then commits via onExpire when it elapses', () => {
    const onExpire = vi.fn()
    const { api } = harness(() => useUndo<{ id: string }>({ timeoutMs: 6000, onExpire }))
    expect(api.pending.value).toBeNull()
    api.arm({ id: 'a' })
    expect(api.pending.value).toEqual({ id: 'a' })
    vi.advanceTimersByTime(5999)
    expect(onExpire).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(onExpire).toHaveBeenCalledWith({ id: 'a' })
    expect(api.pending.value).toBeNull()
  })

  it('cancel stops the commit and hands the payload back to restore', () => {
    const onExpire = vi.fn()
    const { api } = harness(() => useUndo<{ id: string }>({ timeoutMs: 6000, onExpire }))
    api.arm({ id: 'a' })
    expect(api.cancel()).toEqual({ id: 'a' })
    expect(api.pending.value).toBeNull()
    vi.advanceTimersByTime(10000)
    expect(onExpire).not.toHaveBeenCalled()
  })

  it('cancel returns null when nothing is pending', () => {
    const { api } = harness(() => useUndo<{ id: string }>({ timeoutMs: 6000 }))
    expect(api.cancel()).toBeNull()
  })

  it('re-arming commits the superseded payload before opening the new window', () => {
    const onExpire = vi.fn()
    const { api } = harness(() => useUndo<{ id: string }>({ timeoutMs: 6000, onExpire }))
    api.arm({ id: 'a' })
    api.arm({ id: 'b' })
    expect(onExpire).toHaveBeenCalledTimes(1)
    expect(onExpire).toHaveBeenCalledWith({ id: 'a' })
    expect(api.pending.value).toEqual({ id: 'b' })
    vi.advanceTimersByTime(6000)
    expect(onExpire).toHaveBeenCalledTimes(2)
    expect(onExpire).toHaveBeenLastCalledWith({ id: 'b' })
  })

  it('expire commits immediately and is a no-op when nothing is pending', () => {
    const onExpire = vi.fn()
    const { api } = harness(() => useUndo<{ id: string }>({ timeoutMs: 6000, onExpire }))
    api.expire()
    expect(onExpire).not.toHaveBeenCalled()
    api.arm({ id: 'a' })
    api.expire()
    expect(onExpire).toHaveBeenCalledWith({ id: 'a' })
    expect(api.pending.value).toBeNull()
    // The armed timer must not fire a second time after a manual expire.
    vi.advanceTimersByTime(6000)
    expect(onExpire).toHaveBeenCalledTimes(1)
  })

  it('commits a still-pending payload on unmount', () => {
    const onExpire = vi.fn()
    const { api, wrapper } = harness(() => useUndo<{ id: string }>({ timeoutMs: 6000, onExpire }))
    api.arm({ id: 'a' })
    wrapper.unmount()
    expect(onExpire).toHaveBeenCalledWith({ id: 'a' })
  })
})
