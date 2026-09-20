package com.offlinekeyboard.ime

import android.app.Application
import com.offlinekeyboard.ime.pack.ModelPackRuntime

/**
 * Exists to install the application Context before anything touches a native binding.
 *
 * The vendored native bindings (`OfflineRecognizer`, `Vad`, `SwipeDecoder`, `SwipeTrie`) load
 * their libraries from `companion object` initialisers, which run the first time any of their
 * classes is touched -- possibly from a background thread, and possibly before an Activity or the
 * keyboard service exists. When the library lives in the model pack rather than in this APK, the
 * loader needs a Context to find it, and `Application.onCreate` is the only point that is
 * guaranteed to run first.
 *
 * If the pack is absent this is a no-op beyond a log line, which is the self-contained build.
 */
class KeyboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ModelPackRuntime.install(this)
    }
}
