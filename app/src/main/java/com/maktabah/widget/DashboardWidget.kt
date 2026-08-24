package com.maktabah.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.maktabah.MainActivity
import com.maktabah.database.AnnotationManager
import com.maktabah.database.HistoryDatabaseManager
import com.maktabah.manager.LibraryDataManager
import com.maktabah.models.Annotation
import com.maktabah.models.ReadingEntry
import kotlinx.coroutines.runBlocking
import java.io.File
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.LocalContext
import androidx.glance.color.ColorProvider

class DashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DashboardWidget()
}

class DashboardWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            androidx.compose.ui.unit.DpSize(100.dp, 100.dp),
            androidx.compose.ui.unit.DpSize(200.dp, 150.dp),
            androidx.compose.ui.unit.DpSize(250.dp, 250.dp)
        )
    )

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val filesDir = context.filesDir
        val mainDbFile = File(filesDir, "main.sqlite")
        val annotationsDbFile = File(filesDir, "annotations.sqlite")

        val dataManager = LibraryDataManager(mainDbFile)
        dataManager.loadData()

        val historyDbFile = File(filesDir, "history.sqlite")
        val historyManager = HistoryDatabaseManager(historyDbFile)
        val annotationManager = AnnotationManager(annotationsDbFile)

        val (entries, order) = historyManager.loadFromDatabase()
        val historyList = order.mapNotNull { bookId ->
            entries.find { it.bookId == bookId }
        }.take(3)

        val annotationsList = runBlocking { annotationManager.getAllAnnotations() }.take(3)

        provideContent {
            val prefs = currentState<androidx.datastore.preferences.core.Preferences>()
            val selectedTab = prefs[TAB_KEY] ?: 0

            WidgetContent(
                selectedTab = selectedTab,
                historyList = historyList,
                annotationsList = annotationsList,
                dataManager = dataManager
            )
        }
    }

    @Composable
    private fun WidgetContent(
        selectedTab: Int,
        historyList: List<ReadingEntry>,
        annotationsList: List<Annotation>,
        dataManager: LibraryDataManager
    ) {
        val isSepiaDark = false // You can make this dynamic if needed, default to light sepia for simplicity or get config

        // Use a neutral Sepia scheme
        val bg = Color(0xFFEDD9B8)
        val onBg = Color.Black
        val primary = Color(0xFF9C7A4E)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bg)
                .padding(8.dp)
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabButton(
                    text = "History",
                    isSelected = selectedTab == 0,
                    tabIndex = 0,
                    primaryColor = primary,
                    textColor = onBg,
                    modifier = GlanceModifier.defaultWeight()
                )
                Spacer(modifier = GlanceModifier.width(8.dp))
                TabButton(
                    text = "Anotasi",
                    isSelected = selectedTab == 1,
                    tabIndex = 1,
                    primaryColor = primary,
                    textColor = onBg,
                    modifier = GlanceModifier.defaultWeight()
                )
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(Color(0xFFE5D9C2)) // slightly darker sepia for list bg
            ) {
                if (selectedTab == 0) {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(historyList) { entry ->
                            val bookName = dataManager.booksById[entry.bookId]?.name ?: "Unknown Book"
                            HistoryItem(entry, bookName, onBg)
                        }
                    }
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(annotationsList) { annotation ->
                            val bookName = dataManager.booksById[annotation.bkId]?.name ?: "Unknown Book"
                            AnnotationItem(annotation, bookName, onBg)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TabButton(
        text: String,
        isSelected: Boolean,
        tabIndex: Int,
        primaryColor: Color,
        textColor: Color,
        modifier: GlanceModifier
    ) {
        Box(
            modifier = modifier
                .background(if (isSelected) primaryColor else Color.Transparent)
                .padding(4.dp)
                .clickable(actionRunCallback<SwitchTabAction>(
                    androidx.glance.action.actionParametersOf(
                        TAB_PARAM to tabIndex
                    )
                )),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = TextStyle(
                    color = ColorProvider(day = if (isSelected) Color.White else textColor, night = if (isSelected) Color.White else textColor),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp
                )
            )
        }
    }

    @Composable
    private fun HistoryItem(entry: ReadingEntry, bookName: String, textColor: Color) {
        val intent = Intent(LocalContext.current, MainActivity::class.java).apply {
            action = "ACTION_OPEN_BOOK"
            putExtra("bookId", entry.bookId)
            putExtra("contentId", entry.lastContentId ?: -1)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(8.dp)
                .clickable(actionStartActivity(intent))
        ) {
            Text(
                text = bookName,
                style = TextStyle(
                    color = ColorProvider(day = textColor, night = textColor),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                ),
                maxLines = 1
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(Color.Gray)) {}
        }
    }

    @Composable
    private fun AnnotationItem(annotation: Annotation, bookName: String, textColor: Color) {
        val intent = Intent(LocalContext.current, MainActivity::class.java).apply {
            action = "ACTION_OPEN_BOOK"
            putExtra("bookId", annotation.bkId)
            putExtra("contentId", annotation.contentId)
            putExtra("flashLoc", annotation.rangeLocation)
            putExtra("flashLen", annotation.rangeLength)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(8.dp)
                .clickable(actionStartActivity(intent))
        ) {
            Text(
                text = bookName,
                style = TextStyle(
                    color = ColorProvider(day = textColor, night = textColor),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                ),
                maxLines = 1
            )
            Spacer(modifier = GlanceModifier.height(2.dp))
            Text(
                text = annotation.note ?: annotation.context,
                style = TextStyle(
                    color = ColorProvider(day = textColor, night = textColor),
                    fontSize = 12.sp
                ),
                maxLines = 1
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Box(modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(Color.Gray)) {}
        }
    }

    companion object {
        val TAB_KEY = intPreferencesKey("selected_tab")
        val TAB_PARAM = androidx.glance.action.ActionParameters.Key<Int>("tab_index")
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
            prefs.toMutablePreferences().apply {
                this[DashboardWidget.TAB_KEY] = tabIndex
            }
        }
        DashboardWidget().update(context, glanceId)
    }
}
