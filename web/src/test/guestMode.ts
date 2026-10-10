// Guest-mode mounts (inbox #401, rule 3 of the share=member rebuild): a share guest's page is the member's views with
// functions removed, and nothing of it may reach an AUTHENTICATED client — a guest holds no session, and a view that
// called one would either fail for every guest or, worse, act as whoever is signed in on the same browser. A test mocks
// each client module through `guardedClients`, then asserts `authenticatedCalls` stayed empty:
//
//   vi.mock('../../api/client', async (orig) => (await import('../../test/guestMode')).guardedClients(await orig()))
//
// Every `…Api` factory becomes a `vi.fn`: the public ones (`public…Api`, the guest's own reads and writes) for the test
// to stub with `stubApi`/`fakeApi`; every other one THROWS when called and is recorded, so a call is red even where the
// view would have swallowed the error.
import { vi } from 'vitest'

/** The authenticated factories a guest-mode mount called, in order (reset it in a `beforeEach`). */
export const authenticatedCalls: string[] = []

/** `mod` with every `…Api` factory replaced as the header says; everything else as it is. */
export function guardedClients<T extends object>(mod: T): T {
  const out: Record<string, unknown> = {}
  for (const [name, value] of Object.entries(mod)) {
    if (typeof value === 'function' && /Api$/.test(name)) {
      out[name] = name.startsWith('public')
        ? vi.fn()
        : vi.fn(() => {
            authenticatedCalls.push(name)
            throw new Error(`guest mode called the authenticated client ${name}()`)
          })
    } else {
      out[name] = value
    }
  }
  return out as T
}
