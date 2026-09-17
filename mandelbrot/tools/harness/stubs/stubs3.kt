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

    fun interface OnChangeListener {
        fun onValueChange(slider: Slider, value: Float, fromUser: Boolean)
    }

    fun addOnChangeListener(listener: OnChangeListener) {}
}
