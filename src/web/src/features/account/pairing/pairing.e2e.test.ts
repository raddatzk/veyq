// Live e2e for the two token flows no other suite drives: the web refresh (ADR 0004) and device pairing
// (uc.account.onboard-device, ADR 0059 — the *add a device* direction). Both exist mainly so the wire sweeps see
// what they mint: a refresh token rotated by `token/refresh`, the pairing `handle`, the delegated grant token and
// the cookie its adoption converts it into (cc.security.wire-assertion § Capability tokens). Every one of them is
// a `token` the orchestrator derives from this suite's recording and searches every surface for, raw, with their
// SHA-256 in the dump as the control.
//
// **The cookie is carried by hand.** node's `fetch` keeps no cookie jar, and the web refresh is cookie-only: the
// login and every rotation answer with a `Set-Cookie` scoped to `/api/token/refresh` and no body token. So this
// suite wraps `fetch` in a small jar — per *device*, because the trusted device and the one being onboarded are two
// browsers — that keeps each `Set-Cookie` and sends it back on a request under its `Path`, the way a browser
// would. It wraps the recorder rather than replacing it, so the `Cookie` it adds is in the recording too. With
// the jar in place the product's own `refreshSession()` runs unchanged.
//
// The onboarded device stops short of `runOnboardTail`: its unlock tail needs IndexedDB, which this node process
// does not have. What it does run is that tail's first step, adoption, exactly as `onboardTail.adopt` makes it.
//
//   python3 scripts/run_web_e2e.py web/src/features/account/pairing/pairing.e2e.test.ts
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { birthInstance, stamp, type BirthedInstance } from '../../../test/e2eBootstrap'
import { recordCanaries } from '../../../test/canaries'
import { tokenApi } from '../../../api/client'
import { TokenAdoptRequestSessionScopeEnum } from '../../../api/generated/auth'
import { clearTokens, session, setTokens } from '../../../app/session'
import { refreshSession } from '../../../app/sessionRefresh'
import { masterKey } from '../../../app/vault'
import { isCodedError } from '../../../shared/httpError'
import { getAccountEmail } from '../email/emailConfirmation'
import { readMarks } from '../trustMarks'
import {
  channelKeys,
  checkNumber,
  mintPairingSecret,
  mintTransportKey,
  openGrant,
  pairingPayload,
  parsePairingPayload,
  readTransportAnswer,
  sealGrant,
  transportAnswer,
} from './pairing'
import {
  answerPairingChannel,
  collectPairingChannel,
  delegatePairingSession,
  openPairingChannel,
  sealPairingChannel,
} from './pairingChannel'

const live = process.env.VEYQ_E2E === '1'

/** The cookie the web refresh rides on for a `SESSION`-scoped sign-in (ADR 0004 § web refresh cookie lifetimes). */
const REFRESH_COOKIE = 'veyq_refresh_session'

type Cookie = { value: string; path: string }
const jars = new Map<string, Map<string, Cookie>>()
let device = 'trusted'
const jar = (name = device): Map<string, Cookie> => {
  if (!jars.has(name)) jars.set(name, new Map())
  return jars.get(name)!
}
const cookie = (name: string, on = device): string | undefined => jar(on).get(name)?.value

/** Keep what a `Set-Cookie` says; an empty value (the clearing cookie for the other lifetime) removes the name. */
function keep(setCookie: string): void {
  const [pair, ...attributes] = setCookie.split(';')
  const eq = pair.indexOf('=')
  const name = pair.slice(0, eq).trim()
  const value = pair.slice(eq + 1).trim()
  const path = attributes.map((a) => a.trim()).find((a) => /^path=/i.test(a))?.slice(5) ?? '/'
  if (value === '') jar().delete(name)
  else jar().set(name, { value, path })
}

let unwrapped: typeof fetch | null = null

function installCookieJar(): void {
  const inner = globalThis.fetch
  unwrapped = inner
  globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url)
    const sent = [...jar()].filter(([, c]) => url.pathname.startsWith(c.path))
    let next = init
    if (sent.length > 0) {
      const headers = new Headers(init?.headers)
      headers.set('cookie', sent.map(([name, c]) => `${name}=${c.value}`).join('; '))
      next = { ...init, headers: Object.fromEntries(headers) }
    }
    const response = await inner(input, next)
    for (const setCookie of response.headers.getSetCookie()) keep(setCookie)
    return response
  }
}

let instance: BirthedInstance

