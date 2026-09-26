package com.offlinekeyboard.ime.pack

import android.content.Context
import android.util.Log

/**
 * The Context-free half of [ModelPack].
 *
 * The native bindings load their libraries from a `companion object` initialiser, which runs before
 * any application code has had a chance to hand them a Context, and cannot be given one -- they
 * are vendored upstream files whose signatures are not ours to change. So the Context is stashed
 * here once, from `Application.onCreate`, and read back by the initialisers.
 *
 * The fallback has to be right in both directions:
 *
 *   * a **self-contained build** has no pack, [context] is still set, and the library is loaded
 *     from this APK -- `System.loadLibrary` with its usual search path.
 *   * a **vendored class touched before the app installed the context** (in a unit test, or from
 *     an unrelated entry point) must still work, so a missing context is a fallback and not an
 *     error.
 *
 * Nothing here needs a Context to decide *whether* the pack exists in the ordinary case: the app
 * always calls [install] first, which resolves the pack once. [ModelPack.loadFromPack] returning
 * false is what makes a stale or half-installed pack degrade to the local copy instead of crashing
 * on a missing `.so`, which is exactly the failure mode a split payload invites.
 */
object ModelPackRuntime {

    private const val TAG = "ModelPackRuntime"

    @Volatile
    private var appContext: Context? = null

    /** Called from `Application.onCreate`, before any binding class is touched. */
    fun install(context: Context) {
        appContext = context.applicationContext
        // Report once, and eagerly: this is the line that says whether dictation and glide have
        // their models available, and it is much easier to read from logcat than to infer from a
        // keyboard that quietly does nothing.
        Log.i(TAG, ModelPack.describe(context))
    }

    /**
     * Loads [name] from the model pack when it is there, from this APK otherwise.
     *
     * `System.loadLibrary` only searches the *calling* class's own package directory, so a library
     * in the pack has to be loaded by absolute path. The pack's location comes from the package
     * manager, not from a path we invent, so this is not a way to load arbitrary code.
     */
    @JvmStatic
    fun loadOptional(name: String) {
        val context = appContext
        if (context != null && ModelPack.loadFromPack(context, name)) return
        System.loadLibrary(name)
    }
}
