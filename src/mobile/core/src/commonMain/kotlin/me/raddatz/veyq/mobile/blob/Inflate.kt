package me.raddatz.veyq.mobile.blob

/**
 * A zlib stream ([RFC 1950](https://www.rfc-editor.org/rfc/rfc1950), what a PDF's `/FlateDecode` holds) inflated by the
 * platform's own zlib: `java.util.zip.Inflater` on the JVM and Android, `platform.zlib` on every native target. Null when
 * the stream is broken or truncated, and as soon as it would inflate past [cap] bytes — a bomb costs at most the cap.
 */
expect fun inflate(bytes: ByteArray, cap: Int): ByteArray?
