// @vitest-environment happy-dom
// uc.moderation.appoint-moderators (admin-web, ADR 0099) — Server → Moderation. Each case is one observable:
//   - with no epoch, the panel says reporting is off, and turning moderation on asks first;
//   - a due rotation is said, and its button rotates;
//   - the roster shows each moderator's state and whether their address is confirmed;
//   - a removal asks first, and a refused rotation behind it is SHOWN (the CR's bite, below);
//   - an appointment of an address no account has is refused in words;
//   - a block whose statement did not plainly reach the member leaves a standing report with the words (ADR 0093 § 7),
//     and the statement's fields warn before the block while no instance mail is set up.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./moderation', async () => {
  const actual = await vi.importActual<typeof import('./moderation')>('./moderation')
  return {
    ...actual,
    getModeration: vi.fn(),
    appointModerator: vi.fn(),
    removeModerator: vi.fn(),
    rotateModerationKey: vi.fn(),
    getModerationSteps: vi.fn(),
    declineProposal: vi.fn(),
    blockSignIn: vi.fn(),
    liftSignInBlock: vi.fn(),
  }
})
vi.mock('./stepStatement', async () => ({
  ...(await vi.importActual<typeof import('./stepStatement')>('./stepStatement')),
  stepStatement: vi.fn(),
}))
vi.mock('./instanceMail', () => ({ statementMailNotSetUp: vi.fn() }))
const { confirmAsk } = vi.hoisted(() => ({ confirmAsk: vi.fn() }))
vi.mock('../../shared/state/useConfirm', () => ({ useConfirm: () => ({ ask: confirmAsk }) }))

import ModerationPanel from './ModerationPanel.vue'
import {
  appointModerator, blockSignIn, declineProposal, getModeration, getModerationSteps, liftSignInBlock, removeModerator,
  rotateModerationKey,
  type AccountSignInBlock, type InstanceModeration, type InstanceModerator, type ModerationSteps,
  type OpenModerationProposal,
} from './moderation'
import { stepStatement } from './stepStatement'
import { statementMailNotSetUp } from './instanceMail'
import { i18n } from '../../i18n'
import { httpError } from '../../test/httpError'

const stubs = {
  VeyqMessage: {
    // `closable` typed Boolean: the notice passes it bare, which an untyped prop would read as ''.
    props: { tone: String, closable: Boolean },
    emits: ['close'],
    template: '<div class="msg" :data-severity="tone"><slot /><button v-if="closable" class="msg-close" @click="$emit(\'close\')">x</button></div>',
  },
  VeyqInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  VeyqSelect: {
    props: ['modelValue', 'options'],
    emits: ['update:modelValue'],
    template:
      '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option></select>',
  },
  VeyqButton: {
    props: ['label', 'disabled', 'type'],
    emits: ['click'],
    template: '<button :type="type ?? \'button\'" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}</button>',
  },
}
const t = (k: string, p?: Record<string, unknown>) => i18n.global.t(k, p ?? {})

function moderator(over: Partial<InstanceModerator>): InstanceModerator {
  return {
    accountId: 'owner', address: 'owner@example.com', state: 'ACCEPTED', appointedAt: new Date('2026-10-01T00:00:00Z'),
    acceptedAt: new Date('2026-10-01T00:00:00Z'), identityPublicKey: 'AAAA', holdsCurrentEpoch: true, addressConfirmed: true,
    ...over,
  }
}

function on(over: Partial<InstanceModeration> = {}): InstanceModeration {
  return {
    enabled: true,
    currentEpoch: 2,
    currentKemPublicKey: undefined,
    rotationDueSince: undefined,
    serverOwner: { accountId: 'owner', identityPublicKey: 'AAAA' },
    moderators: [
      moderator({}),
      moderator({ accountId: 'deputy', address: 'deputy@example.com', addressConfirmed: false }),
      moderator({ accountId: 'new', address: 'new@example.com', state: 'PENDING', acceptedAt: undefined, identityPublicKey: undefined, holdsCurrentEpoch: false, addressConfirmed: false }),
    ],
    ...over,
  }
}

