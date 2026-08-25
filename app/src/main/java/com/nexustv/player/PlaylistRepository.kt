package com.nexustv.player

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class CatalogMetadata(
    val synopsis: String = "",
    val year: String = "",
    val backdrop: String = "",
    val trailer: String = "",
)

data class EpisodeDetail(
    val image: String = "",
    val plot: String = "",
)

data class TmdbRating(
    val score: Double,
    val voteCount: Int,
)

class PlaylistRepository(private val context: Context) {

    private data class XtreamSource(val baseUrl: String, val username: String, val password: String)
    private class PlaylistHttpException(val statusCode: Int) : IOException("HTTP $statusCode")

    private var externalMetadataByKey: Map<String, CatalogMetadata> = emptyMap()
    private var xtreamSource: XtreamSource? = null
    // Cache do mapa nome->id (get_series / get_vod_streams) para não baixar e
    // varrer a lista inteira do provedor de novo a cada série/filme aberto --
    // isso podia ser bem lento/travar em catálogos grandes (dezenas de
    // milhares de itens). Só roda de verdade fetchIdLookup na primeira vez
    // por tipo, dentro do executor single-thread desta classe.
    private val idLookupCache = mutableMapOf<MediaKind, Map<String, String>>()
    private val episodeDetailsCache = mutableMapOf<String, Map<String, EpisodeDetail>>()
    private val tmdbRatingCache = mutableMapOf<String, TmdbRating?>()

