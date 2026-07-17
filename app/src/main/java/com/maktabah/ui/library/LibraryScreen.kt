package com.maktabah.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ImportContacts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maktabah.R
import com.maktabah.database.AnnotationManager
import com.maktabah.models.CategoryData
import com.maktabah.models.FlatLibraryItem
import com.maktabah.models.LibraryViewMode
import com.maktabah.ui.common.DonationCard
import com.maktabah.ui.common.DonationIconButton
import com.maktabah.ui.common.GroupedRecyclerView
import com.maktabah.ui.common.rememberGroupedListColors
import com.maktabah.ui.reader.ReaderTabManager
import com.maktabah.ui.search.SearchTextField
import com.maktabah.ui.settings.SettingsDialog
import com.maktabah.utils.GroupedCardDecoration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    annotationManager: AnnotationManager,
    tabManager: ReaderTabManager,
    onNavigateToReader: (Int, Int?, Int?, Int?, String?) -> Unit,
    onNavigateToCloudKit: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    hasDonated: Boolean,
) {
    val context = LocalContext.current
    val downloadedBookIds by viewModel.downloadedBookIds.collectAsState()
    val flatVisibleItems by viewModel.flatVisibleItems.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val showOnlyDownloaded by viewModel.showOnlyDownloaded.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val isBulkDownloading by viewModel.isBulkDownloading.collectAsState()
    val selectedBookIds by viewModel.selectedBookIds.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val expandedCategories by viewModel.expandedCategories.collectAsState()
    val focusManager = LocalFocusManager.current
    var showImportSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier =
                Modifier.pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                    })
                },
            topBar = {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(Color.Transparent),
                ) {
                    TopAppBar(
                        navigationIcon = {
                            if (!hasDonated) {
                                DonationIconButton()
                            }
                        },
                        title = {
                            SearchTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.updateSearchQuery(it) },
                                placeholder =
                                    if (viewMode == LibraryViewMode.CATEGORY) {
                                        stringResource(R.string.library_search_books_placeholder)
                                    } else {
                                        stringResource(R.string.library_search_authors_placeholder)
                                    },
                                modifier = Modifier.padding(end = 12.dp),
                                onClearClick = { viewModel.updateSearchQuery("") }
                            )
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                            ),
                        actions = {
                            if (isSelectionMode && selectedBookIds.isNotEmpty()) {
                                val hasDownloadedSelected =
                                    selectedBookIds.any { downloadedBookIds.contains(it) }
                                if (hasDownloadedSelected) {
                                    IconButton(
                                        onClick = {
                                            viewModel.deleteSelectedBooks(context, tabManager) { msg ->
                                                android.widget.Toast
                                                    .makeText(
                                                        context,
                                                        msg,
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                            }
                                        },
                                        enabled = !isBulkDownloading,
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.library_action_delete_selected),
                                        )
                                    }
                                }
                            }
                            if (isSelectionMode) {
                                IconButton(
                                    onClick = { viewModel.toggleSelectionMode(context) },
                                    enabled = !isBulkDownloading,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.library_action_selection_mode),
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.toggleShowOnlyDownloaded() }) {
                                Icon(
                                    imageVector =
                                        if (showOnlyDownloaded) {
                                            Icons.Filled.CloudDone
                                        } else {
                                            Icons.Outlined.CloudDone
                                        },
                                    contentDescription = stringResource(R.string.library_action_filter_downloaded),
                                )
                            }
                            if (!isSelectionMode) {
                                LibraryMoreOptionsMenu(
                                    viewMode = viewMode,
                                    onToggleSelectionMode = { viewModel.toggleSelectionMode(context) },
                                    onSetViewMode = { viewModel.setViewMode(it) },
                                    onImportClick = { showImportSheet = true },
                                    onSettingsClick = { showSettings = true },
                                )
                            }
                        },
                    )
                }
            },
        ) { padding ->
            LibraryList(
                flatVisibleItems = flatVisibleItems,
                searchQuery = searchQuery,
                showOnlyDownloaded = showOnlyDownloaded,
                viewMode = viewMode,
                isSelectionMode = isSelectionMode,
                selectedBookIds = selectedBookIds,
                expandedCategories = expandedCategories,
                isBookDownloadedById = { viewModel.isBookDownloadedById(it) },
                onCategoryToggle = { viewModel.toggleCategory(it) },
                onBookClick = { onNavigateToReader(it, null, null, null, null) },
                onBookSelectionToggle = { viewModel.toggleBookSelection(context, it) },
                onLoadMore = { viewModel.loadMore(it) },
                onLoadMoreAuthors = { viewModel.loadMoreAuthors() },
                onCategorySelectionToggle = { viewModel.toggleCategorySelection(context, it) },
                padding = padding,
                bottomPadding = bottomPadding,
                hasDonated = hasDonated,
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onNavigateToCloudKit = onNavigateToCloudKit
        )
    }

    // Import Sheet
    if (showImportSheet) {
        Dialog(
            onDismissRequest = { showImportSheet = false },
            properties =
                DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = false,
                ),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                ImportBookSheet(
                    annotationManager = annotationManager,
                    onDismiss = { showImportSheet = false },
                    onImportSuccess = { viewModel.reloadData(context) },
                )
            }
        }
    }
}

