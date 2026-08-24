package com.maktabah.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.maktabah.MainActivity
import com.maktabah.R
import com.maktabah.database.AnnotationManager
import com.maktabah.database.HistoryDatabaseManager
import com.maktabah.manager.LibraryDataManager
import com.maktabah.models.Annotation
import com.maktabah.models.ReadingEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DashboardWidget()
}

object WidgetTheme {
    val background = ColorProvider(day = Color(0xFFEDD9B8), night = Color(0xFF26231D))
    val onBackground = ColorProvider(day = Color.Black, night = Color.White)
    val primary = ColorProvider(day = Color(0xFF9C7A4E), night = Color(0xFFE9C099))
    val onPrimary = ColorProvider(day = Color.White, night = Color.Black)
    val primaryContainer = ColorProvider(day = Color(0xFFEFE3CC), night = Color(0xFF36322A))
    val divider = ColorProvider(day = Color(0xFFD1C4AC), night = Color(0xFF4C4538))
}

class DashboardWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (historyList, annotationsList, bookNames) = withContext(Dispatchers.IO) {
            try {
                val filesDir = context.filesDir
                val historyDbFile = File(filesDir, "History.sqlite")
                val annotationsDbFile = File(filesDir, "annotations.sqlite")
                val mainDbFile = File(filesDir, "main.sqlite")

                if (!mainDbFile.exists()) {
                    return@withContext Triple(emptyList(), emptyList(), emptyMap<Int, String>())
                }

                val historyManager = HistoryDatabaseManager(historyDbFile)
                val annotationManager = AnnotationManager(annotationsDbFile)
                val dataManager = LibraryDataManager(mainDbFile)

                val (entries, order) = historyManager.loadFromDatabase()
                val historyMap = entries.associateBy { it.bookId }
                val historyFromOrder = order.mapNotNull { historyMap[it] }
                val otherHistory = entries
                    .filter { it.lastOpenedAt != null && it.bookId !in order }
                    .sortedByDescending { it.lastOpenedAt ?: 0L }
                val history = (historyFromOrder + otherHistory).ifEmpty {
                    entries.filter { it.lastOpenedAt != null }.sortedByDescending { it.lastOpenedAt ?: 0L }
                }.take(30)

                val annotations = annotationManager.getLatestAnnotations(30)

                val bookIds = (history.map { it.bookId } + annotations.map { it.bkId }).distinct()
                val names = dataManager.getBookNames(bookIds)

                Triple(history, annotations, names)
            } catch (e: Exception) {
                android.util.Log.e("DashboardWidget", "Error loading widget data", e)
                Triple(emptyList<ReadingEntry>(), emptyList<Annotation>(), emptyMap<Int, String>())
            }
        }

        provideContent {
            val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
            val selectedTab = prefs[TAB_KEY] ?: 0

            WidgetContent(
                selectedTab = selectedTab,
                historyList = historyList,
                annotationsList = annotationsList,
                bookNames = bookNames
            )
        }
    }

    @Composable
    private fun WidgetContent(
        selectedTab: Int,
        historyList: List<ReadingEntry>,
        annotationsList: List<Annotation>,
        bookNames: Map<Int, String>
    ) {
        val size = LocalSize.current
        val containerPadding = 8.dp
        val headerHeight = 32.dp
        val headerSpacing = 8.dp
        val dividerHeight = 1.dp
        val availableHeight = size.height - (containerPadding * 2) - headerHeight - (headerSpacing * 2) - dividerHeight

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetTheme.background)
                .padding(containerPadding)
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().height(headerHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val context = LocalContext.current
                val appIntent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                Box(
                    modifier = GlanceModifier
                        .size(headerHeight)
                        .clickable(actionStartActivity(appIntent)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_launcher_monochrome),
                        contentDescription = "Maktabah",
                        modifier = GlanceModifier.size(48.dp).padding(top = 1.dp),
                        colorFilter = ColorFilter.tint(WidgetTheme.onBackground)
                    )
                }

                Spacer(modifier = GlanceModifier.width(16.dp))

                TabButton(
                    iconRes = R.drawable.clock_fill,
                    contentDescription = "History",
                    isSelected = selectedTab == 0,
                    tabIndex = 0,
                    modifier = GlanceModifier.defaultWeight()
                )
                TabButton(
                    iconRes = R.drawable.ic_quote_closing,
                    contentDescription = "Anotasi",
                    isSelected = selectedTab == 1,
                    tabIndex = 1,
                    modifier = GlanceModifier.defaultWeight()
                )
            }

            Spacer(modifier = GlanceModifier.height(headerSpacing))
			Box(
				modifier = GlanceModifier
					.fillMaxWidth()
					.height(1.dp)
					.background(WidgetTheme.divider)
			) {}
			Spacer(modifier = GlanceModifier.height(headerSpacing))

            if (selectedTab == 0) {
                if (historyList.isEmpty()) {
                    EmptyState("Belum ada riwayat")
                } else {
                    val itemSlotHeight = 34.dp
                    val itemSpacing = 6.dp
                    val maxItems = maxOf(1, ((availableHeight + itemSpacing) / itemSlotHeight).toInt())
                    val itemsToShow = historyList.take(maxItems)

                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(itemsToShow.size) { index ->
                            val entry = itemsToShow[index]
                            Box(
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .padding(bottom = if (index < itemsToShow.size - 1) itemSpacing else 0.dp)
                            ) {
                                HistoryItem(
                                    entry = entry,
                                    bookName = bookNames[entry.bookId] ?: "Buku Tidak Dikenal"
                                )
                            }
                        }
                    }
                }
            } else {
                if (annotationsList.isEmpty()) {
                    EmptyState("Belum ada anotasi")
                } else {
                    val itemSlotHeight = 58.dp
                    val itemSpacing = 6.dp
                    val maxItems = maxOf(1, ((availableHeight + itemSpacing) / itemSlotHeight).toInt())
                    val itemsToShow = annotationsList.take(maxItems)

                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(itemsToShow.size) { index ->
                            val annotation = itemsToShow[index]
                            Box(
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .padding(bottom = if (index < itemsToShow.size - 1) itemSpacing else 0.dp)
                            ) {
                                AnnotationItem(
                                    annotation = annotation,
                                    bookName = bookNames[annotation.bkId] ?: "Buku Tidak Dikenal"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TabButton(
        iconRes: Int,
        contentDescription: String,
        isSelected: Boolean,
        tabIndex: Int,
        modifier: GlanceModifier
    ) {
        Box(
            modifier = modifier
                .height(32.dp)
                .cornerRadius(12.dp)
                .background(if (isSelected) WidgetTheme.primary else ColorProvider(day = Color.Transparent, night = Color.Transparent))
                .clickable(actionRunCallback<SwitchTabAction>(
                    androidx.glance.action.actionParametersOf(
                        TAB_PARAM to tabIndex
                    )
                )),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = contentDescription,
                modifier = GlanceModifier.size(18.dp),
                colorFilter = ColorFilter.tint(
                    if (isSelected) WidgetTheme.onPrimary else WidgetTheme.onBackground
                )
            )
        }
    }

    @Composable
    private fun HistoryItem(
        entry: ReadingEntry,
        bookName: String
    ) {
        val intent = Intent(LocalContext.current, MainActivity::class.java).apply {
            action = "ACTION_OPEN_BOOK"
            putExtra("bookId", entry.bookId)
            putExtra("contentId", entry.lastContentId ?: -1)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(28.dp)
                .cornerRadius(12.dp)
                .background(WidgetTheme.primaryContainer)
                .clickable(actionStartActivity(intent))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(
                text = bookName,
                style = TextStyle(
                    color = WidgetTheme.onBackground,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    textAlign = TextAlign.End
                ),
            )
        }
    }

    @Composable
    private fun AnnotationItem(
        annotation: Annotation,
        bookName: String
    ) {
        val intent = Intent(LocalContext.current, MainActivity::class.java).apply {
            action = "ACTION_OPEN_BOOK"
            putExtra("bookId", annotation.bkId)
            putExtra("contentId", annotation.contentId)
            putExtra("flashLoc", annotation.rangeLocation)
            putExtra("flashLen", annotation.rangeLength)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .cornerRadius(10.dp)
                .background(WidgetTheme.primaryContainer)
                .clickable(actionStartActivity(intent))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = bookName,
                style = TextStyle(
                    color = WidgetTheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End
                ),
                maxLines = 1
            )
            Spacer(modifier = GlanceModifier.height(2.dp))
            Text(
                text = annotation.note?.takeIf { it.isNotBlank() } ?: annotation.context,
                style = TextStyle(
                    color = WidgetTheme.onBackground,
                    fontSize = 11.sp,
                    textAlign = TextAlign.End
                ),
                maxLines = 1
            )
        }
    }

    @Composable
    private fun EmptyState(message: String) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .cornerRadius(12.dp)
                .background(WidgetTheme.primaryContainer)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message,
                style = TextStyle(
                    color = WidgetTheme.onBackground,
                    fontSize = 12.sp
                )
            )
        }
    }

    companion object {
        val TAB_KEY = intPreferencesKey("selected_tab")
        val TAB_PARAM = ActionParameters.Key<Int>("tab_index")

        suspend fun updateWidget(context: Context) {
            try {
                DashboardWidget().updateAll(context)
            } catch (e: Exception) {
                android.util.Log.e("DashboardWidget", "Failed to update widget", e)
            }
        }
    }
}

class SwitchTabAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val tabIndex = parameters[DashboardWidget.TAB_PARAM] ?: 0
        androidx.glance.appwidget.state.updateAppWidgetState(context, glanceId) { prefs ->
            prefs[DashboardWidget.TAB_KEY] = tabIndex
        }
        DashboardWidget().update(context, glanceId)
    }
}
