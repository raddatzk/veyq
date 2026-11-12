package me.raddatz.veyq.mobile.blob

/**
 * The media type a file picked by its path is claimed as on upload, by its name's extension — what a browser's `File.type`
 * and the phones' pickers claim for it (the web's upload, Android's documents provider, Apple's `UTType`). A desktop's
 * dialog hands a path and no type, so the core claims it here, the same for Linux and Windows. It is a claim sealed with
 * the file (shown as such on its page); how the file is shown is decided by its bytes ([Sniff]). An extension not listed
 * is `application/octet-stream`, as a browser leaves an unknown one.
 */
object MediaTypes {
    const val UNKNOWN = "application/octet-stream"

    private val BY_EXTENSION: Map<String, String> = mapOf(
        // pictures
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "avif" to "image/avif", "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
        "tif" to "image/tiff", "tiff" to "image/tiff", "svg" to "image/svg+xml", "ico" to "image/vnd.microsoft.icon",
        // video
        "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mov" to "video/quicktime", "webm" to "video/webm", "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo", "mpeg" to "video/mpeg", "mpg" to "video/mpeg", "ogv" to "video/ogg", "3gp" to "video/3gpp",
        // audio
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav", "flac" to "audio/flac",
        "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg", "weba" to "audio/webm", "mid" to "audio/midi", "midi" to "audio/midi",
        // documents
        "pdf" to "application/pdf", "txt" to "text/plain", "text" to "text/plain", "log" to "text/plain", "md" to "text/markdown",
        "markdown" to "text/markdown", "csv" to "text/csv", "tsv" to "text/tab-separated-values", "html" to "text/html", "htm" to "text/html",
        "css" to "text/css", "js" to "text/javascript", "mjs" to "text/javascript", "json" to "application/json", "xml" to "application/xml",
        "yaml" to "application/yaml", "yml" to "application/yaml", "ics" to "text/calendar", "vcf" to "text/vcard", "rtf" to "application/rtf",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text", "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation", "epub" to "application/epub+zip",
        // archives
        "zip" to "application/zip", "gz" to "application/gzip", "tgz" to "application/gzip", "tar" to "application/x-tar",
        "bz2" to "application/x-bzip2", "xz" to "application/x-xz", "7z" to "application/x-7z-compressed", "rar" to "application/vnd.rar",
        // fonts
        "ttf" to "font/ttf", "otf" to "font/otf", "woff" to "font/woff", "woff2" to "font/woff2",
    )

    /** The type [name] is claimed as by its extension (case does not matter); [UNKNOWN] where none is listed. */
    fun byName(name: String): String = BY_EXTENSION[Sniff.extensionOf(name)] ?: UNKNOWN
}
