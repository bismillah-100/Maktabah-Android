package com.maktabah.ui.search.savedresults

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.models.FolderNode
import com.maktabah.models.ResultNode
import com.maktabah.models.SavedResultsItem
import com.maktabah.ui.common.InsetGroupedItem
import com.maktabah.ui.common.fadingEdge
import com.maktabah.ui.search.ResultsViewModel
import com.maktabah.ui.search.SearchTextField

sealed class MoveTarget {
    data class Folder(val node: FolderNode) : MoveTarget()
    data class Result(val node: ResultNode) : MoveTarget()
}

sealed class RenameTarget {
    data class Folder(val node: FolderNode) : RenameTarget()
    data class Result(val node: ResultNode) : RenameTarget()
    data object NewRootFolder : RenameTarget()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedResultsScreen(
    resultsViewModel: ResultsViewModel,
    onSelectResult: (List<SavedResultsItem>) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    backHandlerEnabled: Boolean = true
) {
    val folderStack by resultsViewModel.folderStack.collectAsState()
    val currentFolder = folderStack.lastOrNull()

    var searchQuery by remember { mutableStateOf("") }

    val folderRoots by resultsViewModel.folderRoots.collectAsState()
    val folderResults by resultsViewModel.folderResults.collectAsState()
    val isLoading by resultsViewModel.isLoading.collectAsState()

    var folderToDelete by remember { mutableStateOf<FolderNode?>(null) }
    var itemToRename by remember { mutableStateOf<RenameTarget?>(null) }
    var renameText by remember { mutableStateOf("") }
    var itemToMove by remember { mutableStateOf<MoveTarget?>(null) }

    BackHandler(enabled = backHandlerEnabled) {
        if (!resultsViewModel.popFolder()) {
            onDismiss()
        }
    }

    val topPadding = 112.dp

    Surface(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Content (List)
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                folderRoots.isEmpty() && (folderResults[null]?.isEmpty() != false) -> {
                    SavedResultsEmptyState(modifier = Modifier.padding(top = topPadding))
                }
                else -> {
                    val isSearching = searchQuery.isNotBlank()
                    val displayFolders = if (isSearching) {
                        resultsViewModel.searchFoldersInMemory(searchQuery)
                    } else {
                        (currentFolder?.children ?: folderRoots).toList()
                    }

                    val displayResults = if (isSearching) {
                        resultsViewModel.searchResultsInMemory(searchQuery)
                    } else {
                        folderResults[currentFolder?.id] ?: emptyList()
                    }

                    SavedResultsList(
                        folders = displayFolders,
                        results = displayResults,
                        topPadding = topPadding,
                        bottomPadding = bottomPadding,
                        onFolderClick = { resultsViewModel.pushFolder(it) },
                        onResultClick = {
                            onSelectResult(it.items)
                            onDismiss()
                        },
                        onRenameFolder = {
                            itemToRename = RenameTarget.Folder(it)
                            renameText = it.name
                        },
                        onMoveFolder = { itemToMove = MoveTarget.Folder(it) },
                        onDeleteFolder = { folderToDelete = it },
                        onRenameResult = {
                            itemToRename = RenameTarget.Result(it)
                            renameText = it.name
                        },
                        onMoveResult = { itemToMove = MoveTarget.Result(it) },
                        onDeleteResult = { resultsViewModel.deleteResult(it.parentId, it.name) }
                    )
                }
            }

            // Top Bar & Search (on top of the list for overlap)
            Column(modifier = Modifier.fillMaxWidth()) {
                SavedResultsTopBar(
                    title = currentFolder?.name ?: stringResource(R.string.saved_results_title),
                    onBackClick = {
                        if (!resultsViewModel.popFolder()) {
                            onDismiss()
                        }
                    },
                    onNewFolderClick = {
                        itemToRename = RenameTarget.NewRootFolder
                        renameText = ""
                    }
                )

                SearchTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    onClearClick = { searchQuery = "" },
                    placeholder = stringResource(R.string.saved_results_search_globally),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }

    DeleteFolderDialog(
        folder = folderToDelete,
        onConfirm = {
            resultsViewModel.deleteFolder(it)
            folderToDelete = null
        },
        onDismiss = { folderToDelete = null }
    )

    RenameItemDialog(
        target = itemToRename,
        text = renameText,
        onTextChange = { renameText = it },
        onConfirm = { target, newName ->
            if (newName.isNotBlank()) {
                when (target) {
                    is RenameTarget.Folder -> resultsViewModel.updateFolderName(target.node.id, newName)
                    is RenameTarget.Result -> resultsViewModel.updateResultName(target.node.id, newName)
                    is RenameTarget.NewRootFolder -> resultsViewModel.addRootFolder(newName)
                }
            }
            itemToRename = null
        },
        onDismiss = { itemToRename = null }
    )

    if (itemToMove != null) {
        val disabledIds = if (itemToMove is MoveTarget.Folder) {
            val node = (itemToMove as MoveTarget.Folder).node
            fun getAllDescendantIds(n: FolderNode): List<Long> {
                val ids = mutableListOf(n.id)
                for (child in n.children) ids.addAll(getAllDescendantIds(child))
                return ids
            }
            getAllDescendantIds(node).toSet()
        } else emptySet()

        MoveItemSheet(
            disabledFolderIds = disabledIds,
            onSelect = { newParentId ->
                when (val target = itemToMove) {
                    is MoveTarget.Folder -> resultsViewModel.moveFolder(target.node, newParentId)
                    is MoveTarget.Result -> resultsViewModel.moveResult(target.node.id, newParentId)
                    null -> {}
                }
                itemToMove = null
            },
            resultsViewModel = resultsViewModel,
            onDismiss = { itemToMove = null }
        )
    }
}

@Composable
private fun SavedResultsTopBar(
    title: String,
    onBackClick: () -> Unit,
    onNewFolderClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackClick) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.reader_action_back))
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )

        IconButton(onClick = onNewFolderClick) {
            Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.save_results_new_folder))
        }
    }
}

