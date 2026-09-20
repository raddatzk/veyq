// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'
import { useConfirm, confirmRequest, settleConfirm } from './useConfirm'

const request = { header: 'Sign out?', message: 'This device signs out.', acceptLabel: 'Sign out' }

describe('useConfirm', () => {
  beforeEach(() => {
    // The pending request is module-level (one dialog, mounted once in App.vue), so a test that leaves one
    // open would poison the next. Settling is the only way out — there is deliberately no reset door.
    if (confirmRequest.value) settleConfirm(false)
  })

  it('exposes the request while it is open and resolves true when it is accepted', async () => {
    const { ask } = useConfirm()
    const answer = ask(request)
    expect(confirmRequest.value).toEqual(request)
    settleConfirm(true)
    await expect(answer).resolves.toBe(true)
    expect(confirmRequest.value).toBeNull()
  })

  it('resolves false when it is declined — cancel, Escape and dismiss are one answer', async () => {
    const { ask } = useConfirm()
    const answer = ask(request)
    settleConfirm(false)
    await expect(answer).resolves.toBe(false)
    expect(confirmRequest.value).toBeNull()
  })

  // Decision 4 — single-flight. Every caller is a user-driven click, so a second ask while one is open is a
  // bug; `false` is the safe reading of it, and the open request must answer the question it was opened for.
  it('answers a second ask with false and leaves the open request alone', async () => {
    const { ask } = useConfirm()
    const first = ask(request)
    const second = ask({ header: 'Other', message: 'Other', acceptLabel: 'Other' })

    await expect(second).resolves.toBe(false)
    expect(confirmRequest.value).toEqual(request)

    settleConfirm(true)
    await expect(first).resolves.toBe(true)
  })

  it('is reusable: a settled request leaves nothing behind for the next one', async () => {
    const { ask } = useConfirm()
    settleConfirm(true) // nothing open: a stray settle must not arm the next request
    const first = ask(request)
    settleConfirm(true)
    await expect(first).resolves.toBe(true)

    const second = ask({ header: 'Again', message: 'Again', acceptLabel: 'Again' })
    expect(confirmRequest.value?.header).toBe('Again')
    settleConfirm(false)
    await expect(second).resolves.toBe(false)
  })
})
