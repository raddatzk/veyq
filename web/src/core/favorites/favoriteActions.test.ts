// @vitest-environment happy-dom
// The heart as a core action (core/favorites/favoriteActions.ts, inbox #409): what it offers a member for each kind, what it
// offers a guest (nothing, and the store is not even read), and what a click does (toggle in the access's scope).
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('./favoritesStore', () => ({ isFavorite: vi.fn(), toggleFavorite: vi.fn() }))

import { favoriteHeaderActions, favoriteRowActions } from './favoriteActions'
import { isFavorite, toggleFavorite } from './favoritesStore'
import { FOLDER_KIND } from '../node/kind'
import { MEMORY_KIND } from '../../features/memory/kinds'
import { FILE_KIND, DROPBOX_KIND } from '../../features/depot/kinds'
import { guestAccessOf, memberAccessOf } from '../../test/models'

const SCOPE = '5c09e000-0000-4000-8000-0000000000bb'
const t = (key: string) => `<${key}>`
const member = memberAccessOf({ scopeId: SCOPE })

beforeEach(() => {
  vi.mocked(isFavorite).mockReset().mockReturnValue(false)
  vi.mocked(toggleFavorite).mockReset()
})

describe('the heart', () => {
  it.each([FOLDER_KIND, MEMORY_KIND, FILE_KIND, DROPBOX_KIND])('a member gets one first-class, unpressed heart for a %s, in header and row alike', (kind) => {
    const node = { id: 'n1', kind }
    for (const actions of [favoriteHeaderActions(t, member, node), favoriteRowActions(t, member, node)]) {
      expect(actions).toHaveLength(1)
      expect(actions[0]).toMatchObject({ id: 'favorite', label: '<node.favorite>', icon: 'pi pi-heart', pressed: false, placement: 'before' })
    }
    expect(favoriteRowActions(t, member, node)[0].first, 'a visible button on a row, never in the menu').toBe(true)
  })

  it('a pinned node shows the filled heart, pressed, and the way to remove it', () => {
    vi.mocked(isFavorite).mockImplementation((id) => id === 'n1')
    expect(favoriteRowActions(t, member, { id: 'n1', kind: FOLDER_KIND })[0]).toMatchObject({
      label: '<node.unfavorite>', icon: 'pi pi-heart-fill', pressed: true,
    })
    expect(favoriteRowActions(t, member, { id: 'n2', kind: FOLDER_KIND })[0]).toMatchObject({ pressed: false })
  })

  it('a click toggles THIS node in the access\'s scope', () => {
    favoriteHeaderActions(t, member, { id: 'n1', kind: MEMORY_KIND })[0].onClick()
    expect(toggleFavorite).toHaveBeenCalledWith('n1', SCOPE)
  })

  it('a share guest gets no heart in any role, and the pin store is not even read', () => {
    for (const role of ['READ', 'READ_WRITE', 'WRITE_ONLY']) {
      const guest = guestAccessOf(role)
      expect(favoriteHeaderActions(t, guest, { id: 'n1', kind: FOLDER_KIND }), role).toEqual([])
      expect(favoriteRowActions(t, guest, { id: 'n1', kind: MEMORY_KIND }), role).toEqual([])
    }
    expect(isFavorite).not.toHaveBeenCalled()
  })

  it('no heart for the scope root or a kind this client cannot draw', () => {
    expect(favoriteHeaderActions(t, member, null)).toEqual([])
    expect(favoriteRowActions(t, member, { id: 'n1', kind: 'FUTURE_KIND' })).toEqual([])
    expect(isFavorite).not.toHaveBeenCalled()
  })
})
