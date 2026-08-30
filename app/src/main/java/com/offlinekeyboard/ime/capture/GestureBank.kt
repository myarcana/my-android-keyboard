package com.offlinekeyboard.ime.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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
 * backup on purpose (this keyboard does not send anything anywhere). That made the bank
 * genuinely permanent only once it had been pulled off the device, which is a fine arrangement
 * for a rig used beside the machine that pulls it and a bad one for a phone collecting for a
 * fortnight in a pocket: the one command that makes the data durable was the one command
 * unavailable to it.
 *
 * So [mirror] keeps a second copy in the shared Downloads folder, which is outside the app's
 * sandbox: it survives uninstall, it is visible to the phone's own Files app, and it comes off
 * over USB without adb, run-as or a debuggable build. The internal file stays the master --
 * appends go there and only there -- and the mirror is rewritten from it. `tools/gestures.sh
 * pull` reads whichever copies exist and merges them by id, so no copy is authoritative and
 * none of them can lose a sample that another one has.
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

    // --- the copy that outlives the app -------------------------------------------------------

    /** Inside the shared Downloads folder, so a file browser can find it without being told. */
    private const val PUBLIC_DIR = "Download/OfflineKeyboard"

    /**
     * The mirror's name, with the extension the platform was going to add anyway.
     *
     * MediaStore reconciles the display name against the MIME type, and it has never heard of
     * `.jsonl`, so a file offered as `gesture-bank.jsonl` with `text/plain` comes back out as
     * `gesture-bank.jsonl.txt`. Naming it that up front costs nothing and means the path in this
     * file is the path on the phone -- and `.txt` is the more useful half of the bargain anyway,
     * since it is what makes the mirror open in a phone's own file browser rather than offering
     * to be handed to some app that might know what a jsonl is.
     */
    private const val PUBLIC_NAME = "$FILE_NAME.txt"

    /**
     * Rewrites the shared-storage copy from the internal one. Returns where it landed.
     *
     * Whole-file rewrite rather than append. An append into MediaStore has no way to know how
     * much of the file is already there -- the mirror can be edited, moved or deleted by the
     * person who owns the phone, and a mirror that appended blindly would silently double every
     * record the first time that happened. Rewriting is O(bank) on a file that is under a
     * megabyte for the first ten thousand gestures, on a background thread, a few times a
     * session.
     *
     * The name is read back from MediaStore rather than assumed, because the display name that
     * comes out is not always the one that went in: the platform reconciles the extension
     * against the MIME type, and a message naming a file that is not there is worse than none.
     */
    fun mirror(context: Context): String? {
        val source = file(context)
        if (!source.exists() || source.length() == 0L) return null
        val resolver = context.contentResolver
        val existing = findMirror(context)
        val write = { uri: Uri ->
            resolver.openOutputStream(uri, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } }
        }
        if (existing != null) {
            // A row can outlive the file it points at, if the copy was deleted from a file
            // browser. Falling through to a fresh insert is the repair.
            val ok = runCatching { write(existing) }.isSuccess
            if (ok) return describe(context, existing)
            runCatching { resolver.delete(existing, null, null) }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, PUBLIC_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, PUBLIC_DIR)
        }
        val uri = runCatching { resolver.insert(downloads(), values) }.getOrNull() ?: return null
        return runCatching {
            write(uri)
            describe(context, uri)
        }.getOrNull()
    }

    private fun downloads(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun findMirror(context: Context): Uri? = runCatching {
        context.contentResolver.query(
            downloads(),
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("$PUBLIC_DIR/", "gesture-bank%"),
            "${MediaStore.Downloads._ID} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                Uri.withAppendedPath(downloads(), cursor.getLong(0).toString())
            } else {
                null
            }
        }
    }.getOrNull()

    private fun describe(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) + cursor.getString(1) else null
        }
    }.getOrNull() ?: "$PUBLIC_DIR/$PUBLIC_NAME"

    /**
     * Merges another bank into this one, keyed on record id, and returns how many were new.
     *
     * This is what makes the phone able to recover on its own. A reinstall empties internal
     * storage while the Downloads copy sits there untouched, and without a way back in the only
     * route home is a cable and a laptop -- which is exactly the thing this app is supposed to
     * work without.
     *
     * Merging by id rather than replacing is the same rule the pull script follows, for the same
     * reason: two copies of this file are routinely both partly ahead of each other, and any
     * rule that picks a winner throws away whatever the loser knew.
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

    /** Counts by label, and how often the shipped heuristic agreed with the label. */
    data class Summary(
        val total: Int,
        val byIntent: Map<GestureIntent, Int>,
        val agreed: Int,
        val decided: Int,
    ) {
        val accuracy: Float get() = if (decided == 0) 0f else agreed.toFloat() / decided

        /** "12 symbol / 8 word / 6 tap", for the line under the passage. */
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
