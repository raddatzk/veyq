// @vitest-environment happy-dom
// Proves the `enableAutoUnmount(afterEach)` line in `setup.ts`: a component a test leaves mounted must not
// outlive the file's teardown.
//
// **The mechanism it pins.** PrimeVue's `TabList.mounted()` arms a 150 ms `setTimeout` and keeps no handle, so its
// own `beforeUnmount` cannot clear it. That timer is **Node's**, not happy-dom's (vitest does not copy happy-dom's
// timers onto the global), so `happyDOM.abort()` in vitest's teardown never cancels it. When it lands after the
// teardown has deleted the DOM globals, `updateInkBar` → `getOuterWidth` throws
// `ReferenceError: HTMLElement is not defined`, and vitest fails the whole run on that unhandled error while every
// test is green. Only CPU contention makes the real window wide enough to hit, so the full suite never reproduced
// it on demand. This file opens the window deterministically instead: its `afterAll` repeats vitest's own
// teardown steps, then waits for the timer.
//
// With the setup line, the wrapper is unmounted in `afterEach`, its `$refs.inkbar` is cleared, and the late timer
// returns at `if (!inkbar) return` without touching the DOM. Without it, this file ends with exactly that
// unhandled `ReferenceError`.
//
// **Why this must be its own file.** Its `afterAll` destroys its worker's DOM. That is safe only because vitest
// isolates files; a second test in this file, or this test in a shared file, would run against a dead window.
import { afterAll, describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'

type TeardownGlobal = { happyDOM?: { abort(): Promise<void> }; window?: { close(): void } } & Record<string, unknown>

const TabsHost = defineComponent({
  render: () =>
    h(Tabs, { value: 'a' }, () => [
      h(TabList, null, () => [h(Tab, { value: 'a' }, () => 'A'), h(Tab, { value: 'b' }, () => 'B')]),
    ]),
})

describe('a component left mounted by its test', () => {
  // Vitest's happy-dom teardown, in its order: abort, close the window, delete the globals it populated. Then
  // give TabList's 150 ms timer room to land on what is left.
  afterAll(async () => {
    const g = globalThis as unknown as TeardownGlobal
    await g.happyDOM?.abort()
    g.window?.close()
    for (const key of ['HTMLElement', 'Element', 'document', 'getComputedStyle']) delete g[key]
    await new Promise((resolve) => setTimeout(resolve, 400))
  })

  it('does not outlive the teardown that deletes the DOM globals (PrimeVue TabList timer)', () => {
    // Deliberately no `unmount()`: the shared setup must do it.
    const wrapper = mount(TabsHost, { global: { plugins: [PrimeVue] } })
    expect(wrapper.findAll('[data-pc-name="tab"]')).toHaveLength(2)
  })
})
