// The Emergency Kit — a saveable artifact carrying everything needed to sign in again EXCEPT the password
// (which stays only in the user's head). It's human-readable (printed text), camera-readable (a QR), and
// machine-readable (an embedded payload), so it can be re-entered, scanned, or imported as a file later.
// The Secret Key is sensitive — this is the user's recovery secret, so the kit must be stored safely.
// jspdf + qrcode are heavy and only needed when actually writing a kit — they're imported lazily inside
// renderKitPdf so the setup screen's first paint stays cheap. The `veyqkit:` codec is `@veyq/crypto/emergencyKit`,
// shared with `src/web/`, which is the only package that ever reads a kit back.
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

/** Resolves a `kit.*` message key in the user's language — the caller's `t`. */
export type Translate = (key: string) => string

/**
 * Render the Emergency Kit PDF: human text in the caller's language + QR + the embedded payload (in the PDF
 * metadata). Returns the file's bytes; `saveKitPdf` hands them to the user.
 */
export async function renderKitPdf(data: KitData, t: Translate): Promise<Uint8Array> {
  const [{ jsPDF }, QRCode] = await Promise.all([import('jspdf'), import('qrcode').then((m) => m.default)])
  const payload = kitPayload(data)
  const qr = await QRCode.toDataURL(payload, { margin: 1, width: 320 })
  const doc = new jsPDF({ unit: 'pt', format: 'a4' })
  // Embed the machine-readable payload in the document metadata — uncompressed in the PDF bytes, so `src/web/`'s
  // file import can recover it by scanning the file text without decoding the QR. This embedding is part of the
  // format (en.account.key-model § The Emergency Kit); emergencyKit.test.ts reads it back with the shared parser.
  doc.setProperties({ title: t('kit.pdfTitle'), keywords: payload })

  doc.setFontSize(22)
  doc.text(t('kit.pdfHeading'), 56, 70)
  doc.setFontSize(11)
  doc.setTextColor(90)
  doc.text(t('kit.pdfKeepSafe'), 56, 96, { maxWidth: 480 })
  doc.setTextColor(20)
  doc.setFontSize(12)
  let y = 140
  doc.setFont('helvetica', 'bold')
  doc.text(t('kit.pdfEmail'), 56, y)
  doc.setFont('helvetica', 'normal')
  doc.text(data.email, 160, y)
  y += 26
  doc.setFont('helvetica', 'bold')
  doc.text(t('kit.pdfSecretKey'), 56, y)
  doc.setFont('courier', 'normal')
  doc.setFontSize(11)
  doc.text(doc.splitTextToSize(data.secretKey, 360), 160, y)
  y += 50

  doc.addImage(qr, 'PNG', 56, y, 150, 150)
  doc.setFont('helvetica', 'normal')
  doc.setFontSize(10)
  doc.setTextColor(90)
  doc.text(t('kit.pdfScanHint'), 220, y + 80, { maxWidth: 300 })

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
