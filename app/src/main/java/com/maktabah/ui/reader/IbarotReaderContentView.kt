package com.maktabah.ui.reader

import android.graphics.Typeface
import android.text.Spanned
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.maktabah.R
import com.maktabah.models.Annotation
import com.maktabah.models.FlashTarget
import com.maktabah.ui.common.AnnotationSpan
import com.maktabah.ui.common.ArabicTextRenderer
import com.maktabah.ui.common.drawGenericVerticalScrollbar
import com.maktabah.utils.HONORIFIC_PHRASES
import com.maktabah.utils.findArabicMatchingRanges
import com.maktabah.utils.isArabicHarakat
import com.maktabah.utils.normalizeArabic
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun IbarotReaderContentView(
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel,
    bookId: Int = -1,
    contentId: Int,
    nass: String,
    textSize: Float,
    typeface: Typeface?,
    lateefTypeface: Typeface? = null,
    textColor: Color,
    backgroundColor: Color,
    highlightColor: Color,
    showHarakat: Boolean,
    annotations: List<Annotation>,
    topPadPx: Float,
    botPadPx: Float,
    paddingValues: PaddingValues,
    searchQuery: String?,
    tabManager: ReaderTabManager? = null,
    tabId: String? = null,
    isMultiLanguage: Boolean = false,
    flashTarget: FlashTarget? = null,
    onScrollViewCreated: (NestedScrollView) -> Unit,
    onHighlight: (loc: Int, len: Int, str: String) -> Unit,
    onUnderline: (loc: Int, len: Int, str: String) -> Unit,
    onAddNote: (loc: Int, len: Int, str: String) -> Unit,
    onAnnotationClick: (Annotation) -> Unit,
    onDeleteAnnotation: (Annotation) -> Unit,
    onCopyReference: (String) -> Unit,
) {
    var topOverscroll by remember { mutableFloatStateOf(0f) }
    var botOverscroll by remember { mutableFloatStateOf(0f) }

    val scrollOffset = remember { mutableFloatStateOf(0f) }
    val scrollRange = remember { mutableFloatStateOf(0f) }
    val scrollExtent = remember { mutableFloatStateOf(0f) }
    var isScrollInProgress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scrollJobRef = remember { arrayOf<Job?>(null) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .drawGenericVerticalScrollbar(
                    isScrollInProgress = isScrollInProgress,
                    scrollOffsetProvider = { scrollOffset.floatValue },
                    scrollRangeProvider = { scrollRange.floatValue },
                    scrollExtentProvider = { scrollExtent.floatValue },
                    topPadding = paddingValues.calculateTopPadding(),
                    bottomPadding = paddingValues.calculateBottomPadding()
                )
        ) {
        AndroidView(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    if (topPadPx > 0f) {
                        drawRect(
                            brush =
                                Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    0.8f to Color.Black.copy(alpha = 0.15f),
                                    1.0f to Color.Black,
                                    startY = 0f,
                                    endY = topPadPx,
                                ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    if (botPadPx > 0f) {
                        drawRect(
                            brush =
                                Brush.verticalGradient(
                                    0.0f to Color.Black,
                                    0.2f to Color.Black.copy(alpha = 0.15f),
                                    1.0f to Color.Transparent,
                                    startY = size.height - botPadPx,
                                    endY = size.height,
                                ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                },
        factory = { context ->
            val scrollView =
                object : NestedScrollView(context) {
                    override fun requestChildFocus(child: android.view.View?, focused: android.view.View?) {
                        // Mencegah scroll otomatis saat TextView mendapatkan fokus (tap)
                        if (focused is IbarotTextView) return
                        super.requestChildFocus(child, focused)
                    }

                    override fun requestRectangleOnScreen(rect: android.graphics.Rect?, immediate: Boolean): Boolean {
                        // Mencegah scroll otomatis saat TextView meminta area tertentu ditampilkan (misal cursor saat tap)
                        return false
                    }
                    override fun requestChildRectangleOnScreen(
                        child: android.view.View,
                        rectangle: android.graphics.Rect?,
                        immediate: Boolean
                    ): Boolean {
                        if (child is IbarotTextView) return false
                        return super.requestChildRectangleOnScreen(child, rectangle, immediate)
                    }
                }.apply {
                    isFillViewport = true
                    isVerticalFadingEdgeEnabled = false
                    isVerticalScrollBarEnabled = false
                    clipToPadding = false
                    // Agar ScrollView bisa menerima fokus alih-alih langsung ke child
                    isFocusable = true
                    isFocusableInTouchMode = true
                    descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

                    setOnScrollChangeListener { v, _, scrollY, _, _ ->
                        val sv = v as NestedScrollView
                        val childHeight = sv.getChildAt(0)?.height?.toFloat() ?: 0f
                        scrollOffset.floatValue = scrollY.toFloat()
                        scrollRange.floatValue = childHeight + sv.paddingTop + sv.paddingBottom
                        scrollExtent.floatValue = sv.height.toFloat()
                        if (!isScrollInProgress) {
                            isScrollInProgress = true
                        }
                        scrollJobRef[0]?.cancel()
                        scrollJobRef[0] = scope.launch {
                            delay(350.milliseconds)
                            isScrollInProgress = false
                        }
                    }
                }
            val textView =
                IbarotTextView(context).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    this.textSize = textSize
                    this.typeface = typeface
                    this.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    this.setTextColor(textColor.toArgb())

                    val textColorInt = textColor.toArgb()
                    val bgColorInt = backgroundColor.toArgb()
                    val highlightColorInt = highlightColor.toArgb()

                    this.text =
                        renderContent(
                            nass = nass,
                            bookId = bookId,
                            contentId = contentId,
                            textColor = textColor,
                            backgroundColor = backgroundColor,
                            highlightColor = highlightColorInt,
                            showHarakat = showHarakat,
                            annotations = annotations,
                            searchQuery = searchQuery,
                            isMultiLanguage = isMultiLanguage,
                            typeface = typeface,
                            lateefTypeface = lateefTypeface,
                        )
                    this.isMultiLanguage = isMultiLanguage
                    this.currentTabId = tabId
                    this.contentId = contentId
                    this.lastAnnotations = annotations
                    this.lastSearchQuery = searchQuery
                    this.lastTextColor = textColorInt
                    this.lastBackgroundColor = bgColorInt
                    this.lastHighlightColor = highlightColorInt
                    this.rawNass = nass
                    this.currentShowHarakat = showHarakat

                    this.onHighlight = { loc, len, str, _, _ ->
                        onHighlight(loc, len, str)
                    }
                    this.onUnderline = { loc, len, str, _, _ ->
                        onUnderline(loc, len, str)
                    }
                    this.onAddNote = { loc, len, str, _, _ ->
                        onAddNote(loc, len, str)
                    }
                    this.onAnnotationClick = onAnnotationClick
                    this.onDeleteAnnotation = onDeleteAnnotation
                    this.onCopyReference = onCopyReference
                    this.onNavigateNext = { _ ->
                        viewModel.nextPage()
                    }
                    this.onNavigatePrev = { isContinuous ->
                        this.pendingScrollToBottom = isContinuous
                        viewModel.prevPage()
                    }
                    this.onOverscrollUpdate = { progress, isTop ->
                        if (isTop) topOverscroll = progress else botOverscroll = progress
                    }
                }
            scrollView.addView(textView)
            scrollView.tag = textView

            // Notify ReaderScreen that scrollView is ready
            onScrollViewCreated(scrollView)

            // Restore scroll position secara andal setelah layout selesai diukur
            textView.viewTreeObserver.addOnGlobalLayoutListener(object :
                ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    textView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (tabManager != null && tabId != null) {
                        val savedY = tabManager.getSavedScrollY(tabId)
                        if (savedY > 0) {
                            scrollView.scrollTo(0, savedY)
                        }
                    }
                }
            })
            scrollView
        },
        update = { view ->
            val density = view.context.resources.displayMetrics.density
            val topPad = (paddingValues.calculateTopPadding().value * density).toInt()
            val botPad = (paddingValues.calculateBottomPadding().value * density).toInt()
            view.setPadding(0, topPad, 0, botPad)

            val textView = view.tag as IbarotTextView

            // Deteksi perpindahan tab jika AndroidView digunakan ulang (reused)
            val isTabSwitch = tabId != null && textView.currentTabId != tabId
            if (isTabSwitch) {
                textView.currentTabId = tabId
                // Jika pindah tab, pulihkan scroll dari tabManager
                if (tabManager != null) {
                    val savedY = tabManager.getSavedScrollY(tabId)
                    view.post { view.scrollTo(0, savedY) }
                }
                // Update contentId agar tidak dianggap perubahan halaman (yang memicu scroll ke atas)
                textView.contentId = contentId
            }

            val oldContentId = textView.contentId

            // Update callback agar selalu menggunakan viewModel terbaru
            textView.onNavigateNext = { viewModel.nextPage() }
            textView.onNavigatePrev = { isContinuous ->
                textView.pendingScrollToBottom = isContinuous
                viewModel.prevPage()
            }

            val currentTextColorInt = textColor.toArgb()
            val currentBgColorInt = backgroundColor.toArgb()
            val currentHighlightColorInt = highlightColor.toArgb()

            // Hapus update text yang tidak perlu untuk performa dan mencegah lonjakan layout
            val needsTextUpdate = textView.contentId != contentId ||
                                 textView.currentShowHarakat != showHarakat ||
                                 textView.lastAnnotations != annotations ||
                                 textView.lastSearchQuery != searchQuery ||
                                 textView.lastTextColor != currentTextColorInt ||
                                 textView.lastBackgroundColor != currentBgColorInt ||
                                 textView.lastHighlightColor != currentHighlightColorInt

            if (needsTextUpdate) {
                // Simpan posisi scroll saat ini
                val currentY = view.scrollY
                val hadFocus = textView.isFocused

                textView.text =
                    renderContent(
                        nass = nass,
                        bookId = bookId,
                        contentId = contentId,
                        textColor = textColor,
                        backgroundColor = backgroundColor,
                        highlightColor = currentHighlightColorInt,
                        showHarakat = showHarakat,
                        annotations = annotations,
                        searchQuery = searchQuery,
                        isMultiLanguage = isMultiLanguage,
                        typeface = typeface,
                        lateefTypeface = lateefTypeface,
                    )

                if (hadFocus) textView.requestFocus()

                // Jika halaman tetap sama, pulihkan posisi scroll
                // Jangan lakukan ini jika baru saja pindah tab karena scroll sudah dipulihkan dari tabManager
                if (oldContentId == contentId && oldContentId != -1 && !isTabSwitch) {
                    // Coba restorasi secara sinkron dulu untuk meminimalkan jump
                    view.scrollTo(0, currentY)
                    /* Dan juga post untuk memastikan setelah layout selesai jika teks berubah
                    secara drastis (meskipun jarang saat update anotasi) */
                    view.post {
                        if (view.scrollY != currentY) {
                            view.scrollTo(0, currentY)
                        }
                    }
                }

                textView.lastAnnotations = annotations
                textView.lastSearchQuery = searchQuery
                textView.currentShowHarakat = showHarakat
                textView.lastTextColor = currentTextColorInt
                textView.lastBackgroundColor = currentBgColorInt
                textView.lastHighlightColor = currentHighlightColorInt
            }

            if (!isTabSwitch && oldContentId != -1 && oldContentId != contentId) {
                // Hentikan fling yang sedang berjalan
                val scrollView = view
                scrollView.stopNestedScroll()
                scrollView.scrollTo(scrollView.scrollX, scrollView.scrollY)

                textView.resetTouchState()

                if (textView.pendingScrollToBottom) {
                    textView.pendingScrollToBottom = false
                    textView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            textView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            scrollView.post {
                                scrollView.fullScroll(android.view.View.FOCUS_DOWN)
                            }
                        }
                    })
                } else {
                    scrollView.scrollTo(0, 0)
                }
            }
            textView.contentId = contentId

            textView.textSize = textSize
            textView.typeface = typeface
            textView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            textView.setTextColor(textColor.toArgb())
            textView.rawNass = nass
            textView.isMultiLanguage = isMultiLanguage
            textView.onAnnotationClick = onAnnotationClick
            textView.onDeleteAnnotation = onDeleteAnnotation
            textView.onCopyReference = onCopyReference
            textView.onOverscrollUpdate = { progress, isTop ->
                if (isTop) topOverscroll = progress else botOverscroll = progress
            }

            if (flashTarget != null && (flashTarget.targetContentId == null || flashTarget.targetContentId == contentId)) {
                var targetStart = -1
                var targetEnd = -1

                if (flashTarget.loc != null && flashTarget.len != null) {
                    val spannable = textView.text as? Spanned
                    if (spannable != null) {
                        val spans =
                            spannable.getSpans(
                                0,
                                spannable.length,
                                AnnotationSpan::class.java,
                            )
                        val targetAnn =
                            spans.find {
                                it.annotation.rangeLocation == flashTarget.loc &&
                                    it.annotation.rangeLength == flashTarget.len
                            }
                        if (targetAnn != null) {
                            targetStart = spannable.getSpanStart(targetAnn)
                            targetEnd = spannable.getSpanEnd(targetAnn)
                        }
                    }
                } else if (flashTarget.query != null) {
                    val range = findQueryRange(
                        textView.text,
                        flashTarget.query,
                        onlyParagraphStart = flashTarget.isParagraphStart
                    )
                    if (range != null) {
                        targetStart = range.first
                        targetEnd = range.second
                    }
                }

                if (targetStart != -1 && targetEnd != -1) {
                    textView.flashRange(targetStart, targetEnd)
                    viewModel.clearFlashTarget()
                } else if (flashTarget.targetContentId == contentId) {
                    viewModel.clearFlashTarget()
                }
            }
        },
    )

    if (topOverscroll > 0f) {
        val scale = 0.8f + 0.2f * topOverscroll
        val isActive = topOverscroll >= 1f
        val bgColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        val contentColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = paddingValues.calculateTopPadding() + 16.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = topOverscroll
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .background(bgColor, CircleShape)
                    .padding(4.dp)
            )
            Text(
                text = stringResource(R.string.reader_action_previous),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .background(bgColor, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }

    if (botOverscroll > 0f) {
        val scale = 0.8f + 0.2f * botOverscroll
        val isActive = botOverscroll >= 1f
        val bgColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        val contentColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = paddingValues.calculateBottomPadding() + 16.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = botOverscroll
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.reader_action_next),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .background(bgColor, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .background(bgColor, CircleShape)
                    .padding(4.dp)
            )
        }
    }
    }
}
}

