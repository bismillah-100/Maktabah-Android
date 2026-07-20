package com.maktabah.ui.annotation

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withClip
import com.maktabah.R
import com.maktabah.cloudKit.CloudKitSyncManager
import com.maktabah.database.AnnotationManager
import com.maktabah.models.AnnotationGroup
import com.maktabah.models.AnnotationGroupingMode
import com.maktabah.models.AnnotationSearchScope
import com.maktabah.models.AnnotationSortField
import com.maktabah.ui.common.DonationIconButton
import com.maktabah.ui.common.GroupedRecyclerView
import com.maktabah.ui.common.rememberGroupedListColors
import com.maktabah.ui.history.HistoryViewModel
import com.maktabah.ui.library.LibraryViewModel
import com.maktabah.ui.search.SearchTextField
import com.maktabah.ui.search.AnnotationSearchScopeSegmentedRow
import com.maktabah.utils.GroupedCardDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationsScreen(
    libraryViewModel: LibraryViewModel,
    annotationManager: AnnotationManager,
    cloudKitSyncManager: CloudKitSyncManager,
    historyViewModel: HistoryViewModel,
    viewModel: AnnotationsViewModel,
    bottomPadding: Dp,
    bookIdFilter: Int? = null,
    onBack: (() -> Unit)? = null,
    onNavigateToReader: (Int, Int?, Int?, Int?, String?) -> Unit,
    hasDonated: Boolean,
) {
    val context = LocalContext.current
    val isDataLoaded by libraryViewModel.isDataLoaded.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val scope = rememberCoroutineScope()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchScope by viewModel.searchScope.collectAsState()
    val groupedAnnotations by viewModel.groupedAnnotations.collectAsState()
    val expandedGroups by viewModel.expandedGroups.collectAsState()
    val groupingMode by viewModel.groupingMode.collectAsState()
    val sortField by viewModel.sortField.collectAsState()
    val sortAscending by viewModel.sortAscending.collectAsState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(viewModel, annotationManager, libraryViewModel) {
        viewModel.initialize(context, annotationManager, libraryViewModel.dataManager)
    }

    LaunchedEffect(bookIdFilter) {
        viewModel.setBookIdFilter(bookIdFilter)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            modifier =
                Modifier.pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                    })
                },
            topBar = {
                AnnotationsTopBar(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { viewModel.searchQuery.value = it },
                    searchScope = searchScope,
                    onSearchScopeChange = { viewModel.setSearchScope(it) },
                    bookIdFilter = bookIdFilter,
                    onBack = onBack,
                    hasDonated = hasDonated,
                    onSyncRequested = {
                        scope.launch {
                            val resultMsg = withContext(Dispatchers.IO) {
                                cloudKitSyncManager.fetchChanges(
                                    context,
                                    annotationManager,
                                    historyViewModel
                                )
                            }
                            withContext(Dispatchers.Main) {
                                if (resultMsg != null) {
                                    Toast.makeText(context, resultMsg, Toast.LENGTH_LONG).show()
                                }
                            }
                            viewModel.forceReload(annotationManager)
                        }
                    },
                    groupingMode = groupingMode,
                    onGroupingModeChange = { viewModel.setGroupingMode(it) },
                    sortField = sortField,
                    onSortFieldChange = { viewModel.setSortField(it) },
                    sortAscending = sortAscending,
                    onSortAscendingChange = { viewModel.setSortAscending(it) }
                )
            },
        ) { padding ->
            if (!isDataLoaded || isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (groupedAnnotations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.annotations_empty_hint),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                AnnotationsList(
                    groupedAnnotations = groupedAnnotations,
                    expandedGroups = expandedGroups,
                    groupingMode = groupingMode,
                    padding = padding,
                    bottomPadding = bottomPadding,
                    onToggleGroup = { viewModel.toggleGroupExpanded(it) },
                    onAnnotationClick = onNavigateToReader,
                    onAnnotationDelete = { viewModel.forceReload(annotationManager) },
                    annotationManager = annotationManager,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnotationsTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchScope: AnnotationSearchScope,
    onSearchScopeChange: (AnnotationSearchScope) -> Unit,
    bookIdFilter: Int?,
    onBack: (() -> Unit)?,
    hasDonated: Boolean,
    onSyncRequested: () -> Unit,
    groupingMode: AnnotationGroupingMode,
    onGroupingModeChange: (AnnotationGroupingMode) -> Unit,
    sortField: AnnotationSortField,
    onSortFieldChange: (AnnotationSortField) -> Unit,
    sortAscending: Boolean,
    onSortAscendingChange: (Boolean) -> Unit,
) {
    var isSyncing by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
    ) {
        TopAppBar(
            title = {
                SearchTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = stringResource(R.string.reader_annotations_search_placeholder),
                    onClearClick = { onSearchQueryChange("") },
                    modifier = Modifier.padding(end = 12.dp)
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            navigationIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (bookIdFilter != null && onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.reader_action_back),
                            )
                        }
                    }
                    if (!hasDonated) {
                        DonationIconButton()
                    }
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        isSyncing = true
                        onSyncRequested()
                        isSyncing = false
                    },
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.history_settings_cloudkit),
                        )
                    }
                }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.ImportExport,
                            contentDescription = stringResource(R.string.reader_action_menu_options),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        Text(
                            text = stringResource(R.string.annotations_group_by),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.annotations_menu_book)) },
                            onClick = {
                                onGroupingModeChange(AnnotationGroupingMode.BOOK); showMenu = false
                            },
                            leadingIcon = {
                                if (groupingMode == AnnotationGroupingMode.BOOK) Icon(
                                    Icons.Default.Check,
                                    null
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.annotations_menu_tag)) },
                            onClick = {
                                onGroupingModeChange(AnnotationGroupingMode.TAG); showMenu = false
                            },
                            leadingIcon = {
                                if (groupingMode == AnnotationGroupingMode.TAG) Icon(
                                    Icons.Default.Check,
                                    null
                                )
                            },
                        )
                        HorizontalDivider()
                        Text(
                            text = stringResource(R.string.annotations_sort_by),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        AnnotationSortItem(
                            AnnotationSortField.CREATED_AT,
                            R.string.annotations_menu_date,
                            sortField,
                            onSortFieldChange
                        ) { showMenu = false }
                        AnnotationSortItem(
                            AnnotationSortField.CONTEXT,
                            R.string.annotations_menu_context,
                            sortField,
                            onSortFieldChange
                        ) { showMenu = false }
                        AnnotationSortItem(
                            AnnotationSortField.PAGE,
                            R.string.annotations_menu_page,
                            sortField,
                            onSortFieldChange
                        ) { showMenu = false }
                        AnnotationSortItem(
                            AnnotationSortField.PART,
                            R.string.annotations_menu_part,
                            sortField,
                            onSortFieldChange
                        ) { showMenu = false }

                        HorizontalDivider()
                        Text(
                            text = stringResource(R.string.annotations_order),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.annotations_menu_asc)) },
                            onClick = { onSortAscendingChange(true); showMenu = false },
                            leadingIcon = { if (sortAscending) Icon(Icons.Default.Check, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.annotations_menu_desc)) },
                            onClick = { onSortAscendingChange(false); showMenu = false },
                            leadingIcon = { if (!sortAscending) Icon(Icons.Default.Check, null) },
                        )
                    }
                }
            },
        )
        if (searchQuery.isNotEmpty()) {
            AnnotationSearchScopeSegmentedRow(
                searchScope = searchScope,
                onSearchScopeChange = onSearchScopeChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
            )
        }
    }
}

