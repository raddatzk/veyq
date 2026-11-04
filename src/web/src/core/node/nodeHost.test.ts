// The one host contract (`nodeHost.ts`): an absent door is an action not offered - the rights narrowed to the doors, the
// standard handlers bound only where a door is.
import { describe, it, expect, vi } from 'vitest'
import { hostHandlers, hostRights, NO_DOORS, shareOfferOf, sharesAny, type NodeHost } from './nodeHost'
import { standardNodeActions } from './nodeHeader'
import { treeNode } from '../../test/models'
import { GUEST_DOORS, recordingHost } from '../../test/nodeHost'

const t = (key: string) => key
const node = treeNode({ id: 'n1', kind: 'ALPHA', name: 'n1', parentId: null })
const ALL = { share: true, shareContribute: true, shareUpload: true, rename: true, move: true, delete: true }

describe('hostRights', () => {
  it("keeps every granted right a member's page has the door of", () => {
    expect(hostRights({ ...ALL, own: true }, recordingHost().host)).toEqual({ ...ALL, own: true })
  })

  it("a guest's page offers no rename, delete or share, whatever the answer; the move where it has the door", () => {
    const guest = recordingHost(GUEST_DOORS).host
    expect(hostRights(ALL, guest)).toEqual({ share: false, shareContribute: false, shareUpload: false, rename: false, move: true, delete: false })
    expect(hostRights(ALL, NO_DOORS), 'no host: nothing').toEqual({ share: false, shareContribute: false, shareUpload: false, rename: false, move: false, delete: false })
  })

  it('never widens: a right the answer withholds stays withheld, and a field it does not name is not added', () => {
    expect(hostRights({ rename: false }, recordingHost().host)).toEqual({ rename: false })
  })
})

describe('hostHandlers', () => {
  it("binds the standard actions to the node through the page's doors", () => {
    const host: NodeHost = { share: vi.fn(), rename: vi.fn(), move: vi.fn(), delete: vi.fn() }
    const on = hostHandlers(host, () => node, () => shareOfferOf(ALL))
    on.share!(); on.rename!(); on.move!(); on.remove!()
    expect(host.share).toHaveBeenCalledWith(node, { view: true, contribute: true, upload: true })
    expect(host.rename).toHaveBeenCalledWith(node)
    expect(host.move).toHaveBeenCalledWith(node)
    expect(host.delete).toHaveBeenCalledWith(node)
  })

  it("a guest's page: the header shows the move alone, whatever the answer says", () => {
    const guest = recordingHost(GUEST_DOORS).host
    const actions = standardNodeActions(t, hostRights(ALL, guest), hostHandlers(guest, () => node, () => shareOfferOf({})))
    expect(actions.map((a) => a.id)).toEqual(['move'])
    expect(Object.keys(hostHandlers(guest, () => node, () => shareOfferOf({})))).toEqual(['move'])
  })
})

describe('the share offer (owner 2026-11-04: one share action, one dialog of every role)', () => {
  it('offers each role exactly where its right holds, and none an answer leaves out (fail closed)', () => {
    expect(shareOfferOf(ALL)).toEqual({ view: true, contribute: true, upload: true })
    expect(shareOfferOf({ share: true })).toEqual({ view: true, contribute: false, upload: false })
    expect(shareOfferOf({ shareContribute: true })).toEqual({ view: false, contribute: true, upload: false })
    expect(shareOfferOf({ shareUpload: true })).toEqual({ view: false, contribute: false, upload: true })
    expect(shareOfferOf({ share: false, shareContribute: false, shareUpload: false })).toEqual({ view: false, contribute: false, upload: false })
    expect(shareOfferOf({}), 'an answer with no share right').toEqual({ view: false, contribute: false, upload: false })
  })

  it('a share action shows where any role may be minted, and nowhere else', () => {
    expect(sharesAny({ share: true })).toBe(true)
    expect(sharesAny({ shareContribute: true })).toBe(true)
    expect(sharesAny({ shareUpload: true })).toBe(true)
    expect(sharesAny({ share: false, shareContribute: false, shareUpload: false })).toBe(false)
    expect(sharesAny({})).toBe(false)
    expect(sharesAny(hostRights(ALL, recordingHost(GUEST_DOORS).host)), "a guest's page: no share door, no action").toBe(false)
  })
})
