// @vitest-environment happy-dom
// The drop-box guest's page shows what became of every file it picked (inbox #381, found in a real browser,
// 2026-10-08): after a revoke the uploads 404ed and the page showed nothing — it listed only finished files and has no
// upload menu, so the guest believed the files were sent. A failed file is listed with its reason; a gone link (any
// 404, a gone code — also from the store's start or the completion, not only from the create) turns the page into
// the "no longer takes files" state with no picker, and stops the rest of the batch without a request. A store's own
// refusal (403) is a failed file with a retry, not a gone link. The real upload queue runs; the guest's job is a stub
// whose `run` fails as the wire would.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'

vi.mock('../dropBox', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../dropBox')>()),
  loadDropGuest: vi.fn(),
  nameDropGuest: vi.fn(),
  saveDropGuest: vi.fn(),
  ensureDropFolder: vi.fn(),
  dropGuestContext: vi.fn(),
}))
vi.mock('../shareUpload', () => ({ guestFileUploadJob: vi.fn() }))

import DropBoxShareBody from './DropBoxShareBody.vue'
import { dropGuestContext, ensureDropFolder, loadDropGuest, type DropGuestSession } from '../dropBox'
import { guestFileUploadJob } from '../shareUpload'
import type { FileUploadResult } from '../uploadFile'
import { clearUploads } from '../../../core/blob/queue'
import { GuestTreeError, type GuestTreeContext } from '../../../core/sharing/guestTree'
import type { PublicShareHead } from '../../../core/sharing/resolvePublicShare'
import { realI18n } from '../../../test/i18n'
import { formatBytes } from '../../../shared/formatBytes'

const { t } = realI18n().global
const SHARE = 'Dr0pIdDr0pIdDr0pIdDr0pIdDr0pIdDr0pIdDr0p-_1'
const HEAD = { shareId: SHARE, scopeId: 'scope', name: 'Steuer 2026' } as PublicShareHead
const SESSION: DropGuestSession = { identityId: 'id-1', contributorToken: 'tok-1', identityKey: 'a2V5' }
const FOLDER = { nodeId: 'folder-1', key: v1.randomBytes(32), epoch: 0, placementVersion: 0, created: true }

/** A 404 as the generated client throws it: a `response` with the server's `ErrorResponse`. */
const http = (status: number, code: string) =>
  Object.assign(new Error('Response returned an error code'), { response: new Response(JSON.stringify({ code }), { status }) })

/** Each file's `run`: what it does is the test's (`outcome`), counted per file name. */
const runs = new Map<string, number>()
let outcome: (name: string, attempt: number) => Promise<FileUploadResult>
function fakeJob(file: File) {
  return {
    name: file.name,
    sizeBytes: file.size,
    run: async () => {
      const n = (runs.get(file.name) ?? 0) + 1
      runs.set(file.name, n)
      return outcome(file.name, n)
    },
    extra: { file },
  }
}

beforeEach(() => {
  clearUploads()
  runs.clear()
  outcome = async () => ({ nodeId: 'n', metaWritten: true })
  vi.mocked(loadDropGuest).mockReset().mockReturnValue(SESSION)
  vi.mocked(ensureDropFolder).mockReset().mockResolvedValue(FOLDER)
  vi.mocked(dropGuestContext).mockReset().mockReturnValue({} as GuestTreeContext)
  vi.mocked(guestFileUploadJob).mockReset().mockImplementation((_t, file) => fakeJob(file))
})

function mountPage() {
  return mount(DropBoxShareBody, {
    props: { shareId: SHARE, fragmentKey: 'frag', pwToken: undefined, head: HEAD, reload: async () => {} },
    global: { plugins: [realI18n()] },
  })
}
type Page = ReturnType<typeof mountPage>
async function pick(w: Page, ...names: string[]) {
  const input = w.find('input.dropbox-picker')
  expect(input.exists(), 'the picker is offered').toBe(true)
  Object.defineProperty(input.element, 'files', { value: names.map((n) => new File(['x'.repeat(2048)], n)), configurable: true })
  await input.trigger('change')
  await flushPromises()
}
const rows = (w: Page) =>
  w.findAll('.dropbox-guest-row').map((r) => ({
    name: r.find('.veyq-row-title').text(),
    title: r.find('.veyq-row-title').attributes('title'),
    status: r.attributes('data-status'),
    detail: r.find('.veyq-row-subtitle').text(),
    retry: r.find('button').exists(),
  }))
/** Until every row has settled (the real queue runs its jobs one after the other). */
const settled = (w: Page, count: number) =>
  vi.waitFor(() => {
    const r = rows(w)
    expect(r.length).toBe(count)
    expect(r.every((x) => x.status === 'done' || x.status === 'failed')).toBe(true)
  })

