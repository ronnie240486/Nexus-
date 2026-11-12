package com.nexustv.player

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// Painel atual (Railway). O Manus é só reserva: o domínio antigo não fala mais a API de verdade.
private const val RENCIA_BASE_URL = "https://renciaapp-production.up.railway.app"
private const val RENCIA_BASE_URL_FALLBACK = "https://renciaapp.manus.space"
private const val OMINUS_APP_ID = "ominus"
/** Sentinela que o painel entende como "saiu do player": limpa o "Assistindo" na hora. */
const val HEARTBEAT_IDLE_SENTINEL = "__idle__"

/** O que está tocando de verdade agora (player em tela cheia ou mini player). Compartilhado por toda a app. */
object NowPlaying {
    @Volatile var title: String? = null
        private set

    /** Atualiza o que está tocando e avisa o painel NA HORA (sem esperar o ciclo de 60s). */
    fun update(mac: String, newTitle: String?) {
        val next = newTitle?.takeIf { it.isNotBlank() }
        if (next == title) return
        title = next
        if (mac.isNotBlank()) AppIntegrationRepository.heartbeatNow(mac, next)
    }
}

data class RemoteAppConfig(
    val registered: Boolean,
    val allowed: Boolean,
    val mac: String,
    val appId: String,
    val appName: String,
    val status: String,
    val expiration: String,
    val logoUrl: String,
    val bannerUrl: String,
    val backgroundUrl: String,
    val messageTitle: String,
    val messageText: String,
    val messageImageUrl: String,
    val iconLiveTv: String,
    val iconMovies: String,
    val iconSeries: String,
    val serverApiUrl: String,
    val dnsUrl: String,
    val testApiUrl: String,
    val epgUrl: String,
    val playlistUrls: List<String>,
    val apkDownloadUrl: String,
    val apkVersion: String,
)

data class RemoteNotification(val id: Long, val severity: String, val title: String, val message: String)
data class RemoteCommand(val id: Long, val command: String, val payload: JSONObject)
data class UpdateInfo(val available: Boolean, val version: String, val url: String)
data class WatchingInfo(val title: String, val updatedAt: String)
data class ServerTestResult(val ok: Boolean, val httpCode: Int, val contentType: String, val message: String)

class AppIntegrationRepository {
    private val executor = Executors.newFixedThreadPool(3)
    private val heartbeat: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var syncFuture: ScheduledFuture<*>? = null

    fun fetchConfig(mac: String, callback: (Result<RemoteAppConfig>) -> Unit) {
        // Rota própria do Ominus no painel. Se o MAC não estiver vinculado a ele (403/404) ou der erro,
        // cai no caminho antigo (check_mac / device/check) para não deixar cliente antigo sem acesso.
        getAsync("/api/v5/apps/$OMINUS_APP_ID/config?mac=${encode(mac)}") { own ->
            val ownConfig = own.getOrNull()?.let { parseGenericConfig(it, mac) }
            if (ownConfig != null && ownConfig.registered) {
                callback(Result.success(ownConfig))
            } else {
                fetchLegacyMacConfig(mac, callback)
            }
        }
    }

    private fun fetchLegacyMacConfig(mac: String, callback: (Result<RemoteAppConfig>) -> Unit) {
        getAsync("/api/v5/check_mac.php?mac=${encode(mac)}") { result ->
            result.onSuccess { json ->
                val config = parseMaximusConfig(json, mac)
                if (config.registered || config.playlistUrls.isNotEmpty()) {
                    callback(Result.success(config))
                } else {
                    fetchDeviceConfig(mac, callback)
                }
            }.onFailure {
                fetchDeviceConfig(mac, callback)
            }
        }
    }

    private fun fetchDeviceConfig(mac: String, callback: (Result<RemoteAppConfig>) -> Unit) {
        getAsync("/api/device/check?mac=${encode(mac)}") { result ->
            callback(result.map { parseMaximusConfig(it, mac) })
        }
    }

    fun fetchLegacyConfig(mac: String, callback: (Result<RemoteAppConfig>) -> Unit) {
        getAsync("/api/v5/apps/$OMINUS_APP_ID/config?mac=${encode(mac)}") { result ->
            callback(result.map { parseGenericConfig(it, mac) })
        }
    }

    fun checkDevice(mac: String, callback: (Result<JSONObject>) -> Unit) {
        getAsync("/api/device/check?mac=${encode(mac)}", callback)
    }

