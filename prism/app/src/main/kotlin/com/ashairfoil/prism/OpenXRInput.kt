package com.ashairfoil.prism

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log

class OpenXRInput(private val activity: Activity) {

    companion object {
        init { System.loadLibrary("openxr_input") }
        private const val TAG = "ChloeVR-OpenXR"
        private const val STATE_SIZE = 41
        private const val POLL_INTERVAL_MS = 8L // ~120Hz for responsive buttons

        @JvmStatic
        external fun nativeSetSurfaceSize(surface: android.view.Surface, width: Int, height: Int)
    }

    // Current controller state
    var leftThumbX = 0f; private set
    var leftThumbY = 0f; private set
    var rightThumbX = 0f; private set
    var rightThumbY = 0f; private set
    var leftTrigger = 0f; private set
    var rightTrigger = 0f; private set
    var leftSqueeze = 0f; private set
    var rightSqueeze = 0f; private set
    var aButton = false; private set
    var bButton = false; private set
    var xButton = false; private set
    var yButton = false; private set
    var menuButton = false; private set
    var leftStickClick = false; private set
    var rightStickClick = false; private set

    // Hand poses (in local/stage space)
    var leftHandPos = floatArrayOf(0f, 0f, 0f); private set
    var leftHandRot = floatArrayOf(0f, 0f, 0f, 1f); private set
    var rightHandPos = floatArrayOf(0f, 0f, 0f); private set
    var rightHandRot = floatArrayOf(0f, 0f, 0f, 1f); private set
    var leftHandValid = false; private set
    var rightHandValid = false; private set

    // Aim pose rotation (laser pointer direction)
    var leftAimRot = floatArrayOf(0f, 0f, 0f, 1f); private set
    var rightAimRot = floatArrayOf(0f, 0f, 0f, 1f); private set
    var leftAimValid = false; private set
    var rightAimValid = false; private set

    private val stateBuffer = FloatArray(STATE_SIZE)
    private val handler = Handler(Looper.getMainLooper())
    private var initialized = false
    private var listener: ControllerListener? = null

    interface ControllerListener {
        fun onControllerState(input: OpenXRInput)
    }

    fun setListener(l: ControllerListener) { listener = l }

    fun start(): Boolean {
        if (initialized) return true
        initialized = nativeInit(activity)
        if (initialized) {
            Log.i(TAG, "OpenXR input started")
            handler.post(pollRunnable)
        } else {
            Log.e(TAG, "OpenXR input failed to initialize")
        }
        return initialized
    }

    fun stop() {
        handler.removeCallbacks(pollRunnable)
        if (initialized) {
            nativeShutdown()
            initialized = false
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!initialized) return
            if (nativePoll(stateBuffer)) {
                leftThumbX = stateBuffer[0]
                rightThumbX = stateBuffer[1]
                leftThumbY = stateBuffer[2]
                rightThumbY = stateBuffer[3]
                leftTrigger = stateBuffer[4]
                rightTrigger = stateBuffer[5]
                leftSqueeze = stateBuffer[6]
                rightSqueeze = stateBuffer[7]
                aButton = stateBuffer[8] > 0.5f
                bButton = stateBuffer[9] > 0.5f
                xButton = stateBuffer[10] > 0.5f
                yButton = stateBuffer[11] > 0.5f
                menuButton = stateBuffer[12] > 0.5f
                leftStickClick = stateBuffer[13] > 0.5f
                rightStickClick = stateBuffer[14] > 0.5f
                leftHandPos = floatArrayOf(stateBuffer[15], stateBuffer[17], stateBuffer[19])
                rightHandPos = floatArrayOf(stateBuffer[16], stateBuffer[18], stateBuffer[20])
                leftHandRot = floatArrayOf(stateBuffer[21], stateBuffer[23], stateBuffer[25], stateBuffer[27])
                rightHandRot = floatArrayOf(stateBuffer[22], stateBuffer[24], stateBuffer[26], stateBuffer[28])
                leftHandValid = stateBuffer[29] > 0.5f
                rightHandValid = stateBuffer[30] > 0.5f
                leftAimRot = floatArrayOf(stateBuffer[31], stateBuffer[33], stateBuffer[35], stateBuffer[37])
                rightAimRot = floatArrayOf(stateBuffer[32], stateBuffer[34], stateBuffer[36], stateBuffer[38])
                leftAimValid = stateBuffer[39] > 0.5f
                rightAimValid = stateBuffer[40] > 0.5f
                listener?.onControllerState(this@OpenXRInput)
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private external fun nativeInit(activity: Activity): Boolean
    private external fun nativeShutdown()
    private external fun nativePoll(outState: FloatArray): Boolean
}
