// ADR 0047, protocol 6: a playback's URL is handed out only once the service worker confirmed its registration. The
// registration message and the element's `/__media/` fetch reach the worker on separate queues, so a URL handed out at
// once could be fetched before the worker held it: a 404, about one in three in headless Chrome
// (cr-background-music-from-own-audio, where every track of a playlist registers one).
import { describe, it, expect, vi, afterEach } from 'vitest'
import { MEDIA_PATH_PREFIX, PROTOCOL_VERSION } from './protocol'

type Posted = { msg: { type: string; handle?: string }; ports: MessagePort[] }

/** A controlling worker that answers the handshake; `confirm` decides whether it confirms a registration. */
async function withWorker(confirm: boolean) {
  vi.resetModules()
  const posted: Posted[] = []
  const sw = {
    postMessage: (msg: Posted['msg'], ports: MessagePort[] = []) => {
      posted.push({ msg, ports })
      if (msg.type === 'veyq-handshake') ports[0].postMessage({ type: 'veyq-handshake-ack', version: PROTOCOL_VERSION })
      if (msg.type === 'veyq-register-playback' && confirm) setTimeout(() => ports[0].postMessage({ type: 'veyq-playback-registered' }), 20)
    },
  }
  vi.stubGlobal('navigator', { serviceWorker: { register: async () => ({}), ready: Promise.resolve(), controller: sw, addEventListener: () => {}, removeEventListener: () => {} } })
  const register = await import('./register')
  return { register, posted }
}
const provider = { chunkCount: 1, byteSize: 40, getPlaintextChunk: async () => new Uint8Array(4) }
const meta = { chunkCount: 1, byteSize: 40, mediaType: 'audio/mpeg' }

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('registerPlayback waits for the worker to hold the registration', () => {
  it('hands the URL out only after the worker confirmed it', async () => {
    const { register, posted } = await withWorker(true)
    let url: string | null | undefined
    const pending = register.registerPlayback(meta, provider).then((u) => { url = u })
    await new Promise((r) => setTimeout(r, 5))
    expect(posted.map((p) => p.msg.type)).toEqual(['veyq-handshake', 'veyq-register-playback'])
    expect(url, 'not before the confirmation').toBeUndefined()
    await pending
    expect(url).toBe(MEDIA_PATH_PREFIX + posted[1].msg.handle)
  })

  it('a worker that never confirms is no usable worker: null (the caller falls back), and the registration is revoked', async () => {
    const { register, posted } = await withWorker(false)
    await expect(register.registerPlayback(meta, provider, 50)).resolves.toBeNull()
    expect(posted.map((p) => p.msg.type)).toEqual(['veyq-handshake', 'veyq-register-playback', 'veyq-revoke-playback'])
    expect(posted[2].msg.handle).toBe(posted[1].msg.handle)
  })
})
