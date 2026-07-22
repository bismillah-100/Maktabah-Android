package com.maktabah.ui.search.savedresults

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.models.FolderNode
import com.maktabah.ui.search.ResultsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveItemSheet(
    disabledFolderIds: Set<Long>,
    onSelect: (Long?) -> Unit,
    resultsViewModel: ResultsViewModel,
    onDismiss: () -> Unit
) {
    val folderRoots by resultsViewModel.folderRoots.collectAsState()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = { WindowInsets(0.dp) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.saved_results_move_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                item {
                    MoveFolderItem(
                        text = stringResource(R.string.save_results_root),
                        level = 0,
                        disabled = false,
                        onClick = { onSelect(null) }
                    )
                }
                
                fun addFolderNodes(nodes: List<FolderNode>, level: Int) {
                    for (node in nodes) {
                        val isDisabled = disabledFolderIds.contains(node.id)
                        item {
                            MoveFolderItem(
                                text = node.name,
                                level = level,
                                disabled = isDisabled,
                                onClick = { onSelect(node.id) }
                            )
                        }
                        addFolderNodes(node.children, level + 1)
                    }
                }
                
                addFolderNodes(folderRoots, 1)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MoveFolderItem(
    text: String,
    level: Int,
    disabled: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (disabled) 0.38f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(vertical = 12.dp)
            .padding(start = (level * 24).dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        )
    }
}
