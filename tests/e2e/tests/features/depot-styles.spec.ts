// Depot's stylesheet after the core/application split (inbox #517, ADR 0079), read in a real browser — written as lane L1's
// spec and NOT YET RUN (the implementing session ran no stack; it waits for the V1 heavy stage, where it is the visual
// proof of the move). The Memories twin is `memories-styles.spec.ts`.
//
// `styles/pdf.css` went to the application's own `features/depot/depot.css`, imported by the application, so it now loads
// BEFORE core's `style.css`. What a unit test cannot see is the cascade: happy-dom applies no stylesheet. So this reads the
// computed styles of the PDF text layer pdf.js builds over a drawn page — the layer, a text span and the end marker — light
// and dark (a page is paper in every theme). No rule of that sheet tied with a core rule at the move; this holds it there.
// A token's value is never spelled here: a probe element is given the same declaration and its computed value is the
// expected one.
//
// The PDF is built here, one page with one line of Helvetica, so no fixture file stands behind it. It starts from the tenant
// whose owner the round trip activated (the `features` project). Every value typed is a canary (recordTyped). Labels are
// src/web/'s own (src/web/src/i18n/index.ts and src/web/src/features/depot/i18n.ts, en).
import { test, expect, type Locator, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'
import { createFolder, downloadButton, pickFiles } from '../../src/depot'
import { newDevice, signInOwner, tenantOrigin, watchConsole, type ConsoleErrors, type Device } from '../../src/devices'

test.describe.configure({ mode: 'serial' })

const stamp = randomBytes(3).toString('hex')
const folder = `Belege ${stamp}`
const pdfName = `Mietvertrag ${stamp}.pdf`

let member: Device
let pageConsole: ConsoleErrors

/** A one-page PDF 1.4 with `line` in Helvetica: the objects, then an xref table whose offsets are counted, not guessed. */
function onePagePdf(line: string): Uint8Array {
  const content = `BT /F1 24 Tf 72 720 Td (${line}) Tj ET`
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${content.length} >>\nstream\n${content}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ]
  let body = '%PDF-1.4\n'
  const offsets: number[] = []
  objects.forEach((object, i) => {
    offsets.push(body.length)
    body += `${i + 1} 0 obj\n${object}\nendobj\n`
  })
  const xref = body.length
  body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
  for (const offset of offsets) body += `${String(offset).padStart(10, '0')} 00000 n \n`
  body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return new TextEncoder().encode(body)
}

/** The computed value of `property` on a throwaway element given `declarations` — what a rule saying the same resolves to. */
async function probe(page: Page, declarations: string, property: string): Promise<string> {
  return page.evaluate(({ declarations, property }) => {
    const el = document.createElement('div')
    el.style.cssText = declarations
    document.body.append(el)
    const value = getComputedStyle(el).getPropertyValue(property)
    el.remove()
    return value
  }, { declarations, property })
}

/** The computed values of `properties` on the element `locator` names (the first match), or on its `pseudo` element. */
async function styleOf(locator: Locator, properties: string[], pseudo?: string): Promise<Record<string, string>> {
  return locator.first().evaluate((el, { props, pseudo }) => {
    const s = getComputedStyle(el, pseudo)
    return Object.fromEntries(props.map((p) => [p, s.getPropertyValue(p)]))
  }, { props: properties, pseudo })
}

/** The file's own page, in light or dark through the OS preference the theme follows by default ('system'). */
async function openPdf(page: Page, colorScheme: 'light' | 'dark'): Promise<Locator> {
  await page.emulateMedia({ colorScheme })
  await page.goto(tenantOrigin())
  await page.getByRole('button', { name: folder, exact: true }).click({ timeout: 60_000 })
  await page.getByRole('button', { name: `Open ${pdfName}`, exact: true }).click({ timeout: 60_000 })
  await expect(page.locator('html')).toHaveClass(colorScheme === 'dark' ? /\bveyq-dark\b/ : /^(?!.*\bveyq-dark\b)/)
  const layer = page.locator('.pdf-page .pdf-text-layer').first()
  // pdf.js writes the text spans once the page is drawn and near the viewport.
  await expect(layer.locator('span').first()).toBeAttached({ timeout: 60_000 })
  await layerSettled(layer)
  return layer
}

