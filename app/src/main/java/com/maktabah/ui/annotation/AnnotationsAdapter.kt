package com.maktabah.ui.annotation

import android.animation.ObjectAnimator
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.maktabah.R
import com.maktabah.models.Annotation
import com.maktabah.models.AnnotationGroup
import com.maktabah.utils.convertToArabicDigits

// Flat item types for the RecyclerView
sealed class AnnotationFlatItem {
    data class Header(
        val group: AnnotationGroup,
        val isExpanded: Boolean,
        val isFirst: Boolean,
        val isLast: Boolean,
    ) : AnnotationFlatItem()

    data class Item(
        val ann: Annotation,
        val groupKey: String,
        val index: Int,      // 1-based, used for InsetGroupedItem
        val lastIndex: Int,
    ) : AnnotationFlatItem()

    data class Spacer(val groupKey: String) : AnnotationFlatItem()
}

class AnnotationsAdapter(
    private val onToggleGroup: (String) -> Unit,
    private val onAnnotationClick: (Int, Int?, Int?, Int?, String?) -> Unit,
) : ListAdapter<AnnotationFlatItem, RecyclerView.ViewHolder>(DiffCallback()) {

    private lateinit var recyclerView: RecyclerView
    private var lastClickedHeaderY: Float? = null
    private var lastClickedGroupKey: String? = null

    private val clearParentRunnable = Runnable {
        lastClickedHeaderY = null
        lastClickedGroupKey = null
    }

    var primaryColor: Int = Color.TRANSPARENT
    var secondaryColor: Int = Color.TRANSPARENT
    var onSurfaceColor: Int = Color.TRANSPARENT
    var onSurfaceVariantColor: Int = Color.TRANSPARENT

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
        private const val TYPE_SPACER = 2
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is AnnotationFlatItem.Header -> TYPE_HEADER
        is AnnotationFlatItem.Item -> TYPE_ITEM
        is AnnotationFlatItem.Spacer -> TYPE_SPACER
    }

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        rv.removeCallbacks(clearParentRunnable)
    }

    override fun onCurrentListChanged(
        previousList: MutableList<AnnotationFlatItem>,
        currentList: MutableList<AnnotationFlatItem>
    ) {
        super.onCurrentListChanged(previousList, currentList)
        syncVisibleDecorationState()
    }

    private fun syncVisibleDecorationState() {
        if (!::recyclerView.isInitialized) return
        recyclerView.post {
            if (!::recyclerView.isInitialized) return@post
            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChildAt(i)
                val holder = recyclerView.getChildViewHolder(child)
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION || position >= itemCount) continue
                applyDecorationState(child, getItem(position))
            }
            recyclerView.invalidateItemDecorations()
            recyclerView.invalidate()
        }
    }

    private fun applyDecorationState(view: View, item: AnnotationFlatItem) {
        view.translationZ = when (item) {
            is AnnotationFlatItem.Header -> 100f
            is AnnotationFlatItem.Item -> 99f
            is AnnotationFlatItem.Spacer -> 98f
        }

        when (item) {
            is AnnotationFlatItem.Header -> {
                view.setTag(R.id.tag_is_first, item.isFirst)
                view.setTag(R.id.tag_is_last, item.isLast)
            }
            is AnnotationFlatItem.Item -> {
                view.setTag(R.id.tag_is_first, false)
                view.setTag(R.id.tag_is_last, item.index == item.lastIndex)
            }
            is AnnotationFlatItem.Spacer -> {
                view.setTag(R.id.tag_is_first, null)
                view.setTag(R.id.tag_is_last, null)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_annotation_header, parent, false)
                HeaderViewHolder(view)
            }
            TYPE_ITEM -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_annotation, parent, false)
                ItemViewHolder(view)
            }
            else -> {
                val view = View(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (16 * parent.context.resources.displayMetrics.density).toInt()
                    )
                }
                SpacerViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        // Apply uniform 16dp horizontal margins to all items (same as LibraryAdapter)
        val density = holder.itemView.context.resources.displayMetrics.density
        val horizontalMargin = (16 * density).toInt()
        val lp = holder.itemView.layoutParams as? ViewGroup.MarginLayoutParams
            ?: ViewGroup.MarginLayoutParams(holder.itemView.layoutParams).also {
                holder.itemView.layoutParams = it
            }
        if (lp.leftMargin != horizontalMargin || lp.rightMargin != horizontalMargin) {
            lp.leftMargin = horizontalMargin
            lp.rightMargin = horizontalMargin
            holder.itemView.layoutParams = lp
        }

        // Tag child items for expand/collapse animation (same pattern as LibraryAdapter)
        val clickedKey = lastClickedGroupKey
        val clickedY = lastClickedHeaderY
        if (clickedKey != null && clickedY != null) {
            val isChildOfClicked = when (val flatItem = getItem(position)) {
                is AnnotationFlatItem.Item -> flatItem.groupKey == clickedKey
                is AnnotationFlatItem.Spacer -> flatItem.groupKey == clickedKey
                else -> false
            }
            holder.itemView.tag = if (isChildOfClicked) clickedY else null
        } else {
            holder.itemView.tag = null
        }

        val item = getItem(position)
        applyDecorationState(holder.itemView, item)

        when (holder) {
            is HeaderViewHolder -> {
                val item = getItem(position) as AnnotationFlatItem.Header
                holder.bind(
                    item = item,
                    onToggle = {
                        val currentPos = holder.bindingAdapterPosition
                        if (currentPos == RecyclerView.NO_POSITION) return@bind
                        val isExpanded = item.isExpanded
                        val animator = recyclerView.itemAnimator as? com.maktabah.ui.library.TreeItemAnimator
                        if (isExpanded) {
                            // collapsing — tag children so they animate toward header
                            animator?.collapsingParentY = holder.itemView.bottom.toFloat()
                            for (i in (currentPos + 1) until itemCount) {
                                val next = getItem(i)
                                if (next is AnnotationFlatItem.Header) break
                                recyclerView.findViewHolderForAdapterPosition(i)
                                    ?.itemView?.tag = holder.itemView.bottom.toFloat()
                            }
                        } else {
                            // expanding — store parent Y so newly bound children get the tag
                            lastClickedHeaderY = holder.itemView.bottom.toFloat()
                            lastClickedGroupKey = item.group.key
                            recyclerView.removeCallbacks(clearParentRunnable)
                            recyclerView.postDelayed(clearParentRunnable, 350)
                        }
                        onToggleGroup(item.group.key)
                    }
                )
            }
            is ItemViewHolder -> {
                val item = getItem(position) as AnnotationFlatItem.Item
                holder.bind(
                    item = item,
                    onAnnotationClick = onAnnotationClick,
                )
            }
            is SpacerViewHolder -> { /* nothing */ }
        }
    }

    // ── ViewHolders ─────────────────────────────────────────────────────────

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val itemContainer: View = itemView.findViewById(R.id.itemContainer)
        private val nameText: TextView = itemView.findViewById(R.id.nameText)
        private val arrowIcon: ImageView = itemView.findViewById(R.id.arrowIcon)
        private val typeIcon: ImageView = itemView.findViewById(R.id.typeIcon)
        private val divider: View = itemView.findViewById(R.id.divider)

        fun bind(item: AnnotationFlatItem.Header, onToggle: () -> Unit) {
            nameText.text = item.group.title

            // Set dynamic icon colors
            arrowIcon.setColorFilter(secondaryColor)
            typeIcon.setColorFilter(secondaryColor)

            // Only set rotation if not currently performing a rotation animation
            val isRotating = arrowIcon.getTag(R.id.tag_animating_rotation) as? Boolean ?: false
            if (!isRotating) {
                arrowIcon.rotation = if (item.isExpanded) 0f else 90f
            }

            if (item.isLast) {
                divider.animate().cancel()
                divider.visibility = View.INVISIBLE
            } else {
                if (divider.isInvisible) {
                    divider.alpha = 0f
                    divider.visibility = View.VISIBLE
                    divider.animate().alpha(0.35f).setDuration(250).start()
                } else {
                    divider.animate().cancel()
                    divider.visibility = View.VISIBLE
                    divider.alpha = 0.35f
                }
            }

            // Clear background for decoration
            itemView.background = null
            itemContainer.background = null
            itemContainer.isClickable = false
            itemContainer.isFocusable = false

            itemView.setOnClickListener {
                val current = arrowIcon.rotation
                val target = if (current == 0f) 90f else 0f
                val animator = ObjectAnimator.ofFloat(arrowIcon, "rotation", current, target).setDuration(250)
                animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationStart(animation: android.animation.Animator) {
                        arrowIcon.setTag(R.id.tag_animating_rotation, true)
                    }
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        arrowIcon.setTag(R.id.tag_animating_rotation, null)
                    }
                })
                animator.start()
                onToggle()
            }
        }
    }

    inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val itemContainer: View = itemView.findViewById(R.id.itemContainer)
        private val contextText: TextView = itemView.findViewById(R.id.contextText)
        private val noteText: TextView = itemView.findViewById(R.id.noteText)
        private val pageText: TextView = itemView.findViewById(R.id.pageText)
        private val tagsText: TextView = itemView.findViewById(R.id.tagsText)
        private val divider: View = itemView.findViewById(R.id.divider)

        fun bind(
            item: AnnotationFlatItem.Item,
            onAnnotationClick: (Int, Int?, Int?, Int?, String?) -> Unit,
        ) {
            val ann = item.ann
            val hexStr = if (ann.colorHex.startsWith("#")) ann.colorHex else "#${ann.colorHex}"
            val parsedColor = try { hexStr.toColorInt() } catch (_: Exception) { Color.YELLOW }

            val ssb = SpannableStringBuilder(ann.context)

            when (ann.type) {
                0 -> { // Highlight
                    val alphaColor = ColorUtils.setAlphaComponent(parsedColor, (255 * 0.3f).toInt())
                    ssb.setSpan(BackgroundColorSpan(alphaColor), 0, ssb.length, 0)
                }
                1 -> { // Underline
                    ssb.setSpan(UnderlineSpan(), 0, ssb.length, 0)
                }
            }
            contextText.text = ssb
            contextText.setTextColor(onSurfaceColor)

            if (!ann.note.isNullOrEmpty()) {
                noteText.visibility = View.VISIBLE
                noteText.text = ann.note
                noteText.setTextColor(onSurfaceVariantColor)
            } else {
                noteText.visibility = View.GONE
            }

            val context = itemView.context
            val rawPageText = if (ann.part > 0) {
                context.getString(R.string.annotation_item_part_page, ann.part, ann.page)
            } else {
                context.getString(R.string.annotation_item_page, ann.page)
            }
            pageText.text = rawPageText.convertToArabicDigits()
            pageText.setTextColor(onSurfaceVariantColor)

            if (ann.tags.isNotEmpty()) {
                tagsText.visibility = View.VISIBLE
                tagsText.text = ann.tags.split(",").joinToString(" -- ")
                tagsText.setTextColor(onSurfaceVariantColor)
            } else {
                tagsText.visibility = View.GONE
            }

            itemView.background = null
            itemContainer.background = null
            itemContainer.isClickable = false
            itemContainer.isFocusable = false

            itemView.setOnClickListener {
                onAnnotationClick(ann.bkId, ann.contentId, ann.rangeLocation, ann.rangeLength, null)
            }

            if (item.index == item.lastIndex) {
                divider.animate().cancel()
                divider.visibility = View.INVISIBLE
            } else {
                if (divider.isInvisible) {
                    divider.alpha = 0f
                    divider.visibility = View.VISIBLE
                    divider.animate().alpha(0.35f).setDuration(250).start()
                } else {
                    divider.animate().cancel()
                    divider.visibility = View.VISIBLE
                    divider.animate().alpha(0.35f).setDuration(250).start()
                }
            }
        }
    }

    class SpacerViewHolder(view: View) : RecyclerView.ViewHolder(view)

    // ── DiffCallback ─────────────────────────────────────────────────────────

    class DiffCallback : DiffUtil.ItemCallback<AnnotationFlatItem>() {
        override fun areItemsTheSame(old: AnnotationFlatItem, new: AnnotationFlatItem): Boolean {
            return when (old) {
                is AnnotationFlatItem.Header if new is AnnotationFlatItem.Header ->
                    old.group.key == new.group.key

                is AnnotationFlatItem.Item if new is AnnotationFlatItem.Item ->
                    old.ann.id == new.ann.id && old.groupKey == new.groupKey

                is AnnotationFlatItem.Spacer if new is AnnotationFlatItem.Spacer ->
                    old.groupKey == new.groupKey

                else -> false
            }
        }

        override fun areContentsTheSame(old: AnnotationFlatItem, new: AnnotationFlatItem) =
            old == new
    }
}

/** Converts grouped annotation data to a flat list for the RecyclerView. */
fun List<AnnotationGroup>.toFlatItems(
    expandedGroups: Map<String, Boolean>,
): List<AnnotationFlatItem> {
    val result = mutableListOf<AnnotationFlatItem>()
    forEachIndexed { _, group ->
        val isExpanded = expandedGroups[group.key] ?: false
        val annList = group.annotations
        val groupLastIndex = if (isExpanded) annList.size else 0

        result += AnnotationFlatItem.Header(
            group = group,
            isExpanded = isExpanded,
            isFirst = true,
            isLast = groupLastIndex == 0,
        )

        if (isExpanded) {
            annList.forEachIndexed { annIndex, ann ->
                result += AnnotationFlatItem.Item(
                    ann = ann,
                    groupKey = group.key,
                    index = annIndex + 1,
                    lastIndex = groupLastIndex,
                )
            }
        }

        result += AnnotationFlatItem.Spacer(group.key)
    }
    return result
}
