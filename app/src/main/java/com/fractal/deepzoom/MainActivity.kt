package com.fractal.deepzoom

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.graphics.drawable.GradientDrawable
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.slider.Slider
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var view: MandelbrotView
    private lateinit var controls: View
    private lateinit var iterSlider: Slider
    private lateinit var resSlider: Slider
    private lateinit var motionSlider: Slider
    private lateinit var motionLabel: TextView
    private lateinit var readout: TextView
    private lateinit var status: TextView
    private lateinit var exporter: ExportManager

    private val dimRunnable = Runnable { fadeControls(0.25f) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        view = findViewById(R.id.gl_view)
        controls = findViewById(R.id.controls)
        iterSlider = findViewById(R.id.iter_slider)
        resSlider = findViewById(R.id.res_slider)
        motionSlider = findViewById(R.id.motion_slider)
        motionLabel = findViewById(R.id.motion_label)
        readout = findViewById(R.id.readout)
        status = findViewById(R.id.status)
        exporter = ExportManager(view)

        // Log scale: deep zooms need orders of magnitude more iterations than shallow
        // ones, and a linear slider would spend its travel on values nobody wants.
        iterSlider.valueFrom = 5f      // 2^5  = 32
        iterSlider.valueTo = 16f       // 2^16 = 65536
        iterSlider.stepSize = 0.5f
        iterSlider.value = 9f
        iterSlider.addOnChangeListener { _, v, _ ->
            view.setMaxIter(2.0.pow(v.toDouble()).roundToInt())
            updateReadout()
            wake()
        }

        // Discrete power-of-two steps below native. Anything in between would mean
        // resampling, and the whole point of this control is sharpness.
        resSlider.valueFrom = 0f
        resSlider.valueTo = 3f
        resSlider.stepSize = 1f
        resSlider.value = 0f
        resSlider.addOnChangeListener { _, v, _ ->
            view.finestLevel = v.toInt()
            updateReadout()
            wake()
        }

        motionSlider.valueFrom = 0f
        motionSlider.valueTo = 2f
        motionSlider.stepSize = 1f
        motionSlider.value = 1f
        motionSlider.addOnChangeListener { _, v, _ ->
            view.motionQuality = v.toInt()
            updateMotionLabel()
            wake()
        }
        updateMotionLabel()

        findViewById<Button>(R.id.reset).setOnClickListener {
            view.resetView()
            iterSlider.value = 9f
            resSlider.value = 0f
            wake()
        }
        findViewById<Button>(R.id.colors).setOnClickListener { showPaletteDialog() }
        findViewById<Button>(R.id.save_png).setOnClickListener { showPngDialog() }
        findViewById<Button>(R.id.save_video).setOnClickListener { showVideoDialog() }
        findViewById<Button>(R.id.share_pos).setOnClickListener { copyPosition() }
        findViewById<Button>(R.id.goto_pos).setOnClickListener { showGoToDialog() }

        view.onViewChanged = { runOnUiThread { updateReadout() } }

        // Rebuilding a reference orbit at depth is the one operation here that is not
        // instant. Saying so beats an unexplained pause.
        view.renderer.onOrbitStateChanged = { busy ->
            runOnUiThread { status.visibility = if (busy) View.VISIBLE else View.GONE }
        }
        view.setOnTouchListener { v, e ->
            wake()
            v.onTouchEvent(e)
        }

        updateReadout()
        wake()
    }

    // --- Readout and control chrome ---------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun updateReadout() {
        val mode = if (view.state.needsPerturbation()) "perturbed" else "direct"
        val res = when (view.finestLevel) {
            0 -> "native"
            1 -> "1/2"
            2 -> "1/4"
            else -> "1/8"
        }
        readout.text = "Zoom 1e%.1f  ·  %d iter  ·  %s  ·  %s".format(
            view.state.zoomDepth(), view.state.maxIter, res, mode
        )
    }

    @SuppressLint("SetTextI18n")
    private fun updateMotionLabel() {
        motionLabel.text = "While moving — " + when (view.motionQuality) {
            MandelbrotRenderer.QUALITY_FULL -> "always full resolution"
            MandelbrotRenderer.QUALITY_FAST -> "always fast"
            else -> "full unless frames get slow"
        }
    }

    private fun wake() {
        controls.removeCallbacks(dimRunnable)
        fadeControls(1f)
        controls.postDelayed(dimRunnable, 2600)
    }

    private fun fadeControls(target: Float) {
        controls.animate().alpha(target).setDuration(320).start()
    }

    // --- Position sharing -------------------------------------------------------------

    private fun copyPosition() {
        val code = PositionCodec.encode(view.state, view.renderer.palette)
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("DeepZoom position", code))
        toast("Position copied")
        wake()
    }

    private fun showGoToDialog() {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_position, null)
        val input = content.findViewById<EditText>(R.id.pos_input)

        // Pre-fill from the clipboard if it already holds a position, since pasting is
        // the reason this dialog exists.
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()
            ?.takeIf { it.trimStart().startsWith("DZ1:") }
            ?.let { input.setText(it) }

        AlertDialog.Builder(this)
            .setTitle("Go to position")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Go") { _, _ ->
                val parsed = PositionCodec.decode(input.text.toString())
                if (parsed == null) {
                    toast("That does not look like a position code")
                } else {
                    view.applyState(parsed.state)
                    parsed.palette?.let { view.renderer.palette = it }
                    iterSlider.value = (kotlin.math.ln(parsed.state.maxIter.toDouble()) /
                        kotlin.math.ln(2.0)).toFloat().coerceIn(5f, 16f)
                    updateReadout()
                }
                wake()
            }
            .show()
    }


    // --- Colours ----------------------------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun showPaletteDialog() {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_palette, null)
        val preview = content.findViewById<View>(R.id.c_preview)
        val presetSlider = content.findViewById<Slider>(R.id.c_preset)
        val cycleSlider = content.findViewById<Slider>(R.id.c_cycle)
        val offsetSlider = content.findViewById<Slider>(R.id.c_offset)
        val presetLabel = content.findViewById<TextView>(R.id.c_preset_label)
        val cycleLabel = content.findViewById<TextView>(R.id.c_cycle_label)
        val offsetLabel = content.findViewById<TextView>(R.id.c_offset_label)
        val custom = content.findViewById<EditText>(R.id.c_custom)
        val interior = content.findViewById<EditText>(R.id.c_interior)

        val startingPalette = view.renderer.palette
        presetSlider.valueTo = (Palettes.PRESETS.size - 1).toFloat()

        val presetIndex = Palettes.PRESETS.indexOfFirst { it.name == startingPalette.name }
        presetSlider.value = (if (presetIndex >= 0) presetIndex else 0).toFloat()
        // Cycle length is a log slider: the useful range spans 4 to 8192 iterations per
        // trip, and a linear control would bunch everything usable into one end.
        cycleSlider.value = (ln(startingPalette.cycleLength.toDouble()) / ln(2.0))
            .toFloat().coerceIn(2f, 13f)
        offsetSlider.value = startingPalette.offset.coerceIn(0f, 1f)
        if (presetIndex < 0) {
            custom.setText(startingPalette.colors.joinToString(", ") { "#%06x".format(it) })
        }
        interior.setText("#%06x".format(startingPalette.interior and 0xFFFFFF))

        fun current(): PaletteSpec {
            val base = Palettes.PRESETS[presetSlider.value.toInt()]
            val typed = PaletteSpec.parseColors(custom.text.toString())
            val inside = PaletteSpec.parseColors(
                interior.text.toString() + "," + interior.text.toString()
            )?.firstOrNull() ?: 0x000000
            return base.copy(
                name = if (typed != null) "Custom" else base.name,
                colors = typed ?: base.colors,
                interior = inside,
                cycleLength = 2.0.pow(cycleSlider.value.toDouble()).toFloat(),
                offset = offsetSlider.value
            )
        }

        fun refresh() {
            val spec = current()
            presetLabel.text = "Palette — ${spec.name}"
            cycleLabel.text = "Band width — %.0f iterations per cycle".format(spec.cycleLength)
            offsetLabel.text = "Rotation — %.0f%%".format(spec.offset * 100)

            // Repeat the first stop at the end so the preview shows the wrap-around the
            // shader actually samples.
            val stops = (spec.colors + spec.colors.first())
                .map { 0xFF000000.toInt() or it }.toIntArray()
            preview.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, stops
            )
            // Live preview: the fractal updates as you drag, which is the only way to
            // judge band width honestly.
            view.renderer.palette = spec
            view.requestRender()
        }

        presetSlider.addOnChangeListener { _, _, _ ->
            custom.setText("")
            refresh()
        }
        cycleSlider.addOnChangeListener { _, _, _ -> refresh() }
        offsetSlider.addOnChangeListener { _, _, _ -> refresh() }
        refresh()

        AlertDialog.Builder(this)
            .setTitle("Colours")
            .setView(content)
            .setNegativeButton("Cancel") { _, _ ->
                view.renderer.palette = startingPalette
                view.requestRender()
            }
            .setPositiveButton("Apply") { _, _ ->
                view.renderer.palette = current()
                view.requestRender()
                wake()
            }
            .show()
    }

    // --- PNG --------------------------------------------------------------------------

    private val imageHeights = intArrayOf(720, 1080, 1440, 2160, 4320, 8640)

    @SuppressLint("SetTextI18n")
    private fun showPngDialog() {
        val screenAspect = view.width.toDouble() / view.height
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_image, null)

        val aspectSlider = content.findViewById<Slider>(R.id.i_aspect)
        val sizeSlider = content.findViewById<Slider>(R.id.i_size)
        val aspectLabel = content.findViewById<TextView>(R.id.i_aspect_label)
        val sizeLabel = content.findViewById<TextView>(R.id.i_size_label)
        val summary = content.findViewById<TextView>(R.id.i_summary)
        val warning = content.findViewById<TextView>(R.id.i_warning)

        fun dimensions(): Pair<Int, Int> {
            val h = imageHeights[sizeSlider.value.toInt()]
            val aspect = ExportManager.aspectValue(aspectSlider.value.toInt(), screenAspect)
            return Pair(ExportManager.alignedWidth(h, aspect), h)
        }

        fun refresh() {
            val (w, h) = dimensions()
            val idx = aspectSlider.value.toInt()
            aspectLabel.text = "Aspect ratio — ${ExportManager.ASPECT_LABELS[idx]}"
            sizeLabel.text = "Height — ${imageHeights[sizeSlider.value.toInt()]} px"
            summary.text = "$w x $h  ·  %.1f megapixels".format(w.toDouble() * h / 1e6)

            val cap = view.renderer.maxTextureSizeCached
            // Vertical span is what stays fixed, so a wider ratio shows more of the
            // plane rather than cropping the current framing.
            warning.text = if (cap > 0 && maxOf(w, h) > cap)
                "Above this device's ${cap} px render limit. It may fail — smaller sizes are safe."
            else
                "Height sets the vertical span; wider ratios reveal more to the sides."
        }

        aspectSlider.addOnChangeListener { _, _, _ -> refresh() }
        sizeSlider.addOnChangeListener { _, _, _ -> refresh() }
        refresh()

        AlertDialog.Builder(this)
            .setTitle("Save PNG")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Render") { _, _ ->
                val (w, h) = dimensions()
                runPngExport(w, h)
            }
            .show()
    }

    private fun runPngExport(w: Int, h: Int) {
        val dialog = progressDialog("Rendering $w x $h…")
        dialog.show()
        exporter.savePng(this, w, h) { uri, error ->
            runOnUiThread {
                dialog.dismiss()
                reportResult(uri, error, "Saved to Pictures/DeepZoom")
            }
        }
    }

    // --- Video ------------------------------------------------------------------------

    private val fpsOptions = intArrayOf(24, 30, 60)
    private val videoHeights = intArrayOf(720, 1080, 1440, 2160)
    private val qualityLabels = arrayOf("Standard", "High", "Maximum")
    private val qualityBpp = doubleArrayOf(0.25, 0.5, 1.0)

    @SuppressLint("SetTextI18n")
    private fun showVideoDialog() {
        val screenAspect = view.width.toDouble() / view.height
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_video, null)

        val aspectSlider = content.findViewById<Slider>(R.id.v_aspect)
        val resSlider = content.findViewById<Slider>(R.id.v_res)
        val fpsSlider = content.findViewById<Slider>(R.id.v_fps)
        val zoomSlider = content.findViewById<Slider>(R.id.v_zoom)
        val qualitySlider = content.findViewById<Slider>(R.id.v_quality)

        val aspectLabel = content.findViewById<TextView>(R.id.v_aspect_label)
        val resLabel = content.findViewById<TextView>(R.id.v_res_label)
        val fpsLabel = content.findViewById<TextView>(R.id.v_fps_label)
        val zoomLabel = content.findViewById<TextView>(R.id.v_zoom_label)
        val qualityLabel = content.findViewById<TextView>(R.id.v_quality_label)
        val summary = content.findViewById<TextView>(R.id.v_summary)
        val warning = content.findViewById<TextView>(R.id.v_warning)
        val expMapCheck = content.findViewById<android.widget.CheckBox>(R.id.v_expmap)
        val dumpCheck = content.findViewById<android.widget.CheckBox>(R.id.v_dumpstrip)
        val zoomInCheck = content.findViewById<android.widget.CheckBox>(R.id.v_zoomin)

        fun settings(): ExportManager.VideoSettings {
            val h = videoHeights[resSlider.value.toInt()]
            val aspect = ExportManager.aspectValue(aspectSlider.value.toInt(), screenAspect)
            return ExportManager.VideoSettings(
                width = ExportManager.alignedWidth(h, aspect),
                height = h,
                fps = fpsOptions[fpsSlider.value.toInt()],
                zoomPerFrame = zoomSlider.value.toDouble(),
                bitsPerPixel = qualityBpp[qualitySlider.value.toInt()],
                exponentialMap = expMapCheck.isChecked,
                zoomIn = zoomInCheck.isChecked,
                dumpStrip = dumpCheck.isChecked
            )
        }

        fun refresh() {
            val s = settings()
            val frames = ExportManager.frameCount(view.state.spanY, s)
            val seconds = frames.toDouble() / s.fps
            val mbps = s.width.toDouble() * s.height * s.fps * s.bitsPerPixel / 1e6

            aspectLabel.text =
                "Aspect ratio — ${ExportManager.ASPECT_LABELS[aspectSlider.value.toInt()]}"
            resLabel.text = "Resolution — ${s.width} x ${s.height}"
            fpsLabel.text = "Frame rate — ${s.fps} fps"
            zoomLabel.text = "Zoom out per frame — %.1f%%".format((s.zoomPerFrame - 1) * 100)
            qualityLabel.text =
                "Quality — ${qualityLabels[qualitySlider.value.toInt()]} (~%.0f Mbps)".format(mbps)
            summary.text = (if (zoomInCheck.isChecked) "Zoom in  ·  " else "Zoom out  ·  ") +
                "$frames frames  ·  %d:%02d long".format(
                (seconds / 60).toInt(), (seconds % 60).toInt()
            )
            // Report the real figure from the geometry that will actually be used,
            // rather than an idealised one: the strip gets narrowed to fit memory, and
            // that changes both the speedup and the corner sharpness.
            val geom = ExportManager.stripFor(
                s, view.state.spanY, view.renderer.maxTextureSizeCached
            )
            if (geom != null) {
                val ratio = StripGeometry.speedup(geom, s.width, s.height, s.zoomPerFrame)
                val soft = StripGeometry.cornerSoftness(geom, s.width, s.height)
                summary.append("  ·  ~%.0fx faster".format(ratio))
                if (soft > 1.3) {
                    summary.append("  ·  corners %.1fx softer".format(soft))
                }
            } else if (expMapCheck.isChecked) {
                summary.append("  ·  strip will not fit, rendering frames directly")
            }
            warning.text = if (frames > 400)
                "Long export. Every frame is a full render, and the view is blocked until done."
            else
                "Rendering blocks the view until done."
        }

        aspectSlider.addOnChangeListener { _, _, _ -> refresh() }
        resSlider.addOnChangeListener { _, _, _ -> refresh() }
        fpsSlider.addOnChangeListener { _, _, _ -> refresh() }
        zoomSlider.addOnChangeListener { _, _, _ -> refresh() }
        qualitySlider.addOnChangeListener { _, _, _ -> refresh() }
        expMapCheck.setOnCheckedChangeListener { _, _ -> refresh() }
        zoomInCheck.setOnCheckedChangeListener { _, _ -> refresh() }
        refresh()

        AlertDialog.Builder(this)
            .setTitle("Zoom-out video")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Render") { _, _ -> runVideoExport(settings()) }
            .show()
    }

    private fun runVideoExport(settings: ExportManager.VideoSettings) {
        val total = ExportManager.frameCount(view.state.spanY, settings)
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null)
        val label = content.findViewById<TextView>(R.id.p_label)
        val bar = content.findViewById<ProgressBar>(R.id.p_bar)
        label.text = "Rendering frame 0 of $total"

        val dialog = AlertDialog.Builder(this)
            .setTitle("Rendering video")
            .setView(content)
            .setCancelable(false)
            .create()
        dialog.show()

        val progress = object : ExportManager.Progress {
            override fun onProgress(frame: Int, total: Int) {
                runOnUiThread {
                    label.text = "Rendering frame $frame of $total"
                    bar.progress = frame * 100 / total
                }
            }
            override fun onStripBuild(rowsDone: Int, rowsTarget: Int) {
                runOnUiThread {
                    label.text = "Building strip — row $rowsDone of $rowsTarget"
                    bar.progress = rowsDone * 100 / rowsTarget.coerceAtLeast(1)
                }
            }
        }

        exporter.saveVideo(this, settings, progress) { uri, error ->
            runOnUiThread {
                dialog.dismiss()
                val diag = exporter.lastVideoDiag
                // A video that saved but repeated frames is not a failure, so it does
                // not get the failure dialog — but it must not pass as a silent success
                // either, which is the whole reason the check exists.
                if (error == null && uri != null && diag.isNotEmpty()) {
                    // Copy rather than screenshot: the report runs past a phone screen,
                    // and screenshotting it has proven unreliable.
                    AlertDialog.Builder(this)
                        .setTitle("Saved to Movies/DeepZoom")
                        .setMessage(diag)
                        .setNeutralButton("Copy") { _, _ ->
                            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("DeepZoom diagnostics", diag))
                            Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
                        }
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    reportResult(uri, error, "Saved to Movies/DeepZoom")
                }
            }
        }
    }

    // --- Shared helpers ---------------------------------------------------------------

    private fun progressDialog(message: String): AlertDialog {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null)
        content.findViewById<TextView>(R.id.p_label).text = message
        content.findViewById<ProgressBar>(R.id.p_bar).isIndeterminate = true
        return AlertDialog.Builder(this)
            .setView(content)
            .setCancelable(false)
            .create()
    }

    private fun reportResult(uri: Uri?, error: String?, successMessage: String) {
        when {
            error != null -> AlertDialog.Builder(this)
                .setTitle("Export failed")
                .setMessage(error)
                .setNeutralButton("Copy") { _, _ ->
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("DeepZoom diagnostics", error))
                    Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
                }
                .setPositiveButton("OK", null)
                .show()
            uri != null -> toast(successMessage)
            else -> toast("Nothing was written")
        }
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

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
