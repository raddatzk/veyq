import { describe, it, expect, vi } from 'vitest'
import { httpError } from '../test/httpError'
import type { Router } from 'vue-router'
import { handle401 } from './handle401'

const fakeRouter = () => ({ push: vi.fn() }) as unknown as Router & { push: ReturnType<typeof vi.fn> }

describe('handle401', () => {
  it('redirects to the login route on a 401', () => {
    const router = fakeRouter()
    handle401(router)(httpError(401))
    expect(router.push).toHaveBeenCalledWith({ name: 'login' })
  })

  it('ignores non-401 HTTP failures', () => {
    const router = fakeRouter()
    handle401(router)(httpError(403))
    handle401(router)(httpError(500))
    expect(router.push).not.toHaveBeenCalled()
  })

  it('ignores non-HTTP failures (network / abort, status 0)', () => {
    const router = fakeRouter()
    handle401(router)(new Error('network'))
    expect(router.push).not.toHaveBeenCalled()
  })
})
