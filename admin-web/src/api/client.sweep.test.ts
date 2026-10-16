// Every authed call goes through the one retry-on-401 (inbox #449), so no panel can forget it. Two halves:
//   - each factory `api/client.ts` exports carries the middleware — every one but `setupApi`, which runs before
//     there is a session at all;
//   - nothing outside `api/client.ts` builds a generated client of its own, which would route around the factory.
//     The one exception is the rotation itself (`app/sessionRefresh.ts`), which must NOT carry the middleware: a
//     refresh that 401s and then waits on its own rotation would wait forever.
import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import * as client from './client'
import { refreshOnUnauthorized } from '../app/sessionRefresh'

const SRC = join(__dirname, '..')
const MAY_BUILD_A_CLIENT = new Set(['api/client.ts', 'app/sessionRefresh.ts'])

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === 'generated' ? [] : sources(path)
    return /\.(ts|vue)$/.test(name) && !/\.test\.ts$/.test(name) ? [path] : []
  })
}

describe('the authed clients', () => {
  const factories = Object.entries(client).filter(([name]) => name !== 'setupApi')

  it('are all built through the factories', () => {
    expect(factories.length).toBeGreaterThan(0)
  })

  it.each(factories)('%s carries the retry-on-401', (_name, factory) => {
    // `middleware` is the generated BaseAPI's own field, copied from the Configuration it was built with.
    expect(Reflect.get((factory as () => object)(), 'middleware')).toContain(refreshOnUnauthorized)
  })

  it('are never built anywhere else', () => {
    const offenders = sources(SRC)
      .map((path) => relative(SRC, path))
      .filter((path) => !MAY_BUILD_A_CLIENT.has(path))
      .filter((path) => /\bnew \w*Api\(/.test(readFileSync(join(SRC, path), 'utf8')))
    expect(offenders).toEqual([])
  })
})
