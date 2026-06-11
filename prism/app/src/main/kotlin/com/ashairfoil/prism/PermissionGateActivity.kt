package com.ashairfoil.prism

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log

/**
 * 2D trampoline that collects runtime permissions BEFORE the OpenXR viewer
 * starts. FilamentModelActivity must never call requestPermissions itself —
 * the permission dialog backgrounds the immersive activity and kills the XR
 * session — so launch paths that skip MainActivity (the Quest launcher, adb
 * direct launches) bounce through this gate once. It asks for the same set
 * MainActivity does (runtime dialogs + the all-files-access settings screen
 * for .glb/.funscript browsing), records that it ran, and hands off to the
 * viewer. Denials are respected: the gate never re-asks on later launches.
 */
class PermissionGateActivity : Activity() {

    companion object {
        private const val TAG = "ChloeVR-PermGate"
        private const val REQ_RUNTIME = 100
        private const val PREFS = "permission_gate"
        private const val KEY_DONE = "asked_once"

        // Mirrors MainActivity.requestBasicMediaPermissions: RECORD_AUDIO for
        // the BeatReactor Visualizer, BLUETOOTH_* for Lovense BLE discovery,
        // READ_MEDIA_* for the media library.
        val RUNTIME_PERMISSIONS = arrayOf(
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN
        )

        fun hasMissingRuntimePermissions(context: Context): Boolean =
            RUNTIME_PERMISSIONS.any {
                context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }

        fun gateCompleted(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DONE, false)
    }

    // Set when we hand the user to the all-files-access settings screen;
    // onResume picks the flow back up when they return.
    private var storageSettingsOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasMissingRuntimePermissions(this)) {
            requestPermissions(RUNTIME_PERMISSIONS, REQ_RUNTIME)
        } else {
            requestAllFilesAccessOrLaunch()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_RUNTIME) return
        for (i in permissions.indices) {
            if (grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permission denied: ${permissions[i]}")
            }
        }
        requestAllFilesAccessOrLaunch()
    }

    private fun requestAllFilesAccessOrLaunch() {
        // MANAGE_EXTERNAL_STORAGE (.glb/.gltf/.funscript browsing) is granted
        // on a settings screen, not a dialog — same cascade as MainActivity.
        if (!Environment.isExternalStorageManager() && !storageSettingsOpened) {
            storageSettingsOpened = true
            try {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
                return  // resumes in onResume when the user comes back
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    return
                } catch (e2: Exception) {
                    Log.w(TAG, "All-files-access settings screen unavailable", e2)
                }
            }
        }
        launchViewer()
    }

    override fun onResume() {
        super.onResume()
        if (storageSettingsOpened) launchViewer()
    }

    private fun launchViewer() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
        Log.i(TAG, "Gate complete (allFiles=${Environment.isExternalStorageManager()}, " +
            "missingRuntime=${hasMissingRuntimePermissions(this)}) — launching viewer")
        startActivity(Intent(this, FilamentModelActivity::class.java))
        finish()
    }
}
