// @vitest-environment happy-dom
// What a viewer may do about favorites on a node (core/favorites/rights.ts, inbox #409): the matrix, FAIL CLOSED.
// One function for every kind, member only; nothing for a guest in any role, for the scope root, or for a kind this
// client cannot draw. The registry is the real one (`src/test/setup.ts` registers the applications).
import { describe, it, expect } from 'vitest'
import { favoriteRights } from './rights'
import { FOLDER_KIND } from '../node/kind'
import { MEMORY_KIND, CHAPTER_KIND } from '../../features/memory/kinds'
import { FILE_KIND, DROPBOX_KIND } from '../../features/depot/kinds'
import type { Access } from '../access'
import { guestAccessOf, memberAccessOf } from '../../test/models'

const n = (kind: string) => ({ id: `n-${kind}`, kind })

describe('favoriteRights', () => {
  it.each([FOLDER_KIND, MEMORY_KIND, CHAPTER_KIND, FILE_KIND, DROPBOX_KIND])('a member may pin a %s', (kind) => {
    expect(favoriteRights(memberAccessOf(), n(kind))).toEqual({ favorite: true })
  })

  it('a member in a scope pending deletion keeps the heart: a pin is a private preference, as Memories always kept it', () => {
    expect(favoriteRights(memberAccessOf({ readonly: true }), n(FOLDER_KIND))).toEqual({ favorite: true })
  })

  it('the scope root is no node: nothing to pin', () => {
    expect(favoriteRights(memberAccessOf(), null)).toEqual({ favorite: false })
  })

  it('a kind no application registered is refused (it renders as the unsupported row)', () => {
    expect(favoriteRights(memberAccessOf(), n('FUTURE_KIND'))).toEqual({ favorite: false })
    expect(favoriteRights(memberAccessOf(), n(''))).toEqual({ favorite: false })
  })

  it.each(['READ', 'READ_WRITE', 'WRITE_ONLY', 'SOMETHING_NEW'])('a %s share guest never pins, whatever the kind', (role) => {
    for (const kind of [FOLDER_KIND, MEMORY_KIND, CHAPTER_KIND, FILE_KIND, DROPBOX_KIND, 'FUTURE_KIND']) {
      expect(favoriteRights(guestAccessOf(role), n(kind)), `${role} ${kind}`).toEqual({ favorite: false })
    }
    expect(favoriteRights(guestAccessOf(role), null)).toEqual({ favorite: false })
  })

  it('an access of an unknown mode grants nothing', () => {
    const odd = { mode: 'robot' } as unknown as Access
    expect(favoriteRights(odd, n(FOLDER_KIND))).toEqual({ favorite: false })
  })
})
