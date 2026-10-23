// @vitest-environment happy-dom
// Proves the `enableAutoUnmount(afterEach)` line in `setup.ts`: a component a test leaves mounted must not
// outlive the file's teardown.
//
// **The mechanism it pins.** A component whose `mounted()` arms a `setTimeout` it keeps no handle to cannot clear it
// in its own `beforeUnmount`. That timer is **Node's**, not happy-dom's (vitest does not copy happy-dom's timers onto
// the global), so `happyDOM.abort()` in vitest's teardown never cancels it. When it lands after the teardown has
// deleted the DOM globals and reaches for the DOM, it throws `ReferenceError: HTMLElement is not defined`, and vitest
// fails the whole run on that unhandled error while every test is green. A library's tab row did exactly this (its
// ink-bar timer, under load, before ADR 0107); only CPU contention made the real window wide enough to hit, so the
// full suite never reproduced it on demand. `LateTimer` below is that pattern, so the file does not depend on which
// library has it today, and its `afterAll` repeats vitest's own teardown steps, then waits for the timer.
//
// With the setup line, the wrapper is unmounted in `afterEach`, its `$refs.box` is cleared, and the late timer
// returns at `if (!box) return` without touching the DOM. Without it, this file ends with exactly that unhandled
// `ReferenceError`.
//
// **Why this must be its own file.** Its `afterAll` destroys its worker's DOM. That is safe only because vitest
// isolates files; a second test in this file, or this test in a shared file, would run against a dead window.
import { afterAll, describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'

type TeardownGlobal = { happyDOM?: { abort(): Promise<void> }; window?: { close(): void } } & Record<string, unknown>

const LateTimer = defineComponent({
  mounted() {
    setTimeout(() => {
      const box = this.$refs.box
      if (!box) return
      void (box instanceof HTMLElement)
    }, 150)
  },
  render: () => h('div', { ref: 'box', class: 'late-timer' }),
})

describe('a component left mounted by its test', () => {
  // Vitest's happy-dom teardown, in its order: abort, close the window, delete the globals it populated. Then
  // give the 150 ms timer room to land on what is left.
  afterAll(async () => {
    const g = globalThis as unknown as TeardownGlobal
    await g.happyDOM?.abort()
    g.window?.close()
    for (const key of ['HTMLElement', 'Element', 'document', 'getComputedStyle']) delete g[key]
    await new Promise((resolve) => setTimeout(resolve, 400))
  })

  it('does not outlive the teardown that deletes the DOM globals (a timer that reads the DOM late)', () => {
    // Deliberately no `unmount()`: the shared setup must do it.
    const wrapper = mount(LateTimer)
    expect(wrapper.find('.late-timer').exists()).toBe(true)
  })
})
