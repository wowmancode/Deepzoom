package com.google.android.material.slider

import android.content.Context
import android.util.AttributeSet
import android.view.View

open class Slider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    var value: Float = 0f
    var valueFrom: Float = 0f
    var valueTo: Float = 1f
    var stepSize: Float = 0f
    // Colour editor: BaseSlider get/set pairs, seen from Kotlin as properties.
    var labelBehavior: Int = 0
    var thumbTintList: android.content.res.ColorStateList? = null
    var trackActiveTintList: android.content.res.ColorStateList? = null
    var trackInactiveTintList: android.content.res.ColorStateList? = null

    fun interface OnChangeListener {
        fun onValueChange(slider: Slider, value: Float, fromUser: Boolean)
    }

    fun addOnChangeListener(listener: OnChangeListener) {}
}

interface LabelFormatter {
    companion object {
        const val LABEL_GONE = 2
    }
}
