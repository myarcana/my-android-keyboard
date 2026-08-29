package com.offlinekeyboard.ime.capture

import android.content.Context
import com.offlinekeyboard.ime.gesture.GestureIntent
import com.offlinekeyboard.ime.gesture.GestureRecord
import com.offlinekeyboard.ime.gesture.GestureRecordCodec
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * The permanent store of labelled gestures.
 *
 * Append-only JSONL in internal storage. Append-only because the value of this file is that it
 * accumulates: a rewrite that goes wrong loses months of a person's thumb, which cannot be
 * regenerated from anything. One line per gesture means a corrupt tail costs one sample, and
 * means the file can be read by anything -- `wc -l` counts it, jq filters it, a notebook plots
 * it -- without this app being involved at all.
 *
 * Internal storage survives app updates but not uninstall, and the manifest disables cloud
 * backup on purpose (this keyboard does not send anything anywhere). So the bank is only
 * genuinely permanent once it has been pulled off the device: `tools/gestures.sh pull` does
 * that, and the copy in the repo is the archive of record.
 */
object GestureBank {

    private const val FILE_NAME = "gesture-bank.jsonl"

    /**
     * Writes are serialised onto one thread. They happen on finger-up, which is on the IME's UI
     * thread, and an input method that stutters while typing is worse than useless.
     */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gesture-bank").apply { isDaemon = true }
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * Appends one record, then forces it to disk.
     *
     * The fsync is deliberate. Data collection sessions end the way phone sessions end -- the
     * screen goes off, the app is swiped away, the battery dies -- and a page-cached line that
     * never landed is a gesture the user performed and will not perform again.
     */
    fun append(context: Context, record: GestureRecord) {
        val line = GestureRecordCodec.encode(record) + "\n"
        val target = file(context)
        writer.execute {
            runCatching {
                FileOutputStream(target, true).use { out ->
                    out.write(line.toByteArray())
                    out.flush()
                    out.fd.sync()
                }
            }
        }
    }

    /** Every readable record. Lines this build cannot parse are skipped, not fatal. */
    fun readAll(context: Context): List<GestureRecord> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            if (line.isBlank()) null else GestureRecordCodec.decode(line)
        }
    }

    /** Cheap enough to call on resume: counts lines without parsing them. */
    fun count(context: Context): Int {
        val f = file(context)
        if (!f.exists()) return 0
        return f.useLines { lines -> lines.count { it.isNotBlank() } }
    }

    /**
     * Drops the most recent record -- the one undo the lab offers, for a gesture the user knows
     * they fumbled. The only operation that is not an append, and the only one that can lose
     * data, so it rewrites through a temporary file rather than truncating in place.
     */
    fun removeLast(context: Context): GestureRecord? {
        val f = file(context)
        if (!f.exists()) return null
        val lines = f.readLines().filter { it.isNotBlank() }
        val last = lines.lastOrNull() ?: return null
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(lines.dropLast(1).joinToString("") { "$it\n" })
        if (!tmp.renameTo(f)) {
            tmp.delete()
            return null
        }
        return GestureRecordCodec.decode(last)
    }

    /**
     * Copies the bank somewhere `adb pull` can reach without run-as, for pulling from a release
     * build or a device where run-as is blocked.
     */
    fun export(context: Context): File? {
        val source = file(context)
        if (!source.exists()) return null
        val dir = context.getExternalFilesDir(null) ?: return null
        val target = File(dir, FILE_NAME)
        source.copyTo(target, overwrite = true)
        return target
    }

    /** Counts by label, and how often the shipped heuristic agreed with the label. */
    data class Summary(
        val total: Int,
        val byIntent: Map<GestureIntent, Int>,
        val agreed: Int,
        val decided: Int,
    ) {
        val accuracy: Float get() = if (decided == 0) 0f else agreed.toFloat() / decided

        /** "12 symbol / 8 word / 6 tap", for the line under the drill. */
        val breakdown: String
            get() = GestureIntent.entries.joinToString(" / ") { intent ->
                "${byIntent[intent] ?: 0} ${if (intent == GestureIntent.LETTER) "tap" else intent.name.lowercase()}"
            }
    }

    fun summarise(records: List<GestureRecord>): Summary {
        val decided = records.filter { it.verdictIntent != null }
        return Summary(
            total = records.size,
            byIntent = records.groupingBy { it.intent }.eachCount(),
            agreed = decided.count { it.verdictIntent == it.intent },
            decided = decided.size,
        )
    }
}