    fun fetchCurrentWatching(mac: String, callback: (Result<WatchingInfo?>) -> Unit) {
        getAsync("/api/v5/current-watching?mac=${encode(mac)}") { result ->
            callback(result.map { json ->
                val root = json.optJSONObject("data") ?: json
                val title = root.optString("current_content", root.optString("title"))
                if (title.isBlank()) null else WatchingInfo(title, root.optString("updated_at"))
            })
        }
    }

    fun fetchNotifications(mac: String, callback: (Result<List<RemoteNotification>>) -> Unit) {
        getAsync("/api/v5/list-notifications?mac=${encode(mac)}") { result ->
            callback(result.map { json ->
                val root = json.optJSONObject("data") ?: json
                val array = root.optJSONArray("notifications") ?: json.optJSONArray("notifications") ?: JSONArray()
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        add(RemoteNotification(
                            id = item.optLong("id"),
                            severity = item.optString("severity", "info"),
                            title = item.optString("title"),
                            message = item.optString("message"),
                        ))
                    }
                }
            })
        }
    }

    fun fetchRemoteCommands(mac: String, callback: (Result<List<RemoteCommand>>) -> Unit) {
        getAsync("/api/v5/remote-commands?mac=${encode(mac)}") { result ->
            callback(result.map { json ->
                val root = json.optJSONObject("data") ?: json
                val array = root.optJSONArray("commands") ?: json.optJSONArray("commands") ?: JSONArray()
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        add(RemoteCommand(item.optLong("id"), item.optString("command"), item.optJSONObject("payload") ?: JSONObject()))
                    }
                }
            })
        }
    }

    fun ackNotification(mac: String, alertId: Long) {
        postAsync("/api/v5/list-notifications/ack", JSONObject().apply {
            put("mac", mac)
            put("alert_id", alertId)
        }) { }
    }

    fun ackCommand(mac: String, commandId: Long, status: String, message: String = "") {
        postAsync("/api/v5/remote-commands/ack", JSONObject().apply {
            put("mac", mac)
            put("command_id", commandId)
            put("status", status)
            put("result_message", message)
        }) { }
    }

    fun reportPlaybackFailure(mac: String, activeListNumber: Int? = null, callback: (Result<JSONObject>) -> Unit = {}) {
        postAsync("/api/v5/playback-failure", JSONObject().apply {
            put("mac", mac)
            activeListNumber?.let { put("active_list_number", it) }
        }, callback)
    }

    fun reportMaximusTestResult(payload: JSONObject, callback: (Result<JSONObject>) -> Unit = {}) {
        postAsync("/api/v5/maximus-test-result", payload, callback)
    }

    fun testExternalApi(urlString: String, callback: (Result<ServerTestResult>) -> Unit) {
        executor.execute {
            callback(runCatching {
                val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "MaximusTVPlayer/1.0 AndroidTV")
                }
                val status = connection.responseCode
                val contentType = connection.contentType.orEmpty()
                val preview = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readLine().orEmpty() }.orEmpty()
                connection.disconnect()
                val html = preview.trimStart().startsWith("<html", true) || preview.trimStart().startsWith("<!doctype", true)
                ServerTestResult(status in 200..299 && !html, status, contentType, if (html) "Resposta HTML/bloqueio" else "Resposta recebida")
            })
        }
    }

    fun sendHeartbeat(mac: String, currentContent: String? = null) {
        getAsync(heartbeatPath(mac, currentContent)) { }
    }

    fun startBackgroundSync(
        mac: String,
        currentContent: () -> String?,
        onNotifications: (List<RemoteNotification>) -> Unit,
        onCommands: (List<RemoteCommand>) -> Unit,
    ) {
        stopHeartbeat()
        syncNow(mac, currentContent, onNotifications, onCommands)
        syncFuture = heartbeat.scheduleAtFixedRate({
            syncNow(mac, currentContent, onNotifications, onCommands)
        }, 60, 60, TimeUnit.SECONDS)
    }

    private fun syncNow(
        mac: String,
        currentContent: () -> String?,
        onNotifications: (List<RemoteNotification>) -> Unit,
        onCommands: (List<RemoteCommand>) -> Unit,
    ) {
        sendHeartbeat(mac, currentContent())
        fetchNotifications(mac) { it.onSuccess(onNotifications) }
        fetchRemoteCommands(mac) { it.onSuccess(onCommands) }
    }

    fun stopHeartbeat() {
        syncFuture?.cancel(true)
        syncFuture = null
    }

    fun checkUpdate(mac: String, callback: (Result<UpdateInfo>) -> Unit) {
        // /maximus-update recusa MAC que não é do Maximus (403): o Ominus nunca recebia atualização.
        getAsync("/api/v5/apps/$OMINUS_APP_ID/update?mac=${encode(mac)}") { result ->
            callback(result.map { json ->
                UpdateInfo(
                    available = json.optBoolean("update_available", false),
                    version = json.optString("latest_version", json.optString("version")),
                    url = json.optString("apk_download_url", json.optString("apk_link", json.optString("url"))),
                )
            })
        }
    }

    fun fetchDnsList(callback: (Result<List<String>>) -> Unit) {
        getAsync("/api/v5/getdns_list") { result ->
            callback(result.map { json ->
                val array = json.optJSONArray("data") ?: json.optJSONArray("dns") ?: JSONArray()
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.opt(index)
                        val value = if (item is JSONObject) item.optString("url", item.optString("dns")) else item.toString()
                        if (value.startsWith("http", true)) add(value)
                    }
                }
            })
        }
    }

    fun shutdown() {
        stopHeartbeat()
        heartbeat.shutdownNow()
        executor.shutdownNow()
    }

    private fun parseMaximusConfig(json: JSONObject, fallbackMac: String): RemoteAppConfig {
        val root = payloadRoot(json)
        val found = if (root.has("found")) root.optBoolean("found") else if (root.has("registered")) root.optBoolean("registered") else root.has("mac") || root.has("urlM3u8") || root.has("playlist_url")
        val allowed = if (root.has("allowed")) root.optBoolean("allowed") else root.optString("status").lowercase() in setOf("active", "online", "liberado", "allowed", "liberated")
        val status = root.optString("status", "")
        val blockedStatus = status.lowercase() in setOf("blocked", "bloqueado", "expired", "expirado", "denied", "negado")
        val playlist = firstString(root, "urlM3u8", "playlist_url", "playlistUrl", "m3u_url", "url")
        val epg = firstString(root, "urlEpg", "urlEpg", "epg_url", "url_epg")
        val playlistUrls = when {
            playlist.startsWith("http", true) -> listOf(playlist)
            root.optJSONArray("playlist_urls") != null -> parsePlaylistArray(root.optJSONArray("playlist_urls"))
            root.optJSONArray("playlists") != null -> parsePlaylistArray(root.optJSONArray("playlists"))
            else -> emptyList()
        }
        return RemoteAppConfig(
            registered = found,
            allowed = found && allowed && !blockedStatus,
            mac = root.optString("mac", fallbackMac),
            appId = root.optString("app_id", OMINUS_APP_ID),
            appName = root.optString("app_name", root.optString("app", "OMINUS")),
            status = status,
            expiration = root.optString("dataExpiracao", root.optString("expiration", "")),
            logoUrl = root.optString("logo_url"),
            bannerUrl = root.optString("banner_url"),
            backgroundUrl = root.optString("background_url"),
            messageTitle = root.optString("message_title"),
            messageText = root.optString("message_text"),
            messageImageUrl = root.optString("message_image_url"),
            iconLiveTv = root.optString("icon_live_tv"),
            iconMovies = root.optString("icon_movies"),
            iconSeries = root.optString("icon_series"),
            serverApiUrl = root.optString("server_api_url", root.optString("dns_url")),
            dnsUrl = root.optString("dns_url"),
            testApiUrl = root.optString("test_api_url"),
            epgUrl = epg,
            playlistUrls = playlistUrls,
            apkDownloadUrl = root.optString("apk_download_url"),
            apkVersion = root.optString("apk_version"),
        )
    }

    private fun parseGenericConfig(json: JSONObject, fallbackMac: String): RemoteAppConfig {
        val root = json.optJSONObject("data") ?: json
        val icons = root.optJSONObject("icons") ?: JSONObject()
        return RemoteAppConfig(
            registered = root.optBoolean("registered", true),
            allowed = root.optBoolean("allowed", true),
            mac = root.optString("mac", fallbackMac),
            appId = root.optString("app_id", OMINUS_APP_ID),
            appName = root.optString("app_name", "OMINUS"),
            status = root.optString("status"),
            expiration = root.optString("dataExpiracao", root.optString("expiration")),
            logoUrl = root.optString("logo_url"),
            bannerUrl = root.optString("banner_url"),
            backgroundUrl = root.optString("background_url"),
            messageTitle = root.optString("message_title"),
            messageText = root.optString("message_text"),
            messageImageUrl = root.optString("message_image_url"),
            iconLiveTv = icons.optString("live_tv"),
            iconMovies = icons.optString("movies"),
            iconSeries = icons.optString("series"),
            serverApiUrl = root.optString("server_api_url"),
            dnsUrl = root.optString("dns_url"),
            testApiUrl = root.optString("test_api_url"),
            epgUrl = root.optString("urlEpg", root.optString("epg_url")),
            playlistUrls = parsePlaylistArray(root.optJSONArray("playlist_urls")),
            apkDownloadUrl = root.optString("apk_download_url"),
            apkVersion = root.optString("apk_version"),
        )
    }

    private fun payloadRoot(json: JSONObject): JSONObject {
        val data = json.opt("data")
        return when (data) {
            is JSONObject -> data
            is JSONArray -> data.optJSONObject(0) ?: json
            else -> json
        }
    }

    private fun firstString(root: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = root.optString(key, "").trim()
            if (value.startsWith("http", true)) return value
        }
        return ""
    }

    private fun parsePlaylistArray(array: JSONArray?): List<String> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.opt(index)
            val value = if (item is JSONObject) firstString(item, "url", "playlist_url", "playlistUrl", "urlM3u8") else item.toString().trim()
            if (value.startsWith("http", true)) add(value)
        }
    }

    private fun getAsync(path: String, callback: (Result<JSONObject>) -> Unit) {
        executor.execute { callback(runCatching { request("GET", path, null) }) }
    }

    private fun postAsync(path: String, body: JSONObject, callback: (Result<JSONObject>) -> Unit) {
        executor.execute { callback(runCatching { request("POST", path, body) }) }
    }

    private fun request(method: String, path: String, body: JSONObject?): JSONObject = panelRequest(method, path, body)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        // Fila única: "tocando X" e "saiu" chegam ao painel na mesma ordem em que aconteceram.
        private val quickExecutor = Executors.newSingleThreadExecutor()

        /** Caminho do heartbeat: sem conteúdo manda a sentinela explícita, senão o painel mantém o "Assistindo" velho. */
        fun heartbeatPath(mac: String, currentContent: String?): String {
            val content = currentContent?.takeIf { it.isNotBlank() } ?: HEARTBEAT_IDLE_SENTINEL
            return "/api/v5/heartbeat?mac=${URLEncoder.encode(mac, "UTF-8")}&current_content=${URLEncoder.encode(content, "UTF-8")}"
        }

        /** Heartbeat imediato, independente de qualquer instância (usado quando o que está tocando muda). */
        fun heartbeatNow(mac: String, currentContent: String?) {
            quickExecutor.execute { runCatching { panelRequest("GET", heartbeatPath(mac, currentContent), null) } }
        }
    }
}

