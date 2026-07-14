package com.maktabah.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.utils.convertToArabicDigits
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookNavigationSheet(
    viewModel: ReaderViewModel,
    onDismissRequest: () -> Unit,
) {
    val totalParts by viewModel.totalParts.collectAsState()
    val minPage by viewModel.minPageInPart.collectAsState()
    val maxPage by viewModel.maxPageInPart.collectAsState()
    val content by viewModel.currentContent.collectAsState()

    val currentPart = content?.part ?: 1
    val currentPage = content?.page ?: 1

    var sliderPart by remember(currentPart) { mutableFloatStateOf(currentPart.toFloat()) }
    var sliderPage by remember(currentPage) { mutableFloatStateOf(currentPage.toFloat()) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.reader_jump_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (totalParts > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(
                            R.string.reader_jump_part,
                            sliderPart.toInt()
                        ).convertToArabicDigits()
                    )
                    Text(
                        text = stringResource(
                            R.string.reader_jump_from,
                            totalParts
                        ).convertToArabicDigits()
                    )
                }
                Slider(
                    value = sliderPart,
                    onValueChange = { sliderPart = it },
                    onValueChangeFinished = {
                        viewModel.jumpToPart(sliderPart.toInt())
                    },
                    valueRange = 1f..max(1f, totalParts.toFloat()),
                    steps = if (totalParts > 2) totalParts - 2 else 0,
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(
                        R.string.reader_jump_page,
                        sliderPage.toInt()
                    ).convertToArabicDigits()
                )
                Text(text = "(${minPage} - ${maxPage})".convertToArabicDigits())
            }
            Slider(
                value = sliderPage,
                onValueChange = { sliderPage = it },
                onValueChangeFinished = {
                    viewModel.jumpToPage(sliderPage.toInt())
                },
                valueRange = minPage.toFloat()..max(minPage.toFloat() + 1f, maxPage.toFloat()),
                steps = if (maxPage > minPage + 1) maxPage - minPage - 1 else 0,
            )
        }
    }
}