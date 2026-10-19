// An affected member's statements of reasons (uc.moderation.object-to-a-measure steps 5, 8, 10): read once per page and
// afresh after an objection, matched to a blocked item, node or link by its id; the facts' key unwraps with the member's
// own identity key (`moderation-facts-key` [caseId, statementId, accountId]) and opens the facts sealed once for every
// member (`moderation-statement-facts` [caseId, statementId]), and an objection seals to the moderation key's current epoch
// (`moderation-objection` [epoch, statementId]). Nothing here is shown to a guest: a guest has no session to ask with.
import { reactive } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { statementsApi } from '../../../api/client'
import type { ModerationStatement } from '../../../api/generated/moderation'
import { account } from '../../../app/account'
import { withRefresh } from '../../../app/sessionRefresh'
import { identityPrivate } from '../../../app/vault'
import { publishedKey } from '../../../core/report/report'
import { decodeHttpError } from '../../../shared/httpError'
import { readMyStatements } from './statementsRead'

export const statements = reactive({ list: [] as ModerationStatement[], loaded: false })

let reading: Promise<void> | null = null

/** Read the caller’s statements once (or [fresh]); a page with no unlocked member reads nothing. A failed read shows none. */
export function loadStatements(fresh = false): Promise<void> {
  if (!account.userId) return Promise.resolve()
  if (!reading || fresh) {
    reading = readMyStatements()
      .then((list) => {
        statements.list = list
        statements.loaded = true
      })
      .catch(() => {
        reading = null
      })
  }
  return reading
}

/** The newest statement about [target]: a node or an item by its id, or a link by its share id. */
export function statementFor(target: { targetId?: string | null; shareId?: string | null }): ModerationStatement | undefined {
  return statements.list.find(
    (s) => (target.targetId != null && s.targetId === target.targetId) || (target.shareId != null && s.shareId === target.shareId),
  )
}

/**
 * The facts the moderators wrote: their key unwrapped with this member's identity key, then the one sealed copy opened
 * with it, both at the statement the member reads; null without facts or when either does not open.
 */
export function openFacts(s: ModerationStatement): string | null {
  if (!s.sealedFacts || !s.wrappedFactsKey) return null
  const priv = identityPrivate()
  if (!priv) return null
  let factsKey: Uint8Array | null = null
  try {
    factsKey = v1.unwrapFactsKey(s.caseId, s.id, account.userId, priv.kemSeed, fromB64std(s.wrappedFactsKey))
    return v1.openStatementFacts(s.caseId, s.id, factsKey, fromB64std(s.sealedFacts))
  } catch {
    return null
  } finally {
    factsKey?.fill(0)
    priv.kemSeed.fill(0)
    priv.sign.fill(0)
  }
}

/** Why an objection was refused, in the wire's words — or null for anything else. */
export async function objectionFailure(e: unknown): Promise<string | null> {
  return (await decodeHttpError(e)).code ?? null
}

/** Object once to [s]'s measure: sealed to the current epoch, read afresh once when the server says it moved on. */
export async function objectTo(s: ModerationStatement, text: string): Promise<ModerationStatement> {
  const send = async (fresh: boolean) => {
    const key = await publishedKey(fresh)
    if (!key) throw new Error('moderation_off')
    const sealedObjection = b64std(v1.sealObjection(key.epoch, s.id, key.kem, text))
    return withRefresh(() =>
      statementsApi().objectToStatement({ statementId: s.id, objectionSubmission: { epoch: key.epoch, sealedObjection } }),
    )
  }
  let answered: ModerationStatement
  try {
    answered = await send(false)
  } catch (e) {
    if ((await objectionFailure(e)) !== 'moderation_epoch_changed') throw e
    answered = await send(true)
  }
  statements.list = statements.list.map((x) => (x.id === answered.id ? answered : x))
  return answered
}