@Composable
private fun AnnotationSortItem(
    field: AnnotationSortField,
    labelRes: Int,
    currentField: AnnotationSortField,
    onClick: (AnnotationSortField) -> Unit,
    onDismiss: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        onClick = { onClick(field); onDismiss() },
        leadingIcon = { if (currentField == field) Icon(Icons.Default.Check, null) },
    )
}

@Composable
private fun AnnotationsList(
    groupedAnnotations: List<AnnotationGroup>,
    expandedGroups: Map<String, Boolean>,
    groupingMode: AnnotationGroupingMode,
    padding: PaddingValues,
    bottomPadding: Dp,
    onToggleGroup: (String) -> Unit,
    onAnnotationClick: (Int, Int?, Int?, Int?, String?) -> Unit,
    onAnnotationDelete: () -> Unit,
    annotationManager: AnnotationManager,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("AnnotationsPrefs", android.content.Context.MODE_PRIVATE) }
    var hasRestoredScroll by rememberSaveable { mutableStateOf(false) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        val colors = rememberGroupedListColors()

        val flatItems = remember(groupedAnnotations, expandedGroups) {
            groupedAnnotations.toFlatItems(expandedGroups)
        }

        val adapter = remember {
            AnnotationsAdapter(
                onToggleGroup = onToggleGroup,
                onAnnotationClick = onAnnotationClick,
            ).apply {
                this.primaryColor = colors.primaryColor
                this.secondaryColor = colors.secondaryColor
                this.onSurfaceColor = colors.onSurfaceColor
                this.onSurfaceVariantColor = colors.onSurfaceVariantColor
                this.groupingMode = groupingMode
                this.stateRestorationPolicy = androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
                submitList(flatItems)
            }
        }

        LaunchedEffect(flatItems, colors, groupingMode) {
            adapter.primaryColor = colors.primaryColor
            adapter.secondaryColor = colors.secondaryColor
            adapter.onSurfaceColor = colors.onSurfaceColor
            adapter.onSurfaceVariantColor = colors.onSurfaceVariantColor
            adapter.groupingMode = groupingMode
            adapter.submitList(flatItems)
        }

        val itemTouchHelper = remember(adapter, annotationManager, onAnnotationDelete) {
            androidx.recyclerview.widget.ItemTouchHelper(
                object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                    0,
                    androidx.recyclerview.widget.ItemTouchHelper.RIGHT
                ) {
                    override fun onMove(
                        recyclerView: androidx.recyclerview.widget.RecyclerView,
                        viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                        target: androidx.recyclerview.widget.RecyclerView.ViewHolder
                    ): Boolean = false

                    override fun getSwipeDirs(
                        recyclerView: androidx.recyclerview.widget.RecyclerView,
                        viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
                    ): Int {
                        if (viewHolder is AnnotationsAdapter.ItemViewHolder) {
                            return androidx.recyclerview.widget.ItemTouchHelper.RIGHT
                        }
                        return 0
                    }

                    override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
                        return defaultValue * 10f
                    }

                    override fun getSwipeThreshold(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder): Float {
                        return 0.55f
                    }

                    override fun onSwiped(
                        viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                        direction: Int
                    ) {
                        val pos = viewHolder.bindingAdapterPosition
                        if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                            val item = adapter.currentList[pos] as? AnnotationFlatItem.Item
                            if (item != null) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        item.ann.id?.let { id ->
                                            annotationManager.deleteAnnotation(id, item.ann.ckRecordId)
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        onAnnotationDelete()
                                    }
                                }
                            }
                        }
                    }

                    override fun onChildDraw(
                        c: android.graphics.Canvas,
                        recyclerView: androidx.recyclerview.widget.RecyclerView,
                        viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                        dX: Float,
                        dY: Float,
                        actionState: Int,
                        isCurrentlyActive: Boolean
                    ) {
                        val ctx = recyclerView.context
                        val itemView = viewHolder.itemView
                        val itemPos = viewHolder.bindingAdapterPosition
                        if (itemPos != androidx.recyclerview.widget.RecyclerView.NO_POSITION && dX != 0f) {
                            val item = adapter.currentList[itemPos] as? AnnotationFlatItem.Item
                            if (item != null) {
                                val isLast = item.index == item.lastIndex
                                val density = ctx.resources.displayMetrics.density
                                val maxRadius = 30f * density
                                val bottomRadius = if (isLast) maxRadius else 0f

                                val path = android.graphics.Path()
                                val rect = android.graphics.RectF(
                                    if (dX > 0) itemView.left.toFloat() else itemView.right.toFloat() + dX,
                                    itemView.top.toFloat(),
                                    if (dX > 0) itemView.left.toFloat() + dX else itemView.right.toFloat(),
                                    itemView.bottom.toFloat()
                                )
                                val radii = floatArrayOf(
                                    0f, 0f,
                                    0f, 0f,
                                    bottomRadius, bottomRadius,
                                    bottomRadius, bottomRadius
                                )
                                path.addRoundRect(rect, radii, android.graphics.Path.Direction.CW)
                                c.withClip(path) {
                                    drawColor("#FF3B30".toColorInt()) // iOS Red

                                    // Trigger haptic feedback when swiped past 55% width threshold
                                    val ratio = kotlin.math.abs(dX) / itemView.width.toFloat()
                                    val threshold = 0.55f
                                    val isPastThreshold = ratio >= threshold
                                    val hasTriggeredHaptic =
                                        itemView.getTag(R.id.tag_swipe_haptic) as? Boolean
                                            ?: false
                                    if (isPastThreshold && !hasTriggeredHaptic) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                            itemView.performHapticFeedback(android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                                        }
                                        itemView.setTag(R.id.tag_swipe_haptic, true)
                                    } else if (!isPastThreshold && hasTriggeredHaptic) {
                                        itemView.setTag(R.id.tag_swipe_haptic, false)
                                    }

                                    // Animation logic for anchor bias (0.0 = centered, 1.0 = anchored)
                                    var anchorBias =
                                        itemView.getTag(R.id.tag_anchor_bias) as? Float ?: 0f
                                    val targetBias = if (isPastThreshold) 1f else 0f
                                    val currentTarget =
                                        itemView.getTag(R.id.tag_target_bias) as? Float

                                    if (currentTarget != targetBias) {
                                        itemView.setTag(R.id.tag_target_bias, targetBias)
                                        (itemView.getTag(R.id.tag_bias_animator) as? android.animation.ValueAnimator)?.cancel()

                                        val animator = android.animation.ValueAnimator.ofFloat(
                                            anchorBias,
                                            targetBias
                                        ).apply {
                                            duration = 250
                                            interpolator =
                                                android.view.animation.DecelerateInterpolator()
                                            addUpdateListener {
                                                itemView.setTag(
                                                    R.id.tag_anchor_bias,
                                                    it.animatedValue as Float
                                                )
                                                recyclerView.postInvalidateOnAnimation()
                                            }
                                        }
                                        animator.start()
                                        itemView.setTag(R.id.tag_bias_animator, animator)
                                    }
                                    anchorBias =
                                        itemView.getTag(R.id.tag_anchor_bias) as? Float ?: 0f

                                    // Draw trash icon with animated "pulling" effect
                                    val trashIcon =
                                        androidx.core.content.ContextCompat.getDrawable(
                                            ctx,
                                            R.drawable.ic_delete
                                        )
                                    if (trashIcon != null) {
                                        trashIcon.setTint(
                                            android.graphics.Color.argb(
                                                (255 * 0.85f).toInt(),
                                                255,
                                                255,
                                                255
                                            )
                                        )
                                        val iconSize = (38 * density).toInt()
                                        val margin = (16 * density).toInt()
                                        val iconTop =
                                            itemView.top + (itemView.height - iconSize) / 2
                                        val iconBottom = iconTop + iconSize

                                        val centeredLeft = if (dX < 0) {
                                            itemView.right + dX / 2 - iconSize / 2
                                        } else {
                                            itemView.left + dX / 2 - iconSize / 2
                                        }

                                        val anchoredLeft = if (dX < 0) {
                                            itemView.right + dX + margin
                                        } else {
                                            itemView.left + dX - margin - iconSize
                                        }

                                        val iconLeft =
                                            (centeredLeft + (anchoredLeft - centeredLeft) * anchorBias).toInt()
                                        val iconRight = iconLeft + iconSize
                                        trashIcon.setBounds(
                                            iconLeft,
                                            iconTop,
                                            iconRight,
                                            iconBottom
                                        )
                                        trashIcon.draw(this)
                                    }

                                }
                            }
                        }
                        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                    }

                    override fun clearView(
                        recyclerView: androidx.recyclerview.widget.RecyclerView,
                        viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
                    ) {
                        super.clearView(recyclerView, viewHolder)
                        viewHolder.itemView.setTag(R.id.tag_swipe_haptic, null)
                        viewHolder.itemView.setTag(R.id.tag_anchor_bias, null)
                        viewHolder.itemView.setTag(R.id.tag_target_bias, null)
                        (viewHolder.itemView.getTag(R.id.tag_bias_animator) as? android.animation.ValueAnimator)?.cancel()
                        viewHolder.itemView.setTag(R.id.tag_bias_animator, null)
                    }
                }
            )
        }

        GroupedRecyclerView(
            recyclerViewId = R.id.annotations_recycler_view,
            adapter = adapter,
            padding = padding,
            bottomContentPadding = bottomPadding,
            colors = colors,
            itemTouchHelper = itemTouchHelper,
            onScrollStateChanged = { rv, newState ->
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                    if (lm != null) {
                        val pos = lm.findFirstVisibleItemPosition()
                        val view = lm.findViewByPosition(pos)
                        val offset = (view?.top ?: 0) - rv.paddingTop
                        sharedPrefs.edit()
                            .putInt("scroll_pos", pos)
                            .putInt("scroll_offset", offset)
                            .apply()
                    }
                }
            },
            decorationFactory = { rv ->
                val ctx = rv.context
                val cornerRadius = 30 * ctx.resources.displayMetrics.density
                val marginH = 16 * ctx.resources.displayMetrics.density

                GroupedCardDecoration(
                    cornerRadius = cornerRadius,
                    strokeWidth = 0f,
                    marginHorizontal = marginH
                ) { position ->
                    if (position < 0 || position >= adapter.currentList.size) return@GroupedCardDecoration null
                    when (val item = adapter.currentList[position]) {
                        is AnnotationFlatItem.Header -> GroupedCardDecoration.GroupInfo(
                            isFirst = true,
                            isLast = item.isLast
                        )
                        is AnnotationFlatItem.Item -> GroupedCardDecoration.GroupInfo(
                            isFirst = false,
                            isLast = item.index == item.lastIndex
                        )
                        is AnnotationFlatItem.Spacer -> null
                    }
                }
            },
            update = { rv ->
                if (!hasRestoredScroll && adapter.currentList.isNotEmpty()) {
                    hasRestoredScroll = true
                    val pos = sharedPrefs.getInt("scroll_pos", 0)
                    val offset = sharedPrefs.getInt("scroll_offset", 0)
                    if (pos != 0 || offset != 0) {
                        (rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                            ?.scrollToPositionWithOffset(pos, offset)
                    }
                }
            }
        )

    }
}
