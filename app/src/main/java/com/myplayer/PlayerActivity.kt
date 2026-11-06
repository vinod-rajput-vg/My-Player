package com.myplayer

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

class PlayerActivity : Activity() {
    companion object {
        const val EXTRA_URL = "com.myplayer.EXTRA_URL"
        const val EXTRA_HTTP_HEADERS = "com.myplayer.EXTRA_HTTP_HEADERS"
        private const val TAG = "MyPlayerVLC"
        private const val STALL_CHECK_INTERVAL_MS = 5_000L
        private const val STALL_TIMEOUT_MS = 20_000L
        private const val BUFFERING_TIMEOUT_MS = 45_000L
    }

    private var libVLC: LibVLC? = null
    private var player: MediaPlayer? = null
    private lateinit var playerView: VLCVideoLayout
    private lateinit var controls: LinearLayout
    private lateinit var aspectButton: TextView
    private lateinit var audioButton: TextView
    private lateinit var timeline: SeekBar
    private lateinit var positionLabel: TextView
    private lateinit var durationLabel: TextView
    private val prefs by lazy { getSharedPreferences("player_settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private var controlsHideDelay = 3000L
    private var retryAttempt = 0
    private val maxAutoRetries = 12
    private var playbackReleased = false
    private var isLiveOrStream = false
    private var recoveryPending = false
    private var buffering = false
    private var bufferingStartedRealtime = 0L
    private var lastObservedPosition = Long.MIN_VALUE
    private var lastProgressRealtime = 0L
    private var retryPositionMs: Long? = null
    private var pendingSeekMs: Long? = null
    private var resumeAfterStop = false
    private var currentUrl = ""
    private var currentHeaders = Bundle()

    private val retryPlayback = Runnable {
        recoveryPending = false
        if (playbackReleased || isFinishing || isDestroyed) return@Runnable
        val position = retryPositionMs
        retryPositionMs = null
        startPlayback(position)
    }

    private val playbackStallMonitor = object : Runnable {
        override fun run() {
            val activePlayer = player ?: return
            if (playbackReleased || isFinishing || isDestroyed) return

            val now = SystemClock.elapsedRealtime()
            if (!activePlayer.isPlaying) {
                lastObservedPosition = Long.MIN_VALUE
                lastProgressRealtime = 0L
                if (buffering && bufferingStartedRealtime > 0L &&
                    now - bufferingStartedRealtime >= BUFFERING_TIMEOUT_MS
                ) {
                    recoverStalledPlayback("buffering timeout")
                }
            } else {
                buffering = false
                bufferingStartedRealtime = 0L
                val position = activePlayer.time.coerceAtLeast(0L)
                if (lastObservedPosition == Long.MIN_VALUE || position > lastObservedPosition + 250L) {
                    lastObservedPosition = position
                    lastProgressRealtime = now
                } else if (lastProgressRealtime > 0L &&
                    now - lastProgressRealtime >= STALL_TIMEOUT_MS
                ) {
                    recoverStalledPlayback("playback position stopped advancing")
                }
            }

            handler.postDelayed(this, STALL_CHECK_INTERVAL_MS)
        }
    }

    private val resetRetryCounter = Runnable { retryAttempt = 0 }

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            if (!playbackReleased && ::controls.isInitialized && controls.visibility == View.VISIBLE) {
                handler.postDelayed(this, 500L)
            }
        }
    }

    private val hideControls = Runnable {
        if (::controls.isInitialized) controls.visibility = View.GONE
        handler.removeCallbacks(progressUpdater)
    }

