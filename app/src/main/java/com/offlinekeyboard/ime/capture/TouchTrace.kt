package com.offlinekeyboard.ime.capture

import android.content.Context
import android.os.SystemClock
import com.offlinekeyboard.ime.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * A line per touch event and per gesture output, written to a file.
 *
 * It exists because this device's logcat does not carry our app's output at all -- ColorOS drops
 * third-party app logs, so `Log.d` here is a write to nowhere and a debugging session spent
 * staring at an empty log. The bank already established that the way to get data off this phone
 * is a file in the sandbox pulled over adb, so this follows it.
 *
 * What it is for: telling apart a press the digitizer never reported from a press this code
 * received and then dropped. `getevent` answers the first half, and nothing but the view itself
 * can answer the second -- which branch of the down handler swallowed the finger.
 *
 * Debug builds only, and it truncates rather than growing without bound, because a trace at
 * touch-move rates is a megabyte every few minutes and this is a keyboard, not a flight recorder.
 */
object TouchTrace {

    private const val FILE_NAME = "touch-trace.log"
    private const val MAX_BYTES = 4L * 1024 * 1024

    val enabled = BuildConfig.DEBUG

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "touch-trace").apply { isDaemon = true }
    }

    /** Held open: a press produces tens of lines, and reopening the file for each is silly. */
    private var out: FileOutputStream? = null
    private var written = 0L

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun log(context: Context, message: String) {
        if (!enabled) return
        val at = SystemClock.uptimeMillis()
        val target = file(context)
        writer.execute {
            runCatching {
                var stream = out
                if (stream == null || written > MAX_BYTES) {
                    stream?.close()
                    stream = FileOutputStream(target, written <= MAX_BYTES)
                    out = stream
                    written = target.length()
                }
                val line = "$at $message\n".toByteArray()
                stream.write(line)
                stream.flush()
                written += line.size
            }
        }
    }
}
