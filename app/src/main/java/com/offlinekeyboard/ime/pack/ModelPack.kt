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
        if (!loadFromPack(context, name)) System.loadLibrary(name)
    }

    /**
     * Where the payload's assets are read from: the pack when there is one, this APK otherwise.
     *
     * Only for the payload -- the SenseVoice and VAD models and the swipe models. The small
     * assets (lexicons, keyword spotter, pinyin) always ship in the code APK and must still be
     * read through the caller's own `context.assets`.
     */
    fun payloadAssets(context: Context): android.content.res.AssetManager =
        assets(context) ?: context.assets

    /**
     * Loads `lib<name>.so` out of the pack. False when there is no pack or it lacks the library,
     * so the caller can fall back to this APK's own copy.
     *
     * The pack is built with AGP's default packaging, which on minSdk 23+ stores native libraries
     * *uncompressed and page-aligned inside the APK* and does not extract them at install
     * (`extractNativeLibs=false`). Its `nativeLibraryDir` then exists but is empty, and checking
     * for a file there -- all this used to do -- always failed, leaving a `-Ppack` keyboard with
     * no dictation and no glide typing. The bionic linker loads such a library straight out of
     * the zip with a `base.apk!/lib/<abi>/lib<name>.so` path, so that is tried after the
     * extracted copy.
     *
     * Dependencies are the other half. A library loaded by path still resolves its `DT_NEEDED`
     * entries against *this* app's search path, which does not include the pack, so
     * `libsherpa-onnx-jni.so` fails on `libonnxruntime.so`. The linker does match an
     * already-loaded library by soname, though, so a missing dependency that the pack carries is
     * loaded first and the load retried. Driven by the linker's own error rather than a hardcoded
     * list, so a runtime update that adds a dependency does not silently break it again.
     */
    @Synchronized
    fun loadFromPack(context: Context, name: String): Boolean {
        val paths = libraryPaths(context)
        if (paths.isEmpty()) return false
        val file = "lib$name.so"
        if (file !in paths) {
            Log.w(TAG, "model pack has no $file; falling back to this APK")
            return false
        }
        return loadWithDependencies(file, paths, depth = 0)
    }

    private val loaded = mutableSetOf<String>()

    private fun loadWithDependencies(file: String, paths: Map<String, String>, depth: Int): Boolean {
        if (file in loaded) return true
        val path = paths[file] ?: return false
        repeat(MAX_DEPENDENCIES) {
            try {
                System.load(path)
                loaded += file
                return true
            } catch (e: UnsatisfiedLinkError) {
                val missing = MISSING_LIBRARY.find(e.message.orEmpty())?.groupValues?.get(1)
                if (missing == null || missing == file || missing !in paths || depth >= MAX_DEPTH ||
                    !loadWithDependencies(missing, paths, depth + 1)
                ) {
                    Log.e(TAG, "could not load $file from the model pack ($path)", e)
                    return false
                }
            }
        }
        return false
    }

    /** `lib<name>.so` -> a path `System.load` accepts, for every library the pack carries. */
    private var libraryPaths: Map<String, String>? = null

    @Synchronized
    private fun libraryPaths(context: Context): Map<String, String> {
        libraryPaths?.let { return it }
        assets(context) ?: return emptyMap<String, String>().also { libraryPaths = it }
        val found = runCatching {
            val info = context.packageManager.getApplicationInfo(PACKAGE, 0)
            val paths = mutableMapOf<String, String>()
            // Extracted copies first, for a pack built with legacy packaging.
            info.nativeLibraryDir?.let(::File)?.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".so") }
                ?.forEach { paths[it.name] = it.absolutePath }
            // Then the copies stored inside the APK, for the device's preferred ABI that has any.
            val apk = info.sourceDir
            java.util.zip.ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .filter { it.method == java.util.zip.ZipEntry.STORED }
                    .map { it.name }
                    .toList()
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull { abi ->
                    entries.any { it.startsWith("lib/$abi/") }
                }
                if (abi != null) {
                    entries.filter { it.startsWith("lib/$abi/") }.forEach { entry ->
                        paths.putIfAbsent(entry.substringAfterLast('/'), "$apk!/$entry")
                    }
                }
            }
            paths.toMap()
        }.onFailure {
            Log.w(TAG, "could not list the model pack's native libraries", it)
        }.getOrDefault(emptyMap())
        Log.i(TAG, "model pack native libraries: ${found.keys.sorted()}")
        libraryPaths = found
        return found
    }

    /** bionic: `dlopen failed: library "libfoo.so" not found: needed by ...` */
    private val MISSING_LIBRARY = Regex("""library "([^"/]+\.so)" not found""")
    private const val MAX_DEPENDENCIES = 8
    private const val MAX_DEPTH = 4

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
                "libs=${libraryPaths(context).size}, enabled=${isEnabled(context)})"
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
