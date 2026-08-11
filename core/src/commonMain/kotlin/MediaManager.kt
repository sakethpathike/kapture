import at.released.tempfolder.sync.createTempDirectory
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.uuid.Uuid

internal typealias FileName = String
internal typealias MediaUrl = String

private val tempDirectory = createTempDirectory()

internal class MediaManager(private val document: Document, private val httpClient: HttpClient) {
    private val urls = mutableSetOf<String>()

    init {
        loadUrlsFromDocument()
    }

    suspend fun downloadMediaToTempFiles(): HashMap<FileName, MediaUrl> = withContext(PlatformIODispatcher) {
        val fileMediaMap = HashMap<FileName, MediaUrl>()
        urls.forEach { url ->
            val opName = Uuid.random().toHexString()
            val filePath = Path(base = tempDirectory.absolutePath().asString(), opName)
            fileMediaMap[url] = tempDirectory.absolutePath().asString() + "/$opName"

            val tempMediaFile = SystemFileSystem.sink(filePath).buffered()
            httpClient.get(urlString = url).bodyAsChannel().copyTo(tempMediaFile.asByteWriteChannel())
            tempMediaFile.close()
        }

        return@withContext fileMediaMap
    }

    private fun loadUrlsFromDocument() {
        val mediaElements = document.select(
            "img[src], img[srcset], source[src], source[srcset], video[src], " +
                    "video[poster], audio[src], track[src], embed[src], object[data]"
        )

        mediaElements.forEach { mediaElement ->
            when (mediaElement.tagName().lowercase()) {
                "img", "source" -> {
                    addAttribute(mediaElement, "src")
                    addSrcset(mediaElement)
                }

                "video" -> {
                    addAttribute(mediaElement, "src")
                    addAttribute(mediaElement, "poster")
                }

                "audio", "track", "embed" -> {
                    addAttribute(mediaElement, "src")
                }

                "object" -> {
                    addAttribute(mediaElement, "data")
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

        // srcset="small.jpg 480w, medium.jpg 800w, large.jpg 1200w"
        srcset.split(",").forEach { candidate ->
            val trimmedCandidate = candidate.trim()
            if (trimmedCandidate.isEmpty()) return@forEach

            val url = trimmedCandidate
                .split(Regex("\\s+"))
                .firstOrNull()

            addUrl(url)
        }
    }
}