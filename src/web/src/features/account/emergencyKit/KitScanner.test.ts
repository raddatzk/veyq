// @vitest-environment happy-dom
// The scanner's first harness (cr-pairing-add-a-device-web). It exists because ADR 0059 makes this component
// load-bearing for a SECOND, security-critical flow, and the refactor that got it there — dropping `parseKit`
// out of the decode loop so the component is decoder-agnostic — is a refactor of code that had no safety net at
// all. The net is the thing being bought here; the pairing feature is only why it became worth buying.
//
// What it pins:
//   - the component hands back the RAW decoded string, never an interpretation of it (that is the whole
//     refactor, and a component that re-learns what a payload means would pass every other test here);
//   - it keeps scanning past a QR the caller does not accept, and gives up the camera when it does — the
//     existing Emergency-Kit behaviour, which is exactly what a decoder-agnostic loop is most likely to break —
//     saying the caller's `refusedText` over such a QR where one is passed, and nothing where none is;
//   - a camera it cannot have is a message, not a crash, and no code path leaves the stream running.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'

const decode = vi.fn<(...args: unknown[]) => { data: string } | null>()
vi.mock('jsqr', () => ({ default: (...args: unknown[]) => decode(...args) }))

import KitScanner from './KitScanner.vue'
import { realI18n, t } from '../../../test/i18n'

const stubs = {
  VeyqMessage: { props: ['tone'], template: '<div class="msg"><slot /></div>' },
  VeyqButton: {
    props: ['label', 'icon', 'variant', 'appearance'],
    emits: ['click'],
    template: '<button @click="$emit(\'click\')">{{ label }}</button>',
  },
}

let track: { stop: ReturnType<typeof vi.fn> }
let getUserMedia: ReturnType<typeof vi.fn>
let frames: FrameRequestCallback[]

/** Run the frames the component has queued. One call = one pass of the scan loop; the loop re-queues itself, so
 *  a second call is the "it kept scanning" observation. The scan body awaits a dynamic `import('jsqr')`, which
 *  settles on a macrotask — flushing microtasks alone leaves the pass half-run and every assertion below reads
 *  as "nothing happened". */
async function tick() {
  const due = frames
  frames = []
  due.forEach((f) => f(0))
  await flushPromises()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await flushPromises()
}

const mountScanner = (accepts?: (text: string) => boolean) =>
  mount(KitScanner, { props: accepts ? { accepts } : {}, global: { plugins: [realI18n()], stubs } })

beforeEach(() => {
  vi.clearAllMocks()
  frames = []
  track = { stop: vi.fn() }
  getUserMedia = vi.fn().mockResolvedValue({ getTracks: () => [track] } as unknown as MediaStream)

  Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia }, configurable: true })
  // happy-dom has no media pipeline and no 2D canvas: stand in for exactly the three things the loop reads.
  HTMLMediaElement.prototype.play = vi.fn().mockResolvedValue(undefined)
  // happy-dom's `srcObject` setter rejects anything that is not a real MediaStream, and the component assigns
  // it inside the try that reports "camera unavailable" — so without this the whole harness silently exercises
  // the permission-denied branch and every decode assertion reads as "nothing was scanned".
  Object.defineProperty(HTMLMediaElement.prototype, 'srcObject', {
    value: null,
    writable: true,
    configurable: true,
  })
  // Both halves of the loop's readiness guard, because happy-dom carries no media pipeline: the constant it
  // compares against as well as the value. Stubbing only `readyState` compares 4 against `undefined`.
  Object.defineProperty(HTMLMediaElement.prototype, 'HAVE_ENOUGH_DATA', { value: 4, configurable: true })
  Object.defineProperty(HTMLMediaElement.prototype, 'readyState', { value: 4, configurable: true })
  HTMLCanvasElement.prototype.getContext = vi.fn().mockReturnValue({
    drawImage: vi.fn(),
    getImageData: () => ({ data: new Uint8ClampedArray(4), width: 1, height: 1 }),
  }) as unknown as HTMLCanvasElement['getContext']
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => frames.push(cb))
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

const emittedFound = (w: VueWrapper) => w.emitted('found') as string[][] | undefined

