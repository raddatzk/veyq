// Ported to Kotlin from ZXing's qrcode/decoder/DecodedBitStreamParser.java, common/StringUtils.java (guessCharset) and
// common/CharacterSetECI.java (Copyright 2007-2008 ZXing authors, Apache License 2.0; NOTICE beside this file names
// the upstream commit). Changed: a segment in a character set common Kotlin has no decoder for (Shift_JIS, GB2312, a
// legacy ECI) is kept as its bytes and named, not decoded (QrContent); no hints; a failure is a QrReadException.
package me.raddatz.veyq.mobile.qr

/** A decoded code's content, segment by segment in the order the code holds them. */
internal class QrContent(val pieces: List<Piece>, val errorsCorrected: Int = 0) {
    sealed interface Piece {
        /** A segment read as text: numeric, alphanumeric, or bytes in UTF-8, ISO 8859-1 or ASCII. */
        data class Text(val text: String) : Piece

        /** A segment in a character set the core does not decode, as its bytes and the set's IANA name. */
        class Encoded(val bytes: ByteArray, val charset: String) : Piece
    }

    /** The text, or null when a segment is in a character set the core does not decode (no veyq code is). */
    val text: String? get() = if (pieces.all { it is Piece.Text }) pieces.joinToString("") { (it as Piece.Text).text } else null
}

/** Reads a code's data codewords (corrected, in order) into its content: ISO 18004:2006 6.4.3 onwards. */
internal object BitStreamParser {
    private const val ALPHANUMERIC_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
    private const val GB2312_SUBSET = 1

    fun decode(bytes: ByteArray, version: Version, errorsCorrected: Int): QrContent {
        val bits = BitSource(bytes)
        val pieces = ArrayList<QrContent.Piece>()
        var currentCharset: String? = null
        var fc1InEffect = false
        do {
            // While still another segment to read...
            val mode = if (bits.available() < 4) Mode.TERMINATOR else Mode.forBits(bits.readBits(4)) // mode is encoded by 4 bits
            when (mode) {
                Mode.TERMINATOR -> {}
                Mode.FNC1_FIRST_POSITION, Mode.FNC1_SECOND_POSITION -> fc1InEffect = true
                Mode.STRUCTURED_APPEND -> {
                    if (bits.available() < 16) throw QrReadException("structured append past the end")
                    // sequence number and parity are added later to the result metadata; read and ignore
                    bits.readBits(8)
                    bits.readBits(8)
                }
                Mode.ECI -> currentCharset = eciCharset(parseECIValue(bits)) ?: throw QrReadException("unknown ECI")
                Mode.HANZI -> {
                    // First handle Hanzi mode which does not start with character count; chinese mode contains a sub
                    // set indicator right after mode indicator
                    val subset = bits.readBits(4)
                    val countHanzi = bits.readBits(mode.characterCountBits(version))
                    if (subset == GB2312_SUBSET) pieces += decodeHanziSegment(bits, countHanzi)
                }
                else -> {
                    // "Normal" QR code modes: How many characters will follow, encoded in this mode?
                    val count = bits.readBits(mode.characterCountBits(version))
                    pieces += when (mode) {
                        Mode.NUMERIC -> QrContent.Piece.Text(decodeNumericSegment(bits, count))
                        Mode.ALPHANUMERIC -> QrContent.Piece.Text(decodeAlphanumericSegment(bits, count, fc1InEffect))
                        Mode.BYTE -> decodeByteSegment(bits, count, currentCharset)
                        Mode.KANJI -> decodeKanjiSegment(bits, count)
                        else -> throw QrReadException("mode $mode")
                    }
                }
            }
        } while (mode != Mode.TERMINATOR)
        return QrContent(pieces, errorsCorrected)
    }

