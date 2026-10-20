// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'

// Stub PrimeVue's service so the wrapper can run without the ToastService plugin / an injection context.
const add = vi.fn()
const removeGroup = vi.fn()
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add, removeGroup }) }))

import { useToast, UNDO_GROUP } from './useToast'

function harness<T>(factory: () => T) {
  const wrapper = mount(
    defineComponent({
      setup: (_, { expose }) => {
        expose({ api: factory() })
        return () => h('div')
      },
    }),
  )
  return (wrapper.vm as unknown as { api: T }).api
}

describe('useToast', () => {
  beforeEach(() => {
    add.mockClear()
    removeGroup.mockClear()
  })

  it('success() adds an auto-dismissing success toast in the default region', () => {
    harness(() => useToast()).success('Saved.')
    expect(add).toHaveBeenCalledTimes(1)
    const msg = add.mock.calls[0][0]
    expect(msg.severity).toBe('success')
    expect(msg.summary).toBe('Saved.')
    expect(msg.group).toBeUndefined()
    expect(msg.life).toBeGreaterThan(0)
  })

  it('error() adds an error toast in the default region that stays until closed', () => {
    harness(() => useToast()).error('No space left.')
    const msg = add.mock.calls[0][0]
    expect(msg.severity).toBe('error')
    expect(msg.summary).toBe('No space left.')
    expect(msg.group).toBeUndefined()
    expect(msg.life).toBeUndefined()
  })

  it('showUndo() targets the undo group and carries the summary, life, and onUndo', () => {
    const onUndo = vi.fn()
    harness(() => useToast()).showUndo({ summary: 'Moved to trash', onUndo, life: 6000 })
    const msg = add.mock.calls[0][0]
    expect(msg.group).toBe(UNDO_GROUP)
    expect(msg.summary).toBe('Moved to trash')
    expect(msg.life).toBe(6000)
    expect(msg.closable).toBe(false)
    expect(msg.onUndo).toBe(onUndo)
  })

  it('dismissUndo() clears the undo group', () => {
    harness(() => useToast()).dismissUndo()
    expect(removeGroup).toHaveBeenCalledWith(UNDO_GROUP)
  })
})
