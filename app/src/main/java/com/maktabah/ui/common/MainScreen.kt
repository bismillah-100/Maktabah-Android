@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.maktabah.ui.common

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.maktabah.R
import com.maktabah.cloudKit.CloudKitSyncManager
import com.maktabah.database.AnnotationManager
import com.maktabah.models.FlashTarget
import com.maktabah.ui.annotation.AnnotationsScreen
import com.maktabah.ui.annotation.AnnotationsViewModel
import com.maktabah.ui.cloudKit.CloudKitAuthScreen
import com.maktabah.ui.history.HistoryScreen
import com.maktabah.ui.history.HistoryViewModel
import com.maktabah.ui.library.LibraryScreen
import com.maktabah.ui.library.LibraryViewModel
import com.maktabah.ui.reader.ReaderScreen
import com.maktabah.ui.reader.ReaderTabManager
import com.maktabah.ui.search.SearchScreen
import com.maktabah.ui.search.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab(
    val titleRes: Int,
    val route: String,
) {
    Library(R.string.app_name, "library"),
    Search(R.string.reader_action_search, "search"),
    Annotations(R.string.annotations_title, "annotations"),
    History(R.string.history_title, "history");

    @Composable
    fun getIcon(): ImageVector = when (this) {
        Library -> ImageVector.vectorResource(R.drawable.books_vertical_fill)
        Search -> ImageVector.vectorResource(R.drawable.magnifying_glass)
        Annotations -> ImageVector.vectorResource(R.drawable.ic_quote_closing)
        History -> ImageVector.vectorResource(R.drawable.clock_fill)
    }
}

