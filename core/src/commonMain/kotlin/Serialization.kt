package io.github.sakethpathike.kapture

import com.fleeksoft.ksoup.nodes.*
import io.ktor.utils.io.core.*
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private sealed interface WalkItem {
    data class ProcessNode(val node: Node) : WalkItem
    data class WriteCloseTag(val tagName: String) : WalkItem
}

internal class Serialization(private val options: Options) {

    fun writeBySerializing(document: Document, mediaFileMap: Map<MediaUrl, FileName>, destinationFile: RawSink) {
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
                                "script" -> if (!options.includeJs) continue
                                "noscript" -> {
                                    val children = node.childNodes()
                                    for (i in children.indices.reversed()) {
                                        stack.addLast(WalkItem.ProcessNode(children[i]))
                                    }
                                    continue
                                }

                                "style" -> if (!options.includeCss) continue
                                "link" -> if (node.attr("rel").lowercase()
                                        .contains("stylesheet") && !options.includeCss
                                ) continue

                                "img", "source", "picture" -> if (!options.includeImages) continue
                                "video" -> if (!options.includeVideo) continue
                                "audio", "track" -> if (!options.includeAudio) continue
                            }

                            if (tag == "script" || tag == "style") {
                                writeOpenTag(element = node, destinationFile, mediaFileMap)
                                val rawData = node.data()
                                if (tag == "style") {
                                    val processedCss = processCss(rawData, node.baseUri(), mediaFileMap)
                                    destinationFile.write(processedCss)
                                } else {
                                    val safeJs = rawData.replace("</script>", "<\\/script>", ignoreCase = true)
                                    destinationFile.write(safeJs)
                                }
                                destinationFile.write("</$tag>")
                                continue
                            }

                            writeOpenTag(element = node, destinationFile, mediaFileMap)
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

    private fun writeOpenTag(
        element: Element,
        destinationFile: RawSink,
        mediaFileMap: Map<String, String>,
    ) {
        val tagName = element.tagName().lowercase()

        if (tagName == "link") {
            val rel = element.attr("rel").lowercase()
            val href = element.absUrl("href").ifEmpty { element.attr("href") }

            if (rel.contains("stylesheet")) {
                val cssFilePath = mediaFileMap[href]
                if (cssFilePath != null) {
                    val rawCss = SystemFileSystem.source(Path(cssFilePath)).buffered().use { it.readString() }
                    val processedCss = processCss(rawCss, element.baseUri(), mediaFileMap)
                    destinationFile.write("<style>$processedCss</style>")
                    return
                }
            }

            if (rel.contains("icon") || rel.contains("shortcut icon")) {
                val tempFile = mediaFileMap[href]
                if (tempFile != null) {
                    val mime = getMimeType(href)
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
                writeSrcsetInline(attrValue, element.baseUri(), destinationFile, mediaFileMap)
                destinationFile.write("\"")
                return@forEach
            }

            if (attrName.equals("style", ignoreCase = true)) {
                val processedStyle = processCss(attrValue, element.baseUri(), mediaFileMap)
                destinationFile.write(" style=\"${escapeHtml(processedStyle)}\"")
                return@forEach
            }

            val resolvedUrl = if (isUrlAttribute(attrName) && !isNonResolvableUrl(attrValue)) {
                element.absUrl(attrName).ifEmpty { attrValue }
            } else {
                attrValue
            }

            val tempFileName = mediaFileMap[resolvedUrl] ?: mediaFileMap[attrValue]

            if (tempFileName != null) {
                val mime = getMimeType(resolvedUrl)
                destinationFile.write(" $attrName=\"data:$mime;base64,")
                streamBase64(options.base64StreamSize, tempFileName, destinationFile)
                destinationFile.write("\"")
            } else {
                destinationFile.write(" $attrName=\"${escapeHtml(resolvedUrl)}\"")
            }
        }

        destinationFile.write(">")
    }

    private val WHITESPACE_REGEX = Regex("\\s+")

    private fun writeSrcsetInline(
        srcset: String, baseUrl: String, destinationFile: RawSink, mediaFileMap: Map<String, String>
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
                val mime = getMimeType(absoluteUrl)
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


    private fun processCss(
        cssText: String, baseUrl: String, mediaFileMap: Map<String, String>
    ): String {
        return CSS_URL_REGEX.replace(cssText) { matchResult ->
            val quote = matchResult.groupValues[1]
            val originalUrl = matchResult.groupValues[2].trim()

            if (originalUrl.isEmpty() || isNonResolvableUrl(originalUrl)) {
                matchResult.value
            } else {
                val absoluteUrl = resolveUrl(baseUrl, originalUrl)
                val tempFile = mediaFileMap[absoluteUrl] ?: mediaFileMap[originalUrl]

                if (tempFile != null) {
                    val mime = getMimeType(absoluteUrl)
                    val bytes = SystemFileSystem.source(tempFile.toPath()).buffered().use { it.readByteArray() }

                    @OptIn(ExperimentalEncodingApi::class) "url(${quote}data:$mime;base64,${Base64.encode(bytes)}$quote)"
                } else {
                    "url($quote$absoluteUrl$quote)"
                }
            }
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