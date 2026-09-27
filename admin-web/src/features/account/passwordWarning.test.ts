// The breached-password check's own rule (docs/crosscutting/security-model.md § the breached-password check):
// opt-in, on submit, over the FINAL password, advisory. `fetch` is the observable — what leaves the device is
// the whole point, so every case asserts on the request, not only on the returned verdict.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { nextTick, ref } from 'vue'
import { useBreachCheck } from './passwordWarning'

// The oracle is computed outside the code under test: SHA-1("password") = 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8.
const FINAL = 'password'
const PREFIX = '5BAA6'
const HIT_BODY = '0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n1E4C9B93F3F0682250B6CF8331B7EE68FD8:9545824\r\n'
const CLEAN_BODY = '0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n'

const fetchMock = vi.fn()
const ok = (body: string) => new Response(body, { status: 200 })

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('useBreachCheck', () => {
  it('sends nothing while the password is being typed — even ticked, even long after each pause', async () => {
    // The bite: the watcher this replaced queried at every 600 ms pause, sending the prefix of each PARTIAL
    // password. Seen red against it (5 requests for these 5 keystrokes).
    vi.useFakeTimers()
    const password = ref('')
    const { enabled } = useBreachCheck(password)
    enabled.value = true
    for (const pw of ['p', 'pa', 'pas', 'pass', FINAL]) {
      password.value = pw
      await nextTick()
      await vi.advanceTimersByTimeAsync(1_000)
    }
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('unticked: proceeds without any request', async () => {
    const password = ref(FINAL)
    const { proceed, stop } = useBreachCheck(password)
    expect(await proceed()).toBe(true)
    expect(fetchMock).not.toHaveBeenCalled()
    expect(stop.value).toBeNull()
  })

  it('ticked: one request, carrying only the prefix of the FINAL password, padded; clean → proceeds', async () => {
    fetchMock.mockResolvedValue(ok(CLEAN_BODY))
    const password = ref('pass')
    const { enabled, proceed, stop } = useBreachCheck(password)
    enabled.value = true
    password.value = FINAL
    expect(await proceed()).toBe(true)
    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(`https://api.pwnedpasswords.com/range/${PREFIX}`)
    expect(init).toEqual({ headers: { 'Add-Padding': 'true' } })
    expect(stop.value).toBeNull()
  })

  it('a hit stops the first submit with the count; the same password then proceeds with no second request', async () => {
    fetchMock.mockResolvedValue(ok(HIT_BODY))
    const password = ref(FINAL)
    const { enabled, proceed, stop } = useBreachCheck(password)
    enabled.value = true

    expect(await proceed()).toBe(false)
    expect(stop.value).toEqual({ count: 9545824 })

    expect(await proceed()).toBe(true)
    // And again, e.g. after the dialog's own work failed: still no query for a password already advised on.
    expect(await proceed()).toBe(true)
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('a check that could not run stops the first submit as such — never read as clean', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    const password = ref(FINAL)
    const { enabled, proceed, stop } = useBreachCheck(password)
    enabled.value = true

    expect(await proceed()).toBe(false)
    expect(stop.value).toEqual({ count: null })
    expect(await proceed()).toBe(true)
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('a non-2xx is "could not run" too, not zero', async () => {
    fetchMock.mockResolvedValue(new Response('', { status: 503 }))
    const password = ref(FINAL)
    const { enabled, proceed, stop } = useBreachCheck(password)
    enabled.value = true
    expect(await proceed()).toBe(false)
    expect(stop.value).toEqual({ count: null })
  })

  it('a changed password after a stop clears it and queries afresh', async () => {
    fetchMock.mockResolvedValue(ok(HIT_BODY))
    const password = ref(FINAL)
    const { enabled, proceed, stop } = useBreachCheck(password)
    enabled.value = true
    expect(await proceed()).toBe(false)

    fetchMock.mockResolvedValue(ok(CLEAN_BODY))
    password.value = 'a different password'
    expect(stop.value).toBeNull()
    expect(await proceed()).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls[1][0]).not.toContain(PREFIX)
  })

  it('unticking after a stop clears it; ticking again queries afresh', async () => {
    fetchMock.mockResolvedValue(ok(HIT_BODY))
    const password = ref(FINAL)
    const { enabled, proceed, stop } = useBreachCheck(password)
    enabled.value = true
    expect(await proceed()).toBe(false)

    enabled.value = false
    expect(stop.value).toBeNull()
    enabled.value = true
    expect(await proceed()).toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('a password edited while the query is in flight does not proceed on the old verdict', async () => {
    let answer!: (r: Response) => void
    fetchMock.mockReturnValue(new Promise<Response>((r) => (answer = r)))
    const password = ref(FINAL)
    const { enabled, proceed, stop } = useBreachCheck(password)
    enabled.value = true

    const verdict = proceed()
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledOnce())
    password.value = 'edited mid-flight'
    answer(ok(CLEAN_BODY))
    expect(await verdict).toBe(false)
    expect(stop.value).toBeNull()
  })
})
