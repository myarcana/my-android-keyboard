package com.offlinekeyboard.ime.demo

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.LayoutGeometry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Writes down where the keyboard is, so a demo can be aimed at it.
 *
 * Step one of the recording pipeline in `tools/demo/`. It opens the test pad, focuses a field,
 * waits for the keyboard to settle and reports every key's rectangle in screen pixels. The host
 * turns that into a plan of touches; `DemoPlayer` in the test pad injects it. See
 * `.claude/skills/demo-video/SKILL.md`.
 *
 * **Why the measuring and the typing are two different APKs.** Instrumentation runs inside the
 * process of the app it targets, and `am instrument` kills that process when the run ends.
 * Targeting the keyboard therefore *closes the keyboard* the moment the run finishes, which is
 * fine here -- this runs before the camera rolls -- and fatal for the playback, which has to
 * outlive nothing but still leave the keyboard standing. So playback is instrumentation on the
 * test pad instead, and the two halves meet through files on the host.
 *
 * The key rectangles come from the keyboard's own [LayoutGeometry] rather than a copy of the
 * metrics. This is the only process that can ask the real thing, which is most of the reason
 * this class exists at all: `tools/fit_spatial.py` already keeps a second copy of the geometry
 * and needs `LayoutGeometryTest` to stay honest, and a third copy inside the demo tooling would
 * be a third thing to keep in step.
 */
@RunWith(AndroidJUnit4::class)
class DemoGeometry {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val args: Bundle = InstrumentationRegistry.getArguments()
    private val dir = File(instrumentation.targetContext.filesDir, "demo").apply { mkdirs() }

    @Test
    fun measure() {
        retrieveWindows()

        val pkg = args.getString("app") ?: "com.offlinekeyboard.testpad"
        val activity = args.getString("activity") ?: "com.offlinekeyboard.testpad.TestPadActivity"
        val index = args.getString("field")?.toIntOrNull() ?: 0

        // The test pad opens itself on the right field with the keyboard up: see `demoField` in
        // TestPadActivity. Asked of the app rather than done from here with a tap, because a tap
        // raises the keyboard only when it *changes* which view has focus, and this run keeps
        // arriving at a screen whose field is already focused and whose keyboard is not there --
        // the previous run ended by killing the keyboard's process. One take in three was lost
        // to that before the app was asked directly.
        // The test pad is stopped first, so that the launch below is always a cold one.
        //
        // This is what stopped every other take failing. A warm relaunch delivers the intent to
        // the activity that is already showing, and a request to open the keyboard on a field
        // that already has focus, in an app whose window never changed, is dropped -- silently,
        // with `ImeTracker` reporting a show that failed at `PHASE_SERVER_UPDATE_CLIENT_VISIBILITY`.
        // A cold start goes through `onCreate`, where the same request is honoured every time.
        // (The playback run gets this for free: `am instrument` kills the app it targets.)
        shell("am force-stop $pkg")
        SystemClock.sleep(500)

        startTestPad(pkg, activity, index)
        waitFor("the test pad") { findFields(pkg).isNotEmpty() }
        val bounds = Rect().also { rect ->
            findFields(pkg).let { it.getOrNull(index) ?: it.first() }.getBoundsInScreen(rect)
        }

        waitForKeyboard()
        SystemClock.sleep(args.getString("settleMs")?.toLongOrNull() ?: 900)
        // Measured again after the settle so the numbers describe a still screen. A rectangle
        // read while the window was still sliding up put the keyboard's top 600 px low, and
        // every key centre derived from it would have been off the bottom of the screen.
        File(dir, "geometry.json").writeText(geometry(waitForKeyboard(), bounds).toString(2))
    }

    private fun startTestPad(pkg: String, activity: String, field: Int) {
        instrumentation.targetContext.startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(pkg, activity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("demoField", field)
            },
        )
    }

    /**
     * Where the keyboard is, and where every key in it is, in screen pixels.
     *
     * Rectangles as well as centres: the renderer wants a rectangle to watch for the frame the
     * recording starts moving, and the planner wants centres to aim at.
     */
    private fun geometry(keyboard: Rect, field: Rect): JSONObject {
        val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, keyboard.width().toFloat())
        val keys = JSONObject()
        val rects = JSONObject()
        geometry.keyRects.forEach { rect ->
            keys.put(
                rect.key.id,
                JSONArray().put(keyboard.left + rect.centerX).put(keyboard.top + rect.centerY),
            )
            rects.put(
                rect.key.id,
                JSONArray()
                    .put(keyboard.left + rect.left)
                    .put(keyboard.top + rect.top)
                    .put(keyboard.left + rect.right)
                    .put(keyboard.top + rect.bottom),
            )
        }
        val display = instrumentation.targetContext.resources.displayMetrics
        return JSONObject()
            .put("screenWidth", display.widthPixels)
            .put("screenHeight", display.heightPixels)
            .put("density", display.density)
            .put(
                "keyboard",
                JSONArray().put(keyboard.left).put(keyboard.top)
                    .put(keyboard.right).put(keyboard.bottom),
            )
            .put("keyUnit", geometry.keyUnit)
            .put("keyHeight", geometry.keyHeight)
            .put("stripHeight", geometry.stripHeight)
            .put("keys", keys)
            .put("keyRects", rects)
            // Where the field was. The host needs it to put the keyboard back up: this run ends
            // by killing the process it targets, which is the keyboard's, and nothing else
            // records which of the test pad's fields was chosen.
            .put("field", JSONArray().put(field.exactCenterX()).put(field.exactCenterY()))
    }

    /**
     * The keyboard's window rectangle, once it has stopped moving.
     *
     * Asked of the system rather than computed from the metrics because the answer depends on
     * things this side cannot see: the navigation bar's height, and whether the window sits above
     * it or behind it. Getting that wrong by the height of a gesture bar puts every injected tap
     * one row off, which looks like a decoding bug rather than a measurement one.
     */
    private fun waitForKeyboard(timeoutMs: Long = 10_000): Rect {
        var found: Rect? = null
        var previous: Rect? = null
        waitFor("the keyboard window", timeoutMs) {
            val now = automation.windows
                .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?.let { window -> Rect().also { window.getBoundsInScreen(it) } }
            val settled = now != null && now.height() > 0 && now == previous
            previous = now
            if (settled) found = now
            settled
        }
        return found!!
    }

    /**
     * The test pad's text fields, top to bottom.
     *
     * Every window is searched rather than the active one. When the keyboard is already up --
     * which it is on every take after the first -- the active window is the keyboard's, and
     * looking only there finds no fields at all.
     */
    private fun findFields(pkg: String): List<AccessibilityNodeInfo> {
        val root = automation.windows.mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == pkg }
            ?: automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }
            ?: return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            if (node.isEditable) out.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        walk(root)
        return out.sortedBy { Rect().also { r -> it.getBoundsInScreen(r) }.top }
    }

    private fun shell(command: String) {
        automation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
    }

    /** Windows are not reported to an accessibility client that has not asked for them. */
    private fun retrieveWindows() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(120)
        }
        error("timed out waiting for $what")
    }
}