describe.runIf(live)('session refresh and device pairing — live', () => {
  beforeAll(async () => {
    // Before the birth, so the owner's sign-in cookie lands in the trusted device's jar.
    installCookieJar()
    instance = await birthInstance(`Pairing ${stamp}`)
  }, 120_000)

  afterAll(() => {
    if (unwrapped) globalThis.fetch = unwrapped
  })

  it('a web refresh rotates the cookie, and the rotated cookie is the one the next refresh presents', async () => {
    device = 'trusted'
    const signedIn = cookie(REFRESH_COOKIE)
    expect(signedIn, 'the sign-in set no refresh cookie').toBeTruthy()
    const access0 = session.accessToken

    // The product's own refresh: an empty body, so the jar's cookie is the credential.
    await refreshSession()
    const rotated = cookie(REFRESH_COOKIE)
    expect(rotated).toBeTruthy()
    expect(rotated).not.toBe(signedIn)
    expect(session.accessToken).not.toBe(access0)

    // The successor is itself a refresh token: presenting it rotates again.
    await refreshSession()
    expect(cookie(REFRESH_COOKIE)).not.toBe(rotated)
    expect((await getAccountEmail()).email).toBe(instance.owner.email)
  })

  it('a device paired by code adopts its grant into a cookie and refreshes it; the grant adopts only once', async () => {
    const owner = instance.owner
    const mk = masterKey()!
    const label = `Reiselaptop ${stamp}`

    // Trusted device: mint the code it displays and open the channel under its handle.
    device = 'trusted'
    const secret = mintPairingSecret()
    const trusted = channelKeys(secret)
    await openPairingChannel(trusted.handle)

    // New device: scan the code, answer with a transport key MACed under the channel key. No session, no cookie.
    device = 'new'
    const scanned = channelKeys(parsePairingPayload(pairingPayload(secret))!)
    const transport = mintTransportKey()
    await answerPairingChannel(scanned.handle, transportAnswer(scanned.key, transport.pub))

    // Trusted device: verify the answer, compare the number both screens show, delegate, seal.
    device = 'trusted'
    const answered = await collectPairingChannel(trusted.handle)
    const pub = readTransportAnswer(trusted.key, answered.answer!)
    expect(checkNumber(pub)).toBe(checkNumber(transport.pub))
    const grantToken = await delegatePairingSession(trusted.handle)
    const envelope = sealGrant(trusted.key, pub, {
      email: owner.email,
      masterKey: mk,
      refreshToken: grantToken,
      label,
      marks: await readMarks(mk),
    })
    await sealPairingChannel(trusted.handle, envelope)
    const ownerTokens = { access: session.accessToken!, family: session.familyId! }

    // New device: collect the envelope and open it — the same Master Key, the token the trusted device was handed.
    device = 'new'
    const collected = await collectPairingChannel(scanned.handle)
    const grant = openGrant(scanned.key, transport.priv, collected.envelope!)
    expect(Array.from(grant.masterKey)).toEqual(Array.from(mk))
    expect(grant.refreshToken).toBe(grantToken)
    expect(grant.label).toBe(label)

    // Adopt, as `onboardTail.adopt` does: the handed-over token in, a cookie of this device's own out.
    clearTokens()
    const adopted = await tokenApi().adoptSession({
      tokenAdoptRequest: { refreshToken: grant.refreshToken, sessionScope: TokenAdoptRequestSessionScopeEnum.Session },
    })
    setTokens(adopted.accessToken, null, adopted.family.id)
    expect(adopted.refreshToken ?? null, 'adoption put a refresh token in the body').toBeNull()
    expect(adopted.family.id).not.toBe(ownerTokens.family)
    const adoptedCookie = cookie(REFRESH_COOKIE)
    expect(adoptedCookie).toBeTruthy()
    expect(adoptedCookie).not.toBe(grant.refreshToken)

    // The adopted session refreshes like any web session, and it is the owner's account.
    await refreshSession()
    const refreshedCookie = cookie(REFRESH_COOKIE)
    expect(refreshedCookie).not.toBe(adoptedCookie)
    expect((await getAccountEmail()).email).toBe(owner.email)

    // The grant token, presented again, is two generations behind — ADR 0004's clone case: refused, and the whole
    // family it belonged to is revoked. The adoption above is its positive control on the same token.
    const replay = await tokenApi()
      .adoptSession({ tokenAdoptRequest: { refreshToken: grant.refreshToken, sessionScope: TokenAdoptRequestSessionScopeEnum.Session } })
      .then(() => null, (e: unknown) => e)
    expect(replay, 'a spent grant token was adopted a second time').not.toBeNull()
    expect(await isCodedError(replay, 401, 'refresh_rejected')).toBe(true)
    // ...so the new device's current cookie, which rotated a moment ago, is dead too. Called on the client directly:
    // the product's refresh would end the session, which needs a document this process does not have.
    const afterRevoke = await tokenApi().refreshSession({ refreshRequest: {} }).then(() => null, (e: unknown) => e)
    expect(afterRevoke, 'the cloned family survived').not.toBeNull()
    expect(await isCodedError(afterRevoke, 401, 'refresh_rejected')).toBe(true)

    // The revocation is per family: the trusted device's own session still rotates.
    device = 'trusted'
    setTokens(ownerTokens.access, null, ownerTokens.family)
    const ownerBefore = cookie(REFRESH_COOKIE)
    await refreshSession()
    expect(cookie(REFRESH_COOKIE)).not.toBe(ownerBefore)

    // The device's name travelled only inside the sealed grant. The email beside it is declared plaintext (`pii`),
    // and the refresh token is a token swept as one — so the label is the canary, not the whole grant.
    recordCanaries(grant.label)
  }, 120_000)
})
