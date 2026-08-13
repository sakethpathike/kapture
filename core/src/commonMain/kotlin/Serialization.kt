package io.github.sakethpathike.kapture

import com.fleeksoft.ksoup.nodes.*
import kotlinx.io.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private sealed interface WalkItem {
    data class ProcessNode(val node: Node) : WalkItem
    data class WriteCloseTag(val tagName: String) : WalkItem
}

internal class Serialization(private val options: Kapture.Options) {

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

                            if (tag == "script" && !options.includeJs) continue
                            if (tag == "noscript" && !options.includeJs) {
                                val children = node.childNodes()
                                for (i in children.indices.reversed()) {
                                    stack.addLast(WalkItem.ProcessNode(children[i]))
                                }
                                continue
                            }
                            if (tag == "style" && !options.includeCss) continue
                            if (tag == "link" && node.attr("rel").lowercase()
                                    .contains("stylesheet") && !options.includeCss
                            ) continue
                            if (tag in setOf("img", "source", "picture") && !options.includeImages) continue
                            if (tag == "video" && !options.includeVideo) continue
                            if (tag in setOf("audio", "track") && !options.includeAudio) continue

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

    private fun writeSrcsetInline(
        srcset: String, baseUrl: String, destinationFile: RawSink, mediaFileMap: Map<String, String>
    ) {
        val candidates = srcset.split(",")

        candidates.forEachIndexed { index, candidate ->
            if (index > 0) destinationFile.write(", ")

            val trimmedCandidate = candidate.trim()
            val parts = trimmedCandidate.split(Regex("\\s+"))

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

    @OptIn(ExperimentalEncodingApi::class)
    private fun streamBase64(streamSize: Int, tempFileName: String, destinationFile: RawSink) {
        // streamSize must be a multiple of 3 to prevent padding
        // good stuff: https://stackoverflow.com/questions/4080988/why-does-base64-encoding-require-padding-if-the-input-length-is-not-divisible-by
        require(streamSize % 3 == 0) {
            "streamSize must be multiple of 3"
        }

        SystemFileSystem.source(tempFileName.toPath()).buffered().use { source ->
            // we dont use .use here. we dont want to close the underlying destinationFile
            val sink = destinationFile.buffered()

            while (source.request(streamSize.toLong())) {
                val chunkBytes = source.readByteArray(streamSize)
                sink.writeString(Base64.encode(chunkBytes))
            }

            val remainingBytes = source.readByteArray()
            if (remainingBytes.isNotEmpty()) {
                sink.writeString(Base64.encode(remainingBytes))
            }

            sink.flush()
        }
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

                    @OptIn(ExperimentalEncodingApi::class)
                    "url(${quote}data:$mime;base64,${Base64.encode(bytes)}$quote)"
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