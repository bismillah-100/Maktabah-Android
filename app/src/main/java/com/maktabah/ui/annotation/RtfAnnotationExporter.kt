package com.maktabah.ui.annotation

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import com.maktabah.R
import com.maktabah.manager.LibraryDataManager
import com.maktabah.models.Annotation
import com.maktabah.models.AnnotationGroup
import com.maktabah.utils.convertToArabicDigits
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RtfAnnotationExporter {

    fun generateRtfData(
        groups: List<AnnotationGroup>,
        dataManager: LibraryDataManager,
        selectedAnnotationIds: Set<Long>? = null,
    ): ByteArray {
        val colorMap = mutableMapOf<String, Int>()
        var nextColorIdx = 4 // 1: black, 2: secondary, 3: tertiary

        // Pre-collect unique colors
        for (group in groups) {
            for (ann in group.annotations) {
                if (selectedAnnotationIds != null && ann.id != null && !selectedAnnotationIds.contains(ann.id)) {
                    continue
                }
                val hex = ann.colorHex.lowercase()
                if (!colorMap.containsKey(hex)) {
                    colorMap[hex] = nextColorIdx++
                }
            }
        }

        val sb = StringBuilder()
        sb.append("{\\rtf1\\ansi\\ansicpg1252\\uc1\\deff0\n")
        sb.append("{\\fonttbl{\\f0\\fnil\\fcharset178 System;}}\n")

        // Build Color Table
        sb.append("{\\colortbl ;")
        sb.append("\\red0\\green0\\blue0;") // Index 1: Black
        sb.append("\\red102\\green102\\blue102;") // Index 2: Secondary Gray
        sb.append("\\red142\\green142\\blue147;") // Index 3: Tertiary Gray

        for ((hex, _) in colorMap) {
            val colorInt = try {
                val formattedHex = if (hex.startsWith("#")) hex else "#$hex"
                formattedHex.toColorInt()
            } catch (_: Exception) {
                0xFFFF00.toInt()
            }
            val r = (colorInt shr 16) and 0xFF
            val g = (colorInt shr 8) and 0xFF
            val b = colorInt and 0xFF
            sb.append("\\red$r\\green$g\\blue$b;")
        }
        sb.append("}\n")

        val now = System.currentTimeMillis()
        val dateFormatter = SimpleDateFormat("EEEE, dd/MM/yy", Locale.getDefault())

        for (group in groups) {
            val activeAnns = if (selectedAnnotationIds != null) {
                group.annotations.filter { it.id != null && selectedAnnotationIds.contains(it.id) }
            } else {
                group.annotations
            }

            if (activeAnns.isEmpty()) continue

            // Header Buku / Folder (Title: 18pt Bold, Right-aligned RTL)
            sb.append("\\qr\\rtlpar\\sb80\\sa160\\fs36\\b\\cf1 ")
            sb.append(escapeRtf(group.title))
            sb.append("\\b0\\par\n")

            for (ann in activeAnns) {
                val colorIdx = colorMap[ann.colorHex.lowercase()] ?: 1

                // Context text (14pt, Right-aligned RTL)
                sb.append("\\qr\\rtlpar\\sb80\\sa160\\fs28\\cf1 ")
                if (ann.type == 0) { // Highlight
                    sb.append("\\highlight$colorIdx\\cb$colorIdx ")
                } else if (ann.type == 1) { // Underline
                    sb.append("\\ul\\ulc$colorIdx ")
                }

                sb.append(escapeRtf(ann.context))
                sb.append("\\highlight0\\cb0\\ul0\\par\n")

                // Note (13pt, Secondary Color, in quotes)
                if (!ann.note.isNullOrEmpty()) {
                    sb.append("\\qr\\rtlpar\\sb80\\sa160\\fs26\\cf2 ")
                    val noteFormatted = "\"${ann.note}\""
                    sb.append(escapeRtf(noteFormatted))
                    sb.append("\\par\n")
                }

                // Metadata (12pt, Tertiary Color)
                val targetMillis = if (ann.createdAt in 1L..9999999999L) ann.createdAt * 1000L else ann.createdAt
                val targetDate = Date(if (targetMillis > 0L) targetMillis else now)
                val dateString = dateFormatter.format(targetDate)

                val kitabName = dataManager.booksById[ann.bkId]?.name ?: "<Unknown Book>"
                val partArb = if (ann.part > 0) ann.part.toString().convertToArabicDigits() else "-"
                val pageArb = if (ann.page > 0) ann.page.toString().convertToArabicDigits() else "-"
                val tagsStr = if (ann.tags.isNotEmpty()) {
                    ann.tags.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(" ") { " -- $it" }
                } else ""

                val metaText = "$kitabName • الجزء: $partArb • الصفحة: $pageArb $tagsStr\n$dateString"
                sb.append("\\qr\\rtlpar\\sb80\\sa160\\fs24\\cf3 ")
                sb.append(escapeRtf(metaText))
                sb.append("\\par\\par\\par\\par\n")
            }
        }

        sb.append("}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun exportAndShareRtf(
        context: Context,
        groups: List<AnnotationGroup>,
        dataManager: LibraryDataManager,
        selectedAnnotationIds: Set<Long>? = null,
    ): Boolean {
        return try {
            val rtfBytes = generateRtfData(groups, dataManager, selectedAnnotationIds)
            val file = File(context.cacheDir, "annotations_export.rtf")
            file.writeBytes(rtfBytes)

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/rtf"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.annotations_menu_export_rtf))
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun escapeRtf(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val code = ch.code
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '{' -> sb.append("\\{")
                ch == '}' -> sb.append("\\}")
                ch == '\n' -> sb.append("\\par ")
                code in 32..126 -> sb.append(ch)
                else -> {
                    val shortVal = if (code > 32767) code - 65536 else code
                    sb.append("\\u$shortVal?")
                }
            }
        }
        return sb.toString()
    }
}
