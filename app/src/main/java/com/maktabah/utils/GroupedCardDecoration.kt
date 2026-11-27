package com.maktabah.utils

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.withClip
import androidx.recyclerview.widget.RecyclerView

class ItemHighlightDrawable(private val parentView: () -> View?) : Drawable() {
    val rippleDrawable = RippleDrawable(
        ColorStateList.valueOf(Color.TRANSPARENT),
        null,
        Color.WHITE.toDrawable()
    )

    private var currentHighlightColor: Int = Color.TRANSPARENT
    private var lastActivityTimeMs: Long = 0L
    private var hasActiveState: Boolean = false
    private var lastRippleWidth: Int = -1
    private var lastRippleHeight: Int = -1

    fun setHighlightColor(color: Int) {
        if (currentHighlightColor != color) {
            currentHighlightColor = color
            rippleDrawable.setColor(ColorStateList.valueOf(color))
        }
    }

    fun isHighlightActive(nowMs: Long): Boolean {
        return hasActiveState || (lastActivityTimeMs != 0L && nowMs - lastActivityTimeMs < 800L)
    }

    fun updateRippleBounds(width: Int, height: Int) {
        if (lastRippleWidth != width || lastRippleHeight != height) {
            lastRippleWidth = width
            lastRippleHeight = height
            rippleDrawable.setBounds(0, 0, width, height)
        }
    }