/**
 * Waits until the text layer has stopped being rebuilt: the same width and the same spans over two animation frames.
 * While the viewer's width still changes, every new width re-lays the layer (`PdfPages.vue` redraws, and the engine's
 * `replaceChildren` empties the host first), so a span found a moment ago may be detached when its style is read — and
 * `getComputedStyle` on a detached element reads `''` for every property. The stack lane caught it 2026-10-24: the page went
 * 1016 → 1030 → 1044 px within 20 ms of the span being found, and all five values read `''`.
 */
async function layerSettled(layer: Locator): Promise<void> {
  await expect.poll(() => layer.evaluate((el) => new Promise<boolean>((resolve) => {
    const spans = () => [...el.querySelectorAll('span:not(.markedContent)')]
    const shape = () => `${(el as HTMLElement).style.width}|${spans().length}`
    const before = shape()
    const first = spans()[0]
    requestAnimationFrame(() => requestAnimationFrame(() => {
      resolve(first !== undefined && first.isConnected && spans()[0] === first && shape() === before)
    }))
  })), { message: 'the text layer stops being re-laid', timeout: 60_000 }).toBe(true)
}

test.beforeAll(async ({ browser }) => {
  member = await newDevice(browser)
  pageConsole = watchConsole(member.page)
})

test.afterEach(() => {
  member?.confinement.assertConfined()
  expect(pageConsole.errors, 'the console is free of errors').toEqual([])
})

test.afterAll(async () => {
  await member?.context.close()
})

test(
  "a PDF's text layer wears Depot's own stylesheet, light and dark: transparent, selectable spans over the page",
  { tag: ['@uc.depot.view-file:the-text-of-a-pdf-can-be-selected-and-copied'] },
  async () => {
    const { page } = member
    await signInOwner(member)
    pageConsole.clear()
    await createFolder(page, folder)
    await pickFiles(page, [{ name: pdfName, bytes: onePagePdf(`Probe ${stamp}`) }])
    // Arrived before the page is left: `openPdf` opens a new document, which would cut the upload off mid-way (its
    // `POST …/nodes` was cut 8–13 ms after the pick in lane L1, 2026-10-23, so the file never existed).
    await expect(downloadButton(page, pdfName)).toBeEnabled({ timeout: 60_000 })

    for (const colorScheme of ['light', 'dark'] as const) {
      const layer = await openPdf(page, colorScheme)
      // The layer: over the canvas, clipped to the page, paper in every theme.
      expect(await styleOf(layer, ['position', 'z-index', 'overflow-x', 'color-scheme'])).toEqual({
        position: 'absolute', 'z-index': '1', 'overflow-x': 'clip', 'color-scheme': 'light only',
      })
      // A text span: placed by pdf.js, invisible, selectable, with the accent as its selection.
      const span = layer.locator('span:not(.markedContent)').first()
      expect(await styleOf(span, ['position', 'color', 'user-select', 'white-space-collapse', 'cursor'])).toEqual({
        position: 'absolute',
        color: 'rgba(0, 0, 0, 0)',
        'user-select': 'text',
        'white-space-collapse': 'preserve',
        cursor: 'text',
      })
      expect(await styleOf(span, ['background-color'], '::selection')).toEqual({
        'background-color': await probe(page, 'background-color: color-mix(in srgb, var(--app-accent) 35%, transparent)', 'background-color'),
      })
      // The end marker closes the layer below the text and is the one thing in it that is not selectable.
      expect(await styleOf(layer.locator('.pdf-text-end'), ['display', 'position', 'user-select'])).toEqual({
        display: 'block', position: 'absolute', 'user-select': 'none',
      })
    }
    await page.emulateMedia({ colorScheme: 'light' })
  },
)
