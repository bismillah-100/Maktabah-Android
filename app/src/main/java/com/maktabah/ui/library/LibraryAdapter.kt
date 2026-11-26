package com.maktabah.ui.library

import android.animation.ObjectAnimator
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.maktabah.R
import com.maktabah.models.BooksData
import com.maktabah.models.CategoryData
import com.maktabah.models.FlatLibraryItem
import com.maktabah.models.LibraryViewMode
import com.maktabah.models.LoadMoreAuthors
import com.maktabah.models.LoadMoreData

class LibraryAdapter(
    private val isBookDownloadedById: (Int) -> Boolean,
    private val onCategoryToggle: (Int) -> Unit,
    private val onBookClick: (Int) -> Unit,
    private val onBookSelectionToggle: (Int) -> Unit,
    private val onBookLongClick: ((Int, View) -> Unit)? = null,
    private val onLoadMore: (Int) -> Unit,
    private val onLoadMoreAuthors: () -> Unit,
    private val onCategorySelectionToggle: (CategoryData) -> Unit
) : ListAdapter<FlatLibraryItem, LibraryAdapter.ViewHolder>(ItemDiffCallback()) {

    private lateinit var recyclerView: RecyclerView
    private var lastClickedParent: java.lang.ref.WeakReference<View>? = null
    private var lastClickedCategoryId: Int? = null
    private var lastClickedDescendantRange: IntRange? = null
    private class CategoryBookIdsEntry(
        val categoryRef: CategoryData,
        val bookIds: IntArray,
    )

    private val categoryBookIdsCache = HashMap<Int, CategoryBookIdsEntry>()

    fun clearLastClickedParent() {
        lastClickedParent = null
        lastClickedCategoryId = null
        lastClickedDescendantRange = null
    }

    fun scrollToTop() {
        if (::recyclerView.isInitialized) {
            recyclerView.scrollToPosition(0)
        }
    }

    private val clearParentRunnable = Runnable {
        lastClickedParent = null
        lastClickedCategoryId = null
        lastClickedDescendantRange = null
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.itemView.tag = null
        holder.unbind()
    }

    var primaryColor: Int = Color.TRANSPARENT
    var secondaryColor: Int = Color.TRANSPARENT
    var onSurfaceVariantColor: Int = Color.TRANSPARENT

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        recyclerView.removeCallbacks(clearParentRunnable)
    }

    override fun onCurrentListChanged(
        previousList: MutableList<FlatLibraryItem>,
        currentList: MutableList<FlatLibraryItem>
    ) {
        super.onCurrentListChanged(previousList, currentList)
        if (onlySelectDownloaded) {
            categoryBookIdsCache.clear()
        }
        recomputeExpandDescendantRange(currentList)
        syncVisibleDecorationState()
    }

    private fun recomputeExpandDescendantRange(list: List<FlatLibraryItem>) {
        val clickedCategoryId = lastClickedCategoryId
        if (clickedCategoryId == null || lastClickedParent == null) {
            lastClickedDescendantRange = null
            return
        }
        val clickedPos = list.indexOfFirst {
            val item = it.item
            item is CategoryData && item.id == clickedCategoryId
        }
        if (clickedPos == -1) {
            lastClickedDescendantRange = null
            return
        }
        val clickedLevel = list[clickedPos].level
        var endPos = clickedPos
        for (i in (clickedPos + 1) until list.size) {
            if (list[i].level > clickedLevel) {
                endPos = i
            } else {
                break
            }
        }
        lastClickedDescendantRange = if (endPos > clickedPos) (clickedPos + 1)..endPos else null
    }

    private fun syncVisibleDecorationState() {
        if (!::recyclerView.isInitialized) return
        recyclerView.post {
            if (!::recyclerView.isInitialized) return@post
            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChildAt(i)
                val holder = recyclerView.getChildViewHolder(child) as? ViewHolder ?: continue
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION || position >= itemCount) continue
                val isLast = position == itemCount - 1
                holder.updateDividerForLast(isLast, animate = true)
            }
            recyclerView.invalidateItemDecorations()
            recyclerView.invalidate()
        }
    }

    fun updateDisplayState(
        viewMode: LibraryViewMode = this.viewMode,
        isSelectionMode: Boolean = this.isSelectionMode,
        selectedBookIds: Set<Int> = this.selectedBookIds,
        onlySelectDownloaded: Boolean = this.onlySelectDownloaded,
    ) {
        val viewModeChanged = this.viewMode != viewMode
        val selectionModeChanged = this.isSelectionMode != isSelectionMode
        val selectedChanged = this.selectedBookIds != selectedBookIds
        val onlyDownloadedChanged = this.onlySelectDownloaded != onlySelectDownloaded

        if (!viewModeChanged && !selectionModeChanged && !selectedChanged && !onlyDownloadedChanged) {
            return
        }

        if (viewModeChanged || selectionModeChanged) {
            clearLastClickedParent()
        }
        if (viewModeChanged || onlyDownloadedChanged || !isSelectionMode) {
            categoryBookIdsCache.clear()
        }

        this.viewModeField = viewMode
        this.isSelectionModeField = isSelectionMode
        this.selectedBookIdsField = selectedBookIds
        this.onlySelectDownloadedField = onlySelectDownloaded

        if (itemCount > 0) {
            val payload = if (!viewModeChanged && !selectionModeChanged && !onlyDownloadedChanged) {
                PAYLOAD_SELECTION_STATE
            } else {
                PAYLOAD_DISPLAY_STATE
            }
            notifyItemRangeChanged(0, itemCount, payload)
        }
    }

    private var viewModeField: LibraryViewMode = LibraryViewMode.CATEGORY
    var viewMode: LibraryViewMode
        get() = viewModeField
        set(value) {
            updateDisplayState(viewMode = value)
        }

    private var isSelectionModeField: Boolean = false
    var isSelectionMode: Boolean
        get() = isSelectionModeField
        set(value) {
            updateDisplayState(isSelectionMode = value)
        }

    private var selectedBookIdsField: Set<Int> = emptySet()
    var selectedBookIds: Set<Int>
        get() = selectedBookIdsField
        set(value) {
            updateDisplayState(selectedBookIds = value)
        }

    companion object {
        private const val PAYLOAD_EXPAND_STATE = "payload_expand_state"
        private const val PAYLOAD_SELECTION_STATE = "payload_selection_state"
        private const val PAYLOAD_DISPLAY_STATE = "payload_display_state"
    }

    var expandedCategories: Set<Int> = emptySet()
        set(value) {
            if (field != value) {
                val changedIds = (field - value) + (value - field)
                field = value
                if (itemCount > 0 && changedIds.isNotEmpty()) {
                    currentList.forEachIndexed { index, flatItem ->
                        val item = flatItem.item
                        if (item is CategoryData && item.id in changedIds) {
                            notifyItemChanged(index, PAYLOAD_EXPAND_STATE)
                        }
                    }
                }
            }
        }

    private var onlySelectDownloadedField: Boolean = false
    var onlySelectDownloaded: Boolean
        get() = onlySelectDownloadedField
        set(value) {
            updateDisplayState(onlySelectDownloaded = value)
        }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).item) {
            is CategoryData -> 0
            is BooksData -> 1
            is LoadMoreData -> 2
            is LoadMoreAuthors -> 3
            else -> throw IllegalArgumentException("Unknown item type")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val horizontalMargin = (16 * parent.context.resources.displayMetrics.density).toInt()
        val view = when (viewType) {
            0 -> inflater.inflate(R.layout.item_library_category, parent, false)
            1 -> inflater.inflate(R.layout.item_library_book, parent, false)
            2, 3 -> inflater.inflate(R.layout.item_library_load_more, parent, false)
            else -> throw IllegalArgumentException("Unknown viewType")
        }.apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val lp = layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                lp.leftMargin = horizontalMargin
                lp.rightMargin = horizontalMargin
                lp.marginStart = horizontalMargin
                lp.marginEnd = horizontalMargin
                layoutParams = lp
            }
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val flatItem = getItem(position)
        when {
            payloads.contains(PAYLOAD_DISPLAY_STATE) -> {
                holder.updateDisplayState(
                    flatItem = flatItem,
                    viewMode = viewMode,
                    isSelectionMode = isSelectionMode,
                    selectedBookIds = selectedBookIds,
                    onCategorySelectionToggle = onCategorySelectionToggle,
                )
            }
            payloads.contains(PAYLOAD_SELECTION_STATE) -> {
                holder.updateSelectionState(
                    flatItem = flatItem,
                    isSelectionMode = isSelectionMode,
                    selectedBookIds = selectedBookIds,
                )
            }
            payloads.contains(PAYLOAD_EXPAND_STATE) -> {
                val item = flatItem.item as? CategoryData ?: return
                holder.updateExpandState(expandedCategories.contains(item.id))
            }
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val flatItem = getItem(position)

        // Tag child item with parent if expanding (O(1) range check)
        val clickedParent = lastClickedParent
        val expandRange = lastClickedDescendantRange
        if (clickedParent != null && expandRange != null && position in expandRange) {
            holder.itemView.tag = clickedParent
        } else {
            holder.itemView.tag = null
        }

        holder.bind(
            flatItem = flatItem,
            isLast = position == itemCount - 1,
            viewMode = viewMode,
            isSelectionMode = isSelectionMode,
            selectedBookIds = selectedBookIds,
            expandedCategories = expandedCategories,
            onCategoryToggle = onCategoryToggle,
            onBookClick = onBookClick,
            onBookSelectionToggle = onBookSelectionToggle,
            onBookLongClick = onBookLongClick,
            onLoadMore = onLoadMore,
            onLoadMoreAuthors = onLoadMoreAuthors,
            onCategorySelectionToggle = onCategorySelectionToggle,
            onSetExpandClick = { parentView, categoryId, isExpanded ->
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    val animator = recyclerView.itemAnimator as? TreeItemAnimator
                    if (isExpanded) { // collapsing
                        val parentRef = java.lang.ref.WeakReference(parentView)
                        animator?.collapsingParent = parentRef
                        for (i in (currentPos + 1) until itemCount) {
                            val nextItem = getItem(i)
                            if (nextItem.level > flatItem.level) {
                                val childHolder = recyclerView.findViewHolderForAdapterPosition(i)
                                childHolder?.itemView?.tag = parentRef
                            } else {
                                break
                            }
                        }
                    } else { // expanding
                        lastClickedParent = java.lang.ref.WeakReference(parentView)
                        lastClickedCategoryId = categoryId
                        recomputeExpandDescendantRange(currentList)
                        recyclerView.removeCallbacks(clearParentRunnable)
                        recyclerView.postDelayed(clearParentRunnable, 350)
                    }
                }
            }
        )
    }

    private fun getOrComputeCategoryBookIds(category: CategoryData): IntArray {
        categoryBookIdsCache[category.id]?.let { entry ->
            if (entry.categoryRef === category) return entry.bookIds
        }
        val collected = ArrayList<Int>()
        fun collect(children: List<Any>) {
            for (i in 0 until children.size) {
                when (val child = children[i]) {
                    is BooksData -> {
                        if (!onlySelectDownloaded || isBookDownloadedById(child.id)) {
                            collected.add(child.id)
                        }
                    }
                    is CategoryData -> collect(child.children)
                }
            }
        }
        collect(category.children)
        val result = IntArray(collected.size) { collected[it] }
        categoryBookIdsCache[category.id] = CategoryBookIdsEntry(category, result)
        return result
    }

    private fun countSelected(bookIds: IntArray, selected: Set<Int>): Int {
        var count = 0
        for (i in 0 until bookIds.size) {
            if (selected.contains(bookIds[i])) {
                count++
            }
        }
        return count
    }

    private class IndentedDividerDrawable(
        private val delegate: android.graphics.drawable.Drawable?,
    ) : android.graphics.drawable.Drawable() {
        private var startInset: Int = 0
        private var endInset: Int = 0
        private var isRtl: Boolean = true

        fun setIndents(start: Int, end: Int, rtl: Boolean) {
            if (startInset != start || endInset != end || isRtl != rtl) {
                startInset = start
                endInset = end
                isRtl = rtl
                invalidateSelf()
            }
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val inner = delegate ?: return
            val b = bounds
            val left = b.left + if (isRtl) endInset else startInset
            val right = b.right - if (isRtl) startInset else endInset
            if (left < right) {
                inner.setBounds(left, b.top, right, b.bottom)
                inner.draw(canvas)
            }
        }

        override fun setAlpha(alpha: Int) {
            delegate?.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            delegate?.colorFilter = colorFilter
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val itemContainer: View? = itemView.findViewById(R.id.itemContainer)
        private val nameText: TextView? = itemView.findViewById(R.id.nameText)
        private val typeIcon: ImageView? = itemView.findViewById(R.id.typeIcon)
        private val arrowIcon: ImageView? = itemView.findViewById(R.id.arrowIcon)
        private val indentSpace: Space? = itemView.findViewById(R.id.indentSpace)
        private val divider: View? = itemView.findViewById(R.id.divider)
        private val dividerDrawable: IndentedDividerDrawable? = divider?.let { div ->
            val lp = div.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null && (lp.marginStart != 0 || lp.marginEnd != 0 || lp.leftMargin != 0 || lp.rightMargin != 0)) {
                lp.marginStart = 0
                lp.marginEnd = 0
                lp.leftMargin = 0
                lp.rightMargin = 0
                div.layoutParams = lp
            }
            IndentedDividerDrawable(div.background).also { div.background = it }
        }

        init {
            itemView.background = com.maktabah.utils.ItemHighlightDrawable { itemView.parent as? View }
            itemView.outlineProvider = null
        }

        fun updateExpandState(isExpanded: Boolean) {
            val isRotating = arrowIcon?.getTag(R.id.tag_animating_rotation) != null
            if (!isRotating) {
                arrowIcon?.rotation = if (isExpanded) 0f else 90f
            }
        }

        private fun getPrimaryColor(): Int = primaryColor

        private fun getSecondaryColor(): Int = secondaryColor

        private fun getOnSurfaceVariantColor(): Int = onSurfaceVariantColor

        fun unbind() {
            itemView.setOnClickListener(null)
            itemView.setOnLongClickListener(null)
            typeIcon?.setOnClickListener(null)
            typeIcon?.animate()?.cancel()
            arrowIcon?.animate()?.cancel()
            (arrowIcon?.getTag(R.id.tag_animating_rotation) as? android.animation.Animator)?.cancel()
            arrowIcon?.setTag(R.id.tag_animating_rotation, null)
            divider?.animate()?.cancel()
            itemView.setTag(R.id.tag_selection_mode, null)
        }

        fun updateDividerForLast(isLast: Boolean, animate: Boolean = false) {
            val div = divider ?: return
            if (isLast) {
                div.animate().cancel()
                div.visibility = View.INVISIBLE
            } else if (animate && div.visibility == View.INVISIBLE) {
                div.alpha = 0f
                div.visibility = View.VISIBLE
                div.animate().alpha(0.35f).setDuration(250).start()
            } else {
                div.animate().cancel()
                div.visibility = View.VISIBLE
                div.alpha = 0.35f
            }
        }

        private fun bindCategoryIconAndSelection(
            item: CategoryData,
            viewMode: LibraryViewMode,
            isSelectionMode: Boolean,
            selectedBookIds: Set<Int>,
            selectionModeChanged: Boolean,
            density: Float,
            onCategorySelectionToggle: (CategoryData) -> Unit,
        ) {
            if (isSelectionMode) {
                val allBooks = getOrComputeCategoryBookIds(item)
                val selectedCount = countSelected(allBooks, selectedBookIds)

                val allSelected = allBooks.isNotEmpty() && selectedCount == allBooks.size
                val mixedSelected = allBooks.isNotEmpty() && selectedCount > 0 && selectedCount < allBooks.size

                if (allSelected) {
                    typeIcon?.setImageResource(R.drawable.ic_check_circle)
                    typeIcon?.setColorFilter(getPrimaryColor())
                } else if (mixedSelected) {
                    typeIcon?.setImageResource(R.drawable.ic_check_indeterminate)
                    typeIcon?.setColorFilter(getPrimaryColor())
                } else {
                    typeIcon?.setImageResource(R.drawable.ic_circle)
                    typeIcon?.setColorFilter(getOnSurfaceVariantColor())
                }
                typeIcon?.scaleX = 1f
                itemView.isSelected = allSelected || mixedSelected
                typeIcon?.setOnClickListener { onCategorySelectionToggle(item) }
            } else {
                itemView.isSelected = false
                typeIcon?.setOnClickListener(null)
                typeIcon?.isClickable = false
                typeIcon?.setImageResource(if (viewMode == LibraryViewMode.AUTHOR) R.drawable.ic_person else R.drawable.ic_folder)
                typeIcon?.setColorFilter(getSecondaryColor())
                typeIcon?.scaleX = -1f
            }

            if (selectionModeChanged) {
                typeIcon?.animate()?.cancel()
                typeIcon?.alpha = 0f
                typeIcon?.translationX = if (isSelectionMode) -20f * density else 20f * density
                typeIcon?.animate()
                    ?.alpha(1f)
                    ?.translationX(0f)
                    ?.setDuration(250)
                    ?.start()
            } else {
                typeIcon?.alpha = 1f
                typeIcon?.translationX = 0f
            }
        }

        private fun bindBookIconAndSelection(
            flatItem: FlatLibraryItem,
            item: BooksData,
            isSelectionMode: Boolean,
            selectedBookIds: Set<Int>,
            selectionModeChanged: Boolean,
            density: Float,
        ) {
            if (isSelectionMode) {
                val isSelected = selectedBookIds.contains(item.id)
                itemView.isSelected = isSelected
                if (isSelected) {
                    typeIcon?.setImageResource(R.drawable.ic_check_circle)
                    typeIcon?.setColorFilter(getPrimaryColor())
                } else {
                    typeIcon?.setImageResource(R.drawable.ic_circle)
                    typeIcon?.setColorFilter(getOnSurfaceVariantColor())
                }
                typeIcon?.scaleX = 1f
            } else {
                itemView.isSelected = false
                if (flatItem.isDownloaded) {
                    typeIcon?.setImageResource(R.drawable.ic_import_contacts)
                } else {
                    typeIcon?.setImageResource(R.drawable.ic_cloud)
                }
                typeIcon?.setColorFilter(getSecondaryColor())
                typeIcon?.scaleX = -1f
            }

            if (selectionModeChanged) {
                typeIcon?.animate()?.cancel()
                typeIcon?.alpha = 0f
                typeIcon?.translationX = if (isSelectionMode) -20f * density else 20f * density
                typeIcon?.animate()
                    ?.alpha(1f)
                    ?.translationX(0f)
                    ?.setDuration(250)
                    ?.start()
            } else {
                typeIcon?.alpha = 1f
                typeIcon?.translationX = 0f
            }
        }

        fun updateSelectionState(
            flatItem: FlatLibraryItem,
            isSelectionMode: Boolean,
            selectedBookIds: Set<Int>,
        ) {
            val density = itemView.context.resources.displayMetrics.density
            when (val item = flatItem.item) {
                is CategoryData -> bindCategoryIconAndSelection(
                    item = item,
                    viewMode = viewMode,
                    isSelectionMode = isSelectionMode,
                    selectedBookIds = selectedBookIds,
                    selectionModeChanged = false,
                    density = density,
                    onCategorySelectionToggle = onCategorySelectionToggle,
                )
                is BooksData -> bindBookIconAndSelection(
                    flatItem = flatItem,
                    item = item,
                    isSelectionMode = isSelectionMode,
                    selectedBookIds = selectedBookIds,
                    selectionModeChanged = false,
                    density = density,
                )
            }
        }

        fun updateDisplayState(
            flatItem: FlatLibraryItem,
            viewMode: LibraryViewMode,
            isSelectionMode: Boolean,
            selectedBookIds: Set<Int>,
            onCategorySelectionToggle: (CategoryData) -> Unit,
        ) {
            val density = itemView.context.resources.displayMetrics.density
            val wasSelectionMode = itemView.getTag(R.id.tag_selection_mode) as? Boolean
            val selectionModeChanged = wasSelectionMode != null && wasSelectionMode != isSelectionMode
            itemView.setTag(R.id.tag_selection_mode, isSelectionMode)

            when (val item = flatItem.item) {
                is CategoryData -> bindCategoryIconAndSelection(
                    item = item,
                    viewMode = viewMode,
                    isSelectionMode = isSelectionMode,
                    selectedBookIds = selectedBookIds,
                    selectionModeChanged = selectionModeChanged,
                    density = density,
                    onCategorySelectionToggle = onCategorySelectionToggle,
                )
                is BooksData -> bindBookIconAndSelection(
                    flatItem = flatItem,
                    item = item,
                    isSelectionMode = isSelectionMode,
                    selectedBookIds = selectedBookIds,
                    selectionModeChanged = selectionModeChanged,
                    density = density,
                )
            }
        }

        fun bind(
            flatItem: FlatLibraryItem,
            isLast: Boolean,
            viewMode: LibraryViewMode,
            isSelectionMode: Boolean,
            selectedBookIds: Set<Int>,
            expandedCategories: Set<Int>,
            onCategoryToggle: (Int) -> Unit,
            onBookClick: (Int) -> Unit,
            onBookSelectionToggle: (Int) -> Unit,
            onBookLongClick: ((Int, View) -> Unit)?,
            onLoadMore: (Int) -> Unit,
            onLoadMoreAuthors: () -> Unit,
            onCategorySelectionToggle: (CategoryData) -> Unit,
            onSetExpandClick: (View, Int, Boolean) -> Unit
        ) {
            itemView.translationZ = (20 - flatItem.level) * 0.1f

            val density = itemView.context.resources.displayMetrics.density
            val item = flatItem.item

            itemContainer?.background = null
            itemContainer?.isClickable = false
            itemContainer?.isFocusable = false

            if (dividerDrawable != null) {
                val strokeWidth = (1 * density).toInt()
                val targetStart = if (item is LoadMoreData || item is LoadMoreAuthors) {
                    strokeWidth
                } else {
                    ((80 + (flatItem.level * 16)) * density).toInt()
                }
                dividerDrawable.setIndents(
                    start = targetStart,
                    end = strokeWidth,
                    rtl = itemView.layoutDirection == View.LAYOUT_DIRECTION_RTL,
                )
            }

            val wasSelectionMode = itemView.getTag(R.id.tag_selection_mode) as? Boolean
            val selectionModeChanged =
                wasSelectionMode != null && wasSelectionMode != isSelectionMode
            itemView.setTag(R.id.tag_selection_mode, isSelectionMode)

            when (item) {
                is CategoryData -> {
                    nameText?.text = item.name
                    val indent = (16 + (flatItem.level * 16)) * density
                    val targetIndent = indent.toInt()
                    if (indentSpace != null && indentSpace.layoutParams.width != targetIndent) {
                        indentSpace.layoutParams.width = targetIndent
                        indentSpace.requestLayout()
                    }

                    val isExpanded = expandedCategories.contains(item.id)

                    val isRotating = arrowIcon?.getTag(R.id.tag_animating_rotation) != null
                    if (!isRotating) {
                        arrowIcon?.rotation = if (isExpanded) 0f else 90f
                    }

                    arrowIcon?.setColorFilter(getSecondaryColor())

                    bindCategoryIconAndSelection(
                        item = item,
                        viewMode = viewMode,
                        isSelectionMode = isSelectionMode,
                        selectedBookIds = selectedBookIds,
                        selectionModeChanged = selectionModeChanged,
                        density = density,
                        onCategorySelectionToggle = onCategorySelectionToggle,
                    )

                    arrowIcon?.isClickable = false

                    itemView.setOnClickListener {
                        val isCurrentlyExpanded = this@LibraryAdapter.expandedCategories.contains(item.id)
                        val targetRotation = if (isCurrentlyExpanded) 90f else 0f

                        val existingAnimator = arrowIcon?.getTag(R.id.tag_animating_rotation) as? ObjectAnimator
                        existingAnimator?.cancel()

                        val currentRotation = arrowIcon?.rotation ?: if (isCurrentlyExpanded) 0f else 90f
                        val animator = ObjectAnimator.ofFloat(arrowIcon, "rotation", currentRotation, targetRotation).setDuration(250)
                        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationStart(animation: android.animation.Animator) {
                                arrowIcon?.setTag(R.id.tag_animating_rotation, animator)
                            }
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                arrowIcon?.setTag(R.id.tag_animating_rotation, null)
                            }
                        })
                        animator.start()

                        onSetExpandClick(itemView, item.id, isCurrentlyExpanded)
                        onCategoryToggle(item.id)
                    }
                }
                is BooksData -> {
                    nameText?.text = item.name
                    val indent = (16 + (flatItem.level * 16) + 32) * density
                    val targetIndent = indent.toInt()
                    if (indentSpace != null && indentSpace.layoutParams.width != targetIndent) {
                        indentSpace.layoutParams.width = targetIndent
                        indentSpace.requestLayout()
                    }

                    typeIcon?.isClickable = false

                    bindBookIconAndSelection(
                        flatItem = flatItem,
                        item = item,
                        isSelectionMode = isSelectionMode,
                        selectedBookIds = selectedBookIds,
                        selectionModeChanged = selectionModeChanged,
                        density = density,
                    )

                    itemView.setOnClickListener {
                        if (isSelectionMode) {
                            onBookSelectionToggle(item.id)
                        } else {
                            onBookClick(item.id)
                        }
                    }

                    if (onBookLongClick != null) {
                        itemView.setOnLongClickListener {
                            onBookLongClick(item.id, it)
                            true
                        }
                    } else {
                        itemView.setOnLongClickListener(null)
                    }
                }
                is LoadMoreData -> {
                    nameText?.text = itemView.context.getString(R.string.library_load_more_items, item.hiddenCount)
                    itemView.setOnClickListener { onLoadMore(item.categoryId) }
                }
                is LoadMoreAuthors -> {
                    nameText?.text = itemView.context.getString(R.string.library_load_more_authors, item.hiddenCount)
                    itemView.setOnClickListener { onLoadMoreAuthors() }
                }
            }

            updateDividerForLast(isLast, animate = false)
        }
    }
}

class ItemDiffCallback : DiffUtil.ItemCallback<FlatLibraryItem>() {
    override fun areItemsTheSame(oldItem: FlatLibraryItem, newItem: FlatLibraryItem): Boolean {
        val old = oldItem.item
        val new = newItem.item
        if (old is CategoryData && new is CategoryData) return old.id == new.id
        if (old is BooksData && new is BooksData) return old.id == new.id
        if (old is LoadMoreData && new is LoadMoreData) return old.categoryId == new.categoryId
        if (old is LoadMoreAuthors && new is LoadMoreAuthors) return true
        return false
    }

    override fun areContentsTheSame(oldItem: FlatLibraryItem, newItem: FlatLibraryItem): Boolean {
        return oldItem == newItem
    }
}
