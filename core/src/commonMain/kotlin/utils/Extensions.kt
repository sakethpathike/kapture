package utils

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.writeString

fun RawSink.write(string: String) {
    val buffer = Buffer()
    buffer.writeString(string)
    this.write(source = buffer, byteCount = buffer.size)
}

fun RawSink.write(byteArray: ByteArray) {
    val buffer = byteArray.asBuffer()
    this.write(source = buffer, byteCount = buffer.size)
}

fun ByteArray.asBuffer(): Buffer {
    return Buffer().apply {
        write(
            source = this@asBuffer,
            startIndex = 0,
            endIndex = this@asBuffer.lastIndex
        )
    }
}