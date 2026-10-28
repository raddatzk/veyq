// One sealed chunk frame `PUT` to its presigned URL — the one spelling both places that send a frame use: core's frame
// worker (`frames.worker.ts`, which reads, seals and sends each chunk off the page's main thread, inbox #220) and the
// page thread's fall-back in the blob transport (`transport.ts`). Its own module because the worker must not import the
// transport, which pulls in the app's API clients. It holds no key and sees only ciphertext.

/** The one `Content-Type` a presigned PUT signs, so the store refuses any other (ADR 0013 §6). The uploader no
 *  longer chooses the stored type: whatever the store later serves is never renderable. */
export const PUT_CONTENT_TYPE = 'application/octet-stream'

/**
 * `PUT` one sealed frame and resolve to the store's status; reject only where the request failed in the network or was
 * aborted. The store's answer is empty (`200`, `Content-Length: 0`) and nothing here needs it — but Chromium cancels the
 * load of an empty body nobody reads and reports the PUT `net::ERR_ABORTED` although the store took the chunk: 1,163 of
 * 1,176 PUTs of a 50-photo upload in the V1 heavy stage. So it is read to its end; a failure to read an empty body says
 * nothing about the PUT, whose status is already known.
 *
 * The frame worker hands the frame as a `Blob`: in Chrome a `PUT` of a 256 KiB byte array cost its thread 6.2–6.7 ms,
 * the same bytes as a `Blob` 0.85–0.89 ms, the `Blob`'s making included (400 PUTs, four in flight, in a worker, inbox
 * #220). The page thread's fall-back sends the bytes as they are; it runs only where no Worker does.
 */
export async function putFrame(url: string, frame: Uint8Array | Blob, signal?: AbortSignal): Promise<number> {
  const res = await fetch(url, { method: 'PUT', headers: { 'Content-Type': PUT_CONTENT_TYPE }, body: frame as BodyInit, signal })
  await res.arrayBuffer().catch(() => undefined)
  return res.status
}
