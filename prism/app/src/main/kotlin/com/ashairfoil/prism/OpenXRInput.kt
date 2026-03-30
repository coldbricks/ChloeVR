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

    // Hand poses (in local/stage space) — preallocated, written in-place
    val leftHandPos = floatArrayOf(0f, 0f, 0f)
    val leftHandRot = floatArrayOf(0f, 0f, 0f, 1f)
    val rightHandPos = floatArrayOf(0f, 0f, 0f)
    val rightHandRot = floatArrayOf(0f, 0f, 0f, 1f)
    var leftHandValid = false; private set
    var rightHandValid = false; private set

    // Aim pose rotation (laser pointer direction) — preallocated, written in-place
    val leftAimRot = floatArrayOf(0f, 0f, 0f, 1f)
    val rightAimRot = floatArrayOf(0f, 0f, 0f, 1f)
    var leftAimValid = false; private set
    var rightAimValid = false; private set

    private val stateBuffer = FloatArray(STATE_SIZE)
    private val handler = Handler(Looper.getMainLooper())
    private var initialized = false
    private var isPolling = false
    private var listener: ControllerListener? = null

    interface ControllerListener {
        fun onControllerState(input: OpenXRInput)
    }

    fun setListener(l: ControllerListener) { listener = l }

    fun start(): Boolean {
        if (isPolling) return true
        if (!initialized) {
            initialized = nativeInit(activity)
        }
        if (initialized) {
            isPolling = true
            Log.i(TAG, "OpenXR input started")
            handler.post(pollRunnable)
        } else {
            Log.e(TAG, "OpenXR input failed to initialize")
        }
        return initialized
    }

    fun stop() {
        isPolling = false
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
                leftHandPos[0] = stateBuffer[15]; leftHandPos[1] = stateBuffer[17]; leftHandPos[2] = stateBuffer[19]
                rightHandPos[0] = stateBuffer[16]; rightHandPos[1] = stateBuffer[18]; rightHandPos[2] = stateBuffer[20]
                leftHandRot[0] = stateBuffer[21]; leftHandRot[1] = stateBuffer[23]; leftHandRot[2] = stateBuffer[25]; leftHandRot[3] = stateBuffer[27]
                rightHandRot[0] = stateBuffer[22]; rightHandRot[1] = stateBuffer[24]; rightHandRot[2] = stateBuffer[26]; rightHandRot[3] = stateBuffer[28]
                leftHandValid = stateBuffer[29] > 0.5f
                rightHandValid = stateBuffer[30] > 0.5f
                leftAimRot[0] = stateBuffer[31]; leftAimRot[1] = stateBuffer[33]; leftAimRot[2] = stateBuffer[35]; leftAimRot[3] = stateBuffer[37]
                rightAimRot[0] = stateBuffer[32]; rightAimRot[1] = stateBuffer[34]; rightAimRot[2] = stateBuffer[36]; rightAimRot[3] = stateBuffer[38]
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
