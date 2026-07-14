package com.maktabah.utils

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.recyclerview.widget.RecyclerView

/**
 * An ItemDecoration that draws a rounded background and stroke around groups of items.
 * The grouping logic is provided via [getGroupInfo].
 */
class GroupedCardDecoration(
    private val cornerRadius: Float,
    private val strokeWidth: Float,
    private val marginHorizontal: Float,
    private val getGroupInfo: (Int) -> GroupInfo?
) : RecyclerView.ItemDecoration() {

    data class GroupInfo(
        val isFirst: Boolean,
        val isLast: Boolean
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val halfStroke = strokeWidth / 2f
    private val path = Path()

    var surfaceColor: Int = 0
        set(value) {
            field = value
            fillPaint.color = value
        }

    var strokeColor: Int = 0
        set(value) {
            field = value
            strokePaint.color = value
        }

    init {
        strokePaint.strokeWidth = strokeWidth
    }

    private fun snap(v: Float) = kotlin.math.floor(v) + 0.5f

    private fun visibleVerticalBounds(child: android.view.View): Pair<Float, Float> {
        if (child.translationY <= -9999f) {
            val parentBottom = child.tag as? Float
            if (parentBottom != null) {
                return parentBottom to parentBottom
            }
        }

        val translatedTop = child.top + child.translationY
        val clipBounds = child.clipBounds
        val top = translatedTop + (clipBounds?.top ?: 0)
        val bottom = if (clipBounds != null) {
            translatedTop + clipBounds.bottom
        } else {
            child.bottom + child.translationY
        }

        if (bottom <= top) return top to top
        return top to bottom
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = parent.childCount
        if (count == 0) return

        var currentTop = Float.MAX_VALUE
        var currentBottom = -Float.MAX_VALUE
        var currentIsFirst = false
        var currentIsLast = false
        var hasVisibleGroup = false

        fun drawCurrentGroup() {
            if (!hasVisibleGroup) return

            val topRadius = if (currentIsFirst) cornerRadius else 0f
            val bottomRadius = if (currentIsLast) cornerRadius else 0f

            val left = marginHorizontal + halfStroke
            val right = parent.width - marginHorizontal - halfStroke
            val top = snap(currentTop) + halfStroke
            val bottom = snap(currentBottom) - halfStroke

            val radii = floatArrayOf(
                topRadius, topRadius, // top-left
                topRadius, topRadius, // top-right
                bottomRadius, bottomRadius, // bottom-right
                bottomRadius, bottomRadius  // bottom-left
            )

            path.reset()
            path.addRoundRect(
                left, top, right, bottom,
                radii,
                Path.Direction.CW
            )
            c.drawPath(path, fillPaint)
            c.drawPath(path, strokePaint)

            hasVisibleGroup = false
            currentTop = Float.MAX_VALUE
            currentBottom = -Float.MAX_VALUE
        }

        // Sort by the visible top. Animating children can have a raw translated top
        // far above their clipping boundary, which would merge them into groups above.
        // We use view.top as a secondary key for stable sorting.
        val children = mutableListOf<android.view.View>()
        for (i in 0 until count) {
            children.add(parent.getChildAt(i))
        }
        children.sortWith(compareBy(
            { visibleVerticalBounds(it).first },
            { it.top }
        ))

        for (child in children) {
            val tagFirst = child.getTag(com.maktabah.R.id.tag_is_first) as? Boolean
            val tagLast = child.getTag(com.maktabah.R.id.tag_is_last) as? Boolean

            var isFirst: Boolean
            var isLast: Boolean

            if (tagFirst != null && tagLast != null) {
                isFirst = tagFirst
                isLast = tagLast
            } else {
                val pos = parent.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION) continue
                val info = getGroupInfo(pos)
                if (info == null) {
                    drawCurrentGroup()
                    continue
                }
                isFirst = info.isFirst
                isLast = info.isLast
            }

            val (childTop, childBottom) = visibleVerticalBounds(child)

            if (!hasVisibleGroup) {
                hasVisibleGroup = true
                currentTop = childTop
                currentBottom = childBottom
                currentIsFirst = isFirst
                currentIsLast = isLast
            } else {
                if (isFirst) {
                    drawCurrentGroup()
                    hasVisibleGroup = true
                    currentTop = childTop
                    currentBottom = childBottom
                    currentIsFirst = true
                    currentIsLast = isLast
                } else {
                    currentTop = minOf(currentTop, childTop)
                    currentBottom = maxOf(currentBottom, childBottom)
                    currentIsLast = currentIsLast || isLast
                }
            }
        }

        drawCurrentGroup()
    }
}
