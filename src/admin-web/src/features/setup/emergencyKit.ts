// The Emergency Kit — a saveable artifact carrying everything needed to sign in again EXCEPT the password
// (which stays only in the user's head). It's human-readable (printed text), camera-readable (a QR), and
// machine-readable (an embedded payload), so it can be re-entered, scanned, or imported as a file later.
// The Secret Key is sensitive — this is the user's recovery secret, so the kit must be stored safely.
// jspdf + qrcode are heavy and only needed when actually writing a kit — they're imported lazily inside
// renderKitPdf so the setup screen's first paint stays cheap. The layout is `src/web/`'s
// `features/account/emergencyKit/emergencyKit.ts`, line for line but for one row: this kit names no address it was
// made on (`KitContext`). A shared layout would be UI in `@veyq/crypto`, which holds nothing but crypto (ADR 0065).
// The `veyqkit:` codec is `@veyq/crypto/emergencyKit`, shared with `src/web/`, which is the only package that ever
// reads a kit back.
//
// `admin-web`'s half is the WRITE half only. `src/web/`'s version also imports a kit — from the PDF's text or by
// decoding a QR image with `jsqr` — because a returning member arrives with a kit in hand. This surface never
// does: the only kit it ever meets is the one first-run setup just minted, one screen ago, for an account that
// did not exist a moment before. Dropping the read half drops `jsqr` with it, which is worth naming rather
// than trimming quietly: the root CLAUDE.md forbids unmaintained dependencies and `src/web/CLAUDE.md` grants jsQR
// a single, argued exception (HEAD from 2021). An exception earned once is not a licence to spread it to a
// second package for a code path that has no caller.
//
// Measured, not assumed: under npm, the local `npm run build` was GREEN with `jsqr` undeclared, because npm workspaces
// hoisted `src/web/`'s copy into the root `node_modules`. Only the container build — which never sees `src/web/` —
// failed to resolve it. pnpm links only declared dependencies into a package's `node_modules`, so that hiding is gone.

import { kitPayload, type KitData } from '@veyq/crypto/emergencyKit'
import { readSecretKey } from '@veyq/crypto/secretKey'

/** Resolves a `kit.*` message key in the user's language, with named values — the caller's `t`. */
export type Translate = (key: string, named?: Record<string, unknown>) => string

/** What the kit is written in: the caller's messages, and the language tag its date is formatted for. */
export type KitLanguage = { t: Translate; locale: string }

/** The facts of the kit beyond its data: when it is made. No address it is made on, unlike `src/web/`'s: this surface
 *  is served on localhost, which is not where the Server Owner signs in to the app on another device. */
export type KitContext = { now?: Date }

// The kit's palette, from the brand mark: ink, a muted line, the brand violet and its deep shade, a tint, a rule, and
// the callout's amber.
type Rgb = readonly [number, number, number]
const C: Record<'ink' | 'muted' | 'brand' | 'brandDeep' | 'tint' | 'line' | 'warnBg' | 'warnLine' | 'warnInk', Rgb> = {
  ink: [24, 20, 38],
  muted: [102, 96, 120],
  brand: [91, 63, 168],
  brandDeep: [69, 33, 99],
  tint: [245, 242, 252],
  line: [214, 206, 236],
  warnBg: [255, 247, 230],
  warnLine: [240, 200, 120],
  warnInk: [122, 74, 0],
}
const WHITE: Rgb = [255, 255, 255]

/**
 * Render the Emergency Kit PDF (A4): the human text in the caller's language, the Secret Key in its printed form, the QR,
 * three form fields for the addresses the person signs in at, and the token in the document information. Returns the
 * file's bytes; `saveKitPdf` hands them to the user.
 */
