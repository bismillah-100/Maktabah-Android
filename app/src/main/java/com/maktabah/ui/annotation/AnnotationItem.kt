package com.maktabah.ui.annotation

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.maktabah.R
import com.maktabah.models.Annotation
import com.maktabah.ui.common.InsetGroupedItem
import com.maktabah.ui.common.SwipeDeleteBackground
import com.maktabah.ui.common.isSwipeTargetReached
import com.maktabah.utils.convertToArabicDigits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyItemScope
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LazyItemScope.AnnotationItem(
    ann: Annotation,
    index: Int,
    lastIndex: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    onClick: () -> Unit,
    onDelete: suspend () -> String?, // returns sync result message
    onDeleteComplete: () -> Unit,
    outerPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    dividerStartPadding: Dp = 16.dp,
    enableSwipe: Boolean = true
) {
    val context = LocalContext.current
    var itemWidth by remember { mutableIntStateOf(0) }
    var dismissStateRef by remember { mutableStateOf<SwipeToDismissBoxState?>(null) }
    val dismissScope = rememberCoroutineScope()
    val dismissState =
        rememberSwipeToDismissBoxState(
            positionalThreshold = { it * 0.5f },
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) {
                    dismissStateRef?.isSwipeTargetReached(itemWidth) == true
                } else {
                    true
                }
            },
        )
    dismissStateRef = dismissState

    androidx.compose.runtime.LaunchedEffect(dismissState.targetValue, itemWidth) {
        androidx.compose.runtime.snapshotFlow {
            val offset = try { dismissState.requireOffset() } catch (_: Exception) { 0f }
            dismissState.targetValue to offset
        }.collect { (target, offset) ->
            if (target == SwipeToDismissBoxValue.EndToStart && itemWidth > 0 && kotlin.math.abs(offset) >= itemWidth * 0.95f) {
                onDeleteComplete()
                dismissScope.launch {
                    val syncResult = onDelete()
                    if (syncResult != null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, syncResult, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    val isFirst = index == 0
    val isLast = index == lastIndex
    val shape = when {
        isFirst && isLast -> RoundedCornerShape(30.dp)
        isFirst -> RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
        isLast -> RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp)
        else -> RoundedCornerShape(0.dp)
    }

    val content = @Composable {
        InsetGroupedItem(
            index = index,
            lastIndex = lastIndex,
            onClick = onClick,
            color = color,
            outerPadding = outerPadding,
            contentPadding =
                PaddingValues(
                    horizontal = 16.dp,
                    vertical = 10.dp,
                ),
            dividerStartPadding = dividerStartPadding,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val hexStr = if (ann.colorHex.startsWith("#")) ann.colorHex else "#${ann.colorHex}"
                val parsedColor =
                    try {
                        Color(hexStr.toColorInt())
                    } catch (_: Exception) {
                        Color.Yellow
                    }
                val contextText =
                    buildAnnotatedString {
                        when (ann.type) {
                            0 -> { // 0 is Highlight
                                withStyle(style = SpanStyle(background = parsedColor.copy(alpha = 0.3f))) {
                                    append(ann.context)
                                }
                            }

                            1 -> { // 1 is Underline
                                withStyle(style = SpanStyle(textDecoration = TextDecoration.Underline)) {
                                    append(ann.context)
                                }
                            }

                            else -> {
                                append(ann.context)
                            }
                        }
                    }
                Text(
                    text = contextText,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Rtl),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!ann.note.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = ann.note,
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Rtl),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val pageText =
                            if (ann.part > 0) {
                                stringResource(
                                    R.string.annotation_item_part_page,
                                    ann.part,
                                    ann.page
                                )
                            } else {
                                stringResource(R.string.annotation_item_page, ann.page)
                            }
                        Text(
                            text = pageText.convertToArabicDigits(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (ann.tags.isNotEmpty()) {
                            Text(
                                text = ann.tags.split(",").joinToString(" -- "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (enableSwipe) {
        SwipeToDismissBox(
            modifier = modifier.onSizeChanged { itemWidth = it.width }.animateItem(),
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                SwipeDeleteBackground(
                    dismissState = dismissState,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    shape = shape,
                    revealedWidthOffset = 16.dp,
                    contentDescription = stringResource(R.string.reader_ann_editor_delete),
                    iconSize = 38.dp
                )
            },
            content = { content() }
        )
    } else {
        androidx.compose.foundation.layout.Box(modifier = modifier) {
            content()
        }
    }
}
