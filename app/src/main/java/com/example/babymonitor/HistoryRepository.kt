package com.example.babymonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A single detected noise/cry event within a monitoring session. */
data class CryEvent(
    val timestamp: Long,
    val durationSec: Int,
    val peakDb: Int
)

/** One continuous monitoring session (from "start" to "stop"). */
data class SleepSession(
    val id: String,
    val startTime: Long,
    var endTime: Long?,
    val phoneNumber: String,
    val cryEvents: MutableList<CryEvent> = mutableListOf()
)

/**
 * Lightweight local persistence for sleep-monitoring history, stored as a
 * single JSON file in the app's private internal storage. A full database
 * (e.g. Room) isn't needed given how little data this generates.
 */
object HistoryRepository {

    private const val FILE_NAME = "sleep_history.json"
    private const val MAX_SESSIONS = 200

    @Synchronized
    fun startSession(context: Context, phoneNumber: String): String {
        val sessions = readAll(context)
        val id = UUID.randomUUID().toString()
        val session = SleepSession(
            id = id,
            startTime = System.currentTimeMillis(),
            endTime = null,
            phoneNumber = phoneNumber
        )
        sessions.add(0, session)
        while (sessions.size > MAX_SESSIONS) sessions.removeAt(sessions.size - 1)
        writeAll(context, sessions)
        return id
    }

    @Synchronized
    fun addCryEvent(context: Context, sessionId: String, event: CryEvent) {
        val sessions = readAll(context)
        val session = sessions.firstOrNull { it.id == sessionId } ?: return
        session.cryEvents.add(0, event)
        writeAll(context, sessions)
    }

    @Synchronized
    fun endSession(context: Context, sessionId: String) {
        val sessions = readAll(context)
        val session = sessions.firstOrNull { it.id == sessionId } ?: return
        session.endTime = System.currentTimeMillis()
        writeAll(context, sessions)
    }

    @Synchronized
    fun getAllSessions(context: Context): List<SleepSession> = readAll(context)

    @Synchronized
    fun clearHistory(context: Context) {
        writeAll(context, mutableListOf())
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun readAll(context: Context): MutableList<SleepSession> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return try {
            val text = f.readText()
            val array = JSONArray(text)
            val result = mutableListOf<SleepSession>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val events = mutableListOf<CryEvent>()
                val eventsArray = obj.optJSONArray("cryEvents")
                if (eventsArray != null) {
                    for (j in 0 until eventsArray.length()) {
                        val e = eventsArray.getJSONObject(j)
                        events.add(
                            CryEvent(
                                timestamp = e.getLong("timestamp"),
                                durationSec = e.getInt("durationSec"),
                                peakDb = e.getInt("peakDb")
                            )
                        )
                    }
                }
                result.add(
                    SleepSession(
                        id = obj.getString("id"),
                        startTime = obj.getLong("startTime"),
                        endTime = if (obj.isNull("endTime")) null else obj.getLong("endTime"),
                        phoneNumber = obj.optString("phoneNumber", ""),
                        cryEvents = events
                    )
                )
            }
            result
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun writeAll(context: Context, sessions: List<SleepSession>) {
        val array = JSONArray()
        for (session in sessions) {
            val obj = JSONObject()
            obj.put("id", session.id)
            obj.put("startTime", session.startTime)
            obj.put("endTime", session.endTime ?: JSONObject.NULL)
            obj.put("phoneNumber", session.phoneNumber)
            val eventsArray = JSONArray()
            for (event in session.cryEvents) {
                val e = JSONObject()
                e.put("timestamp", event.timestamp)
                e.put("durationSec", event.durationSec)
                e.put("peakDb", event.peakDb)
                eventsArray.put(e)
            }
            obj.put("cryEvents", eventsArray)
            array.put(obj)
        }
        file(context).writeText(array.toString())
    }
}
