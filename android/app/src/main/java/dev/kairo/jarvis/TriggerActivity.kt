package dev.kairo.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings

/**
 * No screen of its own: wakes JARVIS up to listen, then closes. Used by the app shortcut, the
 * quick-settings tile when JARVIS isn't running yet, and "assist" gestures. Because the user
 * just tapped something, Android lets it start the microphone.
 */
class TriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ready = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && Settings.canDrawOverlays(this)
        if (ready) JarvisService.send(this, JarvisService.ACTION_ACTIVATE)
        else startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
