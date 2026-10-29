// happy-dom loads no image: an `<img>` given a source fires neither `load` nor `error`, so the deriver's size probe, for a
// file whose header it does not read (`shared/image/mediaDeriver.ts` `imageSizeFromElement`, inbox #558), would wait
// forever where a browser answers, and an
// upload whose tiers could not be derived waits on that probe. A suite that uploads through the real deriver stubs
// `Image` with this (`vi.stubGlobal('Image', UnreadableImage)`): a browser that cannot read the file, which answers
// `error` a task later — what such a suite got from `createImageBitmap`'s absence before the probe read the header.
export class UnreadableImage {
  naturalWidth = 0
  naturalHeight = 0
  onload: (() => void) | null = null
  onerror: (() => void) | null = null

  set src(_url: string) {
    setTimeout(() => this.onerror?.(), 0)
  }

  removeAttribute(): void {}
}