@Composable
fun MainScreen(
    libraryViewModel: LibraryViewModel,
    annotationManager: AnnotationManager,
    cloudKitSyncManager: CloudKitSyncManager,
) {
    val isDataLoaded by libraryViewModel.isDataLoaded.collectAsState()
    var hasLoadedInitially by remember { mutableStateOf(false) }

    if (isDataLoaded) {
        hasLoadedInitially = true
    }

    if (!hasLoadedInitially) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }

    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val context = LocalContext.current
    val prefs =
        remember {
            context.getSharedPreferences(
                "main_prefs",
                android.content.Context.MODE_PRIVATE,
            )
        }
    val lastTabRoute =
        remember { prefs.getString("last_tab_route", Tab.Library.route) ?: Tab.Library.route }
    var hasDonated by remember { mutableStateOf(prefs.getBoolean("has_donated", false)) }
    var showBookNotFoundPopover by remember { mutableStateOf(false) }

    DisposableEffect(prefs) {
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "has_donated") {
                    hasDonated = prefs.getBoolean("has_donated", false)
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val historyViewModel: HistoryViewModel =
        androidx.lifecycle.viewmodel.compose
            .viewModel()
    val annotationsViewModel: AnnotationsViewModel =
        androidx.lifecycle.viewmodel.compose
            .viewModel()
    LaunchedEffect(Unit) {
        historyViewModel.initialize(context)
        annotationsViewModel.initialize(context, annotationManager, libraryViewModel.dataManager)

        scope.launch {
            HistoryViewModel.refreshFlow.collect {
                historyViewModel.initialize(context)
            }
        }

    }

    val currentRouteState = rememberUpdatedState(currentRoute)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var syncJob: kotlinx.coroutines.Job? = null
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                syncJob?.cancel()
                syncJob = scope.launch {
                    // Wait until currentRoute becomes a valid main screen route
                    snapshotFlow { currentRouteState.value }
                        .first { route -> route != null && Tab.entries.any { it.route == route } }

                    val ckPrefs = context.getSharedPreferences(
                        "MaktabahPrefs",
                        android.content.Context.MODE_PRIVATE
                    )
                    if (ckPrefs.getString("ckWebAuthToken", null) != null) {
                        cloudKitSyncManager.checkAccountChangeAndSync(
                            context,
                            annotationManager,
                            historyViewModel,
                        )
                        cloudKitSyncManager.fetchChanges(
                            context,
                            annotationManager,
                            historyViewModel,
                        )
                    }
                }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                syncJob?.cancel()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            syncJob?.cancel()
        }
    }

    val tabManager: ReaderTabManager =
        androidx.lifecycle.viewmodel.compose
            .viewModel()

    // Save last tab when it changes
    LaunchedEffect(currentRoute) {
        if (currentRoute != null && Tab.entries.any { it.route == currentRoute }) {
            prefs.edit { putString("last_tab_route", currentRoute) }
        }
    }

    val handleNavigateToReader: (Int, Int?, Int?, Int?, String?) -> Unit =
        remember(
            libraryViewModel,
            tabManager,
            navController,
            context,
            historyViewModel,
            annotationManager
        ) {
            { bookId, contentId, loc, len, query ->
                val book = libraryViewModel.dataManager.booksById[bookId]
                if (book == null) {
                    showBookNotFoundPopover = true
                } else {
                    if (libraryViewModel.isBookDownloadedById(bookId)) {
                        val archivePath =
                            java.io.File(context.filesDir, "${book.archive}.sqlite").absolutePath
                        val flashTarget =
                            when {
                                loc != null && len != null -> FlashTarget(loc = loc, len = len)
                                query != null -> FlashTarget(query = query)
                                else -> null
                            }
                        val isInReader = navController.currentDestination?.route == "reader_tabs"
                        val tab =
                            tabManager.openTab(
                                book = book,
                                archivePath = archivePath,
                                initialContentId = contentId,
                                flashTarget = flashTarget,
                                searchQuery = query,
                                setActive = !isInReader, // Don't switch if already in reader
                            )
                        // Initialize ViewModel jika belum
                        tab.viewModel.initialize(
                            context,
                            annotationManager,
                            libraryViewModel.dataManager,
                        )
                        if (tab.viewModel.currentBookIdFlow.value != bookId) {
                            val finalContentId =
                                contentId
                                    ?: historyViewModel.entriesByBookId.value[bookId]?.lastContentId
                            tab.viewModel.loadBook(bookId, archivePath, book.name, finalContentId)
                            tab.viewModel.setSearchQuery(query)
                        }
                        // Navigate ke reader_tabs hanya jika belum di sana
                        if (navController.currentDestination?.route != "reader_tabs") {
                            navController.navigate("reader_tabs") {
                                launchSingleTop = true
                                // Simpan state tab sebelumnya agar bisa di-restore saat kembali
                                restoreState = true
                            }
                        }
                    } else {
                        libraryViewModel.showDownloadConfirmation(
                            context,
                            bookId,
                            contentId,
                            loc,
                            len,
                            query,
                        )
                    }
                }
            }
        }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            MainBottomBar(currentRoute, navController)
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            AppNavHost(
                navController = navController,
                startDestination = lastTabRoute,
                innerPadding = innerPadding,
                libraryViewModel = libraryViewModel,
                annotationManager = annotationManager,
                cloudKitSyncManager = cloudKitSyncManager,
                historyViewModel = historyViewModel,
                annotationsViewModel = annotationsViewModel,
                tabManager = tabManager,
                handleNavigateToReader = handleNavigateToReader,
                hasDonated = hasDonated,
            )

            BookNotFoundPopover(
                visible = showBookNotFoundPopover,
                onDismiss = { showBookNotFoundPopover = false },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = innerPadding.calculateBottomPadding() + 16.dp),
            )

            val downloadedBookIds by libraryViewModel.downloadedBookIds.collectAsState()
            val activeDownloadStates by libraryViewModel.activeDownloadStates.collectAsState()
            val showOverlay = activeDownloadStates.any {
                if (it.isBulk) it.bulkBookIds.isNotEmpty() else !downloadedBookIds.contains(it.bookId)
            }
            val allowedOverlayRoutes =
                listOf(Tab.Library.route, Tab.Annotations.route, Tab.History.route)
            val isAllowedRoute = currentRoute in allowedOverlayRoutes

            if (showOverlay && isAllowedRoute) {
                BookDownloadOverlay(
                    viewModel = libraryViewModel,
                    bottomPadding = innerPadding.calculateBottomPadding(),
                    onNavigateToReader = handleNavigateToReader,
                )
            }
        }
    }
}

