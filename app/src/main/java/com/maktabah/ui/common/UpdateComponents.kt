package com.maktabah.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.update.UpdateUIState
import com.maktabah.update.UpdateViewModel

@Composable
fun UpdateDialog(viewModel: UpdateViewModel) {
    val state = viewModel.updateState

    if (state == UpdateUIState.Idle || state == UpdateUIState.Checking) return

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        AlertDialog(
            onDismissRequest = { if (state !is UpdateUIState.Downloading) viewModel.dismiss() },
            title = {
                Text(
                    text = when (state) {
                        is UpdateUIState.UpdateAvailable -> "Update Tersedia"
                        is UpdateUIState.Downloading -> "Mengunduh Update"
                        is UpdateUIState.Installing -> "Menyiapkan Instalasi"
                        is UpdateUIState.Error -> "Gagal Update"
                    }
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
            text = {
                Column {
                    when (state) {
                        is UpdateUIState.UpdateAvailable -> {
                            Text("Versi ${state.release.tagName} sudah tersedia. Apakah Anda ingin memperbarui aplikasi?")
                            if (state.release.body.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = state.release.body,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        is UpdateUIState.Downloading -> {
                            Text("Sedang mengunduh... ${state.progress}%")
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { state.progress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        is UpdateUIState.Installing -> {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        is UpdateUIState.Error -> {
                            Text(state.message)
                        }
                    }
                }
            },
            confirmButton = {
                if (state is UpdateUIState.UpdateAvailable) {
                    Button(onClick = { viewModel.downloadAndInstall(state.release) }) {
                        Text("Download & Install")
                    }
                } else if (state is UpdateUIState.Error) {
                    Button(onClick = { viewModel.dismiss() }) {
                        Text("OK")
                    }
                }
            },
            dismissButton = {
                if (state is UpdateUIState.UpdateAvailable) {
                    TextButton(onClick = { viewModel.dismiss() }) {
                        Text("Nanti Saja")
                    }
                }
            }
        )
    }
}

// Helper to wrap the Box in Installing state
@Composable
private fun Box(modifier: Modifier, contentAlignment: Alignment, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier = modifier, contentAlignment = contentAlignment) {
        content()
    }
}