export async function renderKitPdf(data: KitData, { t, locale }: KitLanguage, { now = new Date() }: KitContext = {}): Promise<Uint8Array> {
  const read = readSecretKey(data.secretKey)
  if (!read.ok) throw new Error(`renderKitPdf: the Secret Key is ${read.problem}`)
  read.key.fill(0)
  const groups = read.text.split('-')
  const [{ jsPDF, AcroFormTextField }, QRCode, logo] = await Promise.all([
    import('jspdf'),
    import('qrcode').then((m) => m.default),
    import('../../assets/brand/logo-kit.png?inline').then((m) => m.default),
  ])
  const payload = kitPayload(data)
  const qr = await QRCode.toDataURL(payload, { margin: 0, width: 480, color: { dark: '#181426' } })
  const doc = new jsPDF({ unit: 'pt', format: 'a4' })
  // Embed the machine-readable payload in the document metadata — uncompressed in the PDF bytes, so a later
  // file import can recover it by scanning the file text without decoding the QR. This embedding is part of the
  // format (en.account.key-model § The Emergency Kit); emergencyKit.test.ts reads it back through the reader.
  doc.setProperties({ title: t('kit.pdfTitle'), keywords: payload })

  const W = 595.28
  const M = 48
  const CW = W - 2 * M
  const fill = ([r, g, b]: Rgb) => doc.setFillColor(r, g, b)
  const draw = ([r, g, b]: Rgb) => doc.setDrawColor(r, g, b)
  const ink = ([r, g, b]: Rgb) => doc.setTextColor(r, g, b)
  const font = (style: 'normal' | 'bold', size: number, color: Rgb, face = 'helvetica') => {
    doc.setFont(face, style)
    doc.setFontSize(size)
    ink(color)
  }

  // Brand strip, mark and wordmark, date.
  fill(C.brandDeep)
  doc.rect(0, 0, W, 6, 'F')
  fill(C.brand)
  doc.rect(0, 6, W, 2, 'F')
  doc.addImage(logo, 'PNG', M, 34, 30, 32.4, 'logo', 'FAST')
  font('bold', 15, C.brandDeep)
  doc.text('veyq', M + 40, 56)
  font('normal', 9, C.muted)
  doc.text(t('kit.pdfCreated', { date: new Intl.DateTimeFormat(locale, { dateStyle: 'long' }).format(now) }), W - M, 48, { align: 'right' })
  doc.text(t('kit.pdfPage'), W - M, 60, { align: 'right' })

  // Title and what the sheet is for.
  font('bold', 28, C.ink)
  doc.text(t('kit.pdfHeading'), M, 112)
  font('normal', 11, C.muted)
  doc.text(t('kit.pdfIntro'), M, 134, { maxWidth: CW, lineHeightFactor: 1.35 })

  // Confidential callout.
  let y = 160
  fill(C.warnBg)
  draw(C.warnLine)
  doc.setLineWidth(0.8)
  doc.roundedRect(M, y, CW, 40, 6, 6, 'FD')
  font('bold', 10, C.warnInk)
  doc.text(t('kit.pdfConfidential'), M + 14, y + 17)
  doc.setFont('helvetica', 'normal')
  doc.text(t('kit.pdfConfidentialBody'), M + 14, y + 30)

  const section = (n: number, title: string, top: number) => {
    fill(C.brand)
    doc.circle(M + 9, top - 4, 9, 'F')
    font('bold', 10, WHITE)
    doc.text(String(n), M + 9, top - 0.5, { align: 'center' })
    font('bold', 13, C.ink)
    doc.text(title, M + 26, top)
  }

  // 1 — the account: the email and the Secret Key, three lines of three groups.
  y = 232
  section(1, t('kit.pdfAccount'), y)
  y += 16
  fill(C.tint)
  draw(C.line)
  doc.roundedRect(M, y, CW, 138, 8, 8, 'FD')
  font('bold', 8.5, C.muted)
  doc.text(t('kit.pdfEmail').toUpperCase(), M + 18, y + 24)
  font('normal', 13, C.ink)
  doc.text(data.email, M + 18, y + 42)
  font('bold', 8.5, C.muted)
  doc.text(t('kit.pdfSecretKey').toUpperCase(), M + 18, y + 68)
  font('bold', 16, C.brandDeep, 'courier')
  for (let line = 0; line < 3; line++) doc.text(groups.slice(line * 3, line * 3 + 3).join('-'), M + 18, y + 90 + line * 20)

  // 2 — where you sign in: three lines (form fields) for the addresses. The member's kit names the one it was made on
  // first; this one cannot (KitContext), so its lines move up by that row.
  y += 162
  section(2, t('kit.pdfWhere'), y)
  y += 20
  font('normal', 9, C.muted)
  doc.text(t('kit.pdfMoreAddresses'), M, y + 4, { maxWidth: CW })
  draw(C.line)
  doc.setLineWidth(0.8)
  for (let i = 0; i < 3; i++) {
    const ly = y + 28 + i * 24
    doc.line(M, ly, W - M, ly)
    const field = new AcroFormTextField()
    field.fieldName = `address${i + 1}`
    field.x = M
    field.y = ly - 18
    field.width = CW
    field.height = 18
    field.fontSize = 11
    field.value = ''
    doc.addField(field)
  }

  // 3 — quick restore: the QR in its frame, and the three steps (the password is not on this sheet).
  y += 104
  section(3, t('kit.pdfRestore'), y)
  y += 16
  const box = 118
  draw(C.line)
  fill(WHITE)
  doc.roundedRect(M, y, box, box, 8, 8, 'FD')
  doc.addImage(qr, 'PNG', M + 12, y + 12, box - 24, box - 24, 'qr', 'FAST')
  const tx = M + box + 22
  const tw = W - M - tx
  let sy = y + 16
  ;['kit.pdfStep1', 'kit.pdfStep2', 'kit.pdfStep3'].forEach((key, i) => {
    font('bold', 10.5, C.brand)
    doc.text(`${i + 1}.`, tx, sy)
    font('normal', 10.5, C.ink)
    const lines = doc.splitTextToSize(t(key), tw - 16)
    doc.text(lines, tx + 16, sy, { lineHeightFactor: 1.35 })
    sy += lines.length * 14.2 + 10
  })

  // 4 — how to keep it.
  y += box + 30
  section(4, t('kit.pdfKeep'), y)
  y += 20
  font('normal', 10.5, C.ink)
  ;['kit.pdfKeep1', 'kit.pdfKeep2', 'kit.pdfKeep3'].forEach((key) => {
    fill(C.brand)
    doc.circle(M + 4, y - 3.5, 2.2, 'F')
    const lines = doc.splitTextToSize(t(key), CW - 16)
    doc.text(lines, M + 14, y, { lineHeightFactor: 1.35 })
    y += lines.length * 14.2 + 4
  })

  // Footer: where to get help (the site's forgot-password page in this language), and the marking again.
  draw(C.line)
  doc.setLineWidth(0.6)
  doc.line(M, 800, W - M, 800)
  font('normal', 8.5, C.muted)
  const help = t('kit.pdfHelpUrl')
  doc.textWithLink(t('kit.pdfHelp', { url: help.replace(/^https:\/\//, '') }), M, 816, { url: help })
  doc.text(`${t('kit.pdfConfidential')} · ${t('kit.pdfTitle')}`, W - M, 816, { align: 'right' })

  return new Uint8Array(doc.output('arraybuffer'))
}

/** Hand a rendered kit to the user as a download (a Blob object URL + a synthetic `<a download>`, then revoke). */
export function saveKitPdf(bytes: Uint8Array, email: string): void {
  const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type: 'application/pdf' }))
  try {
    const a = document.createElement('a')
    a.href = url
    a.download = `veyq-emergency-kit-${email}.pdf`
    document.body.appendChild(a)
    a.click()
    a.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}
