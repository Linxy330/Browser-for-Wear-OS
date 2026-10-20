package com.kenny.browserforwearos.presentation

import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.annotation.UiThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import kotlin.math.abs
import kotlin.math.min

/**
 * Measures Gecko's real CSS-to-surface scale. Synthetic input is corrected from native
 * measurements; a local product of button factors is never used as the current scale.
 * Own one instance per logical tab, rather than per Compose viewport.
 */
@UiThread
class PageZoomController {
    enum class Outcome { APPLIED, CANCELLED, NO_VIEW, NO_BASELINE, VIEWPORT_CHANGED, LIMITED, INVALID_ARGUMENT }
    data class Result(val outcome: Outcome, val actualScale: Float?, val targetScale: Float?)
    data class State(
        val actualScale: Float? = null, val baselineScale: Float? = null,
        val busy: Boolean = false, val lastOutcome: Outcome? = null,
    ) {
        val percent: Int? get() = if (actualScale != null && baselineScale != null && baselineScale > 0) (actualScale / baselineScale * 100).toInt() else null
    }
    private data class Baseline(val scale: Float, val width: Int, val height: Int, val densityDpi: Int)
    private class Operation(
        val view: GeckoView, val session: GeckoSession, val epoch: Long,
        val target: Float, val result: GeckoResult<Result>,
    ) {
        var attempts = 0
        var corrections = 0
        var radius = 0f
        var started = false
        var twoPointers = false
        var downTime = 0L
        val tasks = mutableListOf<Runnable>()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val baselines = object : LinkedHashMap<String, Baseline>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Baseline>?): Boolean = size > 32
    }
    private var documentKey: String? = null
    private var nativeSession: GeckoSession? = null
    private var viewport: GeckoView? = null
    private var epoch = 0L
    private var painted = false
    private var nativeViewportReady = false
    private var initialCaptureAllowed = false
    private var captureScheduled = false
    private var sendingSynthetic = false
    private var operation: Operation? = null

    private fun mainThread() = check(Looper.myLooper() == Looper.getMainLooper()) { "PageZoomController requires the main thread" }

    /**
     * Call from onPageStart with the logical original URL. For known BFCache URLs the
     * cached unzoomed baseline is reused. For an unknown history restore pass false:
     * its current (possibly pinched) scale must not be mistaken for an initial scale.
     */
    fun documentStarted(key: String, session: GeckoSession, allowInitialBaseline: Boolean) {
        mainThread()
        cancel()
        epoch++
        documentKey = key
        nativeSession = session
        painted = false
        nativeViewportReady = false
        initialCaptureAllowed = allowInitialBaseline && !baselines.containsKey(key)
        captureScheduled = false
        publish()
    }

    /** Attach after GeckoView.setSession. Detaching keeps document baselines in this tab. */
    fun attach(view: GeckoView, session: GeckoSession) {
        mainThread()
        if (viewport !== view || nativeSession !== session) cancel()
        if (nativeSession !== session) {
            epoch++
            painted = false
            nativeViewportReady = false
            initialCaptureAllowed = false
            captureScheduled = false
        }
        viewport = view
        nativeSession = session
        publish()
        if (painted && initialCaptureAllowed) scheduleBaselineCapture()
    }

    fun detach(view: GeckoView) {
        mainThread()
        if (viewport !== view) return
        cancel()
        viewport = null
    }

    /** Forward ContentDelegate.onFirstContentfulPaint; waits for stable native frames. */
    fun contentPainted(session: GeckoSession) {
        mainThread()
        if (nativeSession !== session) return
        painted = true
        if (initialCaptureAllowed) scheduleBaselineCapture()
    }

    /** Forward CompositorScrollDelegate, whose ScrollPositionUpdate exposes real zoom. */
    fun viewportChanged(session: GeckoSession, zoom: Float) {
        mainThread()
        if (nativeSession !== session || !zoom.isFinite() || zoom <= 0) return
        // GeckoSession's matrix starts at identity, even when the actual initial
        // CSS-to-surface scale is different. Only this compositor callback confirms
        // that its mViewportZoom has been populated by native rendering.
        nativeViewportReady = true
        _state.value = _state.value.copy(actualScale = zoom, baselineScale = baseline()?.scale)
        if (painted && initialCaptureAllowed) scheduleBaselineCapture()
    }

    /** Forward the view's touch listener and return false from that listener. */
    fun observeTouch(event: MotionEvent) {
        mainThread()
        if (sendingSynthetic) return
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            if (initialCaptureAllowed) {
                // DOWN arrives before Gecko handles focus/auto-zoom or a pinch. If
                // stable native samples are not ready yet, no initial scale can be
                // established safely. Never adopt the gesture's eventual zoom.
                initialCaptureAllowed = false
                captureScheduled = false
                publish(Outcome.NO_BASELINE)
            }
        }
        if (event.pointerCount > 1 && initialCaptureAllowed) {
            // Initial paint/callback ordering was too late to establish a trustworthy baseline.
            initialCaptureAllowed = false
            captureScheduled = false
            publish(Outcome.NO_BASELINE)
        }
    }

    fun zoomBy(factor: Float): GeckoResult<Result> {
        mainThread()
        if (!factor.isFinite() || factor <= 0 || factor > 10) return immediate(Outcome.INVALID_ARGUMENT, null)
        val actual = measure() ?: return immediate(Outcome.NO_VIEW, null)
        return zoomTo(actual * factor)
    }

    /** Caller first restores its fit-rectangle layout, then invokes this after layout. */
    fun resetToFit(): GeckoResult<Result> {
        mainThread()
        val baseline = baseline() ?: return immediate(Outcome.NO_BASELINE, null)
        val view = viewport ?: return immediate(Outcome.NO_VIEW, baseline.scale)
        if (view.width != baseline.width || view.height != baseline.height || view.resources.displayMetrics.densityDpi != baseline.densityDpi) {
            return immediate(Outcome.VIEWPORT_CHANGED, baseline.scale)
        }
        return zoomTo(baseline.scale)
    }

    fun cancel() {
        mainThread()
        operation?.let { finish(it, Outcome.CANCELLED) }
    }

    fun close() {
        mainThread()
        cancel()
        epoch++
        viewport = null
        nativeSession = null
        nativeViewportReady = false
        documentKey = null
        baselines.clear()
        initialCaptureAllowed = false
        captureScheduled = false
        _state.value = State()
    }

    /** A fresh reload can deliberately invalidate a prior baseline for changed viewport meta. */
    fun forgetBaseline(key: String) {
        mainThread()
        baselines.remove(key)
        publish()
    }

    private fun baseline(): Baseline? = documentKey?.let(baselines::get)

    private fun captureBaseline(view: GeckoView, scale: Float) {
        documentKey?.let { baselines[it] = Baseline(scale, view.width, view.height, view.resources.displayMetrics.densityDpi) }
        initialCaptureAllowed = false
        captureScheduled = false
        publish()
    }

    private fun measure(): Float? {
        if (!nativeViewportReady) return null
        val native = nativeSession ?: return null
        if (!native.isOpen) return null
        val matrix = Matrix()
        native.getClientToSurfaceMatrix(matrix)
        val values = FloatArray(9)
        matrix.getValues(values)
        return values[Matrix.MSCALE_X].takeIf { it.isFinite() && it > 0 }
    }

    private fun publish(outcome: Outcome? = _state.value.lastOutcome) {
        _state.value = State(measure(), baseline()?.scale, operation != null, outcome)
    }

    private fun immediate(outcome: Outcome, target: Float?): GeckoResult<Result> {
        publish(outcome)
        return GeckoResult.fromValue(Result(outcome, measure(), target))
    }

    private fun scheduleBaselineCapture() {
        if (captureScheduled || !initialCaptureAllowed || !painted || !nativeViewportReady) return
        captureScheduled = true
        val captureEpoch = epoch
        var previous: Float? = null
        var stable = 0
        var tries = 0
        lateinit var sample: Runnable
        sample = Runnable {
            if (captureEpoch != epoch) return@Runnable
            if (!initialCaptureAllowed || !painted) { captureScheduled = false; return@Runnable }
            val view = viewport
            val native = nativeSession
            val value = measure()
            if (view != null && native != null && view.session === native && view.isAttachedToWindow && view.width > 0 && view.height > 0 && value != null) {
                stable = if (previous != null && abs(value / previous!! - 1f) <= .003f) stable + 1 else 0
                previous = value
                if (stable >= 2) {
                    captureBaseline(view, value)
                    return@Runnable
                }
            }
            if (++tries < 24) handler.postDelayed(sample, 32) else captureScheduled = false
        }
        handler.postDelayed(sample, 32)
    }

    private fun zoomTo(target: Float): GeckoResult<Result> {
        val view = viewport ?: return immediate(Outcome.NO_VIEW, target)
        val native = nativeSession ?: return immediate(Outcome.NO_VIEW, target)
        if (!target.isFinite() || target <= 0) return immediate(Outcome.INVALID_ARGUMENT, target)
        if (!view.isAttachedToWindow || view.session !== native || !native.isOpen || view.width < 20 || view.height < 20) return immediate(Outcome.NO_VIEW, target)
        if (!nativeViewportReady) return immediate(Outcome.NO_BASELINE, target)
        cancel()
        // A button press before the initial stable frames must not capture its own
        // synthetic pinch as the document's unzoomed baseline.
        initialCaptureAllowed = false
        captureScheduled = false
        val result = GeckoResult<Result>(handler)
        val next = Operation(view, native, epoch, target, result)
        operation = next
        publish(null)
        if (withinTarget(measure(), target)) finish(next, Outcome.APPLIED) else beginAttempt(next)
        return result
    }

    private fun current(op: Operation): Boolean = operation === op && op.epoch == epoch &&
        viewport === op.view && nativeSession === op.session && op.view.session === op.session && op.view.isAttachedToWindow && op.session.isOpen

    private fun post(op: Operation, delay: Long, action: () -> Unit) {
        lateinit var task: Runnable
        task = Runnable {
            op.tasks.remove(task)
            if (current(op)) action() else if (operation === op) finish(op, Outcome.CANCELLED)
        }
        op.tasks.add(task)
        handler.postDelayed(task, delay)
    }

    private fun beginAttempt(op: Operation) {
        if (!current(op)) { finish(op, Outcome.CANCELLED); return }
        if (++op.attempts > MAX_ATTEMPTS) { finish(op, Outcome.LIMITED); return }
        op.corrections = 0
        op.radius = min(op.view.width, op.view.height) * .16f
        op.downTime = SystemClock.uptimeMillis()
        op.started = true
        op.twoPointers = false
        send(op, MotionEvent.ACTION_DOWN, 1, op.radius)
        post(op, 16) {
            op.twoPointers = true
            send(op, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, op.radius)
        }
        // Cross touch slop before relying on feedback; subsequent corrections stay in this
        // active gesture, so a small requested reset is not swallowed by pinch activation.
        val start = op.radius
        for (step in 1..4) post(op, (step + 1) * 16L) {
            op.radius = start * (1f + .25f * step / 4f)
            send(op, MotionEvent.ACTION_MOVE, 2, op.radius)
        }
        post(op, 160) { correct(op) }
    }

    private fun correct(op: Operation) {
        val actual = measure()
        if (actual == null || withinTarget(actual, op.target) || op.corrections++ >= MAX_CORRECTIONS) { releaseAndCheck(op); return }
        val side = min(op.view.width, op.view.height).toFloat()
        val start = op.radius
        val end = (start * op.target / actual).coerceIn(side * .04f, side * .40f)
        if (abs(end - start) < .5f) { releaseAndCheck(op); return }
        for (step in 1..6) post(op, step * 16L) {
            op.radius = start + (end - start) * step / 6f
            send(op, MotionEvent.ACTION_MOVE, 2, op.radius)
        }
        post(op, 192) { correct(op) }
    }

    private fun releaseAndCheck(op: Operation) {
        if (op.twoPointers) send(op, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, op.radius)
        op.twoPointers = false
        post(op, 16) {
            send(op, MotionEvent.ACTION_UP, 1, op.radius)
            op.started = false
        }
        post(op, 144) {
            when {
                withinTarget(measure(), op.target) -> finish(op, Outcome.APPLIED)
                op.attempts >= MAX_ATTEMPTS -> finish(op, Outcome.LIMITED)
                else -> beginAttempt(op)
            }
        }
    }

    private fun send(op: Operation, action: Int, count: Int, radius: Float) {
        val properties = Array(count) { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(count) { index -> MotionEvent.PointerCoords().apply {
            x = op.view.width / 2f + if (index == 0) -radius else radius
            y = op.view.height / 2f
            pressure = 1f
            size = .1f
        } }
        val event = MotionEvent.obtain(op.downTime, SystemClock.uptimeMillis(), action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        sendingSynthetic = true
        try { op.view.dispatchTouchEvent(event) } finally { sendingSynthetic = false; event.recycle() }
    }

    private fun finish(op: Operation, outcome: Outcome) {
        if (operation !== op) return
        op.tasks.forEach(handler::removeCallbacks)
        op.tasks.clear()
        if (op.started) send(op, MotionEvent.ACTION_CANCEL, if (op.twoPointers) 2 else 1, op.radius)
        op.started = false
        operation = null
        publish(outcome)
        op.result.complete(Result(outcome, measure(), op.target))
    }

    private fun withinTarget(actual: Float?, target: Float): Boolean = actual != null && abs(actual / target - 1f) <= RELATIVE_TOLERANCE

    companion object {
        const val RELATIVE_TOLERANCE = .015f
        private const val MAX_ATTEMPTS = 5
        private const val MAX_CORRECTIONS = 4
    }
}