@Composable
private fun LibraryMoreOptionsMenu(
    viewMode: LibraryViewMode,
    onToggleSelectionMode: () -> Unit,
    onSetViewMode: (LibraryViewMode) -> Unit,
    onImportClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showGroupByMenu by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { showMenu = true }) {
            Icon(
                Icons.Default.MoreHoriz,
                contentDescription = stringResource(R.string.library_action_more_options),
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = {
                showMenu = false
                showGroupByMenu = false
            },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_menu_select)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.CheckCircleOutline,
                        contentDescription = stringResource(R.string.library_action_selection_mode),
                    )
                },
                onClick = {
                    showMenu = false
                    onToggleSelectionMode()
                },
            )
            Box {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_action_group_by)) },
                    leadingIcon = {
                        Icon(
                            imageVector =
                                if (viewMode == LibraryViewMode.CATEGORY) {
                                    Icons.Default.Folder
                                } else {
                                    Icons.Default.Person
                                },
                            contentDescription = stringResource(R.string.library_action_group_by),
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowRight,
                            contentDescription = stringResource(R.string.library_action_arrow_right),
                        )
                    },
                    onClick = { showGroupByMenu = true },
                )
                DropdownMenu(
                    expanded = showGroupByMenu,
                    onDismissRequest = { showGroupByMenu = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_menu_category)) },
                        leadingIcon = { Icon(Icons.Default.Folder, null) },
                        onClick = {
                            showGroupByMenu = false
                            showMenu = false
                            onSetViewMode(LibraryViewMode.CATEGORY)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_menu_author)) },
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                        onClick = {
                            showGroupByMenu = false
                            showMenu = false
                            onSetViewMode(LibraryViewMode.AUTHOR)
                        },
                    )
                }
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_menu_import)) },
                leadingIcon = { Icon(Icons.Outlined.ImportContacts, null) },
                onClick = {
                    showMenu = false
                    onImportClick()
                },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text(stringResource(R.string.history_settings_title)) },
                leadingIcon = { Icon(Icons.Default.Settings, null) },
                onClick = {
                    showMenu = false
                    onSettingsClick()
                },
            )
        }
    }
}

