// Save decrypted bytes to the user's device (cr-0049). Platform code: the Memories gallery and the Emergency Kit
// download both write a file this way, and neither may import the other's feature.

/** Save decrypted bytes to the user's device via a Blob object URL + a synthetic `<a download>` (then revoke). */
export function saveBytes(bytes: Uint8Array, filename: string): void {
  saveBlob(new Blob([bytes as BlobPart]), filename)
}

/** Save a `Blob` the same way — for content assembled from parts (a file decrypted chunk by chunk), so it is never
 *  copied into one `Uint8Array` first. */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  try {
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}