    init {
        rippleDrawable.callback = object : Callback {
            override fun invalidateDrawable(who: Drawable) {
                lastActivityTimeMs = android.os.SystemClock.uptimeMillis()
                parentView()?.invalidate()
            }
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                lastActivityTimeMs = android.os.SystemClock.uptimeMillis()
                parentView()?.postDelayed(what, `when` - android.os.SystemClock.uptimeMillis())
            }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                parentView()?.removeCallbacks(what)
            }
        }
    }

    override fun onStateChange(stateSet: IntArray): Boolean {
        var active = false
        for (state in stateSet) {
            if (state == android.R.attr.state_pressed ||
                state == android.R.attr.state_focused ||
                state == android.R.attr.state_hovered
            ) {
                active = true
                break
            }
        }
        hasActiveState = active
        val changed = rippleDrawable.setState(stateSet)
        if (changed || active) {
            lastActivityTimeMs = android.os.SystemClock.uptimeMillis()
        }
        if (changed) parentView()?.invalidate()
        return changed || super.onStateChange(stateSet)
    }

    override fun setHotspot(x: Float, y: Float) {
        super.setHotspot(x, y)
        rippleDrawable.setHotspot(x, y)
    }

    override fun jumpToCurrentState() {
        super.jumpToCurrentState()
        hasActiveState = false
        lastActivityTimeMs = 0L
        rippleDrawable.jumpToCurrentState()
    }

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        super.setBounds(left, top, right, bottom)
        val width = right - left
        val height = bottom - top
        lastRippleWidth = width
        lastRippleHeight = height
        rippleDrawable.setBounds(left, top, right, bottom)
    }

    override fun isStateful(): Boolean = true
    override fun draw(canvas: Canvas) {}
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Suppress("DEPRECATION")
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

    companion object {
        const val DEFAULT_CORNER_RADIUS_DP = 24f
        const val COMPACT_HEADER_CORNER_RADIUS_DP = 23f

        /**
         * Computes the scale factor for top and bottom radii when their sum exceeds [height],
         * matching Skia's addRoundRect behavior without allocating in onDraw.
         */
        internal fun radiusScale(
            topRadius: Float,
            bottomRadius: Float,
            height: Float,
        ): Float {
            val safeHeight = height.coerceAtLeast(0f)
            val totalRadius = topRadius + bottomRadius
            return if (totalRadius > safeHeight && totalRadius > 0f) {
                safeHeight / totalRadius
            } else {
                1f
            }
        }
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val strokePath = Path()

    // Cached objects to avoid allocation in onDraw
    private val currentGroupChildren = mutableListOf<View>()
    private val sortedChildren = mutableListOf<View>()
    private val radiiCache = FloatArray(8)
    private val childComparator = Comparator<View> { a, b ->
        val topCompare = getVisibleTop(a).compareTo(getVisibleTop(b))
        if (topCompare != 0) topCompare else a.top.compareTo(b.top)
    }

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

    init {
        strokePaint.strokeWidth = strokeWidth
    }

    private fun snap(v: Float) = kotlin.math.floor(v) + 0.5f

    private fun getVisibleTop(child: View): Float {
        if (child.translationY <= -9999f) {
            return child.tag as? Float ?: 0f
        }
        return child.top + child.translationY + (child.clipBounds?.top ?: 0)
    }

    private fun getVisibleBottom(child: View): Float {
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
        var currentTranslationX = 0f
        var hasVisibleGroup = false
        currentGroupChildren.clear()

        val nowMs = android.os.SystemClock.uptimeMillis()

        fun drawCurrentGroup() {
            if (!hasVisibleGroup) return

            val rawTopRadius = if (currentIsFirst) cornerRadius else 0f
            val rawBottomRadius = if (currentIsLast) cornerRadius else 0f

            // Kita gunakan fungsi snap() untuk left dan right agar bernilai x.5f
            // Sama seperti top dan bottom, ini akan membuat hairline (strokeWidth = 0f) menjadi sangat tajam
            val left = snap(marginHorizontal + currentTranslationX)
            // Untuk right, kita gunakan snap() dan pastikan garis menjorok ke dalam (dikurangi 1 agar presisi dengan margin)
            val right = snap(parent.width - marginHorizontal + currentTranslationX) - 1f
            val top = snap(currentTop)
            val bottom = snap(currentBottom) - 1f

            val scale = radiusScale(
                topRadius = rawTopRadius,
                bottomRadius = rawBottomRadius,
                height = bottom - top,
            )
            val topRadius = rawTopRadius * scale
            val bottomRadius = rawBottomRadius * scale

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
                var hasActiveRipple = false
                for (i in 0 until currentGroupChildren.size) {
                    val bg = currentGroupChildren[i].background as? ItemHighlightDrawable
                    if (bg != null && bg.isHighlightActive(nowMs)) {
                        hasActiveRipple = true
                        break
                    }
                }
                if (hasActiveRipple) {
                    c.withClip(path) {
                        for (i in 0 until currentGroupChildren.size) {
                            val groupChild = currentGroupChildren[i]
                            val bg = groupChild.background as? ItemHighlightDrawable ?: continue
                            if (!bg.isHighlightActive(nowMs)) continue
                            bg.setHighlightColor(highlightColor)
                            val translatedTop = groupChild.top + groupChild.translationY
                            val translatedBottom = groupChild.bottom + groupChild.translationY

                            val clipBounds = groupChild.clipBounds
                            val visibleTop = snap(translatedTop + (clipBounds?.top ?: 0))
                            val visibleBottom =
                                snap(if (clipBounds != null) translatedTop + clipBounds.bottom else translatedBottom)

                            if (visibleTop < visibleBottom) {
                                withClip(left, visibleTop, right, visibleBottom) {
                                    translate(left, snap(translatedTop))
                                    bg.updateRippleBounds(
                                        (right - left).toInt(),
                                        (translatedBottom - translatedTop).toInt()
                                    )
                                    bg.rippleDrawable.draw(this)
                                }
                            }
                        }
                    }
                }
            }

            if (strokePaint.color != Color.TRANSPARENT) {
                if (topRadius > 0f && bottomRadius > 0f) {
                    c.drawPath(path, strokePaint)
                } else {
                    strokePath.reset()

                    if (topRadius > 0f && bottomRadius == 0f) {
                        strokePath.moveTo(left, bottom)
                        strokePath.lineTo(left, top + topRadius)
                        strokePath.arcTo(left, top, left + 2 * topRadius, top + 2 * topRadius, 180f, 90f, false)
                        strokePath.lineTo(right - topRadius, top)
                        strokePath.arcTo(right - 2 * topRadius, top, right, top + 2 * topRadius, 270f, 90f, false)
                        strokePath.lineTo(right, bottom)
                    } else if (topRadius == 0f && bottomRadius > 0f) {
                        strokePath.moveTo(left, top)
                        strokePath.lineTo(left, bottom - bottomRadius)
                        strokePath.arcTo(left, bottom - 2 * bottomRadius, left + 2 * bottomRadius, bottom, 180f, -90f, false)
                        strokePath.lineTo(right - bottomRadius, bottom)
                        strokePath.arcTo(right - 2 * bottomRadius, bottom - 2 * bottomRadius, right, bottom, 90f, -90f, false)
                        strokePath.lineTo(right, top)
                    } else {
                        strokePath.moveTo(left, top)
                        strokePath.lineTo(left, bottom)
                        strokePath.moveTo(right, top)
                        strokePath.lineTo(right, bottom)
                    }
                    c.drawPath(strokePath, strokePaint)
                }
            }

            hasVisibleGroup = false
            currentTop = Float.MAX_VALUE
            currentBottom = -Float.MAX_VALUE
            currentTranslationX = 0f
            currentGroupChildren.clear()
        }

        // Sort by the visible top. Animating children can have a raw translated top
        // far above their clipping boundary, which would merge them into groups above.
        // We use view.top as a secondary key for stable sorting.
        sortedChildren.clear()
        for (i in 0 until count) {
            sortedChildren.add(parent.getChildAt(i))
        }
        sortedChildren.sortWith(childComparator)

        for (i in 0 until sortedChildren.size) {
            val child = sortedChildren[i]
            var isFirst: Boolean
            var isLast: Boolean

            val pos = parent.getChildAdapterPosition(child)
            if (pos != RecyclerView.NO_POSITION) {
                val info = getGroupInfo(pos)
                if (info == null) {
                    drawCurrentGroup()
                    continue
                }
                isFirst = info.isFirst
                isLast = info.isLast
                if (child.getTag(com.maktabah.R.id.tag_is_first) != isFirst) {
                    child.setTag(com.maktabah.R.id.tag_is_first, isFirst)
                }
                if (child.getTag(com.maktabah.R.id.tag_is_last) != isLast) {
                    child.setTag(com.maktabah.R.id.tag_is_last, isLast)
                }
            } else {
                val tagFirst = child.getTag(com.maktabah.R.id.tag_is_first) as? Boolean
                val tagLast = child.getTag(com.maktabah.R.id.tag_is_last) as? Boolean
                if (tagFirst != null && tagLast != null) {
                    isFirst = tagFirst
                    isLast = tagLast
                } else {
                    continue
                }
            }

            val childTop = getVisibleTop(child)
            val childBottom = getVisibleBottom(child)
            val rawTx = child.translationX
            val childTx = if (kotlin.math.abs(rawTx) > 0.5f) rawTx else 0f

            if (!hasVisibleGroup) {
                hasVisibleGroup = true
                currentTop = childTop
                currentBottom = childBottom
                currentIsFirst = isFirst
                currentIsLast = isLast
                currentTranslationX = childTx
                currentGroupChildren.add(child)
            } else {
                if (isFirst || childTx != currentTranslationX) {
                    drawCurrentGroup()
                    hasVisibleGroup = true
                    currentTop = childTop
                    currentBottom = childBottom
                    currentIsFirst = isFirst
                    currentIsLast = isLast
                    currentTranslationX = childTx
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
