// @vitest-environment happy-dom
// uc.memory.upload (web, cr-0058) — the TopBar uploads tray renders the live queue: an active batch shows its
// file, status and badge, and the cancel control aborts it. Core's since cr-files-application B3: it is driven here by
// plain queue jobs, no application involved, and it lists a batch of any application (the case at the end) — what a
// Memories batch looks like through the real top bar is `AppTopbar.test.ts`'s.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import UploadStatusMenu from './UploadStatusMenu.vue'
import { enqueue, clearUploads, useUploadQueue, type UploadJob } from './queue'
import { realI18n, t } from '../../test/i18n'

const mountTray = () => mount(UploadStatusMenu, { global: { plugins: [realI18n()] } })

/** A job that reports its first progress (→ the item flips preparing → uploading) and then stays pending until aborted,
 *  so it sits in 'uploading' for the assertions. */
const pendingJob = (name = 'photo.png'): UploadJob => ({
  name,
  sizeBytes: 3,
  run: ({ onProgress, signal }) =>
    new Promise((_resolve, reject) => {
      onProgress(0)
      const abort = () => reject(new DOMException('cancelled', 'AbortError'))
      if (signal.aborted) return abort()
      signal.addEventListener('abort', abort)
    }),
})
/** A job that finishes at once. */
const doneJob = (name: string): UploadJob => ({
  name,
  sizeBytes: 3,
  run: async ({ onProgress }) => onProgress(1),
})
/** A job whose progress the test reports, and that stays in flight until the test aborts it. */
function steeredJob(name: string): { job: UploadJob; report(fraction: number): void } {
  let onProgress: (fraction: number) => void = () => {}
  const job: UploadJob = { ...pendingJob(name), run: (ctx) => ((onProgress = ctx.onProgress), pendingJob(name).run(ctx)) }
  return { job, report: (fraction) => onProgress(fraction) }
}
/** What the open tray draws: per list its title, its files and its bar's value, and whether it offers Close. */
const listsOf = (w: VueWrapper) =>
  w.findAll('.uploads-list').map((l) => ({
    title: l.find('.uploads-list-head strong').text(),
    files: l.findAll('.uploads-file-name').map((n) => n.text()),
    bar: l.find('.uploads-bar').attributes('aria-valuenow'),
    close: l.find('.uploads-dismiss').exists(),
  }))
async function openTray() {
  const w = mountTray()
  await flushPromises()
  await w.find('.uploads-trigger').trigger('click')
  return w
}

/** A job that fails every attempt with `code`. */
const failingJob = (code: string, name = 'photo.png'): UploadJob => ({
  name,
  sizeBytes: 3,
  run: () => Promise.reject(new Error(code)),
})

beforeEach(() => {
  clearUploads()
})

