package com.maktabah.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.ui.library.LibraryViewModel
import com.maktabah.ui.common.fadingEdge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookInfoSheet(
    bookId: Int,
    defaultTitle: String,
    libraryViewModel: LibraryViewModel,
    onDismissRequest: () -> Unit,
) {
    val book = libraryViewModel.dataManager.booksById[bookId]
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0.dp) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                val scrollState = rememberScrollState()
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .fadingEdge(
                                canScrollForward = scrollState.canScrollForward,
                                topPad = 24.dp,
                                bottomFade = 48.dp,
                            )
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = book?.name ?: defaultTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )

                        val authorName = book?.auth?.trim() ?: ""
                        if (authorName.isNotEmpty()) {
                            Text(
                                text = authorName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }

                        val authInfo = book?.authInf?.trim() ?: ""
                        if (authInfo.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = stringResource(R.string.reader_info_author),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            ParagraphText(
                                text = authInfo,
                            )
                        }

                        val betakaInfo = book?.betaka?.trim() ?: ""
                        if (betakaInfo.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = stringResource(R.string.reader_info_betaka),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            ParagraphText(
                                text = betakaInfo,
                            )
                        }

                        val infoText = book?.info?.trim() ?: ""
                        if (infoText.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = stringResource(R.string.reader_info_about_book),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            ParagraphText(
                                text = infoText,
                            )
                        } else if (authInfo.isEmpty() && betakaInfo.isEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = stringResource(R.string.reader_info_not_available),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ParagraphText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val paragraphs = androidx.compose.runtime.remember(text) {
        text.split(Regex("(?:\r?\n|\\\\n)"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    Column(modifier = modifier) {
        paragraphs.forEachIndexed { index, paragraph ->
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (index < paragraphs.lastIndex) Modifier.padding(bottom = 6.dp) else Modifier,
            )
        }
    }
}
