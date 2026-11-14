// @vitest-environment happy-dom
// A moderator's review of a reported medium (uc.moderation.review-a-case A5, ADR 0135 decision 10): a photo is drawn from
// its preview tier; a music track is heard - its original opened once with its own `K_blob` and played by an
// `<audio controls>` over a `blob:` of it, the bytes the download then saves without a second read.
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../api', () => ({ caseMediaApi: vi.fn() }))
vi.mock('../../../core/blob/transport', async (orig) => ({ ...(await orig<object>()), openBlobWithKey: vi.fn() }))

import { mount, flushPromises } from '@vue/test-utils'
import * as v1 from '@veyq/crypto/v1'
import { b64std } from '@veyq/crypto/base64'
import MediumReview from './MediumReview.vue'
import { caseMediaApi } from '../api'
import { openBlobWithKey } from '../../../core/blob/transport'
import { beginReview } from '../../../core/sharing/review'
import { realI18n } from '../../../test/i18n'
import { fakeApi } from '../../../test/apiMock'

const ORIGINAL_KEY = v1.randomBytes(32)
const PREVIEW_KEY = v1.randomBytes(32)
const meta = (type: string, name: string) => b64std(v1.sealBlobMeta(ORIGINAL_KEY, { type, name }))
const keys = [
  { role: 'BLOB', ref: 'b-orig', key: ORIGINAL_KEY },
  { role: 'BLOB', ref: 'b-prev', key: PREVIEW_KEY },
]

function listing(type: string, name: string) {
  const medium = { mediaId: 'm1', blobId: 'b-orig', wrappedBlobMeta: meta(type, name), derivatives: type.startsWith('image/') ? [{ kind: 'PREVIEW', blobId: 'b-prev' }] : [] }
  fakeApi(caseMediaApi, { listCaseMedia: async () => ({ media: [medium] }) as unknown as Awaited<ReturnType<ReturnType<typeof caseMediaApi>['listCaseMedia']>> })
}

let end: () => void
beforeEach(() => {
  vi.clearAllMocks()
  end?.()
  end = beginReview({ caseId: 'c1', chunkUrls: async () => [], proof: async () => ({ xVeyqModerationChallenge: 'c', xVeyqModerationSignature: 's' }) })
  vi.mocked(openBlobWithKey).mockImplementation(async (key) => new Uint8Array(key === ORIGINAL_KEY ? [1, 2, 3] : [9]))
})

const mountReview = () => mount(MediumReview, { props: { caseId: 'c1', itemId: 'm1', scopeId: 's1', keys }, global: { plugins: [realI18n()] } })

describe('MediumReview', () => {
  it('a reported music track can be heard: its original, opened with its own key, plays in an <audio controls>', async () => {
    listing('audio/mpeg', 'Summer Song.mp3')
    const w = mountReview()
    await flushPromises()
    const audio = w.find('[data-testid="medium-review-audio"]')
    expect(audio.exists()).toBe(true)
    expect(audio.attributes('controls')).toBeDefined()
    expect(audio.attributes('src')).toMatch(/^blob:/)
    expect(vi.mocked(openBlobWithKey).mock.calls.map(([key]) => key)).toEqual([ORIGINAL_KEY])
    expect(w.find('img').exists()).toBe(false)
    expect(w.text()).toContain('Summer Song.mp3')
  })

  it('the download saves the bytes already opened for the player, without a second read', async () => {
    listing('audio/mpeg', 'Summer Song.mp3')
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const w = mountReview()
    await flushPromises()
    await w.find('.medium-review-download').trigger('click')
    await flushPromises()
    expect(click).toHaveBeenCalledTimes(1)
    expect(openBlobWithKey).toHaveBeenCalledTimes(1)
  })

  it('a photo is drawn from its preview tier, with no player and the original unread', async () => {
    listing('image/jpeg', 'IMG_1.jpg')
    const w = mountReview()
    await flushPromises()
    expect(w.find('img').attributes('src')).toMatch(/^blob:/)
    expect(w.find('[data-testid="medium-review-audio"]').exists()).toBe(false)
    expect(vi.mocked(openBlobWithKey).mock.calls.map(([key]) => key)).toEqual([PREVIEW_KEY])
  })
})