    /** See specification GBT 18284-2000: the 13-bit values become GB2312 byte pairs. */
    private fun decodeHanziSegment(bits: BitSource, countIn: Int): QrContent.Piece {
        var count = countIn
        // Don't crash trying to read more bits than we have available.
        if (count * 13 > bits.available()) throw QrReadException("hanzi past the end")
        // Each character will require 2 bytes. Read the characters as 2-byte pairs and decode as GB2312 afterwards
        val buffer = ByteArray(2 * count)
        var offset = 0
        while (count > 0) {
            // Each 13 bits encodes a 2-byte character
            val twoBytes = bits.readBits(13)
            var assembledTwoBytes = ((twoBytes / 0x060) shl 8) or (twoBytes % 0x060)
            assembledTwoBytes += if (assembledTwoBytes < 0x00A00) 0x0A1A1 else 0x0A6A1 // In the 0xA1A1 to 0xAAFE range / 0xB0A1 to 0xFAFE
            buffer[offset] = ((assembledTwoBytes shr 8) and 0xFF).toByte()
            buffer[offset + 1] = (assembledTwoBytes and 0xFF).toByte()
            offset += 2
            count--
        }
        return QrContent.Piece.Encoded(buffer, "GB2312")
    }

    /** Kanji mode: the 13-bit values become Shift_JIS byte pairs. */
    private fun decodeKanjiSegment(bits: BitSource, countIn: Int): QrContent.Piece {
        var count = countIn
        if (count * 13 > bits.available()) throw QrReadException("kanji past the end")
        // Each character will require 2 bytes. Read the characters as 2-byte pairs and decode as Shift_JIS afterwards
        val buffer = ByteArray(2 * count)
        var offset = 0
        while (count > 0) {
            // Each 13 bits encodes a 2-byte character
            val twoBytes = bits.readBits(13)
            var assembledTwoBytes = ((twoBytes / 0x0C0) shl 8) or (twoBytes % 0x0C0)
            assembledTwoBytes += if (assembledTwoBytes < 0x01F00) 0x08140 else 0x0C140 // In the 0x8140 to 0x9FFC range / 0xE040 to 0xEBBF
            buffer[offset] = (assembledTwoBytes shr 8).toByte()
            buffer[offset + 1] = assembledTwoBytes.toByte()
            offset += 2
            count--
        }
        return QrContent.Piece.Encoded(buffer, SHIFT_JIS)
    }

    private fun decodeByteSegment(bits: BitSource, count: Int, currentCharset: String?): QrContent.Piece {
        // Don't crash trying to read more bits than we have available.
        if (8 * count > bits.available()) throw QrReadException("bytes past the end")
        val readBytes = ByteArray(count) { bits.readBits(8).toByte() }
        // The spec isn't clear on this mode; see section 6.4.5: t does not say which encoding to assuming upon
        // decoding. I have seen ISO-8859-1 used as well as Shift_JIS -- without anything like an ECI designator to
        // give a hint.
        val encoding = currentCharset ?: guessCharset(readBytes)
        return when (encoding) {
            UTF_8 -> QrContent.Piece.Text(readBytes.decodeToString())
            ISO_8859_1 -> QrContent.Piece.Text(latin1(readBytes))
            US_ASCII -> QrContent.Piece.Text(buildString { for (b in readBytes) append(if (b >= 0) b.toInt().toChar() else '�') })
            else -> QrContent.Piece.Encoded(readBytes, encoding)
        }
    }

    private fun latin1(bytes: ByteArray): String = buildString(bytes.size) { for (b in bytes) append((b.toInt() and 0xFF).toChar()) }

    private fun toAlphaNumericChar(value: Int): Char {
        if (value >= ALPHANUMERIC_CHARS.length) throw QrReadException("alphanumeric $value")
        return ALPHANUMERIC_CHARS[value]
    }

    private fun decodeAlphanumericSegment(bits: BitSource, countIn: Int, fc1InEffect: Boolean): String {
        var count = countIn
        val result = StringBuilder()
        // Read two characters at a time
        while (count > 1) {
            if (bits.available() < 11) throw QrReadException("alphanumeric past the end")
            val nextTwoCharsBits = bits.readBits(11)
            result.append(toAlphaNumericChar(nextTwoCharsBits / 45))
            result.append(toAlphaNumericChar(nextTwoCharsBits % 45))
            count -= 2
        }
        if (count == 1) {
            // special case: one character left
            if (bits.available() < 6) throw QrReadException("alphanumeric past the end")
            result.append(toAlphaNumericChar(bits.readBits(6)))
        }
        // See section 6.4.8.1, 6.4.8.2
        if (fc1InEffect) {
            // We need to massage the result a bit if in an FNC1 mode:
            var i = 0
            while (i < result.length) {
                if (result[i] == '%') {
                    if (i < result.length - 1 && result[i + 1] == '%') {
                        // %% is rendered as %
                        result.deleteAt(i + 1)
                    } else {
                        // In alpha mode, % should be converted to FNC1 separator 0x1D
                        result[i] = 0x1D.toChar()
                    }
                }
                i++
            }
        }
        return result.toString()
    }