@Composable
private fun LibraryList(
    flatVisibleItems: List<FlatLibraryItem>,
    searchQuery: String,
    showOnlyDownloaded: Boolean,
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
    padding: PaddingValues,
    bottomPadding: androidx.compose.ui.unit.Dp,
    hasDonated: Boolean,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides
            androidx.compose.ui.unit.LayoutDirection.Rtl,
    ) {
        val colors = rememberGroupedListColors()

        val adapter = remember {
            LibraryAdapter(
                isBookDownloadedById = isBookDownloadedById,
                onCategoryToggle = onCategoryToggle,
                onBookClick = onBookClick,
                onBookSelectionToggle = onBookSelectionToggle,
                onLoadMore = onLoadMore,
                onLoadMoreAuthors = onLoadMoreAuthors,
                onCategorySelectionToggle = onCategorySelectionToggle
            ).apply {
                this.viewMode = viewMode
                this.isSelectionMode = isSelectionMode
                this.selectedBookIds = selectedBookIds
                this.expandedCategories = expandedCategories
                this.onlySelectDownloaded = showOnlyDownloaded
                this.primaryColor = colors.primaryColor
                this.secondaryColor = colors.secondaryColor
                this.onSurfaceVariantColor = colors.onSurfaceVariantColor
                this.stateRestorationPolicy = androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
				submitList(flatVisibleItems)
            }
        }

        val donationAdapter = remember {
            object : androidx.recyclerview.widget.ListAdapter<Unit, androidx.recyclerview.widget.RecyclerView.ViewHolder>(
                object : androidx.recyclerview.widget.DiffUtil.ItemCallback<Unit>() {
                    override fun areItemsTheSame(oldItem: Unit, newItem: Unit): Boolean = true
                    override fun areContentsTheSame(oldItem: Unit, newItem: Unit): Boolean = true
                }
            ) {
                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                    val composeView = androidx.compose.ui.platform.ComposeView(parent.context).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(composeView) {}
                }

                override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                    (holder.itemView as androidx.compose.ui.platform.ComposeView).setContent {
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalLayoutDirection provides
                                androidx.compose.ui.unit.LayoutDirection.Rtl,
                        ) {
                            DonationCard()
                        }
                    }
                }
            }.apply {
                submitList(if (hasDonated) emptyList() else listOf(Unit))
            }
        }

        androidx.compose.runtime.LaunchedEffect(hasDonated) {
            donationAdapter.submitList(if (hasDonated) emptyList() else listOf(Unit))
        }

        androidx.compose.runtime.LaunchedEffect(
            viewMode, isSelectionMode, selectedBookIds, expandedCategories, flatVisibleItems,
            colors, showOnlyDownloaded
        ) {
            val viewModeChanged = adapter.viewMode != viewMode
            adapter.submitList(flatVisibleItems) {
                adapter.viewMode = viewMode
                adapter.isSelectionMode = isSelectionMode
                adapter.selectedBookIds = selectedBookIds
                adapter.expandedCategories = expandedCategories
                adapter.onlySelectDownloaded = showOnlyDownloaded
                adapter.primaryColor = colors.primaryColor
                adapter.secondaryColor = colors.secondaryColor
                adapter.onSurfaceVariantColor = colors.onSurfaceVariantColor
                if (viewModeChanged) {
                    adapter.scrollToTop()
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            if (flatVisibleItems.isEmpty() && searchQuery.isEmpty() && !showOnlyDownloaded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else if (flatVisibleItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.library_empty_downloaded)) }
            } else {
                GroupedRecyclerView(
                    recyclerViewId = R.id.library_recycler_view,
                    adapter = androidx.recyclerview.widget.ConcatAdapter(adapter, donationAdapter),
                    padding = padding,
                    bottomContentPadding = bottomPadding,
                    colors = colors,
                    decorationFactory = { rv ->
                        val ctx = rv.context
                        val cornerRadius = 30 * ctx.resources.displayMetrics.density
                        val marginH = 16 * ctx.resources.displayMetrics.density

                        GroupedCardDecoration(
                            cornerRadius = cornerRadius,
                            strokeWidth = 0f,
                            marginHorizontal = marginH
                        ) { position ->
                            val adapterCount = adapter.itemCount
                            if (position !in 0..<adapterCount) return@GroupedCardDecoration null
                            GroupedCardDecoration.GroupInfo(
                                isFirst = position == 0,
                                isLast = position == adapterCount - 1
                            )
                        }
                    }
                )
            }
        }
    }
}
