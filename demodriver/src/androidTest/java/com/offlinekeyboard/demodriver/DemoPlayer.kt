package com.offlinekeyboard.demodriver

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Types on the keyboard from a script, so a demo can be recorded without a thumb.
 *
 * Step two of the pipeline in `tools/demo/`. It replays a *plan*: a flat list of pointer samples
 * with the millisecond each one is due, built on the host by `tools/demo/plan.py` from the key
 * rectangles `DemoGeometry` measured. Everything shaped -- where a glide curves, how it speeds up
 * and slows down, how much scatter a tap gets -- is decided there, where changing it costs a file
 * save rather than a Gradle build and an install.
 *
 * **Why instrumentation.** Injecting touches into another app's window needs `INJECT_EVENTS`,
 * which no ordinary app may hold and which `UiAutomation` has. `adb shell input` has the
 * permission but not the resolution: one pointer, straight lines, no timing control, so a glide
 * through it travels a chord at constant speed and decodes like nothing a hand has produced.
 *
 * **Why it injects and does nothing else.** It touches the screen and writes a log. It does not
 * open the test pad, focus a field or raise the keyboard: the screen is already set up before the
 * recording starts, by `tools/demo/demo.sh`. That division is the whole reason this lives in its
 * own empty app -- see `build.gradle.kts`. Every arrangement where the injector also set the
 * screen up failed, because instrumentation kills the app it targets: from the keyboard's package
 * it closed the keyboard, and from the test pad's it restarted the test pad, so the first
 * gestures of a take landed on the launcher. Once.
 */
@RunWith(AndroidJUnit4::class)
class DemoPlayer {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val args: Bundle = InstrumentationRegistry.getArguments()
    private val dir = File(instrumentation.targetContext.filesDir, "demo").apply { mkdirs() }

    @Test
    fun play() {
        val plan = JSONObject(File(dir, args.getString("plan") ?: "plan.json").readText())
        val samples = plan.getJSONArray("samples")
        val log = StringBuilder()

        // The plan's clock starts at zero; the device's does not. Everything below is scheduled
        // against this one instant, so a slow dispatch delays the next event rather than
        // compressing the gesture that follows it.
        val origin = SystemClock.uptimeMillis() + 120
        var downTime = origin

        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            val due = origin + sample.getLong("t")
            val action = when (sample.getString("action")) {
                "down" -> MotionEvent.ACTION_DOWN
                "up" -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            if (action == MotionEvent.ACTION_DOWN) downTime = due

            // Waited for rather than sent early. The whole point of building a gesture in time is
            // lost if the events arrive as fast as this loop can run them, and every threshold in
            // the keyboard -- flick against glide above all -- is a speed.
            val wait = due - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)

            val x = sample.getDouble("x").toFloat()
            val y = sample.getDouble("y").toFloat()
            val event = MotionEvent.obtain(downTime, due, action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            // Synchronous, so the call returns only once the event has been dispatched.
            automation.injectInputEvent(event, true)
            event.recycle()

            log.append(
                JSONObject()
                    .put("t", due - origin)
                    .put("deviceTime", due)
                    .put("x", x)
                    .put("y", y)
                    .put("action", sample.getString("action"))
                    .put("tag", sample.optString("tag"))
                    .toString(),
            ).append('\n')
        }

        SystemClock.sleep(plan.optLong("tailMs", 1200))
        File(dir, "touches.jsonl").writeText(log.toString())
    }
}