    private fun decodeNumericSegment(bits: BitSource, countIn: Int): String {
        var count = countIn
        val result = StringBuilder()
        // Read three digits at a time
        while (count >= 3) {
            // Each 10 bits encodes three digits
            if (bits.available() < 10) throw QrReadException("numeric past the end")
            val threeDigitsBits = bits.readBits(10)
            if (threeDigitsBits >= 1000) throw QrReadException("numeric $threeDigitsBits")
            result.append(toAlphaNumericChar(threeDigitsBits / 100))
            result.append(toAlphaNumericChar((threeDigitsBits / 10) % 10))
            result.append(toAlphaNumericChar(threeDigitsBits % 10))
            count -= 3
        }
        if (count == 2) {
            // Two digits left over to read, encoded in 7 bits
            if (bits.available() < 7) throw QrReadException("numeric past the end")
            val twoDigitsBits = bits.readBits(7)
            if (twoDigitsBits >= 100) throw QrReadException("numeric $twoDigitsBits")
            result.append(toAlphaNumericChar(twoDigitsBits / 10))
            result.append(toAlphaNumericChar(twoDigitsBits % 10))
        } else if (count == 1) {
            // One digit left over to read
            if (bits.available() < 4) throw QrReadException("numeric past the end")
            val digitBits = bits.readBits(4)
            if (digitBits >= 10) throw QrReadException("numeric $digitBits")
            result.append(toAlphaNumericChar(digitBits))
        }
        return result.toString()
    }

    private fun parseECIValue(bits: BitSource): Int {
        val firstByte = bits.readBits(8)
        if ((firstByte and 0x80) == 0) return firstByte and 0x7F // just one byte
        if ((firstByte and 0xC0) == 0x80) return ((firstByte and 0x3F) shl 8) or bits.readBits(8) // two bytes
        if ((firstByte and 0xE0) == 0xC0) return ((firstByte and 0x1F) shl 16) or bits.readBits(16) // three bytes
        throw QrReadException("ECI")
    }

    const val UTF_8 = "UTF-8"
    const val ISO_8859_1 = "ISO-8859-1"
    const val US_ASCII = "US-ASCII"
    const val SHIFT_JIS = "Shift_JIS"

    /** An ECI assignment value's character set, as ZXing's `CharacterSetECI` maps it; null for one it does not know. */
    private fun eciCharset(value: Int): String? = when (value) {
        0, 2 -> "IBM437"
        1, 3 -> ISO_8859_1
        in 4..13 -> "ISO-8859-${value - 2}"
        in 15..18 -> "ISO-8859-${value - 2}"
        20 -> SHIFT_JIS
        21 -> "windows-1250"
        22 -> "windows-1251"
        23 -> "windows-1252"
        24 -> "windows-1256"
        25 -> "UTF-16BE"
        26 -> UTF_8
        27, 170 -> US_ASCII
        28 -> "Big5"
        29 -> "GB18030"
        30 -> "EUC-KR"
        else -> null
    }

