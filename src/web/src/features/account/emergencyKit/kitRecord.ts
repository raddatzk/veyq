// The account's kit record (ADR 0157 decisions 3-6, en.account.key-model § The kit record): which layout of the
// Emergency Kit the account last made, so a clearer one can be suggested (uc.account.renew-emergency-kit).
//
// ONE blob sealed under the Master Key, purpose `kit-record` with no binding field (the Master Key is the account's alone,
// the account has one record, and a birth seals it before the account has an id); the plaintext is the compact JSON
// `{"layout":n}`. The server stores and serves it to the account alone and never sees the number. **Last writer wins**:
// no revision and no trust mark — a served older record only suggests a kit nobody needs, a newer one hides a
// suggestion; nothing is opened either way (decision 3).
//
// **No record, or one that does not open, is layout 1** (decision 3, E4): a kit made before the record existed. Never an
// error on screen.
//
// **Every flow that makes a kit writes the record in the same step, after the kit was saved** (decision 4) — the two
// births and the rotation in their request (`sealKitRecord` under the Master Key they hold), the address change and the
// renewal with the `PUT` here (`writeKitRecord`) once `EmergencyKitSave` says the PDF was saved and kept. Each seals it
// while it holds the Master Key and writes the sealed blob afterwards, so no key is held across the person's save.
//
// The layout is read once per unlock and kept in memory (`kitLayoutState`, decision 6): the Settings block and the
// shell's notice share the one read, a write here or a committed rotation sets it to current, and a rotation whose
// outcome is unknown (or its resume) drops it (`rotateKeys.ts`). `clearActiveSpace` drops it too, but nothing in
// production calls that: a sign-out is a document load (ADR 0070), and the memory goes with the page.
import { computed, reactive } from 'vue'
import * as v1 from '@veyq/crypto/v1'
import { b64std, fromB64std } from '@veyq/crypto/base64'
import { accountKitRecordApi } from '../../../api/client'
import { withRefresh } from '../../../app/sessionRefresh'
import { masterKey } from '../../../app/vault'
import { KIT_LAYOUT } from './emergencyKit'

/** The record's seal context (vector `seal.sym.kit-record`): the purpose only, the Master Key is the binding. */
const CONTEXT = { purpose: 'kit-record' } as const

/** What an account without a record made: every kit before ADR 0157. */
export const LAYOUT_WITHOUT_RECORD = 1

const encoder = new TextEncoder()
const decoder = new TextDecoder()

/** The record of a kit of this client's layout, sealed under [mk] → the opaque base64 blob a request carries. The client
 *  only ever writes the layout it prints: every kit it makes is a current one. */
export function sealKitRecord(mk: Uint8Array): string {
  return b64std(v1.seal(CONTEXT, mk, encoder.encode(JSON.stringify({ layout: KIT_LAYOUT }))))
}

/** The layout a served blob names, opened with [mk]: {@link LAYOUT_WITHOUT_RECORD} for no blob, one that does not open (a
 *  blob that is not this account's, or garbage) and JSON without a positive integer `layout` (E4). */
export function layoutOf(mk: Uint8Array, blob: string | null | undefined): number {
  if (!blob) return LAYOUT_WITHOUT_RECORD
  try {
    const layout: unknown = (JSON.parse(decoder.decode(v1.open(CONTEXT, mk, fromB64std(blob)))) as { layout?: unknown } | null)?.layout
    return typeof layout === 'number' && Number.isSafeInteger(layout) && layout > 0 ? layout : LAYOUT_WITHOUT_RECORD
  } catch {
    return LAYOUT_WITHOUT_RECORD
  }
}

/** Read the caller's record (`GET /api/account/kit-record`) and open it with [mk]. Rejects only when the read fails. */
export async function readKitLayout(mk: Uint8Array): Promise<number> {
  return layoutOf(mk, (await withRefresh(() => accountKitRecordApi().getKitRecord())).blob)
}

/** Write a record [sealKitRecord] sealed (`PUT /api/account/kit-record`), and remember that the account's kit is current. */
export async function writeKitRecord(sealed: string): Promise<void> {
  await withRefresh(() => accountKitRecordApi().putKitRecord({ kitRecordUpdate: { blob: sealed } }))
  rememberCurrentKit()
}

/**
 * The layout as this unlock read it: `layout` null until read (or while a read failed: `failed`). In memory only, and
 * dropped with the keys ({@link resetKitLayout}).
 */
export const kitLayoutState = reactive({ layout: null as number | null, failed: false })

/** Whether the account's last kit is of an older layout than this client prints — the suggestion's one condition. */
export const kitLayoutOlder = computed(() => kitLayoutState.layout !== null && kitLayoutState.layout < KIT_LAYOUT)

let reading: Promise<void> | null = null
/** Bumped by every reset, so a read that started before it never installs a layout read under the keys that went. */
let generation = 0

/**
 * Read the layout once for this unlock: the first caller reads, every later one shares that read or its answer. Does
 * nothing while the vault is locked. A failed read leaves the layout unknown (`failed`) and the next caller asks again.
 */
export function ensureKitLayout(): Promise<void> {
  if (kitLayoutState.layout !== null) return Promise.resolve()
  if (reading) return reading
  const mk = masterKey()
  if (!mk) return Promise.resolve()
  const at = generation
  kitLayoutState.failed = false
  reading = readKitLayout(mk)
    .then(
      (layout) => {
        if (at === generation) kitLayoutState.layout = layout
      },
      () => {
        if (at === generation) kitLayoutState.failed = true
      },
    )
    .finally(() => {
      mk.fill(0)
      if (at === generation) reading = null
    })
  return reading
}

/** The account just made a kit of this client's layout and its record says so (a write here, or a committed rotation). */
export function rememberCurrentKit(): void {
  generation++ // a read still on its way answered for the record before this one
  reading = null
  kitLayoutState.layout = KIT_LAYOUT
  kitLayoutState.failed = false
}

/** Drop what this unlock read (sign-out / re-lock): the next unlock reads again. */
export function resetKitLayout(): void {
  generation++
  reading = null
  kitLayoutState.layout = null
  kitLayoutState.failed = false
}
