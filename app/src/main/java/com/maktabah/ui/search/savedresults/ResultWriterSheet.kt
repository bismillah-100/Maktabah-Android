package com.maktabah.ui.search.savedresults

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.manager.LibraryDataManager
import com.maktabah.models.FolderNode
import com.maktabah.models.SearchResult
import com.maktabah.ui.search.ResultsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultWriterSheet(
    results: List<SearchResult>,
    query: String,
    resultsViewModel: ResultsViewModel,
    dataManager: LibraryDataManager,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(query) }
    var selectedFolderId by remember { mutableStateOf<Long?>(null) }
    var isCreatingFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    
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
                text = stringResource(R.string.save_results_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.save_results_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "${stringResource(R.string.save_results_query_label)}: $query",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.save_results_select_folder),
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = { isCreatingFolder = true }) {
                    Text(stringResource(R.string.save_results_new_folder))
                }
            }
            
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                item {
                    FolderRadioItem(
                        text = stringResource(R.string.save_results_root),
                        level = 0,
                        selected = selectedFolderId == null,
                        onClick = { selectedFolderId = null }
                    )
                }
                
                fun addFolderNodes(nodes: List<FolderNode>, level: Int) {
                    for (node in nodes) {
                        item {
                            FolderRadioItem(
                                text = node.name,
                                level = level,
                                selected = selectedFolderId == node.id,
                                onClick = { selectedFolderId = node.id }
                            )
                        }
                        addFolderNodes(node.children, level + 1)
                    }
                }
                
                addFolderNodes(folderRoots, 1)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.save_results_cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val success = resultsViewModel.saveSearchResults(
                            results = results,
                            query = query,
                            folderId = selectedFolderId,
                            name = name.trim(),
                            dataManager = dataManager
                        )
                        if (success) {
                            onDismiss()
                        }
                    },
                    enabled = name.trim().isNotEmpty()
                ) {
                    Text(stringResource(R.string.save_results_save))
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    
    if (isCreatingFolder) {
        AlertDialog(
            onDismissRequest = { isCreatingFolder = false },
            title = { Text(stringResource(R.string.save_results_create_folder)) },
            containerColor = MaterialTheme.colorScheme.surface,
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(stringResource(R.string.save_results_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(30.dp)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newFolderName.trim().isNotEmpty()) {
                            if (selectedFolderId != null) {
                                resultsViewModel.addSubFolder(selectedFolderId!!, newFolderName.trim())
                            } else {
                                resultsViewModel.addRootFolder(newFolderName.trim())
                            }
                            isCreatingFolder = false
                            newFolderName = ""
                        }
                    }
                ) {
                    Text(stringResource(R.string.save_results_create_folder))
                }
            },
            dismissButton = {
                TextButton(onClick = { isCreatingFolder = false }) {
                    Text(stringResource(R.string.save_results_cancel))
                }
            }
        )
    }
}

@Composable
private fun FolderRadioItem(
    text: String,
    level: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .padding(start = (level * 24).dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}
