@file:OptIn(ExperimentalJsExport::class)

package me.raddatz.veyq.mobile.expenses

import me.raddatz.veyq.mobile.json.JsonText
import me.raddatz.veyq.mobile.json.jsonObject
import me.raddatz.veyq.mobile.json.jsonString
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

/**
 * The format's reader of plaintext bytes on JavaScript — `TextDecoder`-free UTF-8, a byte order mark dropped, `JSON.parse` —
 * for `@veyq/core-js`'s check of the vectors' `decode` section, which no façade call reaches on its own (the codec decodes
 * only what it opened). In the conformance binary, never the shipped module: `{"body": …}` or `{"path": …}` as JSON text.
 */
@JsExport
fun decodeRevisionPlaintext(plaintext: Uint8Array): String {
    val bytes = Int8Array(plaintext.buffer, plaintext.byteOffset, plaintext.length).unsafeCast<ByteArray>()
    val answer = try {
        jsonObject("body" to RevisionFormat.toJson(RevisionFormat.decode(bytes)))
    } catch (e: RevisionFormatInvalid) {
        jsonObject("path" to jsonString(e.path))
    }
    return JsonText.stringify(answer)
}
