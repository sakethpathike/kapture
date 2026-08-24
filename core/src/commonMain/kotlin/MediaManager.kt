package io.github.sakethpathike.kapture

import at.released.tempfolder.sync.createTempDirectory
import com.fleeksoft.ksoup.nodes.DataNode
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
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

internal class DownloadedMedia(
    val files: HashMap<MediaUrl, FileName>, val mimes: HashMap<MediaUrl, String>
)

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

    suspend fun downloadMediaToTempFiles(): DownloadedMedia = withContext(PlatformIODispatcher) {
        val fileMediaMap = HashMap<MediaUrl, FileName>()
        val mimeMap = HashMap<MediaUrl, String>()
        val queue = ArrayDeque<String>().apply { addAll(urls) }
        val visited = mutableSetOf<String>().apply { addAll(urls) }
        val basePath = tempDirectory.absolutePath().asString()

        val documentBaseUrl = document.baseUri()

        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            @OptIn(ExperimentalUuidApi::class) val opName = Uuid.random().toHexString()
            val filePathString = "$basePath/$opName"
            val filePath = Path(filePathString)
            val tempMediaFile = SystemFileSystem.sink(filePath).buffered()
            var downloadSucceeded = false

            try {
                val response = httpClient.get(urlString = url) {
                    header(HttpHeaders.UserAgent, options.userAgent)
                    header(HttpHeaders.Accept, "*/*")
                    if (documentBaseUrl.isNotBlank()) {
                        header(HttpHeaders.Referrer, documentBaseUrl)
                    }

                    val isCss = url in cssUrls || url.substringBefore('?').substringBefore('#').endsWith(".css", ignoreCase = true)
                    header("Sec-Fetch-Dest", if (isCss) "style" else "font")
                    header("Sec-Fetch-Mode", "cors")
                    header("Sec-Fetch-Site", "cross-site")
                }

                if (response.status.value in 200..299) {
                    val responseMime =
                        response.contentType()?.toString()?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                    val effectiveMime = responseMime.ifEmpty { getMimeType(url) }

                    if (shouldDownloadResourceByMime(effectiveMime, options)) {
                        response.bodyAsChannel().copyTo(tempMediaFile.asByteWriteChannel())
                        mimeMap[url] = effectiveMime
                        downloadSucceeded = true
                        if (effectiveMime == "text/css") {
                            cssUrls.add(url)
                        }
                    }
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
                    addCssResourceUrls(
                        cssText = cssText, baseUrl = url, visited = visited, queue = queue
                    )
                } catch (_: Exception) {
                }
            }
        }
        return@withContext DownloadedMedia(
            files = fileMediaMap, mimes = mimeMap
        )
    }

    private fun loadUrlsFromDocument() {
        val mediaElements = document.select(
            "img[src], img[srcset], source[src], source[srcset], video[src], " +
                    "video[poster], audio[src], track[src], embed[src], object[data], link[href]"
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
                    val asAttr = mediaElement.attr("as").lowercase()
                    val href = mediaElement.absUrl("href").ifEmpty { mediaElement.attr("href") }

                    when {
                        rel.contains("stylesheet") -> {
                            if (options.includeCss && addUrl(href)) {
                                cssUrls.add(href.trim())
                            }
                        }

                        rel.contains("icon") -> {
                            if (options.includeImages) {
                                addUrl(href)
                            }
                        }

                        asAttr == "font" || isFontUrl(href) -> {
                            if (options.includeFonts) {
                                addUrl(href)
                            }
                        }
                    }
                }
            }
        }

        if (options.includeCss) {
            document.select("style").forEach { styleElement ->
                val cssText = styleElement.childNodes().filterIsInstance<DataNode>().joinToString("") { it.getWholeData() }
                if (cssText.isNotBlank()) {
                    addCssResourceUrls(
                        cssText = cssText,
                        baseUrl = styleElement.baseUri(),
                        visited = urls
                    )
                }
            }
        }
    }

    private fun addUrl(raw: String?): Boolean {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return false
        if (url.startsWith("#")) return false
        if (url.startsWith("data:", ignoreCase = true)) return false
        if (url.startsWith("blob:", ignoreCase = true)) return false
        if (url.startsWith("javascript:", ignoreCase = true)) return false
        if (url.startsWith("mailto:", ignoreCase = true)) return false
        if (url.startsWith("tel:", ignoreCase = true)) return false

        urls.add(url)
        return true
    }

    private fun addCssResourceUrls(
        cssText: String,
        baseUrl: String,
        visited: MutableSet<String>,
        queue: ArrayDeque<String>? = null
    ) {
        CSS_URL_REGEX.findAll(cssText).forEach { match ->
            val rawUrl = match.groupValues[2].trim()
            addCssResourceUrl(
                rawUrl = rawUrl,
                baseUrl = baseUrl,
                visited = visited,
                queue = queue,
                forceCss = false
            )
        }

        CSS_IMPORT_REGEX.findAll(cssText).forEach { match ->
            val rawUrl = match.groupValues[2].ifEmpty { match.groupValues[4] }.trim()
            addCssResourceUrl(
                rawUrl = rawUrl,
                baseUrl = baseUrl,
                visited = visited,
                queue = queue,
                forceCss = true
            )
        }
    }

    private fun addCssResourceUrl(
        rawUrl: String,
        baseUrl: String,
        visited: MutableSet<String>,
        queue: ArrayDeque<String>?,
        forceCss: Boolean
    ) {
        if (rawUrl.isEmpty()) return
        if (rawUrl.startsWith("#")) return
        if (rawUrl.startsWith("data:", ignoreCase = true)) return
        if (rawUrl.startsWith("blob:", ignoreCase = true)) return
        if (rawUrl.startsWith("javascript:", ignoreCase = true)) return

        val absoluteUrl = resolveUrl(baseUrl, rawUrl)

        if (forceCss) {
            if (!options.includeCss) return
        } else {
            if (!shouldDownloadCssResource(absoluteUrl, options)) return
        }

        if (forceCss) {
            cssUrls.add(absoluteUrl)
        }

        if (visited.add(absoluteUrl)) {
            queue?.addLast(absoluteUrl)

            if (!forceCss) {
                val pathWithoutQuery = absoluteUrl
                    .substringBefore('?')
                    .substringBefore('#')

                if (pathWithoutQuery.substringAfterLast('.', "").lowercase() == "css") {
                    cssUrls.add(absoluteUrl)
                }
            }
        }
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