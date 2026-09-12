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

    val renderer = MandelbrotRenderer()

    /**
     * Fraction of native resolution to render at when idle. The GL surface is
     * allocated smaller than the view and the display hardware upscales it for free,
     * so this is a true cost saving rather than a render-then-downsample trick.
     */
    var renderScale: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.1f, 1.0f)
            applySurfaceSize(field)
        }

    /**
     * Resolution multiplier applied on top of renderScale while a gesture is active.
     * Dropping to a quarter of the pixels during a drag is the difference between
     * pan feeling attached to your finger and feeling like it is catching up.
     */
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
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applySurfaceSize(if (gesturing) renderScale * interactiveScale else renderScale)
    }

    private fun applySurfaceSize(scale: Float) {
        if (width == 0 || height == 0) return
        val sw = max(1, (width * scale).roundToInt())
        val sh = max(1, (height * scale).roundToInt())
        holder.setFixedSize(sw, sh)
        requestRender()
    }

    // --- Coordinate mapping ---------------------------------------------------------
    // All touch math is done in *view* pixels, never surface pixels, so changing the
    // render resolution never shifts what is under your finger.

    private fun pixelSpan(): Double = renderer.spanY / height.toDouble()

    private fun screenToComplexX(sx: Float): Double =
        renderer.centerX + (sx - width / 2.0) * pixelSpan()

    private fun screenToComplexY(sy: Float): Double =
        // Screen y grows downward, the complex plane's grows upward.
        renderer.centerY - (sy - height / 2.0) * pixelSpan()

    private fun zoomAround(focusX: Float, focusY: Float, factor: Double) {
        if (factor <= 0.0) return

        val cxBefore = screenToComplexX(focusX)
        val cyBefore = screenToComplexY(focusY)

        // Clamp the span so float32 pixel quantisation stays off-screen until the
        // perturbation path exists to handle deeper zooms.
        renderer.spanY = (renderer.spanY / factor).coerceIn(MIN_SPAN, MAX_SPAN)

        val cxAfter = screenToComplexX(focusX)
        val cyAfter = screenToComplexY(focusY)

        renderer.centerX += cxBefore - cxAfter
        renderer.centerY += cyBefore - cyAfter

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
                        renderer.centerX -= dx * span
                        renderer.centerY += dy * span
                        requestRender()
                        onViewChanged?.invoke()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Hand the drag anchor to a finger that is still down, otherwise the
                // view jumps when you lift one finger out of a pinch.
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
        renderer.centerX = -0.5
        renderer.centerY = 0.0
        renderer.spanY = 3.0
        requestRender()
        onViewChanged?.invoke()
    }

    /** Zoom depth relative to the default view, as a power of ten. */
    fun zoomDepth(): Double = kotlin.math.log10(3.0 / renderer.spanY)

    companion object {
        // float32 runs out of usable mantissa here. Raise once perturbation lands.
        private const val MIN_SPAN = 1e-6
        private const val MAX_SPAN = 8.0
    }
}
