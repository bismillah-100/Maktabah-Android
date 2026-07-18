package com.maktabah.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.maktabah.ui.common.rememberBottomSheetNestedScrollConnection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.models.TOCNode
import com.maktabah.models.VisibleTOCNode
import com.maktabah.ui.common.InsetGroupedItem
import com.maktabah.ui.common.fadingEdge
import com.maktabah.ui.search.SearchTextField
import com.maktabah.utils.normalizeArabic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookTOCSheet(
    tocList: List<TOCNode>,
    expandedTOCNodes: MutableState<Set<String>>,
    viewModel: ReaderViewModel,
    onDismissRequest: () -> Unit,
) {
    val currentContent by viewModel.currentContent.collectAsState()
    val listState = rememberLazyListState()

    val nestedScrollConnection = rememberBottomSheetNestedScrollConnection(listState)
    var searchQuery by viewModel.tocSearchQuery

    val filteredTOC = remember(tocList, searchQuery) {
        val cleanQuery = searchQuery.normalizeArabic()
        if (cleanQuery.isBlank()) {
            tocList
        } else {
            fun filterNodes(nodes: List<TOCNode>): List<TOCNode> {
                return nodes.mapNotNull { node ->
                    val filteredChildren = filterNodes(node.children)
                    val matches = node.title.normalizeArabic().contains(cleanQuery, ignoreCase = true)
                    if (matches || filteredChildren.isNotEmpty()) {
                        node.copy(children = filteredChildren.toMutableList())
                    } else {
                        null
                    }
                }
            }
            filterNodes(tocList)
        }
    }

    val selectedNode = remember(tocList, currentContent) {
        val currentId = currentContent?.id ?: return@remember null
        fun findNode(nodes: List<TOCNode>, targetId: Int): TOCNode? {
            for (node in nodes) {
                if (targetId >= node.id && targetId <= node.endID) return node
                val found = findNode(node.children, targetId)
                if (found != null) return found
            }
            return null
        }
        findNode(tocList, currentId)
    }

    val selectedNodeUuid = selectedNode?.uuid

    val visibleNodesState = remember(filteredTOC, selectedNodeUuid) {
        derivedStateOf {
            val list = mutableListOf<VisibleTOCNode>()

            fun buildList(nodes: List<TOCNode>, depth: Int) {
                nodes.forEach { node ->
                    val hasChildren = node.children.isNotEmpty()
                    val isExpanded = expandedTOCNodes.value.contains(node.uuid)
                    val isSelected = selectedNodeUuid != null && node.uuid == selectedNodeUuid

                    list.add(
                        VisibleTOCNode(
                            node = node,
                            depth = depth,
                            hasChildren = hasChildren,
                            isExpanded = isExpanded,
                            isSelected = isSelected
                        )
                    )

                    if (isExpanded) {
                        buildList(node.children, depth + 1)
                    }
                }
            }

            buildList(filteredTOC, 0)
            list
        }
    }
    val visibleNodes by visibleNodesState

    // Keep track of the last selected node UUID we scrolled to
    var lastScrolledToUuid by remember { mutableStateOf<String?>(null) }
    var lastSearchQuery by remember { mutableStateOf(searchQuery) }

    LaunchedEffect(tocList, selectedNode, searchQuery) {
        if (tocList.isEmpty() || selectedNode == null) return@LaunchedEffect

        val isSearchEmptyNow = searchQuery.isBlank()
        val wasSearchNotEmpty = lastSearchQuery.isNotBlank()
        val searchJustCleared = isSearchEmptyNow && wasSearchNotEmpty
        lastSearchQuery = searchQuery

        // Only scroll if:
        // 1. Target node changed
        // 2. We just cleared the search
        if (selectedNode.uuid == lastScrolledToUuid && !searchJustCleared) return@LaunchedEffect

        delay(0.5.seconds)

        val pathResult = withContext(Dispatchers.Default) {
            val path = mutableListOf<TOCNode>()
            fun findPath(nodes: List<TOCNode>, target: TOCNode): Boolean {
                for (node in nodes) {
                    path.add(node)
                    if (node.id == target.id) return true
                    if (findPath(node.children, target)) return true
                    path.removeAt(path.size - 1)
                }
                return false
            }
            if (findPath(tocList, selectedNode)) path else null
        }

        pathResult?.let { path ->
            val expandedSet = expandedTOCNodes.value.toMutableSet()
            var addedAny = false
            // Expand all parents of the target node, and the node itself.
            for (i in 0 until path.size - 1) {
                if (expandedSet.add(path[i].uuid)) {
                    addedAny = true
                }
            }
            if (addedAny) {
                expandedTOCNodes.value = expandedSet
            }

            // Wait for expansion to apply then scroll
            val targetUuid = selectedNode.uuid
            snapshotFlow { visibleNodesState.value }
                .first { list -> list.any { it.node.uuid == targetUuid } }

            // Tunggu sampai LazyColumn siap (layout ready)
            snapshotFlow { listState.layoutInfo.totalItemsCount == visibleNodesState.value.size }
                .first { it }

            val index = visibleNodesState.value.indexOfFirst { it.node.uuid == targetUuid }
            if (index != -1) {
                val visibleItems = listState.layoutInfo.visibleItemsInfo
                val isVisible = visibleItems.any { it.index == index }
                if (!isVisible) {
                    delay(100.milliseconds)
                    listState.scrollToItem(index)
                }
                lastScrolledToUuid = targetUuid
            }
        }
    }

    // Auto-expand all nodes when searching
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            val allUuids = mutableSetOf<String>()
            fun collectUuids(nodes: List<TOCNode>) {
                nodes.forEach {
                    allUuids.add(it.uuid)
                    collectUuids(it.children)
                }
            }
            collectUuids(filteredTOC)
            expandedTOCNodes.value = allUuids
        }
    }

    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = { WindowInsets(0.dp) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            val topPadding = 54.dp
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                if (filteredTOC.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(top = topPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.reader_toc_no_results))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .nestedScroll(nestedScrollConnection)
                            .fillMaxSize()
                            .fadingEdge(listState, 48.dp),
                        state = listState,
                        contentPadding = PaddingValues(top = topPadding, bottom = 32.dp)
                    ) {
                        itemsIndexed(visibleNodes) { index, visibleNode ->
                            val node = visibleNode.node
                            val depth = visibleNode.depth
                            val hasChildren = visibleNode.hasChildren
                            val isExpanded = visibleNode.isExpanded
                            val isSelected = visibleNode.isSelected

                            InsetGroupedItem(
                                index = index,
                                lastIndex = visibleNodes.lastIndex,
                                onClick = {
                                    viewModel.loadContentById(node.id)
                                    onDismissRequest()
                                },
                                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = (depth * 16).dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (hasChildren) {
                                        Icon(
                                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = stringResource(R.string.reader_toc_expand_collapse),
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clickable {
                                                    val set = expandedTOCNodes.value.toMutableSet()
                                                    if (isExpanded) set.remove(node.uuid) else set.add(
                                                        node.uuid
                                                    )
                                                    expandedTOCNodes.value = set
                                                }
                                                .padding(end = 8.dp)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.width(32.dp))
                                    }
                                    Text(
                                        text = node.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
                SearchTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.reader_toc_search_placeholder),
                    onClearClick = { searchQuery = "" }
                )
            }
        }
    }
}
