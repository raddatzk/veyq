// What a bookmark offers (ADR 0120): a member's, fail closed for everyone else.
import { describe, expect, it } from 'vitest'
import { FOLDER_KIND, SHARE_BOOKMARK_KIND } from '../node/kind'
import type { RightsTarget } from '../access'
import { guestAccessOf, memberAccessOf } from '../../test/models'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { bookmarkRights, canCreateBookmark, NO_BOOKMARK_RIGHTS } from './rights'

const at: RightsTarget = { kind: SHARE_BOOKMARK_KIND, inIdentityFolder: false, shareable: false, parentKind: FOLDER_KIND }
const level: RightsTarget = { kind: FOLDER_KIND, inIdentityFolder: false, shareable: true, parentKind: null }
const EPOCHS = epochsOf(new Uint8Array(32).fill(1))

describe('bookmarkRights', () => {
  it('lets a member whose keys are open open, rename, move and delete it, and share nothing', () => {
    expect(bookmarkRights(memberAccessOf({ epochs: EPOCHS }), at)).toEqual({ open: true, rename: true, move: true, delete: true })
  })

  it('writes nothing during a pending deletion but still opens, and opens nothing before the keys are', () => {
    expect(bookmarkRights(memberAccessOf({ epochs: EPOCHS, readonly: true }), at)).toEqual({ open: true, rename: false, move: false, delete: false })
    expect(bookmarkRights(memberAccessOf({ epochs: null }), at).open).toBe(false)
  })

  it('offers a guest of every role nothing, wherever a page put one', () => {
    for (const role of ['READ', 'READ_WRITE', 'WRITE_ONLY']) {
      expect(bookmarkRights(guestAccessOf(role), at), role).toEqual(NO_BOOKMARK_RIGHTS)
    }
    expect(bookmarkRights(memberAccessOf({ epochs: EPOCHS }), { ...at, kind: null })).toEqual(NO_BOOKMARK_RIGHTS)
  })
})

describe('canCreateBookmark', () => {
  it('follows the folder create: a writing member at a folder or the top level, never in a guest\'s identity folder', () => {
    expect(canCreateBookmark(memberAccessOf({ epochs: EPOCHS }), level)).toBe(true)
    expect(canCreateBookmark(memberAccessOf({ epochs: EPOCHS }), { kind: null, inIdentityFolder: false, shareable: false })).toBe(true)
    expect(canCreateBookmark(memberAccessOf({ epochs: EPOCHS, readonly: true }), level)).toBe(false)
    expect(canCreateBookmark(memberAccessOf({ epochs: EPOCHS }), { ...level, inIdentityFolder: true })).toBe(false)
    expect(canCreateBookmark(guestAccessOf('READ_WRITE'), level), 'a guest creates none (contracts: creatableInFolder false)').toBe(false)
  })
})
