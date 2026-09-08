// Live e2e for uc.account.recover-access (cr-account-recovery, ADR 0054) — the CR's acceptance smoke, as a test
// rather than a one-off run.
//
// This is the only place the flow is proven end to end, because it is the only place all three parties are real:
// a backend that withholds the RECOVERY wrap, an SMTP server that actually carries the link, and a client that
// opens the wrap with a Secret Key nobody typed. Every layer below this can pass while the whole is broken — the
// slice tests mint their own tokens, and the unit tests stub the transport.
//
// It also proves the one criterion no automated test could reach before: an instance that CAN send mail behaves
// differently from one that cannot, and the difference is observable without reading the server's mind.
//
// Runs under `VEYQ_E2E=1` via `scripts/run_web_e2e.py`, which gives this file its own database and brings the
// mailbox up with the rest of the stack. Where that mailbox is, and how it is read, lives in `test/mailpit.ts`.
import { beforeAll, describe, expect, it } from 'vitest'
import * as v1 from '@veyq/crypto/v1'
import { fromB64std } from '@veyq/crypto/base64'
import { session } from '../../../app/session'
import { login } from '../login/login'
import { accountApi } from '../../../api/client'
import { birthInstance, HOST, stamp, type BirthedInstance } from '../../../test/e2eBootstrap'
import { clearMailbox, waitForMail } from '../../../test/mailpit'
import { recordCanaries } from '../../../test/canaries'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../../spaces/scopes'
import { epochsOf } from '../../spaces/epochsTestUtil'
import { createMemory, listTree } from '../../memory/tree'
import { ScopeKind } from '../../../api/generated/scopes'
import { completeRecovery, openWithSecretKey, releaseRecoveryMaterial, requestRecovery } from './recovery'
import { PRODUCTION_ARGON_TIMEOUT_MS } from '@veyq/crypto/testArgon'

const live = process.env.VEYQ_E2E === '1'

let instance: BirthedInstance
const NEW_PASSWORD = `Reset-${stamp}-7wK!`

describe.runIf(live)('recovery — live', () => {
  beforeAll(async () => {
    // `birthInstance` already points the tenant at Mailpit — a tenant that cannot send mail is misconfigured,
    // not a variant (ADR 0055 §1), so the bootstrap owns that, not this suite.
    instance = await birthInstance(`Recovery ${stamp}`)
  }, 120_000)

  it(
    'the whole flow: a mailed link plus the Secret Key resets the password, and nothing is re-encrypted',
    async () => {
      const owner = instance.owner
      const secretKey = owner.built.emergencyKit.secretKey

      // What the account looked like before — the identity blob must come out of this untouched, because that
      // is what "nothing is re-encrypted" means in practice.
      const before = await accountApi().getAccountKeyMaterial()
      expect(before.wraps.map((w) => w.method)).not.toContain('RECOVERY')

      // Real content, created BEFORE the reset. Its name is the thing that must survive the reset AND stay
      // unreadable in the database — which is what "nothing is re-encrypted" has to mean to be worth claiming.
      await ensurePersonalScope()
      const scopeId = (await listMyScopes()).find((s) => s.kind === ScopeKind.Personal)!.id
      const scopeKey = (await openPersonalScope())!
      const memoryName = `Reisen ${stamp}`
      await createMemory(scopeId, epochsOf(scopeKey), memoryName)

      // 1. Ask for a link. The response says nothing; the mail is the observable effect. Clearing first is what
      //    makes the read unambiguous: a mail already sitting there — from an earlier hand run, which shares the
      //    default stamp and therefore the address — would be returned as though it were this request's.
      await clearMailbox(owner.email)
      await requestRecovery(owner.email)
      const body = await waitForMail(owner.email)

      // 2. The mail carries a link, an expiry and the domain — and nothing that names the person or the
      //    account (ADR 0049). The token rides the fragment.
      const link = body.match(/https:\/\/[^\s]+\/recover#([A-Za-z0-9_-]+)/)
      expect(link, `no recovery link in mail:\n${body}`).toBeTruthy()
      const token = link![1]
      expect(body).not.toContain(owner.username)
      expect(body).toContain(HOST)

      // 3. Release. This is the wrap the ordinary key-material read refuses to serve.
      session.apiBase = `http://${HOST}:8080`
      const material = await releaseRecoveryMaterial(token)
      expect(material.wrappedKey).toBeTruthy()
      // The release names the account, so the person never re-types an address they already proved they read.
      expect(material.email).toBe(owner.email)

      // 4. Open it with the Secret Key alone — no password anywhere in this step.
      const opened = await openWithSecretKey(material, secretKey)

      // 5. Set the new password.
      await completeRecovery(token, material, opened, secretKey, NEW_PASSWORD)

      // 6. The old password no longer logs in; the new one does.
      await expect(login(owner.email, owner.password)).rejects.toThrow()
      await login(owner.email, NEW_PASSWORD)

      // 7. The account opens under the new password, and the identity blob is byte-identical — the Master Key
      //    never changed, so no content was touched.
      const after = await accountApi().getAccountKeyMaterial()
      expect(after.wrappedIdentityPrivateKey).toBe(before.wrappedIdentityPrivateKey)
      expect(after.wraps.map((w) => w.method)).not.toContain('RECOVERY')

      const floor = after.wraps.find((w) => w.method === 'PASSWORD')!
      const { params, salt } = v1.decodeKdfParams(fromB64std(floor.kdfParams!))
      const masterKey = v1.symUnwrap(
        v1.kekPasswordSecret(fromB64std(secretKey), NEW_PASSWORD, salt, params),
        fromB64std(floor.wrappedKey),
        'master-key',
      )
      // The same Master Key the recovery wrap yielded — one key, two locks, nothing re-encrypted.
      expect(Array.from(masterKey)).toEqual(Array.from(opened.masterKey))

      // 8. The content is still there and still opens — under the SAME scope key, reached through the same
      //    identity blob. Its name is recorded as a canary, so the orchestrator proves it appears nowhere in
      //    this instance's database. A reset that quietly re-sealed content would fail step 7; a reset that
      //    leaked it would fail here.
      const names = (await listTree(scopeId, epochsOf((await openPersonalScope())!))).map((n) => n.name)
      expect(names).toContain(memoryName)
      recordCanaries(names)
    },
    PRODUCTION_ARGON_TIMEOUT_MS,
  )

  it(
    'a spent token cannot be replayed against the live server',
    async () => {
      const owner = instance.owner
      // No sign-in first: recovery is unauthenticated by necessity, so the replay case must be reachable
      // without a session — and after the first test this actor's in-memory wrap is stale anyway.
      // The first test already delivered a link to this address and SPENT it. Without clearing, a poll that wins
      // the race against the new mail returns that dead token and this test fails for the wrong reason.
      await clearMailbox(owner.email)
      await requestRecovery(owner.email)
      const token = (await waitForMail(owner.email)).match(/\/recover#([A-Za-z0-9_-]+)/)![1]

      const material = await releaseRecoveryMaterial(token)
      const opened = await openWithSecretKey(material, owner.built.emergencyKit.secretKey)
      await completeRecovery(token, material, opened, owner.built.emergencyKit.secretKey, NEW_PASSWORD)

      // Spent. The release is refused too — the token is gone, not merely unusable for a second reset.
      await expect(releaseRecoveryMaterial(token)).rejects.toThrow()
    },
    PRODUCTION_ARGON_TIMEOUT_MS,
  )
})