const off: InstanceModeration = { ...on(), enabled: false, currentEpoch: undefined, moderators: [] }

const noSteps: ModerationSteps = { proposals: [], signInBlocks: [] }

async function mountPanel(initial: InstanceModeration, initialSteps: ModerationSteps = noSteps) {
  vi.mocked(getModeration).mockResolvedValue(initial)
  vi.mocked(getModerationSteps).mockResolvedValue(initialSteps)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  const w = mount(ModerationPanel, { global: { plugins: [router, i18n], stubs } })
  await flushPromises()
  return w
}
const row = (w: Awaited<ReturnType<typeof mountPanel>>, address: string) => w.find(`[data-address="${address}"]`)

describe('ModerationPanel (uc.moderation.appoint-moderators, admin-web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
    vi.mocked(statementMailNotSetUp).mockResolvedValue(false)
  })

  it('says reporting is off while no key exists, and offers no way to make one here (setup makes epoch 1)', async () => {
    const w = await mountPanel(off)
    expect(w.find('.mod-off').text()).toBe(t('moderation.off'))
    expect(w.find('.mod-roster').exists()).toBe(false)
    expect(w.find('.mod-enable').exists()).toBe(false)
  })

  it('shows each moderator’s state and whether the address is confirmed', async () => {
    const w = await mountPanel(on({ moderators: [
      moderator({}),
      moderator({ accountId: 'deputy', address: 'deputy@example.com', holdsCurrentEpoch: false, addressConfirmed: false }),
      moderator({ accountId: 'new', address: 'new@example.com', state: 'PENDING', acceptedAt: undefined, identityPublicKey: undefined, holdsCurrentEpoch: false }),
    ] }))
    expect(row(w, 'owner@example.com').text()).toContain(t('moderation.stateHolds'))
    expect(row(w, 'owner@example.com').text()).toContain(t('moderation.addressConfirmed'))
    expect(row(w, 'deputy@example.com').text()).toContain(t('moderation.stateWaiting'))
    expect(row(w, 'deputy@example.com').text()).toContain(t('moderation.addressUnconfirmed'))
    expect(row(w, 'new@example.com').text()).toContain(t('moderation.statePending'))
    expect(w.find('.mod-epoch').text()).toBe(t('moderation.epoch', { epoch: 2 }))
  })

  it('warns of a due rotation, and its button rotates with the removal reason it starts on', async () => {
    vi.mocked(rotateModerationKey).mockResolvedValue(on({ currentEpoch: 3 }))
    const w = await mountPanel(on({ rotationDueSince: new Date('2026-10-17T09:00:00Z') }))
    expect(w.find('.mod-due').text()).toContain('Rotation due since')
    confirmAsk.mockResolvedValueOnce(true)
    await w.find('.mod-due__rotate').trigger('click')
    await flushPromises()
    expect(rotateModerationKey).toHaveBeenCalledWith('REMOVAL', expect.objectContaining({ currentEpoch: 2 }))
    expect(w.find('.mod-due').exists()).toBe(false)
  })

  it('says nothing is due when nothing is — control', async () => {
    const w = await mountPanel(on())
    expect(w.find('.mod-due').exists()).toBe(false)
  })

  it('rotates by hand with the chosen reason, after asking', async () => {
    vi.mocked(rotateModerationKey).mockResolvedValue(on({ currentEpoch: 3 }))
    const w = await mountPanel(on())
    await w.find('.mod-reason').setValue('COMPROMISE')
    confirmAsk.mockResolvedValueOnce(true)
    await w.find('.mod-rotate__submit').trigger('click')
    await flushPromises()
    expect(rotateModerationKey).toHaveBeenCalledWith('COMPROMISE', expect.objectContaining({ currentEpoch: 2 }))
  })

  // THE CR's bite (phase 5): a removal whose rotation the server refuses — the grants no longer name exactly the
  // accepted moderators — must reach the Server Owner as the panel's error. This case goes red if the panel swallows
  // the refusal (measured: a `catch` that drops the error leaves `.mod-error` absent and the case fails).
  it('shows a refused rotation after a removal as the panel’s error, and reads the roster again', async () => {
    vi.mocked(removeModerator).mockRejectedValue(httpError(409, 'moderation_grants_incomplete'))
    const w = await mountPanel(on())
    vi.mocked(getModeration).mockResolvedValue(on({ rotationDueSince: new Date('2026-10-17T10:00:00Z') }))
    confirmAsk.mockResolvedValueOnce(true)
    await row(w, 'deputy@example.com').find('.mod-remove').trigger('click')
    await flushPromises()
    expect(confirmAsk).toHaveBeenCalledWith(expect.objectContaining({ message: t('moderation.removeBody') }))
    expect(removeModerator).toHaveBeenCalledWith('deputy')
    expect(w.find('.mod-error').text()).toBe(t('moderation.errors.grantsIncomplete'))
    // the removal stood: the roster read again now says a rotation is due
    expect(w.find('.mod-due').exists()).toBe(true)
  })

  it('shows a refused rotation by hand as the panel’s error', async () => {
    vi.mocked(rotateModerationKey).mockRejectedValue(httpError(409, 'moderation_grants_incomplete'))
    const w = await mountPanel(on())
    confirmAsk.mockResolvedValueOnce(true)
    await w.find('.mod-rotate__submit').trigger('click')
    await flushPromises()
    expect(w.find('.mod-error').text()).toBe(t('moderation.errors.grantsIncomplete'))
  })

  it('removes nothing when the removal is not confirmed', async () => {
    const w = await mountPanel(on())
    confirmAsk.mockResolvedValueOnce(false)
    await row(w, 'deputy@example.com').find('.mod-remove').trigger('click')
    await flushPromises()
    expect(removeModerator).not.toHaveBeenCalled()
  })

  it('appoints by address, and names an address no account has', async () => {
    vi.mocked(appointModerator).mockRejectedValueOnce(httpError(404, 'account_not_found'))
    const w = await mountPanel(on())
    await w.find('.mod-address').setValue('nobody@example.com')
    await w.find('.mod-appoint').trigger('submit')
    await flushPromises()
    expect(appointModerator).toHaveBeenCalledWith('nobody@example.com')
    expect(w.find('.mod-error').text()).toBe(t('moderation.errors.accountNotFound'))
    expect((w.find('.mod-address').element as HTMLInputElement).value).toBe('nobody@example.com') // kept to correct

    vi.mocked(appointModerator).mockResolvedValueOnce(on())
    await w.find('.mod-address').setValue('deputy@example.com')
    await w.find('.mod-appoint').trigger('submit')
    await flushPromises()
    expect(w.find('.mod-error').exists()).toBe(false)
    expect((w.find('.mod-address').element as HTMLInputElement).value).toBe('')
  })
})

