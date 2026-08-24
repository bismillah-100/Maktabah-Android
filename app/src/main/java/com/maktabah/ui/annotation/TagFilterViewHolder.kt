package com.maktabah.ui.annotation

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.maktabah.R
import com.maktabah.models.TagFilterMode

class TagFilterViewHolder private constructor(
    rootView: LinearLayout,
    private val scrollView: HorizontalScrollView,
    private val chipsContainer: LinearLayout,
    private val filterButton: ImageView,
    private val modeButton: ImageView,
) : RecyclerView.ViewHolder(rootView) {

    private var lastSelectedTags: Set<String> = emptySet()
    private var lastTagCount: Int = 0
    private var lastFilterMode: TagFilterMode? = null
    private var isFirstBind: Boolean = true

    fun bind(
        item: AnnotationFlatItem.TagFilter,
        primaryColor: Int,
        surfaceColor: Int,
        onSurfaceColor: Int,
        onSurfaceVariantColor: Int,
        onStrokeColor: Int,
        onToggleTag: (String) -> Unit,
        onOpenTagDialog: () -> Unit,
        onToggleFilterMode: () -> Unit,
    ) {
        val wasFiltered = lastSelectedTags.isNotEmpty()
        val isNowCleared = item.selectedTags.isEmpty()
        val tagCountExpanded = item.allTags.size > lastTagCount
        val modeSwitchedToOr = lastFilterMode == TagFilterMode.AND && item.filterMode == TagFilterMode.OR

        val shouldScrollToStart = isFirstBind ||
            (wasFiltered && isNowCleared) ||
            (isNowCleared && tagCountExpanded) ||
            modeSwitchedToOr

        lastSelectedTags = item.selectedTags
        lastTagCount = item.allTags.size
        lastFilterMode = item.filterMode
        isFirstBind = false

        val ctx = scrollView.context
        val density = ctx.resources.displayMetrics.density

        val unselectedBg = Color.TRANSPARENT
        val selectedBg = if (surfaceColor != Color.TRANSPARENT) surfaceColor else Color.WHITE

        val unselectedText =
            if (onSurfaceVariantColor != Color.TRANSPARENT) onSurfaceVariantColor else onSurfaceColor
        val selectedText = if (primaryColor != Color.TRANSPARENT) primaryColor else onSurfaceColor

        val unselectedStroke =
            if (onStrokeColor != Color.TRANSPARENT) onStrokeColor else ColorUtils.setAlphaComponent(
                unselectedText,
                (255 * 0.38f).toInt()
            )
        val selectedStroke =
            if (primaryColor != Color.TRANSPARENT) primaryColor else unselectedStroke

        // 1. Style filter tags dialog button
        val hasActiveFilter = item.selectedTags.isNotEmpty()
        val btnTint = if (hasActiveFilter) primaryColor else onSurfaceVariantColor
        filterButton.setColorFilter(btnTint)

        val btnBgColor = if (hasActiveFilter) {
            ColorUtils.setAlphaComponent(primaryColor, (255 * 0.15f).toInt())
        } else {
            Color.TRANSPARENT
        }
        val btnStrokeColor = if (hasActiveFilter) primaryColor else unselectedStroke
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(btnBgColor)
            setStroke((if (hasActiveFilter) 1.5f else 1f * density).toInt(), btnStrokeColor)
        }
        val ripple = RippleDrawable(
            ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(
                    primaryColor,
                    (255 * 0.2f).toInt()
                )
            ),
            bgDrawable,
            null
        )
        filterButton.background = ripple
        filterButton.setOnClickListener { onOpenTagDialog() }

        // 2. Style AND/OR mode toggle button
        val isAndMode = item.filterMode == TagFilterMode.AND
        val modeTint = if (isAndMode) primaryColor else onSurfaceVariantColor
        modeButton.setColorFilter(modeTint)

        val modeBgColor = if (isAndMode) {
            ColorUtils.setAlphaComponent(primaryColor, (255 * 0.15f).toInt())
        } else {
            Color.TRANSPARENT
        }
        val modeStrokeColor = if (isAndMode) primaryColor else unselectedStroke
        val modeBgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(modeBgColor)
            setStroke((if (isAndMode) 1.5f else 1f * density).toInt(), modeStrokeColor)
        }
        val modeRipple = RippleDrawable(
            ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(
                    primaryColor,
                    (255 * 0.2f).toInt()
                )
            ),
            modeBgDrawable,
            null
        )
        modeButton.background = modeRipple
        modeButton.setOnClickListener {
            onToggleFilterMode()
            val isNextAnd = item.filterMode == TagFilterMode.OR
            val msgRes =
                if (isNextAnd) R.string.annotations_tag_filter_mode_and else R.string.annotations_tag_filter_mode_or
            Toast.makeText(ctx, ctx.getString(msgRes), Toast.LENGTH_SHORT).show()
        }

        // 3. Bind chips
        while (chipsContainer.childCount > item.allTags.size) {
            chipsContainer.removeViewAt(chipsContainer.childCount - 1)
        }
        item.allTags.forEachIndexed { i, tag ->
            val chip: TextView = if (i < chipsContainer.childCount) {
                chipsContainer.getChildAt(i) as TextView
            } else {
                TextView(ctx).apply {
                    textSize = 13f
                    gravity = Gravity.CENTER
                    val hPad = (12 * density).toInt()
                    val vPad = (6 * density).toInt()
                    setPadding(hPad, vPad, hPad, vPad)
                    isClickable = true
                    isFocusable = true
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        (32 * density).toInt()
                    ).apply {
                        marginEnd = (8 * density).toInt()
                    }
                    layoutParams = lp
                    chipsContainer.addView(this)
                }
            }
            if (chip.text != tag) {
                chip.text = tag
            }
            val isSelected = item.selectedTags.contains(tag)
            val bgColor = if (isSelected) selectedBg else unselectedBg
            val strokeColor = if (isSelected) selectedStroke else unselectedStroke
            val strokeWidth = (if (isSelected) 1.5f else 1f * density).toInt()
            val textColor = if (isSelected) selectedText else unselectedText

            val pillDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(bgColor)
                setStroke(strokeWidth, strokeColor)
            }
            val chipRipple = RippleDrawable(
                ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(
                        primaryColor,
                        (255 * 0.15f).toInt()
                    )
                ),
                pillDrawable,
                null
            )
            chip.background = chipRipple
            chip.setTextColor(textColor)
            chip.setOnClickListener { onToggleTag(tag) }
        }

        if (shouldScrollToStart) {
            chipsContainer.post {
                scrollView.scrollTo(chipsContainer.width, 0)
            }
        }
    }

    companion object {
        fun create(parent: ViewGroup): TagFilterViewHolder {
            val density = parent.context.resources.displayMetrics.density
            val hPad = (16 * density).toInt()

            val rootLayout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    leftMargin = 0
                    rightMargin = 0
                    topMargin = (-8 * density).toInt()
                    bottomMargin = (16 * density).toInt()
                }
            }

            val rowLayout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }

            val btnSize = (32 * density).toInt()
            val filterButton = ImageView(parent.context).apply {
                setImageResource(R.drawable.ic_tag)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = (6 * density).toInt()
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
                contentDescription =
                    parent.context.getString(R.string.annotations_filter_tags_title)
                val lp = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginStart = hPad // 16dp di sisi kanan (Start RTL)
                    marginEnd = (6 * density).toInt() // 6dp jarak ke modeButton
                }
                layoutParams = lp
            }

            val modeButton = ImageView(parent.context).apply {
                setImageResource(R.drawable.ic_filter_tune)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val pad = (6 * density).toInt()
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
                contentDescription =
                    parent.context.getString(R.string.annotations_tag_filter_mode_desc)
                val lp = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginStart = 0
                    marginEnd = (8 * density).toInt() // 8dp jarak ke chip scrollview
                }
                layoutParams = lp
            }

            val chipsContainer = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            // overScrollMode=NEVER karena bug Android: HorizontalScrollView + RTL + stretch
            // overscroll (Android 12+) menyebabkan scroll freeze di edge boundary.
            val scrollView = HorizontalScrollView(parent.context).apply {
                isHorizontalScrollBarEnabled = false
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                setPaddingRelative(0, 0, hPad, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                )
                addView(chipsContainer)
            }

            // In RTL, adding order: filterButton (rightmost) -> modeButton -> scrollView (leftmost)
            rowLayout.addView(filterButton)
            rowLayout.addView(modeButton)
            rowLayout.addView(scrollView)
            rootLayout.addView(rowLayout)

            return TagFilterViewHolder(
                rootLayout,
                scrollView,
                chipsContainer,
                filterButton,
                modeButton
            )
        }
    }
}
