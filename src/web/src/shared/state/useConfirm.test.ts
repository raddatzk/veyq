// @vitest-environment happy-dom
import { describe, it, expect, beforeEach } from 'vitest'
import { useConfirm, confirmRequest, confirmOptions, settleConfirm } from './useConfirm'

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

  it('askWithOption answers null while another request is open, and leaves that one alone', async () => {
    const { ask, askWithOption } = useConfirm()
    const first = ask(request)
    await expect(askWithOption({ ...request, option: { label: 'Also' } })).resolves.toBeNull()
    expect(confirmRequest.value).toEqual(request)
    settleConfirm(true)
    await expect(first).resolves.toBe(true)
  })

  it('askWithOption carries the opt-in state set while it was open, and resets it for the next request', async () => {
    const { askWithOption } = useConfirm()
    const answer = askWithOption({ ...request, option: { label: 'Also' } })
    expect(confirmOptions.value).toEqual([false])
    confirmOptions.value[0] = true
    settleConfirm(true)
    await expect(answer).resolves.toEqual({ option: true })
    expect(confirmOptions.value).toEqual([])
  })

  it('askWithOptions answers each opt-in by its place, and a decline answers null whatever was ticked', async () => {
    const { askWithOptions } = useConfirm()
    const options = [{ label: 'First' }, { label: 'Second' }]
    const answer = askWithOptions({ ...request, options })
    expect(confirmOptions.value).toEqual([false, false])
    confirmOptions.value[1] = true
    settleConfirm(true)
    await expect(answer).resolves.toEqual({ options: [false, true] })

    const declined = askWithOptions({ ...request, options })
    confirmOptions.value[0] = true
    settleConfirm(false)
    await expect(declined).resolves.toBeNull()
  })

  it('a plain ask carries no opt-in, even one handed to it', async () => {
    const { ask } = useConfirm()
    const answer = ask({ ...request, options: [{ label: 'Ignored' }] })
    expect(confirmRequest.value?.options).toBeUndefined()
    expect(confirmOptions.value).toEqual([])
    settleConfirm(true)
    await expect(answer).resolves.toBe(true)
  })
})
