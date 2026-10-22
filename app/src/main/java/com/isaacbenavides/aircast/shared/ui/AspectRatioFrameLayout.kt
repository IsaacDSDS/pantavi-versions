package com.isaacbenavides.aircast.shared.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** Shrinks itself to [aspectRatio] (width / height) inside the space its parent offers. */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var aspectRatio: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (aspectRatio <= 0f || measuredWidth == 0 || measuredHeight == 0) return
        var width = measuredWidth
        var height = measuredHeight
        if (aspectRatio > width.toFloat() / height) {
            height = (width / aspectRatio).toInt()
        } else {
            width = (height * aspectRatio).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
