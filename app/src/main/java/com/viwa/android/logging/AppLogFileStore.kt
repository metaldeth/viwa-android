package com.viwa.android.logging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * Rotating on-device log files under [filesDir]/logs/ for Timber capture and REST shipping.
 * Thread-safe; ship cursor avoids re-upload and supports concurrent writes during upload.
 */
@Singleton
class AppLogFileStore
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    private val logDir = File(context.filesDir, "logs").apply { mkdirs() }
    private val activeFile = File(logDir, "active.log")
    private val rolled1File = File(logDir, "rolled-1.log")
    private val rolled2File = File(logDir, "rolled-2.log")
    private val cursorFile = File(logDir, "ship.cursor")

    private val lock = java.util.concurrent.locks.ReentrantLock()

    data class ShipSnapshot(
        val gzipBytes: ByteArray,
        val shippedByteCount: Int,
        val periodStart: String,
        val periodEnd: String,
    )

    fun appendLine(line: String) {
        val payload = (line + '\n').toByteArray(Charsets.UTF_8)
        lock.withLock {
            activeFile.appendBytes(payload)
            if (activeFile.length() > MAX_FILE_BYTES) {
                rotateActive()
            }
        }
    }

    fun hasPendingContent(): Boolean =
        lock.withLock {
            val (fileName, offset) = readCursor()
            val files = orderedLogFiles()
            val startIdx = files.indexOfFirst { it.name == fileName }.let { if (it < 0) 0 else it }
            for (i in startIdx until files.size) {
                val file = files[i]
                val from = if (i == startIdx) offset else 0L
                if (file.length() > from) return true
            }
            false
        }

    fun prepareShipSnapshot(): ShipSnapshot? =
        lock.withLock {
            val raw = readPendingBytes()
            if (raw.isEmpty()) return null
            val lines = raw.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
            val periodStart = parseLineTimestamp(lines.firstOrNull()) ?: Instant.now().toString()
            val periodEnd = parseLineTimestamp(lines.lastOrNull()) ?: periodStart
            val gzipBytes = gzip(raw)
            ShipSnapshot(
                gzipBytes = gzipBytes,
                shippedByteCount = raw.size,
                periodStart = periodStart,
                periodEnd = periodEnd,
            )
        }

    fun commitShip(shippedByteCount: Int) {
        if (shippedByteCount <= 0) return
        lock.withLock {
            advanceCursorBy(shippedByteCount)
            compactShippedFiles()
        }
    }

    private fun rotateActive() {
        val (cursorName, cursorOffset) = readCursor()

        if (rolled2File.exists()) {
            rolled2File.delete()
            if (cursorName == rolled2File.name) {
                writeCursor(
                    if (rolled1File.exists()) rolled1File.name else activeFile.name,
                    0L,
                )
            }
        }

        if (rolled1File.exists()) {
            rolled1File.renameTo(rolled2File)
            if (cursorName == rolled1File.name) {
                writeCursor(rolled2File.name, cursorOffset)
            }
        }

        if (activeFile.exists() && activeFile.length() > 0) {
            activeFile.renameTo(rolled1File)
            if (cursorName == activeFile.name) {
                writeCursor(rolled1File.name, cursorOffset)
            }
        }

        activeFile.writeBytes(byteArrayOf())
    }

    private fun orderedLogFiles(): List<File> =
        listOf(rolled2File, rolled1File, activeFile).filter { it.exists() }

    private fun readPendingBytes(): ByteArray {
        val (cursorName, cursorOffset) = readCursor()
        val files = orderedLogFiles()
        if (files.isEmpty()) return byteArrayOf()
        val startIdx = files.indexOfFirst { it.name == cursorName }.let { if (it < 0) 0 else it }
        val out = ByteArrayOutputStream()
        for (i in startIdx until files.size) {
            val file = files[i]
            val from = if (i == startIdx) cursorOffset else 0L
            val length = file.length() - from
            if (length <= 0) continue
            file.inputStream().use { input ->
                input.skip(from)
                copyBytes(input, out, length)
            }
        }
        return out.toByteArray()
    }

    private fun advanceCursorBy(byteCount: Int) {
        var remaining = byteCount
        var (cursorName, cursorOffset) = readCursor()
        val files = orderedLogFiles()
        val startIdx = files.indexOfFirst { it.name == cursorName }.let { if (it < 0) 0 else it }
        for (i in startIdx until files.size) {
            if (remaining <= 0) break
            val file = files[i]
            val from = if (i == startIdx) cursorOffset else 0L
            val available = (file.length() - from).toInt()
            if (available <= 0) continue
            if (remaining >= available) {
                remaining -= available
                cursorName = file.name
                cursorOffset = file.length()
            } else {
                cursorName = file.name
                cursorOffset = from + remaining
                remaining = 0
            }
        }
        writeCursor(cursorName, cursorOffset)
    }

    private fun compactShippedFiles() {
        val files = orderedLogFiles()
        for (file in files) {
            val (cursorName, cursorOffset) = readCursor()
            if (file.name != cursorName) continue
            if (cursorOffset >= file.length() && file.name != activeFile.name) {
                file.delete()
                val next =
                    when (file.name) {
                        rolled2File.name -> rolled1File.takeIf { it.exists() }
                        rolled1File.name -> activeFile
                        else -> null
                    }
                writeCursor(next?.name ?: activeFile.name, 0L)
            } else if (file.name == activeFile.name && cursorOffset > 0 && cursorOffset >= file.length()) {
                activeFile.writeBytes(byteArrayOf())
                writeCursor(activeFile.name, 0L)
            } else if (file.name == activeFile.name && cursorOffset > 0 && cursorOffset < file.length()) {
                truncateActivePrefix(cursorOffset)
                writeCursor(activeFile.name, 0L)
            }
        }
    }

    private fun truncateActivePrefix(bytesToRemove: Long) {
        if (bytesToRemove <= 0) return
        val tail = ByteArrayOutputStream()
        activeFile.inputStream().use { input ->
            input.skip(bytesToRemove)
            input.copyTo(tail)
        }
        activeFile.writeBytes(tail.toByteArray())
    }

    private fun readCursor(): Pair<String, Long> {
        if (!cursorFile.exists()) return activeFile.name to 0L
        val line = cursorFile.readText(Charsets.UTF_8).trim()
        if (line.isBlank()) return activeFile.name to 0L
        val parts = line.split(':', limit = 2)
        val name = parts.firstOrNull()?.trim().orEmpty().ifBlank { activeFile.name }
        val offset = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
        return name to offset.coerceAtLeast(0L)
    }

    private fun writeCursor(fileName: String, offset: Long) {
        cursorFile.writeText("$fileName:$offset", Charsets.UTF_8)
    }

    private fun parseLineTimestamp(line: String?): String? {
        if (line.isNullOrBlank()) return null
        val token = line.substringBefore(' ').trim()
        return try {
            Instant.parse(token).toString()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size)
        GZIPOutputStream(out).use { gzip -> gzip.write(raw) }
        return out.toByteArray()
    }

    private fun copyBytes(
        input: java.io.InputStream,
        out: ByteArrayOutputStream,
        byteCount: Long,
    ) {
        val buffer = ByteArray(8192)
        var remaining = byteCount
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    }
}
