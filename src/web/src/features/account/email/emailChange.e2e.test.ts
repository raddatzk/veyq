// Live e2e for uc.account.change-email (ADR 0074 Decision 8): request → the link read out of a real mailbox → confirm →
// sign in with the NEW address, unlock with the same Secret Key, and open the same content. The slice tests mint
// their own pending rows and sign with JDK keys, and the unit tests stub the transport — so this is the only place
// the client's real derivation under the new salt meets the server that installs it and the login that must accept it.
//
// It feeds the wire sweeps as the confirmation suite does: the change token is a `token` field (derived from the
// recording, searched raw everywhere, exempt only in the mailbox), and the Memory sealed before the request is a
// canary the mailbox sweep reads both mails for — the link to the new address and the notice to the old one carry
// link, domain and expiry only.
//
//   python3 scripts/run_web_e2e.py src/web/src/features/account/email/emailChange.e2e.test.ts
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
import { login } from '../login/login'
import { unlockMasterKey } from '../unlock/unlock'
import { account } from '../../../app/account'
import { getAccountEmail } from './emailConfirmation'
import { confirmEmailChange, requestEmailChange } from './emailChange'

const live = process.env.VEYQ_E2E === '1'

let instance: BirthedInstance

describe.runIf(live)('changing the sign-in address — live', () => {
  beforeAll(async () => {
    // `birthInstance` points the tenant at Mailpit: without a relay the request is a `503` and there is no link.
    instance = await birthInstance(`Readdress ${stamp}`)
  }, 120_000)

  it('the mailed link moves the address and the auth key; the same Secret Key opens the same content', async () => {
    const owner = instance.owner
    const oldEmail = owner.email
    const newEmail = `moved-${stamp}@e2e.test`
    const secretKey = owner.built.emergencyKit.secretKey

    // The app's own sign-in path, so the vault carries the account id the attestation binds.
    await login(oldEmail, owner.password)
    await unlockMasterKey(owner.password, secretKey)
    expect(account.userId, 'the unlock path names the account').toBeTruthy()

    // Sealed content that exists BEFORE either mail is sent, so a mail that carried it would be caught.
    await ensurePersonalScope()
    const scopeId = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)!.id
    const memoryName = `Umzug ${stamp}`
    await createMemory(scopeId, epochsOf((await openPersonalScope())!), memoryName)

    // 1. Ask. `202` says only "if the address can be used"; the mail is the observable effect.
    const toNew = await mailCursor(newEmail)
    const toOld = await mailCursor(oldEmail)
    await requestEmailChange(newEmail, owner.password, secretKey)
    const linkMail = await waitForMail(newEmail, { after: toNew })
    const link = linkMail.match(/https:\/\/[^\s]+\/confirm-email-change#([A-Za-z0-9_-]+)/)
    expect(link, `no change link in mail:\n${linkMail}`).toBeTruthy()
    expect(linkMail).toContain(HOST)
    expect(linkMail).not.toContain(oldEmail)

    // Nothing moved yet: the account still signs in with the old address until the link is opened.
    expect((await getAccountEmail()).email).toBe(oldEmail)

    // 2. Confirm with what the mail said, signed in as the account that asked.
    await confirmEmailChange(link![1])
    const after = await getAccountEmail()
    expect(after.email).toBe(newEmail)
    expect(after.confirmed, 'the link proved the new address').toBe(true)

    // 3. The old address got the fixed notice — never the new address, never a secret.
    const notice = await waitForMail(oldEmail, { after: toOld })
    expect(notice).toContain(HOST)
    expect(notice).not.toContain(newEmail)
    expect(notice).not.toContain('#')

    // 4. Spent: the same link now gets the one neutral refusal (status AND code, so a network failure cannot pass).
    const replay = await confirmEmailChange(link![1]).then(() => null, (e: unknown) => e)
    expect(await isCodedError(replay, 404, 'email_change_invalid')).toBe(true)

    // 5. The old address no longer signs in — the generic 401, which is also what an unknown account gets.
    const oldSignIn = await login(oldEmail, owner.password).then(() => null, (e: unknown) => e)
    expect(await isCodedError(oldSignIn, 401, 'authentication_failed')).toBe(true)

    // 6. The new address does, with the same password; the same Secret Key unlocks; the same content opens.
    await login(newEmail, owner.password)
    await unlockMasterKey(owner.password, secretKey)
    const names = (await listTree(scopeId, epochsOf((await openPersonalScope())!))).map((n) => n.name)
    expect(names).toContain(memoryName)
    recordCanaries(names)
  }, 180_000)
})