// uc.moderation.take-a-measure step 5 + uc.admin.suspend-tenant A4 — the Server Owner's steps. Each case is one
// observable: the lists render what the server says stands; each action reaches its operation with the right
// arguments (and the block only after a confirm that says what it does); each refusal is shown in its own words.
describe('ModerationPanel — the Server Owner’s steps (uc.moderation.take-a-measure step 5)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
    vi.mocked(statementMailNotSetUp).mockResolvedValue(false)
  })

  const PROPOSED = new Date('2026-10-17T12:00:00Z')

  function memberProposal(over: Partial<OpenModerationProposal> = {}): OpenModerationProposal {
    return {
      id: 'p-member', caseId: 'case-1', category: 'ILLEGAL_CONTENT', step: 'MEMBER_SIGN_IN', accountId: 'acc-1',
      address: 'member@example.com', tenantId: undefined, tenantDomain: undefined, proposedAt: PROPOSED, ...over,
    }
  }
  function tenantProposal(over: Partial<OpenModerationProposal> = {}): OpenModerationProposal {
    return {
      id: 'p-tenant', caseId: 'case-2', category: 'TERRORIST_CONTENT', step: 'TENANT_OFFLINE', accountId: undefined,
      address: undefined, tenantId: 'tn-9', tenantDomain: 'club.example.org', proposedAt: PROPOSED, ...over,
    }
  }
  function signInBlock(over: Partial<AccountSignInBlock> = {}): AccountSignInBlock {
    return { id: 'b-1', accountId: 'acc-2', address: 'blocked@example.com', caseId: 'case-3', blockedAt: PROPOSED, ...over }
  }
  const open: ModerationSteps = {
    proposals: [memberProposal(), tenantProposal()],
    signInBlocks: [signInBlock(), signInBlock({ id: 'b-2', accountId: 'acc-3', address: 'ordered@example.com', caseId: undefined })],
  }
  const proposal = (w: Awaited<ReturnType<typeof mountPanel>>, id: string) => w.find(`[data-proposal="${id}"]`)
  const blockRow = (w: Awaited<ReturnType<typeof mountPanel>>, address: string) => w.find(`[data-block="${address}"]`)
  const at = PROPOSED.toLocaleString('en')

  it('lists each open proposal — its step, the case’s category, its target, when — and every block in force', async () => {
    const w = await mountPanel(on(), open)
    const member = proposal(w, 'p-member')
    expect(member.text()).toContain(t('moderation.steps.stepMemberSignIn'))
    expect(member.find('.mod-proposal__category').text()).toBe(t('moderation.steps.categories.ILLEGAL_CONTENT'))
    expect(member.find('.mod-proposal__target').text()).toBe('member@example.com')
    expect(member.find('.mod-proposal__when').text()).toBe(t('moderation.steps.proposedAt', { at, caseId: 'case-1' }))
    const tenant = proposal(w, 'p-tenant')
    expect(tenant.text()).toContain(t('moderation.steps.stepTenantOffline'))
    expect(tenant.find('.mod-proposal__category').text()).toBe(t('moderation.steps.categories.TERRORIST_CONTENT'))
    expect(tenant.find('.mod-proposal__target').text()).toBe('club.example.org')

    expect(blockRow(w, 'blocked@example.com').find('.mod-block__since').text())
      .toBe(t('moderation.steps.blockedForCase', { at, caseId: 'case-3' }))
    expect(blockRow(w, 'ordered@example.com').find('.mod-block__since').text())
      .toBe(t('moderation.steps.blockedNoCase', { at }))
  })

  it('says when there is nothing to take and nothing blocked — control', async () => {
    const w = await mountPanel(on())
    expect(w.find('.mod-proposals-empty').text()).toBe(t('moderation.steps.noProposals'))
    expect(w.find('.mod-blocks-empty').text()).toBe(t('moderation.steps.noBlocks'))
  })

  it('keeps a block in force liftable while moderation is off', async () => {
    const w = await mountPanel(off, { proposals: [], signInBlocks: [signInBlock()] })
    expect(w.find('.mod-off').exists()).toBe(true)
    expect(blockRow(w, 'blocked@example.com').find('.mod-block__lift').exists()).toBe(true)
  })

  it('blocks a member’s sign-in with the address, the case and its statement of reasons, after a confirm that says what it does', async () => {
    vi.mocked(blockSignIn).mockResolvedValue({
      steps: { proposals: [tenantProposal()], signInBlocks: [signInBlock({ address: 'member@example.com' })] },
      statementDelivery: { outcome: 'REACHED', notReached: [] },
    })
    const statement = { statementId: 's-1', ground: 'TERMS_BREACH' as const, provision: 'Space terms, clause 4', facts: 'what was found', factsForLog: { epoch: 3, sealedFacts: 'c2VhbGVk' } }
    vi.mocked(stepStatement).mockResolvedValue(statement)
    const w = await mountPanel(on(), open)
    // A block from a proposal is over content (ADR 0093 § 7): it opens the statement first, its ground the case's category.
    await proposal(w, 'p-member').find('.mod-proposal__block').trigger('click')
    await flushPromises()
    expect(blockSignIn).not.toHaveBeenCalled()
    await proposal(w, 'p-member').find('.step-statement__provision').setValue('Space terms, clause 4')
    await proposal(w, 'p-member').find('.step-statement__facts').setValue('what was found')
    confirmAsk.mockResolvedValueOnce(true)
    await proposal(w, 'p-member').find('.mod-proposal__send').trigger('click')
    await flushPromises()
    expect(stepStatement).toHaveBeenCalledWith({ ground: 'ILLEGAL_CONTENT', provision: 'Space terms, clause 4', facts: 'what was found' })
    expect(confirmAsk).toHaveBeenCalledWith(expect.objectContaining({
      header: t('moderation.steps.blockTitle', { address: 'member@example.com' }),
      message: t('moderation.steps.blockBody'),
    }))
    expect(blockSignIn).toHaveBeenCalledWith('member@example.com', 'case-1', statement)
    // the answer is what is shown: the proposal is settled, the block is in force
    expect(proposal(w, 'p-member').exists()).toBe(false)
    expect(blockRow(w, 'member@example.com').exists()).toBe(true)
    // its statement reached the member: nothing stands beside the steps (the block had no success message before either)
    expect(w.find('.statement-delivery').exists()).toBe(false)
  })

  async function blockAnswering(statementDelivery: { outcome: 'NOT_ALL_REACHED' | 'UNKNOWN' | 'NOBODY_TO_TELL'; notReached: string[] }) {
    vi.mocked(blockSignIn).mockResolvedValue({
      steps: { proposals: [tenantProposal()], signInBlocks: [signInBlock({ address: 'member@example.com' })] },
      statementDelivery,
    })
    vi.mocked(stepStatement).mockResolvedValue({ statementId: 's-7', ground: 'ILLEGAL_CONTENT', provision: '§ 130 StGB', facts: 'what was found', factsForLog: { epoch: 3, sealedFacts: 'c2VhbGVk' } })
    const w = await mountPanel(on(), open)
    await proposal(w, 'p-member').find('.mod-proposal__block').trigger('click')
    await flushPromises()
    confirmAsk.mockResolvedValueOnce(true)
    await proposal(w, 'p-member').find('.mod-proposal__send').trigger('click')
    await flushPromises()
    return w
  }

  it('a block whose statement did not reach the member names the address and keeps the words until dismissed', async () => {
    const w = await blockAnswering({ outcome: 'NOT_ALL_REACHED', notReached: ['member@example.com'] })
    const report = w.find('.statement-delivery')
    expect(report.attributes('data-severity')).toBe('warn')
    expect(report.find('.statement-delivery__what').text()).toBe(t('moderation.steps.delivery.notReached', { addresses: 'member@example.com' }))
    expect(report.find('.statement-delivery__ground').text()).toBe(t('moderation.steps.categories.ILLEGAL_CONTENT'))
    expect(report.find('.statement-delivery__provision').text()).toBe('§ 130 StGB')
    expect(report.find('.statement-delivery__facts').text()).toBe('what was found')
    // the block itself stands: the steps the answer carried are shown
    expect(blockRow(w, 'member@example.com').exists()).toBe(true)
    await report.find('.msg-close').trigger('click')
    expect(w.find('.statement-delivery').exists()).toBe(false)
  })

  it.each([
    ['UNKNOWN' as const, 'moderation.steps.delivery.unknown', 'warn'],
    ['NOBODY_TO_TELL' as const, 'moderation.steps.delivery.nobodyToTell', 'info'],
  ])('a block answering %s says so beside the words', async (outcome, key, severity) => {
    const w = await blockAnswering({ outcome, notReached: [] })
    const report = w.find('.statement-delivery')
    expect(report.attributes('data-severity')).toBe(severity)
    expect(report.find('.statement-delivery__what').text()).toBe(t(key))
    expect(report.find('.statement-delivery__facts').text()).toBe('what was found')
  })

  it('warns in the block’s statement that it cannot be mailed while the instance mail is not set up', async () => {
    vi.mocked(statementMailNotSetUp).mockResolvedValue(true)
    const w = await mountPanel(on(), open)
    await proposal(w, 'p-member').find('.mod-proposal__block').trigger('click')
    await flushPromises()
    expect(proposal(w, 'p-member').find('.step-statement__no-mail').text()).toBe(t('moderation.steps.statement.noMail'))
  })

  it('says nothing of mail in the block’s statement when the instance mail is set up — control', async () => {
    const w = await mountPanel(on(), open)
    await proposal(w, 'p-member').find('.mod-proposal__block').trigger('click')
    await flushPromises()
    expect(statementMailNotSetUp).toHaveBeenCalled()
    expect(proposal(w, 'p-member').find('.step-statement__no-mail').exists()).toBe(false)
  })

  it('blocks nothing when the block is not confirmed', async () => {
    const w = await mountPanel(on(), open)
    await proposal(w, 'p-member').find('.mod-proposal__block').trigger('click')
    await flushPromises()
    confirmAsk.mockResolvedValueOnce(false)
    await proposal(w, 'p-member').find('.mod-proposal__send').trigger('click')
    await flushPromises()
    expect(blockSignIn).not.toHaveBeenCalled()
  })

  it.each([
    [400, 'statement_required', 'moderation.steps.errors.statementRequired'],
    [400, 'statement_facts_required', 'moderation.steps.errors.factsRequired'],
    [400, 'statement_provision_required', 'moderation.steps.errors.provisionRequired'],
    [409, 'moderation_epoch_changed', 'moderation.steps.errors.epochChanged'],
  ])('a block whose statement is refused with %i %s says so', async (status, code, key) => {
    vi.mocked(stepStatement).mockResolvedValue({ statementId: 's-1', ground: 'TERMS_BREACH', provision: 'Space terms, clause 4', facts: 'what was found', factsForLog: { epoch: 3, sealedFacts: 'c2VhbGVk' } })
    vi.mocked(blockSignIn).mockRejectedValueOnce(httpError(status, code))
    const w = await mountPanel(on(), open)
    await proposal(w, 'p-member').find('.mod-proposal__block').trigger('click')
    await flushPromises()
    confirmAsk.mockResolvedValueOnce(true)
    await proposal(w, 'p-member').find('.mod-proposal__send').trigger('click')
    await flushPromises()
    expect(w.find('.mod-error').exists() ? w.find('.mod-error').text() : w.text()).toContain(t(key))
  })

  it('offers no block for a proposal whose account is gone, and still lets it be declined', async () => {
    const w = await mountPanel(on(), { proposals: [memberProposal({ address: undefined })], signInBlocks: [] })
    expect(proposal(w, 'p-member').find('.mod-proposal__block').exists()).toBe(false)
    expect(proposal(w, 'p-member').find('.mod-proposal__target').text()).toBe(t('moderation.steps.accountGone'))
    expect(proposal(w, 'p-member').find('.mod-proposal__decline').exists()).toBe(true)
  })

  it('takes a tenant-offline proposal to the tenant’s own page, where the suspension lives — and blocks nothing', async () => {
    const w = await mountPanel(on(), open)
    expect(proposal(w, 'p-tenant').find('.mod-proposal__block').exists()).toBe(false)
    await proposal(w, 'p-tenant').find('.mod-proposal__tenant').trigger('click')
    expect(w.emitted('openTenant')).toEqual([['tn-9']])
    expect(blockSignIn).not.toHaveBeenCalled()
  })

  it('declines either kind of proposal by its id, after asking', async () => {
    for (const id of ['p-tenant', 'p-member']) {
      vi.mocked(declineProposal).mockResolvedValueOnce({ ...open, proposals: open.proposals.filter((p) => p.id !== id) })
      const w = await mountPanel(on(), open)
      confirmAsk.mockResolvedValueOnce(true)
      await proposal(w, id).find('.mod-proposal__decline').trigger('click')
      await flushPromises()
      expect(confirmAsk).toHaveBeenLastCalledWith(expect.objectContaining({ header: t('moderation.steps.declineTitle') }))
      expect(declineProposal).toHaveBeenLastCalledWith(id)
      expect(proposal(w, id).exists()).toBe(false)
    }
  })

  it('declines nothing when the decline is not confirmed', async () => {
    const w = await mountPanel(on(), open)
    confirmAsk.mockResolvedValueOnce(false)
    await proposal(w, 'p-member').find('.mod-proposal__decline').trigger('click')
    await flushPromises()
    expect(declineProposal).not.toHaveBeenCalled()
  })

  it('lifts a block by its id', async () => {
    vi.mocked(liftSignInBlock).mockResolvedValue({ ...open, signInBlocks: [open.signInBlocks[1]] })
    const w = await mountPanel(on(), open)
    await blockRow(w, 'blocked@example.com').find('.mod-block__lift').trigger('click')
    await flushPromises()
    expect(liftSignInBlock).toHaveBeenCalledWith('b-1')
    expect(blockRow(w, 'blocked@example.com').exists()).toBe(false)
  })

  // One row per code the step operations name (instance.openapi.yaml): each must reach the Server Owner in its own
  // words, never as the generic failure — and the steps are read again, since a refusal most often means someone
  // else took the step meanwhile.
  const refusals: [string, number, string, 'block' | 'decline' | 'lift'][] = [
    ['account_not_found', 404, 'moderation.steps.errors.accountNotFound', 'block'],
    ['case_not_found', 404, 'moderation.steps.errors.caseNotFound', 'block'],
    ['sign_in_block_exists', 409, 'moderation.steps.errors.blockExists', 'block'],
    ['cannot_block_server_owner', 409, 'moderation.steps.errors.cannotBlockOwner', 'block'],
    ['invalid_request', 400, 'moderation.steps.errors.invalidRequest', 'block'],
    ['not_server_owner', 403, 'moderation.steps.errors.notOwner', 'block'],
    ['proposal_not_found', 404, 'moderation.steps.errors.proposalNotFound', 'decline'],
    ['sign_in_block_not_found', 404, 'moderation.steps.errors.blockNotFound', 'lift'],
  ]
  it.each(refusals)('shows %s in its own words and reads the steps again', async (code, status, key, action) => {
    const refusal = httpError(status, code)
    vi.mocked(blockSignIn).mockRejectedValue(refusal)
    vi.mocked(declineProposal).mockRejectedValue(refusal)
    vi.mocked(liftSignInBlock).mockRejectedValue(refusal)
    const w = await mountPanel(on(), open)
    vi.mocked(getModerationSteps).mockResolvedValue(noSteps)
    confirmAsk.mockResolvedValueOnce(true)
    if (action === 'block') {
      await proposal(w, 'p-member').find('.mod-proposal__block').trigger('click')
      await flushPromises()
      await proposal(w, 'p-member').find('.mod-proposal__send').trigger('click')
    }
    if (action === 'decline') await proposal(w, 'p-member').find('.mod-proposal__decline').trigger('click')
    if (action === 'lift') await blockRow(w, 'blocked@example.com').find('.mod-block__lift').trigger('click')
    await flushPromises()
    expect(w.find('.mod-error').text()).toBe(t(key))
    expect(w.find('.mod-proposals-empty').exists()).toBe(true) // read again
  })
})