    private val playerEventListener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                buffering = true
                if (bufferingStartedRealtime == 0L) {
                    bufferingStartedRealtime = SystemClock.elapsedRealtime()
                }
                android.util.Log.d(TAG, "Opening stream")
            }
            MediaPlayer.Event.Buffering -> {
                buffering = true
                if (bufferingStartedRealtime == 0L) {
                    bufferingStartedRealtime = SystemClock.elapsedRealtime()
                }
            }
            MediaPlayer.Event.Playing -> {
                buffering = false
                bufferingStartedRealtime = 0L
                lastObservedPosition = Long.MIN_VALUE
                lastProgressRealtime = SystemClock.elapsedRealtime()
                if (pendingSeekMs != null) {
                    val seek = pendingSeekMs ?: 0L
                    pendingSeekMs = null
                    if (seek > 0L && player?.isSeekable == true) {
                        player?.setTime(seek)
                    }
                }
                applySavedAudioPreference()
                handler.removeCallbacks(resetRetryCounter)
                handler.postDelayed(resetRetryCounter, 30_000L)
                updateProgress()
                android.util.Log.i(TAG, "Playback started")
            }
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                buffering = false
                bufferingStartedRealtime = 0L
            }
            MediaPlayer.Event.EndReached -> {
                android.util.Log.w(TAG, "Stream ended")
                if (isLiveOrStream && !playbackReleased) {
                    scheduleRetry("stream ended", true)
                }
            }
            MediaPlayer.Event.EncounteredError -> {
                android.util.Log.e(TAG, "LibVLC playback error")
                scheduleRetry("playback error", isLiveOrStream)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        currentUrl = url
        currentHeaders = intent.getBundleExtra(EXTRA_HTTP_HEADERS) ?: Bundle()
        val streamUri = android.net.Uri.parse(url)
        val streamPath = streamUri.path.orEmpty().lowercase(Locale.ROOT)
        val finalSegment = streamPath.substringAfterLast('/')
        isLiveOrStream = streamPath.endsWith(".m3u8") ||
            streamPath.endsWith(".m3u") ||
            streamPath.endsWith(".ts") ||
            streamPath.endsWith(".mpegts") ||
            streamPath.endsWith(".m2ts") ||
            streamPath.endsWith(".mpd") ||
            (streamPath.endsWith(".php") && streamUri.queryParameterNames.isNotEmpty()) ||
            !finalSegment.contains('.')
        controlsHideDelay = prefs.getInt("hide_timeout", 3).coerceIn(1, 5) * 1000L

        playerView = VLCVideoLayout(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }

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

        val utilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        audioButton = controlButton("♫", "Audio track") {
            showAudioTrackDialog()
        }
        aspectButton = controlButton("⛶", "Aspect ratio") {
            showAspectRatioMenu(aspectButton)
        }

        val timelineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        positionLabel = TextView(this).apply {
            text = "00:00"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        durationLabel = TextView(this).apply {
            text = "LIVE"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        timeline = SeekBar(this).apply {
            max = 1000
            isFocusable = true
            isFocusableInTouchMode = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val length = player?.length ?: 0L
                        positionLabel.text = formatTime(length * progress / 1000L)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    userSeeking = true
                    handler.removeCallbacks(progressUpdater)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val active = player
                    val length = active?.length ?: 0L
                    if (active != null && active.isSeekable && length > 0L) {
                        active.setTime(length * seekBar.progress / 1000L)
                    }
                    userSeeking = false
                    updateProgress()
                    if (controls.visibility == View.VISIBLE) {
                        handler.postDelayed(progressUpdater, 500L)
                    }
                }
            })
        }

        timelineRow.addView(positionLabel, LinearLayout.LayoutParams(dp(48), -2))
        timelineRow.addView(timeline, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        timelineRow.addView(durationLabel, LinearLayout.LayoutParams(dp(48), -2))
        utilityRow.addView(audioButton)
        utilityRow.addView(aspectButton)
        controls.addView(utilityRow, LinearLayout.LayoutParams(-1, dp(54)))
        controls.addView(timelineRow, LinearLayout.LayoutParams(-1, dp(38)))
        root.addView(controls, FrameLayout.LayoutParams(-1, dp(124), Gravity.BOTTOM))
        setContentView(root)

        val options = arrayListOf(
            "--no-video-title-show",
            "--http-reconnect",
            "--network-caching=1500",
            "--live-caching=1500",
            "--file-caching=1500"
        )
        try {
            val vlc = LibVLC(this, options)
            libVLC = vlc
            player = MediaPlayer(vlc).also { active ->
                active.setEventListener(playerEventListener)
                active.attachViews(playerView, null, false, false)
                applyAspectRatio()
            }
            startPlayback(null)
        } catch (error: Exception) {
            android.util.Log.e(TAG, "Unable to initialize LibVLC", error)
            Toast.makeText(this, "Unable to initialize video player", Toast.LENGTH_LONG).show()
            finish()
        }

        playerView.requestFocus()
    }

    private fun startPlayback(resumePositionMs: Long?) {
        val activePlayer = player ?: return
        if (playbackReleased || currentUrl.isBlank()) return

        handler.removeCallbacks(retryPlayback)
        buffering = true
        bufferingStartedRealtime = SystemClock.elapsedRealtime()
        lastObservedPosition = Long.MIN_VALUE
        lastProgressRealtime = SystemClock.elapsedRealtime()
        pendingSeekMs = resumePositionMs?.coerceAtLeast(0L)

        try {
            activePlayer.stop()
            val vlc = libVLC ?: return
            val media = Media(vlc, android.net.Uri.parse(currentUrl))
            media.addOption(":http-user-agent=MyPlayer/1.0 (Android TV)")
            media.addOption(":network-caching=1500")
            media.addOption(":live-caching=1500")
            media.addOption(":http-reconnect")

            val quality = prefs.getString("video_quality", "original") ?: "original"
            val preferredHeight = when (quality) {
                "1080" -> 1080
                "720" -> 720
                "480" -> 480
                "360" -> 360
                "240" -> 240
                else -> 0
            }
            if (preferredHeight > 0) media.addOption(":preferred-resolution=$preferredHeight")

            currentHeaders.keySet().forEach { name ->
                val value = currentHeaders.getString(name)?.takeIf {
                    it.isNotBlank() && '\r' !in it && '\n' !in it
                }
                if (name.isNotBlank() && value != null &&
                    '\r' !in name && '\n' !in name && ':' !in name
                ) {
                    media.addOption(":http-header=$name: $value")
                }
            }

            activePlayer.setMedia(media)
            media.release()
            activePlayer.play()
        } catch (error: Exception) {
            buffering = false
            android.util.Log.e(TAG, "Unable to open stream", error)
            scheduleRetry("stream initialization error", isLiveOrStream)
        }
    }

    private fun recoverStalledPlayback(reason: String) {
        if (recoveryPending || retryAttempt >= maxAutoRetries || playbackReleased) return
        val activePlayer = player ?: return
        val resumePosition = if (isLiveOrStream || !activePlayer.isSeekable) {
            null
        } else {
            activePlayer.time.coerceAtLeast(0L)
        }
        scheduleRetry(reason, isLiveOrStream || !activePlayer.isSeekable, resumePosition)
    }

    private fun scheduleRetry(
        reason: String,
        reconnectAtLiveEdge: Boolean,
        resumePositionMs: Long? = null
    ) {
        if (recoveryPending || retryAttempt >= maxAutoRetries || playbackReleased) return
        retryAttempt++
        recoveryPending = true
        handler.removeCallbacks(retryPlayback)
        retryPositionMs = if (reconnectAtLiveEdge) null
            else resumePositionMs ?: player?.time?.coerceAtLeast(0L)
        buffering = true
        bufferingStartedRealtime = SystemClock.elapsedRealtime()
        android.util.Log.w(
            TAG,
            "Scheduling playback recovery ($reason), attempt $retryAttempt/$maxAutoRetries"
        )
        handler.postDelayed(retryPlayback, retryDelayMs(retryAttempt))
    }

    private fun retryDelayMs(attempt: Int): Long =
        if (attempt <= 1) 0L
        else (1000L shl (attempt - 2).coerceAtMost(4)).coerceAtMost(15_000L)

    private fun togglePlayback() {
        player?.let { active ->
            if (active.isPlaying) active.pause() else active.play()
        }
    }

    private fun hasControlFocus(): Boolean =
        currentFocus === timeline || currentFocus === audioButton || currentFocus === aspectButton

    private fun showControls() {
        if (!::controls.isInitialized) return
        val wasHidden = controls.visibility != View.VISIBLE
        controls.visibility = View.VISIBLE
        if (wasHidden && ::timeline.isInitialized && !hasControlFocus()) timeline.requestFocus()
        handler.removeCallbacks(hideControls)
        handler.removeCallbacks(progressUpdater)
        updateProgress()
        handler.postDelayed(progressUpdater, 250L)
        handler.postDelayed(hideControls, controlsHideDelay)
    }

    private fun controlButton(symbol: String, description: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            contentDescription = description
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            isClickable = true
            setOnClickListener { action() }
            fun buttonBackground(focused: Boolean) = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(if (focused) Color.rgb(105, 200, 255) else 0xCC242832.toInt())
                setStroke(dp(2), if (focused) Color.rgb(170, 230, 255) else 0x55FFFFFF)
            }
            background = buttonBackground(false)
            setOnFocusChangeListener { _, hasFocus ->
                background = buttonBackground(hasFocus)
                setTextColor(if (hasFocus) Color.rgb(8, 25, 40) else Color.WHITE)
            }
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(dp(82), dp(48)).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        }

    private data class AudioTrackOption(
        val id: Int,
        val label: String,
        val selected: Boolean
    )

    private fun showAudioTrackDialog() {
        val activePlayer = player ?: return
        val tracks = activePlayer.audioTracks ?: emptyArray()
        val options = tracks.mapNotNull { track ->
            if (track.id == -1) return@mapNotNull null
            val name = track.name.orEmpty()
            val lower = name.lowercase(Locale.ROOT)
            val label = when {
                "hindi" in lower || "hin" in lower -> "Hindi"
                "marathi" in lower || "mar" in lower -> "Marathi"
                "gujarati" in lower || "guj" in lower -> "Gujarati"
                "english" in lower || "eng" in lower -> "English"
                else -> null
            } ?: return@mapNotNull null
            AudioTrackOption(track.id, label, activePlayer.audioTrack == track.id)
        }.distinctBy { it.label }

        if (options.isEmpty()) {
            Toast.makeText(
                this,
                "Audio Tracks Are Not Available For This Playback",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            addView(list, ViewGroup.LayoutParams(-1, -2))
        }
        var dialog: AlertDialog? = null
        options.forEach { option ->
            val row = TextView(this).apply {
                text = (if (option.selected) "●   " else "     ") + option.label
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(54)
                setPadding(dp(18), dp(8), dp(18), dp(8))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                fun styleRow(focused: Boolean) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(6).toFloat()
                        setColor(
                            if (focused) Color.rgb(42, 112, 174)
                            else if (option.selected) Color.rgb(35, 57, 78)
                            else Color.rgb(28, 30, 34)
                        )
                        setStroke(
                            dp(if (focused) 2 else 1),
                            if (focused) Color.rgb(143, 190, 255)
                            else Color.rgb(70, 75, 82)
                        )
                    }
                }
                styleRow(false)
                setOnFocusChangeListener { _, focused -> styleRow(focused) }
                setOnClickListener {
                    activePlayer.setAudioTrack(option.id)
                    prefs.edit().putString("audio_track_language", option.label).apply()
                    dialog?.dismiss()
                    showControls()
                }
            }
            list.addView(
                row,
                LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(4) }
            )
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Audio Language")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()
        dialog?.setOnShowListener {
            val selectedIndex = options.indexOfFirst { it.selected }
            list.getChildAt(if (selectedIndex >= 0) selectedIndex else 0)?.requestFocus()
        }
        dialog?.show()
        dialog?.window?.setBackgroundDrawableResource(android.R.color.black)
    }

    private fun applyAspectRatio() {
        when (prefs.getString("aspect_ratio", "fit")) {
            "fill" -> {
                player?.setAspectRatio("16:9")
                player?.setScale(0f)
            }
            "zoom" -> {
                player?.setAspectRatio(null)
                player?.setScale(1.2f)
            }
            else -> {
                player?.setAspectRatio(null)
                player?.setScale(0f)
            }
        }
    }

    private fun applySavedAudioPreference() {
        val preferred = prefs.getString("audio_track_language", "").orEmpty()
        if (preferred.isBlank()) return
        val active = player ?: return
        val tracks = active.audioTracks ?: return
        val match = tracks.firstOrNull { track ->
            val name = track.name.orEmpty().lowercase(Locale.ROOT)
            when (preferred) {
                "Hindi" -> "hindi" in name || "hin" in name
                "Marathi" -> "marathi" in name || "mar" in name
                "Gujarati" -> "gujarati" in name || "guj" in name
                "English" -> "english" in name || "eng" in name
                else -> false
            }
        }
        if (match != null && active.audioTrack != match.id) {
            active.setAudioTrack(match.id)
        }
    }

    private fun showAspectRatioMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add("Fit").setOnMenuItemClickListener {
            player?.setAspectRatio(null)
            player?.setScale(0f)
            prefs.edit().putString("aspect_ratio", "fit").apply()
            applyAspectRatio()
            showControls()
            true
        }
        menu.menu.add("Fill").setOnMenuItemClickListener {
            player?.setAspectRatio("16:9")
            player?.setScale(0f)
            prefs.edit().putString("aspect_ratio", "fill").apply()
            applyAspectRatio()
            showControls()
            true
        }
        menu.menu.add("Zoom").setOnMenuItemClickListener {
            player?.setAspectRatio(null)
            player?.setScale(1.2f)
            prefs.edit().putString("aspect_ratio", "zoom").apply()
            applyAspectRatio()
            showControls()
            true
        }
        menu.show()
        showControls()
    }

    private fun seekByRemote(offsetMs: Long) {
        val active = player ?: return
        if (!active.isSeekable) return
        val current = active.time.coerceAtLeast(0L)
        val target = (current + offsetMs).coerceAtLeast(0L)
        val length = active.length
        active.setTime(if (length > 0L) target.coerceAtMost(length) else target)
    }

    private fun updateProgress() {
        val active = player ?: return
        if (!::timeline.isInitialized || userSeeking) return
        val length = active.length
        val position = active.time.coerceAtLeast(0L)
        if (length > 0L && active.isSeekable) {
            timeline.progress = (position * 1000L / length).toInt().coerceIn(0, 1000)
            durationLabel.text = formatTime(length)
            timeline.isEnabled = true
        } else {
            timeline.progress = 0
            durationLabel.text = "LIVE"
            timeline.isEnabled = false
        }
        positionLabel.text = formatTime(position)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controlsVisible = ::controls.isInitialized && controls.visibility == View.VISIBLE
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (controlsVisible && hasControlFocus()) {
                        showControls()
                        return super.dispatchKeyEvent(event)
                    }
                    togglePlayback()
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    player?.play()
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    player?.pause()
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    val backward = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                        event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                    val offset = prefs.getInt("seek_interval", 10) * 1000L
                    seekByRemote(if (backward) -offset else offset)
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
                    if (!controlsVisible) {
                        showControls()
                        timeline.requestFocus()
                        return true
                    }
                    when {
                        currentFocus === timeline && up -> audioButton.requestFocus()
                        currentFocus === audioButton && !up -> timeline.requestFocus()
                        currentFocus === aspectButton && !up -> audioButton.requestFocus()
                        currentFocus === audioButton && up -> aspectButton.requestFocus()
                        else -> {
                            showControls()
                            return true
                        }
                    }
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (controlsVisible) {
                        controls.visibility = View.GONE
                        handler.removeCallbacks(hideControls)
                        handler.removeCallbacks(progressUpdater)
                        playerView.requestFocus()
                    } else {
                        finishAffinity()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        finishAffinity()
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(playbackStallMonitor)
        if (!playbackReleased) handler.postDelayed(playbackStallMonitor, STALL_CHECK_INTERVAL_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(hideControls)
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(retryPlayback)
        handler.removeCallbacks(playbackStallMonitor)
        handler.removeCallbacks(resetRetryCounter)
        recoveryPending = false
        resumeAfterStop = player?.isPlaying == true
        player?.pause()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (resumeAfterStop && !playbackReleased) {
            resumeAfterStop = false
            player?.play()
        }
    }

    override fun onDestroy() {
        playbackReleased = true
        handler.removeCallbacksAndMessages(null)
        player?.let { active ->
            active.setEventListener(null)
            active.stop()
            active.detachViews()
            active.release()
        }
        player = null
        libVLC?.release()
        libVLC = null
        super.onDestroy()
    }
}
