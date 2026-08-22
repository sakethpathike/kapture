package io.github.sakethpathike.kapture

data class KaptureOptions(
    val includeJs: Boolean = true,
    val includeCss: Boolean = true,
    val includeImages: Boolean = true,
    val includeVideo: Boolean = true,
    val includeAudio: Boolean = true,
    val timeoutMillis: Long = 30000L,
    val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    val base64StreamSize: Int = 3000
)

typealias Options = KaptureOptions
