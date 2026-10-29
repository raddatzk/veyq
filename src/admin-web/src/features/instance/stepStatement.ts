// uc.moderation.object-to-a-measure A7 (admin-web, ADR 0093 § 7) — the statement of reasons a Server Owner's own step
// carries when it is taken over content: a member's sign-in block (Server → Moderation, `veyq-admin account block`) or a
// tenant taken offline (the tenant's page, `veyq-admin tenant suspend`). Both doors build it here, so the rules hold for
// the UI and the CLI alike:
//   - the facts go in the clear, for the mail to the blocked member or the tenant's owners — they cannot sign in to open
//     a sealed copy (ADR 0093 § 7); the server mails them once and keeps them only
//     sealed;
//   - that log copy is sealed HERE to the moderation key's PUBLIC half (`moderation-step-facts` [epoch, statementId]),
//     bound to the statement id this client mints — admin-web holds no private half and opens nothing
//     (`moderation.noOpen.test.ts`);
//   - the facts and the provision are required of every statement (ADR 0093 § 7, one form on every instance, § 14) —
//     the server's to refuse, by name.
import { instanceModerationApi } from '../../api/client'
import type { ServerOwnerStatementInput, ServerOwnerStatementInputGroundEnum as ModerationGround } from '../../api/generated/instance'
import { sealStepFacts } from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'

export type { ServerOwnerStatementInput } from '../../api/generated/instance'

/** The grounds a statement rests on, in the moderation surface's order (`ModerationGround`). */
export const GROUNDS: ModerationGround[] = ['CHILD_SEXUAL_ABUSE', 'TERRORIST_CONTENT', 'THREAT_TO_LIFE', 'ILLEGAL_CONTENT', 'TERMS_BREACH']

/** What the Server Owner typed. */
export interface StepStatementDraft {
  ground: ModerationGround
  provision: string
  facts: string
}

export class StepStatementInvalid extends Error {
  constructor(readonly reason: 'moderationOff') {
    super(`step_statement_invalid:${reason}`)
  }
}

/**
 * The statement a step carries: the ground, the provision (trimmed, none when blank), and the facts — in the clear and
 * sealed for the log to the current epoch's public half — under a statement id minted here. No facts, nothing sealed.
 */
export async function stepStatement(draft: StepStatementDraft, newId: () => string = () => crypto.randomUUID()): Promise<ServerOwnerStatementInput> {
  const statementId = newId()
  const provision = draft.provision.trim() || null
  const facts = draft.facts.trim()
  if (!facts) return { statementId, ground: draft.ground, provision, facts: null, factsForLog: null }
  const moderation = await instanceModerationApi().getInstanceModeration()
  if (moderation.currentEpoch == null || !moderation.currentKemPublicKey) throw new StepStatementInvalid('moderationOff')
  const sealed = sealStepFacts(moderation.currentEpoch, statementId, fromB64std(moderation.currentKemPublicKey), facts)
  return {
    statementId,
    ground: draft.ground,
    provision,
    facts,
    factsForLog: { epoch: moderation.currentEpoch, sealedFacts: b64std(sealed) },
  }
}
