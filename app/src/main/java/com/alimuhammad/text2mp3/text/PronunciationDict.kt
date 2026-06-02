package com.alimuhammad.text2mp3.text

import android.content.Context
import org.json.JSONObject

class PronunciationDict private constructor(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("piper_pronunciation", Context.MODE_PRIVATE)

    private val entries = LinkedHashMap<String, String>()

    init { load() }

    @Synchronized
    fun all(): Map<String, String> = LinkedHashMap(entries)

    @Synchronized
    fun put(from: String, to: String) {
        val key = from.trim()
        if (key.isEmpty()) return
        entries[key.lowercase()] = to.trim()
        save()
    }

    @Synchronized
    fun remove(from: String) {
        entries.remove(from.trim().lowercase())
        save()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        save()
    }

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    @Synchronized
    fun apply(text: String): String {
        if (entries.isEmpty()) return text
        var result = text

        for ((from, to) in entries.entries.sortedByDescending { it.key.length }) {
            if (from.isEmpty()) continue
            val pattern = "(?i)\\b" + Regex.escape(from) + "\\b"
            result = try {
                result.replace(Regex(pattern), Regex.escapeReplacement(to))
            } catch (_: Exception) {
                result
            }
        }
        return result
    }

    private fun load() {
        entries.clear()
        val raw = prefs.getString("dict_json", null) ?: return
        try {
            val obj = JSONObject(raw)
            for (k in obj.keys()) entries[k] = obj.optString(k, "")
        } catch (_: Exception) {  }
    }

    private fun save() {
        val obj = JSONObject()
        for ((k, v) in entries) obj.put(k, v)
        prefs.edit().putString("dict_json", obj.toString()).apply()
    }

    companion object {
        @Volatile private var instance: PronunciationDict? = null
        fun get(ctx: Context): PronunciationDict =
            instance ?: synchronized(this) {
                instance ?: PronunciationDict(ctx.applicationContext).also { instance = it }
            }
    }
}
