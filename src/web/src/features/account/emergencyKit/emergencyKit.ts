// The Emergency Kit — a saveable artifact carrying everything needed to sign in again EXCEPT the password
// (which stays only in the user's head). It's human-readable (printed text), camera-readable (a QR), and
// machine-readable (an embedded payload), so it can be re-entered, scanned, or imported as a file later.
// The Secret Key is sensitive — this is the user's recovery secret, so the kit must be stored safely.
//
// This module is the kit's DOM half only: reading a kit from a file or a QR image, and writing the PDF. The
// `veyqkit:` codec itself is `@veyq/crypto/emergencyKit`, shared with admin-web, which writes kits too.
// jspdf + qrcode are heavy and only needed when actually writing a kit — they're imported lazily inside
// renderKitPdf so the parse/import path (the common one) stays cheap.

import { kitPayload, parseKit, type KitData } from '@veyq/crypto/emergencyKit'
import { saveBytes } from '../../../shared/saveBytes'

/** Decode the QR from an image file (a screenshot/photo of the kit) — draw it to a canvas and run jsQR. */
async function decodeQrImage(file: File): Promise<string | null> {
  const jsQR = (await import('jsqr')).default
  const bitmap = await createImageBitmap(file)
  try {
    const canvas = document.createElement('canvas')
    canvas.width = bitmap.width
    canvas.height = bitmap.height
    const ctx = canvas.getContext('2d')
    if (!ctx) return null
    ctx.drawImage(bitmap, 0, 0)
    const { data, width, height } = ctx.getImageData(0, 0, canvas.width, canvas.height)
    return jsQR(data, width, height)?.data ?? null
  } finally {
    bitmap.close()
  }
}

/**
 * Recover the kit from an uploaded file — the kit **PDF** (or any text carrying the `veyqkit:` token), or a
 * QR **image** (screenshot/photo). Entirely client-side; returns the parsed kit or null if no kit is found.
 */
export async function importKitFromFile(file: File): Promise<KitData | null> {
  try {
    const fromText = parseKit(await file.text())
    if (fromText) return fromText
  } catch {
    /* not text / unreadable — try the image path */
  }
  if (file.type.startsWith('image/')) {
    try {
      const token = await decodeQrImage(file)
      if (token) return parseKit(token)
    } catch {
      /* unreadable image */
    }
  }
  return null
}

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
  // Embed the machine-readable payload in the document metadata — uncompressed in the PDF bytes, so a later
  // file import can recover it by scanning the file text without decoding the QR. This embedding is part of the
  // format (en.account.key-model § The Emergency Kit); emergencyKit.test.ts reads it back through the reader.
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

/** Hand a rendered kit to the user as a download. */
export function saveKitPdf(bytes: Uint8Array, email: string): void {
  saveBytes(bytes, `veyq-emergency-kit-${email}.pdf`)
}
