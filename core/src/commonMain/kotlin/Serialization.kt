package io.github.sakethpathike.kapture

import com.fleeksoft.ksoup.nodes.*
import io.ktor.utils.io.core.*
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private sealed interface WalkItem {
    data class ProcessNode(val node: Node) : WalkItem
    data class WriteCloseTag(val tagName: String) : WalkItem
}

internal class Serialization(private val options: Options) {

    fun writeBySerializing(
        document: Document,
        mediaFileMap: Map<MediaUrl, FileName>,
        mimeMap: Map<MediaUrl, String>,
        destinationFile: RawSink
    ) {
        val stack = ArrayDeque<WalkItem>()
        stack.addLast(WalkItem.ProcessNode(document))

        val voidElements = setOf(
            "area",
            "base",
            "br",
            "col",
            "embed",
            "hr",
            "img",
            "input",
            "link",
            "meta",
            "param",
            "source",
            "track",
            "wbr"
        )

        while (stack.isNotEmpty()) {
            when (val item = stack.removeLast()) {
                is WalkItem.ProcessNode -> {
                    when (val node = item.node) {
                        is Document -> {
                            val children = node.childNodes()
                            for (i in children.indices.reversed()) {
                                stack.addLast(WalkItem.ProcessNode(children[i]))
                            }
                        }

                        is Element -> {
                            val tag = node.tagName().lowercase()

                            when (tag) {
                                "script" -> {
                                    if (!options.includeJs) continue
                                    if (!options.includeMetadata && isMetadataScript(node)) continue
                                }

                                "noscript" -> {
                                    val children = node.childNodes()
                                    for (i in children.indices.reversed()) {
                                        stack.addLast(WalkItem.ProcessNode(children[i]))
                                    }
                                    continue
                                }

                                "style" -> {
                                    if (!options.includeCss) continue
                                }

                                "link" -> {
                                    if (isFontLink(node)) continue
                                    if (!options.includeMetadata && isMetadataLink(node)) continue

                                    val rel = node.attr("rel").lowercase()
                                    if (rel.contains("stylesheet") && !options.includeCss) continue
                                }

                                "meta" -> {
                                    if (!options.includeMetadata && isRemovableMetadata(node)) continue
                                }

                                "img", "source", "picture" -> {
                                    if (!options.includeImages) continue
                                }

                                "video" -> {
                                    if (!options.includeVideo) continue
                                }

                                "audio", "track" -> {
                                    if (!options.includeAudio) continue
                                }
                            }

                            if (tag == "script" || tag == "style") {
                                writeOpenTag(
                                    element = node,
                                    destinationFile = destinationFile,
                                    mediaFileMap = mediaFileMap,
                                    mimeMap = mimeMap
                                )
                                val rawData = node.childNodes().filterIsInstance<DataNode>().joinToString("") { it.getWholeData() }
                                if (tag == "style") {
                                    val cssStrings = node.childNodes()
                                        .filterIsInstance<DataNode>()
                                        .map { it.getWholeData() }

                                    processCssToSink(
                                        reader = StringCssByteReader(cssStrings),
                                        baseUrl = node.baseUri(),
                                        destinationFile = destinationFile,
                                        mediaFileMap = mediaFileMap,
                                        mimeMap = mimeMap
                                    )
                                }else {
                                    val safeJs = rawData.replace(
                                        "</script>", "<\\/script>", ignoreCase = true
                                    )
                                    destinationFile.write(safeJs)
                                }
                                destinationFile.write("</$tag>")
                                continue
                            }


                            writeOpenTag(
                                element = node,
                                destinationFile = destinationFile,
                                mediaFileMap = mediaFileMap,
                                mimeMap = mimeMap
                            )

                            if (tag !in voidElements) {
                                stack.addLast(WalkItem.WriteCloseTag(node.tagName()))
                                val children = node.childNodes()
                                for (i in children.indices.reversed()) {
                                    stack.addLast(WalkItem.ProcessNode(children[i]))
                                }
                            }
                        }

                        is TextNode -> {
                            val escapedHtml = escapeHtml(node.getWholeText())
                            destinationFile.write(string = escapedHtml)
                        }

                        is DataNode -> {
                            val parentTag = node.parent()?.nodeName()?.lowercase()
                            if (parentTag == "script" || parentTag == "style") {
                                continue
                            }
                            destinationFile.write(node.getWholeData())
                        }

                        is Comment -> {
                            destinationFile.write("<!--${node.getData()}-->")
                        }

                        is DocumentType -> {
                            destinationFile.write("<!DOCTYPE ${node.name()}>")
                        }

                        is CDataNode -> {
                            destinationFile.write("<![CDATA[${node.getWholeText()}]]>")
                        }

                        is XmlDeclaration -> {
                            destinationFile.write("<?${node.name()} ${node.getWholeDeclaration()}?>")
                        }
                    }
                }

                is WalkItem.WriteCloseTag -> {
                    destinationFile.write("</${item.tagName}>")
                }
            }
        }
        destinationFile.flush()
    }

