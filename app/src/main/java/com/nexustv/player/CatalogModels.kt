package com.nexustv.player

enum class MediaKind {
    LIVE,
    MOVIE,
    SERIES,
}

enum class SortMode(val label: String) {
    RECENT("RECENTES"),
    ALPHABETICAL("A-Z"),
    // Nota (TMDB) ainda não disponível -- fica pra depois, quando tivermos a
    // chave da API. Deixado aqui já para a UI conseguir mostrar desabilitado.
    RATING("NOTA"),
}

data class CatalogEntry(
    val key: String,
    val name: String,
    val groupTitle: String,
    val tvgId: String,
    val logoUrl: String,
    val streamUrl: String,
    val kind: MediaKind,
    val quality: String,
    val seriesGroup: String = "",
    val season: String = "",
    val episode: String = "",
    val year: String = "",
    val synopsis: String = "",
    val cast: String = "",
    val backdropUrl: String = "",
    val trailerUrl: String = "",
    val runtime: String = "",
)

data class CatalogSnapshot(
    val entries: List<CatalogEntry>,
    val loadedFromCache: Boolean = false,
    val totalCount: Int = entries.size,
    val groupCount: Int = entries.map { it.groupTitle }.distinct().size,
    val databaseBacked: Boolean = false,
) {
    val live: List<CatalogEntry> get() = entries.filter { it.kind == MediaKind.LIVE }
    val movies: List<CatalogEntry> get() = entries.filter { it.kind == MediaKind.MOVIE }
    val series: List<CatalogEntry> get() = entries.filter { it.kind == MediaKind.SERIES }
    val groups: List<String> get() = entries.map { it.groupTitle.ifBlank { "Sem categoria" } }.distinct().sorted()
}
