package io.github.sakethpathike.kapture

import com.fleeksoft.ksoup.Ksoup
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal expect val PlatformIODispatcher: CoroutineDispatcher

object Kapture {
    private var client: HttpClient? = null
    private var options: Options? = null

    private val initMutex = Mutex()

    suspend fun init(kaptureOptions: Options) {
        initMutex.withLock {
            if (client == null && options == null) {
                options = kaptureOptions
                client = HttpClient {
                    install(HttpTimeout) {
                        requestTimeoutMillis = kaptureOptions.timeoutMillis
                        connectTimeoutMillis = kaptureOptions.timeoutMillis
                        socketTimeoutMillis = kaptureOptions.timeoutMillis
                    }
                }
            }
        }
    }

    suspend fun archive(url: String, destinationFilePath: String) {
        val currentOptions = options ?: error(INIT_REQUIRED_MSG)
        val currentClient = client ?: error(INIT_REQUIRED_MSG)

        val destinationFile = SystemFileSystem.sink(Path(destinationFilePath))

        try {
            val response = withContext(PlatformIODispatcher) {
                client?.get(url) {
                    header(key = HttpHeaders.UserAgent, value = options?.userAgent ?: error(INIT_REQUIRED_MSG))
                } ?: error(INIT_REQUIRED_MSG)
            }

            val contentType = response.contentType()?.toString()?.lowercase() ?: ""
            val isHtml = contentType.contains("html") || contentType.contains("xhtml") || contentType.contains("xml")

            if (!isHtml) {
                val mime = contentType.substringBefore(";").trim().ifEmpty { getMimeType(url) }

                if (isFontMime(mime) && !currentOptions.includeFonts) {
                    destinationFile.write("")
                    return
                }

                val bytes = response.readRawBytes()

                @OptIn(ExperimentalEncodingApi::class) val base64 = Base64.encode(bytes)

                val tag = when {
                    mime.startsWith("image/") -> "img"
                    mime.startsWith("audio/") -> "audio controls"
                    mime.startsWith("video/") -> "video controls"
                    mime == "application/pdf" -> "iframe"
                    else -> "embed"
                }

                val closeTag = tag.substringBefore(" ")

                val syntheticHtml = buildString {
                    append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head><body>")

                    when (closeTag) {
                        "img" -> {
                            append("<img src=\"data:$mime;base64,$base64\">")
                        }

                        "embed" -> {
                            append("<embed src=\"data:$mime;base64,$base64\">")
                        }

                        "iframe" -> {
                            append("<iframe src=\"data:$mime;base64,$base64\" style=\"width:100%;height:100vh;\"></iframe>")
                        }

                        else -> {
                            append("<$tag src=\"data:$mime;base64,$base64\"></$closeTag>")
                        }
                    }

                    append("</body></html>")
                }

                destinationFile.write(syntheticHtml)
                return
            }

            val htmlString = response.bodyAsText()
            val ksoupDoc = Ksoup.parse(htmlString, url)
            ksoupDoc.setBaseUri(url)

            val mediaManager = MediaManager(
                document = ksoupDoc, httpClient = currentClient, options = currentOptions
            )

            try {
                val mediaResult = mediaManager.downloadMediaToTempFiles()
                Serialization(options = currentOptions).writeBySerializing(
                    document = ksoupDoc,
                    mediaFileMap = mediaResult.files,
                    mimeMap = mediaResult.mimes,
                    destinationFile = destinationFile
                )
            } finally {
                mediaManager.cleanup()
            }
        } finally {
            destinationFile.close()
        }
    }
}
