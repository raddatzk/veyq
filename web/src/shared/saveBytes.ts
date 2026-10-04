// Save decrypted bytes to the user's device (cr-0049). Platform code: the Memories gallery and the Emergency Kit
// download both write a file this way, and neither may import the other's feature.

/** Save decrypted bytes to the user's device via a Blob object URL + a synthetic `<a download>` (then revoke). */
export function saveBytes(bytes: Uint8Array, filename: string): void {
  const url = URL.createObjectURL(new Blob([bytes as BlobPart]))
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
