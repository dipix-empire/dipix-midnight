package pw.dipix.midnight

import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.animation.progressAnimation
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.widgets.EmptyWidget
import com.github.ajalt.mordant.widgets.Text
import com.github.ajalt.mordant.widgets.progressLayout
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import java.io.File
import java.net.URL
import java.time.Instant
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class URLDownloadable(val url: URL) : Downloadable {
    override suspend fun download(file: File, progressCallback: (Long, Long) -> Unit): File {
        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile("downloadable-", ".midnight")
        }
        println("DEBUG: tempFile = ${tempFile.absolutePath}")
        downloadHttpClient.prepareGet(url) {
            accept(ContentType.Application.OctetStream)
            timeout {
                requestTimeoutMillis = 1.toDuration(DurationUnit.HOURS).inWholeMilliseconds
            }
        }.execute { response ->
            println("DEBUG: $url: server responded with content of ${response.contentType()}")
            val channel: ByteReadChannel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
                while (!packet.isEmpty) {
                    val bytes = packet.readBytes()
                    tempFile.appendBytes(bytes)
                    progressCallback(
                        channel.totalBytesRead,
                        response.contentLength()!!
                    )
                    delay(1L)
                }
            }
        }
        tempFile.copyTo(file, overwrite = true)
        tempFile.delete()
        return file
    }

    override fun getSizeBytes(): Long {
        return runBlocking {
            httpClient.prepareGet(url) {
                accept(ContentType.Application.OctetStream)
            }.execute {
                val len = it.contentLength()!!
                it.call.cancel("!")
                len
            }
        }
    }
}

interface Downloadable {
    suspend fun download(file: File, progressCallback: (Long, Long) -> Unit = { progress, total -> }): File
    fun getSizeBytes(): Long

    fun toTask(file: File, progressCallback: (Long, Long) -> Unit = { progress, total -> }) = DownloadTask(file, this, progressCallback)
}

data class DownloadTask(
    val file: File,
    val downloadable: Downloadable,
    val progressCallback: (Long, Long) -> Unit = { progress, total -> }
) {
    suspend fun execute() = downloadable.download(file, progressCallback)
}

suspend fun Iterable<DownloadTask>.downloadAll() = coroutineScope {
    map {
        launch {
            it.execute()
        }
    }.joinAll()
}