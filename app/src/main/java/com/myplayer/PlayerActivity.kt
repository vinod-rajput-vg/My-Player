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
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility: Int ->
                    applyControllerVisibility(visibility)
                    scheduleControllerPreferenceApply()
                }
            )
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
                        scheduleControllerPreferenceApply()
                    }
                })
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                playerView.post {
                    scheduleControllerPreferenceApply()
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
                    seekByRemote(-prefs.getInt("seek_interval", 10) * 1000L)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    seekByRemote(prefs.getInt("seek_interval", 10) * 1000L)
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

    private fun seekByRemote(offsetMs: Long) {
        val exoPlayer = player ?: return
        // A live stream without seeking support must not reveal the controller.
        if (exoPlayer.isCurrentMediaItemLive && !exoPlayer.isCurrentMediaItemSeekable) return
        val current = exoPlayer.currentPosition.coerceAtLeast(0L)
        val target = (current + offsetMs).coerceAtLeast(0L)
        val duration = exoPlayer.duration
        exoPlayer.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
        // Seek silently while the controller is hidden. Do not call showController(),
        // otherwise Media3 reveals the buttons and timeline on every D-pad press.
        scheduleControllerPreferenceApply()
    }

    private fun applyButtonVisibility() {
        if (!::playerView.isInitialized) return

        // Apply preferences to the actual Media3 controller buttons after its layout exists.
        val mapping = listOf(
            androidx.media3.ui.R.id.exo_prev to "previous",
            androidx.media3.ui.R.id.exo_play to "play_pause",
            androidx.media3.ui.R.id.exo_pause to "play_pause",
            androidx.media3.ui.R.id.exo_next to "next"
        )
        // Remove any optional Settings control from Media3's built-in controller.
        // Different Media3 layouts/versions may use different settings-related IDs.
        listOf(
            "exo_settings",
            "exo_settings_button",
            "exo_overflow_show",
            "exo_overflow_hide"
        ).forEach { name ->
            val id = resources.getIdentifier(name, "id", packageName)
                .takeIf { it != 0 }
                ?: resources.getIdentifier(name, "id", "androidx.media3.ui")
            if (id != 0) {
                playerView.findViewById<View?>(id)?.apply {
                    visibility = View.GONE
                    isEnabled = false
                    isFocusable = false
                    isClickable = false
                }
            }
        }

        // Permanently remove every Media3 rewind/fast-forward variant, including
        // amount-label wrappers used by some controller layouts (e.g. "Rewind 5 seconds").
        val seekControlNames = setOf(
            "exo_rew", "exo_ffwd",
            "exo_rew_with_amount", "exo_ffwd_with_amount",
            "exo_rew_container", "exo_ffwd_container",
            "exo_rew_button", "exo_ffwd_button"
        )
        fun hideSeekControls(view: View) {
            val entryName = if (view.id != View.NO_ID) {
                runCatching { resources.getResourceEntryName(view.id) }.getOrNull()
            } else null
            if (entryName in seekControlNames) {
                view.visibility = View.GONE
                view.isEnabled = false
                view.isFocusable = false
                view.isClickable = false
                view.clearFocus()
                return
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) {
                    hideSeekControls(view.getChildAt(index))
                }
            }
        }
        hideSeekControls(playerView)

        mapping.forEach { (viewId, settingKey) ->
            val button = playerView.findViewById<View?>(viewId) ?: return@forEach
            val visible = prefs.getBoolean("button_$settingKey", true)

            // Rewind/forward controls can include a wrapper or amount label in Media3's
            // controller layout. Hide the whole control slot, not only its inner button.
            val target = if (!visible &&
                (settingKey == "rewind" || settingKey == "fast_forward") &&
                button.parent is android.view.ViewGroup
            ) button.parent as View else button

            target.visibility = if (visible) View.VISIBLE else View.GONE
            target.isEnabled = visible
            target.isFocusable = visible
            target.isClickable = visible
            button.visibility = if (visible) View.VISIBLE else View.GONE
            button.isEnabled = visible
            button.isFocusable = visible
            button.isClickable = visible
            if (!visible) {
                button.clearFocus()
                target.clearFocus()
            }
        }

        // The default Media3 controller draws a dark bottom scrim behind the time/progress row.
        // Clear backgrounds on the controller bars and time labels, not just the text widgets.
        val transparentIds = listOf(
            androidx.media3.ui.R.id.exo_controller,
            androidx.media3.ui.R.id.exo_bottom_bar,
            androidx.media3.ui.R.id.exo_time,
            androidx.media3.ui.R.id.exo_progress,
            androidx.media3.ui.R.id.exo_position,
            androidx.media3.ui.R.id.exo_duration
        )
        transparentIds.forEach { viewId ->
            playerView.findViewById<View?>(viewId)?.apply {
                setBackgroundColor(Color.TRANSPARENT)
                background?.alpha = 0
            }
        }
    }

    private fun applyControllerVisibility(visibility: Int) {
        if (!::playerView.isInitialized) return
        val shown = visibility == View.VISIBLE
        // Media3 fades the controller; hide the timeline immediately so it cannot linger
        // onscreen after the transport buttons disappear.
        listOf(
            androidx.media3.ui.R.id.exo_progress,
            androidx.media3.ui.R.id.exo_position,
            androidx.media3.ui.R.id.exo_duration,
            androidx.media3.ui.R.id.exo_time
        ).forEach { id ->
            playerView.findViewById<View?>(id)?.visibility =
                if (shown) View.VISIBLE else View.GONE
        }
    }

    private fun scheduleControllerPreferenceApply() {
        if (!::playerView.isInitialized) return
        playerView.post {
            applyButtonVisibility()
            // Media3 can recreate/update controller views after showing the controls.
            playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_controller)
                ?.post { applyButtonVisibility() }
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
