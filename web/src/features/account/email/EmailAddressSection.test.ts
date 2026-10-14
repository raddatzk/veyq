// @vitest-environment happy-dom
// uc.account.confirm-email-address (web) — the Security rubric's email-address section. Its four states (loading,
// load failed, unconfirmed, confirmed) and the request's answers rendered by status: sent, 503 no mail (with no
// copy-link anywhere), 404 not a member of this space, 429 budget spent.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { httpError } from '../../../test/httpError'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('./emailConfirmation', async (orig) => ({
  ...(await orig<typeof import('./emailConfirmation')>()),
  getAccountEmail: vi.fn(),
  requestEmailConfirmation: vi.fn(),
}))

import EmailAddressSection from './EmailAddressSection.vue'
import { getAccountEmail, requestEmailConfirmation } from './emailConfirmation'
import { realI18n, t } from '../../../test/i18n'
import { activeSpace } from '../../../app/activeSpace'

const stubs = {
  Button: { props: ['label', 'loading'], emits: ['click'], template: '<button :disabled="loading" @click="$emit(\'click\')">{{ label }}</button>' },
  Message: { props: ['severity'], template: '<div class="msg" :data-severity="severity"><slot /></div>' },
}

const ADDRESS = 'owner@example.com'

function mountSection() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'login', component: { template: '<div/>' } }] })
  return mount(EmailAddressSection, { global: { plugins: [router, realI18n()], stubs } })
}

const sendButton = (w: ReturnType<typeof mountSection>) => w.findAll('button').find((b) => b.text() === t('emailConfirm.send'))

/** Mount, press send, and let the request answer with [failure] (a rejection), or succeed without one. */
async function sendWith(failure?: Error) {
  vi.mocked(getAccountEmail).mockResolvedValue({ email: ADDRESS, confirmed: false })
  if (failure) vi.mocked(requestEmailConfirmation).mockRejectedValue(failure)
  else vi.mocked(requestEmailConfirmation).mockResolvedValue(undefined)
  const w = mountSection()
  await flushPromises()
  await sendButton(w)!.trigger('click')
  await flushPromises()
  return w
}

describe('EmailAddressSection (uc.account.confirm-email-address, web)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the loading state until the server answers', async () => {
    vi.mocked(getAccountEmail).mockReturnValue(new Promise(() => {}))
    const w = mountSection()
    expect(w.text()).toContain(t('emailConfirm.loading'))
    expect(sendButton(w)).toBeUndefined()
  })

  it('says so when the address cannot be read, and offers nothing to send', async () => {
    vi.mocked(getAccountEmail).mockRejectedValue(httpError(500))
    const w = mountSection()
    await flushPromises()
    expect(w.text()).toContain(t('emailConfirm.loadFailed'))
    expect(sendButton(w)).toBeUndefined()
  })

  it('shows an unconfirmed address as the server holds it, with the action', async () => {
    vi.mocked(getAccountEmail).mockResolvedValue({ email: ADDRESS, confirmed: false })
    const w = mountSection()
    await flushPromises()
    expect(w.find('[data-testid="email-address"]').text()).toBe(ADDRESS)
    expect(w.text()).toContain(t('emailConfirm.unconfirmed'))
    expect(w.text()).not.toContain(t('emailConfirm.confirmed'))
    expect(sendButton(w)).toBeDefined()
  })

  it('shows a confirmed address, and still lets its holder confirm again (an abandoned address is caught that way)', async () => {
    vi.mocked(getAccountEmail).mockResolvedValue({ email: ADDRESS, confirmed: true })
    const w = mountSection()
    await flushPromises()
    expect(w.text()).toContain(t('emailConfirm.confirmed'))
    expect(w.text()).not.toContain(t('emailConfirm.unconfirmed'))
    expect(sendButton(w)).toBeDefined()
  })

  it('says where the mail went once it is sent', async () => {
    const w = await sendWith()
    expect(requestEmailConfirmation).toHaveBeenCalledTimes(1)
    expect(w.text()).toContain(t('emailConfirm.sent', { email: ADDRESS }))
  })

  it('renders a space that cannot send as that — and offers no link to copy', async () => {
    const w = await sendWith(httpError(503, 'mail_unavailable'))
    expect(w.text()).toContain(t('emailConfirm.mailUnavailable'))
    expect(w.text()).not.toContain(t('emailConfirm.sent', { email: ADDRESS }))
    expect(w.text()).not.toContain('#')
  })

  it('renders a non-member of this space and a spent budget each by its own status', async () => {
    const notMember = await sendWith(httpError(404, 'tenant_not_found'))
    expect(notMember.text()).toContain(t('emailConfirm.notMember'))
    const limited = await sendWith(httpError(429, 'rate_limited'))
    expect(limited.text()).toContain(t('emailConfirm.rateLimited'))
    // Control on the same mapping: an answer the contract does not give this endpoint is the generic failure.
    const other = await sendWith(httpError(500))
    expect(other.text()).toContain(t('emailConfirm.requestFailed'))
  })
})

// uc.account.change-email E5: the link is mailed through a space's relay, so the change is offered only inside one.
describe('EmailAddressSection — changing the address (uc.account.change-email)', () => {
  it('offers "Change sign-in address" inside a space, and not outside one', async () => {
    vi.mocked(getAccountEmail).mockResolvedValue({ email: ADDRESS, confirmed: true })
    activeSpace.tenantId = null
    const outside = mountSection()
    await flushPromises()
    expect(outside.find('[data-testid="change-email-open"]').exists()).toBe(false)

    activeSpace.tenantId = 'tenant-1'
    try {
      const inside = mountSection()
      await flushPromises()
      expect(inside.find('[data-testid="change-email-open"]').exists()).toBe(true)
    } finally {
      activeSpace.tenantId = null
    }
  })
})
