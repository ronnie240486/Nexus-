package com.nexustv.player

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/** Uma estação de rádio reproduzível dentro do mesmo player Media3 do NEXUS. */
data class RadioStation(
    val id: String,
    val name: String,
    val category: String,
    val country: String,
    val city: String,
    val genre: String,
    val streamUrl: String,
    val logoUrl: String,
    val sourceUrl: String,
    val language: String,
)

class RadioRepository(private val context: Context) {
    private val assetFiles = listOf("radio_stations_nacionais.csv")
    private val categoryOrder = listOf(
        "Nacionais • Pop",
        "Nacionais • Sertanejo",
        "Nacionais • Gospel",
        "Nacionais • Rock",
        "Nacionais • Heavy Rock",
        "Nacionais • Blues",
        "Nacionais • Jazz",
    )

    @Volatile
    private var cachedStations: List<RadioStation>? = null

    fun allStations(): List<RadioStation> {
        cachedStations?.let { return it }
        val order = categoryOrder.withIndex().associate { it.value to it.index }
        val stations = assetFiles.flatMap { parseAsset(it) }
            .filter {
                it.name.isNotBlank() &&
                    it.streamUrl.startsWith("http", true) &&
                    it.country.equals("Brasil", true)
            }
            .distinctBy { it.streamUrl.lowercase(Locale.ROOT) }
            .sortedWith(compareBy({ order[it.category] ?: Int.MAX_VALUE }, { it.name.lowercase(Locale.ROOT) }))
        cachedStations = stations
        return stations
    }

    fun categories(): List<String> {
        val available = allStations().map { it.category.ifBlank { "Nacionais" } }.toSet()
        return categoryOrder.filter { it in available } + available.filterNot { it in categoryOrder }.sorted()
    }

    fun filter(category: String = "Todas", query: String = ""): List<RadioStation> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        return allStations().filter { station ->
            val categoryMatches = category == "Todas" || station.category == category
            val queryMatches = normalizedQuery.isBlank() || listOf(
                station.name, station.city, station.country, station.genre, station.language,
            ).any { it.lowercase(Locale.ROOT).contains(normalizedQuery) }
            categoryMatches && queryMatches
        }
    }

    private fun parseAsset(assetName: String): List<RadioStation> = runCatching {
        context.assets.open(assetName).use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                val iterator = lines.iterator()
                if (!iterator.hasNext()) return@useLines emptyList<RadioStation>()
                val headers = parseCsvLine(iterator.next()).map { it.trim().lowercase(Locale.ROOT) }
                val index = headers.withIndex().associate { it.value to it.index }
                buildList {
                    while (iterator.hasNext()) {
                        val values = parseCsvLine(iterator.next())
                        fun value(name: String): String = values.getOrNull(index[name] ?: -1).orEmpty().trim()
                        val stream = value("stream_url")
                        if (stream.isBlank()) continue
                        val name = value("name")
                        add(
                            RadioStation(
                                id = value("stationuuid").ifBlank { stream },
                                name = name,
                                category = value("category").ifBlank { "Rádios" },
                                country = value("country"),
                                city = value("city"),
                                genre = value("genre"),
                                streamUrl = stream,
                                logoUrl = value("logo_url"),
                                sourceUrl = value("source_url"),
                                language = value("language"),
                            ),
                        )
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    result += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        result += current.toString()
        return result
    }
}
