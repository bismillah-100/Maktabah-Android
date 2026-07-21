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
    private val onLoadMore: (Int) -> Unit,
    private val onLoadMoreAuthors: () -> Unit,
    private val onCategorySelectionToggle: (CategoryData) -> Unit
) : ListAdapter<FlatLibraryItem, LibraryAdapter.ViewHolder>(ItemDiffCallback()) {

    private lateinit var recyclerView: RecyclerView
    private var lastClickedParent: java.lang.ref.WeakReference<View>? = null
    private var lastClickedCategoryId: Int? = null

    fun clearLastClickedParent() {
        lastClickedParent = null
        lastClickedCategoryId = null
    }

    fun scrollToTop() {
        if (::recyclerView.isInitialized) {
            recyclerView.scrollToPosition(0)
        }
    }

    private val clearParentRunnable = Runnable {
        lastClickedParent = null
        lastClickedCategoryId = null
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.itemView.tag = null
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
        syncVisibleDecorationState()
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
                applyDecorationState(child, position)
                holder.updateDividerForLast(isLast)
            }
            recyclerView.invalidateItemDecorations()
            recyclerView.invalidate()
        }
    }

    private fun applyDecorationState(view: View, position: Int) {
        view.setTag(R.id.tag_is_first, position == 0)
        view.setTag(R.id.tag_is_last, position == itemCount - 1)
    }

    var viewMode: LibraryViewMode = LibraryViewMode.CATEGORY
        set(value) {
            if (field != value) {
                field = value
                clearLastClickedParent()
                if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
            }
        }

    var isSelectionMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                clearLastClickedParent()
                if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
            }
        }

    var selectedBookIds: Set<Int> = emptySet()
        set(value) {
            if (field != value) {
                field = value
                if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
            }
        }

    var expandedCategories: Set<Int> = emptySet()
        set(value) {
            if (field != value) {
                field = value
                if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
            }
        }

    var onlySelectDownloaded: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
            }
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
        val view = when (viewType) {
            0 -> inflater.inflate(R.layout.item_library_category, parent, false)
            1 -> inflater.inflate(R.layout.item_library_book, parent, false)
            2, 3 -> inflater.inflate(R.layout.item_library_load_more, parent, false)
            else -> throw IllegalArgumentException("Unknown viewType")
        }.apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val flatItem = getItem(position)

        // Tag child item with parent if expanding
        val clickedCategoryId = lastClickedCategoryId
        val clickedParent = lastClickedParent
        if (clickedCategoryId != null && clickedParent != null) {
            val clickedPos = currentList.indexOfFirst {
                val i = it.item
                (i is CategoryData && i.id == clickedCategoryId)
            }
            if (clickedPos != -1) {
                val clickedItem = getItem(clickedPos)
                val clickedLevel = clickedItem.level
                if (position > clickedPos) {
                    var isDescendant = true
                    for (i in (clickedPos + 1) until position) {
                        if (getItem(i).level <= clickedLevel) {
                            isDescendant = false
                            break
                        }
                    }
                    if (isDescendant && flatItem.level > clickedLevel) {
                        holder.itemView.tag = clickedParent
                    } else {
                        holder.itemView.tag = null
                    }
                } else {
                    holder.itemView.tag = null
                }
            } else {
                holder.itemView.tag = null
            }
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
            isBookDownloadedById = isBookDownloadedById,
            onCategoryToggle = onCategoryToggle,
            onBookClick = onBookClick,
            onBookSelectionToggle = onBookSelectionToggle,
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
                        recyclerView.removeCallbacks(clearParentRunnable)
                        recyclerView.postDelayed(clearParentRunnable, 350)
                    }
                }
            }
        )
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: View = itemView
        private val itemContainer: View? = itemView.findViewById(R.id.itemContainer)
        private val nameText: TextView? = itemView.findViewById(R.id.nameText)
        private val typeIcon: ImageView? = itemView.findViewById(R.id.typeIcon)
        private val arrowIcon: ImageView? = itemView.findViewById(R.id.arrowIcon)
        private val indentSpace: Space? = itemView.findViewById(R.id.indentSpace)
        private val divider: View? = itemView.findViewById(R.id.divider)

        init {
            itemView.background = com.maktabah.utils.ItemHighlightDrawable { itemView.parent as? android.view.View }
        }

        private fun getPrimaryColor(): Int = primaryColor

        private fun getSecondaryColor(): Int = secondaryColor

        private fun getOnSurfaceVariantColor(): Int = onSurfaceVariantColor

        fun updateDividerForLast(isLast: Boolean) {
            if (isLast) {
                divider?.animate()?.cancel()
                divider?.visibility = View.INVISIBLE
            } else {
                if (divider?.visibility == View.INVISIBLE) {
                    divider.alpha = 0f
                    divider.visibility = View.VISIBLE
                    divider.animate()?.alpha(0.35f)?.setDuration(250)?.start()
                } else {
                    divider?.animate()?.cancel()
                    divider?.visibility = View.VISIBLE
                    divider?.animate()?.alpha(0.35f)?.setDuration(250)?.start()
                }
            }
        }

        fun bind(
            flatItem: FlatLibraryItem,
            isLast: Boolean,
            viewMode: LibraryViewMode,
            isSelectionMode: Boolean,
            selectedBookIds: Set<Int>,
            expandedCategories: Set<Int>,
            isBookDownloadedById: (Int) -> Boolean,
            onCategoryToggle: (Int) -> Unit,
            onBookClick: (Int) -> Unit,
            onBookSelectionToggle: (Int) -> Unit,
            onLoadMore: (Int) -> Unit,
            onLoadMoreAuthors: () -> Unit,
            onCategorySelectionToggle: (CategoryData) -> Unit,
            onSetExpandClick: (View, Int, Boolean) -> Unit
        ) {
            itemView.translationZ = (100 - flatItem.level).toFloat()

            val density = itemView.context.resources.displayMetrics.density
            val item = flatItem.item

            itemContainer?.background = null
            itemContainer?.isClickable = false
            itemContainer?.isFocusable = false

            val layoutParams = itemView.layoutParams as? ViewGroup.MarginLayoutParams
            if (layoutParams != null) {
                val horizontalMargin = (16 * density).toInt()
                if (layoutParams.leftMargin != horizontalMargin || layoutParams.rightMargin != horizontalMargin ||
                    layoutParams.marginStart != horizontalMargin || layoutParams.marginEnd != horizontalMargin) {
                    layoutParams.leftMargin = horizontalMargin
                    layoutParams.rightMargin = horizontalMargin
                    layoutParams.marginStart = horizontalMargin
                    layoutParams.marginEnd = horizontalMargin
                    itemView.layoutParams = layoutParams
                }
            }

            val dividerParams = divider?.layoutParams as? ViewGroup.MarginLayoutParams
            if (dividerParams != null) {
                val strokeWidth = (1 * density).toInt()
                val marginEndPx = (16 * density).toInt()
                if (item is LoadMoreData || item is LoadMoreAuthors) {
                    dividerParams.marginStart = strokeWidth
                    dividerParams.marginEnd = marginEndPx
                } else {
                    val dividerIndent = (80 + (flatItem.level * 16)) * density
                    dividerParams.marginStart = dividerIndent.toInt()
                    dividerParams.marginEnd = marginEndPx
                }
                divider.layoutParams = dividerParams
            }

            when (item) {
                is CategoryData -> {
                    nameText?.text = item.name
                    val indent = (16 + (flatItem.level * 16)) * density
                    indentSpace?.layoutParams?.width = indent.toInt()
                    indentSpace?.requestLayout()

                    val isExpanded = expandedCategories.contains(item.id)

                    val isRotating = arrowIcon?.getTag(R.id.tag_animating_rotation) != null
                    if (!isRotating) {
                        arrowIcon?.rotation = if (isExpanded) 0f else 90f
                    }

                    arrowIcon?.setColorFilter(getSecondaryColor())

                    if (isSelectionMode) {
                        fun getAllBooks(cats: List<Any>): List<Int> {
                            val list = mutableListOf<Int>()
                            for (c in cats) {
                                if (c is BooksData) {
                                    if (!onlySelectDownloaded || isBookDownloadedById(c.id)) {
                                        list.add(c.id)
                                    }
                                }
                                else if (c is CategoryData) list.addAll(getAllBooks(c.children))
                            }
                            return list
                        }
                        val allBooks = getAllBooks(item.children)
                        val selectedCount = allBooks.count { selectedBookIds.contains(it) }

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
                        typeIcon?.scaleX = -1f // Match Compose scaleX = -1f for RTL/folder icon
                    }

                    arrowIcon?.isClickable = false

                    itemView.setOnClickListener {
                        val isCurrentlyExpanded = expandedCategories.contains(item.id)
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
                    indentSpace?.layoutParams?.width = indent.toInt()
                    indentSpace?.requestLayout()

                    typeIcon?.isClickable = false

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
                        val isDownloaded = flatItem.isDownloaded
                        if (isDownloaded) {
                            typeIcon?.setImageResource(R.drawable.ic_import_contacts)
                        } else {
                            typeIcon?.setImageResource(R.drawable.ic_cloud)
                        }
                        typeIcon?.setColorFilter(getSecondaryColor())
                        typeIcon?.scaleX = -1f
                    }

                    itemView.setOnClickListener {
                        if (isSelectionMode) {
                            onBookSelectionToggle(item.id)
                        } else {
                            onBookClick(item.id)
                        }
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

            updateDividerForLast(isLast)
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