    companion object {
        private const val MAX_FRAGMENT_CHARS = 1_048_576
        private const val KEY_CACHED_AT = "cached_at_ms"
        // Janela em que o app confia no catálogo local sem nem consultar a rede.
        // Evita reimportar listas de dezenas/centenas de milhares de itens a cada
        // abertura do app só por causa de um HEAD instável no servidor de origem.
        private const val CACHE_FRESHNESS_WINDOW_MS = 3 * 24 * 60 * 60 * 1000L
        private const val TMDB_API_KEY = "aad81d5ba22644702893f3a88f6a08c1"
        private val SAME_LINE_URL_PATTERN = Regex("\\s+(https?://\\S+)$")
        private val DESCRIPTION_PATTERN = Regex("(?:^|[\\s,])(?:description|tvg-desc|tvg-description|plot|synopsis|summary|overview)\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|'([^']*)'|([^,\\s]+))", RegexOption.IGNORE_CASE)
        private val QUALITY_PATTERN = Regex("\\b(4K|UHD|FHD|HD|SD)\\b", RegexOption.IGNORE_CASE)
        private val SERIES_SEASON_PATTERN = Regex("(?:^|[\\s._-])(?:S|Season|Temporada)\\s*0*(\\d{1,2})|(?:^|[\\s._-])0*(\\d{1,2})\\s*[ªº]?\\s*Temporada", RegexOption.IGNORE_CASE)
        private val SERIES_EPISODE_PATTERN = Regex("(?:^|[\\s._-])(?:E|EP|Episode|Epis[oó]dio)\\s*0*(\\d{1,4})", RegexOption.IGNORE_CASE)
        private val SERIES_COMBINED_PATTERN = Regex("(?:^|[\\s._-])S\\s*0*(\\d{1,2})\\s*E(?:P)?\\s*0*(\\d{1,4})", RegexOption.IGNORE_CASE)
        private val SEASON_NAME_PATTERN = Regex("\\bs\\d{1,2}\\b|\\btemporada\\s*\\d{1,2}", RegexOption.IGNORE_CASE)
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val cacheFile = File(context.filesDir, "catalog-cache.tsv.gz")
    private val metadata = context.getSharedPreferences("playlist_cache_metadata", Context.MODE_PRIVATE)
    private val database = CatalogDatabase(context)

    fun load(url: String, callback: (Result<CatalogSnapshot>) -> Unit) = load(listOf(url), callback)

    fun load(urls: List<String>, callback: (Result<CatalogSnapshot>) -> Unit) {
        executor.execute {
            val result = runCatching { downloadAndCache(urls) }.recoverCatching {
                val stats = database.stats()
                if (stats.total == 0) throw it
                CatalogSnapshot(emptyList(), loadedFromCache = true, totalCount = stats.total, groupCount = stats.groups, databaseBacked = true)
            }
            callback(result)
        }
    }

    fun loadRemoteOnly(urls: List<String>, callback: (Result<CatalogSnapshot>) -> Unit) {
        executor.execute { callback(runCatching { downloadAndCache(urls) }) }
    }

    fun loadIfChanged(urls: List<String>, callback: (Result<CatalogSnapshot>) -> Unit) = loadIfChanged(urls, {}, callback)

    fun loadIfChanged(urls: List<String>, onProgress: (Int) -> Unit, callback: (Result<CatalogSnapshot>) -> Unit) =
        loadIfChanged(urls, onProgress, {}, callback)

    fun loadIfChanged(
        urls: List<String>,
        onProgress: (Int) -> Unit,
        onCatalogReady: (CatalogDatabase.Stats) -> Unit,
        callback: (Result<CatalogSnapshot>) -> Unit,
    ) {
        executor.execute {
            val result = runCatching {
                val normalized = normalizeUrls(urls)
                xtreamSource = normalized.asSequence().mapNotNull(::parseXtreamSource).firstOrNull()
                val stats = database.stats()
                val sameUrls = stats.total > 0 && normalized == savedUrls()
                if (sameUrls && cacheStillFresh()) {
                    // Cache confiável recentemente confirmado: abre na hora, sem
                    // ida à rede nenhuma. Muitos painéis Xtream (get.php) geram a
                    // lista na hora e não respondem HEAD com ETag/Last-Modified
                    // estáveis, então checar "mudou?" a cada abertura do app fazia
                    // reimportar o catálogo inteiro toda vez, mesmo sem mudança
                    // real -- daqui vem a demora "toda vez" em listas grandes.
                    onProgress(100)
                    CatalogSnapshot(emptyList(), loadedFromCache = true, totalCount = stats.total, groupCount = stats.groups, databaseBacked = true)
                } else if (sameUrls && !sourceChanged(normalized)) {
                    onProgress(100)
                    CatalogSnapshot(emptyList(), loadedFromCache = true, totalCount = stats.total, groupCount = stats.groups, databaseBacked = true)
                } else {
                    downloadAndCache(normalized, onProgress, onCatalogReady)
                }
            }.recoverCatching { failure ->
                // Uma falha de origem não deve apagar um catálogo local que já funciona.
                val cached = database.stats()
                if (cached.total <= 0) throw failure
                onProgress(100)
                CatalogSnapshot(emptyList(), loadedFromCache = true, totalCount = cached.total, groupCount = cached.groups, databaseBacked = true)
            }
            callback(result)
        }
    }

    /**
     * Força uma verificação/atualização real na próxima chamada de loadIfChanged,
     * ignorando a janela de confiança do cache. Útil para o botão VERIFICAR.
     */
    fun invalidateCacheFreshness() {
        metadata.edit().remove(KEY_CACHED_AT).apply()
    }

    fun loadCached(callback: (CatalogSnapshot?) -> Unit) {
        executor.execute {
            val stats = runCatching { database.stats() }.getOrNull()
            callback(stats?.takeIf { it.total > 0 }?.let { CatalogSnapshot(emptyList(), loadedFromCache = true, totalCount = it.total, groupCount = it.groups, databaseBacked = true) })
        }
    }

    fun queryGroups(kind: MediaKind, hidden: Set<String>, includeAdult: Boolean = false, callback: (List<String>) -> Unit) {
        executor.execute { callback(runCatching { database.groups(kind, hidden, includeAdult) }.getOrDefault(emptyList())) }
    }

    fun mostRecent(kind: MediaKind, hidden: Set<String>, callback: (CatalogEntry?) -> Unit) {
        executor.execute { callback(runCatching { database.mostRecent(kind, hidden) }.getOrNull()) }
    }

    fun mostRecentInGroups(kind: MediaKind, keywords: List<String>, hidden: Set<String>, callback: (CatalogEntry?) -> Unit) {
        executor.execute { callback(runCatching { database.mostRecentInGroups(kind, keywords, hidden) }.getOrNull()) }
    }

    fun byKey(key: String, callback: (CatalogEntry?) -> Unit) {
        executor.execute { callback(runCatching { database.byKey(key) }.getOrNull()) }
    }

    fun queryPage(
        kind: MediaKind?,
        group: String,
        search: String,
        hidden: Set<String>,
        favorites: Set<String>,
        sortMode: SortMode,
        limit: Int,
        offset: Int,
        seriesOnly: Boolean = false,
        includeAdult: Boolean = false,
        callback: (List<CatalogEntry>) -> Unit,
    ) {
        executor.execute {
            callback(runCatching { database.queryPage(kind, group, search, hidden, favorites, sortMode, limit, offset, seriesOnly, includeAdult) }.getOrDefault(emptyList()))
        }
    }

    fun querySeriesSeasons(seriesGroup: String, group: String, hidden: Set<String>, includeAdult: Boolean = false, callback: (List<String>) -> Unit) {
        executor.execute { callback(runCatching { database.querySeriesSeasons(seriesGroup, group, hidden, includeAdult) }.getOrDefault(emptyList())) }
    }

    fun querySeriesEpisodes(seriesGroup: String, season: String, group: String, hidden: Set<String>, includeAdult: Boolean = false, callback: (List<CatalogEntry>) -> Unit) {
        executor.execute { callback(runCatching { database.querySeriesEpisodes(seriesGroup, season, group, hidden, includeAdult) }.getOrDefault(emptyList())) }
    }

    fun enrichMetadata(entry: CatalogEntry, callback: (CatalogMetadata?) -> Unit) {
        externalMetadataByKey[entry.key]?.let { callback(it); return }
        executor.execute {
            val metadata = runCatching { fetchExternalMetadata(entry) }.getOrNull()
            if (metadata != null && (metadata.synopsis.isNotBlank() || metadata.year.isNotBlank() || metadata.backdrop.isNotBlank() || metadata.trailer.isNotBlank())) {
                externalMetadataByKey = externalMetadataByKey + (entry.key to metadata)
                database.updateMetadata(entry.key, metadata)
            }
            callback(metadata)
        }
    }

    fun clearCache() {
        cacheFile.delete()
        database.clear()
        metadata.edit().clear().apply()
    }

    private fun downloadAndCache(urls: List<String>): CatalogSnapshot = downloadAndCache(urls, {}, {})

    private fun downloadAndCache(urls: List<String>, onProgress: (Int) -> Unit): CatalogSnapshot =
        downloadAndCache(urls, onProgress, {})

    private fun downloadAndCache(
        urls: List<String>,
        onProgress: (Int) -> Unit,
        onCatalogReady: (CatalogDatabase.Stats) -> Unit,
    ): CatalogSnapshot {
        val normalized = normalizeUrls(urls)
        xtreamSource = normalized.asSequence().mapNotNull(::parseXtreamSource).firstOrNull()
        externalMetadataByKey = emptyMap()
        val stats = database.replaceStreaming({ emit -> streamUrls(normalized, emit) }, onProgress, onCatalogReady)
        if (stats.total == 0) error("A lista do painel está vazia ou indisponível")
        onProgress(100)
        saveSourceMetadata(normalized)
        return CatalogSnapshot(emptyList(), totalCount = stats.total, groupCount = stats.groups, databaseBacked = true)
    }

    private fun normalizeUrls(urls: List<String>): List<String> = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun parseXtreamSource(value: String): XtreamSource? = runCatching {
        val uri = Uri.parse(value)
        var username = uri.getQueryParameter("username").orEmpty()
        var password = uri.getQueryParameter("password").orEmpty()
        if (username.isBlank() || password.isBlank()) {
            val segments = uri.pathSegments
            val typeIndex = segments.indexOfFirst { it.equals("live", true) || it.equals("movie", true) || it.equals("series", true) }
            if (typeIndex >= 0 && segments.size > typeIndex + 2) {
                username = segments[typeIndex + 1]
                password = segments[typeIndex + 2]
            }
        }
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank() || username.isBlank() || password.isBlank()) return@runCatching null
        XtreamSource("${uri.scheme}://${uri.authority}".trimEnd('/'), username, password)
    }.getOrNull()

