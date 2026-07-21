@file:OptIn(ExperimentalMaterial3Api::class)

package com.maktabah.ui.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.widget.NestedScrollView
import com.maktabah.R
import com.maktabah.database.AnnotationManager
import com.maktabah.models.ActiveAnnotationState
import com.maktabah.ui.history.HistoryViewModel
import com.maktabah.ui.library.LibraryViewModel
import com.maktabah.utils.convertToArabicDigits
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    historyViewModel: HistoryViewModel,
    annotationManager: AnnotationManager,
    libraryViewModel: LibraryViewModel,
    tabManager: ReaderTabManager? = null,
    activeTabId: String? = null,
    onBack: () -> Unit,
    onSyncRequested: () -> Unit = {},
    onSyncHistoryRequested: () -> Unit = {},
) {
    val content by viewModel.currentContent.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val title by viewModel.bookTitle.collectAsState()
    val showHarakat by viewModel.showHarakat.collectAsState()
    val textSize by viewModel.textSize.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val backgroundColorIndex by viewModel.backgroundColorIndex.collectAsState()
    val fontIndex by viewModel.fontIndex.collectAsState()
    val tocList by viewModel.tocList.collectAsState()
    val bookId by viewModel.currentBookIdFlow.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isMultiLanguage by viewModel.isMultiLanguage.collectAsState()
    val flashTarget by viewModel.flashTarget.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.initialize(context, annotationManager, libraryViewModel.dataManager)
    }

    val isInitialized by viewModel.isInitialized.collectAsState()
    val isSystemDark = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode && isInitialized) {
        DisposableEffect(isDarkMode, isSystemDark) {
            var ctx = view.context
            while (ctx is ContextWrapper) {
                if (ctx is Activity) break
                ctx = ctx.baseContext
            }
            val window = (ctx as? Activity)?.window
            if (window != null) {
                val insetsController =
                    WindowCompat
                        .getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDarkMode
                insetsController.isAppearanceLightNavigationBars = !isDarkMode
            }
            onDispose {
                if (window != null) {
                    val insetsController =
                        WindowCompat
                            .getInsetsController(window, view)
                    insetsController.isAppearanceLightStatusBars = !isSystemDark
                    insetsController.isAppearanceLightNavigationBars = !isSystemDark
                }
            }
        }
    }

    var showTOC by remember { mutableStateOf(false) }
    var showAnnotationsSheet by remember { mutableStateOf(false) }
    var showBookSearchSheet by remember { mutableStateOf(false) }
    val expandedTOCNodes = viewModel.tocExpandedNodes
    var showOptions by remember { mutableStateOf(false) }
    var showBookInfo by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showNavigationSheet by remember { mutableStateOf(false) }
    var showTabsList by remember { mutableStateOf(false) }
    val scrollViewRef = remember { mutableStateOf<NestedScrollView?>(null) }

    DisposableEffect(activeTabId) {
        onDispose {
            scrollViewRef.value?.let { view ->
                if (tabManager != null && activeTabId != null) {
                    tabManager.saveScrollY(activeTabId, view.scrollY)
                }
            }
        }
    }

    val tabs by (tabManager?.tabs
        ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()

    // Trigger scale animation when count changes
    var lastTabCount by remember { mutableIntStateOf(tabs.size) }
    var triggerAnimation by remember { mutableStateOf(false) }
    val pulseScale by animateFloatAsState(
        targetValue = if (triggerAnimation) 1.2f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 400f),
        finishedListener = { triggerAnimation = false },
        label = "PulseScale"
    )

    LaunchedEffect(tabs.size) {
        if (tabs.size != lastTabCount) {
            triggerAnimation = true
            lastTabCount = tabs.size
        }
    }

    val backgroundColors =
        listOf(
            Color.White,
            Color(0xFFF4ECD8), // Sepia
            Color(0xFF5B4636), // Dark Sepia
            Color(0xFFE5E5EA), // Gray
            Color.Black,
        )
    val textColors = listOf(Color.Black, Color.Black, Color.White, Color.Black, Color.White)
    val highlightColors =
        listOf(
            Color(0xFF8B0000), // White bg → dark red
            Color(0xFF8B0000), // Sepia → dark red
            Color(0xFFFFB347), // Dark Sepia → amber
            Color(0xFF8B0000), // Gray → dark red
            Color(0xFFFFB347), // Black → amber
        )

    val currentBgColor = backgroundColors[backgroundColorIndex]
    val currentTextColor = textColors[backgroundColorIndex]
    val currentHighlightColor = highlightColors[backgroundColorIndex]

    val customFonts by viewModel.customFonts.collectAsState()

    val builtInFonts =
        listOf(
            R.font.uthman,
            R.font.lateef_regular,
            R.font.scheherazade_new_regular,
            R.font.lateef_bold,
        )

    val currentTypeface =
        remember(fontIndex, customFonts, context) {
            try {
                if (fontIndex < builtInFonts.size) {
                    ResourcesCompat.getFont(context, builtInFonts[fontIndex])
                } else {
                    val customIndex = fontIndex - builtInFonts.size
                    if (customIndex < customFonts.size) {
                        Typeface.createFromFile(customFonts[customIndex])
                    } else {
                        Typeface.DEFAULT
                    }
                }
            } catch (_: Exception) {
                Typeface.DEFAULT
            }
        }

    val lateefTypeface = remember(context) {
        try {
            ResourcesCompat.getFont(context, R.font.lateef_regular)
        } catch (_: Exception) {
            null
        }
    }

    val currentAnnotations by viewModel.currentAnnotations.collectAsState()
    val bookAnnotations by viewModel.bookAnnotations.collectAsState()
    val activeAnnotationForEdit by viewModel.activeAnnotationForEdit.collectAsState()

    fun copyWithReference(selectedText: String) {
        val currentTitle = title
        val currentPart = content?.part ?: 1
        val currentPage = content?.page ?: 1

        val reference =
            "$selectedText\n$currentTitle. ص ${currentPage.toString().convertToArabicDigits()} ∙ ج ${currentPart.toString().convertToArabicDigits()}."

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Maktabah Reference", reference)
        clipboard.setPrimaryClip(clip)
    }

    LaunchedEffect(content?.id, bookId) {
        val cid = content?.id
        if (cid != null && bookId != 0) {
            historyViewModel.updateLastContentId(cid, bookId)

            delay(10.seconds)
            onSyncHistoryRequested()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor =
                            MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                        titleContentColor = currentTextColor,
                        navigationIconContentColor = currentTextColor,
                        actionIconContentColor = currentTextColor,
                    ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.reader_action_back),
                        )
                    }
                },
                actions = {
                    // Tombol jumlah tab — muncul jika ada lebih dari 1 tab terbuka
                    if (tabManager != null && tabs.size > 1) {
                        Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(32.dp)
                                .scale(pulseScale)
                                .background(
                                    color = currentTextColor.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .clickable { showTabsList = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = tabs.size.toString(),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = currentTextColor,
                            )
                        }
                    }
                    IconButton(onClick = { showBookSearchSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.reader_action_search),
                        )
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = stringResource(R.string.reader_action_menu_options),
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            containerColor = MaterialTheme.colorScheme.surface,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reader_menu_book_info)) },
                                onClick = {
                                    showBookInfo = true
                                    showOverflowMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reader_menu_settings)) },
                                onClick = {
                                    showOptions = true
                                    showOverflowMenu = false
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.FormatPaint,
                                        contentDescription = null,
                                    )
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                contentColor = currentTextColor,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.reader_status_part_page,
                            content?.part ?: "-",
                            content?.page ?: "-"
                        ).convertToArabicDigits(),
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clickable { showNavigationSheet = true }
                            .padding(vertical = 8.dp),
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { showAnnotationsSheet = true }) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_quote_closing),
                                contentDescription = stringResource(R.string.reader_action_annotations),
                            )
                        }
                        IconButton(onClick = { showTOC = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = stringResource(R.string.reader_action_toc),
                            )
                        }
                        IconButton(onClick = { viewModel.nextPage() }, enabled = !isLoading) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = stringResource(R.string.reader_action_next),
                            )
                        }
                        IconButton(onClick = { viewModel.prevPage() }, enabled = !isLoading) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = stringResource(R.string.reader_action_previous),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(currentBgColor)
        ) {
            if (content != null) {
                val topPadPx =
                    with(LocalDensity.current) {
                        padding.calculateTopPadding().toPx()
                    }
                val botPadPx =
                    with(LocalDensity.current) {
                        padding.calculateBottomPadding().toPx()
                    }

                IbarotReaderContentView(
                    viewModel = viewModel,
                    bookId = bookId,
                    contentId = content!!.id,
                    nass = content!!.nass,
                    textSize = textSize,
                    typeface = currentTypeface,
                    lateefTypeface = lateefTypeface,
                    textColor = currentTextColor,
                    backgroundColor = currentBgColor,
                    highlightColor = currentHighlightColor,
                    showHarakat = showHarakat,
                    annotations = currentAnnotations,
                    topPadPx = topPadPx,
                    botPadPx = botPadPx,
                    paddingValues = padding,
                    tabManager = tabManager,
                    tabId = activeTabId,
                    isMultiLanguage = isMultiLanguage,
                    flashTarget = flashTarget,
                    onScrollViewCreated = { scrollViewRef.value = it },
                    onHighlight = { loc, len, str ->
                        viewModel.addAnnotationDirectly(
                            loc = loc,
                            len = len,
                            selectedText = str,
                            rawNass = content?.nass ?: "",
                            type = 0,
                            colorHex = "#FFFF00",
                            onComplete = {
                                onSyncRequested()
                            }
                        )
                    },
                    onUnderline = { loc, len, str ->
                        viewModel.addAnnotationDirectly(
                            loc = loc,
                            len = len,
                            selectedText = str,
                            rawNass = content?.nass ?: "",
                            type = 1,
                            colorHex = "#000000",
                            onComplete = {
                                onSyncRequested()
                            }
                        )
                    },
                    onAddNote = { loc, len, str ->
                        viewModel.showAnnotationEditor(
                            ActiveAnnotationState(
                                annotation = null,
                                loc = loc,
                                len = len,
                                selectedText = str,
                                type = 0,
                                colorHex = "#00AA00",
                            ),
                        )
                    },
                    onAnnotationClick = { ann ->
                        viewModel.showAnnotationEditor(
                            ActiveAnnotationState(
                                annotation = ann,
                                type = ann.type,
                                colorHex = ann.colorHex,
                            ),
                        )
                    },
                    onDeleteAnnotation = { ann ->
                        viewModel.deleteAnnotationDirectly(ann) {
                            onSyncRequested()
                        }
                    },
                    onCopyReference = { selectedText ->
                        copyWithReference(selectedText)
                    },
                    searchQuery = searchQuery,
                )
            } else if (!isLoading) {
                Text(
                    text = stringResource(R.string.reader_empty_content),
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(currentBgColor.copy(alpha = 0.3f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (showBookInfo) {
        BookInfoSheet(
            bookId = bookId,
            defaultTitle = title,
            libraryViewModel = libraryViewModel,
            onDismissRequest = { showBookInfo = false },
        )
    }

    if (showTOC) {
        BookTOCSheet(
            tocList = tocList,
            expandedTOCNodes = expandedTOCNodes,
            viewModel = viewModel,
            onDismissRequest = { showTOC = false },
        )
    }

    if (showAnnotationsSheet) {
        BookAnnotationsSheet(
            bookAnnotations = bookAnnotations,
            annotationManager = annotationManager,
            viewModel = viewModel,
            onDismissRequest = { showAnnotationsSheet = false },
        )
    }

    if (showOptions) {
        ReaderOptionsSheet(
            textSize = textSize,
            showHarakat = showHarakat,
            backgroundColorIndex = backgroundColorIndex,
            fontIndex = fontIndex,
            viewModel = viewModel,
            onDismissRequest = { showOptions = false },
        )
    }

    if (activeAnnotationForEdit != null) {
        AnnotationEditorDialog(
            active = activeAnnotationForEdit!!,
            bookId = bookId,
            content = content,
            showHarakat = showHarakat,
            annotationManager = annotationManager,
            scope = scope,
            onSyncRequested = onSyncRequested,
            onDismissRequest = { viewModel.showAnnotationEditor(null) },
        )
    }

    if (showBookSearchSheet) {
        BookSearchSheet(
            bookId = bookId,
            libraryViewModel = libraryViewModel,
            viewModel = viewModel,
            tabId = activeTabId ?: "",
            onDismissRequest = { showBookSearchSheet = false },
        )
    }

    if (showNavigationSheet) {
        BookNavigationSheet(
            viewModel = viewModel,
            onDismissRequest = { showNavigationSheet = false },
        )
    }

    if (showTabsList && tabManager != null) {
        ReaderTabsListSheet(
            tabs = tabs,
            activeTabId = activeTabId,
            onSwitch = { tabManager.switchTab(it) },
            onClose = { tabManager.closeTab(it) },
            onDismiss = { showTabsList = false },
        )
    }
}
