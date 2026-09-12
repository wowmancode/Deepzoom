package com.fractal.deepzoom

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.max
import kotlin.math.roundToInt

class MandelbrotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val state = ViewState()
    val renderer = MandelbrotRenderer(state)

    /**
     * Fraction of native resolution rendered when idle. The GL surface is genuinely
     * allocated at this size and the display hardware upscales it, so this is real
     * work avoided rather than a render-then-downsample.
     */
    var renderScale: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.1f, 1.0f)
            applySurfaceSize(field)
        }

    /** Extra reduction applied while a gesture is in flight. */
    var interactiveScale: Float = 0.5f

    var onViewChanged: (() -> Unit)? = null

    private var gesturing = false
    private var lastX = 0f
    private var lastY = 0f
    private var activePointerId = -1

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomAround(detector.focusX, detector.focusY, detector.scaleFactor.toDouble())
                return true
            }
        })

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        renderer.requestRender = { requestRender() }
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applySurfaceSize(if (gesturing) renderScale * interactiveScale else renderScale)
    }

    private fun applySurfaceSize(scale: Float) {
        if (width == 0 || height == 0) return
        holder.setFixedSize(
            max(1, (width * scale).roundToInt()),
            max(1, (height * scale).roundToInt())
        )
        requestRender()
    }

    // --- Gestures ---------------------------------------------------------------------
    // All touch maths happens in view pixels and plain doubles. Offsets within a frame
    // are small even at extreme depth, so only the accumulated centre needs BigDecimal.

    private fun pixelSpan(): Double = state.spanY / height.toDouble()

    private fun zoomAround(focusX: Float, focusY: Float, factor: Double) {
        if (factor <= 0.0) return

        val spanBefore = state.spanY
        state.zoomBy(factor)
        val spanAfter = state.spanY
        if (spanBefore == spanAfter) return

        // Keep the point under the fingers fixed: the centre shifts by the change in
        // how far the focus sits from it.
        val dxPix = focusX - width / 2.0
        val dyPix = focusY - height / 2.0
        val perPixel = (spanBefore - spanAfter) / height

        state.panBy(dxPix * perPixel, -dyPix * perPixel)

        requestRender()
        onViewChanged?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                beginGesture()
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && activePointerId != -1) {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx != -1) {
                        val dx = event.getX(idx) - lastX
                        val dy = event.getY(idx) - lastY
                        lastX = event.getX(idx)
                        lastY = event.getY(idx)

                        val span = pixelSpan()
                        state.panBy(-dx * span, dy * span)
                        requestRender()
                        onViewChanged?.invoke()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Hand the drag anchor to a finger still down, or the view jumps when
                // you lift one finger out of a pinch.
                val upIndex = event.actionIndex
                if (event.getPointerId(upIndex) == activePointerId) {
                    val newIndex = if (upIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(newIndex)
                    lastX = event.getX(newIndex)
                    lastY = event.getY(newIndex)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
                endGesture()
            }
        }
        return true
    }

    private fun beginGesture() {
        if (gesturing) return
        gesturing = true
        applySurfaceSize(renderScale * interactiveScale)
    }

    private fun endGesture() {
        if (!gesturing) return
        gesturing = false
        applySurfaceSize(renderScale)
    }

    fun resetView() {
        state.reset()
        renderer.invalidateOrbit()
        requestRender()
        onViewChanged?.invoke()
    }

    fun setMaxIter(value: Int) {
        state.maxIter = value
        requestRender()
        onViewChanged?.invoke()
    }
}
