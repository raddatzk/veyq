// The report bundle (ADR 0093 §§ 1–2, ADR 0099 § 2): what a reporter's client builds for exactly what it reports.
//
//   - A fresh 32-byte `K_report` per report.
//   - Per reported item, a **report share**: the keys that open that item, sealed under `K_report` — purpose
//     `report-share` [itemKind, itemId] for a node or an application's item, `report-link` [shareId] for an upload
//     link. Its plaintext is the list of keys (`encodeReportKeys`), each tagged with the role it plays (a node's own
//     key, its name's field key, a blob's key with the blob's id, …), so the server learns how many keys a share holds
//     at most by its length, never which.
//   - The **sealed part**: `K_report`, the reporter's reason, their contact and the context they add, sealed to the
//     current epoch's X-Wing public half — `sealTo` purpose `moderation-report` [epoch]. A report stays sealed to the
//     epoch it was made under; opening it needs that epoch's `M_n` (`moderationKemSecret`). Its plaintext is padded to a
//     multiple of `REPORT.bucketBytes`, so its length tells the server which bucket the texts fell into, not how long a
//     reason is.
//
// The server stores both as the opaque base64 they are; in the clear it gets the category, the time and the ids
// (the hold needs them). Pinned by `contracts/crypto/vectors/v1.json#report_bundle` and the three seal vectors.
import { concat, lp, u32be, utf8, type HybridPublic, type HybridSecret } from './primitives'
import { open, openAs, seal, sealTo } from './seal'

/** The layout's bounds, in UTF-8 bytes where a text is meant. */
export const REPORT = {
  keyBytes: 32,
  reasonBytes: 8000,
  contactBytes: 254,
  contextBytes: 8000,
  bucketBytes: 512,
  maxKeysPerShare: 32,
} as const

/** A key a report share hands over: its role (`^[A-Z][A-Z0-9_]{0,15}$`), the object it belongs to where the role
 *  alone does not say (a blob's id), and the 32-byte key. */
export type ReportKey = { role: string; ref: string | null; key: Uint8Array }

/** What the reporter wrote, sealed with `K_report` to the moderation key. `contact` and `context` may be ''. */
export type ReportBody = { reportKey: Uint8Array; reason: string; contact: string; context: string }

/** An item a report share is addressed by: a node, an application's item inside one, or an upload link. */
export type ReportItemAddress = { kind: 'NODE' | 'ITEM'; id: string } | { kind: 'LINK'; shareId: string }

/** An authentic plaintext that is not a report body or key list, or input the layout refuses to write. */
export class ReportInvalid extends Error {
  constructor(detail: string) {
    super(`report_invalid: ${detail}`)
    this.name = 'ReportInvalid'
  }
}

const ROLE = /^[A-Z][A-Z0-9_]{0,15}$/
const CANONICAL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
// Stryker disable next-line StringLiteral: reported Survived, killed in fact — `new TextDecoder('')` throws a RangeError
// at module load, so every suite importing v1 fails to import, which Stryker's vitest runner counts as no failing test
// (the same measurement as primitives.ts's `normalize('NFC')`).
const td = new TextDecoder('utf-8', { fatal: true })

/** A fresh `K_report`. */
export const newReportKey = (): Uint8Array => crypto.getRandomValues(new Uint8Array(REPORT.keyBytes))

/** Reads length-prefixed fields off a plaintext, refusing a field that runs past its end. */
class Reader {
  private at = 0
  constructor(private readonly bytes: Uint8Array) {}
  u32(): number {
    if (this.at + 4 > this.bytes.length) throw new ReportInvalid('truncated')
    const n = new DataView(this.bytes.buffer, this.bytes.byteOffset + this.at, 4).getUint32(0, false)
    this.at += 4
    return n
  }
  field(): Uint8Array {
    const n = this.u32()
    if (this.at + n > this.bytes.length) throw new ReportInvalid('truncated')
    const out = this.bytes.slice(this.at, this.at + n)
    this.at += n
    return out
  }
  rest(): Uint8Array {
    return this.bytes.slice(this.at)
  }
}

const text = (bytes: Uint8Array, what: string): string => {
  try {
    return td.decode(bytes)
  } catch {
    throw new ReportInvalid(`${what} is not UTF-8`)
  }
}

/** `u32be(n) ‖ (lp(utf8(role)) ‖ lp(utf8(ref) or empty) ‖ lp(key))…` — at least one key, at most `maxKeysPerShare`. */
export function encodeReportKeys(keys: readonly ReportKey[]): Uint8Array {
  if (keys.length === 0 || keys.length > REPORT.maxKeysPerShare) throw new ReportInvalid('key count')
  return concat(
    u32be(keys.length),
    ...keys.map((k) => {
      if (typeof k.role !== 'string' || !ROLE.test(k.role)) throw new ReportInvalid('role')
      if (k.ref !== null && (typeof k.ref !== 'string' || !CANONICAL_UUID.test(k.ref))) throw new ReportInvalid('ref')
      if (!(k.key instanceof Uint8Array) || k.key.length !== REPORT.keyBytes) throw new ReportInvalid('key')
      return concat(lp(utf8(k.role)), lp(k.ref === null ? new Uint8Array(0) : utf8(k.ref)), lp(k.key))
    }),
  )
}

