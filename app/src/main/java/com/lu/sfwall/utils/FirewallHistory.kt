package com.lu.sfwall.utils

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Firewall run history: when the firewall was started and stopped.
 *
 * One append-only JSON file in the app-private files dir. Every record is a
 * timestamp, a type and the source that caused the state change. History is
 * never part of a backup export and holds no token or PIN.
 *
 * All writes are guarded: a read/write failure only leaves a Log line and
 * never affects the firewall switch itself.
 */
object FirewallHistory {

    enum class Type(val key: String) {
        ENABLED("enabled"),
        DISABLED("disabled"),
        CLEARED_AFTER_REBOOT("cleared_after_reboot");

        companion object {
            fun fromKey(key: String): Type? = entries.firstOrNull { it.key == key }
        }
    }

    enum class Source(val key: String) {
        MANUAL("manual"),
        WIDGET("widget"),
        AUTOMATION("automation"),
        PROFILE("profile"),
        TILE("tile"),
        FLOATING_BUTTON("floating_button"),
        SHIZUKU_AUTO("shizuku_auto"),
        BOOT_AUTO("boot_auto"),
        SYSTEM_REBOOT("system_reboot"),
        SYSTEM("system");

        companion object {
            fun fromKey(key: String?): Source? = entries.firstOrNull { it.key == key }
        }
    }

    data class Event(val timestampMs: Long, val type: Type, val source: Source)

    const val MAX_ENTRIES = 500
    private const val FILE_NAME = "firewall_history.json"
    private const val TAG = "FirewallHistory"
    private val lock = Any()

    fun load(context: Context): List<Event> {
        synchronized(lock) {
            return try {
                readAll(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read firewall history", e)
                emptyList()
            }
        }
    }

    fun append(context: Context, type: Type, source: Source) {
        synchronized(lock) {
            try {
                val all = readAll(context).toMutableList()
                all += Event(System.currentTimeMillis(), type, source)
                val trimmed = if (all.size > MAX_ENTRIES) all.takeLast(MAX_ENTRIES) else all
                writeAll(context, trimmed)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to append firewall history", e)
            }
        }
    }

    fun recordClearedAfterReboot(context: Context) {
        append(context, Type.CLEARED_AFTER_REBOOT, Source.SYSTEM_REBOOT)
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try {
                File(context.filesDir, FILE_NAME).delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear firewall history", e)
            }
        }
    }

    private fun readAll(context: Context): List<Event> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        val array = JSONArray(text)
        val events = ArrayList<Event>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val type = Type.fromKey(obj.optString("type")) ?: continue
            val source = Source.fromKey(obj.optString("source")) ?: Source.SYSTEM
            events += Event(obj.optLong("ts", 0L), type, source)
        }
        return events
    }

    private fun writeAll(context: Context, events: List<Event>) {
        val array = JSONArray()
        for (event in events) {
            array.put(
                JSONObject()
                    .put("ts", event.timestampMs)
                    .put("type", event.type.key)
                    .put("source", event.source.key)
            )
        }
        File(context.filesDir, FILE_NAME).writeText(array.toString())
    }
}
