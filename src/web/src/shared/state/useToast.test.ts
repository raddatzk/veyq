// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'

// Stub the layer's toast channel (`shared/ui/toast`): this wrapper's job is WHAT it hands the channel - which toast,
// how long it stays, whether it carries an undo action. Where the channel draws it (the default region or the undo
// group) is the layer's, keyed on `onUndo`.
const { add, removeUndo } = vi.hoisted(() => ({ add: vi.fn(), removeUndo: vi.fn() }))
vi.mock('../ui/toast', () => ({ UNDO_GROUP: 'undo', useToastChannel: () => ({ add, removeUndo }) }))

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
    removeUndo.mockClear()
  })

  it('success() adds an auto-dismissing success toast in the default region', () => {
    harness(() => useToast()).success('Saved.')
    expect(add).toHaveBeenCalledTimes(1)
    const msg = add.mock.calls[0][0]
    expect(msg.severity).toBe('success')
    expect(msg.summary).toBe('Saved.')
    expect(msg.onUndo, 'the default region: no undo action').toBeUndefined()
    expect(msg.life).toBeGreaterThan(0)
  })

  it('error() adds an error toast in the default region that stays until closed', () => {
    harness(() => useToast()).error('No space left.')
    const msg = add.mock.calls[0][0]
    expect(msg.severity).toBe('error')
    expect(msg.summary).toBe('No space left.')
    expect(msg.onUndo, 'the default region: no undo action').toBeUndefined()
    expect(msg.life).toBeUndefined()
  })

  it('showUndo() hands the channel an undo toast carrying the summary, life, and onUndo', () => {
    const onUndo = vi.fn()
    harness(() => useToast()).showUndo({ summary: 'Moved to trash', onUndo, life: 6000 })
    expect(add).toHaveBeenCalledTimes(1)
    const msg = add.mock.calls[0][0]
    expect(msg.summary).toBe('Moved to trash')
    expect(msg.life).toBe(6000)
    expect(msg.onUndo).toBe(onUndo)
  })

  it('dismissUndo() clears the undo toast', () => {
    harness(() => useToast()).dismissUndo()
    expect(removeUndo).toHaveBeenCalledOnce()
  })

  it('re-exports the layer\'s undo group', () => {
    expect(UNDO_GROUP).toBe('undo')
  })
})