describe('DropBoxShareBody: what became of every file (inbox #381)', () => {
  it('a sent file is listed inline with its size; a long name is cut with the whole name as its tooltip', async () => {
    const long = `${'Steuererklärung-Anlage-'.repeat(6)}.pdf`
    const w = mountPage()
    await pick(w, long)
    await settled(w, 1)
    expect(rows(w)).toEqual([{ name: long, title: long, status: 'done', detail: t('files.dropBox.guestDone', { size: formatBytes(2048, 'en') }), retry: false }])
    const row = w.find('.dropbox-guest-row')
    expect(row.element.firstElementChild?.className, 'the check mark is the row\'s first item, before the name').toContain('pi-check')
  })

  it('a revoke (the create\'s 404 share_not_found) lists the file as not sent and the page as gone — no picker, no retry', async () => {
    outcome = async () => {
      throw new GuestTreeError('share_not_found')
    }
    const w = mountPage()
    await pick(w, 'a.pdf', 'b.pdf')
    await settled(w, 2)
    expect(rows(w).map((r) => [r.name, r.status, r.detail, r.retry])).toEqual([
      ['a.pdf', 'failed', t('files.dropBox.guestErrorGone'), false],
      ['b.pdf', 'failed', t('files.dropBox.guestErrorGone'), false],
    ])
    expect(w.text()).toContain(t('files.dropBox.guestGone'))
    expect(w.find('input.dropbox-picker').exists(), 'nothing more to pick').toBe(false)
    // The first 404 ends the link for this page: neither the queue's automatic retry nor the second file asks again.
    expect(Object.fromEntries(runs)).toEqual({ 'a.pdf': 1 })
  })

  it('a 404 from a later door (the completion\'s upload_not_found) is a gone link too', async () => {
    outcome = async () => {
      throw http(404, 'upload_not_found')
    }
    const w = mountPage()
    await pick(w, 'a.pdf')
    await settled(w, 1)
    expect(rows(w)[0].detail).toBe(t('files.dropBox.guestErrorGone'))
    expect(w.text()).toContain(t('files.dropBox.guestGone'))
  })

  it('the guest\'s folder trashed (403 share_not_contributable) is a gone link for this guest', async () => {
    outcome = async () => {
      throw http(403, 'share_not_contributable')
    }
    const w = mountPage()
    await pick(w, 'a.pdf')
    await settled(w, 1)
    expect(w.text()).toContain(t('files.dropBox.guestGone'))
  })

  it('a store\'s 403 is a failed file with a retry, not a gone link; the retry sends it again', async () => {
    outcome = async (_name, attempt) => {
      if (attempt <= 2) throw new Error('chunk_put_failed:403')
      return { nodeId: 'n', metaWritten: true }
    }
    const w = mountPage()
    await pick(w, 'a.pdf')
    await settled(w, 1)
    expect(rows(w)).toEqual([expect.objectContaining({ status: 'failed', detail: t('files.dropBox.guestErrorFailed'), retry: true })])
    expect(w.text()).not.toContain(t('files.dropBox.guestGone'))
    expect(w.find('input.dropbox-picker').exists(), 'the guest can still pick').toBe(true)
    await w.find('.dropbox-guest-row button').trigger('click')
    await vi.waitFor(() => expect(rows(w)[0].status).toBe('done'))
  })

  it('a name this guest already sent is said so, with no retry', async () => {
    outcome = async () => {
      throw new Error('slug_taken')
    }
    const w = mountPage()
    await pick(w, 'a.pdf')
    await settled(w, 1)
    expect(rows(w)).toEqual([expect.objectContaining({ status: 'failed', detail: t('files.dropBox.guestErrorNameTaken'), retry: false })])
  })

  it('a gone link at the folder call (before any job) shows the gone state, not a generic error', async () => {
    vi.mocked(ensureDropFolder).mockRejectedValue(http(404, 'share_not_found'))
    const w = mountPage()
    await pick(w, 'a.pdf')
    expect(w.text()).toContain(t('files.dropBox.guestGone'))
    expect(w.text()).not.toContain(t('files.dropBox.guestFailed'))
    expect(w.find('input.dropbox-picker').exists()).toBe(false)
    expect(guestFileUploadJob).not.toHaveBeenCalled()
  })

  it('any other failure at the folder call is a generic error, and the picker stays', async () => {
    vi.mocked(ensureDropFolder).mockRejectedValue(new TypeError('Failed to fetch'))
    const w = mountPage()
    await pick(w, 'a.pdf')
    expect(w.text()).toContain(t('files.dropBox.guestFailed'))
    expect(w.text()).not.toContain(t('files.dropBox.guestGone'))
    expect(w.find('input.dropbox-picker').exists()).toBe(true)
  })
})