    private fun isFontLink(element: Element): Boolean {
        val rel = element.attr("rel").lowercase()
        val asAttr = element.attr("as").lowercase()
        if (asAttr == "font") return true
        if (rel.contains("font")) return true
        val href = element.absUrl("href").ifEmpty { element.attr("href") }
        return isFontUrl(href)
    }

    private fun writeOpenTag(
        element: Element, destinationFile: RawSink, mediaFileMap: Map<String, String>, mimeMap: Map<String, String>
    ) {
        val tagName = element.tagName().lowercase()

        if (tagName == "link") {
            val rel = element.attr("rel").lowercase()
            val href = element.absUrl("href").ifEmpty { element.attr("href") }

            if (rel.contains("stylesheet")) {
                val cssFilePath = mediaFileMap[href]
                if (cssFilePath != null) {
                    destinationFile.write("<style>")

                    SystemFileSystem.source(Path(cssFilePath)).buffered().use { cssSource ->
                        val reader = object : CssByteReader() {
                            private val buffer = ByteArray(8192)
                            private var pos = 0
                            private var limit = 0
                            private var eof = false

                            override fun readNext(): Int {
                                if (pos >= limit) {
                                    if (eof) return -1

                                    val bytesRead = cssSource.readAtMostTo(buffer, 0, buffer.size)
                                    if (bytesRead == -1) {
                                        eof = true
                                        return -1
                                    }

                                    pos = 0
                                    limit = bytesRead
                                }

                                val value = buffer[pos].toInt() and 0xFF
                                pos += 1
                                return value
                            }
                        }

                        processCssToSink(
                            reader = reader,
                            baseUrl = href,
                            destinationFile = destinationFile,
                            mediaFileMap = mediaFileMap,
                            mimeMap = mimeMap
                        )
                    }

                    destinationFile.write("</style>")
                    return
                }
            }

            if (rel.contains("icon") || rel.contains("shortcut icon")) {
                val tempFile = mediaFileMap[href]

                if (tempFile != null) {
                    val mime = mimeMap[href] ?: getMimeType(href)

                    destinationFile.write("<link rel=\"$rel\" href=\"data:$mime;base64,")
                    streamBase64(options.base64StreamSize, tempFile, destinationFile)
                    destinationFile.write("\">")
                    return
                }
            }
        }

        destinationFile.write("<${element.tagName()}")

        element.attributes().forEach { attribute ->
            val attrName = attribute.key
            val attrValue = attribute.value

            if (attrName.equals("srcset", ignoreCase = true)) {
                destinationFile.write(" srcset=\"")
                writeSrcsetInline(
                    srcset = attrValue,
                    baseUrl = element.baseUri(),
                    destinationFile = destinationFile,
                    mediaFileMap = mediaFileMap,
                    mimeMap = mimeMap
                )
                destinationFile.write("\"")
                return@forEach
            }

            if (attrName.equals("style", ignoreCase = true)) {
                destinationFile.write(" style=\"")

                processCssToSink(
                    reader = StringCssByteReader(listOf(attrValue)),
                    baseUrl = element.baseUri(),
                    destinationFile = destinationFile,
                    mediaFileMap = mediaFileMap,
                    mimeMap = mimeMap,
                    escapeHtmlOutput = true
                )

                destinationFile.write("\"")
                return@forEach
            }

            val resolvedUrl = if (isUrlAttribute(attrName) && !isNonResolvableUrl(attrValue)) {
                element.absUrl(attrName).ifEmpty { attrValue }
            } else {
                attrValue
            }

            val tempFileName = mediaFileMap[resolvedUrl] ?: mediaFileMap[attrValue]

            if (tempFileName != null) {
                val mime = mimeMap[resolvedUrl] ?: mimeMap[attrValue] ?: getMimeType(resolvedUrl)

                destinationFile.write(" $attrName=\"data:$mime;base64,")
                streamBase64(options.base64StreamSize, tempFileName, destinationFile)
                destinationFile.write("\"")
            } else {
                destinationFile.write(" $attrName=\"${escapeHtml(resolvedUrl)}\"")
            }
        }

        destinationFile.write(">")
    }


