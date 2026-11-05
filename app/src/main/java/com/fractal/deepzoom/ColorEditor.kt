// SPDX-License-Identifier: AGPL-3.0-or-later
package com.fractal.deepzoom

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** One colour stop: where it sits around the cycle, in [0, 1), and its colour. */
class ColorStop(var pos: Float, var color: Int)

/**
 * Full-screen colour editor.
 *
 * The top of the screen is a preview box: the live view, cropped to its centre at one
 * pixel per pixel, recoloured on every change. It stays a real view -- dragging or
 * pinching inside it moves the fractal, so you can check a palette against more than
 * one spot. The panel below holds the gradient with its stops, a hue/saturation/value
 * picker with a hex field, band width and rotation, the interior colour, and presets.
 *
 * Changes apply to the live view as they are made, which is cheap because the frame
 * holds escape data and colour is applied when it is presented. Apply keeps them;
 * Cancel or Back restores the palette the editor opened with.
 */
@SuppressLint("SetTextI18n", "ViewConstructor")
class ColorEditor(
    private val ctx: Context,
    private val host: FrameLayout,
    private val glView: MandelbrotView,
    private val onClosed: () -> Unit
) {
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    private val original = glView.renderer.palette
    private var name = original.name
    private val stops = ArrayList<ColorStop>()
    private var selected: ColorStop
    private var editingInterior = false
    private var interior = original.interior and 0xFFFFFF
    private var cycle = original.cycleLength
    private var offset = original.offset

    private val overlay = FrameLayout(ctx)
    private val boxEdge = View(ctx)
    private val panel = LinearLayout(ctx)
    private val bar: GradientBarView
    private val picker: ColorPickerView
    private val hex = EditText(ctx)
    private val swatch = View(ctx)
    private val interiorSwatch = View(ctx)
    private val stopLabel = TextView(ctx)
    private val interiorLabel = TextView(ctx)
    private val nameLabel = TextView(ctx)
    private val cycleLabel = TextView(ctx)
    private val offsetLabel = TextView(ctx)
    private val cycleSlider = Slider(ctx)
    private val offsetSlider = Slider(ctx)
    private val deleteButton: TextView
    private var updatingHex = false
    private var boxH = 0

    /** Added to touches on the preview box so a pinch zooms about where you pinch. */
    var touchOffsetY = 0f
        private set

    init {
        loadStops(original)
        selected = stops.first()

        bar = GradientBarView(ctx).also {
            it.stops = stops
            it.selected = selected
            it.spec = { spec() }
            it.onSelect = { s -> select(s) }
            it.onMoved = { name = "Custom"; push() }
            it.onAdd = { pos -> addStopAt(pos) }
        }
        picker = ColorPickerView(ctx).also {
            it.onChange = { rgb -> setTargetColor(rgb, fromPicker = true) }
        }
        deleteButton = chip("Delete stop") { deleteSelected() }
        build()
    }

    // --- Lifecycle --------------------------------------------------------------------

    fun open() {
        host.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutBox() }
        // Added after the window dispatched its insets, so ask again: the panel pads
        // itself clear of the navigation bar and, while typing a hex value, the keyboard.
        panel.requestApplyInsets()
        layoutBox()
        refreshAll()
        push()
    }

    fun cancel() = close(apply = false)

    private fun close(apply: Boolean) {
        hideKeyboard()
        glView.renderer.palette = if (apply) spec() else original
        glView.renderer.previewBox = null
        touchOffsetY = 0f
        glView.requestRender()
        host.removeView(overlay)
        onClosed()
    }

    /** Sizes the preview box and the panel, and tells the renderer where the box is. */
    private fun layoutBox() {
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        val newBox = (h * 0.40f).toInt()
        if (newBox != boxH) {
            boxH = newBox
            (panel.layoutParams as FrameLayout.LayoutParams).let {
                it.topMargin = boxH
                panel.layoutParams = it
            }
            (boxEdge.layoutParams as FrameLayout.LayoutParams).let {
                it.topMargin = boxH - dp(1f)
                boxEdge.layoutParams = it
            }
        }
        // The GL surface fills the host, and GL counts rows from the bottom.
        glView.renderer.previewBox = intArrayOf(0, h - boxH, w, boxH)
        touchOffsetY = h * 0.5f - boxH * 0.5f
        glView.requestRender()
    }

    // --- Model ------------------------------------------------------------------------

    private fun loadStops(spec: PaletteSpec) {
        stops.clear()
        val pos = spec.stopPositions()
        for (i in spec.colors.indices) stops.add(ColorStop(pos[i], spec.colors[i] and 0xFFFFFF))
    }

    private fun sorted(): List<ColorStop> = stops.sortedBy { it.pos }

    fun spec(): PaletteSpec {
        val s = sorted()
        val n = s.size
        val even = s.indices.all { abs(s[it].pos - it.toFloat() / n) < 1e-4f }
        return PaletteSpec(
            name = name,
            colors = s.map { it.color },
            interior = interior,
            cycleLength = cycle,
            offset = offset,
            positions = if (even) null else s.map { it.pos }
        )
    }

    /** Sends the current palette to the live view and redraws the editor's own parts. */
    private fun push() {
        glView.renderer.palette = spec()
        glView.requestRender()
        bar.invalidate()
        nameLabel.text = name
        deleteButton.alpha = if (stops.size > 2) 1f else 0.35f
    }

    private fun select(s: ColorStop) {
        selected = s
        editingInterior = false
        bar.selected = s
        refreshTarget()
        bar.invalidate()
    }

    private fun addStopAt(pos: Float) {
        val p = pos.coerceIn(0f, 0.9999f)
        val s = ColorStop(p, Palettes.colorAt(spec(), p.toDouble()))
        stops.add(s)
        name = "Custom"
        select(s)
        push()
    }

    /** Adds a stop halfway between the selected one and the next around the cycle. */
    private fun addAfterSelected() {
        val s = sorted()
        val i = s.indexOf(selected).coerceAtLeast(0)
        val a = s[i].pos
        val b = if (i + 1 < s.size) s[i + 1].pos else s[0].pos + 1f
        var mid = (a + b) * 0.5f
        if (mid >= 1f) mid -= 1f
        addStopAt(mid)
    }

    private fun deleteSelected() {
        if (stops.size <= 2) return
        val s = sorted()
        val i = s.indexOf(selected)
        stops.remove(selected)
        name = "Custom"
        select(sorted()[i.coerceIn(0, stops.size - 1)])
        push()
    }

    private fun spreadEvenly() {
        val s = sorted()
        s.forEachIndexed { i, st -> st.pos = i.toFloat() / s.size }
        name = "Custom"
        push()
    }

    private fun reverse() {
        val s = sorted()
        val colours = s.map { it.color }.reversed()
        s.forEachIndexed { i, st -> st.color = colours[i] }
        name = "Custom"
        refreshTarget()
        push()
    }

    private fun loadPreset(p: PaletteSpec) {
        name = p.name
        loadStops(p)
        select(stops.first())
        push()
    }

    private fun targetColor(): Int = if (editingInterior) interior else selected.color

    private fun setTargetColor(rgb: Int, fromPicker: Boolean = false, fromHex: Boolean = false) {
        val c = rgb and 0xFFFFFF
        if (editingInterior) interior = c else {
            selected.color = c
            name = "Custom"
        }
        if (!fromPicker) picker.setColor(c)
        if (!fromHex) setHexText(c)
        refreshSwatches()
        push()
    }

    // --- View refresh -----------------------------------------------------------------

    private fun refreshAll() {
        cycleSlider.value = (ln(cycle.toDouble()) / ln(2.0)).toFloat()
            .coerceIn(cycleSlider.valueFrom, cycleSlider.valueTo)
        offsetSlider.value = (offset - kotlin.math.floor(offset)).coerceIn(0f, 1f)
        refreshSliderLabels()
        refreshTarget()
    }

    private fun refreshTarget() {
        picker.setColor(targetColor())
        setHexText(targetColor())
        refreshSwatches()
    }

    private fun refreshSwatches() {
        swatch.background = roundRect(0xFF000000.toInt() or selected.color, if (!editingInterior) 0xFFE4E8F0.toInt() else 0x40FFFFFF)
        interiorSwatch.background = roundRect(0xFF000000.toInt() or interior, if (editingInterior) 0xFFE4E8F0.toInt() else 0x40FFFFFF)
        stopLabel.alpha = if (editingInterior) 0.5f else 1f
        interiorLabel.alpha = if (editingInterior) 1f else 0.5f
    }

    private fun setHexText(c: Int) {
        updatingHex = true
        val t = "#%06X".format(c and 0xFFFFFF)
        if (hex.text.toString() != t) hex.setText(t)
        updatingHex = false
    }

    private fun refreshSliderLabels() {
        cycleLabel.text = "Band width — %.0f iterations per cycle".format(cycle)
        offsetLabel.text = "Rotation — %.0f%%".format(offsetSlider.value * 100)
    }

    // --- Layout -----------------------------------------------------------------------

    private fun build() {
        // The box itself is empty: touches there fall through to the fractal view.
        boxEdge.setBackgroundColor(0x60FFFFFF)
        overlay.addView(boxEdge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)
        ))

        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(0xFF0F1216.toInt())
        panel.isClickable = true   // keeps panel touches away from the fractal
        panel.setOnApplyWindowInsetsListener { v, insets ->
            val sys = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(0, 0, 0, max(sys.bottom, ime.bottom))
            insets
        }
        overlay.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val scroll = ScrollView(ctx)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
        }
        scroll.addView(content)
        panel.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Title row: name of the palette.
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Colours", 17f, 0xFFE4E8F0.toInt()))
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            nameLabel.textSize = 13f
            nameLabel.setTextColor(0xFF8A93A3.toInt())
            addView(nameLabel)
        })

        content.addView(hint("Tap the bar to add a stop · drag a marker to move it"), lp(top = 8f))
        content.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(72f)
        ).also { it.topMargin = dp(4f) })

        content.addView(row(
            chip("Add stop") { addAfterSelected() },
            deleteButton,
            chip("Spread evenly") { spreadEvenly() },
            chip("Reverse") { reverse() }
        ), lp(top = 8f))

        // What the picker edits: the selected stop or the interior. Tap to choose.
        stopLabel.text = "Stop"
        interiorLabel.text = "Interior"
        for (l in listOf(stopLabel, interiorLabel)) {
            l.textSize = 12f
            l.setTextColor(0xFFD8DCE4.toInt())
        }
        swatch.setOnClickListener { editingInterior = false; refreshTarget() }
        stopLabel.setOnClickListener { editingInterior = false; refreshTarget() }
        interiorSwatch.setOnClickListener { editingInterior = true; refreshTarget() }
        interiorLabel.setOnClickListener { editingInterior = true; refreshTarget() }

        hex.textSize = 15f
        hex.setTextColor(0xFFE4E8F0.toInt())
        hex.setSingleLine()
        hex.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        hex.imeOptions = EditorInfo.IME_ACTION_DONE
        hex.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        hex.backgroundTintList = ColorStateList.valueOf(0xFF7FA6D8.toInt())
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingHex) return
                parseHex(s?.toString() ?: "")?.let { setTargetColor(it, fromHex = true) }
            }
        })
        hex.setOnEditorActionListener { _, _, _ -> hideKeyboard(); setHexText(targetColor()); true }

        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(swatch, LinearLayout.LayoutParams(dp(36f), dp(36f)))
            addView(stopLabel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = dp(8f); it.marginEnd = dp(14f) })
            addView(interiorSwatch, LinearLayout.LayoutParams(dp(36f), dp(36f)))
            addView(interiorLabel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = dp(8f) })
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(hex, LinearLayout.LayoutParams(dp(112f), ViewGroup.LayoutParams.WRAP_CONTENT))
        }, lp(top = 12f))

        content.addView(picker, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(170f)
        ).also { it.topMargin = dp(10f) })

        // Band width is a log slider: useful values span 4 to 8192 iterations per trip.
        cycleSlider.valueFrom = 1f
        cycleSlider.valueTo = 14f
        cycleSlider.stepSize = 0f
        offsetSlider.valueFrom = 0f
        offsetSlider.valueTo = 1f
        offsetSlider.stepSize = 0f
        for (sl in listOf(cycleSlider, offsetSlider)) {
            sl.labelBehavior = LabelFormatter.LABEL_GONE
            sl.thumbTintList = ColorStateList.valueOf(0xFFE4E8F0.toInt())
            sl.trackActiveTintList = ColorStateList.valueOf(0xFF7FA6D8.toInt())
            sl.trackInactiveTintList = ColorStateList.valueOf(0xFF2A2F38.toInt())
        }
        cycleSlider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            cycle = 2.0.pow(v.toDouble()).toFloat()
            refreshSliderLabels(); push()
        }
        offsetSlider.addOnChangeListener { _, v, fromUser ->
            if (!fromUser) return@addOnChangeListener
            offset = v
            refreshSliderLabels(); push()
        }
        for (l in listOf(cycleLabel, offsetLabel)) {
            l.textSize = 12f
            l.setTextColor(0xFF8A93A3.toInt())
        }
        content.addView(cycleLabel, lp(top = 14f))
        content.addView(cycleSlider, lp())
        content.addView(offsetLabel, lp(top = 2f))
        content.addView(offsetSlider, lp())

        // Presets: tap to start from one. Band width, rotation and interior are kept.
        content.addView(hint("Presets"), lp(top = 8f))
        val presetRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in Palettes.PRESETS) {
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener { loadPreset(p) }
            }
            val sw = View(ctx)
            sw.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                (p.colors + p.colors.first()).map { 0xFF000000.toInt() or it }.toIntArray()
            ).apply { cornerRadius = dp(6f).toFloat() }
            cell.addView(sw, LinearLayout.LayoutParams(dp(64f), dp(30f)))
            cell.addView(text(p.name, 10f, 0xFF8A93A3.toInt()))
            presetRow.addView(cell, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(10f) })
        }
        content.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(presetRow)
        }, lp(top = 6f))

        // Fixed footer, outside the scroll, so Apply is always reachable.
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(18f), dp(8f), dp(18f), dp(10f))
            setBackgroundColor(0xFF15191F.toInt())
        }
        footer.addView(chip("Cancel") { close(apply = false) })
        footer.addView(View(ctx), LinearLayout.LayoutParams(dp(10f), 1))
        footer.addView(chip("Apply", primary = true) { close(apply = true) })
        panel.addView(footer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun lp(top: Float = 0f) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = dp(top) }

    private fun text(t: String, size: Float, color: Int) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color)
    }

    private fun hint(t: String) = text(t, 12f, 0xFF8A93A3.toInt())

    private fun row(vararg views: View) = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            for (v in views) addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(8f) })
        })
    }

    private fun chip(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 13f
        setTextColor(if (primary) 0xFF0F1216.toInt() else 0xFFE4E8F0.toInt())
        setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        background = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            setColor(if (primary) 0xFF7FA6D8.toInt() else 0xFF232831.toInt())
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun roundRect(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(8f).toFloat()
        setColor(fill)
        setStroke(dp(2f), stroke)
    }

    private fun hideKeyboard() {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(hex.windowToken, 0)
        hex.clearFocus()
    }

    companion object {
        /** "#rrggbb", "rrggbb" or "#rgb"; null for anything else. */
        fun parseHex(text: String): Int? {
            val t = text.trim().removePrefix("#")
            return try {
                when (t.length) {
                    6 -> t.toInt(16)
                    3 -> {
                        val r = t[0].digitToInt(16) * 17
                        val g = t[1].digitToInt(16) * 17
                        val b = t[2].digitToInt(16) * 17
                        (r shl 16) or (g shl 8) or b
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * The palette as a bar, with a marker under it for each stop. Tap a marker to select
 * it, drag it to move the stop, tap the bar itself to add a stop there.
 */
@SuppressLint("ViewConstructor")
class GradientBarView(ctx: Context) : View(ctx) {
    var stops: List<ColorStop> = emptyList()
    var selected: ColorStop? = null
    var spec: () -> PaletteSpec = { Palettes.PRESETS[0] }
    var onSelect: (ColorStop) -> Unit = {}
    var onMoved: () -> Unit = {}
    var onAdd: (Float) -> Unit = {}

    private val density = ctx.resources.displayMetrics.density
    private val pad = 14f * density
    private val markerZone = 26f * density
    private val ramp = IntArray(RAMP)
    private val rampBitmap = Bitmap.createBitmap(RAMP, 1, Bitmap.Config.ARGB_8888)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val src = Rect(0, 0, RAMP, 1)
    private val dst = RectF()

    private var dragging: ColorStop? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private fun barBottom() = height - markerZone
    private fun xOf(pos: Float) = pad + pos * (width - 2 * pad)
    private fun posOf(x: Float) = ((x - pad) / (width - 2 * pad)).coerceIn(0f, 0.9999f)

    override fun onDraw(canvas: Canvas) {
        val s = spec()
        for (i in 0 until RAMP) {
            ramp[i] = 0xFF000000.toInt() or Palettes.colorAt(s, (i + 0.5) / RAMP)
        }
        rampBitmap.setPixels(ramp, 0, RAMP, 0, 0, RAMP, 1)
        dst.set(pad, 0f, width - pad, barBottom())
        canvas.drawBitmap(rampBitmap, src, dst, bitmapPaint)
        stroke.color = 0x40FFFFFF
        stroke.strokeWidth = density
        canvas.drawRect(dst, stroke)

        val r = 9f * density
        val cy = barBottom() + markerZone * 0.5f
        for (st in stops) {
            val x = xOf(st.pos)
            val sel = st === selected
            stroke.color = if (sel) 0xFFFFFFFF.toInt() else 0xB0FFFFFF.toInt()
            stroke.strokeWidth = (if (sel) 2.5f else 1.5f) * density
            canvas.drawLine(x, barBottom() - 10f * density, x, cy - r, stroke)
            fill.color = 0xFF000000.toInt() or st.color
            canvas.drawCircle(x, cy, if (sel) r * 1.15f else r, fill)
            canvas.drawCircle(x, cy, if (sel) r * 1.15f else r, stroke)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; moved = false
                // Nearest marker within reach, anywhere in the view's height: markers
                // are small and a finger is not.
                val reach = 26f * density
                dragging = stops.minByOrNull { abs(xOf(it.pos) - e.x) }
                    ?.takeIf { abs(xOf(it.pos) - e.x) < reach && e.y > barBottom() - reach }
                dragging?.let { onSelect(it) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > 6f * density || abs(e.y - downY) > 6f * density) moved = true
                dragging?.let {
                    if (moved) {
                        it.pos = posOf(e.x)
                        onMoved()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging == null && !moved && e.y < barBottom()) onAdd(posOf(e.x))
                dragging = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    companion object { private const val RAMP = 256 }
}

/**
 * Saturation/value square with a hue strip beside it. Reports colours as 0xRRGGBB.
 * Hue is held separately so that dragging through grey or black does not lose it.
 */
@SuppressLint("ViewConstructor")
class ColorPickerView(ctx: Context) : View(ctx) {
    var onChange: (Int) -> Unit = {}

    private val density = ctx.resources.displayMetrics.density
    private val hsv = floatArrayOf(0f, 0f, 0f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sv = RectF()
    private val hue = RectF()
    private var region = 0   // 1 square, 2 hue strip

    fun setColor(rgb: Int) {
        val t = FloatArray(3)
        Color.RGBToHSV((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255, t)
        // Keep the hue the strip shows when the colour has none of its own.
        if (t[1] > 0f && t[2] > 0f) hsv[0] = t[0]
        hsv[1] = t[1]; hsv[2] = t[2]
        invalidate()
    }

    private fun colour(): Int = Color.HSVToColor(hsv) and 0xFFFFFF

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val strip = 28f * density
        val gap = 14f * density
        sv.set(0f, 0f, w - strip - gap, h.toFloat())
        hue.set(w - strip, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val corner = 8f * density
        val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        paint.shader = LinearGradient(sv.left, 0f, sv.right, 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(sv, corner, corner, paint)
        paint.shader = LinearGradient(0f, sv.top, 0f, sv.bottom, 0x00000000, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(sv, corner, corner, paint)

        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        paint.shader = LinearGradient(0f, hue.top, 0f, hue.bottom, hues, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(hue, corner, corner, paint)
        paint.shader = null

        ring.strokeWidth = 2.5f * density
        val x = sv.left + hsv[1] * sv.width()
        val y = sv.top + (1f - hsv[2]) * sv.height()
        ring.color = if (hsv[2] > 0.5f) Color.BLACK else Color.WHITE
        canvas.drawCircle(x, y, 10f * density, ring)
        val hy = hue.top + hsv[0] / 360f * hue.height()
        ring.color = Color.WHITE
        canvas.drawRoundRect(hue.left - 2f * density, hy - 4f * density,
            hue.right + 2f * density, hy + 4f * density, 3f * density, 3f * density, ring)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                region = if (e.x >= hue.left - 7f * density) 2 else 1
                update(e)
            }
            MotionEvent.ACTION_MOVE -> update(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                region = 0
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun update(e: MotionEvent) {
        when (region) {
            1 -> {
                hsv[1] = ((e.x - sv.left) / sv.width()).coerceIn(0f, 1f)
                hsv[2] = 1f - ((e.y - sv.top) / sv.height()).coerceIn(0f, 1f)
            }
            2 -> hsv[0] = (((e.y - hue.top) / hue.height()).coerceIn(0f, 1f) * 360f).coerceAtMost(359.9f)
            else -> return
        }
        invalidate()
        onChange(colour())
    }
}