/** Read a key list back: exactly the encoding `encodeReportKeys` writes, nothing after it. */
export function decodeReportKeys(plaintext: Uint8Array): ReportKey[] {
  const r = new Reader(plaintext)
  const n = r.u32()
  if (n === 0 || n > REPORT.maxKeysPerShare) throw new ReportInvalid('key count')
  const keys: ReportKey[] = []
  for (let i = 0; i < n; i++) {
    const role = text(r.field(), 'role')
    const ref = r.field()
    const key = r.field()
    if (!ROLE.test(role)) throw new ReportInvalid('role')
    const refText = ref.length === 0 ? null : text(ref, 'ref')
    if (refText !== null && !CANONICAL_UUID.test(refText)) throw new ReportInvalid('ref')
    if (key.length !== REPORT.keyBytes) throw new ReportInvalid('key')
    keys.push({ role, ref: refText, key })
  }
  if (r.rest().length !== 0) throw new ReportInvalid('trailing bytes')
  return keys
}

/**
 * `lp(K_report) ‖ lp(utf8(reason)) ‖ lp(utf8(contact)) ‖ lp(utf8(context))`, zero-padded to the next multiple of
 * `bucketBytes`. The texts are NFC; the reason must not be blank; each is refused above its byte bound rather than cut.
 */
export function encodeReportBody(body: ReportBody): Uint8Array {
  if (!(body.reportKey instanceof Uint8Array) || body.reportKey.length !== REPORT.keyBytes) {
    throw new ReportInvalid('report key')
  }
  const reason = utf8(body.reason)
  const contact = utf8(body.contact)
  const context = utf8(body.context)
  if (body.reason.trim() === '' || reason.length > REPORT.reasonBytes) throw new ReportInvalid('reason')
  if (contact.length > REPORT.contactBytes) throw new ReportInvalid('contact')
  if (context.length > REPORT.contextBytes) throw new ReportInvalid('context')
  const fields = concat(lp(body.reportKey), lp(reason), lp(contact), lp(context))
  const out = new Uint8Array(Math.ceil(fields.length / REPORT.bucketBytes) * REPORT.bucketBytes)
  out.set(fields)
  return out
}

/** Read a body back; the padding after the four fields must be zeros. */
export function decodeReportBody(plaintext: Uint8Array): ReportBody {
  if (plaintext.length % REPORT.bucketBytes !== 0) throw new ReportInvalid('padding')
  const r = new Reader(plaintext)
  const reportKey = r.field()
  const reason = text(r.field(), 'reason')
  const contact = text(r.field(), 'contact')
  const context = text(r.field(), 'context')
  if (reportKey.length !== REPORT.keyBytes) throw new ReportInvalid('report key')
  if (r.rest().some((b) => b !== 0)) throw new ReportInvalid('padding')
  return { reportKey, reason, contact, context }
}

/** Seal the report's body to the epoch's public half (`moderation-report` [epoch]). */
export const sealReport = (epoch: number, kem: HybridPublic, body: ReportBody): Uint8Array =>
  sealTo({ purpose: 'moderation-report', epoch }, kem, encodeReportBody(body))

/** Open a report with the epoch's decapsulation key (`moderationKemSecret(M_n)`); the epoch is the one it is stored at. */
export const openReport = (epoch: number, kemSecret: HybridSecret, blob: Uint8Array): ReportBody =>
  decodeReportBody(openAs({ purpose: 'moderation-report', epoch }, kemSecret, blob))

/** Seal one item's keys under `K_report`, addressed by the item. */
export function sealReportShare(reportKey: Uint8Array, item: ReportItemAddress, keys: readonly ReportKey[]): Uint8Array {
  const plaintext = encodeReportKeys(keys)
  return item.kind === 'LINK'
    ? seal({ purpose: 'report-link', shareId: item.shareId }, reportKey, plaintext)
    : seal({ purpose: 'report-share', itemKind: item.kind, itemId: item.id }, reportKey, plaintext)
}

/** Open one report share with `K_report`, addressed by the item the server stored it for. */
export function openReportShare(reportKey: Uint8Array, item: ReportItemAddress, blob: Uint8Array): ReportKey[] {
  const plaintext =
    item.kind === 'LINK'
      ? open({ purpose: 'report-link', shareId: item.shareId }, reportKey, blob)
      : open({ purpose: 'report-share', itemKind: item.kind, itemId: item.id }, reportKey, blob)
  return decodeReportKeys(plaintext)
}
