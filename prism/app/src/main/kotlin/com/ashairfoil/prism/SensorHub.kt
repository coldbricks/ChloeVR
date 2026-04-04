package com.ashairfoil.prism

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Enumerates and polls ALL available Android hardware sensors on the device.
 * Designed for Samsung Galaxy XR — captures IMU, barometer, magnetometer,
 * rotation vectors, step counter, head tracker, and everything else available.
 */
class SensorHub(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "ChloeVR-SensorHub"
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val activeSensors = mutableMapOf<Int, Sensor>()
    private var sensorThread: HandlerThread? = HandlerThread("SensorHub").apply { start() }
    private var sensorHandler: Handler? = sensorThread?.looper?.let { Handler(it) }
    @Volatile private var isRunning = false

    // ── Sensor data (all @Volatile for cross-thread reads) ──

    // Light (type 5) — already used, now centralized
    @Volatile var lightLux = 200f

    // Accelerometer (type 1) — m/s², excl gravity
    @Volatile var accelX = 0f; @Volatile var accelY = 0f; @Volatile var accelZ = 0f

    // Gyroscope (type 4) — rad/s
    @Volatile var gyroX = 0f; @Volatile var gyroY = 0f; @Volatile var gyroZ = 0f

    // Magnetometer (type 2) — µT
    @Volatile var magX = 0f; @Volatile var magY = 0f; @Volatile var magZ = 0f

    // Pressure/Barometer (type 6) — hPa
    @Volatile var pressureHpa = 0f

    // Proximity (type 8) — cm
    @Volatile var proximityCm = 0f

    // Gravity (type 9) — m/s²
    @Volatile var gravityX = 0f; @Volatile var gravityY = 9.8f; @Volatile var gravityZ = 0f

    // Linear Acceleration (type 10) — m/s², no gravity
    @Volatile var linAccelX = 0f; @Volatile var linAccelY = 0f; @Volatile var linAccelZ = 0f

    // Rotation Vector (type 11) — quaternion (x,y,z,w,heading_accuracy)
    @Volatile var rotVecX = 0f; @Volatile var rotVecY = 0f
    @Volatile var rotVecZ = 0f; @Volatile var rotVecW = 1f

    // Game Rotation Vector (type 15) — quaternion, no magnetometer
    @Volatile var gameRotX = 0f; @Volatile var gameRotY = 0f
    @Volatile var gameRotZ = 0f; @Volatile var gameRotW = 1f

    // Gyroscope Uncalibrated (type 16) — rad/s + drift
    @Volatile var gyroUncalX = 0f; @Volatile var gyroUncalY = 0f; @Volatile var gyroUncalZ = 0f
    @Volatile var gyroDriftX = 0f; @Volatile var gyroDriftY = 0f; @Volatile var gyroDriftZ = 0f

    // Accelerometer Uncalibrated (type 35)
    @Volatile var accelUncalX = 0f; @Volatile var accelUncalY = 0f; @Volatile var accelUncalZ = 0f
    @Volatile var accelBiasX = 0f; @Volatile var accelBiasY = 0f; @Volatile var accelBiasZ = 0f

    // Step Counter (type 19) — cumulative steps since boot
    @Volatile var stepCount = 0L

    // Step Detector (type 18) — fires event on each step
    @Volatile var stepDetected = false; @Volatile var lastStepTimestamp = 0L

    // Head Tracker (type 37) — 6-axis head orientation + angular velocity
    @Volatile var headRotX = 0f; @Volatile var headRotY = 0f; @Volatile var headRotZ = 0f
    @Volatile var headVelX = 0f; @Volatile var headVelY = 0f; @Volatile var headVelZ = 0f

    // Significant Motion (type 17) — one-shot trigger
    @Volatile var significantMotionDetected = false

    // Stationary Detect (type 29)
    @Volatile var isStationary = false

    // Motion Detect (type 30)
    @Volatile var isInMotion = false

    // Geomagnetic Rotation Vector (type 20)
    @Volatile var geoRotX = 0f; @Volatile var geoRotY = 0f
    @Volatile var geoRotZ = 0f; @Volatile var geoRotW = 1f

    // Ambient Temperature (type 13) — °C
    @Volatile var ambientTempC = 0f

    // Relative Humidity (type 12) — %
    @Volatile var relativeHumidity = 0f

    // Heading (type 42) — degrees from true north
    @Volatile var headingDeg = 0f

    // Magnetic Field Uncalibrated (type 14)
    @Volatile var magUncalX = 0f; @Volatile var magUncalY = 0f; @Volatile var magUncalZ = 0f
    @Volatile var magBiasX = 0f; @Volatile var magBiasY = 0f; @Volatile var magBiasZ = 0f

    // Hinge Angle (type 36) — degrees
    @Volatile var hingeAngleDeg = 0f

    // ── Availability tracking ──
    @Volatile var availableSensorTypes = listOf<Int>()
        private set
    @Volatile var availableSensorNames = listOf<String>()
        private set

    // Sensor type → human name
    private val sensorTypeName = mapOf(
        Sensor.TYPE_ACCELEROMETER to "Accelerometer",
        Sensor.TYPE_MAGNETIC_FIELD to "Magnetometer",
        Sensor.TYPE_GYROSCOPE to "Gyroscope",
        Sensor.TYPE_LIGHT to "Light",
        Sensor.TYPE_PRESSURE to "Barometer",
        Sensor.TYPE_PROXIMITY to "Proximity",
        Sensor.TYPE_GRAVITY to "Gravity",
        Sensor.TYPE_LINEAR_ACCELERATION to "LinearAccel",
        Sensor.TYPE_ROTATION_VECTOR to "RotationVec",
        12 to "Humidity",
        13 to "AmbientTemp",
        14 to "MagUncal",
        Sensor.TYPE_GAME_ROTATION_VECTOR to "GameRotVec",
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED to "GyroUncal",
        Sensor.TYPE_SIGNIFICANT_MOTION to "SigMotion",
        Sensor.TYPE_STEP_DETECTOR to "StepDetect",
        Sensor.TYPE_STEP_COUNTER to "StepCount",
        20 to "GeoRotVec",
        28 to "Pose6DoF",
        29 to "Stationary",
        30 to "MotionDetect",
        35 to "AccelUncal",
        36 to "HingeAngle",
        37 to "HeadTracker",
        42 to "Heading",
    )

    /**
     * Register ALL available sensors. Call from onCreate.
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        if (sensorThread == null) {
            val thread = HandlerThread("SensorHub").apply { start() }
            sensorThread = thread
            sensorHandler = Handler(thread.looper)
        }
        val allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        val types = mutableListOf<Int>()
        val names = mutableListOf<String>()

        Log.i(TAG, "═══════════════════════════════════════════")
        Log.i(TAG, "SENSOR ENUMERATION: ${allSensors.size} sensors found")
        Log.i(TAG, "═══════════════════════════════════════════")

        for (sensor in allSensors) {
            val typeName = sensorTypeName[sensor.type] ?: "Type${sensor.type}"
            Log.i(TAG, "  [${sensor.type}] $typeName: ${sensor.name} " +
                    "(vendor=${sensor.vendor}, power=${sensor.power}mA, " +
                    "maxRange=${sensor.maximumRange}, resolution=${sensor.resolution})")
            types.add(sensor.type)
            names.add("$typeName: ${sensor.name}")
        }

        availableSensorTypes = types
        availableSensorNames = names

        // Register continuous sensors at game rate
        val continuousTypes = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_ROTATION_VECTOR,
            12, // RELATIVE_HUMIDITY
            13, // AMBIENT_TEMPERATURE
            14, // MAGNETIC_FIELD_UNCALIBRATED
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_STEP_DETECTOR,
            20, // GEOMAGNETIC_ROTATION_VECTOR
            29, // STATIONARY_DETECT
            30, // MOTION_DETECT
            35, // ACCELEROMETER_UNCALIBRATED
            36, // HINGE_ANGLE
            37, // HEAD_TRACKER
            42, // HEADING
        )

        for (type in continuousTypes) {
            val sensor = sensorManager.getDefaultSensor(type)
            if (sensor != null) {
                // Use SENSOR_DELAY_GAME for IMU-type sensors, UI for others
                val rate = when (type) {
                    Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
                    Sensor.TYPE_GYROSCOPE_UNCALIBRATED, 35, 37 ->
                        SensorManager.SENSOR_DELAY_GAME
                    else -> SensorManager.SENSOR_DELAY_UI
                }
                sensorManager.registerListener(this, sensor, rate, sensorHandler ?: return)
                activeSensors[type] = sensor
                Log.i(TAG, "  ✓ Registered: ${sensorTypeName[type] ?: "Type$type"}")
            }
        }

        // Significant motion is a trigger sensor (one-shot)
        val sigMotion = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (sigMotion != null) {
            sensorManager.requestTriggerSensor(sigMotionTrigger, sigMotion)
            activeSensors[Sensor.TYPE_SIGNIFICANT_MOTION] = sigMotion
            Log.i(TAG, "  ✓ Registered trigger: SignificantMotion")
        }

        Log.i(TAG, "═══════════════════════════════════════════")
        Log.i(TAG, "ACTIVE SENSORS: ${activeSensors.size} registered")
        Log.i(TAG, "═══════════════════════════════════════════")
    }

    /**
     * Unregister all sensors. Call from onPause or when temporarily suspending.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        sensorManager.unregisterListener(this)
        val sigMotion = activeSensors[Sensor.TYPE_SIGNIFICANT_MOTION]
        if (sigMotion != null) {
            sensorManager.cancelTriggerSensor(sigMotionTrigger, sigMotion)
        }
        activeSensors.clear()
        Log.i(TAG, "All sensors unregistered")
    }

    /**
     * Full teardown — unregisters sensors AND quits the handler thread.
     * Call from onDestroy. After this, the SensorHub instance is unusable.
     */
    fun release() {
        stop()
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        Log.i(TAG, "SensorHub released (thread quit)")
    }

    // ── Trigger listener for significant motion ──
    private val sigMotionTrigger = object : android.hardware.TriggerEventListener() {
        override fun onTrigger(event: android.hardware.TriggerEvent) {
            significantMotionDetected = true
            Log.i(TAG, "Significant motion detected!")
            // Re-arm
            val sensor = activeSensors[Sensor.TYPE_SIGNIFICANT_MOTION]
            if (sensor != null) {
                sensorManager.requestTriggerSensor(this, sensor)
            }
        }
    }

    // ── SensorEventListener ──

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.isEmpty()) return
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> lightLux = v[0]
            Sensor.TYPE_ACCELEROMETER -> { if (v.size < 3) return; accelX = v[0]; accelY = v[1]; accelZ = v[2] }
            Sensor.TYPE_GYROSCOPE -> { if (v.size < 3) return; gyroX = v[0]; gyroY = v[1]; gyroZ = v[2] }
            Sensor.TYPE_MAGNETIC_FIELD -> { if (v.size < 3) return; magX = v[0]; magY = v[1]; magZ = v[2] }
            Sensor.TYPE_PRESSURE -> pressureHpa = v[0]
            Sensor.TYPE_PROXIMITY -> proximityCm = v[0]
            Sensor.TYPE_GRAVITY -> { if (v.size < 3) return; gravityX = v[0]; gravityY = v[1]; gravityZ = v[2] }
            Sensor.TYPE_LINEAR_ACCELERATION -> { if (v.size < 3) return; linAccelX = v[0]; linAccelY = v[1]; linAccelZ = v[2] }
            Sensor.TYPE_ROTATION_VECTOR -> {
                if (v.size < 3) return
                rotVecX = v[0]; rotVecY = v[1]; rotVecZ = v[2]
                rotVecW = if (v.size > 3) v[3] else kotlin.math.sqrt((1f - v[0]*v[0] - v[1]*v[1] - v[2]*v[2]).coerceAtLeast(0f))
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                if (v.size < 3) return
                gameRotX = v[0]; gameRotY = v[1]; gameRotZ = v[2]
                gameRotW = if (v.size > 3) v[3] else kotlin.math.sqrt((1f - v[0]*v[0] - v[1]*v[1] - v[2]*v[2]).coerceAtLeast(0f))
            }
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                if (v.size < 3) return
                gyroUncalX = v[0]; gyroUncalY = v[1]; gyroUncalZ = v[2]
                if (v.size >= 6) { gyroDriftX = v[3]; gyroDriftY = v[4]; gyroDriftZ = v[5] }
            }
            Sensor.TYPE_STEP_COUNTER -> stepCount = v[0].toLong()
            Sensor.TYPE_STEP_DETECTOR -> { stepDetected = true; lastStepTimestamp = event.timestamp }
            12 -> relativeHumidity = v[0]  // TYPE_RELATIVE_HUMIDITY
            13 -> ambientTempC = v[0]      // TYPE_AMBIENT_TEMPERATURE
            14 -> {  // TYPE_MAGNETIC_FIELD_UNCALIBRATED
                if (v.size < 3) return
                magUncalX = v[0]; magUncalY = v[1]; magUncalZ = v[2]
                if (v.size >= 6) { magBiasX = v[3]; magBiasY = v[4]; magBiasZ = v[5] }
            }
            20 -> {  // TYPE_GEOMAGNETIC_ROTATION_VECTOR
                if (v.size < 3) return
                geoRotX = v[0]; geoRotY = v[1]; geoRotZ = v[2]
                geoRotW = if (v.size > 3) v[3] else 1f
            }
            29 -> isStationary = (v[0] > 0.5f)  // TYPE_STATIONARY_DETECT
            30 -> isInMotion = (v[0] > 0.5f)     // TYPE_MOTION_DETECT
            35 -> {  // TYPE_ACCELEROMETER_UNCALIBRATED
                if (v.size < 3) return
                accelUncalX = v[0]; accelUncalY = v[1]; accelUncalZ = v[2]
                if (v.size >= 6) { accelBiasX = v[3]; accelBiasY = v[4]; accelBiasZ = v[5] }
            }
            36 -> hingeAngleDeg = v[0]  // TYPE_HINGE_ANGLE
            37 -> {  // TYPE_HEAD_TRACKER
                if (v.size < 3) return
                headRotX = v[0]; headRotY = v[1]; headRotZ = v[2]
                if (v.size >= 6) { headVelX = v[3]; headVelY = v[4]; headVelZ = v[5] }
            }
            42 -> headingDeg = v[0]  // TYPE_HEADING
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Accuracy changes are frequent and low-value; suppress in production
    }

    /**
     * Get a formatted debug string showing all active sensor values.
     */
    fun getDebugString(): String {
        val sb = StringBuilder()
        sb.appendLine("=== ANDROID SENSORS (${activeSensors.size} active) ===")

        if (Sensor.TYPE_LIGHT in activeSensors)
            sb.appendLine("Light: %.0f lux".format(lightLux))
        if (Sensor.TYPE_ACCELEROMETER in activeSensors)
            sb.appendLine("Accel: (%.2f, %.2f, %.2f) m/s²".format(accelX, accelY, accelZ))
        if (Sensor.TYPE_GYROSCOPE in activeSensors)
            sb.appendLine("Gyro: (%.3f, %.3f, %.3f) rad/s".format(gyroX, gyroY, gyroZ))
        if (Sensor.TYPE_MAGNETIC_FIELD in activeSensors)
            sb.appendLine("Mag: (%.1f, %.1f, %.1f) µT".format(magX, magY, magZ))
        if (Sensor.TYPE_PRESSURE in activeSensors)
            sb.appendLine("Baro: %.2f hPa".format(pressureHpa))
        if (Sensor.TYPE_PROXIMITY in activeSensors)
            sb.appendLine("Prox: %.1f cm".format(proximityCm))
        if (Sensor.TYPE_GRAVITY in activeSensors)
            sb.appendLine("Grav: (%.2f, %.2f, %.2f)".format(gravityX, gravityY, gravityZ))
        if (Sensor.TYPE_LINEAR_ACCELERATION in activeSensors)
            sb.appendLine("LinAcc: (%.3f, %.3f, %.3f)".format(linAccelX, linAccelY, linAccelZ))
        if (Sensor.TYPE_ROTATION_VECTOR in activeSensors)
            sb.appendLine("RotVec: (%.3f, %.3f, %.3f, %.3f)".format(rotVecX, rotVecY, rotVecZ, rotVecW))
        if (Sensor.TYPE_GAME_ROTATION_VECTOR in activeSensors)
            sb.appendLine("GameRot: (%.3f, %.3f, %.3f, %.3f)".format(gameRotX, gameRotY, gameRotZ, gameRotW))
        if (37 in activeSensors)
            sb.appendLine("HeadTrk: rot(%.3f,%.3f,%.3f) vel(%.3f,%.3f,%.3f)".format(
                headRotX, headRotY, headRotZ, headVelX, headVelY, headVelZ))
        if (Sensor.TYPE_STEP_COUNTER in activeSensors)
            sb.appendLine("Steps: $stepCount")
        if (Sensor.TYPE_PRESSURE in activeSensors && pressureHpa > 0) {
            // Estimate altitude from pressure (ISA sea level = 1013.25 hPa)
            val altitudeM = 44330.0 * (1.0 - Math.pow(pressureHpa / 1013.25, 1.0/5.255))
            sb.appendLine("Alt: %.1f m (est)".format(altitudeM))
        }
        if (42 in activeSensors)
            sb.appendLine("Heading: %.1f°".format(headingDeg))
        if (13 in activeSensors && ambientTempC != 0f)
            sb.appendLine("Temp: %.1f °C".format(ambientTempC))
        if (12 in activeSensors && relativeHumidity != 0f)
            sb.appendLine("Humidity: %.1f%%".format(relativeHumidity))

        return sb.toString().trimEnd()
    }

    /**
     * Returns number of active (registered) sensors.
     */
    fun activeCount(): Int = activeSensors.size

    /**
     * Check if a specific sensor type is available.
     */
    fun hasSensor(type: Int): Boolean = type in activeSensors
}