    @Volatile var lastMetadataDebug: String = ""
        private set

    private fun fetchExternalMetadata(entry: CatalogEntry): CatalogMetadata? {
        val source = xtreamSource ?: parseXtreamSource(entry.streamUrl)
        if (source == null) { lastMetadataDebug = "sem fonte Xtream (streamUrl: ${entry.streamUrl.take(60)})"; return null }
        val directId = if (entry.kind == MediaKind.SERIES) {
            entry.tvgId.filter(Char::isDigit).ifBlank { extractProviderId(entry.streamUrl) }
        } else {
            extractProviderId(entry.streamUrl).ifBlank { entry.tvgId.filter(Char::isDigit) }
        }
        val action = if (entry.kind == MediaKind.SERIES) "get_series_info" else "get_vod_info"
        val parameter = if (entry.kind == MediaKind.SERIES) "series_id" else "vod_id"
        val directJson = directId.takeIf { it.isNotBlank() }?.let { requestJson(xtreamEndpoint(source, action, parameter, it)) }
        val directMetadata = directJson?.let(::parseExternalMetadata)
        if (directMetadata?.hasAnyData() == true) { lastMetadataDebug = "ok (id direto $directId)"; return directMetadata }
        val resolvedId = resolveProviderId(source, entry)
        if (resolvedId.isBlank() || resolvedId == directId) {
            lastMetadataDebug = when {
                directId.isBlank() -> "sem id direto e sem id resolvido pelo nome ($lastResolveDebug)"
                directJson == null -> "requisicao falhou (id direto $directId, timeout ou erro HTTP)"
                resolvedId.isBlank() -> "id direto $directId sem dados uteis, e busca por nome falhou ($lastResolveDebug)"
                else -> "resposta sem dados uteis (id direto $directId): ${directJson.toString().take(120)}"
            }
            return directMetadata
        }
        val resolvedJson = requestJson(xtreamEndpoint(source, action, parameter, resolvedId))
        val resolvedMetadata = resolvedJson?.let(::parseExternalMetadata)
        lastMetadataDebug = if (resolvedMetadata?.hasAnyData() == true) "ok (id resolvido por nome $resolvedId)" else "id resolvido por nome ($resolvedId) tambem sem dados uteis"
        return resolvedMetadata
    }

    private fun parseExternalMetadata(root: JSONObject): CatalogMetadata {
        val info = root.optJSONObject("info") ?: root.optJSONObject("data")?.optJSONObject("info") ?: root.optJSONObject("data") ?: root
        return CatalogMetadata(
            synopsis = firstJsonText(info, "plot", "description", "desc", "synopsis", "overview", "summary"),
            year = firstJsonText(info, "releaseDate", "release_date", "year"),
            backdrop = firstJsonText(info, "backdrop_path", "backdrop", "fanart", "cover_big", "cover"),
            trailer = firstJsonText(info, "youtube_trailer", "youtube-trailer", "trailer", "trailer_url"),
        )
    }

    private fun CatalogMetadata.hasAnyData(): Boolean = synopsis.isNotBlank() || year.isNotBlank() || backdrop.isNotBlank() || trailer.isNotBlank()

    // Busca imagem + sinopse de CADA episodio de uma serie, usando o mesmo
    // get_series_info do Xtream (ja chamado para a sinopse geral da serie,
    // mas ate aqui o campo "episodes" da resposta era ignorado). Resultado
    // fica em cache em memoria por serie.
    fun fetchSeriesEpisodeDetails(entry: CatalogEntry, callback: (Map<String, EpisodeDetail>) -> Unit) {
        val cacheKey = entry.seriesGroup.ifBlank { entry.name }
        episodeDetailsCache[cacheKey]?.let { callback(it); return }
        executor.execute {
            val result = runCatching { fetchEpisodeDetailsInternal(entry) }.getOrDefault(emptyMap())
            if (result.isNotEmpty()) episodeDetailsCache[cacheKey] = result
            callback(result)
        }
    }