private fun renderContent(
    nass: String,
    bookId: Int,
    contentId: Int,
    textColor: Color,
    backgroundColor: Color,
    highlightColor: Int,
    showHarakat: Boolean,
    annotations: List<Annotation>,
    searchQuery: String?,
    isMultiLanguage: Boolean,
    typeface: Typeface? = null,
    lateefTypeface: Typeface? = null,
): CharSequence {
    // Hitung warna footnote secara solid (opaque) untuk menghindari
    // artefak "sambungan terang" pada font Arab transparan.
    val footnoteColorInt = ColorUtils.compositeColors(
        textColor.copy(alpha = 0.6f).toArgb(),
        backgroundColor.toArgb()
    )

    return ArabicTextRenderer.render(
        text = nass,
        bookId = bookId,
        contentId = contentId,
        highlightColor = highlightColor,
        footnoteColor = footnoteColorInt,
        showHarakat = showHarakat,
        annotations = annotations,
        searchQuery = searchQuery,
        isMultiLanguage = isMultiLanguage,
        typeface = typeface,
        lateefTypeface = lateefTypeface,
    )
}

private fun buildFuzzyQuery(query: String): String {
    val sb = StringBuilder()
    var lastWasSpace = true
    val normalized = query.normalizeArabic()
    for (c in normalized) {
        if (c.isArabicHarakat() || c == '\u0640') continue
        if (Character.isLetterOrDigit(c)) {
            sb.append(c)
            lastWasSpace = false
        } else if (!lastWasSpace) {
            sb.append(' ')
            lastWasSpace = true
        }
    }
    return sb.toString().trim()
}

