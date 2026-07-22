package com.maktabah.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.maktabah.R
import com.maktabah.database.AnnotationManager
import com.maktabah.models.ActiveAnnotationState
import com.maktabah.models.Annotation
import com.maktabah.models.BookContent
import com.maktabah.ui.annotation.AnnotationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationEditorDialog(
    active: ActiveAnnotationState,
    bookId: Int,
    content: BookContent?,
    showHarakat: Boolean,
    annotationManager: AnnotationManager,
    scope: CoroutineScope,
    onSyncRequested: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    var noteText by remember(active) { mutableStateOf(active.annotation?.note ?: "") }
    var selectedColor by remember(active) {
        mutableStateOf(
            active.annotation?.colorHex ?: active.colorHex
        )
    }
    var selectedType by remember(active) {
        mutableIntStateOf(
            (active.annotation?.type ?: active.type).let { if (it == 2) 0 else it }
        )
    }
    var tagsText by remember(active) { mutableStateOf(active.annotation?.tags ?: "") }

    var existingTags by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(bookId) {
        existingTags = annotationManager
            .getAnnotationsForBook(bookId)
            .flatMap { it.tags.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                if (active.annotation == null) stringResource(R.string.reader_ann_editor_add_title) else stringResource(
                    R.string.reader_ann_editor_edit_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column {
                    Text(
                        stringResource(R.string.reader_ann_editor_selected_text),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = active.annotation?.context ?: active.selectedText,
                            modifier = Modifier.padding(8.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDirection = TextDirection.Rtl,
                            ),
                        )
                    }
                }

                Column {
                    Text(
                        stringResource(R.string.reader_ann_editor_type),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            stringResource(R.string.reader_context_highlight) to 0,
                            stringResource(R.string.reader_context_underline) to 1,
                        ).forEach { (label, value) ->
                            FilterChip(
                                selected = selectedType == value,
                                onClick = { selectedType = value },
                                label = { Text(label) },
                            )
                        }
                    }
                }

                if (selectedType != 1) {
                    Column {
                        Text(
                            stringResource(R.string.reader_ann_editor_color),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            val standardColors = listOf(
                                "#FFFF00", // Yellow
                                "#00FF00", // Green
                                "#FF0000", // Red
                                "#0000FF", // Blue
                                "#FFA500", // Orange
                                "#FF00FF", // Pink
                            )
                            standardColors.forEach { colorHex ->
                                val color = Color(colorHex.toColorInt())
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(color, shape = CircleShape)
                                        .clickable { selectedColor = colorHex }
                                        .border(
                                            width = if (selectedColor.equals(
                                                    colorHex,
                                                    ignoreCase = true
                                                )
                                            ) 3.dp else 0.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape,
                                        ),
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text(stringResource(R.string.reader_ann_editor_notes_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )

                Column {
                    OutlinedTextField(
                        value = tagsText,
                        onValueChange = { tagsText = it },
                        label = { Text(stringResource(R.string.reader_ann_editor_tags_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (existingTags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.reader_ann_editor_suggestions),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(existingTags.size, key = { index -> existingTags[index] }) { index ->
                                val tag = existingTags[index]
                                AssistChip(
                                    onClick = {
                                        val currentList = tagsText
                                            .split(",")
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }
                                            .toMutableList()
                                        if (!currentList.contains(tag)) {
                                            currentList.add(tag)
                                            tagsText = currentList.joinToString(", ")
                                        }
                                    },
                                    label = { Text(tag) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val currentContent = content ?: return@Button
                    val (diacLoc, diacLen, plainLoc, plainLen) =
                        if (active.annotation == null) {
                            AnnotationCoordinator.calculateBothRanges(
                                active.loc,
                                active.len,
                                content.nass,
                                showHarakat
                            )
                        } else {
                            intArrayOf(
                                active.annotation.rangeDiacLocation,
                                active.annotation.rangeDiacLength,
                                active.annotation.rangeLocation,
                                active.annotation.rangeLength,
                            )
                        }

                    val annotationToSave = Annotation(
                        id = active.annotation?.id,
                        bkId = bookId,
                        contentId = active.annotation?.contentId ?: currentContent.id,
                        colorHex = if (selectedType == 1) "#000000" else selectedColor,
                        note = noteText.ifBlank { null },
                        type = selectedType,
                        createdAt = active.annotation?.createdAt?.let {
                            if (it in 1L..9999999999L) it * 1000L else it
                        } ?: System.currentTimeMillis(),
                        page = active.annotation?.page ?: currentContent.page,
                        context = active.annotation?.context ?: active.selectedText,
                        rangeLocation = plainLoc,
                        rangeLength = plainLen,
                        rangeDiacLocation = diacLoc,
                        rangeDiacLength = diacLen,
                        part = active.annotation?.part ?: currentContent.part,
                        tags = tagsText
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString(","),
                        ckRecordId = active.annotation?.ckRecordId ?: UUID.randomUUID().toString(),
                    )

                    scope.launch(Dispatchers.IO) {
                        annotationManager.insertOrUpdate(annotationToSave)
                        withContext(Dispatchers.Main) {
                            onSyncRequested()
                            onDismissRequest()
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.reader_ann_editor_save))
            }
        },
        dismissButton = {
            Row {
                if (active.annotation != null) {
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                active.annotation.id?.let {
                                    annotationManager.deleteAnnotation(
                                        it,
                                        active.annotation.ckRecordId
                                    )
                                }
                                withContext(Dispatchers.Main) {
                                    onSyncRequested()
                                    onDismissRequest()
                                }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.reader_ann_editor_delete))
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(R.string.reader_ann_editor_cancel))
                }
            }
        },
    )
}
