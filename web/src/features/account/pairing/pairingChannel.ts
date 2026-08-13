// uc.account.onboard-device (web) — the pairing channel's data layer. A thin wrapper over the generated auth
// client, so the screens mock this module rather than the HTTP runtime.
//
// **Which factory each verb uses is part of the design, not a detail.** `open`, `answer` and `collect` are
// unauthenticated: the device being onboarded has no session, and pickup is deliberately ungated (ADR 0059 §3)
// — the envelope is worthless without the ephemeral private key. `delegate` and `seal` are the two verbs that
// reach an account, and only the trusted, unlocked device makes them.
import { pairingApi, publicPairingApi } from '../../../api/client'
import { withRefresh } from '../login/login'
import type { PairingChannel } from '../../../api/generated/auth'

export type { PairingChannel } from '../../../api/generated/auth'

/** Open a channel under a freshly minted handle. Carries nothing else — no account, no address, no direction. */
export function openPairingChannel(handle: string): Promise<void> {
  return publicPairingApi().openPairingChannel({ pairingOpenRequest: { handle } })
}

/** Post the one opaque answer this channel relays — here, the onboarded device's transport public bundle. */
export function answerPairingChannel(handle: string, answer: string): Promise<void> {
  return publicPairingApi().answerPairingChannel({ pairingAnswerRequest: { handle, answer } })
}

/**
 * Read what the channel currently relays.
 *
 * **This call is not free after the seal.** The server marks the row COLLECTED as soon as it hands an envelope
 * over, whoever asked — so a trusted device that keeps polling eats the grant the onboarded device came for.
 * Reading a channel that holds only an `answer` spends nothing; that is the only window this side may poll in.
 */
export function collectPairingChannel(handle: string): Promise<PairingChannel> {
  return publicPairingApi().collectPairingChannel({ pairingCollectRequest: { handle } })
}

/**
 * Bind the channel to this account and mint the session family the grant carries (ADR 0059 §2). Its own verb,
 * before the seal, because the grant is sealed on this device — so the refresh token has to be in this device's
 * hand before there is anything to seal.
 */
export function delegatePairingSession(handle: string): Promise<string> {
  return withRefresh(() => pairingApi().delegatePairingSession({ pairingDelegateRequest: { handle } })).then(
    (result) => result.refreshToken,
  )
}

/** Post the sealed grant. Accepted only on a channel this account delegated. */
export function sealPairingChannel(handle: string, envelope: string): Promise<void> {
  return withRefresh(() => pairingApi().sealPairingChannel({ pairingSealRequest: { handle, envelope } }))
}
