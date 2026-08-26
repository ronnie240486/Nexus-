package com.nexustv.player

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {
    private enum class ScaleMode(val label: String) { NORMAL("MODO: NORMAL"), STRETCH("MODO: ESTICAR"), ZOOM("MODO: ZOOM") }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var errorView: TextView
    private lateinit var scaleButton: TextView
    private lateinit var backButton: TextView
    private var scaleMode = ScaleMode.NORMAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        errorView = findViewById(R.id.playerError)
        scaleButton = findViewById(R.id.playerScaleButton)
        backButton = findViewById(R.id.playerBackButton)
        backButton.setOnClickListener { finish() }
        backButton.setOnFocusChangeListener { view, hasFocus ->
            view.background = rounded(if (hasFocus) 0xFF7B61FF.toInt() else 0xCC101827.toInt(), 10f)
            (view as TextView).setTextColor(if (hasFocus) Color.rgb(5, 6, 10) else Color.WHITE)
        }
        playerView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                playerView.showController()
                showBackButtonTemporarily()
            }
            false
        }
        showBackButtonTemporarily()
        scaleButton.background = rounded(0xCC101827.toInt(), 10f)
        scaleButton.setOnClickListener {
            scaleMode = when (scaleMode) {
                ScaleMode.NORMAL -> ScaleMode.STRETCH
                ScaleMode.STRETCH -> ScaleMode.ZOOM
                ScaleMode.ZOOM -> ScaleMode.NORMAL
            }
            applyScale()
        }
        scaleButton.setOnFocusChangeListener { view, hasFocus ->
            view.background = rounded(if (hasFocus) 0xFF7B61FF.toInt() else 0xCC101827.toInt(), 10f)
            (view as TextView).setTextColor(if (hasFocus) Color.rgb(5, 6, 10) else Color.WHITE)
        }
        playerView.post {
            playerView.requestFocus()
            playerView.showController()
        }
        applyScale()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val streamUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val mac = intent.getStringExtra(EXTRA_MAC).orEmpty()
        findViewById<TextView>(R.id.playerTitle).text = "OMINUS  •  $title"
        if (streamUrl.isBlank()) {
            showError("URL de reprodução não disponível")
            return
        }
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    if (mac.isNotBlank()) {
                        val integration = AppIntegrationRepository()
                        integration.reportPlaybackFailure(mac) { integration.shutdown() }
                    }
                    showError("Não foi possível reproduzir este conteúdo")
                }
            })
            exo.setMediaItem(MediaItem.fromUri(streamUrl))
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_UP,
                -> {
                    showBackButtonTemporarily()
                    return backButton.requestFocus()
                }
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_DOWN,
                -> {
                    showBackButtonTemporarily()
                    return scaleButton.requestFocus()
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    val focused = currentFocus
                    if (focused === playerView) {
                        if (player?.isPlaying == true) player?.pause() else player?.play()
                        playerView.showController()
                        showBackButtonTemporarily()
                        return true
                    }
                    if (focused != null && focused.isShown && focused.isEnabled && focused.isClickable) {
                        focused.performClick()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showBackButtonTemporarily() {
        backButton.visibility = View.VISIBLE
        backButton.removeCallbacks(hideBackButton)
        backButton.postDelayed(hideBackButton, 3_000L)
    }

    private val hideBackButton = Runnable { if (!isFinishing) backButton.visibility = View.GONE }

    private fun applyScale() {
        scaleButton.text = scaleMode.label
        playerView.resizeMode = when (scaleMode) {
            ScaleMode.NORMAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            ScaleMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            ScaleMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun showError(message: String) {
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    override fun onStop() {
        backButton.removeCallbacks(hideBackButton)
        player?.release()
        player = null
        super.onStop()
    }

    companion object {
        const val EXTRA_TITLE = "player_title"
        const val EXTRA_URL = "player_url"
        const val EXTRA_MAC = "player_mac"
    }
}
