package com.myplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale

class PlayerActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var controls: LinearLayout
    private lateinit var timeline: SeekBar
    private lateinit var currentTime: TextView
    private lateinit var totalTime: TextView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var seeking = false
    private val progressTask = object : Runnable {
        override fun run() {
            if (::player.isInitialized && !seeking) updateTimeline()
            handler.postDelayed(this, 500)
        }
    }
    private val hideTask = Runnable { hideControls() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        val uri = incomingUri()
        if (uri == null || !isAllowedUri(uri)) {
            Toast.makeText(this, "Unsupported or missing media link", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        createPlayerUi()
        resolveRedirectAndPlay(uri)
    }

    private fun incomingUri(): Uri? {
        return intent.data ?: if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun isAllowedUri(uri: Uri): Boolean = when (uri.scheme?.lowercase(Locale.ROOT)) {
        "http", "https" -> !uri.host.isNullOrBlank() && uri.userInfo == null
        "content", "file" -> true
        else -> false
    }

    private fun createPlayerUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -1))

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(8), dp(28), dp(12))
            setBackgroundColor(Color.TRANSPARENT)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        currentTime = timeText("00:00")
        totalTime = timeText("00:00")
        timeline = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF55D5F2.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFF55D5F2.toInt())
            isFocusable = true; isFocusableInTouchMode = true
            setOnFocusChangeListener { _, focused -> if (focused) showControls() else scheduleHide() }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) { seeking = true }
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && ::player.isInitialized && player.duration > 0)
                        currentTime.text = formatTime(player.duration * progress / 1000)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (::player.isInitialized && player.duration > 0)
                        player.seekTo(player.duration * (seekBar?.progress ?: 0) / 1000)
                    seeking = false; updateTimeline(); scheduleHide()
                }
            })
        }
        row.addView(currentTime)
        row.addView(timeline, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            leftMargin = dp(16); rightMargin = dp(16)
        })
        row.addView(totalTime)
        controls.addView(row)
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.setOnClickListener { showControls() }
        setContentView(root)
        timeline.requestFocus()
        handler.post(progressTask)
    }

    private fun resolveRedirectAndPlay(uri: Uri) {
        // Let Media3 follow HTTP 3xx redirects itself. This preserves redirect
        // headers/cookies and lets the HLS parser read the final playlist.
        initializePlayer(uri, "")
    }

    private fun initializePlayer(uri: Uri, contentType: String) {
        val trackBuilder = TrackSelectionParameters.Builder(this)
        when (PlayerSettings.quality(this)) {
            "1080p" -> trackBuilder.setMaxVideoSize(1920, 1080)
            "720p" -> trackBuilder.setMaxVideoSize(1280, 720)
        }
        val language = PlayerSettings.audioLanguage(this)
        if (language != "und") trackBuilder.setPreferredAudioLanguage(language)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("My Player/1.0.0")
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                .setDataSourceFactory(httpFactory))
            .build().apply {
            trackSelectionParameters = trackBuilder.build()
            val path = uri.lastPathSegment.orEmpty().lowercase(Locale.ROOT)
            val isHls = path.endsWith(".m3u8") ||
                path.endsWith(".php") ||
                contentType.contains("mpegurl", ignoreCase = true) ||
                contentType.contains("vnd.apple", ignoreCase = true)
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .apply { if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .build()
            setMediaItem(mediaItem)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    status.visibility = if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (playbackState == Player.STATE_READY) { updateTimeline(); scheduleHide() }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) status.visibility = View.GONE
                }
                override fun onPlayerError(error: PlaybackException) {
                    status.text = "Unable to play this stream\n" + error.errorCodeName + "\nPress Back to exit"
                    status.visibility = View.VISIBLE
                    showControls()
                }
            })
            prepare()
            playWhenReady = true
        }
        playerView.player = player
        playerView.resizeMode = when (PlayerSettings.aspectRatio(this)) {
            "Fit" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            "Zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (controlsVisible) togglePlayback() else showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    showControls(); seekBy(-PlayerSettings.seekSeconds(this) * 1000L); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    showControls(); seekBy(PlayerSettings.seekSeconds(this) * 1000L); return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> showControls()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun togglePlayback() {
        if (!::player.isInitialized) return
        if (player.isPlaying) player.pause() else player.play()
        showControls()
    }

    private fun seekBy(delta: Long) {
        if (!::player.isInitialized || (player.isCurrentMediaItemLive && !player.isCurrentMediaItemSeekable)) return
        val duration = player.duration
        if (duration <= 0) return
        player.seekTo((player.currentPosition + delta).coerceIn(0L, duration))
        updateTimeline()
    }

    private fun updateTimeline() {
        if (!::player.isInitialized) return
        val duration = player.duration
        val position = player.currentPosition
        currentTime.text = formatTime(position)
        totalTime.text = if (duration > 0) formatTime(duration) else "LIVE"
        timeline.isEnabled = duration > 0 && player.isCurrentMediaItemSeekable
        if (duration > 0) timeline.progress = (position * 1000 / duration).toInt().coerceIn(0, 1000)
    }

    private fun showControls() {
        controlsVisible = true
        controls.visibility = View.VISIBLE
        status.visibility = if (::player.isInitialized && player.playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
        scheduleHide()
    }

    private fun hideControls() {
        controlsVisible = false
        controls.visibility = View.GONE
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideTask)
        handler.postDelayed(hideTask, PlayerSettings.hideDelay(this) * 1000L)
    }

    private fun timeText(value: String) = TextView(this).apply {
        text = value; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; minWidth = dp(48)
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = (milliseconds / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
            .let { if (seconds >= 3600) it else it.substring(3) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTask)
        handler.removeCallbacks(hideTask)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
