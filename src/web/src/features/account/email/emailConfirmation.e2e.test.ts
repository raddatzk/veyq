// Live e2e for uc.account.confirm-email-address (ADR 0074): link → confirm → confirmed, with the link read out of a
// real mailbox. The slice tests mint their own token and the unit tests stub the transport, so this is the only
// place the three real parties meet — a backend that mints and hashes the token, an SMTP relay that carries it, and
// a client that confirms with what the mail said.
//
// It feeds the wire sweeps two ways. The confirmation token is a `token` field, so the orchestrator derives it from
// this suite's recording and searches every surface for it raw (the mailbox exempts it — mail is its channel), with
// its SHA-256 in the dump as the control. And the Memory this suite seals before asking for the link is a canary:
// the confirmation mail carries link, domain and expiry only (ADR 0074 Decision 6), so the mailbox sweep reading
// that very mail is where a leak of the account's content would show.
//
//   python3 scripts/run_web_e2e.py web/src/features/account/email/emailConfirmation.e2e.test.ts
import { beforeAll, describe, expect, it } from 'vitest'
import { birthInstance, HOST, stamp, type BirthedInstance } from '../../../test/e2eBootstrap'
import { mailCursor, waitForMail } from '../../../test/mailpit'
import { recordCanaries } from '../../../test/canaries'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../../spaces/scopes'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { listTree } from '../../../core/node/tree'
import { createMemory } from '../../memories/memoryNodes'
import { ScopeKind } from '../../../api/generated/scopes'
import { isCodedError } from '../../../shared/httpError'
import { confirmEmail, getAccountEmail, requestEmailConfirmation } from './emailConfirmation'

const live = process.env.VEYQ_E2E === '1'

let instance: BirthedInstance

describe.runIf(live)('email address confirmation — live', () => {
  beforeAll(async () => {
    // `birthInstance` points the tenant at Mailpit: without a relay the request is a `503` and there is no link.
    instance = await birthInstance(`Confirm ${stamp}`)
  }, 120_000)

  it('the mailed link confirms the address, and only once', async () => {
    const owner = instance.owner

    // Sealed content that exists BEFORE the mail is sent, so a confirmation mail that carried it would be caught.
    await ensurePersonalScope()
    const scopeId = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)!.id
    const scopeKey = (await openPersonalScope())!
    const memoryName = `Hochzeit ${stamp}`
    await createMemory(scopeId, epochsOf(scopeKey), memoryName)

    // A cold-born account starts unconfirmed, at the address it was born with.
    const before = await getAccountEmail()
    expect(before.email).toBe(owner.email)
    expect(before.confirmed).toBe(false)

    // 1. Ask for the link. `202` says only that it was sent; the mail is the observable effect, read behind a cursor
    //    so a mail from an earlier hand run (same default stamp, same address) cannot stand in for this one.
    const cursor = await mailCursor(owner.email)
    await requestEmailConfirmation()
    const body = await waitForMail(owner.email, { after: cursor })

    // 2. Link, domain, expiry — and nothing that names the person. The token rides the fragment.
    const link = body.match(/https:\/\/[^\s]+\/confirm-email#([A-Za-z0-9_-]+)/)
    expect(link, `no confirmation link in mail:\n${body}`).toBeTruthy()
    const token = link![1]
    expect(body).toContain(HOST)
    expect(body).not.toContain(owner.username)

    // 3. Confirm with what the mail said, signed in as the account it was minted for.
    await confirmEmail(token)

    // 4. Confirmed — the server's own reading, not this client's memory of having asked.
    const after = await getAccountEmail()
    expect(after.email).toBe(owner.email)
    expect(after.confirmed).toBe(true)

    // 5. Spent. The same token, which step 3 just proved valid for this account, now gets the one refusal the
    //    contract gives every dead token — anchored on status AND code, so a network or auth failure cannot pass.
    const replay = await confirmEmail(token).then(() => null, (e: unknown) => e)
    expect(replay, 'a spent confirmation token was accepted again').not.toBeNull()
    expect(await isCodedError(replay, 404, 'email_confirmation_invalid')).toBe(true)

    const names = (await listTree(scopeId, epochsOf((await openPersonalScope())!))).map((n) => n.name)
    expect(names).toContain(memoryName)
    recordCanaries(names)
  }, 120_000)
})
