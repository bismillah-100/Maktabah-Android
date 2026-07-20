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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.maktabah.R
import com.maktabah.models.Annotation
import com.maktabah.models.FlashTarget
import com.maktabah.ui.common.AnnotationSpan
import com.maktabah.ui.common.ArabicTextRenderer
import com.maktabah.utils.HONORIFIC_PHRASES
import com.maktabah.utils.isArabicHarakat
import com.maktabah.utils.normalizeArabic

@Composable
fun IbarotReaderContentView(
    modifier: Modifier = Modifier,
    viewModel: ReaderViewModel,
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

    Box(modifier = modifier.fillMaxSize()) {
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
                    clipToPadding = false
                    // Agar ScrollView bisa menerima fokus alih-alih langsung ke child
                    isFocusable = true
                    isFocusableInTouchMode = true
                    descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
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

            val flashTargetValue = flashTarget
            if (flashTargetValue != null) {
                var targetStart = -1
                var targetEnd = -1

                if (flashTargetValue.loc != null && flashTargetValue.len != null) {
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
                                it.annotation.rangeLocation == flashTargetValue.loc &&
                                    it.annotation.rangeLength == flashTargetValue.len
                            }
                        if (targetAnn != null) {
                            targetStart = spannable.getSpanStart(targetAnn)
                            targetEnd = spannable.getSpanEnd(targetAnn)
                        }
                    }
                } else if (flashTargetValue.query != null) {
                    val range = findQueryRange(textView.text, flashTargetValue.query)
                    if (range != null) {
                        targetStart = range.first
                        targetEnd = range.second
                    }
                }

                if (targetStart != -1 && targetEnd != -1) {
                    textView.flashRange(targetStart, targetEnd)
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

private fun renderContent(
    nass: String,
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

private fun findQueryRange(
    text: CharSequence,
    query: String,
): Pair<Int, Int>? {
    val normalizedQuery = query.normalizeArabic()
    if (normalizedQuery.isEmpty()) return null

    val renderedStr = text.toString()
    val cleanToOrig = IntArray(renderedStr.length * 2)
    val cleanText = StringBuilder()
    var cleanIdx = 0

    for (i in renderedStr.indices) {
        val char = renderedStr[i]
        val expansion = HONORIFIC_PHRASES.find { it.second == char.toString() }?.first
        if (expansion != null) {
            val normalizedExpansion = expansion.normalizeArabic()
            for (c in normalizedExpansion) {
                if (cleanIdx < cleanToOrig.size) cleanToOrig[cleanIdx++] = i
                cleanText.append(c)
            }
        } else {
            val normalizedChar = char.toString().normalizeArabic()
            if (normalizedChar.isNotEmpty()) {
                if (cleanIdx < cleanToOrig.size) cleanToOrig[cleanIdx++] = i
                cleanText.append(normalizedChar)
            }
        }
    }

    val idx = cleanText.indexOf(normalizedQuery, ignoreCase = true)

    if (idx != -1 && idx < cleanIdx) {
        val start = cleanToOrig[idx]
        val endIdx = idx + normalizedQuery.length
        val end = if (endIdx < cleanIdx) {
            cleanToOrig[endIdx] + 1
        } else {
            renderedStr.length
        }
        return start to end
    }
    return null
}
