// Which of the two sealed account documents this device refuses right now (ADR 0028 Decision 7) — the read the
// settings hub uses to decide whether to offer the reset at all, and the reset section uses to say what is refused.
//
// Read-only by construction: it fetches and opens, exactly like every other read, and writes nothing. That is why
// it lives apart from `trustReset.ts` — the hub may import this freely, while the module that WRITES is reachable
// only from the confirm dialog (`eslint.config.js`, `veyq/trust-reset-one-door`).
import { masterKey } from '../../../app/vault'
import { pinStoreDocument } from '../identityPins'
import { registerDocument } from '../deviceRegister'
import { TrustStateRefused, type AccountDocumentStore, type TrustStateRefusal, type AccountDocument } from '../trustMarks'

/** One document this device refuses, and why. */
export type DocumentRefusal = { document: AccountDocument; code: TrustStateRefusal }

/**
 * The documents this device refuses, in a fixed order (pins, then register). Empty when the vault is locked —
 * there is no account to key the marks by — and when a fetch fails for any other reason than a refusal: the
 * reset is offered on a refusal this device decided, never on a network error it cannot tell apart from one.
 */
export async function refusedDocuments(): Promise<DocumentRefusal[]> {
  const out: DocumentRefusal[] = []
  for (const doc of [pinStoreDocument, registerDocument] as AccountDocumentStore<unknown, { revision: number }>[]) {
    const code = await refusalOf(doc)
    if (code) out.push({ document: doc.document, code })
  }
  return out
}

async function refusalOf(doc: AccountDocumentStore<unknown, { revision: number }>): Promise<TrustStateRefusal | null> {
  const mk = masterKey()
  if (!mk) return null
  try {
    await doc.open(mk, await doc.fetch())
    return null
  } catch (e) {
    return e instanceof TrustStateRefused ? e.code : null
  } finally {
    mk.fill(0)
  }
}
