package com.myplayer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {
    companion object {
        const val EXTRA_URL = "com.myplayer.EXTRA_URL"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private val prefs by lazy { getSharedPreferences("player_settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        playerView = PlayerView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            useController = true
            controllerAutoShow = true
            controllerShowTimeoutMs = prefs.getInt("hide_timeout", 3) * 1000
            controllerHideOnTouch = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        })

        // Some streaming endpoints redirect to an HLS playlist without a .m3u8 suffix.
        val parsedUri = android.net.Uri.parse(url)
        val path = parsedUri.path.orEmpty()
        val isHlsEndpoint = path.endsWith(".m3u8", ignoreCase = true) ||
            (path.endsWith(".php", ignoreCase = true) && parsedUri.queryParameterNames.isNotEmpty())

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .apply { if (isHlsEndpoint) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("MyPlayer/1.0 (Android TV)")

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val detail = error.cause?.message ?: error.message ?: "Unknown playback error"
                        Toast.makeText(this@PlayerActivity, "Playback failed: $detail", Toast.LENGTH_LONG).show()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        applyButtonVisibility()
                    }
                })
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                playerView.post {
                    applyButtonVisibility()
                    playerView.requestFocus()
                }
            }

        playerView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (playerView.isControllerFullyVisible) {
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                    } else {
                        playerView.showController()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    player?.seekBack()
                    playerView.showController()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    player?.seekForward()
                    playerView.showController()
                    true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    finishAffinity()
                    true
                }
                else -> false
            }
        }
    }

    private fun applyButtonVisibility() {
        if (!::playerView.isInitialized) return
        val mapping = listOf(
            androidx.media3.ui.R.id.exo_prev to "previous",
            androidx.media3.ui.R.id.exo_rew to "rewind",
            androidx.media3.ui.R.id.exo_play to "play_pause",
            androidx.media3.ui.R.id.exo_pause to "play_pause",
            androidx.media3.ui.R.id.exo_ffwd to "fast_forward",
            androidx.media3.ui.R.id.exo_next to "next"
        )
        mapping.forEach { (viewId, settingKey) ->
            playerView.findViewById<View?>(viewId)?.visibility =
                if (prefs.getBoolean("button_$settingKey", true)) View.VISIBLE else View.GONE
        }

        listOf(androidx.media3.ui.R.id.exo_position, androidx.media3.ui.R.id.exo_duration).forEach { viewId ->
            playerView.findViewById<View?>(viewId)?.apply {
                setBackgroundColor(Color.TRANSPARENT)
                (parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        finishAffinity()
    }

    override fun onStop() {
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }
}
