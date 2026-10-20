package com.kenny.browserforwearos.presentation

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import org.mozilla.geckoview.GeckoView
import java.util.WeakHashMap
import kotlin.math.min

/** Only one complete touch sequence per view; disposal cancels every delayed event. */
internal object SingleHandZoom {
    private class State {
        var scale = 1f
        var busy = false
        var started = false
        var sendCancel: (() -> Unit)? = null
        val tasks = mutableListOf<Runnable>()
    }
    private val states = WeakHashMap<GeckoView, State>()

    fun zoom(view: GeckoView, factor: Float) {
        val state = states.getOrPut(view, ::State)
        if (state.busy) return
        schedule(view, state, factor.coerceIn(.1f, 10f))
    }

    fun reset(view: GeckoView) {
        val state = states.getOrPut(view, ::State)
        if (!state.busy && state.scale != 1f) schedule(view, state, 1f / state.scale)
    }

    fun cancel(view: GeckoView) {
        val state = states[view] ?: return
        state.tasks.forEach(view::removeCallbacks)
        state.tasks.clear()
        if (state.started) state.sendCancel?.invoke()
        state.busy = false
        state.started = false
        state.sendCancel = null
    }

    private fun schedule(view: GeckoView, state: State, factor: Float) {
        state.busy = true
        val begin = Runnable {
            if (!view.isAttachedToWindow || view.session?.isOpen != true || view.width <= 0 || view.height <= 0) {
                state.busy = false
                return@Runnable
            }
            val start = SystemClock.uptimeMillis()
            val cx = view.width / 2f
            val cy = view.height / 2f
            val radius = min(view.width, view.height) * .18f
            val props = Array(2) { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            fun send(action: Int, count: Int, distance: Float) {
                val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                    x = cx + (if (i == 0) -distance else distance); y = cy; pressure = 1f; size = .1f
                } }
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, count, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                view.dispatchTouchEvent(event)
                event.recycle()
            }
            fun post(after: Long, block: () -> Unit) {
                val task = Runnable(block)
                state.tasks += task
                view.postDelayed(task, after)
            }
            state.started = true
            state.sendCancel = { send(MotionEvent.ACTION_CANCEL, 2, radius) }
            send(MotionEvent.ACTION_DOWN, 1, radius)
            post(16) { send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, radius) }
            for (step in 1..12) post((step + 1) * 16L) {
                send(MotionEvent.ACTION_MOVE, 2, radius * (1 + (factor - 1) * step / 12f))
            }
            post(224) { send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, radius * factor) }
            post(240) {
                send(MotionEvent.ACTION_UP, 1, radius * factor)
                state.scale = (state.scale * factor).coerceIn(.05f, 20f)
                state.busy = false
                state.started = false
                state.sendCancel = null
                state.tasks.clear()
            }
        }
        state.tasks += begin
        // The menu dismisses and returns engine focus before beginning the gesture.
        view.postDelayed(begin, 180)
    }
}
