package com.maktabah.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.models.SearchMode
import com.maktabah.utils.filterRangesForNearMode
import com.maktabah.utils.findArabicMatchingRanges
import com.maktabah.utils.findArabicMatchingRangesWithIndex
import com.maktabah.utils.startsWithArabic

@Composable
fun QueryInputBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    canSearch: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val isArabic = remember(query) { query.startsWithArabic() }
    val layoutDirection = if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
    val textAlign = if (isArabic) TextAlign.Right else TextAlign.Left

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {

            // Query TextField (Dynamic RTL/LTR based on text)
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier =
                        Modifier
                            .weight(1f)
                            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                            .onFocusChanged { focusState ->
                                onFocusChanged?.invoke(focusState.isFocused)
                            },
                    placeholder = {
                        Text(
                            text = placeholder,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = textAlign,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        )
                    },
                    singleLine = true,
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    textStyle =
                        LocalTextStyle.current.copy(
                            textAlign = textAlign,
                            textDirection = TextDirection.ContentOrRtl,
                        ),
                )
            }

            // Clear button
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.search_action_clear_query)
                    )
                }
            }

            // Search Button
            IconButton(
                onClick = onSearch,
                enabled = canSearch,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.search_action_start),
                    tint =
                        if (canSearch) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchHistoryOverlay(
    searchHistory: List<String>,
    onClearAll: () -> Unit,
    onHistoryClick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    activeMode: SearchMode,
    onModeSelect: (SearchMode) -> Unit,
    nearDistance: Int,
    onNearDistanceChange: (Int) -> Unit,
    onHelpClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {} // Blocks clicks to layers below
                ),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (searchHistory.isNotEmpty()) {
                // Header: Clear All and History title
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.search_history_clear_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { onClearAll() },
                    )
                    Text(
                        text = stringResource(R.string.search_history_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // History List
                val dynamicHeight = (searchHistory.size * 40).dp
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(minOf(160.dp, dynamicHeight)),
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(
                                items = searchHistory,
                                key = { _, item -> item },
                            ) { index, historyQuery ->
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { onHistoryClick(historyQuery) }
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = historyQuery,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.Start,
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    IconButton(
                                        onClick = { onRemoveHistory(historyQuery) },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.search_history_remove),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                if (index < searchHistory.lastIndex) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.2f
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!searchHistory.isEmpty()) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                )
            }

            // Options (hstack of SearchMode buttons and Help button)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.weight(1f)
                ) {
                    SearchMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = activeMode == mode,
                            onClick = { onModeSelect(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = SearchMode.entries.size
                            ),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                inactiveContainerColor = MaterialTheme.colorScheme.surface,
                                inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            icon = {}
                        ) {
                            Icon(
                                imageVector = when (mode) {
                                    SearchMode.PHRASE -> Icons.Default.FormatQuote
                                    SearchMode.CONTAINS -> Icons.AutoMirrored.Filled.PlaylistAddCheck
                                    SearchMode.OR -> Icons.AutoMirrored.Filled.List
                                    SearchMode.NEAR -> Icons.AutoMirrored.Filled.WrapText
                                },
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = activeMode == SearchMode.NEAR,
                    enter = expandHorizontally() + fadeIn(),
                    exit = shrinkHorizontally() + fadeOut()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = if (nearDistance == 0) "" else nearDistance.toString(),
                            onValueChange = { newValue ->
                                val filtered = newValue.filter { char -> char.isDigit() }
                                if (filtered.isNotEmpty()) {
                                    onNearDistanceChange(filtered.toInt().coerceIn(1, 999))
                                } else if (newValue.isEmpty()) {
                                    onNearDistanceChange(0)
                                }
                            },
                            modifier = Modifier
                                .width(54.dp)
                                .height(40.dp), // Match SegmentedButton default height
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(
                                                alpha = 0.5f
                                            )
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(20.dp)
                                        )
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (nearDistance == 0) {
                                        Text(
                                            text = "0",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.5f
                                            ),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Help Button
                IconButton(onClick = onHelpClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = stringResource(R.string.search_action_help),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
fun SearchHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.search_help_action_close))
            }
        },
        title = {
            Text(stringResource(R.string.search_help_title))
        },
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column {
                    Text(
                        stringResource(R.string.search_help_phrase_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.search_help_phrase_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column {
                    Text(
                        stringResource(R.string.search_help_and_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.search_help_and_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column {
                    Text(
                        stringResource(R.string.search_help_or_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.search_help_or_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column {
                    Text(
                        stringResource(R.string.search_help_near_title),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.search_help_near_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
fun buildHighlightedText(
    text: String,
    searchKeywords: List<String>,
    searchMode: SearchMode = SearchMode.PHRASE,
    nearDistance: Int = 10,
    highlightColor: Color = Color(0xFFFFD54F).copy(alpha = 0.4f),
): AnnotatedString {
    return remember(text, searchKeywords, searchMode, nearDistance, highlightColor) {
        buildAnnotatedString {
            append(text)
            if (searchKeywords.isEmpty()) return@buildAnnotatedString

            val ranges = if (searchMode == SearchMode.NEAR && searchKeywords.size > 1) {
                val rangesWithIndex = text.findArabicMatchingRangesWithIndex(searchKeywords)
                text.filterRangesForNearMode(rangesWithIndex, searchKeywords.size, nearDistance)
            } else {
                text.findArabicMatchingRanges(searchKeywords)
            }

            for (range in ranges) {
                if (range.first in text.indices && range.last < text.length && range.first <= range.last) {
                    addStyle(
                        style = SpanStyle(background = highlightColor),
                        start = range.first,
                        end = range.last + 1
                    )
                }
            }
        }
    }
}
