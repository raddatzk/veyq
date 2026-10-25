// Expenses' part of a Pot's re-key (ADR 0109 point 2, ADR 0108 decision 4; core's driver `core/node/rekey/rekeyScope.ts`
// calls it through the kind's `rekeyContent`): each revision's `K_rev` is opened under the Pot's old key and wrapped under
// the new one, bound to the same (scope, Pot, entry, revision) — the body is no input, so no body and no digest moves, and
// the server refuses a staged part that names one (the backend participant's `STALE`). Live and trashed Pots alike: the
// listing serves every revision. The Pot's meta (`pot`: base currency, own categories) holds nothing sealed under the key
// of its own beyond the envelope core re-seals. Staged under the commit's re-key id at the stage's cap; fails closed —
// a wrap that does not open throws, and core does not commit the node.
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { withRefresh } from '../../app/sessionRefresh'
import type { RekeyNodeContext } from '../../core/node/rekey/hooks'
import { STAGE_BATCH } from '../../core/node/rekey/rekeyScope'
import { expensesApi } from './api'
import { rewrapRevisionKey } from './domain/codec'

/** The listing's page size: the stage's cap, so one page is one batch. */
const PAGE = STAGE_BATCH

/** The kind's `rekeyContent` for EXPENSES_POT (see the header). */
export async function rekeyPotContent(ctx: RekeyNodeContext): Promise<void> {
  const { scopeId, node, oldKey, newKey } = ctx
  let since: string | undefined
  for (;;) {
    ctx.signal?.throwIfAborted()
    const page = await withRefresh(() => expensesApi().listPotRekey({ scopeId, nodeId: node.id, since, limit: PAGE }))
    const revisions = page.revisions.map((r) => ({
      entryId: r.entryId,
      revision: r.revision,
      wrappedRevisionKey: b64std(rewrapRevisionKey({ scopeId, nodeId: node.id, entryId: r.entryId, revision: r.revision }, oldKey, newKey, fromB64std(r.wrappedRevisionKey))),
    }))
    for (let i = 0; i < revisions.length; i += STAGE_BATCH) {
      const slice = revisions.slice(i, i + STAGE_BATCH)
      await withRefresh(() =>
        expensesApi().stagePotRekey({ scopeId, nodeId: node.id, rekeyId: ctx.rekeyId, potRekeyStage: { fromEpoch: node.epoch, revisions: slice } }),
      )
    }
    if (!page.more) return
    since = page.cursor ?? undefined
  }
}
