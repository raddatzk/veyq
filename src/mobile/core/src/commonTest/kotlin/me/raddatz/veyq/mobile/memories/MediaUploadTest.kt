package me.raddatz.veyq.mobile.memories

import me.raddatz.veyq.mobile.crypto.BlobMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What an upload decides before any key: the type an original goes under, and the sealed meta of its original. */
class MediaUploadTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(at: Int, s: String, size: Int = 16) = ByteArray(size).also { s.encodeToByteArray().copyInto(it, at) }

    @Test
    fun theGivenTypeThenTheExtensionThenTheBytesDecide() {
        val jpegHead = bytes(0xFF, 0xD8, 0xFF, 0xE0)
        assertEquals("image/png", MediaUpload.typeOf("image/png", "x.jpg", jpegHead), "a given type wins")
        assertEquals("video/quicktime", MediaUpload.typeOf("", "IMG_1.MOV", jpegHead), "an empty type: the extension, any case")
        assertEquals("image/jpeg", MediaUpload.typeOf("application/octet-stream", "photo", jpegHead), "no extension: the bytes")
        assertEquals("application/octet-stream", MediaUpload.typeOf(null, null, ByteArray(16)))
    }

    @Test
    fun theBytesNameWhatTheWebsSnifferNames() {
        assertEquals("image/png", MediaUpload.sniff(bytes(0x89, 'P'.code, 'N'.code, 'G'.code)))
        assertEquals("image/heic", MediaUpload.sniff(ascii(4, "ftypheic")))
        assertEquals("video/quicktime", MediaUpload.sniff(ascii(4, "ftypqt  ")))
        assertEquals("video/mp4", MediaUpload.sniff(ascii(4, "ftypisom")))
        assertEquals("image/webp", MediaUpload.sniff(ascii(0, "RIFF").also { "WEBP".encodeToByteArray().copyInto(it, 8) }))
        assertEquals("video/webm", MediaUpload.sniff(bytes(0x1A, 0x45, 0xDF, 0xA3, 0, 0)))
        assertEquals(null, MediaUpload.sniff(ascii(0, "hello")))
    }

    @Test
    fun aPickedFileIsAMediumByItsTypeElseItsExtension() {
        assertTrue(MediaUpload.isMedia("image/heic", null))
        assertTrue(MediaUpload.isMedia(null, "clip.MKV"))
        assertFalse(MediaUpload.isMedia("application/pdf", "doc.pdf"))
    }

    @Test
    fun anOriginalsMetaCarriesItsDigestLastAndOnlyAValidOne() {
        val digest = "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU"
        val sealed = BlobMeta.encode("image/jpeg", "a.jpg", 5, digest).decodeToString().trimEnd()
        assertEquals("""{"v":1,"type":"image/jpeg","name":"a.jpg","modifiedAt":5,"sha256":"$digest"}""", sealed)
        assertEquals(256, BlobMeta.encode("image/jpeg", null, null, digest).size)
        assertFalse(BlobMeta.encode("image/jpeg", null, null, "$digest=").decodeToString().contains("sha256"), "padded: no digest")
        assertFalse(BlobMeta.encode("image/jpeg", null, null, digest.drop(1)).decodeToString().contains("sha256"), "short: no digest")
    }
}
