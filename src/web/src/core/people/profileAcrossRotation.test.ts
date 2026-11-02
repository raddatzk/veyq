// @vitest-environment happy-dom
// A member's name and picture across a tenant rotation (ADR 0115 decisions 1-2, inbox #573): a scope has ONE key at a time,
// and a rotation re-seals every profile under the new one in its commit. So after a rotation a name or picture saved before
// it reads under the new key BECAUSE the rotation re-sealed it — and one left under the old key does not, nor does a reader
// still holding the old key read the re-sealed rows. Through the REAL readers - the people directory and its picture
// cache, the "finish your profile" prompt and the profile editor - with REAL crypto and the rotation's REAL re-seal
// (`resealMemberName` / `resealMemberAvatar`); only the transport is faked: the generated clients, the member listing and
// the scope open (which hands back the one key this device's grant opens to).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { effectScope } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { stubApi } from '../../test/apiMock'
import { accountKeyMaterial, memberProfile, openedScope, scopeMember } from '../../test/models'
import { realI18n } from '../../test/i18n'
import type { ScopeMember } from '../../api/generated/scopes'
import type { MemberProfile } from '../../api/generated/account'

vi.mock('../../api/client', async (orig) => ({ ...(await orig<typeof import('../../api/client')>()), accountApi: vi.fn(), accountProfileApi: vi.fn(), tenantsApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../features/spaces/members', async (orig) => ({ ...(await orig<typeof import('../../features/spaces/members')>()), listScopeMembers: vi.fn() }))
// Not a passthrough of the real module: `scopes` reaches the application registry (through the rotation's re-seal), whose
// graph imports the directory back — loaded inside the mock's own factory, the directory would bind the REAL `openScope`.
vi.mock('../../features/spaces/scopes', () => ({ openScope: vi.fn(), isStaleEpoch: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import { accountApi, accountProfileApi, tenantsApi } from '../../api/client'
import { listScopeMembers } from '../../features/spaces/members'
import { isStaleEpoch, openScope } from '../../features/spaces/scopes'
import { HttpStatus, isCodedError } from '../../shared/httpError'
import { epochsOf } from '../../features/spaces/epochsTestUtil'
import { resealMemberName, sealName } from '../../features/account/profile/memberName'
import { resealMemberAvatar, sealAvatar } from '../../features/account/profile/memberAvatar'
import { avatarUrlOf, loadTenantPeople, refreshPeopleDirectory, resetPeopleDirectory } from './directory'
import { useNameAndPicture } from '../../features/account/profile/useNameAndPicture'
import FinishProfilePrompt from '../../features/account/profile/FinishProfilePrompt.vue'
import { activeSpace } from '../../app/activeSpace'
import { account } from '../../app/account'

const TENANT = '5c09e000-0000-4000-8000-0000000005a3'
const ANNA = '5c09e000-0000-4000-8000-00000000a001' // saved her name and picture under E0, before the rotation
const BEN = '5c09e000-0000-4000-8000-00000000a002' // saved his name under E1, after it
const CARL = '5c09e000-0000-4000-8000-00000000a003' // set no name
const E0 = v1.randomBytes(32)
const E1 = v1.randomBytes(32) // the key the rotation minted at epoch 1; it REPLACED E0
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0])
const at = (userId: string) => ({ scopeId: TENANT, userId })
// What Anna saved under E0 …
const ANNA_NAME_E0 = sealName(E0, at(ANNA), { firstName: 'Anna', lastName: 'Meyer' })
const ANNA_PICTURE_E0 = sealAvatar(E0, at(ANNA), PNG)
// … and what the rotation's commit replaced it with: the same plaintext, sealed under E1.
const ANNA_NAME = resealMemberName(E0, E1, at(ANNA), ANNA_NAME_E0)
const ANNA_PICTURE = resealMemberAvatar(E0, E1, at(ANNA), ANNA_PICTURE_E0)
const BEN_NAME = sealName(E1, at(BEN), { firstName: 'Ben', lastName: 'Kurz' })

/** The tenant roster as the server serves it: Anna's row re-sealed by the rotation (`resealed`), or — what a rotation
 *  never commits — left under E0. */
const members = (resealed: boolean): ScopeMember[] => [
  scopeMember({ userId: ANNA, username: 'anna', sealedName: resealed ? ANNA_NAME : ANNA_NAME_E0, avatarRevision: 2 }),
  scopeMember({ userId: BEN, username: 'ben', sealedName: BEN_NAME }),
  scopeMember({ userId: CARL, username: 'carl' }),
]

/** The one key this device's grant opens to: E1 at epoch 1 by default; a device still on E0 holds that key at epoch 0.
 *  Fresh bytes per open: the readers zero what they opened. */
let heldKey = { key: E1, epoch: 1 }

function serve(profile: MemberProfile, resealed = true) {
  vi.mocked(listScopeMembers).mockResolvedValue(members(resealed))
  vi.mocked(openScope).mockImplementation(async () => {
    const epochs = epochsOf(heldKey.key.slice(), heldKey.epoch)
    return openedScope({ scopeId: TENANT, tenantId: TENANT, key: epochs.key, epochs })
  })
  stubApi(accountApi, { getAccountKeyMaterial: accountKeyMaterial({ userId: ANNA }) })
  stubApi(accountProfileApi, { getMyProfile: profile })
  stubApi(tenantsApi, { getMemberAvatar: { sealedAvatar: resealed ? ANNA_PICTURE : ANNA_PICTURE_E0 } })
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(isStaleEpoch).mockImplementation((e) => isCodedError(e, HttpStatus.conflict, 'stale_epoch')) // the real predicate
  heldKey = { key: E1, epoch: 1 }
  resetPeopleDirectory()
  localStorage.clear()
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:picture'), revokeObjectURL: vi.fn() }))
  serve(memberProfile({ username: 'anna', sealedName: ANNA_NAME }))
})

describe('after a tenant rotation every profile reads under the new key, because the rotation re-sealed it', () => {
  it('the people directory names a member by the name the rotation re-sealed, and one saved after it', async () => {
    const people = await loadTenantPeople()
    expect(people.get(ANNA)?.displayName).toBe('Anna Meyer')
    expect(people.get(BEN)?.displayName).toBe('Ben Kurz')
    expect(people.get(CARL)?.displayName).toBe('carl')
  })

  it('the picture cache shows a picture the rotation re-sealed', async () => {
    await refreshPeopleDirectory()
    expect(avatarUrlOf(ANNA)).toBe('blob:picture')
  })

  it('the profile editor fills in the member\'s own name the rotation re-sealed', async () => {
    const model = effectScope().run(() => useNameAndPicture())!
    await model.load()
    expect([model.firstName, model.lastName]).toEqual(['Anna', 'Meyer'])
  })

  it('the member is not asked to complete a profile they completed before the rotation', async () => {
    account.userId = ANNA
    activeSpace.status = 'ready'
    activeSpace.tenantId = TENANT
    const w = mount(FinishProfilePrompt, { global: { plugins: [realI18n()] } })
    await flushPromises()
    expect(vi.mocked(accountProfileApi)).toHaveBeenCalled() // it did decide
    expect(w.find('[data-testid="finish-profile"]').exists()).toBe(false)
  })
})

describe('under the old key it does not: one key per scope, nothing older is tried', () => {
  it('a name and a picture left under E0 do not open under E1 — the username and the letter show (control: Ben, under E1)', async () => {
    serve(memberProfile({ username: 'anna', sealedName: ANNA_NAME_E0 }), false)
    const people = await loadTenantPeople()
    expect(people.get(ANNA)?.displayName, 'no key, no name: the username').toBe('anna')
    expect(people.get(BEN)?.displayName).toBe('Ben Kurz')
    await refreshPeopleDirectory()
    expect(avatarUrlOf(ANNA), 'no key, no picture: the letter').toBeNull()
  })

  it('the editor shows empty fields and the prompt asks again for a name left under E0 (control: the re-sealed one above)', async () => {
    serve(memberProfile({ username: 'anna', sealedName: ANNA_NAME_E0 }), false)
    const model = effectScope().run(() => useNameAndPicture())!
    await model.load()
    expect([model.firstName, model.lastName]).toEqual(['', ''])
    account.userId = ANNA
    activeSpace.status = 'ready'
    activeSpace.tenantId = TENANT
    const w = mount(FinishProfilePrompt, { global: { plugins: [realI18n()] } })
    await flushPromises()
    expect(w.find('[data-testid="finish-profile"]').exists()).toBe(true)
  })

  it('a device still holding E0 reads none of the re-sealed rows — the rotation replaced its key', async () => {
    heldKey = { key: E0, epoch: 0 }
    serve(memberProfile({ username: 'anna', sealedName: ANNA_NAME }))
    const people = await loadTenantPeople()
    expect(people.get(ANNA)?.displayName).toBe('anna')
    expect(people.get(BEN)?.displayName).toBe('ben')
    await refreshPeopleDirectory()
    expect(avatarUrlOf(ANNA)).toBeNull()
  })
})
