package com.myplayer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
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
            useController = true
            controllerAutoShow = true
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

        // Some streaming endpoints redirect to an HLS playlist but don't expose
        // ".m3u8" in the original URL. Explicit MIME type prevents Media3 from
        // incorrectly treating those endpoints as ordinary downloadable files.
        val path = android.net.Uri.parse(url).path.orEmpty()
        val isHlsEndpoint = path.endsWith(".m3u8", ignoreCase = true) ||
            (path.endsWith(".php", ignoreCase = true) &&
                android.net.Uri.parse(url).queryParameterNames.isNotEmpty())

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .apply {
                if (isHlsEndpoint) setMimeType(MimeTypes.APPLICATION_M3U8)
            }
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
                        val detail = error.cause?.message
                            ?: error.message
                            ?: "Unknown playback error"
                        Toast.makeText(
                            this@PlayerActivity,
                            "Playback failed: $detail",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
    }

    override fun onStop() {
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }
}