describe('UploadStatusMenu', () => {
  it('badges the active count and lists the uploading file when opened', async () => {
    enqueue([pendingJob()], { label: '1 photo' })
    const w = mountTray()
    await flushPromises()

    expect(w.find('.uploads-badge').text()).toBe('1')
    await w.find('.uploads-trigger').trigger('click')
    expect(w.text()).toContain('photo.png')
    expect(w.text()).toContain(t('upload.status.uploading'))
  })

  it('says whether its panel is open (aria-expanded) and which panel it opens (inbox #385)', async () => {
    enqueue([pendingJob()], { label: '1 photo' })
    const w = mountTray()
    await flushPromises()
    const trigger = w.find('.uploads-trigger')
    expect(trigger.attributes('aria-expanded')).toBe('false')
    await trigger.trigger('click')
    expect(trigger.attributes('aria-expanded')).toBe('true')
    expect(w.find(`#${trigger.attributes('aria-controls')}`).classes()).toContain('uploads-panel')
    await trigger.trigger('click')
    expect(trigger.attributes('aria-expanded')).toBe('false')
  })

  it('cancels the in-flight file from the tray', async () => {
    enqueue([pendingJob()], { label: '1 photo' })
    const w = mountTray()
    await flushPromises()
    await w.find('.uploads-trigger').trigger('click')

    await w.find('.uploads-file-action').trigger('click') // the cancel control
    await flushPromises()

    expect(w.text()).toContain(t('upload.status.cancelled'))
    expect(w.find('.uploads-badge').exists()).toBe(false) // no longer active
  })

  // uc.admin.tenant-storage-quota / ADR 0073 §6: a file refused for lack of room says so, and offers no retry.
  it('shows the no-space reason and no retry on a quota_exceeded failure', async () => {
    enqueue([failingJob('quota_exceeded')], { label: '1 photo' })
    const w = mountTray()
    await flushPromises()
    await w.find('.uploads-trigger').trigger('click')

    expect(w.text()).toContain(t('upload.status.failed'))
    expect(w.find('.uploads-file-reason').text()).toBe(t('upload.noSpace'))
    expect(w.find('.uploads-file-action').exists()).toBe(false)
  })

  it('control: any other failure offers the retry and gives no no-space reason', async () => {
    enqueue([failingJob('chunk_put_failed:500')], { label: '1 photo' })
    const w = mountTray()
    await flushPromises()
    await w.find('.uploads-trigger').trigger('click')

    expect(w.text()).toContain(t('upload.status.failed'))
    expect(w.find('.uploads-file-reason').exists()).toBe(false)
    expect(w.find('.uploads-file-action').attributes('aria-label')).toBe(t('upload.retry'))
  })

  // A job's own final refusal (Depot: slug_taken, illegal_structure, stale_file) is not retryable either: the tray
  // offers no retry control for it, and the job ran once (no automatic second attempt). Its control is the same code
  // on a job that declares nothing final — that one still offers the retry and the click re-runs it.
  it('offers no retry for a refusal its job declared final', async () => {
    const run = vi.fn(() => Promise.reject(new Error('slug_taken')))
    enqueue([{ name: 'a.txt', sizeBytes: 1, run, isFinal: (e) => e === 'slug_taken' }], { label: '1 file' })
    const w = mountTray()
    await flushPromises()
    await w.find('.uploads-trigger').trigger('click')

    expect(w.text()).toContain(t('upload.status.failed'))
    expect(w.find('.uploads-file-action').exists()).toBe(false)
    expect(run).toHaveBeenCalledTimes(1)
  })

  it('control: the same code from a job that declares nothing final offers the retry, and its click runs the job again', async () => {
    const run = vi.fn(() => Promise.reject(new Error('slug_taken')))
    enqueue([{ name: 'a.txt', sizeBytes: 1, run }], { label: '1 file' })
    const w = mountTray()
    await flushPromises()
    await w.find('.uploads-trigger').trigger('click')
    expect(run).toHaveBeenCalledTimes(2) // the automatic retry
    await w.find('.uploads-file-action').trigger('click')
    await flushPromises()
    expect(run).toHaveBeenCalledTimes(4)
  })

  // The menu is the platform's: a batch enqueued by an application that is not Memories — no `target`, no `file`, its
  // own `extra` — is listed exactly like a Memories one, under its own label, beside the others in enqueue order.
  it('lists a batch of any application, in enqueue order, by its label and item names alone', async () => {
    enqueue([pendingJob('IMG_1.jpg')], { label: '1 photo', extra: { target: { nodeId: 'm1' } } })
    enqueue([pendingJob('report.pdf'), pendingJob('notes.txt')], { label: '2 files', extra: { folder: 'f1' } })
    const w = mountTray()
    await flushPromises()

    expect(w.find('.uploads-badge').text()).toBe('3') // every application's active items count
    await w.find('.uploads-trigger').trigger('click')
    const batches = w.findAll('.uploads-list')
    expect(batches.map((b) => b.find('.uploads-list-head strong').text())).toEqual(['1 photo', '2 files'])
    expect(batches[1].findAll('.uploads-file-name').map((n) => n.text())).toEqual(['report.pdf', 'notes.txt'])
    // one job at a time across batches: the first batch's file is in flight, the second batch waits behind it
    expect(batches[0].find('.uploads-file-status').text()).toBe(t('upload.status.uploading'))
    expect(batches[1].findAll('.uploads-file-status').map((n) => n.text())).toEqual([
      t('upload.status.queued'),
      t('upload.status.queued'),
    ])
  })

  // One list per destination (uc.memory.upload step 4): the tray groups the OPEN batches by their `key`; the queue keeps
  // its batches as they are.
  describe('one list per destination', () => {
    it('draws two batches with one key as one list, under the first label, with one bar over all their files', async () => {
      enqueue([doneJob('a.jpg')], { label: 'Rome', key: 'm1' })
      const b = steeredJob('b.jpg')
      enqueue([b.job], { label: 'Rome again', key: 'm1' })
      const w = await openTray()
      b.report(0.5)
      await nextTick()

      // (1 for the done file + 0.5) / 2 files — the per-batch formula over the whole list
      expect(listsOf(w)).toEqual([{ title: 'Rome', files: ['a.jpg', 'b.jpg'], bar: '75', close: false }])
    })

    it('a batch enqueued later for that destination joins the open list, and the bar goes down', async () => {
      enqueue([doneJob('a.jpg')], { label: 'Rome', key: 'm1' })
      const w = await openTray()
      expect(listsOf(w)).toEqual([{ title: 'Rome', files: ['a.jpg'], bar: '100', close: true }])

      enqueue([pendingJob('b.jpg')], { label: 'Rome', key: 'm1' })
      await flushPromises()

      // joined, not a second list; no Close while one of its batches still runs
      expect(listsOf(w)).toEqual([{ title: 'Rome', files: ['a.jpg', 'b.jpg'], bar: '50', close: false }])
    })

    it('a closed list stays closed: a later batch for its destination starts a new list', async () => {
      enqueue([doneJob('a.jpg')], { label: 'Rome', key: 'm1' })
      const w = await openTray()
      await w.find('.uploads-dismiss').trigger('click')
      expect(listsOf(w)).toEqual([])

      enqueue([pendingJob('b.jpg')], { label: 'Rome', key: 'm1' })
      await flushPromises()

      expect(listsOf(w)).toEqual([{ title: 'Rome', files: ['b.jpg'], bar: '0', close: false }])
    })

    it('two destinations are two lists, each at its first batch\'s place; the header still counts files', async () => {
      enqueue([pendingJob('a.jpg')], { label: 'Rome', key: 'm1' })
      enqueue([pendingJob('b.jpg')], { label: 'Paris', key: 'm2' })
      enqueue([pendingJob('c.jpg')], { label: 'Rome', key: 'm1' })
      const w = await openTray()

      expect(listsOf(w).map((l) => [l.title, l.files])).toEqual([
        ['Rome', ['a.jpg', 'c.jpg']],
        ['Paris', ['b.jpg']],
      ])
      expect(w.find('.uploads-badge').text()).toBe('3')
      expect(w.find('.uploads-header p').text()).toBe(t('upload.active', { count: 3 }))
    })

    it('a batch without a key is a list of its own, beside one with the same label', async () => {
      enqueue([pendingJob('a.jpg')], { label: 'Cover' })
      enqueue([pendingJob('b.jpg')], { label: 'Cover' })
      const w = await openTray()

      expect(listsOf(w).map((l) => l.files)).toEqual([['a.jpg'], ['b.jpg']])
    })

    it('Close acknowledges every batch of its list, and no other', async () => {
      enqueue([doneJob('a.jpg')], { label: 'Rome', key: 'm1' })
      enqueue([doneJob('b.jpg')], { label: 'Rome', key: 'm1' })
      enqueue([doneJob('c.jpg')], { label: 'Paris', key: 'm2' })
      const w = await openTray()
      expect(listsOf(w).map((l) => [l.title, l.close])).toEqual([
        ['Rome', true],
        ['Paris', true],
      ])

      await w.find('.uploads-dismiss').trigger('click') // Rome's one Close
      expect(useUploadQueue().batches.value.map((b) => b.files[0].name)).toEqual(['c.jpg'])
      expect(listsOf(w).map((l) => l.title)).toEqual(['Paris'])
    })
  })
})