private class PanelHttpException(val status: Int) : RuntimeException("Integração HTTP $status")

/** Tenta o painel no Railway e só cai no Manus se o primeiro falhar (exceção, HTML ou HTTP fora de 2xx). */
private fun panelRequest(method: String, path: String, body: JSONObject?): JSONObject {
    val primary = runCatching { singlePanelRequest(RENCIA_BASE_URL, method, path, body) }
    primary.getOrNull()?.let { return it }
    // O Railway respondeu com um erro "de verdade" (ex.: 404 MAC não cadastrado): é a resposta, não adianta perguntar ao Manus.
    (primary.exceptionOrNull() as? PanelHttpException)?.takeIf { it.status in 400..499 }?.let { throw it }
    val fallback = runCatching { singlePanelRequest(RENCIA_BASE_URL_FALLBACK, method, path, body) }
    fallback.getOrNull()?.let { return it }
    throw primary.exceptionOrNull() ?: fallback.exceptionOrNull() ?: IllegalStateException("Painel indisponível")
}

private fun singlePanelRequest(baseUrl: String, method: String, path: String, body: JSONObject?): JSONObject {
    val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 6_000
        readTimeout = 10_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "OminusTVPlayer/1.0 AndroidTV")
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        }
    }
    try {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty().trim()
        if (text.startsWith("<html", true) || text.startsWith("<!doctype", true)) error("Resposta HTML inválida da integração")
        if (status !in 200..299) throw PanelHttpException(status)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    } finally {
        connection.disconnect()
    }
}
