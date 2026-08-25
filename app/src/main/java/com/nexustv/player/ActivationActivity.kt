package com.nexustv.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

class ActivationActivity : Activity() {
    private lateinit var mac: String
    private lateinit var status: TextView
    private lateinit var verifyButton: TextView
    private lateinit var connectButton: TextView
    private lateinit var extraSettingsButton: TextView
    private lateinit var connectionProgress: ProgressBar
    private lateinit var connectionPercent: TextView
    private lateinit var connectionClock: TextView
    private lateinit var connectionMessage: TextView
    private var checking = false
    private var loadingStartedAt = 0L
    private var mainOpened = false
    private var keepImporterAlive = false
    // Cada tentativa de conexão (painel ou manual) ganha um número novo. Callbacks
    // assíncronos só aplicam o resultado se ainda forem a tentativa mais recente --
    // isso evita que uma checagem antiga do painel, que só termina depois do
    // usuário já ter confirmado DNS/usuário/senha, sobrescreva ou bloqueie a
    // tentativa manual mais nova.
    private var connectionGeneration = 0
    private val integration = AppIntegrationRepository()
    private val playlistRepository by lazy { PlaylistRepository(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var loadingPanelList = false
    private val clockTicker = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1_000)
        }
    }

    private val periodicCheck = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val manualMode = prefs.getString(PREF_SOURCE_MODE, SOURCE_PANEL) == SOURCE_MANUAL
            if (!manualMode && !checking && !loadingPanelList) {
                verifyAccess(false)
            }
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        setContentView(R.layout.activity_activation)

        mac = DeviceIdentifier.resolve(this)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_MAC_ADDRESS, mac).apply()
        val macValue = findViewById<TextView>(R.id.macValue)
        val macFormatted = findViewById<TextView>(R.id.macFormatted)
        macValue.text = mac
        macFormatted.text = "12 caracteres hexadecimais • toque para copiar"
        status = findViewById(R.id.activationStatus)
        verifyButton = findViewById(R.id.recheckButton)
        connectButton = findViewById(R.id.connectButton)
        extraSettingsButton = findViewById(R.id.extraSettingsButton)
        connectionProgress = findViewById(R.id.connectionProgress)
        connectionPercent = findViewById(R.id.connectionPercent)
        connectionClock = findViewById(R.id.connectionClock)
        connectionMessage = findViewById(R.id.connectionMessage)
        loadingStartedAt = SystemClock.elapsedRealtime()
        handler.post(clockTicker)
        macValue.setOnClickListener { copyMac() }
        macFormatted.setOnClickListener { copyMac() }
        findViewById<TextView>(R.id.copyMacButton).setOnClickListener { copyMac() }
        connectButton.setOnClickListener { verifyAccess(true) }
        verifyButton.setOnClickListener {
            playlistRepository.invalidateCacheFreshness()
            verifyAccess(true)
        }
        extraSettingsButton.setOnClickListener { showExtraSettingsDialog() }
        connectButton.requestFocus()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val manualDns = prefs.getString(PREF_MANUAL_DNS, "").orEmpty()
        val manualUser = prefs.getString(PREF_MANUAL_USER, "").orEmpty()
        val manualPassword = prefs.getString(PREF_MANUAL_PASSWORD, "").orEmpty()
        val manualConfigured = manualDns.isNotBlank() && manualUser.isNotBlank() && manualPassword.isNotBlank()
        if (prefs.getString(PREF_SOURCE_MODE, SOURCE_PANEL) == SOURCE_MANUAL && manualConfigured) {
            // Configuração extra já salva: conecta direto, sem depender do painel/MAC.
            setConnectionProgress(0, "Conectando com a configuração extra (DNS/usuário/senha)...")
            connectManual(manualDns, manualUser, manualPassword, showProgress = false)
        } else {
            setConnectionProgress(0, "Aguardando conexão com o painel...")
            verifyAccess(false)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                -> {
                    val targets = listOf(
                        findViewById<View>(R.id.macValue),
                        findViewById<View>(R.id.copyMacButton),
                        connectButton,
                        verifyButton,
                        extraSettingsButton,
                    )
                    val current = targets.indexOf(currentFocus).coerceAtLeast(0)
                    val delta = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                    return targets[(current + delta).coerceIn(0, targets.lastIndex)].requestFocus()
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    val focused = currentFocus
                    if (focused != null && focused.isShown && focused.isEnabled && focused.isClickable) {
                        focused.performClick()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun updateClock() {
        if (!::connectionClock.isInitialized || loadingStartedAt == 0L) return
        val elapsed = ((SystemClock.elapsedRealtime() - loadingStartedAt) / 1000L).coerceAtLeast(0L)
        connectionClock.text = "◷ %02d:%02d".format(Locale.US, elapsed / 60, elapsed % 60)
    }

    private fun setConnectionProgress(value: Int, message: String) {
        if (!::connectionProgress.isInitialized) return
        connectionProgress.progress = value.coerceIn(0, 100)
        connectionPercent.text = "${value.coerceIn(0, 100)}%"
        connectionMessage.text = message
    }

    private fun explainFailure(reason: String): String {
        val safeReason = reason
            .replace(Regex("([?&](username|password)=)[^&\\s]+", RegexOption.IGNORE_CASE), "$1***")
            .replace(Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE), "servidor da lista")
        return when {
            reason.contains("403") -> "A lista foi recusada pelo servidor (HTTP 403). Verifique a URL/credenciais no painel."
            reason.contains("522") -> "O servidor da lista não respondeu (HTTP 522). Tentando novamente ou usando o cache local."
            reason.contains("timeout", true) || reason.contains("timed out", true) -> "O servidor da lista demorou demais para responder."
            reason.contains("HTML", true) -> "O servidor devolveu uma página de bloqueio, não uma lista M3U."
            safeReason.isNotBlank() -> "Falha ao baixar a lista do painel: $safeReason"
            else -> "Falha ao baixar a lista do painel."
        }
    }

    private fun copyMac() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MAC do dispositivo", mac))
        Toast.makeText(this, "MAC copiado", Toast.LENGTH_SHORT).show()
    }

    private fun showExtraSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_extra_settings, null)
        val dnsInput = view.findViewById<EditText>(R.id.extraDnsInput)
        val userInput = view.findViewById<EditText>(R.id.extraUserInput)
        val passwordInput = view.findViewById<EditText>(R.id.extraPasswordInput)
        val errorLabel = view.findViewById<TextView>(R.id.extraSettingsError)
        val cancelButton = view.findViewById<TextView>(R.id.extraCancelButton)
        val confirmButton = view.findViewById<TextView>(R.id.extraConfirmButton)
        val usePanelButton = view.findViewById<TextView>(R.id.extraUsePanelButton)

        dnsInput.setText(prefs.getString(PREF_MANUAL_DNS, ""))
        userInput.setText(prefs.getString(PREF_MANUAL_USER, ""))
        passwordInput.setText(prefs.getString(PREF_MANUAL_PASSWORD, ""))

        val currentlyManual = prefs.getString(PREF_SOURCE_MODE, SOURCE_PANEL) == SOURCE_MANUAL
        usePanelButton.visibility = if (currentlyManual) View.VISIBLE else View.GONE

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setView(view)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnShowListener {
            val displayWidth = resources.displayMetrics.widthPixels
            dialog.window?.setLayout(
                (displayWidth * 0.9f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }

        cancelButton.setOnClickListener { dialog.dismiss() }

        usePanelButton.setOnClickListener {
            prefs.edit().putString(PREF_SOURCE_MODE, SOURCE_PANEL).apply()
            dialog.dismiss()
            Toast.makeText(this, "Usando o painel/MAC novamente", Toast.LENGTH_SHORT).show()
            verifyAccess(true)
        }

        confirmButton.setOnClickListener {
            val dns = normalizeDns(dnsInput.text?.toString().orEmpty())
            val user = userInput.text?.toString()?.trim().orEmpty()
            val password = passwordInput.text?.toString()?.trim().orEmpty()
            if (dns.isBlank() || user.isBlank() || password.isBlank()) {
                errorLabel.text = "Preencha DNS, usuário e senha."
                errorLabel.visibility = View.VISIBLE
                return@setOnClickListener
            }
            prefs.edit()
                .putString(PREF_SOURCE_MODE, SOURCE_MANUAL)
                .putString(PREF_MANUAL_DNS, dns)
                .putString(PREF_MANUAL_USER, user)
                .putString(PREF_MANUAL_PASSWORD, password)
                .apply()
            dialog.dismiss()
            connectManual(dns, user, password, showProgress = true)
        }

        dialog.show()
        dnsInput.requestFocus()
    }

    private fun normalizeDns(raw: String): String {
        var value = raw.trim().trimEnd('/')
        if (value.isBlank()) return ""
        if (!value.contains("://")) value = "http://$value"
        return value
    }

    private fun buildXtreamUrl(dns: String, user: String, password: String): String {
        val encodedUser = URLEncoder.encode(user, "UTF-8")
        val encodedPassword = URLEncoder.encode(password, "UTF-8")
        return "$dns/get.php?username=$encodedUser&password=$encodedPassword&type=m3u_plus&output=ts"
    }

    private fun connectManual(dns: String, user: String, password: String, showProgress: Boolean) {
        // Uma tentativa manual explícita sempre assume prioridade: não é bloqueada
        // por uma checagem de painel ainda em andamento (ex.: a verificação
        // automática que já dispara sozinha ao abrir o app).
        val myGeneration = ++connectionGeneration
        checking = true
        loadingPanelList = true
        status.text = if (showProgress) "Conectando com a configuração extra..." else "Conectando direto por DNS/usuário/senha..."
        status.setTextColor(getColor(R.color.text_secondary))
        setConnectionProgress(15, "Conectando direto por DNS/usuário/senha (sem depender do painel)...")
        verifyButton.isEnabled = false
        connectButton.isEnabled = false
        extraSettingsButton.isEnabled = false
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_IMPORT_IN_PROGRESS, true)
            .apply()
        val manualUrl = buildXtreamUrl(dns, user, password)
        playlistRepository.loadIfChanged(
            listOf(manualUrl),
            onProgress = { progress ->
                runOnUiThread {
                    if (myGeneration != connectionGeneration) return@runOnUiThread
                    setConnectionProgress(progress, if (progress >= 95) "Finalizando catálogo..." else "Organizando canais, filmes e séries...")
                }
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(PREF_IMPORT_PROGRESS_PERCENT, progress).apply()
            },
            onCatalogReady = { stats ->
                runOnUiThread {
                    if (myGeneration != connectionGeneration) return@runOnUiThread
                    if (!mainOpened && stats.total > 0) {
                        setConnectionProgress(86, "Catálogo inicial pronto. Organizando o restante em segundo plano...")
                        status.text = "Catálogo inicial pronto. Abrindo NEXUS..."
                        status.setTextColor(getColor(R.color.success))
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putBoolean(PREF_ACCESS_ALLOWED, true)
                            .apply()
                        keepImporterAlive = true
                        openMainActivity(importInProgress = true)
                    }
                }
            },
            callback = { playlistResult ->
                runOnUiThread {
                    if (myGeneration != connectionGeneration) return@runOnUiThread
                    checking = false
                    loadingPanelList = false
                    verifyButton.isEnabled = true
                    connectButton.isEnabled = true
                    extraSettingsButton.isEnabled = true
                    playlistResult.onSuccess {
                        setConnectionProgress(100, "Conectado. Em breve você terá em mãos o melhor conteúdo para assistir.")
                        status.text = "Catálogo completo."
                        status.setTextColor(getColor(R.color.success))
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putBoolean(PREF_ACCESS_ALLOWED, true)
                            .putBoolean(PREF_IMPORT_IN_PROGRESS, false)
                            .apply()
                        handler.removeCallbacks(periodicCheck)
                        if (mainOpened) {
                            keepImporterAlive = false
                            playlistRepository.shutdown()
                            finish()
                        } else {
                            openMainActivity(importInProgress = false)
                        }
                    }.onFailure {
                        if (mainOpened) {
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .putBoolean(PREF_IMPORT_IN_PROGRESS, false)
                                .apply()
                            keepImporterAlive = false
                            playlistRepository.shutdown()
                            finish()
                        } else {
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .putBoolean(PREF_IMPORT_IN_PROGRESS, false)
                                .apply()
                            val reason = it.message.orEmpty()
                            val message = explainFailure(reason)
                            setConnectionProgress(40, message)
                            status.text = message
                            status.setTextColor(getColor(R.color.warning))
                        }
                    }
                }
            },
        )
    }

    private fun verifyAccess(showProgress: Boolean) {
        val myGeneration = ++connectionGeneration
        checking = true
        if (showProgress) status.text = "Consultando o painel..."
        setConnectionProgress(10, "Conectando sua lista de filmes, séries e canais...")
        verifyButton.isEnabled = false
        connectButton.isEnabled = false
        integration.fetchConfig(mac) { result ->
            runOnUiThread {
                if (myGeneration != connectionGeneration) return@runOnUiThread
                checking = false
                verifyButton.isEnabled = true
                connectButton.isEnabled = true
                result.onSuccess { config ->
                    if (!config.registered || !config.allowed) {
                        setConnectionProgress(20, "Aguardando o cadastro deste MAC no painel...")
                        status.text = "Aguardando cadastro e liberação no painel..."
                        status.setTextColor(getColor(R.color.warning))
                        return@onSuccess
                    }
                    if (config.playlistUrls.isEmpty()) {
                        setConnectionProgress(30, "MAC liberado. Aguardando a lista cadastrada no painel...")
                        status.text = "MAC liberado, aguardando a lista cadastrada no painel..."
                        status.setTextColor(getColor(R.color.warning))
                        return@onSuccess
                    }
                    loadingPanelList = true
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putBoolean(PREF_IMPORT_IN_PROGRESS, true)
                        .apply()
                    setConnectionProgress(60, "Lista encontrada. Conectando sua lista de filmes, séries e canais...")
                    verifyButton.isEnabled = false
                    connectButton.isEnabled = false
                    status.text = "Lista do painel encontrada. Carregando canais, filmes e séries..."
                            playlistRepository.loadIfChanged(
                        config.playlistUrls,
                        onProgress = { progress ->
                            runOnUiThread {
                                if (myGeneration != connectionGeneration) return@runOnUiThread
                                setConnectionProgress(progress, if (progress >= 95) "Finalizando catálogo..." else "Organizando canais, filmes e séries...")
                            }
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(PREF_IMPORT_PROGRESS_PERCENT, progress).apply()
                        },
                        onCatalogReady = { stats ->
                            runOnUiThread {
                                if (myGeneration != connectionGeneration) return@runOnUiThread
                                if (!mainOpened && stats.total > 0) {
                                    // O primeiro lote já foi COMMITADO. A tela principal pode
                                    // consultar SQLite enquanto o restante da M3U continua.
                                    setConnectionProgress(86, "Catálogo inicial pronto. Organizando o restante em segundo plano...")
                                    status.text = "Catálogo inicial pronto. Abrindo NEXUS..."
                                    status.setTextColor(getColor(R.color.success))
                                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                        .putBoolean(PREF_ACCESS_ALLOWED, true)
                                        .apply()
                                    keepImporterAlive = true
                                    openMainActivity(importInProgress = true)
                                }
                            }
                        },
                        callback = { playlistResult ->
                            runOnUiThread {
                                if (myGeneration != connectionGeneration) return@runOnUiThread
                                loadingPanelList = false
                                playlistResult.onSuccess {
                                    setConnectionProgress(100, "Conectado. Em breve você terá em mãos o melhor conteúdo para assistir.")
                                    status.text = "Catálogo completo."
                                    status.setTextColor(getColor(R.color.success))
                                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                        .putBoolean(PREF_ACCESS_ALLOWED, true)
                                        .putBoolean(PREF_IMPORT_IN_PROGRESS, false)
                                        .apply()
                                    handler.removeCallbacks(periodicCheck)
                                    if (mainOpened) {
                                        // A MainActivity já está visível; apenas liberar o
                                        // importador agora que seu trabalho terminou.
                                        keepImporterAlive = false
                                        playlistRepository.shutdown()
                                        finish()
                                    } else {
                                        openMainActivity(importInProgress = false)
                                    }
                                }.onFailure {
                                    if (mainOpened) {
                                        // O primeiro lote continua disponível para a MainActivity;
                                        // não devolver o usuário à tela de MAC por uma falha tardia.
                                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                            .putBoolean(PREF_IMPORT_IN_PROGRESS, false)
                                            .apply()
                                        keepImporterAlive = false
                                        playlistRepository.shutdown()
                                        finish()
                                    } else {
                                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                            .putBoolean(PREF_IMPORT_IN_PROGRESS, false)
                                            .apply()
                                        verifyButton.isEnabled = true
                                        connectButton.isEnabled = true
                                        val reason = it.message.orEmpty()
                                        val message = explainFailure(reason)
                                        setConnectionProgress(40, message)
                                        status.text = message
                                        status.setTextColor(getColor(R.color.warning))
                                    }
                                }
                            }
                        },
                    )
                }.onFailure {
                    setConnectionProgress(20, "O painel não respondeu. Tentando novamente automaticamente...")
                    status.text = "Não foi possível consultar o painel. Toque em CONECTAR para tentar novamente."
                    status.setTextColor(getColor(R.color.warning))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(periodicCheck)
        handler.postDelayed(periodicCheck, 5_000)
    }

    override fun onPause() {
        handler.removeCallbacks(periodicCheck)
        super.onPause()
    }

    private fun openMainActivity(importInProgress: Boolean) {
        if (mainOpened) return
        mainOpened = true
        handler.removeCallbacks(periodicCheck)
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_CATALOG_IMPORT_IN_PROGRESS, importInProgress)
        })
        // Sempre finaliza esta tela, mesmo com o import ainda em segundo plano:
        // keepImporterAlive (checado em onDestroy) garante que a importação
        // continua rodando de qualquer forma. Isso evita que o botão VOLTAR,
        // pressionado na tela principal antes do import 100% terminar, volte
        // para esta tela de ativação, que ficaria "por baixo" dela sem isso.
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        handler.removeCallbacks(clockTicker)
        if (!keepImporterAlive) playlistRepository.shutdown()
        integration.shutdown()
        super.onDestroy()
    }

    companion object {
        const val PREFS_NAME = "maximus_device_preferences"
        const val PREF_MAC_ADDRESS = "mac_address"
        const val PREF_IMPORT_IN_PROGRESS = "catalog_import_in_progress"
        const val PREF_IMPORT_PROGRESS_PERCENT = "catalog_import_progress_percent"
        private const val PREF_ACCESS_ALLOWED = "access_allowed"

        // Fonte alternativa (DNS/usuário/senha), independente do painel/MAC.
        const val PREF_SOURCE_MODE = "source_mode"
        const val PREF_MANUAL_DNS = "manual_dns"
        const val PREF_MANUAL_USER = "manual_user"
        const val PREF_MANUAL_PASSWORD = "manual_password"
        const val SOURCE_PANEL = "panel"
        const val SOURCE_MANUAL = "manual"
    }
}

object DeviceIdentifier {
    fun resolve(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray(Charsets.UTF_8))
        val compact = digest.take(6).joinToString("") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
        return format(compact)
    }

    fun format(compact: String): String = compact.replace(":", "").chunked(2).joinToString(":")
}