    private fun writeSrcsetInline(
        srcset: String,
        baseUrl: String,
        destinationFile: RawSink,
        mediaFileMap: Map<String, String>,
        mimeMap: Map<String, String>
    ) {
        val candidates = srcset.split(",")

        candidates.forEachIndexed { index, candidate ->
            if (index > 0) destinationFile.write(", ")

            val trimmedCandidate = candidate.trim()
            val parts = trimmedCandidate.split(WHITESPACE_REGEX)

            if (parts.isEmpty() || parts[0].isEmpty()) {
                destinationFile.write(trimmedCandidate)
                return@forEachIndexed
            }

            val rawUrl = parts[0]
            val descriptor = parts.drop(1).joinToString(" ")

            if (isNonResolvableUrl(rawUrl)) {
                destinationFile.write(trimmedCandidate)
                return@forEachIndexed
            }

            val absoluteUrl = resolveUrl(baseUrl, rawUrl)
            val tempFileName = mediaFileMap[absoluteUrl] ?: mediaFileMap[rawUrl]

            if (tempFileName != null) {
                val mime = mimeMap[absoluteUrl] ?: mimeMap[rawUrl] ?: getMimeType(absoluteUrl)

                destinationFile.write("data:$mime;base64,")
                streamBase64(options.base64StreamSize, tempFileName, destinationFile)

                if (descriptor.isNotEmpty()) {
                    destinationFile.write(" $descriptor")
                }
            } else {
                destinationFile.write(absoluteUrl)

                if (descriptor.isNotEmpty()) {
                    destinationFile.write(" $descriptor")
                }
            }
        }
    }

    private fun isMetadataScript(element: Element): Boolean {
        return element.attr("type").lowercase().trim() == "application/ld+json"
    }

    private fun isMetadataLink(element: Element): Boolean {
        val rel = element.attr("rel").lowercase()
        return rel.contains("canonical") || rel.contains("alternate") || rel.contains("author") || rel.contains("prev") || rel.contains(
            "next"
        )
    }

    private fun isRemovableMetadata(element: Element): Boolean {
        if (element.hasAttr("charset")) return false

        val httpEquiv = element.attr("http-equiv").lowercase().trim()
        if (httpEquiv == "content-type") return false

        val name = element.attr("name").lowercase().trim()

        // functional meta tags that usually affect rendering
        if (name == "viewport" || name == "color-scheme") return false

        val property = element.attr("property").lowercase().trim()
        val itemprop = element.attr("itemprop").lowercase().trim()

        return name.isNotEmpty() || property.isNotEmpty() || itemprop.isNotEmpty() || httpEquiv.isNotEmpty()
    }

    private fun escapeHtml(input: String): String {
        val stringBuilder = StringBuilder(input.length)
        input.forEach { char ->
            when (char) {
                '&' -> stringBuilder.append("&amp;")
                '<' -> stringBuilder.append("&lt;")
                '>' -> stringBuilder.append("&gt;")
                '"' -> stringBuilder.append("&quot;")
                '\'' -> stringBuilder.append("&#x27;")
                else -> stringBuilder.append(char)
            }
        }
        return stringBuilder.toString()
    }

    private val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toByteArray()

    private fun streamBase64(streamSize: Int, tempFileName: String, destinationFile: RawSink) {
        require(streamSize % 3 == 0 && streamSize > 0) { "streamSize must be a positive multiple of 3" }

        SystemFileSystem.source(tempFileName.toPath()).buffered().use { source ->
            val sink = destinationFile.buffered()

            val buffer = ByteArray(streamSize + 2)
            var bufferLen = 0

            val outputBuffer = ByteArray((streamSize / 3 + 1) * 4)

            while (true) {
                val bytesRead = source.readAtMostTo(buffer, bufferLen, buffer.size)
                if (bytesRead == -1) {
                    if (bufferLen > 0) {
                        val outLen = encodeBase64Final(buffer, bufferLen, outputBuffer)
                        sink.write(outputBuffer, 0, outLen)
                    }
                    break
                }

                bufferLen += bytesRead

                val bytesToEncode = (bufferLen / 3) * 3

                if (bytesToEncode > 0) {
                    val outLen = encodeBase64Chunk(buffer, bytesToEncode, outputBuffer)
                    sink.write(outputBuffer, 0, outLen)

                    val leftover = bufferLen - bytesToEncode
                    if (leftover > 0) {
                        buffer.copyInto(buffer, 0, bytesToEncode, bufferLen)
                    }
                    bufferLen = leftover
                }
            }
            sink.flush()
        }
    }

