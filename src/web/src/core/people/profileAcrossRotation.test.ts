// @vitest-environment happy-dom
// A member's name and picture across a tenant rotation (ADR 0023 § The epoch of a sealed name, inbox #573): the profile row
// records no epoch, so a name or picture saved under an earlier tenant epoch is opened under every epoch key the reader
// holds. Through the REAL readers - the people directory and its picture cache, the "finish your profile" prompt and the
// profile editor - with REAL crypto and a REAL epoch chain (E1 wraps E0, peeled by `deriveEpochKeys`); only the transport is
// faked: the generated clients, the member listing and the scope open (which hands back what a grant at epoch 1 opens to).
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { effectScope } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import { stubApi } from '../../test/apiMock'
import { accountKeyMaterial, memberProfile, openedScope, scopeMember } from '../../test/models'
import { realI18n } from '../../test/i18n'
import type { ScopeMember } from '../../api/generated/scopes'
import type { MemberProfile } from '../../api/generated/account'

vi.mock('../../api/client', async (orig) => ({ ...(await orig<typeof import('../../api/client')>()), accountApi: vi.fn(), accountProfileApi: vi.fn(), tenantsApi: vi.fn() }))
vi.mock('../../app/sessionRefresh', () => ({ withRefresh: (f: () => unknown) => f() }))
vi.mock('../../features/spaces/members', async (orig) => ({ ...(await orig<typeof import('../../features/spaces/members')>()), listScopeMembers: vi.fn() }))
vi.mock('../../features/spaces/scopes', async (orig) => ({ ...(await orig<typeof import('../../features/spaces/scopes')>()), openScope: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import { accountApi, accountProfileApi, tenantsApi } from '../../api/client'
import { listScopeMembers } from '../../features/spaces/members'
import { openScope } from '../../features/spaces/scopes'
import { deriveEpochKeys, epochLinkContext, type EpochKeys } from '../../features/spaces/epochs'
import { sealName } from '../../features/account/profile/memberName'
import { sealAvatar } from '../../features/account/profile/memberAvatar'
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
const E1 = v1.randomBytes(32)
// The chain link a rotation stores: E0 sealed under E1, bound to the tenant scope and epoch 1 (ADR 0024 decision 1).
const LINK = { epoch: 1, wrappedPrev: b64std(v1.seal(epochLinkContext(TENANT, 1), E1, E0)) }
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0])
const at = (userId: string) => ({ scopeId: TENANT, userId })
const ANNA_NAME = sealName(E0, at(ANNA), { firstName: 'Anna', lastName: 'Meyer' })
const ANNA_PICTURE = sealAvatar(E0, at(ANNA), PNG)

const MEMBERS: ScopeMember[] = [
  scopeMember({ userId: ANNA, username: 'anna', sealedName: ANNA_NAME, avatarRevision: 2 }),
  scopeMember({ userId: BEN, username: 'ben', sealedName: sealName(E1, at(BEN), { firstName: 'Ben', lastName: 'Kurz' }) }),
  scopeMember({ userId: CARL, username: 'carl' }),
]

/** What this device's grant opens to: by default the current epoch E1 and E0 peeled through the chain link; `links: []`
 *  is a device whose chain stops at its grant (it holds E1 alone). Fresh keys per open: the readers zero what they opened. */
let links = [LINK]
const held = (): EpochKeys => deriveEpochKeys(TENANT, E1.slice(), 1, links)

function serve(profile: MemberProfile) {
  vi.mocked(listScopeMembers).mockResolvedValue(MEMBERS)
  vi.mocked(openScope).mockImplementation(async () => {
    const epochs = held()
    return openedScope({ scopeId: TENANT, tenantId: TENANT, key: epochs.keys.get(1)!, epochs })
  })
  stubApi(accountApi, { getAccountKeyMaterial: accountKeyMaterial({ userId: ANNA }) })
  stubApi(accountProfileApi, { getMyProfile: profile })
  stubApi(tenantsApi, { getMemberAvatar: { sealedAvatar: ANNA_PICTURE } })
}

beforeEach(() => {
  vi.clearAllMocks()
  links = [LINK]
  resetPeopleDirectory()
  localStorage.clear()
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:picture'), revokeObjectURL: vi.fn() }))
  serve(memberProfile({ username: 'anna', sealedName: ANNA_NAME }))
})

describe('a tenant rotation leaves every name and picture readable (inbox #573)', () => {
  it('the people directory names a member by the name saved before the rotation, and one saved after it', async () => {
    const people = await loadTenantPeople()
    expect(people.get(ANNA)?.displayName).toBe('Anna Meyer')
    expect(people.get(BEN)?.displayName).toBe('Ben Kurz')
    expect(people.get(CARL)?.displayName).toBe('carl')
  })

  it('the picture cache shows a picture saved before the rotation', async () => {
    await refreshPeopleDirectory()
    expect(avatarUrlOf(ANNA)).toBe('blob:picture')
  })

  it('the profile editor fills in the member\'s own name saved before the rotation', async () => {
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

  it('opens only under keys the reader holds: a device whose grant gives it E1 alone does not read a name sealed under E0', async () => {
    links = []
    const people = await loadTenantPeople()
    expect(people.get(ANNA)?.displayName, 'no key, no name: the username').toBe('anna')
    expect(people.get(BEN)?.displayName).toBe('Ben Kurz')
  })
})
