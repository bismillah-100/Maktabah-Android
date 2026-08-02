package com.maktabah.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object ColorPreferences {
    private const val PREFS_NAME = "color_prefs"
    private const val KEY_RECENT_COLORS = "recent_colors"
    
    val DEFAULT_COLORS = listOf(
        "#FFFF00", // Yellow
        "#00FF00", // Green
        "#FF0000", // Red
        "#0000FF", // Blue
        "#FFA500"  // Orange
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getRecentColors(context: Context): List<String> {
        val prefs = getPrefs(context)
        val saved = prefs.getString(KEY_RECENT_COLORS, null)
        return if (saved == null) {
            // Initial registration of default colors
            saveRecentColors(context, DEFAULT_COLORS)
            DEFAULT_COLORS
        } else {
            saved.split(",").filter { it.isNotBlank() }
        }
    }

    private fun saveRecentColors(context: Context, colors: List<String>) {
        val prefs = getPrefs(context)
        prefs.edit {
            putString(KEY_RECENT_COLORS, colors.joinToString(","))
        }
    }

    /**
     * Adds or moves a color to the front (index 0) of the recent colors list.
     * Keeps only the 5 most recent colors.
     */
    fun selectColor(context: Context, hex: String) {
        val current = getRecentColors(context).toMutableList()
        val upperHex = hex.uppercase()
        
        // Remove if exists to move it to the front
        current.remove(upperHex)
        
        // Add to the front
        current.add(0, upperHex)
        
        // Keep only top 5
        val result = current.take(5)
        saveRecentColors(context, result)
    }

    fun getLatestColor(context: Context): String {
        return getRecentColors(context).firstOrNull() ?: DEFAULT_COLORS.first()
    }
}
