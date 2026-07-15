package com.maktabah.ui.common

import android.graphics.BlurMaskFilter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.models.BookDownloadState
import com.maktabah.models.IntegratePhase
import com.maktabah.ui.library.LibraryViewModel

/**
 * Menyimpan snapshot state setiap item yang sedang atau pernah ditampilkan.
 * Dipisah dari activeStates agar bisa mengontrol visibilitas untuk animasi exit.
 */
private data class DisplayState(
    val state: BookDownloadState,
    val transitionState: MutableTransitionState<Boolean>,
    val insertOrder: Long, // untuk mempertahankan urutan insert
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDownloadOverlay(
    viewModel: LibraryViewModel,
    bottomPadding: Dp,
    onNavigateToReader: (Int, Int?, Int?, Int?, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeStates by viewModel.activeDownloadStates.collectAsState()

    // Map dari id -> DisplayState. Dipakai sebagai sumber kebenaran untuk UI.
    val displayMap = remember { mutableStateMapOf<String, DisplayState>() }
    var insertCounter by remember { mutableLongStateOf(0L) }

    // Sinkronisasi displayMap dengan activeStates
    LaunchedEffect(activeStates) {
        val activeIds = activeStates.map { it.id }.toSet()

        // 1. Update state item yang sudah ada (progress, error, dsb.)
        activeStates.forEach { state ->
            val current = displayMap[state.id]
            if (current != null) {
                displayMap[state.id] = current.copy(state = state)
            }
        }

        // 2. Tambah item baru
        val currentIds = displayMap.keys
        val newStates = activeStates.filter { it.id !in currentIds }
        if (newStates.isNotEmpty()) {
            newStates.forEach { state ->
                val transition = MutableTransitionState(false)
                displayMap[state.id] = DisplayState(state, transition, ++insertCounter)
                transition.targetState = true // Trigger enter animation
            }
        }

        // 3. Trigger animasi keluar untuk item yang dihapus
        val removingIds = displayMap.keys.filter { it !in activeIds && displayMap[it]?.transitionState?.targetState == true }
        removingIds.forEach { id ->
            displayMap[id]?.transitionState?.targetState = false
        }
    }

    // Bersihkan saat composable keluar dari komposisi
    DisposableEffect(Unit) {
        onDispose { displayMap.clear() }
    }

    val sortedItems = displayMap.values.sortedBy { it.insertOrder }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding + 16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = activeStates.isNotEmpty(),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 400.dp)
                    .drawShadow(
                        color = Color.Black.copy(alpha = 0.2f),
                        borderRadius = 16.dp,
                        blurRadius = 12.dp,
                        offsetY = 6.dp
                    )
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    sortedItems.forEachIndexed { index, displayState ->
                        AnimatedVisibility(
                            visibleState = displayState.transitionState,
                            enter = slideInVertically { it / 2 } + expandVertically() + fadeIn(),
                            exit = slideOutVertically { it / 2 } + shrinkVertically() + fadeOut()
                        ) {
                            Column {
                                AnimatedVisibility(
                                    visible = index > 0,
                                    enter = fadeIn() + expandVertically(),
                                    exit = fadeOut() + shrinkVertically()
                                ) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                                DownloadStateItem(
                                    state = displayState.state,
                                    onCancel = { viewModel.cancelDownload(displayState.state.id) },
                                    onConfirm = {
                                        viewModel.confirmDownload(context, displayState.state.id) { bId, cId, l, ln, q ->
                                            onNavigateToReader(bId, cId, l, ln, q)
                                        }
                                    }
                                )
                            }
                        }

                        // Hapus dari map HANYA ketika animasi exit benar-benar 100% selesai
                        if (displayState.transitionState.isIdle && !displayState.transitionState.targetState) {
                            LaunchedEffect(displayState.state.id) {
                                displayMap.remove(displayState.state.id)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadStateItem(
    state: BookDownloadState,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(16.dp)
    ) {
        // Baris 1: Icon & Nama Buku
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (state.error != null) Icons.Default.ErrorOutline else Icons.Default.CloudDownload,
                contentDescription = null,
                tint = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = state.bookName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Baris 2: Keterangan & Aksi
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (state.error != null) {
                    Text(
                        text = stringResource(R.string.download_overlay_failed_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (state.isDownloading) {
                    val statusText = when (state.phase) {
                        IntegratePhase.DOWNLOAD -> stringResource(R.string.download_overlay_downloading, state.progress)
                        IntegratePhase.DATA -> stringResource(R.string.download_overlay_integrating_data)
                        IntegratePhase.FTS -> stringResource(R.string.download_overlay_integrating_fts)
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = if (state.isBulk) {
                            stringResource(R.string.download_overlay_confirm_bulk, state.bulkBookIds.size, state.sizeText)
                        } else {
                            stringResource(R.string.download_overlay_confirm_single, state.sizeText)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Aksi
            if (state.error != null) {
                IconButton(
                    onClick = onCancel,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.tutup),
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else if (state.isDownloading) {
                Text(
                    text = if (state.phase == IntegratePhase.DOWNLOAD) "${state.progress}%" else "100%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onCancel,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.batal),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (!state.isBulk || state.bulkBookIds.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = onConfirm,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.import_sekarang),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        if (state.isDownloading && state.error == null) {
            Spacer(modifier = Modifier.height(8.dp))
            if (state.phase == IntegratePhase.DOWNLOAD) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    strokeCap = StrokeCap.Round,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    strokeCap = StrokeCap.Round,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                )
            }
        }
    }
}

fun Modifier.drawShadow(
    color: Color = Color.Black.copy(alpha = 0.15f),
    borderRadius: Dp = 16.dp,
    blurRadius: Dp = 8.dp,
    offsetY: Dp = 4.dp,
    offsetX: Dp = 0.dp
): Modifier = this.drawBehind {
    drawIntoCanvas { canvas ->
        val paint = Paint()
        val frameworkPaint = paint.asFrameworkPaint()
        if (blurRadius.value > 0f) {
            frameworkPaint.maskFilter = BlurMaskFilter(
                blurRadius.toPx(),
                BlurMaskFilter.Blur.NORMAL
            )
        }
        frameworkPaint.color = color.toArgb()
        canvas.drawRoundRect(
            left = offsetX.toPx(),
            top = offsetY.toPx(),
            right = size.width + offsetX.toPx(),
            bottom = size.height + offsetY.toPx(),
            radiusX = borderRadius.toPx(),
            radiusY = borderRadius.toPx(),
            paint = paint
        )
    }
}