    private fun encodeBase64Chunk(input: ByteArray, len: Int, output: ByteArray): Int {
        var outIdx = 0
        for (i in 0 until len step 3) {
            val b0 = input[i].toInt() and 0xFF
            val b1 = input[i + 1].toInt() and 0xFF
            val b2 = input[i + 2].toInt() and 0xFF

            output[outIdx++] = BASE64_ALPHABET[b0 ushr 2]
            output[outIdx++] = BASE64_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)]
            output[outIdx++] = BASE64_ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)]
            output[outIdx++] = BASE64_ALPHABET[b2 and 0x3F]
        }
        return outIdx
    }

    private fun encodeBase64Final(input: ByteArray, len: Int, output: ByteArray): Int {
        var outIdx = 0
        if (len == 1) {
            val b0 = input[0].toInt() and 0xFF
            output[outIdx++] = BASE64_ALPHABET[b0 ushr 2]
            output[outIdx++] = BASE64_ALPHABET[(b0 and 0x03) shl 4]
            output[outIdx++] = '='.code.toByte()
            output[outIdx++] = '='.code.toByte()
        } else if (len == 2) {
            val b0 = input[0].toInt() and 0xFF
            val b1 = input[1].toInt() and 0xFF
            output[outIdx++] = BASE64_ALPHABET[b0 ushr 2]
            output[outIdx++] = BASE64_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)]
            output[outIdx++] = BASE64_ALPHABET[(b1 and 0x0F) shl 2]
            output[outIdx++] = '='.code.toByte()
        }
        return outIdx
    }

    private val MAX_CSS_URL_CAPTURE_BYTES = 16384

    private val HTML_AMP = "&amp;".encodeToByteArray()
    private val HTML_LT = "&lt;".encodeToByteArray()
    private val HTML_GT = "&gt;".encodeToByteArray()
    private val HTML_QUOT = "&quot;".encodeToByteArray()
    private val HTML_APOS = "&#x27;".encodeToByteArray()

    private interface CssOutput {
        fun writeByte(b: Int)
        fun writeBytes(bytes: ByteArray, start: Int, end: Int)
        fun writeString(value: String)
        fun flushAll()
    }

    private abstract class CssByteReader {
        private val pushback = IntArray(32)
        private var pushCount = 0

        fun read(): Int {
            if (pushCount > 0) {
                pushCount -= 1
                return pushback[pushCount]
            }
            return readNext()
        }

        fun unread(b: Int) {
            if (b == -1) return
            if (pushCount == pushback.size) {
                error("CSS byte reader pushback overflow")
            }
            pushback[pushCount] = b
            pushCount += 1
        }

        protected abstract fun readNext(): Int
    }

    private class StringCssByteReader(
        strings: Iterable<String>
    ) : CssByteReader() {
        private val iterator = strings.iterator()
        private var current: ByteArray? = null
        private var pos = 0

        override fun readNext(): Int {
            while (true) {
                val array = current
                if (array != null && pos < array.size) {
                    val value = array[pos].toInt() and 0xFF
                    pos += 1
                    return value
                }

                if (!iterator.hasNext()) return -1

                current = iterator.next().encodeToByteArray()
                pos = 0
            }
        }
    }

    private class ByteCollector {
        var bytes = ByteArray(256)
            private set
        var size = 0
            private set

        fun add(b: Int) {
            if (size == bytes.size) {
                bytes = bytes.copyOf(bytes.size * 2)
            }
            bytes[size] = b.toByte()
            size += 1
        }

        fun toUtf8String(): String {
            return bytes.copyOf(size).decodeToString()
        }
    }

    private fun isCssWhitespaceByte(b: Int): Boolean {
        return b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D || b == 0x0C
    }

    private fun skipCssWhitespace(reader: CssByteReader): Int {
        var b = reader.read()
        while (b != -1 && isCssWhitespaceByte(b)) {
            b = reader.read()
        }
        return b
    }
    private fun processCssToSink(
        reader: CssByteReader,
        baseUrl: String,
        destinationFile: RawSink,
        mediaFileMap: Map<MediaUrl, FileName>,
        mimeMap: Map<MediaUrl, String>,
        escapeHtmlOutput: Boolean = false
    ) {
        val sink = destinationFile.buffered()

        val out = object : CssOutput {
            private val buffer = ByteArray(8192)
            private var pos = 0

            override fun writeByte(b: Int) {
                if (pos == buffer.size) flushBuffer()
                buffer[pos] = b.toByte()
                pos += 1
            }

            override fun writeBytes(bytes: ByteArray, start: Int, end: Int) {
                var currentStart = start
                var remaining = end - start

                while (remaining > 0) {
                    if (pos == buffer.size) flushBuffer()

                    val toCopy = minOf(remaining, buffer.size - pos)
                    bytes.copyInto(
                        destination = buffer,
                        destinationOffset = pos,
                        startIndex = currentStart,
                        endIndex = currentStart + toCopy
                    )

                    pos += toCopy
                    currentStart += toCopy
                    remaining -= toCopy
                }
            }

            override fun writeString(value: String) {
                val bytes = value.encodeToByteArray()
                writeBytes(bytes, 0, bytes.size)
            }

            override fun flushAll() {
                flushBuffer()
                sink.flush()
            }

            private fun flushBuffer() {
                if (pos > 0) {
                    sink.write(buffer, 0, pos)
                    pos = 0
                }
            }
        }

        while (true) {
            val b = reader.read()
            if (b == -1) break

            if (b == 'u'.code || b == 'U'.code) {
                val r = reader.read()
                val l = reader.read()
                val p = reader.read()

                if (
                    (r == 'r'.code || r == 'R'.code) &&
                    (l == 'l'.code || l == 'L'.code) &&
                    p == '('.code
                ) {
                    processCssUrlToken(
                        reader = reader,
                        baseUrl = baseUrl,
                        out = out,
                        destinationFile = destinationFile,
                        mediaFileMap = mediaFileMap,
                        mimeMap = mimeMap,
                        escapeHtmlOutput = escapeHtmlOutput
                    )
                } else {
                    writeMaybeEscapeByte(out, b, escapeHtmlOutput)
                    reader.unread(p)
                    reader.unread(l)
                    reader.unread(r)
                }
            } else {
                writeMaybeEscapeByte(out, b, escapeHtmlOutput)
            }
        }

        out.flushAll()
    }
    private fun processCssUrlToken(
        reader: CssByteReader,
        baseUrl: String,
        out: CssOutput,
        destinationFile: RawSink,
        mediaFileMap: Map<MediaUrl, FileName>,
        mimeMap: Map<MediaUrl, String>,
        escapeHtmlOutput: Boolean
    ) {
        val first = skipCssWhitespace(reader)
        if (first == -1) {
            writeMaybeEscapeString(out, "url(", escapeHtmlOutput)
            return
        }

        val quote = if (first == '"'.code || first == '\''.code) first else 0
        val quoteString = when (quote) {
            '"'.code -> "\""
            '\''.code -> "'"
            else -> ""
        }

        val collector = ByteCollector()
        var overflow = false

        fun addByte(byte: Int) {
            if (overflow) {
                writeMaybeEscapeByte(out, byte, escapeHtmlOutput)
                return
            }

            if (collector.size >= MAX_CSS_URL_CAPTURE_BYTES) {
                overflow = true
                writeMaybeEscapeString(out, "url(", escapeHtmlOutput)
                if (quoteString.isNotEmpty()) {
                    writeMaybeEscapeString(out, quoteString, escapeHtmlOutput)
                }
                writeCapturedBytes(out, collector, escapeHtmlOutput)
                writeMaybeEscapeByte(out, byte, escapeHtmlOutput)
                return
            }

            collector.add(byte)
        }

        var closed = false

        if (quote != 0) {
            var escapeNext = false

            while (true) {
                val b = reader.read()
                if (b == -1) break

                if (escapeNext) {
                    addByte(b)
                    escapeNext = false
                    continue
                }

                if (b == '\\'.code) {
                    addByte(b)
                    escapeNext = true
                    continue
                }

                if (b == quote) {
                    closed = true
                    break
                }

                addByte(b)
            }

            val after = skipCssWhitespace(reader)
            if (after != ')'.code && after != -1) {
                reader.unread(after)
            }
        } else {
            var current = first

            while (true) {
                if (current == -1) break

                if (current == ')'.code) {
                    closed = true
                    break
                }

                if (isCssWhitespaceByte(current)) {
                    var next = reader.read()
                    while (next != -1 && isCssWhitespaceByte(next)) {
                        next = reader.read()
                    }

                    if (next == ')'.code) {
                        closed = true
                    } else if (next != -1) {
                        reader.unread(next)
                    }
                    break
                }

                addByte(current)
                current = reader.read()
            }
        }

        if (overflow) {
            if (quote != 0 && closed) {
                writeMaybeEscapeString(out, quoteString, escapeHtmlOutput)
            }
            writeMaybeEscapeString(out, ")", escapeHtmlOutput)
            return
        }

        val originalUrl = collector.toUtf8String().trim()

        if (originalUrl.isEmpty() || isNonResolvableUrl(originalUrl)) {
            writeMaybeEscapeString(
                out = out,
                value = "url($quoteString$originalUrl$quoteString)",
                escapeHtmlOutput = escapeHtmlOutput
            )
            return
        }

        val absoluteUrl = resolveUrl(baseUrl, originalUrl)

        if (!shouldDownloadCssResource(absoluteUrl, options)) {
            writeMaybeEscapeString(
                out = out,
                value = "url($quoteString$absoluteUrl$quoteString)",
                escapeHtmlOutput = escapeHtmlOutput
            )
            return
        }

        val tempFile = mediaFileMap[absoluteUrl] ?: mediaFileMap[originalUrl]
        if (tempFile == null) {
            writeMaybeEscapeString(
                out = out,
                value = "url($quoteString$absoluteUrl$quoteString)",
                escapeHtmlOutput = escapeHtmlOutput
            )
            return
        }

        val mime = mimeMap[absoluteUrl] ?: mimeMap[originalUrl] ?: getMimeType(absoluteUrl)

        writeMaybeEscapeString(out, "url(", escapeHtmlOutput)
        if (quoteString.isNotEmpty()) {
            writeMaybeEscapeString(out, quoteString, escapeHtmlOutput)
        }
        writeMaybeEscapeString(out, "data:$mime;base64,", escapeHtmlOutput)

        out.flushAll()
        streamBase64(
            streamSize = options.base64StreamSize,
            tempFileName = tempFile,
            destinationFile = destinationFile
        )

        if (quoteString.isNotEmpty()) {
            writeMaybeEscapeString(out, quoteString, escapeHtmlOutput)
        }
        writeMaybeEscapeString(out, ")", escapeHtmlOutput)
    }
    private fun writeMaybeEscapeByte(
        out: CssOutput,
        b: Int,
        escapeHtmlOutput: Boolean
    ) {
        if (!escapeHtmlOutput) {
            out.writeByte(b)
            return
        }

        when (b) {
            '&'.code -> out.writeBytes(HTML_AMP, 0, HTML_AMP.size)
            '<'.code -> out.writeBytes(HTML_LT, 0, HTML_LT.size)
            '>'.code -> out.writeBytes(HTML_GT, 0, HTML_GT.size)
            '"'.code -> out.writeBytes(HTML_QUOT, 0, HTML_QUOT.size)
            '\''.code -> out.writeBytes(HTML_APOS, 0, HTML_APOS.size)
            else -> out.writeByte(b)
        }
    }

    private fun writeMaybeEscapeString(
        out: CssOutput,
        value: String,
        escapeHtmlOutput: Boolean
    ) {
        if (!escapeHtmlOutput) {
            out.writeString(value)
            return
        }

        val bytes = value.encodeToByteArray()
        for (byte in bytes) {
            writeMaybeEscapeByte(out, byte.toInt() and 0xFF, true)
        }
    }

    private fun writeCapturedBytes(
        out: CssOutput,
        collector: ByteCollector,
        escapeHtmlOutput: Boolean
    ) {
        if (collector.size == 0) return

        if (escapeHtmlOutput) {
            for (i in 0 until collector.size) {
                writeMaybeEscapeByte(
                    out = out,
                    b = collector.bytes[i].toInt() and 0xFF,
                    escapeHtmlOutput = true
                )
            }
        } else {
            out.writeBytes(collector.bytes, 0, collector.size)
        }
    }

    private fun isUrlAttribute(attrName: String): Boolean {
        return when (attrName.lowercase()) {
            "href", "src", "poster", "data", "action", "formaction", "cite", "longdesc", "manifest", "profile", "usemap", "background", "ping" -> true
            else -> false
        }
    }

    private fun isNonResolvableUrl(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return true
        val lower = trimmed.lowercase()
        return lower.startsWith("data:") || lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith(
            "javascript:"
        ) || lower.startsWith("blob:") || lower.startsWith("about:") || lower.startsWith("file:")
    }
}