describe('KitScanner (uc.account.unlock + uc.account.onboard-device, web)', () => {
  it('emits the raw decoded string, not an interpretation of it', async () => {
    decode.mockReturnValue({ data: 'veyqpair:SGFuZGxl' })
    const w = mountScanner()
    await flushPromises()

    await tick()

    expect(emittedFound(w)).toEqual([['veyqpair:SGFuZGxl']])
  })

  // The control on the same predicate as the "keeps scanning" case below: when the caller DOES accept, the
  // component stops — otherwise "it kept scanning" would be satisfied by a component that never emits at all.
  it('gives up the camera the moment the caller accepts a payload', async () => {
    decode.mockReturnValue({ data: 'veyqkit:eyJ2IjoxfQ' })
    const w = mountScanner((text) => text.startsWith('veyqkit:'))
    await flushPromises()

    await tick()

    expect(emittedFound(w)).toEqual([['veyqkit:eyJ2IjoxfQ']])
    expect(track.stop).toHaveBeenCalled()
  })

  it('keeps scanning past a QR the caller does not accept, instead of failing on it', async () => {
    decode.mockReturnValue({ data: 'https://example.org/some-other-qr' })
    const w = mountScanner((text) => text.startsWith('veyqkit:'))
    await flushPromises()

    await tick()

    expect(emittedFound(w)).toBeUndefined()
    expect(track.stop).not.toHaveBeenCalled()
    expect(frames).toHaveLength(1) // the loop re-queued itself
    expect(w.find('.msg').exists()).toBe(false) // and said nothing about it

    // …and it is still live: the next frame that does carry a kit is taken.
    decode.mockReturnValue({ data: 'veyqkit:eyJ2IjoxfQ' })
    await tick()
    expect(emittedFound(w)).toEqual([['veyqkit:eyJ2IjoxfQ']])
  })

  // The Secret-Key field's case (owner 2026-11-12): a readable code that is not the caller's is SAID, while the
  // loop goes on — silence there made a pairing code look like a scan that failed for no reason.
  it('says the refusal text over a QR it does not accept, and still takes the next accepted one', async () => {
    decode.mockReturnValue({ data: 'veyqpair:SGFuZGxl' })
    const w = mount(KitScanner, {
      props: { accepts: (text: string) => text.startsWith('veyqkit:'), refusedText: 'Not a kit.' },
      global: { plugins: [realI18n()], stubs },
    })
    await flushPromises()
    expect(w.find('.msg').exists()).toBe(false) // control: nothing said before a code was in view

    await tick()

    expect(w.find('.msg').text()).toBe('Not a kit.')
    expect(emittedFound(w)).toBeUndefined()
    expect(track.stop).not.toHaveBeenCalled()
    expect(frames).toHaveLength(1)

    decode.mockReturnValue({ data: 'veyqkit:eyJ2IjoxfQ' })
    await tick()
    expect(emittedFound(w)).toEqual([['veyqkit:eyJ2IjoxfQ']])
  })

  it('says nothing while no frame decodes, even with a refusal text', async () => {
    decode.mockReturnValue(null)
    const w = mount(KitScanner, {
      props: { accepts: () => false, refusedText: 'Not a kit.' },
      global: { plugins: [realI18n()], stubs },
    })
    await flushPromises()

    await tick()

    expect(w.find('.msg').exists()).toBe(false)
  })

  it('emits nothing while no frame decodes at all', async () => {
    decode.mockReturnValue(null)
    const w = mountScanner()
    await flushPromises()

    await tick()
    await tick()

    expect(emittedFound(w)).toBeUndefined()
    expect(track.stop).not.toHaveBeenCalled()
  })

  it('shows the fallback message when the camera is refused, and emits nothing', async () => {
    getUserMedia.mockRejectedValue(new DOMException('denied', 'NotAllowedError'))
    const w = mountScanner()
    await flushPromises()

    expect(w.find('.msg').text()).toBe(t('unlock.scanError'))
    expect(emittedFound(w)).toBeUndefined()
  })

  it('stops the camera on cancel and on unmount — no path leaves it running', async () => {
    const w = mountScanner()
    await flushPromises()

    await w.find('button').trigger('click')

    expect(w.emitted('close')).toHaveLength(1)
    expect(track.stop).toHaveBeenCalled()

    const second = mountScanner()
    await flushPromises()
    track.stop.mockClear()
    second.unmount()
    expect(track.stop).toHaveBeenCalled()
  })
})
