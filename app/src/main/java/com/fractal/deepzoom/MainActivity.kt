package com.fractal.deepzoom

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.slider.Slider
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var view: MandelbrotView
    private lateinit var controls: View
    private lateinit var iterSlider: Slider
    private lateinit var resSlider: Slider
    private lateinit var readout: TextView

    private val dimRunnable = Runnable { fadeControls(0.25f) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        view = findViewById(R.id.gl_view)
        controls = findViewById(R.id.controls)
        iterSlider = findViewById(R.id.iter_slider)
        resSlider = findViewById(R.id.res_slider)
        readout = findViewById(R.id.readout)

        // Iterations on a log scale: the useful range spans two orders of magnitude,
        // and a linear slider would put almost all of its travel in values you never want.
        iterSlider.valueFrom = 5f     // 2^5  = 32
        iterSlider.valueTo = 14f      // 2^14 = 16384
        iterSlider.stepSize = 0.5f
        iterSlider.value = 9f         // 512
        iterSlider.addOnChangeListener { _, v, _ ->
            view.renderer.maxIter = 2.0.pow(v.toDouble()).roundToInt()
            view.requestRender()
            updateReadout()
            wake()
        }

        resSlider.valueFrom = 0.25f
        resSlider.valueTo = 1.0f
        resSlider.stepSize = 0.125f
        resSlider.value = 1.0f
        resSlider.addOnChangeListener { _, v, _ ->
            view.renderScale = v
            updateReadout()
            wake()
        }

        findViewById<View>(R.id.reset).setOnClickListener {
            view.resetView()
            wake()
        }

        view.onViewChanged = {
            runOnUiThread { updateReadout() }
        }
        view.setOnTouchListener { v, e ->
            wake()
            v.onTouchEvent(e)
        }

        view.renderer.maxIter = 512
        updateReadout()
        wake()
    }

    @SuppressLint("SetTextI18n")
    private fun updateReadout() {
        val depth = view.zoomDepth()
        val pct = (view.renderScale * 100).roundToInt()
        readout.text = "Zoom 1e%.1f   ·   %d iterations   ·   %d%% resolution"
            .format(depth, view.renderer.maxIter, pct)
    }

    /** Bring the controls back to full opacity, then schedule them to recede. */
    private fun wake() {
        controls.removeCallbacks(dimRunnable)
        fadeControls(1f)
        controls.postDelayed(dimRunnable, 2600)
    }

    private fun fadeControls(target: Float) {
        controls.animate().alpha(target).setDuration(320).start()
    }

    override fun onResume() {
        super.onResume()
        view.onResume()
    }

    override fun onPause() {
        super.onPause()
        view.onPause()
    }
}
