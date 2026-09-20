package com.offlinekeyboard.ime.pack

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

/**
 * Where the big, never-changing half of the app actually lives.
 *
 * The keyboard APK is ~320 MB, and 240 MB of that is the SenseVoice model alone. That model
 * changes when the model changes -- which is to say, almost never -- while the code changes on
 * every iteration. Shipping both together means paying for the model on every deploy, and over a
 * relayed tailnet link that is half an hour for a change to a few kilobytes of Kotlin.
 *
 * So the payload is split by how often it changes:
 *
 *   * the **model pack**, a second APK signed with the same key, carrying only the models and the
 *     native libraries. Installed once, and again when a model is actually replaced.
 *   * the **keyboard APK**, carrying only code, resources and the small assets. This is what
 *     `tools/deploy.sh` installs on every iteration.
 *
 * The model pack is a normal app rather than an opaque blob because that is what makes the split
 * cost nothing at runtime: it is held in the *same process*, so [assets] returns its
 * [android.content.res.AssetManager] directly and the existing loaders -- sherpa-onnx's
 * `newFromAsset`, the pinyin dictionary -- keep reading assets exactly as before. Nothing is
 * copied, nothing is unpacked, and there is no second implementation of any loader to drift.
 *
 * Sharing a process is safe precisely because the pack declares no `uses-permission`: it
 * contributes no permissions of its own, no service, no activity and no receiver. It is an
 * `AssetManager` with a package name.
 *
 * The two halves must be signed by the same key. That is not incidental: a same-signature pair
 * gets `android:sharedUserId`-free process sharing and skips the signature check entirely, while a
 * mismatched pair fails the lookup and the keyboard falls back to its own assets.
 */
object ModelPack {

    private const val TAG = "ModelPack"

    /** The pack's application id. Kept in step with `:modelpack`'s `applicationId`. */
    const val PACKAGE = "com.offlinekeyboard.ime.models"

    /**
     * True when this build expects a pack to be installed.
     *
     * Wired to `BuildConfig.MODEL_PACK`, which `app/build.gradle.kts` sets from `-Ppack`.
     * Deliberately not a `const`, so a self-contained build cannot be confused with a packed one
     * at the call site, and so the default state of the repository is unchanged.
     */
    val expected: Boolean get() = com.offlinekeyboard.ime.BuildConfig.MODEL_PACK

    @Volatile
    private var resolved = false

    private var packAssets: android.content.res.AssetManager? = null

    /**
     * The pack's assets, or null when there is no pack.
     *
     * Resolved once: `createPackageContext` is a binder call into the package manager, and the
     * loaders that call this run on a path the keyboard cannot afford to make slow. Called from
     * several threads, hence the lock; the result is a single reference, so holding it for the
     * lookup only is enough.
     */
    @Synchronized
    fun assets(context: Context): android.content.res.AssetManager? {
        if (resolved) return packAssets
        packAssets = runCatching {
            context.createPackageContext(PACKAGE, 0).assets
        }.onFailure {
            // Not an error by itself: a self-contained build has no pack and is expected to fall
            // back to its own assets. It is worth a log line, because the alternative reading --
            // an incremental build with the pack missing -- produces a keyboard that silently
            // cannot dictate.
            Log.i(TAG, "no model pack ($PACKAGE): using this APK's own assets")
        }.getOrNull()
        resolved = true
        return packAssets
    }

    /**
     * The pack's native library directory, or null when there is no pack.
     *
     * The native runtime is the other half of the payload that never changes: 60 MB of
     * `libsherpa-onnx-*.so` and `libswipe_jni.so` that would otherwise be transferred on every
     * deploy. `ApplicationInfo.nativeLibraryDir` is a plain path on disk, so it can be handed
     * straight to `System.load` -- no extraction, no copy.
     */
    @Synchronized
    fun nativeLibraryDir(context: Context): String? {
        assets(context) // the same lookup decides both; see above
        if (packAssets == null) return null
        return runCatching {
            context.packageManager
                .getApplicationInfo(PACKAGE, 0)
                .nativeLibraryDir
        }.onFailure {
            Log.w(TAG, "model pack has no native library directory", it)
        }.getOrNull()
    }

    /**
     * Loads a native library out of the pack, falling back to this APK's own.
     *
     * `System.loadLibrary` searches the *calling* package's `nativeLibraryDir` only, so a library
     * that lives in the pack has to be loaded by absolute path. The fallback keeps a
     * self-contained build working with no branch at the call site.
     *
     * The pack is found by package-manager lookup rather than by path, so this is not a way to
     * load arbitrary code: whatever is at that path was installed by the same signer.
     */
    fun loadLibrary(context: Context, name: String) {
        val dir = nativeLibraryDir(context)
        if (dir != null) {
            val file = File(dir, "lib$name.so")
            if (file.isFile) {
                System.load(file.absolutePath)
                return
            }
            Log.w(TAG, "pack has no lib$name.so; falling back to this APK")
        }
        System.loadLibrary(name)
    }

    /** True when a pack is installed and usable. Used by `tools/deploy.sh`'s preflight. */
    fun isInstalled(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        }.getOrDefault(false)

    /** The pack's `versionName`, for the drift check in [verify]. Null when absent. */
    fun installedVersion(context: Context): String? =
        runCatching {
            context.packageManager.getPackageInfo(PACKAGE, 0).versionName
        }.getOrNull()

    /**
     * The pack's installed code size in bytes, for the same preflight.
     *
     * `PackageInfo` has no "size" field that is reliable across API levels, so the library and
     * model files are measured directly. This is only ever used for reporting.
     */
    fun installedBytes(context: Context): Long {
        val dir = nativeLibraryDir(context) ?: return 0L
        val libDir = File(dir).parentFile ?: return 0L
        return runCatching { libDir.walkTopDown().filter(File::isFile).sumOf(File::length) }
            .getOrDefault(0L)
    }

    /** The pack's `versionCode`, or -1. Compared against this build's expectation in the check. */
    fun installedVersionCode(context: Context): Int =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(PACKAGE, 0).versionCode
        }.getOrDefault(-1)

    /** True when the package manager will let us read the pack's assets at all. */
    fun isEnabled(context: Context): Boolean =
        runCatching {
            context.packageManager.getApplicationInfo(PACKAGE, 0).enabled
        }.getOrDefault(false)

    /** A one-line summary for `adb shell` diagnostics. */
    fun describe(context: Context): String =
        if (isInstalled(context)) {
            "model pack $PACKAGE v${installedVersion(context)} " +
                "(${installedBytes(context) / (1024 * 1024)} MB, " +
                "libs=${nativeLibraryDir(context) != null}, enabled=${isEnabled(context)})"
        } else {
            "no model pack installed"
        }

    /** Permissions the pack declares. Must always be empty; see the class comment. */
    fun declaredPermissions(context: Context): List<String> =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager
                .getPackageInfo(PACKAGE, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
}
