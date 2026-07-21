package com.maktabah.database

import com.maktabah.models.Annotation
import org.json.JSONArray
import org.json.JSONObject

object AnnotationJsonSerializer {
    private const val VERSION = 1

    fun encodeToJson(annotations: List<Annotation>): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("count", annotations.size)

        val array = JSONArray()
        for (ann in annotations) {
            val obj = JSONObject()
            obj.put("bkId", ann.bkId)
            obj.put("contentId", ann.contentId)
            obj.put("colorHex", ann.colorHex)
            if (ann.note != null) obj.put("note", ann.note)
            obj.put("type", ann.type)
            obj.put("createdAt", ann.createdAt)
            obj.put("page", ann.page)
            obj.put("context", ann.context)
            obj.put("rangeLocation", ann.rangeLocation)
            obj.put("rangeLength", ann.rangeLength)
            obj.put("rangeDiacLocation", ann.rangeDiacLocation)
            obj.put("rangeDiacLength", ann.rangeDiacLength)
            obj.put("part", ann.part)
            obj.put("tags", ann.tags)
            if (ann.ckRecordId != null) obj.put("ckRecordId", ann.ckRecordId)
            if (ann.lastModified != null) obj.put("lastModified", ann.lastModified)
            array.put(obj)
        }
        root.put("annotations", array)
        return root.toString(2)
    }

    fun decodeFromJson(jsonString: String): List<Annotation> {
        val result = mutableListOf<Annotation>()
        val root = JSONObject(jsonString)

        val array = if (root.has("annotations")) {
            root.getJSONArray("annotations")
        } else if (root.has("data")) {
            root.getJSONArray("data")
        } else {
            JSONArray(jsonString)
        }

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val bkId = obj.optInt("bkId", 0)
            val contentId = obj.optInt("contentId", 0)
            val colorHex = obj.optString("colorHex", "#FFFF00")
            val note = if (obj.has("note") && !obj.isNull("note")) obj.optString("note") else null
            val type = obj.optInt("type", 0)
            val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            val page = obj.optInt("page", 0)
            val contextText = obj.optString("context", "")
            val rangeLocation = obj.optInt("rangeLocation", 0)
            val rangeLength = obj.optInt("rangeLength", 0)
            val rangeDiacLocation = obj.optInt("rangeDiacLocation", 0)
            val rangeDiacLength = obj.optInt("rangeDiacLength", 0)
            val part = obj.optInt("part", 0)
            val tags = obj.optString("tags", "")
            val ckRecordId = if (obj.has("ckRecordId") && !obj.isNull("ckRecordId")) obj.optString("ckRecordId") else null
            val lastModified = if (obj.has("lastModified") && !obj.isNull("lastModified")) obj.optLong("lastModified") else null

            result.add(
                Annotation(
                    bkId = bkId,
                    contentId = contentId,
                    colorHex = colorHex,
                    note = note,
                    type = type,
                    createdAt = createdAt,
                    page = page,
                    context = contextText,
                    rangeLocation = rangeLocation,
                    rangeLength = rangeLength,
                    rangeDiacLocation = rangeDiacLocation,
                    rangeDiacLength = rangeDiacLength,
                    part = part,
                    tags = tags,
                    ckRecordId = ckRecordId,
                    lastModified = lastModified
                )
            )
        }

        return result
    }
}
