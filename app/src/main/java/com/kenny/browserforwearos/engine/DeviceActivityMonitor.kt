package com.kenny.browserforwearos.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.wear.ambient.AmbientLifecycleObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A page runs only while its Activity and the watch are interactive.
 * Wear ambient callbacks are authoritative: global AOD can keep an Activity resumed.
 * Window focus is deliberately not a gate because an IME or system dialog may own it.
 */
@MainThread
class DeviceActivityMonitor(
    activity: ComponentActivity,
    private val onActiveChanged: (Boolean) -> Unit,
) : AutoCloseable {
    private val context = activity.applicationContext
    private val power = checkNotNull(context.getSystemService(PowerManager::class.java))
    private val displays = checkNotNull(context.getSystemService(DisplayManager::class.java))
    private var state = DeviceActivityState()
    private val _ambient = MutableStateFlow(false)
    val ambient = _ambient.asStateFlow()
    private var closed = false
    private var lastReported: Boolean? = null

    private val ambientObserver = AmbientLifecycleObserver(
        activity,
        ContextCompat.getMainExecutor(context),
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                if (closed) return
                state = state.copy(ambient = true)
                refreshHardware()
            }

            override fun onExitAmbient() {
                if (closed) return
                state = state.copy(ambient = false)
                refreshHardware()
            }
        },
    )

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshHardware()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshHardware()
        override fun onDisplayRemoved(displayId: Int) = refreshHardware()
        override fun onDisplayChanged(displayId: Int) = refreshHardware()
    }

    init {
        checkMainThread()
        // Let AndroidX receive the full lifecycle, including its controller's onDestroy cleanup.
        activity.lifecycle.addObserver(ambientObserver)
        state = state.copy(ambient = ambientObserver.isAmbient)
        ContextCompat.registerReceiver(
            context,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        displays.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        refreshHardware()
    }

    fun onResume() {
        checkMainThread()
        if (closed) return
        state = state.copy(resumed = true, ambient = ambientObserver.isAmbient)
        refreshHardware()
    }

    fun onPause() {
        checkMainThread()
        if (closed) return
        state = state.copy(resumed = false)
        publish()
    }

    private fun refreshHardware() {
        checkMainThread()
        if (closed) return
        state = state.copy(
            interactive = power.isInteractive,
            displayOn = displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON,
        )
        publish()
    }

    private fun publish() {
        _ambient.value = state.ambient
        val active = state.active
        if (lastReported != active) {
            lastReported = active
            onActiveChanged(active)
        }
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        state = state.copy(resumed = false)
        publish()
        closed = true
        context.unregisterReceiver(screenReceiver)
        displays.unregisterDisplayListener(displayListener)
        // ambientObserver stays lifecycle-owned so AndroidX can destroy its Wear controller.
        // Its callbacks are inert after close(), and the Activity releases it at destruction.
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "DeviceActivityMonitor requires the main thread"
    }
}

internal data class DeviceActivityState(
    val resumed: Boolean = false,
    val ambient: Boolean = false,
    val interactive: Boolean = false,
    val displayOn: Boolean = false,
) {
    val active: Boolean get() = resumed && !ambient && interactive && displayOn
}
