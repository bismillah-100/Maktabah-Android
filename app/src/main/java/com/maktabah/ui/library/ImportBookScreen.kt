package com.maktabah.ui.library

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.maktabah.R
import com.maktabah.database.AnnotationManager
import com.maktabah.models.AuthorMode
import com.maktabah.models.ImportMode
import com.maktabah.ui.search.SearchTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportBookSheet(
    annotationManager: AnnotationManager,
    onDismiss: () -> Unit,
    onImportSuccess: suspend () -> Unit,
) {
    val vm: ImportBookViewModel = viewModel()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val isLoadingData by vm.isLoadingData.collectAsState()
    val isImporting by vm.isImporting.collectAsState()
    val importResult by vm.importResult.collectAsState()
    val importMode by vm.importMode.collectAsState()
    val authorMode by vm.authorMode.collectAsState()
    val selectedFileName by vm.selectedFileName.collectAsState()
    val categories by vm.categories.collectAsState()
    val authors by vm.authors.collectAsState()
    val books by vm.books.collectAsState()
    val maxAuthid by vm.maxAuthid.collectAsState()
    val customBookIdText by vm.customBookIdText.collectAsState()
    val selectedBookId by vm.selectedBookId.collectAsState()
    val bookName by vm.bookName.collectAsState()
    val categoryId by vm.categoryId.collectAsState()
    val archiveId by vm.archiveId.collectAsState()
    val isMultiLanguage by vm.isMultiLanguage.collectAsState()
    val betaka by vm.betaka.collectAsState()
    val inf by vm.inf.collectAsState()
    val tafseerNam by vm.tafseerNam.collectAsState()
    val bVerText by vm.bVerText.collectAsState()
    val selectedAuthorId by vm.selectedAuthorId.collectAsState()
    val authorName by vm.authorName.collectAsState()
    val authorInf by vm.authorInf.collectAsState()
    val authorLng by vm.authorLng.collectAsState()
    val authorHigriD by vm.authorHigriD.collectAsState()
    val oVerText by vm.oVerText.collectAsState()

    val isValid by vm.isValid.collectAsState()
    val isBookIdTaken by vm.isBookIdTaken.collectAsState()

    // Handle import result
    LaunchedEffect(importResult) {
        val result = importResult ?: return@LaunchedEffect
        if (result.isSuccess) {
            Toast.makeText(context, context.getString(R.string.import_success), Toast.LENGTH_SHORT)
                .show()
            onImportSuccess() // Trigger reload
            onDismiss()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.import_failed, result.exceptionOrNull()?.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
        vm.clearResult()
    }

    LaunchedEffect(Unit) { vm.setupData(context) }

    // File picker
    val fileLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val fileName = uri.lastPathSegment ?: uri.toString()
                vm.setSelectedFile(uri, fileName.substringAfterLast('/'))
            }
        }

    // Selection dialogs state
    var showBookPicker by remember { mutableStateOf(false) }
    var showAuthorPicker by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    if (showBookPicker) {
        SearchPickerDialog(
            title = stringResource(R.string.pilih_buku),
            items = books.map { it.id to context.getString(R.string.item_with_id, it.name, it.id) },
            onSelect = { id ->
                val book = books.firstOrNull { it.id == id }
                vm.setSelectedBookId(id, book?.name, book?.archive, book?.categoryId)
                showBookPicker = false
            },
            onDismiss = { showBookPicker = false },
        )
    }

    if (showAuthorPicker) {
        SearchPickerDialog(
            title = stringResource(R.string.pilih_author),
            items = authors.map { it.id to context.getString(R.string.item_with_id, it.name, it.id) },
            onSelect = { id ->
                vm.setSelectedAuthorId(id)
                showAuthorPicker = false
            },
            onDismiss = { showAuthorPicker = false },
        )
    }

    if (showCategoryPicker) {
        SearchPickerDialog(
            title = stringResource(R.string.pilih_kategori),
            items = categories.map { it.id to it.name },
            onSelect = { id ->
                vm.setCategoryId(id)
                showCategoryPicker = false
            },
            onDismiss = { showCategoryPicker = false },
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Text(
                    stringResource(R.string.import_help_title),
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(stringResource(R.string.import_help_desc))
            },
            confirmButton = {
                Button(
                    onClick = {
                        uriHandler.openUri(context.getString(R.string.converter_url))
                        showHelpDialog = false
                    },
                    shape = RoundedCornerShape(30.dp)
                ) {
                    Text(stringResource(R.string.import_help_open_web))
                }
            },
            dismissButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text(stringResource(R.string.batal))
                }
            },
            shape = RoundedCornerShape(30.dp),
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (importMode == ImportMode.CHANGE_ID) stringResource(R.string.ubah_id_buku) else stringResource(
                            R.string.import_buku_offline
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.tutup)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = stringResource(R.string.search_action_help)
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        bottomBar = {
            Surface(
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = {
                            vm.performImport(context, annotationManager) {
                                // callback after backend success, but before state update
                            }
                        },
                        enabled = !isImporting && !isLoadingData && isValid,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(30.dp),
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (importMode == ImportMode.CHANGE_ID) stringResource(R.string.ubah_id_sekarang) else stringResource(
                                R.string.import_sekarang
                            )
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (isLoadingData) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.memuat_data),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // File picker header (tidak ditampilkan di mode Change ID)
                if (importMode != ImportMode.CHANGE_ID) {
                    item {
                        ImportSectionCard(title = stringResource(R.string.file_sumber)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = selectedFileName
                                        ?: stringResource(R.string.belum_dipilih),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color =
                                        if (selectedFileName != null) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedButton(
                                    onClick = { fileLauncher.launch(arrayOf("*/*")) },
                                    shape = RoundedCornerShape(30.dp),
                                    border =
                                        BorderStroke(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.2f
                                            ),
                                        ),
                                ) { Text(stringResource(R.string.pilih_file)) }
                            }
                        }
                    }
                }

                // Book Information Section
                item {
                    ImportSectionCard(title = stringResource(R.string.informasi_buku)) {
                        // Mode segmented control
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            ImportMode.entries.forEachIndexed { i, mode ->
                                SegmentedButton(
                                    selected = importMode == mode,
                                    onClick = { vm.setImportMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        i,
                                        ImportMode.entries.size
                                    ),
                                    icon = {},
                                ) {
                                    Text(
                                        text =
                                            when (mode) {
                                                ImportMode.NEW -> stringResource(R.string.baru)
                                                ImportMode.REPLACE -> stringResource(R.string.ganti)
                                                ImportMode.CHANGE_ID -> stringResource(R.string.ubah_id)
                                            },
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }

                        RowDivider()

                        // Book ID field (mode NEW)
                        if (importMode == ImportMode.NEW) {
                            ImportFormRow(label = stringResource(R.string.book_id_baru)) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    SearchTextField(
                                        value = customBookIdText,
                                        onValueChange = { vm.setCustomBookIdText(it) },
                                        placeholder = stringResource(R.string.placeholder_bkid),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = isBookIdTaken,
                                        modifier = Modifier.width(160.dp),
                                    )
                                    if (isBookIdTaken) {
                                        Text(
                                            text = stringResource(R.string.id_sudah_dipakai),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                }
                            }
                            RowDivider()
                        }

                        // Select book (mode REPLACE / CHANGE_ID)
                        if (importMode == ImportMode.REPLACE || importMode == ImportMode.CHANGE_ID) {
                            ImportFormRow(label = stringResource(R.string.pilih_buku)) {
                                PickerButton(
                                    label =
                                        if (selectedBookId != null) {
                                            books.firstOrNull { it.id == selectedBookId }?.let {
                                                stringResource(
                                                    R.string.item_with_id,
                                                    it.name,
                                                    it.id
                                                )
                                            } ?: stringResource(
                                                R.string.id_format,
                                                selectedBookId!!
                                            )
                                        } else {
                                            stringResource(R.string.ketuk_untuk_memilih)
                                        },
                                    onClick = { showBookPicker = true },
                                )
                            }
                            RowDivider()
                        }

                        // New ID field (mode CHANGE_ID)
                        if (importMode == ImportMode.CHANGE_ID) {
                            ImportFormRow(label = stringResource(R.string.id_baru)) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    SearchTextField(
                                        value = customBookIdText,
                                        onValueChange = { vm.setCustomBookIdText(it) },
                                        placeholder = stringResource(R.string.placeholder_bkid),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = isBookIdTaken,
                                        modifier = Modifier.width(160.dp),
                                    )
                                    if (isBookIdTaken) {
                                        Text(
                                            text = stringResource(R.string.id_sudah_dipakai),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // Metadata fields (tidak untuk CHANGE_ID)
                        if (importMode != ImportMode.CHANGE_ID) {
                            ImportFormRow(label = stringResource(R.string.nama_buku)) {
                                SearchTextField(
                                    value = bookName,
                                    onValueChange = { vm.setBookName(it) },
                                    placeholder = stringResource(R.string.placeholder_book_name),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            RowDivider()

                            ImportFormRow(label = stringResource(R.string.kategori)) {
                                PickerButton(
                                    label =
                                        categories.firstOrNull { it.id == categoryId }?.name
                                            ?: stringResource(R.string.pilih_kategori_placeholder),
                                    onClick = { showCategoryPicker = true },
                                )
                            }
                            RowDivider()

                            // Archive ID & Multi-Bahasa digabung dalam satu row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                ImportFormRow(
                                    label = stringResource(R.string.archive_id_label),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        IconButton(
                                            onClick = { vm.setArchiveId(archiveId - 1) },
                                            enabled = archiveId > 1,
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Text(
                                                "−",
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                        }
                                        Text(
                                            "$archiveId",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        IconButton(
                                            onClick = { vm.setArchiveId(archiveId + 1) },
                                            enabled = archiveId < 20,
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Text(
                                                "+",
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                        }
                                    }
                                }
                                ImportFormRow(
                                    label = stringResource(R.string.multi_bahasa),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Switch(
                                        checked = isMultiLanguage,
                                        onCheckedChange = { vm.setIsMultiLanguage(it) })
                                }
                            }
                            RowDivider()

                            ImportFormRow(label = stringResource(R.string.edisi_betaka)) {
                                SearchTextField(
                                    value = betaka,
                                    onValueChange = { vm.setBetaka(it) },
                                    placeholder = stringResource(R.string.opsional),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            RowDivider()

                            ImportFormRow(label = stringResource(R.string.info_inf)) {
                                SearchTextField(
                                    value = inf,
                                    onValueChange = { vm.setInf(it) },
                                    placeholder = stringResource(R.string.opsional),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            RowDivider()

                            ImportFormRow(label = stringResource(R.string.nama_tafseer)) {
                                SearchTextField(
                                    value = tafseerNam,
                                    onValueChange = { vm.setTafseerNam(it) },
                                    placeholder = stringResource(R.string.opsional),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            RowDivider()

                            ImportFormRow(label = stringResource(R.string.versi)) {
                                SearchTextField(
                                    value = bVerText,
                                    onValueChange = { vm.setBVerText(it) },
                                    placeholder = "1",
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(100.dp),
                                )
                            }
                        }
                    }
                }

                // Author Section (tidak untuk CHANGE_ID)
                if (importMode != ImportMode.CHANGE_ID) {
                    item {
                        ImportSectionCard(title = stringResource(R.string.informasi_author)) {
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = authorMode == AuthorMode.EXISTING,
                                    onClick = { vm.setAuthorMode(AuthorMode.EXISTING) },
                                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                                    icon = {},
                                ) {
                                    Text(
                                        stringResource(R.string.author_lama),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                SegmentedButton(
                                    selected = authorMode == AuthorMode.NEW,
                                    onClick = { vm.setAuthorMode(AuthorMode.NEW) },
                                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                                    icon = {},
                                ) {
                                    Text(
                                        stringResource(R.string.author_baru),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }

                            RowDivider()

                            if (authorMode == AuthorMode.EXISTING) {
                                ImportFormRow(label = stringResource(R.string.pilih_author)) {
                                    PickerButton(
                                        label =
                                            if (selectedAuthorId != null) {
                                                authors.firstOrNull { it.id == selectedAuthorId }
                                                    ?.let {
                                                        stringResource(
                                                            R.string.item_with_id,
                                                            it.name,
                                                            it.id
                                                        )
                                                    } ?: stringResource(
                                                    R.string.id_format,
                                                    selectedAuthorId!!
                                                )
                                            } else {
                                                stringResource(R.string.ketuk_untuk_memilih)
                                            },
                                        onClick = { showAuthorPicker = true },
                                    )
                                }
                            } else {
                                ImportFormRow(label = stringResource(R.string.id_author_baru)) {
                                    Text(
                                        "${maxAuthid + 1}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                RowDivider()

                                ImportFormRow(label = stringResource(R.string.nama_author)) {
                                    SearchTextField(
                                        value = authorName,
                                        onValueChange = { vm.setAuthorName(it) },
                                        placeholder = stringResource(R.string.placeholder_author_name),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                RowDivider()

                                ImportFormRow(label = stringResource(R.string.info_author)) {
                                    SearchTextField(
                                        value = authorInf,
                                        onValueChange = { vm.setAuthorInf(it) },
                                        placeholder = stringResource(R.string.opsional),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                RowDivider()

                                ImportFormRow(label = stringResource(R.string.nama_lengkap_lng)) {
                                    SearchTextField(
                                        value = authorLng,
                                        onValueChange = { vm.setAuthorLng(it) },
                                        placeholder = stringResource(R.string.opsional),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                RowDivider()

                                ImportFormRow(label = stringResource(R.string.tahun_wafat_h)) {
                                    SearchTextField(
                                        value = authorHigriD,
                                        onValueChange = { vm.setAuthorHigriD(it) },
                                        placeholder = stringResource(R.string.placeholder_higri_d),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                RowDivider()

                                ImportFormRow(label = stringResource(R.string.versi)) {
                                    SearchTextField(
                                        value = oVerText,
                                        onValueChange = { vm.setOVerText(it) },
                                        placeholder = "1",
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.width(100.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

// ── Helper Composables ──────────────────────────────────────────────────────

@Composable
private fun ImportSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ImportFormRow(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

/**
 * Divider tipis pemisah antar row di dalam card, dengan inset kiri-kanan
 * agar tidak menempel ke tepi card.
 */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
    )
}

@Composable
private fun PickerButton(
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(30.dp),
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
            ),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchPickerDialog(
    title: String,
    items: List<Pair<Int, String>>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered =
        remember(query, items) {
            if (query.isBlank()) {
                items
            } else {
                items.filter { it.second.contains(query, ignoreCase = true) }
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column {
                SearchTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.search_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                        items(filtered) { (id, label) ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(id) }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.batal)) }
        },
        shape = RoundedCornerShape(30.dp),
    )
}