private fun findQueryRange(
    text: CharSequence,
    query: String,
    onlyParagraphStart: Boolean = false,
): Pair<Int, Int>? {
    val fuzzyQuery = buildFuzzyQuery(query)
    if (fuzzyQuery.isEmpty()) return null

    val renderedStr = text.toString()
    val cleanToOrig = IntArray(renderedStr.length * 2)
    val fuzzyText = StringBuilder()
    var cleanIdx = 0
    var lastWasSpace = true

    for (i in renderedStr.indices) {
        val char = renderedStr[i]
        val expansion = HONORIFIC_PHRASES.find { it.second == char.toString() }?.first
        if (expansion != null) {
            val normalizedExpansion = expansion.normalizeArabic()
            for (c in normalizedExpansion) {
                if (Character.isLetterOrDigit(c)) {
                    if (cleanIdx < cleanToOrig.size) cleanToOrig[cleanIdx++] = i
                    fuzzyText.append(c)
                    lastWasSpace = false
                } else if (!lastWasSpace) {
                    if (cleanIdx < cleanToOrig.size) cleanToOrig[cleanIdx++] = i
                    fuzzyText.append(' ')
                    lastWasSpace = true
                }
            }
        } else {
            if (char.isArabicHarakat() || char == '\u0640') continue
            val normalizedChar = char.toString().normalizeArabic()
            for (c in normalizedChar) {
                if (Character.isLetterOrDigit(c)) {
                    if (cleanIdx < cleanToOrig.size) cleanToOrig[cleanIdx++] = i
                    fuzzyText.append(c)
                    lastWasSpace = false
                } else if (!lastWasSpace) {
                    if (cleanIdx < cleanToOrig.size) cleanToOrig[cleanIdx++] = i
                    fuzzyText.append(' ')
                    lastWasSpace = true
                }
            }
        }
    }

    val fuzzyStr = fuzzyText.toString().trimEnd()

    fun searchMatch(targetQuery: String): Pair<Int, Int>? {
        if (targetQuery.isBlank()) return null
        val ranges = fuzzyStr.findArabicMatchingRanges(listOf(targetQuery))
        if (ranges.isEmpty()) return null

        if (onlyParagraphStart) {
            for (range in ranges) {
                val idx = range.first
                if (idx >= cleanIdx) continue

                val origStart = cleanToOrig[idx]
                var isAtStart = false
                var p = origStart - 1
                while (p >= 0) {
                    val c = renderedStr[p]
                    if (c == '\n' || c == '\r') {
                        isAtStart = true
                        break
                    }
                    if (Character.isLetterOrDigit(c)) {
                        isAtStart = false
                        break
                    }
                    p--
                }
                if (p < 0) isAtStart = true

                if (isAtStart) {
                    val endIdx = range.last
                    val end = if (endIdx < cleanIdx) {
                        cleanToOrig[endIdx] + 1
                    } else {
                        renderedStr.length
                    }
                    return origStart to end
                }
            }
            return null
        } else {
            val range = ranges.first()
            if (range.first >= cleanIdx) return null
            val start = cleanToOrig[range.first]
            val endIdx = range.last
            val end = if (endIdx < cleanIdx) {
                cleanToOrig[endIdx] + 1
            } else {
                renderedStr.length
            }
            return start to end
        }
    }

    val directMatch = searchMatch(fuzzyQuery)
    if (directMatch != null) return directMatch

    if (onlyParagraphStart && fuzzyQuery.contains(" ")) {
        val words = fuzzyQuery.split(" ")
        if (words.size > 2) {
            val prefixQuery = words.take(3).joinToString(" ")
            val prefixMatch = searchMatch(prefixQuery)
            if (prefixMatch != null) return prefixMatch
        }
        if (words.size > 1) {
            val prefixQuery = words.take(2).joinToString(" ")
            val prefixMatch = searchMatch(prefixQuery)
            if (prefixMatch != null) return prefixMatch
        }
    }

    return null
}
