// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../features/auth/login', () => ({ login: vi.fn().mockResolvedValue(undefined) }))

import { backendUrl, DEFAULT_BACKEND_URL, signIn } from './adminSession'
import { CliError } from './output'
import { login } from '../features/auth/login'
import { session } from '../app/session'

beforeEach(() => {
  vi.clearAllMocks()
  session.apiBase = ''
})

describe('veyq-admin session — the credential comes from the environment and nowhere else', () => {
  it('reads BACKEND_URL, defaulting to the same address the nginx entrypoint defaults to', () => {
    expect(backendUrl({})).toBe(DEFAULT_BACKEND_URL)
    expect(backendUrl({ BACKEND_URL: 'http://backend:9000' })).toBe('http://backend:9000')
  })

  it('strips a trailing slash, so the generated client does not build `//api/...`', () => {
    expect(backendUrl({ BACKEND_URL: 'http://backend:9000/' })).toBe('http://backend:9000')
  })

  it('signs in with the environment credential and points the API clients at the backend', async () => {
    await signIn({ VEYQ_ADMIN_EMAIL: 'o@example.com', VEYQ_ADMIN_PASSWORD: 'pw', BACKEND_URL: 'http://b:8080' })
    expect(login).toHaveBeenCalledWith('o@example.com', 'pw')
    expect(session.apiBase).toBe('http://b:8080')
  })

  it('an absent password fails loudly and by name, without attempting a login', async () => {
    await expect(signIn({ VEYQ_ADMIN_EMAIL: 'o@example.com' })).rejects.toBeInstanceOf(CliError)
    await expect(signIn({ VEYQ_ADMIN_EMAIL: 'o@example.com' })).rejects.toThrow('VEYQ_ADMIN_PASSWORD is not set')
    expect(login).not.toHaveBeenCalled()
  })

  it('an empty password is absent, not a password — the one an unset shell variable produces', async () => {
    await expect(signIn({ VEYQ_ADMIN_EMAIL: 'o@example.com', VEYQ_ADMIN_PASSWORD: '' }))
      .rejects.toThrow('VEYQ_ADMIN_PASSWORD is not set')
    expect(login).not.toHaveBeenCalled()
  })

  it('an absent email is refused the same way', async () => {
    await expect(signIn({ VEYQ_ADMIN_PASSWORD: 'pw' })).rejects.toThrow('VEYQ_ADMIN_EMAIL is not set')
    expect(login).not.toHaveBeenCalled()
  })
})