@Composable
private fun SavedResultsEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.saved_results_empty), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.saved_results_empty_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SavedResultsList(
    folders: List<FolderNode>,
    results: List<ResultNode>,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onFolderClick: (FolderNode) -> Unit,
    onResultClick: (ResultNode) -> Unit,
    onRenameFolder: (FolderNode) -> Unit,
    onMoveFolder: (FolderNode) -> Unit,
    onDeleteFolder: (FolderNode) -> Unit,
    onRenameResult: (ResultNode) -> Unit,
    onMoveResult: (ResultNode) -> Unit,
    onDeleteResult: (ResultNode) -> Unit
) {
    val totalItems = folders.size + results.size
    val listState = rememberLazyListState()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .fadingEdge(listState, 110.dp),
            state = listState,
            contentPadding = PaddingValues(top = topPadding + 8.dp, bottom = bottomPadding + 16.dp)
        ) {
            itemsIndexed(folders) { index, folder ->
                FolderItem(
                    folder = folder,
                    index = index,
                    lastIndex = totalItems - 1,
                    onClick = { onFolderClick(folder) },
                    onRename = { onRenameFolder(folder) },
                    onMove = { onMoveFolder(folder) },
                    onDelete = { onDeleteFolder(folder) }
                )
            }

            itemsIndexed(results) { idx, result ->
                ResultItem(
                    result = result,
                    index = folders.size + idx,
                    lastIndex = totalItems - 1,
                    onClick = { onResultClick(result) },
                    onRename = { onRenameResult(result) },
                    onMove = { onMoveResult(result) },
                    onDelete = { onDeleteResult(result) }
                )
            }
        }
    }
}

@Composable
private fun FolderItem(
    folder: FolderNode,
    index: Int,
    lastIndex: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    InsetGroupedItem(
        index = index,
        lastIndex = lastIndex,
        onClick = onClick,
        onLongClick = { expanded = true },
        dividerStartPadding = 48.dp,
        dividerEndPadding = 0.dp,
        contentPadding = PaddingValues(start = 16.dp, end = 0.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))

            Box {
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.padding(end = 16.dp).size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = stringResource(R.string.library_action_more_options),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.saved_results_rename)) },
                            onClick = {
                                expanded = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.saved_results_move)) },
                            onClick = {
                                expanded = false
                                onMove()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.saved_results_delete)) },
                            onClick = {
                                expanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultItem(
    result: ResultNode,
    index: Int,
    lastIndex: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    InsetGroupedItem(
        index = index,
        lastIndex = lastIndex,
        onClick = onClick,
        onLongClick = { expanded = true },
        dividerStartPadding = 48.dp,
        dividerEndPadding = 0.dp,
        contentPadding = PaddingValues(start = 16.dp, end = 0.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = result.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f, fill = false)
                )

                val queryText = result.items.firstOrNull()?.query
                if (!queryText.isNullOrEmpty()) {
                    Text("•")
                    Text(
                        text = queryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.padding(end = 16.dp).size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreHoriz,
                        contentDescription = stringResource(R.string.library_action_more_options),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.saved_results_rename)) },
                            onClick = {
                                expanded = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.saved_results_move)) },
                            onClick = {
                                expanded = false
                                onMove()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.saved_results_delete)) },
                            onClick = {
                                expanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteFolderDialog(
    folder: FolderNode?,
    onConfirm: (FolderNode) -> Unit,
    onDismiss: () -> Unit
) {
    if (folder != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.saved_results_delete_folder_title)) },
            containerColor = MaterialTheme.colorScheme.surface,
            text = { Text(stringResource(R.string.saved_results_delete_folder_message, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = { onConfirm(folder) }
                ) { Text(stringResource(R.string.saved_results_delete)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_results_cancel)) }
            }
        )
    }
}

@Composable
private fun RenameItemDialog(
    target: RenameTarget?,
    text: String,
    onTextChange: (String) -> Unit,
    onConfirm: (RenameTarget, String) -> Unit,
    onDismiss: () -> Unit
) {
    if (target != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    if (target is RenameTarget.NewRootFolder)
                        stringResource(R.string.save_results_create_folder)
                    else
                        stringResource(R.string.saved_results_rename)
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    label = { Text(stringResource(R.string.save_results_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(30.dp),
                    textStyle = LocalTextStyle.current.copy(
                        textDirection = TextDirection.ContentOrRtl
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onConfirm(target, text) }
                ) { Text(stringResource(R.string.save_results_save)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_results_cancel)) }
            }
        )
    }
}

