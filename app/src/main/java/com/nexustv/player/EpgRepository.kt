package com.nexustv.player

import android.util.Xml
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import org.xmlpull.v1.XmlPullParser

data class EpgProgram(
    val channelId: String,
    val title: String,
    val description: String,
    val start: Long,
    val stop: Long,
)

class EpgRepository {
    private val executor = Executors.newSingleThreadExecutor()

    fun load(url: String, callback: (Result<Map<String, List<EpgProgram>>>) -> Unit) {
        executor.execute {
            callback(runCatching { fetch(url) })
        }
    }

    private fun fetch(urlString: String): Map<String, List<EpgProgram>> {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "MaximusTVPlayer/1.0 AndroidTV")
        }
        connection.connect()
        if (connection.responseCode !in 200..299) error("EPG HTTP ${connection.responseCode}")
        val parser = Xml.newPullParser().apply {
            setInput(connection.inputStream, "UTF-8")
        }
        val result = mutableMapOf<String, MutableList<EpgProgram>>()
        var event = parser.eventType
        var channelId = ""
        var title = ""
        var description = ""
        var start = 0L
        var stop = 0L
        var currentTag = ""
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "programme" -> {
                            channelId = parser.getAttributeValue(null, "channel").orEmpty()
                            start = parseDate(parser.getAttributeValue(null, "start"))
                            stop = parseDate(parser.getAttributeValue(null, "stop"))
                            title = ""
                            description = ""
                        }
                        "title", "desc" -> currentTag = parser.name
                    }
                }
                XmlPullParser.TEXT -> {
                    if (currentTag == "title") title = parser.text.trim()
                    if (currentTag == "desc") description = parser.text.trim()
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "title", "desc" -> currentTag = ""
                        "programme" -> if (channelId.isNotBlank() && title.isNotBlank()) {
                            result.getOrPut(channelId) { mutableListOf() } += EpgProgram(channelId, title, description, start, stop)
                        }
                    }
                }
            }
            event = parser.next()
        }
        connection.disconnect()
        return result.mapValues { (_, programs) -> programs.sortedBy { it.start } }
    }

    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val normalized = value.trim().replace(" ", "")
        val base = normalized.take(14)
        val timezone = normalized.drop(14).takeIf { it.isNotBlank() } ?: "+0000"
        return runCatching {
            SimpleDateFormat("yyyyMMddHHmmssZ", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(base + timezone)?.time ?: 0L
        }.getOrElse { 0L }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
