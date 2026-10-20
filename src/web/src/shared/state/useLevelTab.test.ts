// @vitest-environment happy-dom
// The level view's tab preference (inbox #348, #408): defaults to All, persists, survives a reload, tolerates blocked
// storage — and belongs to WHO looks: one value per account, one for every share guest page, never one for the browser.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useLevelTab, setLevelTab, levelTabStorageKey } from './useLevelTab'
import { setPreferenceAudience } from './scopedPreference'
import { setAccountUserId, clearAccount } from '../../app/account'

beforeEach(() => {
  vi.unstubAllGlobals()
  localStorage.clear()
  setPreferenceAudience('account')
  setAccountUserId('user-1')
  setLevelTab('all') // reset the module singleton between tests
})

/** A fresh module graph (a reload) with `userId` signed in, and its preference. */
async function reloaded(userId: string | null, audience: 'account' | 'guest' = 'account') {
  vi.resetModules()
  const account = await import('../../app/account')
  if (userId) account.setAccountUserId(userId)
  const scoped = await import('./scopedPreference')
  scoped.setPreferenceAudience(audience)
  return (await import('./useLevelTab')).useLevelTab()
}

describe('useLevelTab', () => {
  it('defaults to all and persists the choice under the account key', () => {
    localStorage.clear()
    expect(useLevelTab().levelTab.value).toBe('all')
    setLevelTab('FILE')
    expect(useLevelTab().levelTab.value).toBe('FILE')
    expect(levelTabStorageKey()).toBe('veyq:level-tab:u:user-1')
    expect(localStorage.getItem('veyq:level-tab:u:user-1')).toBe('FILE')
  })

  it('is restored from storage by a fresh module (a reload)', async () => {
    localStorage.setItem('veyq:level-tab:u:user-1', 'MEMORY')
    expect((await reloaded('user-1')).levelTab.value).toBe('MEMORY')
  })

  it('falls back to all for an empty stored value', async () => {
    localStorage.setItem('veyq:level-tab:u:user-1', '')
    expect((await reloaded('user-1')).levelTab.value).toBe('all')
  })

  it('keeps the choice in memory when storage throws on write, and starts at all when it throws on read', async () => {
    const real = localStorage
    const throwing = () => {
      throw new Error('blocked')
    }
    vi.stubGlobal('localStorage', { getItem: real.getItem.bind(real), setItem: throwing })
    setLevelTab('FILE')
    expect(useLevelTab().levelTab.value).toBe('FILE')
    expect(real.getItem('veyq:level-tab:u:user-1')).toBe('all')
    vi.stubGlobal('localStorage', { getItem: throwing, setItem: throwing })
    expect((await reloaded('user-1')).levelTab.value).toBe('all')
    vi.unstubAllGlobals()
  })

  // inbox #408: the leak — one browser-global value let a guest tab flip a member's stored choice, and one account's
  // choice show for the next account on the same browser.
  it('keeps two accounts on one browser apart', () => {
    setLevelTab('FILE')
    setAccountUserId('user-2')
    expect(useLevelTab().levelTab.value).toBe('all')
    setLevelTab('MEMORY')
    expect(localStorage.getItem('veyq:level-tab:u:user-1')).toBe('FILE')
    expect(localStorage.getItem('veyq:level-tab:u:user-2')).toBe('MEMORY')
    setAccountUserId('user-1')
    expect(useLevelTab().levelTab.value).toBe('FILE')
  })

  it('gives a share guest its own value, global across shares, and leaves the account value alone', () => {
    setLevelTab('FILE')
    setPreferenceAudience('guest')
    expect(useLevelTab().levelTab.value).toBe('all')
    setLevelTab('MEMORY')
    expect(localStorage.getItem('veyq:level-tab:guest')).toBe('MEMORY')
    expect(localStorage.getItem('veyq:level-tab:u:user-1')).toBe('FILE')
    setPreferenceAudience('account')
    expect(useLevelTab().levelTab.value).toBe('FILE')
  })

  it('writes nothing while no account is known (memory only), and ignores the old un-keyed value', async () => {
    localStorage.clear()
    localStorage.setItem('veyq:level-tab', 'LEGACY')
    clearAccount()
    expect(levelTabStorageKey()).toBeNull()
    setLevelTab('FILE')
    expect(useLevelTab().levelTab.value).toBe('FILE')
    expect(Object.keys(localStorage).filter((k) => k !== 'veyq:level-tab')).toEqual([])
    expect((await reloaded('user-1')).levelTab.value).toBe('all')
  })
})
