package com.offlinekeyboard.ime.asr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Asks for the microphone, because an InputMethodService cannot.
 *
 * Runtime permissions are granted to an Activity, and a keyboard is a service with no activity
 * of its own. So the microphone key starts this one, it asks, and it finishes immediately either
 * way. It has no layout and a transparent theme, so what the user sees is the system dialog over
 * whatever they were typing in.
 */
class MicrophonePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }

    override fun finish() {
        super.finish()
        // No animation: the keyboard is still on screen behind this, and a slide-out over it
        // looks like the keyboard itself moved.
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val REQUEST = 1

        fun launchFrom(context: Context) {
            context.startActivity(
                Intent(context, MicrophonePermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                },
            )
        }
    }
}