    private fun fetchEpisodeDetailsInternal(entry: CatalogEntry): Map<String, EpisodeDetail> {
        val source = xtreamSource ?: parseXtreamSource(entry.streamUrl) ?: return emptyMap()
        val directId = entry.tvgId.filter(Char::isDigit).ifBlank { extractProviderId(entry.streamUrl) }
        val root = directId.takeIf { it.isNotBlank() }
            ?.let { requestJson(xtreamEndpoint(source, "get_series_info", "series_id", it)) }
            ?.takeIf { it.optJSONObject("episodes") != null }
            ?: resolveProviderId(source, entry).takeIf { it.isNotBlank() && it != directId }
                ?.let { requestJson(xtreamEndpoint(source, "get_series_info", "series_id", it)) }
            ?: return emptyMap()
        val episodesObj = root.optJSONObject("episodes") ?: return emptyMap()
        val result = mutableMapOf<String, EpisodeDetail>()
        episodesObj.keys().forEach { seasonKey ->
            val array = episodesObj.optJSONArray(seasonKey) ?: return@forEach
            for (i in 0 until array.length()) {
                val ep = array.optJSONObject(i) ?: continue
                val epNum = ep.optString("episode_num").ifBlank { (i + 1).toString() }
                val info = ep.optJSONObject("info")
                val image = info?.let { firstJsonText(it, "movie_image", "cover_big", "cover") }.orEmpty()
                val plot = info?.let { firstJsonText(it, "plot", "description", "overview") }.orEmpty()
                if (image.isNotBlank() || plot.isNotBlank()) result["$seasonKey:$epNum"] = EpisodeDetail(image, plot)
            }
        }
        return result
    }

    // Busca a nota (media de votos) no TMDB pelo nome do filme/serie. Cache em
    // memoria por nome normalizado, ja que o TMDB nao faz parte do catalogo
    // do provedor -- e uma fonte externa, buscada sob demanda (nao da pra
    // buscar pra todo o catalogo de uma vez, seria lento e estouraria limite
    // de requisicoes).
    fun fetchTmdbRating(entry: CatalogEntry, callback: (TmdbRating?) -> Unit) {
        val title = if (entry.kind == MediaKind.SERIES) entry.seriesGroup.ifBlank { entry.name } else entry.name
        val cacheKey = normalizeMetadataName(title)
        if (cacheKey.isBlank()) { callback(null); return }
        if (tmdbRatingCache.containsKey(cacheKey)) { callback(tmdbRatingCache[cacheKey]); return }
        executor.execute {
            val rating = runCatching { fetchTmdbRatingInternal(entry, title) }.getOrNull()
            tmdbRatingCache[cacheKey] = rating
            callback(rating)
        }
    }

    private fun fetchTmdbRatingInternal(entry: CatalogEntry, title: String): TmdbRating? {
        val cleanTitle = normalizeMetadataName(title).ifBlank { return null }
        val endpoint = if (entry.kind == MediaKind.SERIES) "tv" else "movie"
        val url = Uri.parse("https://api.themoviedb.org/3/search/$endpoint").buildUpon()
            .appendQueryParameter("api_key", TMDB_API_KEY)
            .appendQueryParameter("language", "pt-BR")
            .appendQueryParameter("query", cleanTitle)
            .also { if (entry.year.isNotBlank()) it.appendQueryParameter(if (endpoint == "tv") "first_air_date_year" else "year", entry.year.take(4)) }
            .build().toString()
        val root = requestJson(url) ?: return null
        val results = root.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val best = results.optJSONObject(0) ?: return null
        val score = best.optDouble("vote_average", 0.0)
        val votes = best.optInt("vote_count", 0)
        if (score <= 0.0) return null
        return TmdbRating(score = score, voteCount = votes)
    }

    private fun extractProviderId(streamUrl: String): String = runCatching {
        Uri.parse(streamUrl).pathSegments.asReversed()
            .firstOrNull { segment -> segment.substringBeforeLast('.').all(Char::isDigit) && segment.substringBeforeLast('.').isNotBlank() }
            ?.substringBeforeLast('.')
            .orEmpty()
    }.getOrDefault("")

    private fun resolveProviderId(source: XtreamSource, entry: CatalogEntry): String {
        val lookup = idLookupCache.getOrPut(entry.kind) { fetchIdLookup(source, entry.kind) }
        val wanted = normalizeMetadataName(if (entry.kind == MediaKind.SERIES) entry.seriesGroup.ifBlank { entry.name } else entry.name)
        lookup[wanted]?.let { return it }
        val partial = lookup.entries.firstOrNull { (name, _) -> name.contains(wanted) || wanted.contains(name) }?.value
        if (partial != null) return partial
        lastResolveDebug = if (lookup.isEmpty()) "lista de nomes do provedor veio vazia" else "lista tem ${lookup.size} nomes mas nenhum bateu com \"$wanted\""
        return ""
    }

    @Volatile var lastResolveDebug: String = ""
        private set

