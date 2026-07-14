package com.maktabah.ui.reader

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maktabah.R

private val backgroundColors =
    listOf(
        Color.White,
        Color(0xFFF4ECD8), // Sepia
        Color(0xFF5B4636), // Dark Sepia
        Color(0xFFE5E5EA), // Gray
        Color.Black,
    )
private val textColors = listOf(Color.Black, Color.Black, Color.White, Color.Black, Color.White)
private val builtInFontNames = listOf("Uthman", "Lateef", "Scheherazade", "Lateef Bold")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderOptionsSheet(
    textSize: Float,
    showHarakat: Boolean,
    backgroundColorIndex: Int,
    fontIndex: Int,
    viewModel: ReaderViewModel,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    var fontDropdownExpanded by remember { mutableStateOf(false) }
    val customFonts by viewModel.customFonts.collectAsState()
    val allFontNames = remember(customFonts) {
        builtInFontNames + customFonts.map { it.nameWithoutExtension }
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.importFont(it, context) {
                Toast.makeText(
                    context,
                    context.getString(R.string.history_settings_font_import_success),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.reader_options_title), style = MaterialTheme.typography.titleMedium)

            HorizontalDivider()

            // --- Text Size ---
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.reader_options_text_size),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { viewModel.updateTextSize(textSize - 2f) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Text("−", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        textSize.toInt().toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(min = 32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(
                        onClick = { viewModel.updateTextSize(textSize + 2f) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }

            HorizontalDivider()

            // --- Show Harakat ---
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.reader_options_show_harakat),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = showHarakat,
                    onCheckedChange = { viewModel.toggleHarakat() },
                )
            }

            HorizontalDivider()

            // --- Background Color ---
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.reader_options_bg_color), style = MaterialTheme.typography.bodyMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    backgroundColors.forEachIndexed { index, color ->
                        val isSelected = backgroundColorIndex == index
                        val needsBorder = color == Color.White || color == Color(0xFFE5E5EA)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .size(36.dp)
                                    .background(color, shape = CircleShape)
                                    .then(
                                        if (needsBorder) {
                                            Modifier.border(
                                                1.dp,
                                                MaterialTheme.colorScheme.outline,
                                                CircleShape,
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ).clickable { viewModel.setBackgroundColorIndex(index) },
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = textColors[index],
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // --- Font (Dropdown) ---
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.reader_options_font),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    OutlinedButton(
                        onClick = { fontDropdownExpanded = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            allFontNames.getOrElse(fontIndex) { builtInFontNames.first() },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = fontDropdownExpanded,
                        onDismissRequest = { fontDropdownExpanded = false },
                    ) {
                        allFontNames.forEachIndexed { index, name ->
                            val isCustom = index >= builtInFontNames.size
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color =
                                            if (fontIndex == index) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                    )
                                },
                                trailingIcon = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isCustom) {
                                            IconButton(
                                                onClick = {
                                                    val customIndex = index - builtInFontNames.size
                                                    viewModel.deleteFont(customFonts[customIndex], context)
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        if (fontIndex == index) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    viewModel.setFontIndex(index)
                                    fontDropdownExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // --- Font Options ---
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.history_settings_font_options),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = {
                        fontPickerLauncher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.history_settings_import_font))
                }
            }
        }
    }
}
