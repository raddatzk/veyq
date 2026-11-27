package me.raddatz.veyq.mobile.account

import kotlin.math.abs
import kotlin.math.round
import me.raddatz.veyq.mobile.crypto.EmergencyKit
import me.raddatz.veyq.mobile.crypto.Primitives
import me.raddatz.veyq.mobile.crypto.SecretKeyText
import me.raddatz.veyq.mobile.qr.QrEncoder
import me.raddatz.veyq.mobile.qr.QrMatrix

/**
 * The Emergency Kit as the apps write it (ADR 0148 decision 5): a one-page A4 PDF of the web's layout
 * (`src/web/src/features/account/emergencyKit/emergencyKit.ts` `renderKitPdf` — the same coordinates, sizes, colours,
 * sections 1-4 and footer, the words [KitWords] in the person's language), written by the core so no platform draws a
 * fifth layout or handles the Secret Key's text. The web's readers read it by each of their three paths (the token in
 * the bytes, the `Keywords`, the drawn page's QR) — `kit-app.pdf` in the web's kit fixtures is this writer's, held byte
 * for byte by `KitWriterTest`.
 *
 * Where it differs from the web's, on purpose:
 * - **The standard 14 fonts only** (Helvetica, Helvetica-Bold, Courier-Bold; [KitFont]), WinAnsiEncoding, nothing
 *   embedded. Wrapping is the web's `splitTextToSize` by words over the AFM widths, without kerning.
 * - **The QR is drawn as filled rectangles** (one path, one fill) from [QrEncoder.encode] — no image —, the code alone
 *   in the 118 pt white box, its quiet zone the box's 12 pt margin, as the web's `margin: 0` picture is placed.
 * - **No logo:** the web's PNG brand mark is left out and nothing drawn in its place; the wordmark `veyq` moves to the
 *   left margin, where the mark stood.
 * - **Deterministic bytes:** no `/CreationDate`, `/ModDate`, `/Producer` or `/ID`, the content stream uncompressed —
 *   the file is a function of (email, Secret Key, server, words, the day printed), which the caller passes.
 * - The three address fields are AcroForm text fields `address1..3` with `/NeedAppearances true` and no appearance
 *   streams of their own (a viewer draws them); the first holds the server's host (`host[:port]` of its origin).
 *
 * As on the web, the token is in the document information's `/Keywords` (a literal string, so a reader finds it in the
 * raw bytes) and the `/Title` is the kit's title; the help address is a link annotation.
 *
 * **The Secret Key's text** is in the content stream and in the token only. Every byte buffer this writer makes is
 * zeroed once copied on; the returned file is the caller's to zero ([write] does). The key's printed text and the token
 * are Strings, which cannot be zeroed — they are let go.
 */
object KitWriter {
    /**
     * The kit layout this writer prints (ADR 0157 decision 1): 2, the web's sheet with the key labelled "9 blocks in 3
     * lines" (`KitWords.secretKey`); the apps' kits before it were layout 1 and still read (the layout is in no token).
     * Every flow that makes a kit records it in the account's kit record ([KitRecord], ADR 0157 decision 4), and an
     * account whose record names an older layout is offered a new kit ([KitStatus]).
     */
    const val LAYOUT = 2

    const val PAGE_WIDTH = 595.28
    const val PAGE_HEIGHT = 841.89

    /** The file name the web saves a kit as. */
    fun fileName(email: String) = "veyq-emergency-kit-$email.pdf"

    /**
     * The kit's PDF for [email] and [secretKey] (32 bytes), made on [server] (an origin, [EmergencyKit.isServer]; null
     * where none is known — the first address field stays empty), in [words]' language, dated [createdEpochDay] (days
     * since 1970-01-01, the person's local date). An IllegalArgumentException where the token cannot be written.
     */
    fun pdf(p: Primitives, email: String, secretKey: ByteArray, server: String?, words: KitWords, createdEpochDay: Long): ByteArray {
        val token = EmergencyKit.write(p, email, secretKey, server)
        val groups = SecretKeyText.format(p, secretKey).split('-')
        val content = Content()
        try {
            draw(content, email, groups, QrEncoder.encode(token), words, createdEpochDay)
            return assemble(content.out, token, server?.substringAfter("://"), words)
        } finally {
            content.out.wipe()
        }
    }

