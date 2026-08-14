package io.github.sakethpathike.kapture

import at.released.tempfolder.sync.createTempDirectory
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal typealias FileName = String
internal typealias MediaUrl = String

internal class MediaManager(
    private val document: Document, private val httpClient: HttpClient, private val options: Options
) {
    private val urls = mutableSetOf<String>()
    private val tempDirectory = createTempDirectory().apply {
        deleteOnClose = true
    }
    private val cssUrls = mutableSetOf<String>()

    init {
        loadUrlsFromDocument()
    }

    suspend fun downloadMediaToTempFiles(): HashMap<MediaUrl, FileName> = withContext(PlatformIODispatcher) {
        val fileMediaMap = HashMap<MediaUrl, FileName>()
        val queue = ArrayDeque<String>().apply { addAll(urls) }
        val visited = mutableSetOf<String>().apply { addAll(urls) }
        val basePath = tempDirectory.absolutePath().asString()

        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()

            @OptIn(ExperimentalUuidApi::class)
            val opName = Uuid.random().toHexString()

            val filePathString = "$basePath/$opName"
            val filePath = Path(filePathString)

            val tempMediaFile = SystemFileSystem.sink(filePath).buffered()
            var downloadSucceeded = false

            try {
                val response = httpClient.get(urlString = url)
                if (response.status.value in 200..299) {
                    response.bodyAsChannel().copyTo(tempMediaFile.asByteWriteChannel())
                    downloadSucceeded = true
                }
            } catch (_: Exception) {
            } finally {
                tempMediaFile.close()
            }

            if (!downloadSucceeded) {
                runCatching { SystemFileSystem.delete(filePath) }
                continue
            }

            fileMediaMap[url] = filePathString

            if (url in cssUrls) {
                try {
                    val cssText = SystemFileSystem.source(filePath).buffered().use { it.readString() }
                    CSS_URL_REGEX.findAll(cssText).forEach { match ->
                        val rawUrl = match.groupValues[2].trim()
                        if (rawUrl.isNotEmpty() && !rawUrl.startsWith("data:") && !rawUrl.startsWith("#")) {
                            val absoluteUrl = resolveUrl(url, rawUrl)
                            if (visited.add(absoluteUrl)) {
                                queue.addLast(absoluteUrl)
                                val pathWithoutQuery = absoluteUrl.substringBefore('?').substringBefore('#')
                                if (pathWithoutQuery.substringAfterLast('.', "").lowercase() == "css") {
                                    cssUrls.add(absoluteUrl)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return@withContext fileMediaMap
    }

    private fun loadUrlsFromDocument() {
        val mediaElements = document.select(
            "img[src], img[srcset], source[src], source[srcset], video[src], " + "video[poster], audio[src], track[src], embed[src], object[data], link[href]"
        )
        mediaElements.forEach { mediaElement ->
            when (mediaElement.tagName().lowercase()) {
                "img", "source" -> {
                    if (options.includeImages) {
                        addAttribute(mediaElement, "src")
                        addSrcset(mediaElement)
                    }
                }

                "video" -> {
                    if (options.includeVideo) {
                        addAttribute(mediaElement, "src")
                        addAttribute(mediaElement, "poster")
                    }
                }

                "audio", "track", "embed" -> {
                    if (options.includeAudio) {
                        addAttribute(mediaElement, "src")
                    }
                }

                "object" -> {
                    addAttribute(mediaElement, "data")
                }

                "link" -> {
                    val rel = mediaElement.attr("rel").lowercase()
                    val href = mediaElement.absUrl("href").ifEmpty { mediaElement.attr("href") }
                    if (rel.contains("stylesheet")) {
                        if (options.includeCss) {
                            addUrl(href)
                            cssUrls.add(href)
                        }
                    } else if (rel.contains("icon")) {
                        if (options.includeImages) {
                            addUrl(href)
                        }
                    }
                }
            }
        }
    }

    private fun addUrl(raw: String?) {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return
        if (url.startsWith("#")) return
        if (url.startsWith("data:", ignoreCase = true)) return
        if (url.startsWith("blob:", ignoreCase = true)) return
        if (url.startsWith("javascript:", ignoreCase = true)) return
        if (url.startsWith("mailto:", ignoreCase = true)) return
        if (url.startsWith("tel:", ignoreCase = true)) return
        urls.add(url)
    }

    private fun addAttribute(element: Element, attr: String) {
        val absolute = element.absUrl(attr)
        if (absolute.isNotBlank()) {
            addUrl(absolute)
        } else {
            addUrl(element.attr(attr))
        }
    }

    private fun addSrcset(element: Element) {
        val srcset = element.attr("srcset")
        if (srcset.isBlank()) return
        val baseUrl = element.baseUri()
        srcset.split(",").forEach { candidate ->
            val trimmedCandidate = candidate.trim()
            if (trimmedCandidate.isEmpty()) return@forEach
            val rawUrl = trimmedCandidate.split(Regex("\\s+")).firstOrNull()?.trim()
            if (!rawUrl.isNullOrEmpty()) {
                addUrl(resolveUrl(baseUrl, rawUrl))
            }
        }
    }

    suspend fun cleanup() = withContext(PlatformIODispatcher) {
        tempDirectory.close()
    }
}