    /**
     * ZXing's `StringUtils.guessCharset` for a byte segment without an ECI: UTF-16 by its BOM, else whichever of UTF-8,
     * Shift_JIS and ISO-8859-1 the bytes can be, in that order of evidence. Where none fits, UTF-8 (upstream: the
     * platform's default).
     */
    fun guessCharset(bytes: ByteArray): String {
        // For now, merely tries to distinguish ISO-8859-1, UTF-8 and Shift_JIS, which should be by far the most common
        // encodings.
        if (bytes.size > 2 && ((bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) || (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()))) return "UTF-16"
        val length = bytes.size
        var canBeISO88591 = true
        var canBeShiftJIS = true
        var canBeUTF8 = true
        var utf8BytesLeft = 0
        var utf2BytesChars = 0
        var utf3BytesChars = 0
        var utf4BytesChars = 0
        var sjisBytesLeft = 0
        var sjisKatakanaChars = 0
        var sjisCurKatakanaWordLength = 0
        var sjisCurDoubleBytesWordLength = 0
        var sjisMaxKatakanaWordLength = 0
        var sjisMaxDoubleBytesWordLength = 0
        var isoHighOther = 0
        val utf8bom = bytes.size > 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()

        var i = 0
        while (i < length && (canBeISO88591 || canBeShiftJIS || canBeUTF8)) {
            val value = bytes[i].toInt() and 0xFF
            // UTF-8 stuff
            if (canBeUTF8) {
                if (utf8BytesLeft > 0) {
                    if ((value and 0x80) == 0) canBeUTF8 = false else utf8BytesLeft--
                } else if ((value and 0x80) != 0) {
                    if ((value and 0x40) == 0) {
                        canBeUTF8 = false
                    } else {
                        utf8BytesLeft++
                        if ((value and 0x20) == 0) {
                            utf2BytesChars++
                        } else {
                            utf8BytesLeft++
                            if ((value and 0x10) == 0) {
                                utf3BytesChars++
                            } else {
                                utf8BytesLeft++
                                if ((value and 0x08) == 0) utf4BytesChars++ else canBeUTF8 = false
                            }
                        }
                    }
                }
            }
            // ISO-8859-1 stuff
            if (canBeISO88591) {
                if (value > 0x7F && value < 0xA0) {
                    canBeISO88591 = false
                } else if (value > 0x9F && (value < 0xC0 || value == 0xD7 || value == 0xF7)) {
                    isoHighOther++
                }
            }
            // Shift_JIS stuff
            if (canBeShiftJIS) {
                if (sjisBytesLeft > 0) {
                    if (value < 0x40 || value == 0x7F || value > 0xFC) canBeShiftJIS = false else sjisBytesLeft--
                } else if (value == 0x80 || value == 0xA0 || value > 0xEF) {
                    canBeShiftJIS = false
                } else if (value > 0xA0 && value < 0xE0) {
                    sjisKatakanaChars++
                    sjisCurDoubleBytesWordLength = 0
                    sjisCurKatakanaWordLength++
                    if (sjisCurKatakanaWordLength > sjisMaxKatakanaWordLength) sjisMaxKatakanaWordLength = sjisCurKatakanaWordLength
                } else if (value > 0x7F) {
                    sjisBytesLeft++
                    sjisCurKatakanaWordLength = 0
                    sjisCurDoubleBytesWordLength++
                    if (sjisCurDoubleBytesWordLength > sjisMaxDoubleBytesWordLength) sjisMaxDoubleBytesWordLength = sjisCurDoubleBytesWordLength
                } else {
                    sjisCurKatakanaWordLength = 0
                    sjisCurDoubleBytesWordLength = 0
                }
            }
            i++
        }
        if (canBeUTF8 && utf8BytesLeft > 0) canBeUTF8 = false
        if (canBeShiftJIS && sjisBytesLeft > 0) canBeShiftJIS = false

        // Easy -- if there is BOM or at least 1 valid not-single byte character (and no evidence it can't be UTF-8), done
        if (canBeUTF8 && (utf8bom || utf2BytesChars + utf3BytesChars + utf4BytesChars > 0)) return UTF_8
        // Easy -- if assuming Shift_JIS or >= 3 valid consecutive not-ascii characters (and no evidence it can't be),
        // done
        if (canBeShiftJIS && (sjisMaxKatakanaWordLength >= 3 || sjisMaxDoubleBytesWordLength >= 3)) return SHIFT_JIS
        // Distinguishing Shift_JIS and ISO-8859-1 can be a little tough for short words. The crude heuristic is:
        // - If we saw
        //   - only two consecutive katakana chars in the whole text, or
        //   - at least 10% of bytes that could be "upper" not-alphanumeric Latin1,
        // - then we conclude Shift_JIS, else ISO-8859-1
        if (canBeISO88591 && canBeShiftJIS) {
            return if ((sjisMaxKatakanaWordLength == 2 && sjisKatakanaChars == 2) || isoHighOther * 10 >= length) SHIFT_JIS else ISO_8859_1
        }
        // Otherwise, try in order ISO-8859-1, Shift JIS, UTF-8 and fall back to default platform encoding
        if (canBeISO88591) return ISO_8859_1
        if (canBeShiftJIS) return SHIFT_JIS
        if (canBeUTF8) return UTF_8
        // Otherwise, we take a wild guess with platform encoding
        return UTF_8
    }
}
