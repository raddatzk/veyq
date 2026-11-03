// The member directory's client crypto at volume (cr-volume-measurement-local): every member's sealed name
// opened through its value key's wrap under `K_tenant`, as `directory.ts` does for the whole tenant roster, at the member axis' three stages.
// `pnpm exec vitest bench src/core/people/directory.bench.ts`; `vitest run` does not collect bench files, so `pnpm run check`
// is unchanged. TEMPORARY: `cr-volume-measurement-cax` deletes it with the rest of the harness.
import { bench, describe, expect } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { openMemberName, sealName } from '../../features/account/profile/memberName'

const SCOPE = '00000000-0000-4000-8000-000000000003'
const kTenant = { epoch: 0, key: v1.randomBytes(32) }

function roster(n: number) {
  return Array.from({ length: n }, (_, i) => {
    const userId = crypto.randomUUID()
    return { userId, ...sealName(kTenant, { scopeId: SCOPE, userId }, { firstName: `Member`, lastName: `Number ${i}` }) }
  })
}

const stages = [20, 200, 1000].map((n) => [n, roster(n)] as const)
const first = stages[0][1][0]
expect(openMemberName(kTenant, { scopeId: SCOPE, userId: first.userId }, first.sealedName, first.wrappedNameValueKey)?.lastName).toBe('Number 0')

describe('the directory opens every name', () => {
  for (const [n, rows] of stages) {
    bench(`${n} members`, () => {
      for (const m of rows) openMemberName(kTenant, { scopeId: SCOPE, userId: m.userId }, m.sealedName, m.wrappedNameValueKey)
    }, { iterations: 20, warmupIterations: 2, time: 0 })
  }
})
