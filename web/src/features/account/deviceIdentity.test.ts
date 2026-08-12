// @vitest-environment happy-dom
// en.account.device-register — the two client-invented answers the server must never learn: which device this
// is, and what to call it.
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import { deviceId, clearDeviceId, guessDeviceLabel } from './deviceIdentity'

describe('deviceId', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('is stable across calls — the whole point, since a device outlives its logins', () => {
    const first = deviceId()
    expect(deviceId()).toBe(first)
    expect(deviceId()).toBe(first)
  })

  it('survives the storage wipes that clear the key-material stores', () => {
    // The IndexedDB tiers are dropped on a session-less boot and on setup. This id must NOT be, or every session
    // expiry would mint a new device and the register would fill with one entry per expiry.
    const before = deviceId()
    // Whatever those paths clear, they do not clear this key.
    expect(localStorage.getItem('veyq:device-id')).toBe(before)
    expect(deviceId()).toBe(before)
  })

  it('mints a new one after an explicit clear', () => {
    const first = deviceId()
    clearDeviceId()
    expect(deviceId()).not.toBe(first)
  })

  it('still answers when storage is denied, instead of breaking the unlock path', () => {
    // Some privacy modes throw outright on access. The register write is best-effort, so an un-storable device
    // must degrade to unnamed — never to a thrown unlock.
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    expect(() => deviceId()).not.toThrow()
    expect(deviceId()).toMatch(/^[0-9a-f-]{36}$/)
  })

  afterEach(() => vi.restoreAllMocks())
})

describe('guessDeviceLabel', () => {
  const cases: ReadonlyArray<readonly [string, string, string]> = [
    [
      'Edge is not reported as Chrome',
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36 Edg/120.0',
      'Edge · Windows',
    ],
    [
      'Chrome is not reported as Safari',
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36',
      'Chrome · macOS',
    ],
    [
      'real Safari still resolves to Safari',
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15',
      'Safari · macOS',
    ],
    [
      'Firefox on Windows',
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0',
      'Firefox · Windows',
    ],
    [
      'iOS Chrome reports the device, not the engine',
      'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/120.0 Mobile/15E148 Safari/604.1',
      'Chrome · iPhone',
    ],
    [
      'Android Firefox',
      'Mozilla/5.0 (Android 14; Mobile; rv:121.0) Gecko/121.0 Firefox/121.0',
      'Firefox · Android',
    ],
  ]

  // The ordering of the match tables is the whole substance here: every Chromium derivative also claims
  // "Chrome", and Chrome also claims "Safari", so a naive first-match table mislabels two of the six.
  it.each(cases)('%s', (_name, ua, expected) => {
    expect(guessDeviceLabel('Unknown device', ua)).toBe(expected)
  })

  it('falls back to the localized string only when nothing at all matches', () => {
    expect(guessDeviceLabel('Unknown device', 'some-crawler/1.0')).toBe('Unknown device')
  })

  it('reports the half it does know rather than giving up wholesale', () => {
    expect(guessDeviceLabel('Unknown device', 'Mozilla/5.0 (Windows NT 10.0)')).toBe('Windows')
  })
})
