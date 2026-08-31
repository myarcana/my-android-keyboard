package com.offlinekeyboard.ime.capture

import android.content.Context
import com.offlinekeyboard.ime.gesture.GestureIntent
import com.offlinekeyboard.ime.gesture.GestureRecord
import com.offlinekeyboard.ime.gesture.GestureRecordCodec
import com.offlinekeyboard.ime.gesture.GestureSession
import com.offlinekeyboard.ime.gesture.GestureSessionCodec
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
 * backup on purpose (this keyboard does not send anything anywhere). The file stays inside the
 * sandbox: a keyboard that writes what was typed into shared storage, where every app with
 * storage access can read it, would be a strange thing for one built to be incapable of talking
 * to the network. So the bank is only genuinely permanent once it has been pulled off the
 * device: `tools/gestures.sh pull` does that over adb, and the copy in the repo is the archive
 * of record.
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
    fun append(context: Context, record: GestureRecord) = appendLine(context, GestureRecordCodec.encode(record))

    /** The transcript header for a run: what was asked for, and what came out. */
    fun appendSession(context: Context, session: GestureSession) =
        appendLine(context, GestureSessionCodec.encode(session))

    private fun appendLine(context: Context, encoded: String) {
        val line = encoded + "\n"
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

    /**
     * Withdraws the label on the most recent record without deleting the recording.
     *
     * The distinction this preserves is the one the bank is least able to recover later. A
     * gesture the keyboard read *wrongly* is the most valuable line in the file. A gesture whose
     * label is *untrue* -- the flick the hand began, thought better of and came back from, while
     * the passage was asking for a tap -- is worth less than nothing, because the only way to
     * score it as labelled is to drag a threshold somewhere it should not go.
     *
     * They are indistinguishable in the file and trivially distinguishable to the person who
     * just made the gesture, for about two seconds. Before this, saying so meant remembering it
     * for a week and then editing a JSONL file on a laptop, which means it was never said.
     */
    fun voidLast(context: Context, reason: String): GestureRecord? {
        val f = file(context)
        if (!f.exists()) return null
        val lines = f.readLines().filter { it.isNotBlank() }
        val last = lines.lastOrNull() ?: return null
        val record = GestureRecordCodec.decode(last) ?: return null
        if (record.voidReason != null) return record
        val withdrawn = record.copy(voidReason = reason)
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(
            (lines.dropLast(1) + GestureRecordCodec.encode(withdrawn)).joinToString("") { "$it\n" },
        )
        if (!tmp.renameTo(f)) {
            tmp.delete()
            return null
        }
        return withdrawn
    }

    /**
     * Merges another bank into this one, keyed on record id, and returns how many were new.
     *
     * The way back in after an uninstall, which is the one event internal storage does not
     * survive: the archive is handed back through the system file picker, and merges into
     * whatever is here rather than replacing it.
     *
     * Merging by id is the same rule the pull script follows, for the same reason: two copies of
     * this file are routinely both partly ahead of each other, and any rule that picks a whole
     * winner throws away whatever the loser knew.
     */
    fun merge(context: Context, lines: Sequence<String>): Int {
        val known = readAll(context).map { it.id }.toHashSet()
        var added = 0
        val builder = StringBuilder()
        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            val record = GestureRecordCodec.decode(line) ?: return@forEach
            if (!known.add(record.id)) return@forEach
            builder.append(GestureRecordCodec.encode(record)).append('\n')
            added++
        }
        if (added == 0) return 0
        FileOutputStream(file(context), true).use { out ->
            out.write(builder.toString().toByteArray())
            out.flush()
            out.fd.sync()
        }
        return added
    }

    /** What is in the file, for the line under the passage. */
    data class Summary(val total: Int, val runs: Int) {

        /** "2670 gestures in 43 runs", which is all a collector needs to see. */
        val breakdown: String get() = "$total in $runs runs"
    }

    /**
     * Counts the bank for the line under the passage.
     *
     * It used to break the count down by label and report how often the shipped heuristic had
     * agreed with one. Both were removed rather than repaired: the labels they read were written
     * at the moment of the gesture, when what the gesture meant was not yet knowable, and a
     * figure computed from them moved for reasons that had nothing to do with the keyboard --
     * it fell from 92% to 61% the day prose passages started collecting taps, on a heuristic
     * that had not changed a line.
     *
     * What is left is a count, which is the number a collector actually acts on. Whether the
     * heuristic is any good is a question for the bank as a whole, asked on a laptop, against
     * every path at once -- not a running total on a phone that has to be recomputed after
     * every tap.
     */
    fun summarise(records: List<GestureRecord>): Summary = Summary(
        total = records.size,
        runs = records.mapNotNull { it.sessionId }.distinct().size,
    )
}
