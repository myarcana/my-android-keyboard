package com.offlinekeyboard.ime.capture

import android.content.SharedPreferences
import java.time.LocalDate

/**
 * How much was collected today, and how many days in a row.
 *
 * Not decoration. The bank's only real risk is not that the data is bad but that it stops
 * arriving: collection is a thing a person does voluntarily, in gaps, with no one watching, and
 * a count that only exists in a terminal on another machine is a count nobody sees. A day
 * number that goes up is the smallest thing that makes a fifteen-gesture wait for a bus worth
 * starting.
 *
 * The streak counts *days with at least one gesture in them*, and it is deliberately generous
 * about what a day is worth. A rule that demanded the daily goal would be a rule that punishes
 * a short session more than no session, and the bank would rather have the short session.
 */
object LabProgress {

    private const val KEY_DAY = "progress.day"
    private const val KEY_TODAY = "progress.today"
    private const val KEY_STREAK = "progress.streak"
    private const val KEY_BEST = "progress.best"

    /** A day's collecting, in gestures. Reachable in a few minutes; a round number to aim at. */
    const val DAILY_GOAL = 120

    data class Snapshot(val today: Int, val streak: Int, val best: Int)

    /** Counts one recorded gesture, rolling the day over first if the date has changed. */
    fun record(prefs: SharedPreferences, today: Long = LocalDate.now().toEpochDay()): Snapshot {
        val stored = prefs.getLong(KEY_DAY, 0L)
        val rolled = rollover(stored, today, prefs.getInt(KEY_STREAK, 0), prefs.getInt(KEY_TODAY, 0))
        val streak = rolled.first
        val count = rolled.second + 1
        val best = maxOf(prefs.getInt(KEY_BEST, 0), streak)
        prefs.edit()
            .putLong(KEY_DAY, today)
            .putInt(KEY_TODAY, count)
            .putInt(KEY_STREAK, streak)
            .putInt(KEY_BEST, best)
            .apply()
        return Snapshot(count, streak, best)
    }

    /**
     * The day-rollover rule, kept pure so it can be tested without a phone.
     *
     * Yesterday continues the run; any longer gap starts a new one at one, not zero -- the
     * gesture being recorded is itself the first day of the new run. A run is only ever extended
     * from here, on an actual gesture, so a day the lab was merely opened does not count.
     */
    fun rollover(stored: Long, today: Long, streak: Int, count: Int): Pair<Int, Int> = when {
        stored == today -> streak to count
        stored == today - 1 -> streak + 1 to 0
        else -> 1 to 0
    }

    /**
     * What to show before anything has been recorded today.
     *
     * A stale streak is shown as broken rather than as itself: opening the lab after a week away
     * and being told the run is still six days long is a lie the very next gesture corrects.
     */
    fun snapshot(prefs: SharedPreferences, today: Long = LocalDate.now().toEpochDay()): Snapshot {
        val stored = prefs.getLong(KEY_DAY, 0L)
        val streak = prefs.getInt(KEY_STREAK, 0)
        val best = prefs.getInt(KEY_BEST, 0)
        return when (stored) {
            today -> Snapshot(prefs.getInt(KEY_TODAY, 0), streak, best)
            today - 1 -> Snapshot(0, streak, best)
            else -> Snapshot(0, 0, best)
        }
    }
}
