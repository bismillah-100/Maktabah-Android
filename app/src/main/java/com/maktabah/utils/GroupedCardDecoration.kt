package com.maktabah.utils

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import android.content.res.ColorStateList

class ItemHighlightDrawable(private val parentView: () -> View?) : Drawable() {
    val rippleDrawable = RippleDrawable(
        ColorStateList.valueOf(Color.TRANSPARENT),
        null,
        ColorDrawable(Color.WHITE)
    )

    init {
        rippleDrawable.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                parentView()?.invalidate()
            }
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                parentView()?.postDelayed(what, `when` - android.os.SystemClock.uptimeMillis())
            }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                parentView()?.removeCallbacks(what)
            }
        }
    }

    override fun onStateChange(stateSet: IntArray): Boolean {
        val changed = rippleDrawable.setState(stateSet)
        if (changed) parentView()?.invalidate()
        return changed || super.onStateChange(stateSet)
    }

    override fun setHotspot(x: Float, y: Float) {
        super.setHotspot(x, y)
        rippleDrawable.setHotspot(x, y)
    }

    override fun jumpToCurrentState() {
        super.jumpToCurrentState()
        rippleDrawable.jumpToCurrentState()
    }

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        super.setBounds(left, top, right, bottom)
        rippleDrawable.setBounds(left, top, right, bottom)
    }

    override fun isStateful(): Boolean = true
    override fun draw(canvas: Canvas) {}
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSPARENT
}

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
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val halfStroke = strokeWidth / 2f
    private val path = Path()
    
    // Cached objects to avoid allocation in onDraw
    private val currentGroupChildren = mutableListOf<View>()
    private val sortedChildren = mutableListOf<View>()
    private val radiiCache = FloatArray(8)

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

    var highlightColor: Int = 0
        set(value) {
            field = value
            highlightPaint.color = value
        }

    init {
        strokePaint.strokeWidth = strokeWidth
    }

    private fun snap(v: Float) = kotlin.math.floor(v) + 0.5f

    private fun getVisibleTop(child: android.view.View): Float {
        if (child.translationY <= -9999f) {
            return child.tag as? Float ?: 0f
        }
        return child.top + child.translationY + (child.clipBounds?.top ?: 0)
    }

    private fun getVisibleBottom(child: android.view.View): Float {
        if (child.translationY <= -9999f) {
            return child.tag as? Float ?: 0f
        }
        val translatedTop = child.top + child.translationY
        val clipBounds = child.clipBounds
        val top = translatedTop + (clipBounds?.top ?: 0)
        val bottom = if (clipBounds != null) {
            translatedTop + clipBounds.bottom
        } else {
            child.bottom + child.translationY
        }
        return if (bottom <= top) top else bottom
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = parent.childCount
        if (count == 0) return

        var currentTop = Float.MAX_VALUE
        var currentBottom = -Float.MAX_VALUE
        var currentIsFirst = false
        var currentIsLast = false
        var hasVisibleGroup = false
        currentGroupChildren.clear()

        fun drawCurrentGroup() {
            if (!hasVisibleGroup) return

            val topRadius = if (currentIsFirst) cornerRadius else 0f
            val bottomRadius = if (currentIsLast) cornerRadius else 0f

            val left = marginHorizontal + halfStroke
            val right = parent.width - marginHorizontal - halfStroke
            val top = snap(currentTop) + halfStroke
            val bottom = snap(currentBottom) - halfStroke

            radiiCache[0] = topRadius
            radiiCache[1] = topRadius
            radiiCache[2] = topRadius
            radiiCache[3] = topRadius
            radiiCache[4] = bottomRadius
            radiiCache[5] = bottomRadius
            radiiCache[6] = bottomRadius
            radiiCache[7] = bottomRadius

            path.reset()
            path.addRoundRect(
                left, top, right, bottom,
                radiiCache,
                Path.Direction.CW
            )
            c.drawPath(path, fillPaint)

            if (highlightColor != 0) {
                c.save()
                c.clipPath(path)
                val baseAlpha = Color.alpha(highlightColor)
                for (groupChild in currentGroupChildren) {
                    val bg = groupChild.background as? ItemHighlightDrawable
                    if (bg != null) {
                        bg.rippleDrawable.setColor(ColorStateList.valueOf(highlightColor))
                        val translatedTop = groupChild.top + groupChild.translationY
                        val translatedBottom = groupChild.bottom + groupChild.translationY
                        
                        val clipBounds = groupChild.clipBounds
                        val visibleTop = snap(translatedTop + (clipBounds?.top ?: 0))
                        val visibleBottom = snap(if (clipBounds != null) translatedTop + clipBounds.bottom else translatedBottom)

                        if (visibleTop < visibleBottom) {
                            c.save()
                            c.clipRect(left, visibleTop, right, visibleBottom)
                            c.translate(left, snap(translatedTop))
                            bg.rippleDrawable.setBounds(0, 0, (right - left).toInt(), (translatedBottom - translatedTop).toInt())
                            bg.rippleDrawable.draw(c)
                            c.restore()
                        }
                    }
                }
                c.restore()
                highlightPaint.color = highlightColor
            }

            c.drawPath(path, strokePaint)

            hasVisibleGroup = false
            currentTop = Float.MAX_VALUE
            currentBottom = -Float.MAX_VALUE
            currentGroupChildren.clear()
        }

        // Sort by the visible top. Animating children can have a raw translated top
        // far above their clipping boundary, which would merge them into groups above.
        // We use view.top as a secondary key for stable sorting.
        sortedChildren.clear()
        for (i in 0 until count) {
            sortedChildren.add(parent.getChildAt(i))
        }
        sortedChildren.sortWith(compareBy(
            { getVisibleTop(it) },
            { it.top }
        ))

        for (child in sortedChildren) {
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

            val childTop = getVisibleTop(child)
            val childBottom = getVisibleBottom(child)

            if (!hasVisibleGroup) {
                hasVisibleGroup = true
                currentTop = childTop
                currentBottom = childBottom
                currentIsFirst = isFirst
                currentIsLast = isLast
                currentGroupChildren.add(child)
            } else {
                if (isFirst) {
                    drawCurrentGroup()
                    hasVisibleGroup = true
                    currentTop = childTop
                    currentBottom = childBottom
                    currentIsFirst = true
                    currentIsLast = isLast
                    currentGroupChildren.add(child)
                } else {
                    currentTop = minOf(currentTop, childTop)
                    currentBottom = maxOf(currentBottom, childBottom)
                    currentIsLast = currentIsLast || isLast
                    currentGroupChildren.add(child)
                }
            }
        }

        drawCurrentGroup()
    }
}
