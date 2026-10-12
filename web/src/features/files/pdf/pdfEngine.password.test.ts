// @vitest-environment node
// The engine's password handling against a FAKE loading task (inbox #412): the real library is `pdfEngine.real.test.ts`'s, and
// it cannot pin what the engine must do if pdf.js ever rejects a destroyed task with a different error than it does today -
// the decline must stay a "password" failure on the engine's own say-so, and an answer that comes after the open ended must
// never reach pdf.js. What is pinned: the argument of the prompt (need / wrong), that the answer goes to pdf.js' `update`
// and nowhere else, that a decline destroys the task and is the password failure whatever pdf.js then rejects with.
import { describe, it, expect, vi, beforeEach } from 'vitest'

type Task = {
  promise: Promise<unknown>
  destroy: ReturnType<typeof vi.fn>
  onPassword: ((update: (p: string) => void, reason: number) => void) | null
}
let task: Task
let rejectLoad: (e: unknown) => void = () => {}
let resolveLoad: (d: unknown) => void = () => {}

vi.mock('./pdfjsRuntime', () => ({ TextLayer: vi.fn(), getDocument: vi.fn() }))

import { openPdf } from './pdfEngine'
import { getDocument } from './pdfjsRuntime'

beforeEach(() => {
  task = {
    promise: new Promise((res, rej) => {
      resolveLoad = res
      rejectLoad = rej
    }),
    destroy: vi.fn(async () => {
      rejectLoad(new Error('Loading aborted')) // not a PasswordException: the engine must not depend on what pdf.js rejects with
    }),
    onPassword: null,
  }
  vi.mocked(getDocument).mockReset()
  vi.mocked(getDocument).mockReturnValue(task as unknown as ReturnType<typeof getDocument>)
})

const tick = () => new Promise((r) => setTimeout(r, 0))

describe('the engine and the password prompt (fake loading task)', () => {
  it('installs no handler without a prompt: pdf.js then rejects, which is the protected statement', async () => {
    const opening = openPdf(new Uint8Array(1))
    await tick()
    expect(task.onPassword).toBeNull()
    rejectLoad(Object.assign(new Error('x'), { name: 'PasswordException', code: 1 }))
    await expect(opening).rejects.toMatchObject({ reason: 'password' })
  })

  it('hands the answer to pdf.js\' update, and asks "wrong" only for code 2', async () => {
    const prompt = vi.fn(async (wrong: boolean) => (wrong ? 'second' : 'first'))
    const opening = openPdf(new Uint8Array(1), undefined, prompt)
    await tick()
    const update = vi.fn()
    task.onPassword!(update, 1)
    await tick()
    task.onPassword!(update, 2)
    await tick()
    expect(prompt.mock.calls.map((c) => c[0])).toEqual([false, true])
    expect(update.mock.calls).toEqual([['first'], ['second']])
    resolveLoad({ numPages: 1, getPage: async () => ({}) })
    expect((await opening).numPages).toBe(1)
  })

  it('a decline destroys the task and is the password failure, whatever pdf.js rejects with afterwards', async () => {
    const opening = openPdf(new Uint8Array(1), undefined, async () => null)
    await tick()
    const update = vi.fn()
    task.onPassword!(update, 1)
    await expect(opening).rejects.toMatchObject({ name: 'PdfOpenError', reason: 'password' })
    expect(task.destroy).toHaveBeenCalled()
    expect(update).not.toHaveBeenCalled()
  })

  it('a prompt that fails is a decline too', async () => {
    const opening = openPdf(new Uint8Array(1), undefined, async () => {
      throw new Error('closed')
    })
    await tick()
    task.onPassword!(vi.fn(), 1)
    await expect(opening).rejects.toMatchObject({ reason: 'password' })
  })

  it('an answer after the signal aborted never reaches pdf.js', async () => {
    const ctl = new AbortController()
    let answer: (v: string | null) => void = () => {}
    const opening = openPdf(new Uint8Array(1), ctl.signal, () => new Promise((r) => (answer = r)))
    await tick()
    const update = vi.fn()
    task.onPassword!(update, 1)
    ctl.abort()
    await expect(opening).rejects.toBeTruthy()
    answer('too late')
    await tick()
    expect(update).not.toHaveBeenCalled()
  })
})
