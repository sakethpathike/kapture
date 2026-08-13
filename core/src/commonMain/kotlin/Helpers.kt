package io.github.sakethpathike.kapture

import io.ktor.http.*
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.files.Path
import kotlinx.io.writeString

internal fun RawSink.write(string: String) {
    val buffer = Buffer()
    buffer.writeString(string)
    this.write(source = buffer, byteCount = buffer.size)
}

internal fun String.toPath(): Path = Path(this)

internal fun resolveUrl(baseUrl: String, relativeUrl: String): String {
    val trimmed = relativeUrl.trim()
    if (trimmed.isEmpty()) return trimmed

    val lower = trimmed.lowercase()
    if (lower.startsWith("data:") || lower.startsWith("#") || lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith(
            "javascript:"
        ) || lower.startsWith("blob:")
    ) {
        return trimmed
    }

    return try {
        val builder = URLBuilder(baseUrl)
        builder.takeFrom(trimmed)
        builder.buildString()
    } catch (_: Exception) {
        trimmed
    }
}

internal val CSS_URL_REGEX = Regex(
    """url\(\s*(['"]?)([\s\S]*?)\1\s*\)""", RegexOption.IGNORE_CASE
)

internal fun getMimeType(urlOrPath: String): String {
    val path = urlOrPath.substringBefore('?').substringBefore('#').substringAfterLast('/')
    val ext = path.substringAfterLast('.', "").lowercase()

    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "ogv" -> "video/ogg"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "eot" -> "application/vnd.ms-fontobject"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "text/javascript"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "txt" -> "text/plain"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }
}

internal const val INIT_REQUIRED_MSG = "Kapture must be initialized, call Kapture#init before archiving"
