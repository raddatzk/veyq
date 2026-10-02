// Starting a refused trust document over — the one way back ADR 0028 Decision 7 has (inbox #192).
//
// A device refuses its pin store or its device register when the served copy is older than one it has held,
// missing after it held one, or does not open — never reading it as empty, because an empty store is exactly what
// turns a detected key change back into a silent first use. Nothing replaces a refused document on its own: no
// pin is read or written, every wrap to a foreign key pauses, and the register stops naming rows. This module
// replaces it with the EMPTY document, on this device, when the person says so.
//
// **Who can cause it is the whole defence.** The server can cause the refusal (garbage bytes read as unreadable;
// an old copy reads as rolled back), and so it could cause this write if anything it answers reached it. So
// nothing does: no response handler, no refusal path, no worker and no boot step calls this — the only caller is
// the confirm handler of `TrustResetDialog.vue`, after the person ticked that they understand what it costs.
// `eslint.config.js` (`veyq/trust-reset-one-door`) refuses importing this module anywhere else. Its limit: it pins
// who IMPORTS the function, not that the dialog calls it only on a click — the dialog's own test owns that.
//
// **What stays open, stated rather than hidden** (ADR 0028 Decision 7, security-model): a server that keeps a
// document refused on purpose can wait for the person to press this. What changed is that the downgrade is now
// visible and consented, where before Decision 7 it was silent. On a client the operator serves, none of this
// holds against that operator (ADR 0026): it could ship a client that writes the empty store itself.
import { masterKey } from '../../../app/vault'
import { isCodedError, HttpStatus } from '../../../shared/httpError'
import { pinStoreDocument } from '../identityPins'
import { registerDocument } from '../deviceRegister'
import { setMark, TrustStateRefused, type AccountDocument, type AccountDocumentStore } from '../trustMarks'

/** What a reset did: wrote the empty document, or found the served one readable again and left it alone. */
export type ResetOutcome = 'reset' | 'alreadyReadable'

/** Bounds the re-fetch/re-decide loop on a `409 stale_version`, as the documents' own writes are bounded. */
const MAX_RESET_ATTEMPTS = 5

/**
 * Start `document` over on this device: the empty document, sealed under the current Master Key, written one
 * version above the served one.
 *
 *  - **A served copy that opens is never wiped.** Each attempt re-fetches and re-decides; if the document opens
 *    under this device's marks now (another device fixed it, or the server stopped withholding it), nothing is
 *    written and the answer is `alreadyReadable`.
 *  - **A stale write re-decides.** `409 stale_version` means another device wrote meanwhile — maybe a readable
 *    copy — so the next attempt fetches and asks again, bounded.
 *  - **The mark is SET to the written version** ({@link resetMark}) — the one place a mark goes down.
 *
 * Requires an unlocked vault. Throws whatever the fetch or write throws past the bound.
 */
export async function resetAccountDocument(document: AccountDocument): Promise<ResetOutcome> {
  return document === 'pins' ? startOver(pinStoreDocument) : startOver(registerDocument)
}

async function startOver<T>(doc: AccountDocumentStore<T>): Promise<ResetOutcome> {
  for (let attempt = 1; ; attempt++) {
    const mk = masterKey()
    if (!mk) throw new Error('starting a trust document over requires an unlocked vault')
    try {
      const served = await doc.fetch()
      if (await opensNow(doc, mk, served)) return 'alreadyReadable'
      const written = served.version + 1
      try {
        await doc.write({ blob: doc.seal(mk, doc.empty(), written), version: served.version })
      } catch (e) {
        if (attempt < MAX_RESET_ATTEMPTS && (await isCodedError(e, HttpStatus.conflict, 'stale_version'))) continue
        throw e
      }
      await resetMark(mk, doc.document, written)
      return 'reset'
    } finally {
      mk.fill(0)
    }
  }
}

/** Whether the served copy opens under this device's marks — a refusal is `false`, anything else propagates. */
async function opensNow<T>(doc: AccountDocumentStore<T>, mk: Uint8Array, served: { blob: string | null; version: number }) {
  try {
    await doc.open(mk, served)
    return true
  } catch (e) {
    if (e instanceof TrustStateRefused) return false
    throw e
  }
}

/**
 * Set this device's mark for `document` to the version the reset just wrote — **down**, where the served copy
 * sat below the mark (a rolled-back document, e.g. after a backup restore).
 *
 * Needed because the store's compare-and-set only ever writes `served + 1`: for `trust_state_rolled_back` that is
 * at or below the mark, so a reset that merely raised would refuse its own write on the next read. Sound only here:
 * the mark protects this device from a SERVED older copy, and the copy it now accepts is the one it just wrote
 * itself. Not exported — the reset is the only thing that may lower a mark.
 */
function resetMark(mk: Uint8Array, document: AccountDocument, version: number): Promise<void> {
  return setMark(mk, document, version)
}
