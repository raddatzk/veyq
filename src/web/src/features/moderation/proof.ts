// The key proof (ADR 0099 § 2, en.moderation.moderation-key): every key-proved moderation call carries a signature with
// the CURRENT epoch's moderation key over `moderation-proof` — a fresh server challenge, the contract operationId the
// call is for, and the case it acts on (or none). The server rebuilds the statement from the operation it matched, so a
// proof made for one operation answers for no other; a challenge proves one call.
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { moderationApi } from '../../api/client'
import { withRefresh } from '../../app/sessionRefresh'
import { currentModerationKey } from './moderationKeys'

/** The two proof headers, as the generated client names them. */
export type ProofHeaders = { xVeyqModerationChallenge: string; xVeyqModerationSignature: string }

/** Thrown when a key-proved call is attempted without holding the current epoch's key. */
export class ModerationKeyMissing extends Error {
  constructor() {
    super('moderation_key_missing')
    this.name = 'ModerationKeyMissing'
  }
}

/** A fresh proof for [operationId] (and [caseId]), signed with [key] — or with the held current key. */
export async function proofFor(
  operationId: string,
  caseId: string | null = null,
  key: Uint8Array | null = null,
): Promise<ProofHeaders> {
  const held = key ? null : currentModerationKey()
  const m = key ?? held?.key
  if (!m) throw new ModerationKeyMissing()
  try {
    const { challenge } = await withRefresh(() => moderationApi().createModerationChallenge())
    const statement = v1.moderationProofStatement({ challenge: fromB64std(challenge), operationId, caseId })
    return { xVeyqModerationChallenge: challenge, xVeyqModerationSignature: b64std(v1.moderationSign(m, statement)) }
  } finally {
    held?.key.fill(0)
  }
}