@Composable
private fun MainBottomBar(
    currentRoute: String?,
    navController: NavController,
) {
    val isCloudKitLogin = currentRoute == "cloudkit_login"
    val isReaderScreen = currentRoute == "reader_tabs"

    if (isCloudKitLogin || isReaderScreen) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 32.dp,
                end = 32.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
    ) {
        Surface(
            shape = CircleShape,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Tab.entries.forEach { tab ->
                    val title = stringResource(tab.titleRes)
                    val selected = currentRoute == tab.route

                    CustomTabItem(
                        icon = tab.getIcon(),
                        label = title,
                        selected = selected,
                        // 3. Tab mendapatkan proporsi weight murni, sehingga lebar dan titik tengahnya 100% identik
                        modifier = Modifier.weight(1f),
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomTabItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    overlap: Dp = 4.dp,
    onClick: () -> Unit,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .fillMaxHeight()
            .drawBehind {
                if (selected) {
                    val overlapPx = overlap.toPx()
                    val verticalPaddingPx = 4.dp.toPx() // Gap atas & bawah yang pasti sama
                    val highlightHeight = size.height - (verticalPaddingPx * 2)

                    drawRoundRect(
                        color = activeColor.copy(alpha = 0.15f),
                        topLeft = Offset(x = -overlapPx, y = verticalPaddingPx),
                        size = Size(
                            width = size.width + (overlapPx * 2),
                            height = highlightHeight
                        ),
                        cornerRadius = CornerRadius(highlightHeight / 2, highlightHeight / 2)
                    )
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) activeColor else inactiveColor,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) activeColor else inactiveColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                style = LocalTextStyle.current.copy(
                    platformStyle = PlatformTextStyle(
                        includeFontPadding = false
                    ),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                )
            )
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    innerPadding: PaddingValues,
    libraryViewModel: LibraryViewModel,
    annotationManager: AnnotationManager,
    cloudKitSyncManager: CloudKitSyncManager,
    historyViewModel: HistoryViewModel,
    annotationsViewModel: AnnotationsViewModel,
    tabManager: ReaderTabManager,
    handleNavigateToReader: (Int, Int?, Int?, Int?, String?) -> Unit,
    hasDonated: Boolean,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = Modifier.fillMaxSize(),
        enterTransition = {
            val initial = initialState.destination.route ?: ""
            val target = targetState.destination.route ?: ""
            val tabRoutes = Tab.entries.map { it.route }

            if (initial in tabRoutes && target in tabRoutes) {
                androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(350)
                )
            } else {
                slideIntoContainer(
                    androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Start,
                    androidx.compose.animation.core.tween(300),
                )
            }
        },
        exitTransition = {
            val initial = initialState.destination.route ?: ""
            val target = targetState.destination.route ?: ""
            val tabRoutes = Tab.entries.map { it.route }

            if (initial in tabRoutes && target in tabRoutes) {
                androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(350)
                )
            } else {
                androidx.compose.animation.slideOutHorizontally(
                    targetOffsetX = { -it / 3 },
                    animationSpec = androidx.compose.animation.core.tween(300),
                ) + androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(300),
                )
            }
        },
        popEnterTransition = {
            val initial = initialState.destination.route ?: ""
            val target = targetState.destination.route ?: ""
            val tabRoutes = Tab.entries.map { it.route }

            if (initial in tabRoutes && target in tabRoutes) {
                androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(350)
                )
            } else {
                androidx.compose.animation.slideInHorizontally(
                    initialOffsetX = { -it / 3 },
                    animationSpec = androidx.compose.animation.core.tween(300),
                ) + androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(300),
                )
            }
        },
        popExitTransition = {
            val initial = initialState.destination.route ?: ""
            val target = targetState.destination.route ?: ""
            val tabRoutes = Tab.entries.map { it.route }

            if (initial in tabRoutes && target in tabRoutes) {
                androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(350)
                )
            } else {
                slideOutOfContainer(
                    androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.End,
                    androidx.compose.animation.core.tween(300),
                )
            }
        },
    ) {
        composable(Tab.Library.route) {
            LibraryScreen(
                viewModel = libraryViewModel,
                annotationManager = annotationManager,
                tabManager = tabManager,
                onNavigateToReader = handleNavigateToReader,
                onNavigateToCloudKit = { navController.navigate("cloudkit_login") },
                bottomPadding = innerPadding.calculateBottomPadding(),
                hasDonated = hasDonated,
            )
        }
        composable(Tab.Search.route) {
            val searchViewModel: SearchViewModel =
                androidx.lifecycle.viewmodel.compose
                    .viewModel()
            SearchScreen(
                viewModel = searchViewModel,
                libraryViewModel = libraryViewModel,
                bottomPadding = innerPadding.calculateBottomPadding(),
                onNavigateToReader = handleNavigateToReader,
                hasDonated = hasDonated,
            )
        }
        composable(Tab.Annotations.route) {
            AnnotationsScreen(
                libraryViewModel,
                annotationManager,
                cloudKitSyncManager,
                historyViewModel,
                annotationsViewModel,
                innerPadding.calculateBottomPadding(),
                onNavigateToReader = handleNavigateToReader,
                hasDonated = hasDonated,
            )
        }
        composable(Tab.History.route) {
            HistoryScreen(
                historyViewModel = historyViewModel,
                libraryViewModel = libraryViewModel,
                annotationManager = annotationManager,
                cloudKitSyncManager = cloudKitSyncManager,
                bottomPadding = innerPadding.calculateBottomPadding(),
                onNavigateToReader = handleNavigateToReader,
                hasDonated = hasDonated,
            )
        }
        composable("cloudkit_login") {
            CloudKitAuthScreen(
                containerId = com.maktabah.BuildConfig.CLOUDKIT_CONTAINER_ID,
                apiToken = com.maktabah.BuildConfig.CLOUDKIT_TOKEN,
                onTokenReceived = { _ ->
                    scope.launch {
                        cloudKitSyncManager.checkAccountChangeAndSync(
                            context,
                            annotationManager,
                            historyViewModel,
                        )
                        cloudKitSyncManager.fetchChanges(
                            context,
                            annotationManager,
                            historyViewModel,
                        )
                    }
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(route = "reader_tabs") {
            val activeTabId by tabManager.activeTabId.collectAsState()
            val activeTab = tabManager.activeTab

            if (activeTab != null) {
                LaunchedEffect(activeTab.bookId) {
                    historyViewModel.initialize(context)
                    historyViewModel.addBookToHistory(activeTab.bookId)
                    scope.launch {
                        val entry = historyViewModel.entriesByBookId.value[activeTab.bookId]
                        if (entry != null) {
                            cloudKitSyncManager.syncHistoryAndFavorites(
                                context,
                                listOf(entry),
                            )
                        }
                    }
                }

                androidx.compose.runtime.key(activeTab.id) {
                    ReaderScreen(
                        viewModel = activeTab.viewModel,
                        historyViewModel = historyViewModel,
                        annotationManager = annotationManager,
                        libraryViewModel = libraryViewModel,
                        tabManager = tabManager,
                        activeTabId = activeTabId,
                        onBack = {
                            navController.popBackStack()
                        },
                        onSyncRequested = {
                            scope.launch {
                                val syncResult =
                                    cloudKitSyncManager.syncAnnotations(
                                        context,
                                        annotationManager,
                                    )
                                withContext(Dispatchers.Main) {
                                    if (syncResult != null) {
                                        android.widget.Toast
                                            .makeText(
                                                context,
                                                syncResult,
                                                android.widget.Toast.LENGTH_LONG,
                                            ).show()
                                    }
                                }
                            }
                        },
                        onSyncHistoryRequested = {
                            scope.launch {
                                val entry =
                                    historyViewModel.entriesByBookId.value[activeTab.bookId]
                                if (entry != null) {
                                    cloudKitSyncManager.syncHistoryAndFavorites(
                                        context,
                                        listOf(entry),
                                    )
                                }
                            }
                        },
                    )
                }
            } else {
                LaunchedEffect(Unit) {
                    navController.popBackStack()
                }
            }
        }
    }
}
