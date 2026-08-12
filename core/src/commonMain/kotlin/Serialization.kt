import com.fleeksoft.io.kotlinx.asInputStream
import com.fleeksoft.ksoup.nodes.*
import kotlinx.io.RawSink
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import utils.write
import kotlin.io.encoding.Base64

private sealed interface WalkItem {
    data class ProcessNode(val node: Node) : WalkItem
    data class WriteCloseTag(val tagName: String) : WalkItem
}

internal class Serialization {

    /**
    serializes the media into base64 if necessary and writes to the disk, this function doesn't close the [destinationFile].
     */
    fun writeBySerializing(document: Document, mediaFileMap: Map<MediaUrl, FileName>, destinationFile: RawSink) {
        val stack = ArrayDeque<WalkItem>()
        stack.addLast(WalkItem.ProcessNode(document))

        // doesnt have nested stuff
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
                        is Element -> {
                            writeOpenTag(element = node, destinationFile, mediaFileMap)
                            if (node.tagName().lowercase() !in voidElements) {
                                // since stack is FILO, we will meet this after children
                                stack.addLast(WalkItem.WriteCloseTag(node.tagName()))
                                val children = node.childNodes()

                                // insert in reverse, so we will walk through in sequence when popping
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
                            destinationFile.write(node.getWholeData())
                        }

                        is Comment -> {
                            destinationFile.write("<!--${node.getData()}-->")
                        }

                        is DocumentType -> {
                            destinationFile.write("<!DOCTYPE ${node.attr("name")}>")
                        }

                        is Document -> {
                            val children = node.childNodes()
                            for (i in children.indices.reversed()) {
                                stack.addLast(WalkItem.ProcessNode(children[i]))
                            }
                        }

                        else -> {
                            TODO(
                                """
                            Attribute
                            Attributes
                            CDataNode
                            DocumentType
                            Entities
                            EntitiesData
                            FormElement
                            LeafNode
                            Node
                            NodeIterator
                            NodeUtils
                            Printer
                            PseudoTextElement
                            Range
                            TagSet
                            XmlDeclaration
                            """.trimIndent()
                            )
                        }
                    }
                }

                is WalkItem.WriteCloseTag -> {
                    // no more children(s), so we just close the goddamn tag
                    destinationFile.write("</${item.tagName}>")
                }
            }
        }
        destinationFile.flush()
    }


    private fun writeOpenTag(element: Element, destinationFile: RawSink, mediaFileMap: Map<String, String>) {
        destinationFile.write("<${element.tagName()}")

        element.attributes().forEach { (attrName, attrValue) ->
            //  srcset contains multiple URLs and descriptors, I hate this HTML thing already
            if (attrName.equals("srcset", ignoreCase = true)) {
                destinationFile.write(" srcset=\"")
                writeSrcsetInline(attrValue.toString(), destinationFile, mediaFileMap)
                destinationFile.write("\"")
                return@forEach
            }

            // absUrl to resolve relative URLs against the page base URI
            // fallbacks to raw value if absUrl is empty
            val absoluteUrl = element.absUrl(attrName).ifEmpty { attrValue }
            val tempFileName = mediaFileMap[absoluteUrl]

            if (tempFileName != null && isMediaAttribute(element.tagName(), attrName)) {
                val mime = getMimeType(tempFileName)
                destinationFile.write(" $attrName=\"data:$mime;base64,")
                writeBase64(tempFileName, destinationFile)
                destinationFile.write("\"")
            } else {
                destinationFile.write(" $attrName=\"${escapeHtml(attrValue.toString())}\"")
            }
        }
        destinationFile.write(">")
    }

    private fun isMediaAttribute(tagName: String, attrName: String): Boolean {
        val tag = tagName.lowercase()
        val attr = attrName.lowercase()
        return when (attr) {
            "src" if tag in setOf("img", "source", "video", "audio", "track", "embed", "iframe", "script") -> true
            "poster" if tag == "video" -> true
            "data" if tag == "object" -> true
            else -> false
        }
    }

    private fun writeSrcsetInline(srcset: String, destinationFile: RawSink, mediaFileMap: Map<String, String>) {
        val candidates = srcset.split(",")
        candidates.forEachIndexed { index, candidate ->
            if (index > 0) destinationFile.write(", ")

            val parts = candidate.trim().split(Regex("\\s+"))
            if (parts.isEmpty() || parts[0].isEmpty()) {
                destinationFile.write(candidate.trim())
                return@forEachIndexed
            }

            val url = parts[0]
            val descriptor = parts.drop(1).joinToString(" ")

            val tempFileName = mediaFileMap[url]
            if (tempFileName != null) {
                val mime = getMimeType(tempFileName)
                destinationFile.write("data:$mime;base64,")
                writeBase64(tempFileName, destinationFile)
                if (descriptor.isNotEmpty()) {
                    destinationFile.write(" $descriptor")
                }
            } else {
                destinationFile.write(candidate.trim())
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

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()

        // yoinked from Y2Z/monlith
        return when (ext) {
            // Images
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"

            // Video
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "ogv" -> "video/ogg"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"

            // Audio
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"

            // Fonts
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "eot" -> "application/vnd.ms-fontobject"

            // Web / Text
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "text/javascript"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "txt" -> "text/plain"

            // Other
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "wasm" -> "application/wasm"

            else -> "application/octet-stream"
        }
    }

    private fun writeBase64(tempFileName: String, destinationFile: RawSink) {
        val base64String = SystemFileSystem.source(Path(tempFileName)).asInputStream().use {
            Base64.encode(
                source = it.readAllBytes(),
            )
        }
        destinationFile.write(base64String)
    }
}