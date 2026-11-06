// An upload start refused because its application is switched off (uc.platform.switch-application-off): the server's
// `422 capability_disabled` reaches the queue as that code — member and guest alike — so the item fails at once and
// its surface says why; any other refusal keeps its own shape. Recorded at `fetch`, the real generated clients between.
import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest'
import { authedReserve } from './transport'
import { guestReserve } from '../sharing/guestBlob'
import { APPLICATION_OFF } from './queue'
import { session } from '../../app/session'

const SCOPE = '33333333-3333-4333-8333-333333333335'
const SHARE = 'Sh4reIdSh4reIdSh4reIdSh4reIdSh4reIdSh4re-_5'
const DECLARE = {
  blobId: '5c09e000-0000-4000-8000-0000000000c2', ownerId: '5c09e000-0000-4000-8000-0000000000c1', role: 'ORIGINAL',
  chunkCount: 1, byteSize: 40, wrappedBlobKey: 'd3JhcA==',
}

function refuseWith(status: number, code: string) {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ code }), { status, headers: { 'Content-Type': 'application/json' } })))
}

beforeAll(() => { session.accessToken = 'access-token' })
afterEach(() => vi.unstubAllGlobals())

describe('a start its application\'s switch refused', () => {
  it('reaches a member\'s queue as capability_disabled', async () => {
    refuseWith(422, 'capability_disabled')
    await expect(authedReserve(SCOPE, [DECLARE])).rejects.toThrow(APPLICATION_OFF)
  })

  it('reaches a guest\'s queue as capability_disabled', async () => {
    refuseWith(422, 'capability_disabled')
    await expect(guestReserve({ shareId: SHARE, apiBase: '', contributorToken: 'token' }, [DECLARE])).rejects.toThrow(APPLICATION_OFF)
  })

  it('control: another 422 keeps its own shape (not taken for a switch)', async () => {
    refuseWith(422, 'date_time_out_of_range')
    const e = await authedReserve(SCOPE, [DECLARE]).catch((x: unknown) => x)
    expect((e as Error).message).not.toBe(APPLICATION_OFF)
    expect((e as { response?: Response }).response?.status).toBe(422)
  })
})
