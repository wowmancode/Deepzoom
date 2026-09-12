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
    private lateinit var status: TextView

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
        status = findViewById(R.id.status)

        // Log scale: the useful range spans three orders of magnitude, and a linear
        // slider would spend most of its travel on values nobody wants. Deep zooms
        // need far more iterations than shallow ones, hence the high ceiling.
        iterSlider.valueFrom = 5f      // 2^5  = 32
        iterSlider.valueTo = 16f       // 2^16 = 65536
        iterSlider.stepSize = 0.5f
        iterSlider.value = 9f          // 512
        iterSlider.addOnChangeListener { _, v, _ ->
            view.setMaxIter(2.0.pow(v.toDouble()).roundToInt())
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
            iterSlider.value = 9f
            wake()
        }

        view.onViewChanged = { runOnUiThread { updateReadout() } }

        // Building a reference orbit at depth takes a noticeable moment, and it is the
        // one operation here that is not instant. Saying so beats an unexplained pause.
        view.renderer.onOrbitStateChanged = { busy ->
            runOnUiThread {
                status.visibility = if (busy) View.VISIBLE else View.GONE
            }
        }
        view.setOnTouchListener { v, e ->
            wake()
            v.onTouchEvent(e)
        }

        updateReadout()
        wake()
    }

    @SuppressLint("SetTextI18n")
    private fun updateReadout() {
        val depth = view.state.zoomDepth()
        val mode = if (view.state.needsPerturbation()) "perturbed" else "direct"
        val pct = (view.renderScale * 100).roundToInt()

        readout.text = "Zoom 1e%.1f  ·  %d iter  ·  %d%%  ·  %s"
            .format(depth, view.state.maxIter, pct, mode)
    }

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

    override fun onDestroy() {
        super.onDestroy()
        view.renderer.shutdown()
    }
}
