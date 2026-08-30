package com.offlinekeyboard.ime.glide

import java.io.File

/**
 * A dictionary owned by the Kotlin side, for swipe-library's beam search.
 *
 * The library takes its dictionaries as `ITrie` pointers, and its Kotlin binding passes them as
 * longs -- but nothing in the shipped binding can produce one, so out of the box the Kotlin API
 * cannot load a dictionary at all. `tools/patches/swipe-library-trie-jni.patch` adds the three
 * calls below; this is the other half of them.
 *
 * The trie must outlive every use of the pointer [itrie] returns, which is why this is a handle
 * with a [close] rather than a function that returns a number.
 */
class SwipeTrie private constructor(private var handle: Long) : AutoCloseable {

    /** The `ITrie*` to hand to `SwipeDecoder.setMode`. Valid until [close]. */
    val itrie: Long
        get() {
            check(handle != 0L) { "the dictionary has been closed" }
            return nativeItrie(handle)
        }

    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        init {
            // Its own load, even though the binding class does the same thing: these three
            // natives live in that library but in no way depend on that class, and a dictionary
            // is loaded *before* the engine that will use it. Relying on the other class having
            // been touched first is a load-order assumption that was wrong the first time.
            System.loadLibrary("swipe_jni")
        }

        /** Null when the file is missing or unparseable, which is a fallback, not a crash. */
        fun load(file: File): SwipeTrie? {
            if (!file.isFile) return null
            val handle = nativeLoad(file.absolutePath)
            return if (handle == 0L) null else SwipeTrie(handle)
        }

        @JvmStatic private external fun nativeLoad(path: String): Long
        @JvmStatic private external fun nativeItrie(handle: Long): Long
        @JvmStatic private external fun nativeFree(handle: Long)
    }
}
