package com.maktabah.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.NestedScrollView
import com.maktabah.R
import com.maktabah.models.Annotation
import com.maktabah.ui.common.AnnotationSpan
import kotlin.math.abs

class IbarotTextView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    var onHighlight:
        ((loc: Int, len: Int, str: String, rawNass: String, showHarakat: Boolean) -> Unit)? =
        null
    var onUnderline:
        ((loc: Int, len: Int, str: String, rawNass: String, showHarakat: Boolean) -> Unit)? =
        null
    var onAddNote:
        ((loc: Int, len: Int, str: String, rawNass: String, showHarakat: Boolean) -> Unit)? =
        null
    var onAnnotationClick: ((Annotation) -> Unit)? = null
    var onDeleteAnnotation: ((Annotation) -> Unit)? = null
    var onCopyReference: ((String) -> Unit)? = null
    var onNavigateNext: ((isContinuous: Boolean) -> Unit)? = null
    var onNavigatePrev: ((isContinuous: Boolean) -> Unit)? = null
    var onOverscrollUpdate: ((progress: Float, isTop: Boolean) -> Unit)? = null

    /** Teks mentah (berharakat) untuk kalkulasi range diacritics. */
    var rawNass: String = ""

    /** Mode harakat saat ini, dibutuhkan callback. */
    var currentShowHarakat: Boolean = true

    var currentTabId: String? = null
    var contentId: Int = -1
    var lastAnnotations: List<Annotation>? = null
    var lastSearchQuery: String? = null
    var lastTextColor: Int = 0
    var lastBackgroundColor: Int = 0
    var lastHighlightColor: Int = 0
    var pendingScrollToBottom: Boolean = false
    var isMultiLanguage: Boolean = false
        set(value) {
            field = value
            if (value) {
                textDirection = TEXT_DIRECTION_FIRST_STRONG
            } else {
                textDirection = TEXT_DIRECTION_RTL
            }
        }

    private var touchStartY = 0f
    private var isTrackingTouch = false
    private var hasTriggeredHaptic = false
    private var canTriggerTopOverscroll = false
    private var canTriggerBottomOverscroll = false
    private var isInternalCancel = false
    private val pullThreshold: Float
        get() = 120f * context.resources.displayMetrics.density

    fun resetTouchState() {
        isTrackingTouch = false
        hasTriggeredHaptic = false
        touchStartY = 0f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val scrollView = parent as? NestedScrollView
        scrollView?.setOnTouchListener { _, event ->
            if (isInternalCancel) return@setOnTouchListener false
            handleScrollTouch(scrollView, event)
            false
        }
    }

    private fun handleScrollTouch(
        scrollView: NestedScrollView,
        event: MotionEvent
    ) {
        if (isInternalCancel) return
        
        val canScrollUp = scrollView.canScrollVertically(-1)
        val canScrollDown = scrollView.canScrollVertically(1)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartY = event.y
                hasTriggeredHaptic = false
                isTrackingTouch = true
                canTriggerTopOverscroll = !canScrollUp
                canTriggerBottomOverscroll = !canScrollDown
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTrackingTouch) {
                    touchStartY = event.y
                    hasTriggeredHaptic = false
                    isTrackingTouch = true
                    canTriggerTopOverscroll = !canScrollUp
                    canTriggerBottomOverscroll = !canScrollDown
                }
                
                val deltaY = event.y - touchStartY

                if (deltaY > 0 && canScrollUp) {
                    touchStartY = event.y
                    hasTriggeredHaptic = false
                } else if (deltaY < 0 && canScrollDown) {
                    touchStartY = event.y
                    hasTriggeredHaptic = false
                }

                val currentOverscroll = event.y - touchStartY

                if (!hasTriggeredHaptic) {
                    if ((!canScrollUp && currentOverscroll > pullThreshold && canTriggerTopOverscroll) ||
                        (!canScrollDown && currentOverscroll < -pullThreshold && canTriggerBottomOverscroll)
                    ) {
                        hasTriggeredHaptic = true
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                } else {
                    if (abs(currentOverscroll) <= pullThreshold) {
                        hasTriggeredHaptic = false
                    }
                }

                if (!canScrollUp && currentOverscroll > 0 && canTriggerTopOverscroll) {
                    val progress = (currentOverscroll / pullThreshold).coerceIn(0f, 1f)
                    onOverscrollUpdate?.invoke(progress, true)
                } else if (!canScrollDown && currentOverscroll < 0 && canTriggerBottomOverscroll) {
                    val progress = (-currentOverscroll / pullThreshold).coerceIn(0f, 1f)
                    onOverscrollUpdate?.invoke(progress, false)
                } else {
                    onOverscrollUpdate?.invoke(0f, true)
                    onOverscrollUpdate?.invoke(0f, false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val currentOverscroll = event.y - touchStartY
                if (hasTriggeredHaptic && event.actionMasked == MotionEvent.ACTION_UP) {
                    if (!canScrollUp && currentOverscroll > pullThreshold && canTriggerTopOverscroll) {
                        triggerCancelEvent(scrollView)
                        onNavigatePrev?.invoke(true)
                    } else if (!canScrollDown && currentOverscroll < -pullThreshold && canTriggerBottomOverscroll) {
                        triggerCancelEvent(scrollView)
                        onNavigateNext?.invoke(true)
                    }
                }
                hasTriggeredHaptic = false
                isTrackingTouch = false
                onOverscrollUpdate?.invoke(0f, true)
                onOverscrollUpdate?.invoke(0f, false)
            }
        }
    }

    private fun triggerCancelEvent(scrollView: NestedScrollView) {
        isInternalCancel = true
        val cancelEvent = MotionEvent.obtain(
            android.os.SystemClock.uptimeMillis(),
            android.os.SystemClock.uptimeMillis(),
            MotionEvent.ACTION_CANCEL,
            0f, 0f, 0
        )
        scrollView.dispatchTouchEvent(cancelEvent)
        cancelEvent.recycle()
        isInternalCancel = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            if (selectionStart == selectionEnd) {
                val x = event.x.toInt() - totalPaddingLeft + scrollX
                val y = event.y.toInt() - totalPaddingTop + scrollY
                val layout = layout
                if (layout != null) {
                    val line = layout.getLineForVertical(y)
                    if (line >= 0) {
                        val off = layout.getOffsetForHorizontal(line, x.toFloat())
                        val spannable = text as? Spanned
                        if (off >= 0 && spannable != null && off < spannable.length) {
                            val spans = spannable.getSpans(off, off, AnnotationSpan::class.java)
                            if (spans != null && spans.isNotEmpty()) {
                                onAnnotationClick?.invoke(spans[0].annotation)
                                return true
                            }
                        }
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    fun flashRange(
        start: Int,
        end: Int,
    ) {
        val spannable = text as? Spannable ?: return
        if (start < 0 || end > spannable.length || start >= end) return

        val flashSpan =
            BackgroundColorSpan(Color.argb(235, 255, 213, 79))

        val flashCount = 3
        val flashDuration = 150L

        // Jalankan loop untuk membuat jadwal kapan span dipasang dan dilepas
        for (i in 0 until flashCount) {
            // Jadwal pasang span (kedipan mulai)
            postDelayed({
                spannable.setSpan(
                    flashSpan,
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }, i * 2 * flashDuration)

            // Jadwal lepas span (kedipan selesai)
            postDelayed({
                spannable.removeSpan(flashSpan)
            }, (i * 2 + 1) * flashDuration)
        }

        // Gunakan fungsi scroll yang lebih tangguh
        scrollToOffset(start)
    }

    private fun scrollToOffset(offset: Int) {
        post {
            val layout = layout
            if (layout != null) {
                val line = layout.getLineForOffset(offset)
                val y = layout.getLineTop(line)
                val scrollView = parent as? NestedScrollView
                scrollView?.smoothScrollTo(0, y - 100)
            } else {
                // Jika layout belum siap, coba lagi nanti
                scrollToOffset(offset)
            }
        }
    }

    init {
        setTextIsSelectable(true)
        textSize = 24f
        setPadding(32, 32, 32, 32)
        layoutDirection = LAYOUT_DIRECTION_RTL
        textDirection = TEXT_DIRECTION_RTL
        textAlignment = TEXT_ALIGNMENT_GRAVITY
        gravity = Gravity.TOP or Gravity.START

        customSelectionActionModeCallback =
            object : ActionMode.Callback {
                private var existingAnnotation: Annotation? = null

                override fun onCreateActionMode(
                    mode: ActionMode?,
                    menu: Menu?,
                ): Boolean {
                    val start = selectionStart
                    val end = selectionEnd
                    existingAnnotation = null

                    if (start >= 0 && end >= 0 && start != end) {
                        val spannable = text as? Spanned
                        if (spannable != null) {
                            val spans = spannable.getSpans(start, end, AnnotationSpan::class.java)
                            if (spans != null && spans.isNotEmpty()) {
                                // Find the best match or just the first one
                                existingAnnotation = spans[0].annotation
                            }
                        }
                    }

                    if (existingAnnotation != null) {
                        menu?.add(0, 1004, 0, context.getString(R.string.reader_context_edit_note))
                        menu?.add(0, 1005, 1, context.getString(R.string.reader_context_delete))
                        menu?.add(0, 1006, 2, context.getString(R.string.reader_context_copy_reference))
                    } else {
                        menu?.add(0, 1001, 0, context.getString(R.string.reader_context_highlight))
                        menu?.add(0, 1002, 1, context.getString(R.string.reader_context_underline))
                        menu?.add(0, 1003, 2, context.getString(R.string.reader_context_note))
                        menu?.add(0, 1006, 3, context.getString(R.string.reader_context_copy_reference))
                    }
                    return true
                }

                override fun onPrepareActionMode(
                    mode: ActionMode?,
                    menu: Menu?,
                ): Boolean = false

                override fun onActionItemClicked(
                    mode: ActionMode?,
                    item: MenuItem?,
                ): Boolean {
                    val start = selectionStart
                    val end = selectionEnd
                    if (start >= 0 && end >= 0 && start != end) {
                        val selectedText = text.substring(start, end)
                        when (item?.itemId) {
                            1001 -> {
                                onHighlight?.invoke(
                                    start,
                                    end - start,
                                    selectedText,
                                    rawNass,
                                    currentShowHarakat,
                                )
                                mode?.finish()
                                return true
                            }

                            1002 -> {
                                onUnderline?.invoke(
                                    start,
                                    end - start,
                                    selectedText,
                                    rawNass,
                                    currentShowHarakat,
                                )
                                mode?.finish()
                                return true
                            }

                            1003 -> {
                                onAddNote?.invoke(
                                    start,
                                    end - start,
                                    selectedText,
                                    rawNass,
                                    currentShowHarakat,
                                )
                                mode?.finish()
                                return true
                            }

                            1004 -> {
                                existingAnnotation?.let {
                                    onAnnotationClick?.invoke(it)
                                }
                                mode?.finish()
                                return true
                            }

                            1005 -> {
                                existingAnnotation?.let {
                                    onDeleteAnnotation?.invoke(it)
                                }
                                mode?.finish()
                                return true
                            }

                            1006 -> {
                                onCopyReference?.invoke(selectedText)
                                mode?.finish()
                                return true
                            }
                        }
                    }
                    return false
                }

                override fun onDestroyActionMode(mode: ActionMode?) {
                    existingAnnotation = null
                }
            }
    }
}
