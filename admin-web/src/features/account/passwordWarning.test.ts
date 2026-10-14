// The breached-password check's own rule (docs/crosscutting/security-model.md § the breached-password check):
// automatic, over the FINISHED password — on blur, and on submit when it changed since — never while typing,
// advisory. `fetch` is the observable — what leaves the device is the whole point, so every case asserts on the
// request, not only on the returned verdict.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { nextTick, ref } from 'vue'
import { useBreachCheck } from './passwordWarning'

// The oracle is computed outside the code under test:
// SHA-1("correct horse battery staple") = ABF7AAD6438836DBE526AA231ABDE2D0EEF74D42.
const FINAL = 'correct horse battery staple'
const PREFIX = 'ABF7A'
const HIT_BODY = '0018A45C4D1DEF81644B54AB7F969B88D65:1\r\nAD6438836DBE526AA231ABDE2D0EEF74D42:2401\r\n'
const CLEAN_BODY = '0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n'

const fetchMock = vi.fn()
const ok = (body: string) => new Response(body, { status: 200 })
// A fresh Response per call: one Response's body reads once, and a second read throws — which the code under test
// rightly reads as "could not run", and "could not run" goes ahead, so a shared Response greens a wrong verdict.
const answers = (body: string) => fetchMock.mockImplementation(async () => ok(body))

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('useBreachCheck', () => {
  it('sends nothing while the password is being typed — not even long after each pause', async () => {
    // The bite: the watcher this replaced queried at every 600 ms pause, sending the prefix of each PARTIAL
    // password. Seen red against it (5 requests for these 5 keystrokes).
    vi.useFakeTimers()
    const password = ref('')
    useBreachCheck(password)
    for (const pw of ['correct', 'correct horse', 'correct horse battery', 'correct horse battery st', FINAL]) {
      password.value = pw
      await nextTick()
      await vi.advanceTimersByTimeAsync(1_000)
    }
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('on blur: one request, carrying only the prefix of the finished password, padded; clean → proceeds', async () => {
    answers(CLEAN_BODY)
    const password = ref(FINAL)
    const { checkNow, proceed, state } = useBreachCheck(password)
    await checkNow()
    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(`https://api.pwnedpasswords.com/range/${PREFIX}`)
    expect(init).toEqual({ headers: { 'Add-Padding': 'true' } })
    expect(state.value).toEqual({ kind: 'clean' })

    // The submit of the unchanged password asks nothing again.
    expect(await proceed()).toBe(true)
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('a second blur on an unchanged password sends no second request', async () => {
    answers(CLEAN_BODY)
    const password = ref(FINAL)
    const { checkNow } = useBreachCheck(password)
    await checkNow()
    await checkNow()
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('a blur while the first query is still in flight shares it instead of sending a second', async () => {
    let answer!: (r: Response) => void
    fetchMock.mockReturnValue(new Promise<Response>((r) => (answer = r)))
    const password = ref(FINAL)
    const { checkNow, proceed, state } = useBreachCheck(password)
    void checkNow()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    expect(state.value).toEqual({ kind: 'checking' })
    const verdict = proceed()
    answer(ok(CLEAN_BODY))
    expect(await verdict).toBe(true)
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('a password changed since the blur is queried again on submit', async () => {
    answers(CLEAN_BODY)
    const password = ref('a different password')
    const { checkNow, proceed } = useBreachCheck(password)
    await checkNow()
    password.value = FINAL
    expect(await proceed()).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls[0][0]).not.toContain(PREFIX)
    expect(fetchMock.mock.calls[1][0]).toBe(`https://api.pwnedpasswords.com/range/${PREFIX}`)
  })

  it('a submit with no blur before it queries once', async () => {
    answers(CLEAN_BODY)
    const { proceed } = useBreachCheck(ref(FINAL))
    expect(await proceed()).toBe(true)
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('sends nothing for a password the policy refuses — empty or under the floor — on blur or on submit', async () => {
    answers(CLEAN_BODY)
    const password = ref('')
    const { checkNow, proceed, state } = useBreachCheck(password)
    await checkNow()
    password.value = '123456789' // one under the floor of 10
    await checkNow()
    await proceed()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(state.value).toEqual({ kind: 'idle' })

    // The control on the same predicate: exactly at the floor IS sent — `<` vs `<=` would make the floor 11.
    password.value = '0123456789'
    await checkNow()
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('a hit blocks the submit, with no new request, until "Use it anyway"', async () => {
    answers(HIT_BODY)
    const password = ref(FINAL)
    const { checkNow, proceed, state, useAnyway } = useBreachCheck(password)
    await checkNow()
    expect(state.value).toEqual({ kind: 'hit', count: 2401 })

    // Pressing submit again is no longer the acceptance.
    expect(await proceed()).toBe(false)
    expect(await proceed()).toBe(false)

    useAnyway.value = true
    expect(state.value).toEqual({ kind: 'accepted', count: 2401 })
    expect(await proceed()).toBe(true)
    // And again, e.g. after the dialog's own work failed: still no query for a password already advised on.
    expect(await proceed()).toBe(true)
    expect(fetchMock).toHaveBeenCalledOnce()

    // Unticking takes the acceptance back.
    useAnyway.value = false
    expect(await proceed()).toBe(false)
  })

  it('"Use it anyway" means nothing without a hit', async () => {
    answers(CLEAN_BODY)
    const password = ref(FINAL)
    const { checkNow, state, useAnyway } = useBreachCheck(password)
    useAnyway.value = true
    expect(state.value).toEqual({ kind: 'idle' })
    await checkNow()
    useAnyway.value = true
    expect(state.value).toEqual({ kind: 'clean' })
  })

  it('an edit clears the acceptance — the edited password is checked afresh', async () => {
    answers(HIT_BODY)
    const password = ref(FINAL)
    const { checkNow, proceed, state, useAnyway } = useBreachCheck(password)
    await checkNow()
    useAnyway.value = true

    password.value = `${FINAL}!`
    expect(state.value).toEqual({ kind: 'idle' })
    expect(useAnyway.value).toBe(false)
    // Even editing straight back: the acceptance was for that moment's decision, and it is gone.
    password.value = FINAL
    expect(await proceed()).toBe(false)
    expect(state.value).toEqual({ kind: 'hit', count: 2401 })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('a check that could not run goes ahead — and says so, never reads as clean', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    const password = ref(FINAL)
    const { checkNow, proceed, state } = useBreachCheck(password)
    await checkNow()
    expect(state.value).toEqual({ kind: 'unavailable' })
    expect(await proceed()).toBe(true)
    expect(state.value).toEqual({ kind: 'unavailable' })
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('a non-2xx is "could not run" too, not zero', async () => {
    fetchMock.mockResolvedValue(new Response('', { status: 503 }))
    const { proceed, state } = useBreachCheck(ref(FINAL))
    expect(await proceed()).toBe(true)
    expect(state.value).toEqual({ kind: 'unavailable' })
  })

  it('a password edited while the query is in flight does not proceed on the old verdict', async () => {
    let answer!: (r: Response) => void
    fetchMock.mockReturnValue(new Promise<Response>((r) => (answer = r)))
    const password = ref(FINAL)
    const { proceed, state } = useBreachCheck(password)

    const verdict = proceed()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    password.value = 'edited mid-flight'
    answer(ok(CLEAN_BODY))
    expect(await verdict).toBe(false)
    expect(state.value).toEqual({ kind: 'idle' })
  })
})