    private fun fetchIdLookup(source: XtreamSource, kind: MediaKind): Map<String, String> {
        val action = if (kind == MediaKind.SERIES) "get_series" else "get_vod_streams"
        val array = requestJsonArray(xtreamEndpoint(source, action)) ?: return emptyMap()
        val idField = if (kind == MediaKind.SERIES) "series_id" else "stream_id"
        val map = LinkedHashMap<String, String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = normalizeMetadataName(item.optString("name"))
            val id = item.optString(idField)
            if (name.isNotBlank() && id.isNotBlank() && name !in map) map[name] = id
        }
        return map
    }

    private fun xtreamEndpoint(source: XtreamSource, action: String, parameter: String? = null, value: String? = null): String {
        val builder = Uri.parse("${source.baseUrl}/player_api.php").buildUpon()
            .appendQueryParameter("username", source.username)
            .appendQueryParameter("password", source.password)
            .appendQueryParameter("action", action)
        if (!parameter.isNullOrBlank() && !value.isNullOrBlank()) builder.appendQueryParameter(parameter, value)
        return builder.build().toString()
    }

    private fun normalizeMetadataName(value: String): String = value
        .lowercase()
        .replace(Regex("\\s*(?:s\\s*\\d{1,2}\\s*e(?:p)?\\s*\\d{1,4}|temporada\\s*\\d{1,2}|season\\s*\\d{1,2}).*$"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun requestBody(endpoint: String): String? = runCatching {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MaximusTVPlayer/1.0 AndroidTV")
        }
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (status !in 200..299 || body.isBlank()) null else body
    }.getOrNull()

    private fun requestJson(endpoint: String): JSONObject? = requestBody(endpoint)?.let { body -> runCatching { JSONObject(body) }.getOrNull() }

    // Alguns endpoints do Xtream (get_series, get_vod_streams) respondem com
    // um array JSON puro no topo ("[...]"), não um objeto -- se tentarmos
    // JSONObject(body) nesses casos, falha silenciosamente e a busca por
    // nome (fallback quando o id direto não bate) nunca encontra nada.
    private fun requestJsonArray(endpoint: String): org.json.JSONArray? = requestBody(endpoint)?.let { body ->
        runCatching { org.json.JSONArray(body) }.getOrNull()
            ?: runCatching { JSONObject(body) }.getOrNull()?.let { obj ->
                obj.keys().asSequence().firstNotNullOfOrNull { key -> obj.optJSONArray(key) }
            }
    }

    private fun firstJsonText(json: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = json.opt(key)
            when (value) {
                is JSONArray -> if (value.length() > 0) return value.optString(0).trim()
                else -> value?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
            }
        }
        return ""
    }

    private fun savedUrls(): List<String> = metadata.getString("urls", "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun cacheStillFresh(): Boolean {
        if (metadata.getInt("format_version", 0) != 8) return false
        val cachedAt = metadata.getLong(KEY_CACHED_AT, 0L)
        if (cachedAt <= 0L) return false
        return System.currentTimeMillis() - cachedAt < CACHE_FRESHNESS_WINDOW_MS
    }

    private fun sourceChanged(urls: List<String>): Boolean {
        if (metadata.getInt("format_version", 0) != 8) return true
        val savedUrls = savedUrls()
        if (savedUrls != urls) return true
        // Apenas a fonte ativa precisa ser validada no boot. As demais URLs são
        // alternativas de failover e só serão acessadas se a primária falhar.
        val activeUrl = urls.firstOrNull() ?: return false
        val savedSignature = metadata.getString("signature_${activeUrl.hashCode()}", "").orEmpty()
        val remoteSignature = headSignature(activeUrl)
        return savedSignature.isNotBlank() && remoteSignature != null && remoteSignature != savedSignature
    }

    private fun saveSourceMetadata(urls: List<String>) {
        val editor = metadata.edit()
            .putInt("format_version", 8)
            .putString("urls", urls.joinToString("\n"))
            .putLong(KEY_CACHED_AT, System.currentTimeMillis())
        urls.forEach { url -> headSignature(url)?.let { editor.putString("signature_${url.hashCode()}", it) } }
        editor.apply()
    }

    private fun headSignature(urlString: String): String? = runCatching {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("User-Agent", "MaximusTVPlayer/1.0 AndroidTV")
        }
        val status = connection.responseCode
        val signature = if (status in 200..299) {
            listOf(connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"), connection.getHeaderField("Content-Length"))
                .joinToString("|")
                .takeUnless { it == "||" }
        } else null
        connection.disconnect()
        signature
    }.getOrNull()

    fun shutdown() {
        executor.shutdownNow()
        database.close()
    }

    private fun streamUrls(urls: List<String>, emit: (CatalogEntry) -> Unit) {
        var firstFailure: Throwable? = null
        for (url in urls) {
            var emittedForUrl = 0
            val result = runCatching {
                fetchAndParse(url) { entry ->
                    emittedForUrl++
                    emit(entry)
                }
            }
            if (result.isSuccess && result.getOrThrow() > 0) {
                // playlist_urls representa fontes alternativas/failover. A lista ativa é
                // suficiente para o primeiro catálogo; não concatenar cópias da mesma conta.
                return
            }
            result.exceptionOrNull()?.let { failure ->
                if (firstFailure == null) firstFailure = failure
                // Se a origem começou a emitir entradas e caiu no meio, não misturar uma
                // segunda fonte no mesmo catálogo; a transação do lote fará rollback final.
                if (emittedForUrl > 0) throw failure
            }
        }
        val reason = firstFailure?.message?.takeIf { it.isNotBlank() }
        error(reason?.let { "Nenhuma playlist respondeu: $it" } ?: "A lista do painel não contém entradas M3U válidas")
    }

    private fun fetchAndParse(urlString: String, emit: (CatalogEntry) -> Unit): Int {
        // Uma única tentativa por URL evita prender a tela em um proxy 522; o failover
        // percorre as demais playlists e a próxima verificação automática tenta novamente.
        return fetchAndParseOnce(urlString, emit)
    }

    private fun fetchAndParseOnce(urlString: String, emit: (CatalogEntry) -> Unit): Int {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 15_000
            setRequestProperty("Accept", "audio/x-mpegurl, application/vnd.apple.mpegurl, text/plain, */*")
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", "MaximusTVPlayer/1.0 AndroidTV")
        }
        connection.connect()
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        if (status !in 200..299) {
            stream?.close()
            connection.disconnect()
            throw PlaylistHttpException(status)
        }
        val rawInput = stream ?: run {
            connection.disconnect()
            error("A lista do painel não retornou conteúdo")
        }
        val input = if (connection.getHeaderField("Content-Encoding").equals("gzip", true)) GZIPInputStream(rawInput) else rawInput
        var count = 0
        java.io.BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024).use { reader ->
            parseM3uStream(reader) { entry -> count++; emit(entry) }
        }
        connection.disconnect()
        if (count == 0) error("A lista do painel não contém entradas M3U válidas")
        return count
    }

    private fun parseM3uStream(reader: java.io.Reader, emit: (CatalogEntry) -> Unit) {
        val buffer = StringBuilder()
        val chunk = CharArray(32 * 1024)
        var pendingInfo: String? = null
        // `start` é um cursor lógico dentro de `buffer`: em vez de fazer
        // buffer.delete(0, n) a cada linha (que desloca fisicamente todo o
        // restante do StringBuilder e é O(tamanho restante) por chamada,
        // repetido uma vez por linha), avançamos só o cursor e compactamos
        // o buffer de tempos em tempos. Em listas grandes (dezenas de MB)
        // isso reduz o número de deslocamentos de memória em várias ordens
        // de grandeza.
        var start = 0
        val compactThreshold = 256 * 1024

        fun compactIfNeeded() {
            if (start > compactThreshold) {
                buffer.delete(0, start)
                start = 0
            }
        }

        while (true) {
            val count = reader.read(chunk)
            if (count < 0) break
            buffer.append(chunk, 0, count)
            while (true) {
                val token = buffer.indexOf("#EXTINF:", start)
                val newline = buffer.indexOf('\n', start)
                if (token < 0) {
                    if (newline >= 0) {
                        val line = buffer.substring(start, newline)
                        start = newline + 1
                        pendingInfo = processM3uLine(line, pendingInfo, emit)
                        compactIfNeeded()
                        continue
                    }
                    if (buffer.length - start > MAX_FRAGMENT_CHARS) error("A lista M3U contém uma linha inválida muito grande")
                    break
                }
                if (token > start) {
                    if (newline in start until token) {
                        val line = buffer.substring(start, newline)
                        start = newline + 1
                        pendingInfo = processM3uLine(line, pendingInfo, emit)
                    } else {
                        start = token
                    }
                    compactIfNeeded()
                    continue
                }
                val nextToken = buffer.indexOf("#EXTINF:", start + 8)
                if (nextToken >= 0) {
                    val block = buffer.substring(start, nextToken)
                    start = nextToken
                    pendingInfo = processM3uBlock(block, pendingInfo, emit)
                    compactIfNeeded()
                    continue
                }
                if (newline >= 0) {
                    val line = buffer.substring(start, newline)
                    start = newline + 1
                    pendingInfo = processM3uLine(line, pendingInfo, emit)
                    compactIfNeeded()
                    continue
                }
                if (buffer.length - start > MAX_FRAGMENT_CHARS) error("A entrada M3U ultrapassou o limite seguro")
                break
            }
        }
        if (start < buffer.length) processM3uBlock(buffer.substring(start), pendingInfo, emit)
    }

    private fun processM3uBlock(block: String, pendingInfo: String?, emit: (CatalogEntry) -> Unit): String? {
        var nextPending = pendingInfo
        block.split('\n').forEach { line -> nextPending = processM3uLine(line, nextPending, emit) }
        return nextPending
    }

    private fun processM3uLine(raw: String, pendingInfo: String?, emit: (CatalogEntry) -> Unit): String? {
        val line = raw.replace("\r", "").trim()
        if (line.isBlank()) return pendingInfo
        if (line.startsWith("#EXTINF:")) {
            val sameLineUrl = SAME_LINE_URL_PATTERN.find(line)
            if (sameLineUrl != null) {
                addEntry(line.substring(0, sameLineUrl.range.first).trim(), sameLineUrl.groupValues[1], emit)
                return null
            }
            return line
        }
        if (pendingInfo != null && line.startsWith("http")) {
            addEntry(pendingInfo, line, emit)
            return null
        }
        return pendingInfo
    }

    private fun addEntry(info: String, streamUrl: String, emit: (CatalogEntry) -> Unit) {
        val attributes = parseAttributesFast(info)
        val rawName = attributes["tvg-name"].orEmpty().ifBlank { info.substringAfter(',', "") }.trim()
        val displayName = cleanDisplayName(rawName)
        if (displayName.isBlank() || streamUrl.isBlank()) return
        val group = attributes["group-title"].orEmpty().ifBlank { "Sem categoria" }
        val kind = classify(displayName, group)
        val quality = QUALITY_PATTERN.find(displayName)?.value?.uppercase().orEmpty()
        val synopsis = cleanMetadataText(firstAttribute(attributes, "description", "tvg-desc", "tvg-description", "plot", "synopsis", "summary", "overview").ifBlank { extractSynopsis(info) })
        val cast = firstAttribute(attributes, "cast", "actors", "actor", "elenco", "tvg-cast")
        val year = firstAttribute(attributes, "year", "release-year", "release_year", "date")
        val backdrop = cleanAssetUrl(firstAttribute(attributes, "backdrop", "backdrop-url", "backdrop_url", "fanart", "fanart-url", "fanart_url", "background", "background-url", "background_url", "banner", "banner-url", "banner_url", "art", "art-url", "art_url", "cover_big"))
        val trailer = firstAttribute(attributes, "trailer", "trailer-url", "trailer_url", "youtube-trailer", "youtube_trailer")
        val runtime = firstAttribute(attributes, "duration", "runtime", "length")
        val seriesParts = if (kind == MediaKind.SERIES) parseSeriesParts(displayName, attributes) else SeriesParts("", "", "")
        emit(CatalogEntry(
            key = "${attributes["tvg-id"].orEmpty()}|$streamUrl",
            name = displayName,
            groupTitle = group,
            tvgId = attributes["tvg-id"].orEmpty(),
            logoUrl = cleanAssetUrl(firstAttribute(attributes, "tvg-logo", "logo", "logo-url", "logo_url", "poster", "poster-url", "poster_url", "cover", "cover-url", "cover_url", "thumb", "thumbnail", "image", "image-url", "image_url", "cover_big")),
            streamUrl = streamUrl,
            kind = kind,
            quality = quality,
            seriesGroup = seriesParts.group,
            season = seriesParts.season,
            episode = seriesParts.episode,
            year = year,
            synopsis = synopsis,
            cast = cast,
            backdropUrl = backdrop,
            trailerUrl = trailer,
            runtime = runtime,
        ))
    }

    private data class SeriesParts(val group: String, val season: String, val episode: String)

    private fun parseSeriesParts(name: String, attributes: Map<String, String>): SeriesParts {
        val explicitGroup = firstAttribute(attributes, "series-name", "series_title", "series-title", "series_group", "series-group", "show-name", "tv-show")
        val seasonFromAttribute = firstAttribute(attributes, "season", "season-num", "season_number", "season-number", "tvg-season")
            .replace(Regex("[^0-9]"), "")
            .trimStart('0')
            .ifBlank { "1" }
        val episodeFromAttribute = firstAttribute(attributes, "episode", "episode-num", "episode_number", "episode-number", "tvg-episode")
            .replace(Regex("[^0-9]"), "")
            .trimStart('0')
            .ifBlank { "" }
        val seasonMatch = SERIES_SEASON_PATTERN.find(name)
        val combinedMatch = SERIES_COMBINED_PATTERN.find(name)
        val season = if (firstAttribute(attributes, "season", "season-num", "season_number", "season-number", "tvg-season").isNotBlank()) {
            seasonFromAttribute
        } else {
            (seasonMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() } ?: "1").trimStart('0').ifBlank { "1" }
        }
        val episode = if (episodeFromAttribute.isNotBlank()) {
            episodeFromAttribute
        } else {
            combinedMatch?.groupValues?.getOrNull(2)?.trimStart('0')?.ifBlank { "0" }
                ?: SERIES_EPISODE_PATTERN.find(name)?.groupValues?.getOrNull(1)?.trimStart('0')?.ifBlank { "0" }.orEmpty()
        }
        val inferredGroup = seasonMatch?.range?.first?.let { name.substring(0, it) }
            ?.trim()?.trim('-', '–', '_', '.', '|')
            .orEmpty()
        val group = normalizeSeriesGroup(explicitGroup.ifBlank { inferredGroup }.ifBlank { name.trim() })
        return SeriesParts(group = group, season = season, episode = episode)
    }

    private fun normalizeSeriesGroup(value: String): String {
        val cleaned = cleanDisplayName(value)
        val withoutEpisode = cleaned.replace(
            Regex("\\s*(?:[-|:]+\\s*)?(?:S\\s*0*\\d{1,2}\\s*E(?:P)?\\s*0*\\d{1,4}|(?:E|EP|Episode|Epis[oó]dio)\\s*0*\\d{1,4})\\b.*$", RegexOption.IGNORE_CASE),
            "",
        )
        val withoutSeason = withoutEpisode.replace(
            Regex("\\s*(?:[-|:]+\\s*)?(?:0*\\d{1,2}\\s*[ªº]?\\s*Temporada|Temporada\\s*0*\\d{1,2}|Season\\s*0*\\d{1,2})\\b.*$", RegexOption.IGNORE_CASE),
            "",
        )
        return withoutSeason.trim().trim('-', '–', '_', '.', '|').replace(Regex("\\s{2,}"), " ").ifBlank { cleaned }
    }

    private fun cleanDisplayName(value: String): String = value
        .replace(Regex("[\\\"']?\\s*(?:tvg-logo|group-title|tvg-id|tvg-name|tvg-type|tvg-chno|group)\\s*=.*$", RegexOption.IGNORE_CASE), "")
        .trim()
        .replace(Regex("^[\\\"']+|[\\\"']+$"), "")
        .replace(Regex("\\s{2,}"), " ")

    private fun parseAttributesFast(info: String): Map<String, String> {
        val result = HashMap<String, String>(16)
        var index = 0
        while (index < info.length) {
            while (index < info.length && (info[index].isWhitespace() || info[index] == ',' || info[index] == '#' || info[index] == ':')) index++
            val keyStart = index
            while (index < info.length && (info[index].isLetterOrDigit() || info[index] == '-' || info[index] == '_')) index++
            if (index == keyStart) {
                index++
                continue
            }
            val key = info.substring(keyStart, index).lowercase()
            while (index < info.length && info[index].isWhitespace()) index++
            if (index >= info.length || info[index] != '=') continue
            index++
            while (index < info.length && info[index].isWhitespace()) index++
            if (index >= info.length) break
            val value = if (info[index] == '\"' || info[index] == '\'') {
                val quote = info[index++]
                val valueStart = index
                while (index < info.length && info[index] != quote) index++
                val parsed = info.substring(valueStart, index)
                if (index < info.length) index++
                parsed
            } else {
                val valueStart = index
                while (index < info.length && !info[index].isWhitespace() && info[index] != ',') index++
                info.substring(valueStart, index)
            }
            if (value.isNotBlank()) result[key] = value.trim()
        }
        return result
    }

    private fun firstAttribute(attributes: Map<String, String>, vararg keys: String): String {
        keys.forEach { key -> attributes[key.lowercase()]?.trim()?.takeIf { it.isNotBlank() }?.let { return it } }
        return ""
    }

    private fun extractSynopsis(info: String): String {
        val match = DESCRIPTION_PATTERN.find(info) ?: return ""
        return match.groupValues[1].ifBlank { match.groupValues[2] }.ifBlank { match.groupValues[3] }
    }

    private fun cleanMetadataText(value: String): String = value
        .replace("\\\\n", "\n")
        .replace("\\\\\"", "\"")
        .replace("\\\\/", "/")
        .replace("<br>", "\n", ignoreCase = true)
        .replace("<br/>", "\n", ignoreCase = true)
        .replace("<br />", "\n", ignoreCase = true)
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .trim()

    private fun cleanAssetUrl(value: String): String = value
        .replace("&amp;", "&")
        .replace("\\\\/", "/")
        .trim()

    private fun classify(name: String, group: String): MediaKind {
        val normalizedGroup = group.lowercase().trim()
        val normalizedName = name.lowercase()
        if (normalizedGroup.startsWith("filmes |")) return MediaKind.MOVIE
        if (normalizedGroup.startsWith("series |")) return MediaKind.SERIES
        if (normalizedGroup.contains("24/7 filmes") || normalizedGroup.contains("24/7 seriados") || normalizedGroup.contains("24/7 doramas") || normalizedGroup.contains("24/7 animes") || normalizedGroup.contains("24/7 novelas")) return MediaKind.LIVE
        if (normalizedGroup == "filmes e séries" || normalizedGroup == "filmes e series") return MediaKind.LIVE
        if (normalizedName.contains("temporada") || SEASON_NAME_PATTERN.containsMatchIn(normalizedName) || SERIES_SEASON_PATTERN.containsMatchIn(name) || SERIES_COMBINED_PATTERN.containsMatchIn(name)) return MediaKind.SERIES
        if (normalizedName.contains("filme") || normalizedName.contains("movie")) return MediaKind.MOVIE
        return MediaKind.LIVE
    }

    private fun writeCache(entries: List<CatalogEntry>) {
        GZIPOutputStream(cacheFile.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { writer ->
            entries.forEach { entry ->
                val fields = listOf(entry.key, entry.name, entry.groupTitle, entry.tvgId, entry.logoUrl, entry.streamUrl, entry.kind.name, entry.quality, entry.seriesGroup, entry.season, entry.episode, entry.year, entry.synopsis, entry.cast, entry.backdropUrl, entry.trailerUrl, entry.runtime)
                writer.append(fields.joinToString("\t") { encode(it) }).append('\n')
            }
        }
    }

    private fun readCache(): List<CatalogEntry> {
        if (!cacheFile.exists()) return emptyList()
        val entries = ArrayList<CatalogEntry>()
        GZIPInputStream(cacheFile.inputStream().buffered()).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val f = line.split('\t').map(::decode)
                if (f.size >= 9) {
                    runCatching {
                        entries += CatalogEntry(
                            key = f[0], name = f[1], groupTitle = f[2], tvgId = f[3], logoUrl = f[4], streamUrl = f[5],
                            kind = MediaKind.valueOf(f[6]), quality = f[7], seriesGroup = f[8],
                            season = f.getOrElse(9) { "" }, episode = f.getOrElse(10) { "" }, year = f.getOrElse(11) { "" },
                            synopsis = f.getOrElse(12) { "" }, cast = f.getOrElse(13) { "" }, backdropUrl = f.getOrElse(14) { "" },
                            trailerUrl = f.getOrElse(15) { "" }, runtime = f.getOrElse(16) { "" },
                        )
                    }
                }
            }
        }
        return entries
    }

    private fun encode(value: String): String = Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    private fun decode(value: String): String = String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
}
