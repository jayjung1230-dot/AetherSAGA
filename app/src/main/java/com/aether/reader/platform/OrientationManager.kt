package com.aether.reader.platform

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.view.OrientationEventListener
import com.aether.reader.data.Prefs

/**
 * Hardware-Aware Adaptive Orientation Engine
 * 1. Manual choices are only Portrait (세로) / Landscape (가로).
 * 2. Devices with a gravity/accelerometer sensor auto-rotate following the sensor.
 * 3. Rotation can be locked (freezes the current orientation, persisted in prefs).
 * 4. Devices without a sensor (common e-readers) stay in Portrait unless the user rotates manually.
 */
class OrientationManager(
    private val activity: Activity,
    private val prefs: Prefs,
    private val onOrientationChanged: (isLandscape: Boolean) -> Unit
) {
    private val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val hasPhysicalSensor: Boolean by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) != null ||
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }

    private var orientationListener: OrientationEventListener? = null
    private var lastAppliedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    /** Last orientation bucket reported by the sensor (-1 unknown). */
    private var lastSensorTarget: Int = -1
    /** After a manual toggle, auto-rotation stays suspended until the device is physically moved to another bucket. */
    private var suspendedSensorTarget: Int = -1

    val isLocked: Boolean get() = prefs.orientationLocked

    init {
        if (prefs.orientationLocked && prefs.lockedOrientation >= 0) {
            applyOrientation(prefs.lockedOrientation, notify = false)
        } else if (!hasPhysicalSensor) {
            applyOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, notify = false)
        }
        initSensorListener()
    }

    private fun isLandscape(o: Int) =
        o == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || o == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE

    private fun applyOrientation(target: Int, notify: Boolean = true) {
        val changed = lastAppliedOrientation != target
        lastAppliedOrientation = target
        activity.requestedOrientation = target
        if (notify && changed) onOrientationChanged(isLandscape(target))
    }

    private fun initSensorListener() {
        if (!hasPhysicalSensor) return

        orientationListener = object : OrientationEventListener(activity) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val target = when (orientation) {
                    in 60..120 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    in 150..210 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                    in 240..300 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    in 330..360, in 0..30 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else -> return // dead-zone hysteresis to avoid boundary oscillation
                }
                lastSensorTarget = target

                if (prefs.orientationLocked) return
                if (suspendedSensorTarget != -1) {
                    if (target == suspendedSensorTarget) return
                    suspendedSensorTarget = -1 // device was physically moved: resume auto-rotation
                }
                applyOrientation(target)
            }
        }
    }

    fun start() {
        if (hasPhysicalSensor) orientationListener?.enable()
    }

    fun stop() {
        orientationListener?.disable()
    }

    /** Manual toggle between exactly two choices: Portrait <-> Landscape. */
    fun toggleOrientation(): String {
        val goLandscape = !isLandscape(lastAppliedOrientation)
        val target = if (goLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (hasPhysicalSensor) suspendedSensorTarget = lastSensorTarget
        applyOrientation(target)
        if (prefs.orientationLocked) prefs.lockedOrientation = target
        return if (goLandscape) "가로" else "세로"
    }

    /** Locks/unlocks rotation. Returns the new locked state. */
    fun toggleLock(): Boolean {
        val lock = !prefs.orientationLocked
        prefs.orientationLocked = lock
        if (lock) {
            prefs.lockedOrientation = lastAppliedOrientation
        } else {
            suspendedSensorTarget = -1
            if (hasPhysicalSensor && lastSensorTarget != -1) applyOrientation(lastSensorTarget)
        }
        return lock
    }

    fun lockLabel(): String = if (prefs.orientationLocked) "회전 잠금: 켜짐" else "회전 잠금: 꺼짐"
}