    /**
     * Writes the kit ([pdf]) as the whole file at [path] — the path the platform's save dialog handed over, the one
     * place the kit is written: no temp file, no other copy. A write that fails removes what it left at [path] and
     * throws. The file's bytes are zeroed once written.
     */
    fun write(path: String, p: Primitives, email: String, secretKey: ByteArray, server: String?, words: KitWords, createdEpochDay: Long) {
        val bytes = pdf(p, email, secretKey, server, words, createdEpochDay)
        try {
            saveKitFile(path, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    // ---- the layout: renderKitPdf's, line for line (y from the top, as jsPDF's) ----

    private const val W = PAGE_WIDTH
    private const val M = 48.0
    private const val CW = W - 2 * M

    private class Rgb(val r: Int, val g: Int, val b: Int)

    private val INK = Rgb(24, 20, 38)
    private val MUTED = Rgb(102, 96, 120)
    private val BRAND = Rgb(91, 63, 168)
    private val BRAND_DEEP = Rgb(69, 33, 99)
    private val TINT = Rgb(245, 242, 252)
    private val LINE = Rgb(214, 206, 236)
    private val WARN_BG = Rgb(255, 247, 230)
    private val WARN_LINE = Rgb(240, 200, 120)
    private val WARN_INK = Rgb(122, 74, 0)
    private val WHITE = Rgb(255, 255, 255)

    private val REGULAR = KitFont.HELVETICA
    private val BOLD = KitFont.HELVETICA_BOLD

    /** Where each address field sits (top, as jsPDF's `field.y`); the third is [CW] wide and 18 pt high. */
    private val FIELD_TOPS = DoubleArray(3) { 430.0 + 28 + it * 24 - 18 }

    /** The footer's help line, where the link annotation covers it. */
    private const val HELP_Y = 816.0
    private const val HELP_SIZE = 8.5

    private fun draw(c: Content, email: String, groups: List<String>, qr: QrMatrix, w: KitWords, createdEpochDay: Long) {
        // Brand strip, wordmark (no mark: see the class), date.
        c.fill(BRAND_DEEP); c.rect(0.0, 0.0, W, 6.0); c.op("f")
        c.fill(BRAND); c.rect(0.0, 6.0, W, 2.0); c.op("f")
        c.text(BOLD, 15.0, BRAND_DEEP, M, 56.0, "veyq")
        c.textRight(REGULAR, 9.0, MUTED, W - M, 48.0, w.created(createdEpochDay))
        c.textRight(REGULAR, 9.0, MUTED, W - M, 60.0, w.page)

        // Title and what the sheet is for.
        c.text(BOLD, 28.0, INK, M, 112.0, w.heading)
        c.lines(REGULAR, 11.0, MUTED, M, 134.0, split(w.intro, REGULAR, 11.0, CW), 1.35)

        // Confidential callout.
        var y = 160.0
        c.fill(WARN_BG); c.stroke(WARN_LINE); c.lineWidth(0.8)
        c.roundedRect(M, y, CW, 40.0, 6.0); c.op("B")
        c.text(BOLD, 10.0, WARN_INK, M + 14, y + 17, w.confidential)
        c.text(REGULAR, 10.0, WARN_INK, M + 14, y + 30, w.confidentialBody)

        // 1 — the account: the email and the Secret Key, three lines of three groups.
        y = 232.0
        section(c, 1, w.account, y)
        y += 16
        c.fill(TINT); c.stroke(LINE)
        c.roundedRect(M, y, CW, 138.0, 8.0); c.op("B")
        c.text(BOLD, 8.5, MUTED, M + 18, y + 24, w.email.uppercase())
        c.text(REGULAR, 13.0, INK, M + 18, y + 42, email)
        c.text(BOLD, 8.5, MUTED, M + 18, y + 68, w.secretKey.uppercase())
        for (line in 0 until 3) {
            c.text(KitFont.COURIER_BOLD, 16.0, BRAND_DEEP, M + 18, y + 90 + line * 20, groups.subList(line * 3, line * 3 + 3).joinToString("-"))
        }

        // 2 — where you sign in: three lines, each with its form field above it ([assemble] writes the fields).
        y += 162
        section(c, 2, w.where, y)
        y += 20
        c.lines(REGULAR, 9.0, MUTED, M, y + 4, split(w.addresses, REGULAR, 9.0, CW), 1.15)
        c.stroke(LINE); c.lineWidth(0.8)
        for (i in 0 until 3) {
            val ly = y + 28 + i * 24
            c.line(M, ly, W - M, ly)
        }

        // 3 — quick restore: the QR in its frame, and the three steps (the password is not on this sheet).
        y += 104
        section(c, 3, w.restore, y)
        y += 16
        val box = 118.0
        c.stroke(LINE); c.fill(WHITE)
        c.roundedRect(M, y, box, box, 8.0); c.op("B")
        c.qr(qr, M + 12, y + 12, box - 24)
        val tx = M + box + 22
        val tw = W - M - tx
        var sy = y + 16
        w.steps.forEachIndexed { i, step ->
            c.text(BOLD, 10.5, BRAND, tx, sy, "${i + 1}.")
            val lines = split(step, REGULAR, 10.5, tw - 16)
            c.lines(REGULAR, 10.5, INK, tx + 16, sy, lines, 1.35)
            sy += lines.size * 14.2 + 10
        }

        // 4 — how to keep it.
        y += box + 30
        section(c, 4, w.keep, y)
        y += 20
        for (rule in w.keepRules) {
            c.fill(BRAND); c.circle(M + 4, y - 3.5, 2.2); c.op("f")
            val lines = split(rule, REGULAR, 10.5, CW - 16)
            c.lines(REGULAR, 10.5, INK, M + 14, y, lines, 1.35)
            y += lines.size * 14.2 + 4
        }

        // Footer: where to get help, and the marking again.
        c.stroke(LINE); c.lineWidth(0.6)
        c.line(M, 800.0, W - M, 800.0)
        c.text(REGULAR, HELP_SIZE, MUTED, M, HELP_Y, w.help)
        c.textRight(REGULAR, HELP_SIZE, MUTED, W - M, HELP_Y, "${w.confidential} · ${w.title}")
    }

    private fun section(c: Content, n: Int, title: String, top: Double) {
        c.fill(BRAND); c.circle(M + 9, top - 4, 9.0); c.op("f")
        c.textCentred(BOLD, 10.0, WHITE, M + 9, top - 0.5, n.toString())
        c.text(BOLD, 13.0, INK, M + 26, top, title)
    }

    /**
     * [text] in lines no wider than [max] at [size] — jsPDF's `splitTextToSize`: words kept whole and joined by one space
     * while they fit, a word wider than a line alone cut where it no longer fits.
     */
    internal fun split(text: String, font: KitFont, size: Double, max: Double): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (font.width(candidate, size) <= max) {
                line = candidate
                continue
            }
            if (line.isNotEmpty()) lines += line
            line = ""
            var rest = word
            while (font.width(rest, size) > max && rest.length > 1) {
                var n = 1
                while (n < rest.length && font.width(rest.substring(0, n + 1), size) <= max) n++
                lines += rest.substring(0, n)
                rest = rest.substring(n)
            }
            line = rest
        }
        if (line.isNotEmpty() || lines.isEmpty()) lines += line
        return lines
    }

    // ---- the file ----

    private fun assemble(content: Buf, token: String, host: String?, w: KitWords): ByteArray {
        val h = PAGE_HEIGHT
        val fields = (0 until 3).map { i ->
            val top = FIELD_TOPS[i]
            val value = if (i == 0 && host != null) host else ""
            "<< /Type /Annot /Subtype /Widget /FT /Tx /T (address${i + 1}) /V ${literal(value)} /DA (/Helv 11 Tf 0 g) " +
                "/Rect [${n(M)} ${n(h - top - 18)} ${n(M + CW)} ${n(h - top)}] /F 4 /P 3 0 R /MK << >> >>"
        }
        val helpWidth = REGULAR.width(w.help, HELP_SIZE)
        val link = "<< /Type /Annot /Subtype /Link /Rect [${n(M)} ${n(h - HELP_Y - HELP_SIZE * 0.25)} ${n(M + helpWidth)} " +
            "${n(h - HELP_Y + HELP_SIZE * 0.85)}] /Border [0 0 0] /A << /S /URI /URI ${literal(w.helpUrl)} >> >>"
        val font = { f: KitFont -> "<< /Type /Font /Subtype /Type1 /BaseFont /${f.baseFont} /Encoding /WinAnsiEncoding >>" }
        val out = Buf()
        try {
            out.ascii("%PDF-1.4\n%")
            for (b in intArrayOf(0xe2, 0xe3, 0xcf, 0xd3)) out.byte(b)
            out.ascii("\n")
            val offsets = IntArray(13)
            fun obj(id: Int, body: String) {
                offsets[id] = out.size
                out.ascii("$id 0 obj\n$body\nendobj\n")
            }
            obj(1, "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [8 0 R 9 0 R 10 0 R] /NeedAppearances true " +
                "/DR << /Font << /Helv 5 0 R >> >> /DA (/Helv 0 Tf 0 g) >> >>")
            obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(PAGE_WIDTH)} ${n(PAGE_HEIGHT)}] " +
                "/Resources << /Font << /F1 5 0 R /F2 6 0 R /F3 7 0 R >> /ProcSet [/PDF /Text] >> /Contents 4 0 R " +
                "/Annots [8 0 R 9 0 R 10 0 R 11 0 R] >>")
            offsets[4] = out.size
            out.ascii("4 0 obj\n<< /Length ${content.size} >>\nstream\n")
            out.bytes(content)
            out.ascii("\nendstream\nendobj\n")
            obj(5, font(KitFont.HELVETICA))
            obj(6, font(KitFont.HELVETICA_BOLD))
            obj(7, font(KitFont.COURIER_BOLD))
            fields.forEachIndexed { i, f -> obj(8 + i, f) }
            obj(11, link)
            // The token as a literal string, uncompressed: the reader's "token in the bytes" step finds it.
            obj(12, "<< /Title ${textString(w.title)} /Keywords ${literal(token)} >>")
            val xref = out.size
            out.ascii("xref\n0 13\n0000000000 65535 f \n")
            for (id in 1..12) out.ascii("${offsets[id].toString().padStart(10, '0')} 00000 n \n")
            out.ascii("trailer\n<< /Size 13 /Root 1 0 R /Info 12 0 R >>\nstartxref\n$xref\n%EOF\n")
            return out.toByteArray()
        } finally {
            out.wipe()
        }
    }

    /** A PDF literal string of [s]'s WinAnsi bytes: `(`, `)` and `\` escaped, a byte outside printable ASCII in octal. */
    private fun literal(s: String): String = buildString {
        append('(')
        for (ch in s) appendEscaped(KitFont.code(ch))
        append(')')
    }

    private fun StringBuilder.appendEscaped(code: Int) {
        when {
            code == '('.code || code == ')'.code || code == '\\'.code -> append('\\').append(code.toChar())
            code in 0x20..0x7e -> append(code.toChar())
            else -> append('\\').append(code.toString(8).padStart(3, '0'))
        }
    }

    /** A PDF text string: a literal where [s] is printable ASCII, else UTF-16BE with its byte-order mark, in hex. */
    private fun textString(s: String): String =
        if (s.all { it.code in 0x20..0x7e }) literal(s)
        else "<FEFF" + s.map { it.code.toString(16).uppercase().padStart(4, '0') }.joinToString("") + ">"

    /** [v] with at most three decimals and no trailing zeros, the same on every platform (no locale, no exponent). */
    internal fun n(v: Double): String {
        val m = round(v * 1000).toLong()
        val a = abs(m)
        val whole = (a / 1000).toString()
        val frac = (a % 1000).toString().padStart(3, '0').trimEnd('0')
        val s = if (frac.isEmpty()) whole else "$whole.$frac"
        return if (m < 0) "-$s" else s
    }

    /** The page's content stream: jsPDF's calls, y measured from the top and turned here. */
    private class Content {
        val out = Buf()
        val size get() = out.size

        fun op(s: String) = out.ascii("$s\n")

        private fun y(top: Double) = PAGE_HEIGHT - top

        private fun rgb(c: Rgb) = "${n(c.r / 255.0)} ${n(c.g / 255.0)} ${n(c.b / 255.0)}"

        fun fill(c: Rgb) = op("${rgb(c)} rg")
        fun stroke(c: Rgb) = op("${rgb(c)} RG")
        fun lineWidth(w: Double) = op("${n(w)} w")

        fun rect(x: Double, top: Double, w: Double, h: Double) = op("${n(x)} ${n(y(top + h))} ${n(w)} ${n(h)} re")

        fun line(x1: Double, y1: Double, x2: Double, y2: Double) = op("${n(x1)} ${n(y(y1))} m ${n(x2)} ${n(y(y2))} l S")

        fun roundedRect(x: Double, top: Double, w: Double, h: Double, r: Double) {
            val k = KAPPA * r
            val x0 = x
            val x1 = x + w
            val yt = y(top)
            val yb = y(top + h)
            op("${n(x0 + r)} ${n(yt)} m ${n(x1 - r)} ${n(yt)} l")
            op("${n(x1 - r + k)} ${n(yt)} ${n(x1)} ${n(yt - r + k)} ${n(x1)} ${n(yt - r)} c ${n(x1)} ${n(yb + r)} l")
            op("${n(x1)} ${n(yb + r - k)} ${n(x1 - r + k)} ${n(yb)} ${n(x1 - r)} ${n(yb)} c ${n(x0 + r)} ${n(yb)} l")
            op("${n(x0 + r - k)} ${n(yb)} ${n(x0)} ${n(yb + r - k)} ${n(x0)} ${n(yb + r)} c ${n(x0)} ${n(yt - r)} l")
            op("${n(x0)} ${n(yt - r + k)} ${n(x0 + r - k)} ${n(yt)} ${n(x0 + r)} ${n(yt)} c h")
        }

        fun circle(cx: Double, cyTop: Double, r: Double) {
            val k = KAPPA * r
            val cy = y(cyTop)
            op("${n(cx + r)} ${n(cy)} m")
            op("${n(cx + r)} ${n(cy + k)} ${n(cx + k)} ${n(cy + r)} ${n(cx)} ${n(cy + r)} c")
            op("${n(cx - k)} ${n(cy + r)} ${n(cx - r)} ${n(cy + k)} ${n(cx - r)} ${n(cy)} c")
            op("${n(cx - r)} ${n(cy - k)} ${n(cx - k)} ${n(cy - r)} ${n(cx)} ${n(cy - r)} c")
            op("${n(cx + k)} ${n(cy - r)} ${n(cx + r)} ${n(cy - k)} ${n(cx + r)} ${n(cy)} c h")
        }

        /** [s] with its baseline at [baseline], starting at [x]. */
        fun text(font: KitFont, size: Double, color: Rgb, x: Double, baseline: Double, s: String) {
            fill(color)
            out.ascii("BT /${font.resource} ${n(size)} Tf ${n(x)} ${n(y(baseline))} Td (")
            for (ch in s) {
                val code = KitFont.code(ch)
                when {
                    code == '('.code || code == ')'.code || code == '\\'.code -> { out.byte('\\'.code); out.byte(code) }
                    code in 0x20..0x7e -> out.byte(code)
                    else -> out.ascii("\\" + code.toString(8).padStart(3, '0'))
                }
            }
            out.ascii(") Tj ET\n")
        }

        fun textRight(font: KitFont, size: Double, color: Rgb, right: Double, baseline: Double, s: String) =
            text(font, size, color, right - font.width(s, size), baseline, s)

        fun textCentred(font: KitFont, size: Double, color: Rgb, centre: Double, baseline: Double, s: String) =
            text(font, size, color, centre - font.width(s, size) / 2, baseline, s)

        /** jsPDF's multi-line `text`: each line [lineHeightFactor] × [size] below the one before. */
        fun lines(font: KitFont, size: Double, color: Rgb, x: Double, baseline: Double, lines: List<String>, lineHeightFactor: Double) =
            lines.forEachIndexed { i, l -> text(font, size, color, x, baseline + i * size * lineHeightFactor, l) }

        /** The code's dark modules (not its quiet zone) as one path of row runs, [side] pt square from ([x], [top]). */
        fun qr(qr: QrMatrix, x: Double, top: Double, side: Double) {
            fill(INK)
            val q = QrMatrix.QUIET_ZONE
            val module = side / qr.size
            for (row in 0 until qr.size) {
                var col = 0
                while (col < qr.size) {
                    if (!qr.dark(col + q, row + q)) {
                        col++
                        continue
                    }
                    var end = col
                    while (end < qr.size && qr.dark(end + q, row + q)) end++
                    rect(x + col * module, top + row * module, (end - col) * module, module)
                    col = end
                }
            }
            op("f")
        }
    }

    /** The circle's Bézier constant, as jsPDF draws an ellipse. */
    private const val KAPPA = 0.5522847498

    /** A growable byte buffer that zeroes every array it lets go and, on [wipe], its own. */
    private class Buf {
        private var a = ByteArray(16 * 1024)
        var size = 0
            private set

        private fun room(n: Int) {
            if (size + n <= a.size) return
            val b = a.copyOf(maxOf(a.size * 2, size + n))
            a.fill(0)
            a = b
        }

        fun byte(b: Int) {
            room(1)
            a[size++] = b.toByte()
        }

        fun ascii(s: String) {
            room(s.length)
            for (c in s) a[size++] = c.code.toByte()
        }

        fun bytes(other: Buf) {
            room(other.size)
            other.a.copyInto(a, size, 0, other.size)
            size += other.size
        }

        fun toByteArray(): ByteArray = a.copyOf(size)

        fun wipe() {
            a.fill(0)
            size = 0
        }
    }
}

/**
 * Writes [bytes] as the whole file at [path], created or emptied; where it does not all land, removes what it left there
 * and throws. Natively the core's `FileSink`; on the JVM (Android, the tests) `java.io`.
 */
internal expect fun saveKitFile(path: String, bytes: ByteArray)
