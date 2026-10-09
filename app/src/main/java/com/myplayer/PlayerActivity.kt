package com.myplayer

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
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
import java.util.Locale

class PlayerActivity : Activity() {
    companion object { const val EXTRA_URL = "com.myplayer.EXTRA_URL" }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var controls: LinearLayout
    private lateinit var playPauseButton: TextView
    private lateinit var positionLabel: TextView
    private lateinit var remainingLabel: TextView
    private lateinit var progress: SeekBar
    private val prefs by lazy { getSharedPreferences("player_settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private var controlsHideDelay = 3000L

    private val hideControls = Runnable { if (::controls.isInitialized) controls.visibility = View.GONE }
    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) { finish(); return }

        playerView = PlayerView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }

        controlsHideDelay = prefs.getInt("hide_timeout", 3).coerceIn(1, 5) * 1000L
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(12), dp(28), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, 0xD9000000.toInt())
            )
            isFocusable = false
            visibility = View.GONE
        }
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        positionLabel = timeLabel("00:00")
        remainingLabel = timeLabel("-00:00")
        progress = SeekBar(this).apply {
            max = 1000
            isFocusable = true
            contentDescription = "Playback progress"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) { userSeeking = true; showControls() }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val p = player ?: return
                    if (p.duration > 0) p.seekTo((p.duration * seekBar.progress / 1000L))
                    userSeeking = false
                    showControls()
                }
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = player?.duration ?: 0L
                        if (duration > 0) {
                            positionLabel.text = formatTime(duration * value / 1000L)
                            remainingLabel.text = "-" + formatTime(duration - duration * value / 1000L)
                        }
                    }
                }
            })
        }
        seekRow.addView(positionLabel, LinearLayout.LayoutParams(dp(78), -2))
        seekRow.addView(progress, LinearLayout.LayoutParams(0, dp(42), 1f))
        seekRow.addView(remainingLabel, LinearLayout.LayoutParams(dp(90), -2))
        controls.addView(seekRow, LinearLayout.LayoutParams(-1, -2))

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val previousButton = controlButton("⏮", "Previous") {
            player?.seekToPreviousMediaItem()
            showControls()
        }
        playPauseButton = controlButton("Ⅱ", "Play / Pause") {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
            refreshPlayPause()
            showControls()
        }
        val nextButton = controlButton("⏭", "Next") {
            player?.seekToNextMediaItem()
            showControls()
        }
        if (prefs.getBoolean("button_previous", true)) buttonRow.addView(previousButton)
        buttonRow.addView(playPauseButton)
        if (prefs.getBoolean("button_next", true)) buttonRow.addView(nextButton)
        controls.addView(buttonRow, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(controls, FrameLayout.LayoutParams(-1, dp(132), Gravity.BOTTOM))
        setContentView(root)

        val parsedUri = android.net.Uri.parse(url)
        val path = parsedUri.path.orEmpty()
        val isHlsEndpoint = path.endsWith(".m3u8", true) ||
            (path.endsWith(".php", true) && parsedUri.queryParameterNames.isNotEmpty())
        val mediaItem = MediaItem.Builder().setUri(url)
            .apply { if (isHlsEndpoint) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("MyPlayer/1.0 (Android TV)")
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)

        player = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    val detail = error.cause?.message ?: error.message ?: "Unknown playback error"
                    Toast.makeText(this@PlayerActivity, "Playback failed: $detail", Toast.LENGTH_LONG).show()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) = refreshPlayPause()
                override fun onPlaybackStateChanged(playbackState: Int) = updateProgress()
            })
            exo.setMediaItem(mediaItem)
            exo.prepare()
            exo.playWhenReady = true
        }
        root.isFocusableInTouchMode = true
        root.requestFocus()
        root.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    refreshPlayPause(); showControls(); true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekByRemote(-prefs.getInt("seek_interval", 10) * 1000L); showControls(); true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekByRemote(prefs.getInt("seek_interval", 10) * 1000L); showControls(); true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (controls.visibility == View.VISIBLE) { controls.visibility = View.GONE; handler.removeCallbacks(hideControls); true }
                    else { finishAffinity(); true }
                }
                else -> false
            }
        }
        playerView.setOnClickListener { showControls() }
        handler.post(progressUpdater)
    }

    private fun showControls() {
        if (!::controls.isInitialized) return
        controls.visibility = View.VISIBLE
        refreshPlayPause()
        updateProgress()
        handler.removeCallbacks(hideControls)
        if (player?.isPlaying == true) handler.postDelayed(hideControls, controlsHideDelay)
    }

    private fun controlButton(symbol: String, description: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            contentDescription = description
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            isClickable = true
            setOnClickListener { action() }
            setBackgroundResource(android.R.drawable.btn_default)
            background?.alpha = 80
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(52)).apply {
                marginStart = dp(8); marginEnd = dp(8)
            }
        }

    private fun timeLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
    }

    private fun refreshPlayPause() {
        if (::playPauseButton.isInitialized) playPauseButton.text = if (player?.isPlaying == true) "Ⅱ" else "▶"
    }

    private fun updateProgress() {
        val p = player ?: return
        val duration = p.duration
        val position = p.currentPosition.coerceAtLeast(0L)
        if (!userSeeking) {
            if (duration > 0L) {
                progress.progress = ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
                positionLabel.text = formatTime(position)
                remainingLabel.text = "-" + formatTime((duration - position).coerceAtLeast(0L))
                progress.isEnabled = true
            } else {
                progress.progress = 0
                positionLabel.text = formatTime(position)
                remainingLabel.text = "LIVE"
                progress.isEnabled = false
            }
        }
        refreshPlayPause()
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun seekByRemote(offsetMs: Long) {
        val p = player ?: return
        if (p.isCurrentMediaItemLive && !p.isCurrentMediaItemSeekable) return
        val current = p.currentPosition.coerceAtLeast(0L)
        val target = (current + offsetMs).coerceAtLeast(0L)
        val duration = p.duration
        p.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() { finishAffinity() }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }
}
