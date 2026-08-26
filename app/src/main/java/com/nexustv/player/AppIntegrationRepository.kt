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

private const val RENCIA_BASE_URL = "https://renciaapp.manus.space"

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
        getAsync("/api/v5/apps/evolux/config?mac=${encode(mac)}") { result ->
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
        val path = buildString {
            append("/api/v5/heartbeat?mac=")
            append(encode(mac))
            if (!currentContent.isNullOrBlank()) {
                append("&current_content=")
                append(encode(currentContent))
            }
        }
        getAsync(path) { }
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
        getAsync("/api/v5/maximus-update?mac=${encode(mac)}") { result ->
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
            appId = root.optString("app_id", "maximus"),
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
            appId = root.optString("app_id", "evolux"),
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

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val connection = (URL(RENCIA_BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 7_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MaximusTVPlayer/1.0 AndroidTV")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty().trim()
        connection.disconnect()
        if (text.startsWith("<html", true) || text.startsWith("<!doctype", true)) error("Resposta HTML inválida da integração")
        if (status !in 200..299) error("Integração HTTP $status")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
