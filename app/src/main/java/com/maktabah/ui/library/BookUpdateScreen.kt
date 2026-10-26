package com.maktabah.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maktabah.R
import com.maktabah.models.BookUpdateItem
import com.maktabah.models.UpdateStatus
import com.maktabah.ui.search.SearchTextField

private val UthmanFontFamily = FontFamily(Font(R.font.uthman))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookUpdateSheet(
    onDismiss: () -> Unit,
    onUpdateSuccess: suspend () -> Unit,
) {
    val vm: BookUpdateViewModel = viewModel()

    val availableUpdates by vm.availableUpdates.collectAsState()
    val filteredUpdates by vm.filteredUpdates.collectAsState()
    val searchText by vm.searchText.collectAsState()
    val isLoadingList by vm.isLoadingList.collectAsState()
    val isUpdating by vm.isUpdating.collectAsState()
    val progressMessage by vm.progressMessage.collectAsState()

    val selectedCount by remember(availableUpdates) {
        derivedStateOf { availableUpdates.count { it.isSelected } }
    }
    val totalSelectedSizeFormatted by remember(availableUpdates) {
        derivedStateOf {
            val totalBytes = availableUpdates.sumOf { if (it.isSelected) it.fileSize else 0L }
            BookUpdateItem.formatByteCount(totalBytes)
        }
    }
    val needsUpdateCount by remember(availableUpdates) {
        derivedStateOf { availableUpdates.count { it.needsUpdate } }
    }
    val hasUpdates by remember(availableUpdates) {
        derivedStateOf { availableUpdates.isNotEmpty() }
    }

    BackHandler(enabled = isUpdating) {
        // Cegah tutup dialog saat proses update berjalan
    }

    LaunchedEffect(Unit) {
        vm.loadAvailableUpdates()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.book_update_title),
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onDismiss,
                            enabled = !isUpdating,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.tutup),
                            )
                        }
                    },
                    actions = {
                        if (hasUpdates) {
                            IconButton(
                                onClick = { vm.deselectAll() },
                                enabled = !isUpdating && selectedCount > 0,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RemoveCircleOutline,
                                    contentDescription = stringResource(R.string.book_update_clear_selection),
                                )
                            }
                            IconButton(
                                onClick = { vm.selectOnlyUpdates() },
                                enabled = !isUpdating && needsUpdateCount > 0,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowCircleUp,
                                    contentDescription = stringResource(R.string.book_update_select_only_updates),
                                )
                            }
                            IconButton(
                                onClick = { vm.selectAll() },
                                enabled = !isUpdating && needsUpdateCount > 0,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = stringResource(R.string.book_update_select_all),
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )

                if (hasUpdates && !isLoadingList) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SearchTextField(
                                value = searchText,
                                onValueChange = { vm.setSearchText(it) },
                                placeholder = stringResource(R.string.library_search_books_placeholder),
                                onClearClick = { vm.setSearchText("") },
                                modifier = Modifier.fillMaxWidth(),
                            )

                            if (progressMessage.isNotEmpty() || isUpdating) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    if (isUpdating) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                    Text(
                                        text = progressMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        },
        bottomBar = {
            if (hasUpdates && !isLoadingList) {
                Surface(
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (needsUpdateCount > 0) {
                            StatusBadge(
                                text = stringResource(R.string.book_update_found_count, needsUpdateCount),
                                color = Color(0xFFE67E22),
                            )
                        }

                        Button(
                            onClick = {
                                vm.performSelectedUpdates(onReloadLibrary = onUpdateSuccess)
                            },
                            enabled = !isUpdating && selectedCount > 0,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(30.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2E7D32),
                                contentColor = Color.White,
                            ),
                        ) {
                            if (isUpdating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = "${stringResource(R.string.book_update_button_selected, selectedCount)} - $totalSelectedSizeFormatted",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        when {
            isLoadingList -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.book_update_loading_list),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            availableUpdates.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(56.dp),
                        )
                        Text(
                            text = stringResource(R.string.book_update_all_up_to_date),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (progressMessage.isNotEmpty()) {
                            Text(
                                text = progressMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = { vm.loadAvailableUpdates() },
                            shape = RoundedCornerShape(30.dp),
                        ) {
                            Text(stringResource(R.string.book_update_check_again))
                        }
                    }
                }
            }

            else -> {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(30.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    filteredUpdates.forEachIndexed { index, item ->
                                        BookUpdateRow(
                                            item = item,
                                            enabled = !isUpdating,
                                            onToggle = { vm.toggleSelection(item.id) },
                                        )
                                        if (index < filteredUpdates.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                thickness = 0.5.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookUpdateRow(
    item: BookUpdateItem,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (item.needsUpdate) 1f else 0.7f)
            .clickable(enabled = enabled && item.needsUpdate) { onToggle() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.bookName,
                fontFamily = UthmanFontFamily,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.categoryName.isNotEmpty()) {
                    StatusBadge(
                        text = item.categoryName,
                        color = Color(0xFF1976D2),
                    )
                }

                StatusBadge(
                    text = item.fileSizeFormatted,
                    color = if (item.status == UpdateStatus.UpToDate || item.status == UpdateStatus.Completed) {
                        Color(0xFF2E7D32)
                    } else {
                        Color(0xFFE67E22)
                    },
                )

                BookUpdateStatusView(status = item.status)
            }
        }

        if (item.needsUpdate) {
            Icon(
                imageVector = if (item.isSelected) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (item.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun BookUpdateStatusView(status: UpdateStatus) {
    when (status) {
        UpdateStatus.Pending, UpdateStatus.Skipped -> Unit
        UpdateStatus.Checking -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.book_update_status_checking),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        UpdateStatus.Downloading -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.book_update_status_downloading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        UpdateStatus.Processing -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.book_update_status_processing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        UpdateStatus.Downloaded -> StatusBadge(
            text = stringResource(R.string.book_update_status_downloaded),
            color = Color(0xFF1976D2),
        )
        UpdateStatus.New -> StatusBadge(
            text = stringResource(R.string.book_update_status_new),
            color = Color(0xFFE67E22),
        )
        UpdateStatus.NeedsUpdate -> StatusBadge(
            text = stringResource(R.string.book_update_status_needs_update),
            color = Color(0xFFE67E22),
        )
        UpdateStatus.UpToDate -> StatusBadge(
            text = stringResource(R.string.book_update_status_up_to_date),
            color = Color(0xFF2E7D32),
        )
        UpdateStatus.Completed -> Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF2E7D32),
            modifier = Modifier.size(18.dp),
        )
        is UpdateStatus.Failed -> Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = status.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    color: Color,
) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}
