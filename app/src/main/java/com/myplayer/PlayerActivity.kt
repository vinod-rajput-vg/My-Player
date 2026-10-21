package com.myplayer

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale

class PlayerActivity : Activity() {
    companion object { const val EXTRA_URL = "com.myplayer.EXTRA_URL" }

    private var player: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var playerView: PlayerView
    private lateinit var controls: LinearLayout
    private lateinit var playPauseButton: TextView
    private lateinit var positionLabel: TextView
    private lateinit var remainingLabel: TextView
    private lateinit var progress: SeekBar
    private lateinit var aspectButton: TextView
    private lateinit var audioButton: TextView
    private lateinit var previousButton: TextView
    private lateinit var nextButton: TextView
    private val prefs by lazy { getSharedPreferences("player_settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private var controlsHideDelay = 3000L
    private var retryAttempt = 0
    private val retryPlayback = Runnable {
        val activePlayer = player ?: return@Runnable
        activePlayer.playWhenReady = true
        activePlayer.prepare()
    }
    private val resetRetryCounter = Runnable { retryAttempt = 0 }

    private val hideControls = Runnable {
        if (::controls.isInitialized) controls.visibility = View.GONE
        handler.removeCallbacks(progressUpdater)
    }
    private val progressUpdater = object : Runnable {
        override fun run() {
            if (::controls.isInitialized && controls.visibility == View.VISIBLE && player != null) {
                updateProgress()
                handler.postDelayed(this, 750)
            }
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
            resizeMode = when (prefs.getString("aspect_ratio", "fit")) {
                "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
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
        utilityRow.addView(audioButton)
        utilityRow.addView(aspectButton)
        controls.addView(utilityRow, LinearLayout.LayoutParams(-1, dp(54)))

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        positionLabel = timeLabel("00:00")
        remainingLabel = timeLabel("-00:00")
        progress = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Color.rgb(83, 190, 255))
            progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
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
        previousButton = controlButton("−10s", "Rewind") {
            seekByRemote(-prefs.getInt("seek_interval", 10) * 1000L)
            showControls()
        }
        playPauseButton = controlButton("Ⅱ", "Play / Pause") {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
            refreshPlayPause()
            showControls()
        }
        nextButton = controlButton("+10s", "Forward") {
            seekByRemote(prefs.getInt("seek_interval", 10) * 1000L)
            showControls()
        }
        if (prefs.getBoolean("button_previous", true)) buttonRow.addView(previousButton)
        buttonRow.addView(playPauseButton)
        if (prefs.getBoolean("button_next", true)) buttonRow.addView(nextButton)
        controls.addView(buttonRow, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(controls, FrameLayout.LayoutParams(-1, dp(202), Gravity.BOTTOM))
        setContentView(root)

        val parsedUri = android.net.Uri.parse(url)
        val path = parsedUri.path.orEmpty()
        val isHlsEndpoint = path.endsWith(".m3u8", true) ||
            (path.endsWith(".php", true) && parsedUri.queryParameterNames.isNotEmpty())
        val mediaItem = MediaItem.Builder().setUri(url)
            .apply { if (isHlsEndpoint) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent("MyPlayer/1.0 (Android TV)")
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        trackSelector = DefaultTrackSelector(this)
        val quality = prefs.getString("video_quality", "original") ?: "original"
        val maxVideoSize = when (quality) {
            "1080" -> 1920 to 1080
            "720" -> 1280 to 720
            "480" -> 854 to 480
            "360" -> 640 to 360
            "240" -> 426 to 240
            else -> null
        }
        val trackParameters = trackSelector.buildUponParameters()
        if (maxVideoSize == null) {
            trackParameters.clearVideoSizeConstraints()
        } else {
            trackParameters.setMaxVideoSize(maxVideoSize.first, maxVideoSize.second)
        }
        trackSelector.parameters = trackParameters.build()

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    handler.removeCallbacks(retryPlayback)
                    val retryable = isRetryablePlaybackError(error)
                    if (retryable) {
                        retryAttempt++
                        val delayMs = 0L // Re-open the same stream URL immediately; no backoff for .ts disconnects
                        Toast.makeText(
                            this@PlayerActivity,
                            "Connection interrupted. Reconnecting…",
                            Toast.LENGTH_SHORT
                        ).show()
                        handler.postDelayed(retryPlayback, delayMs)
                    } else {
                        val detail = error.cause?.message ?: error.message ?: "Unknown playback error"
                        Toast.makeText(
                            this@PlayerActivity,
                            if (retryable) "Auto-retry limit reached. Retry playback manually."
                            else "Playback failed: $detail",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    refreshPlayPause()
                    handler.removeCallbacks(resetRetryCounter)
                    if (isPlaying) handler.postDelayed(resetRetryCounter, 30_000L)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateProgress()
                }
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                }
            })
            exo.setMediaItem(mediaItem)
            exo.prepare()
            exo.playWhenReady = true
        }
        root.isFocusableInTouchMode = true
        root.requestFocus()
        playerView.setOnClickListener { showControls() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controlsVisible = ::controls.isInitialized && controls.visibility == View.VISIBLE
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (controlsVisible && currentFocus != null &&
                        (currentFocus === progress || currentFocus?.contentDescription == "Rewind" ||
                         currentFocus?.contentDescription == "Forward" ||
                         currentFocus?.contentDescription == "Play / Pause" ||
                         currentFocus?.contentDescription == "Pause" || currentFocus?.contentDescription == "Play")) {
                        showControls()
                        return super.dispatchKeyEvent(event)
                    }
                    if (!controlsVisible) {
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                        refreshPlayPause()
                        showControls()
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    refreshPlayPause()
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (controlsVisible) {
                        val left = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        val focus = currentFocus
                        val transportButtons = listOfNotNull(
                            previousButton.takeIf { it.parent != null },
                            playPauseButton,
                            nextButton.takeIf { it.parent != null }
                        )
                        when {
                            focus === progress -> {
                                val step = prefs.getInt("seek_interval", 10) * 1000L
                                seekByRemote(if (left) -step else step)
                            }
                            focus === audioButton || focus === aspectButton -> {
                                val utilityButtons = listOf(audioButton, aspectButton)
                                val index = utilityButtons.indexOfFirst { it === focus }
                                val target = index + if (left) -1 else 1
                                if (target in utilityButtons.indices) utilityButtons[target].requestFocus()
                            }
                            transportButtons.any { it === focus } -> {
                                val index = transportButtons.indexOfFirst { it === focus }
                                val target = index + if (left) -1 else 1
                                if (target in transportButtons.indices) transportButtons[target].requestFocus()
                            }
                            else -> {
                                // From an unfocused video surface, reveal controls and focus Play/Pause.
                                playPauseButton.requestFocus()
                            }
                        }
                        showControls()
                        return true
                    }
                    seekByRemote(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT)
                        -prefs.getInt("seek_interval", 10) * 1000L
                    else prefs.getInt("seek_interval", 10) * 1000L)
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekByRemote(if (event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND)
                        -prefs.getInt("seek_interval", 10) * 1000L
                    else prefs.getInt("seek_interval", 10) * 1000L)
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!controlsVisible) {
                        showControls()
                        playPauseButton.requestFocus()
                        return true
                    }
                    val focus = currentFocus
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                        (focus === audioButton || focus === aspectButton)) {
                        progress.requestFocus()
                        showControls()
                        return true
                    }
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                        (focus === progress || focus === positionLabel || focus === remainingLabel)) {
                        (if (focus === audioButton) audioButton else aspectButton).requestFocus()
                        showControls()
                        return true
                    }
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                        (focus === progress || focus === positionLabel || focus === remainingLabel)) {
                        playPauseButton.requestFocus()
                        showControls()
                        return true
                    }
                    if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                        (focus === playPauseButton || focus === previousButton || focus === nextButton)) {
                        progress.requestFocus()
                        showControls()
                        return true
                    }
                    showControls()
                    return super.dispatchKeyEvent(event)
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (controlsVisible) {
                        controls.visibility = View.GONE
                        handler.removeCallbacks(hideControls)
                        playerView.requestFocus()
                    } else finishAffinity()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isRetryablePlaybackError(error: PlaybackException): Boolean {
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
            return true
        }

        // Retry temporary stream read/source interruptions and HTTP 5xx, but not expired/denied URLs (401/403).
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode in 500..599
            }
            if (cause is java.io.IOException) return true
            cause = cause.cause
        }
        return false
    }

    private fun showControls() {
        if (!::controls.isInitialized) return
        val wasHidden = controls.visibility != View.VISIBLE
        controls.visibility = View.VISIBLE
        refreshPlayPause()
        updateProgress()
        if (wasHidden) {
            // Start on a real playback control so D-pad users can operate the player immediately.
            playPauseButton.requestFocus()
        }
        handler.removeCallbacks(hideControls)
        handler.removeCallbacks(progressUpdater)
        handler.post(progressUpdater)
        if (player?.isPlaying == true) handler.postDelayed(hideControls, controlsHideDelay)
    }

    private fun controlButton(symbol: String, description: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = symbol
            contentDescription = description
            textSize = if (description == "Play / Pause") 30f else 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true
            isClickable = true
            setOnClickListener { action() }
            fun buttonBackground(focused: Boolean) = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(if (description == "Play / Pause") 30 else 22).toFloat()
                setColor(if (focused) Color.rgb(105, 200, 255) else 0xCC242832.toInt())
                setStroke(dp(2), if (focused) Color.rgb(170, 230, 255) else 0x55FFFFFF)
            }
            background = buttonBackground(false)
            setOnFocusChangeListener { _, hasFocus ->
                background = buttonBackground(hasFocus)
                setTextColor(if (hasFocus) Color.rgb(8, 25, 40) else Color.WHITE)
            }
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(
                if (description == "Play / Pause") dp(66) else dp(82),
                dp(if (description == "Play / Pause") 58 else 48)
            ).apply {
                marginStart = dp(8); marginEnd = dp(8)
            }
        }


    private data class AudioTrackOption(val label: String, val group: androidx.media3.common.TrackGroup?, val trackIndex: Int, val selected: Boolean, val disabled: Boolean = false)

    private fun showAudioTrackDialog() {
        val activePlayer = player ?: return
        val options = mutableListOf(AudioTrackOption("Disable", null, -1, false, true))
        var number = 0
        activePlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                number++
                val language = group.getTrackFormat(index).language?.let { languageDisplayName(it) }
                val label = if (language.isNullOrBlank()) "Track $number" else "Track $number - [$language]"
                options.add(AudioTrackOption(label, group.mediaTrackGroup, index, group.isTrackSelected(index)))
            }
        }
        if (options.size == 1) {
            Toast.makeText(this, "No audio tracks available", Toast.LENGTH_SHORT).show()
            return
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; isFocusable = false; addView(list, ViewGroup.LayoutParams(-1, -2)) }
        var dialog: AlertDialog? = null
        options.forEach { option ->
            val row = TextView(this).apply {
                text = (if (option.selected) "●   " else "     ") + option.label
                textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(54); setPadding(dp(18), dp(8), dp(18), dp(8))
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                fun styleRow(focused: Boolean) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(6).toFloat()
                        setColor(if (focused) Color.rgb(42,112,174) else if (option.selected) Color.rgb(35,57,78) else Color.rgb(28,30,34))
                        setStroke(dp(if (focused) 2 else 1), if (focused) Color.rgb(143,190,255) else Color.rgb(70,75,82))
                    }
                }
                styleRow(false); setOnFocusChangeListener { _, focused -> styleRow(focused) }
                setOnClickListener {
                    val builder = trackSelector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    if (option.disabled) builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    else option.group?.let { group ->
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        builder.setOverrideForType(androidx.media3.common.TrackSelectionOverride(group, option.trackIndex))
                    }
                    trackSelector.parameters = builder.build()
                    dialog?.dismiss(); showControls()
                }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(4) })
        }
        dialog = AlertDialog.Builder(this).setTitle("Audio Track").setView(scroll).setNegativeButton("Close", null).create()
        dialog?.setOnShowListener { val index = options.indexOfFirst { it.selected }; list.getChildAt(if (index >= 0) index else 1)?.requestFocus() }
        dialog?.show(); dialog?.window?.setBackgroundDrawableResource(android.R.color.black)
    }

    private fun normalizedLanguageTag(rawLanguage: String): String? {
        val raw = rawLanguage.trim().replace('_', '-')
        if (raw.isBlank() || raw.equals("und", ignoreCase = true)) return null
        val primary = raw.substringBefore('-').lowercase(Locale.ROOT)
        val aliases = mapOf("eng" to "en", "hin" to "hi", "tam" to "ta", "tel" to "te", "mal" to "ml", "kan" to "kn", "mar" to "mr", "ben" to "bn", "urd" to "ur", "guj" to "gu", "pan" to "pa", "ara" to "ar", "spa" to "es", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de", "ita" to "it", "por" to "pt", "rus" to "ru", "jpn" to "ja", "kor" to "ko", "chi" to "zh", "zho" to "zh")
        val normalized = aliases[primary] ?: primary
        val suffix = raw.substringAfter('-', "").takeIf { it.isNotBlank() }
        return if (suffix == null) normalized else "$normalized-$suffix"
    }

    private fun languageDisplayName(rawLanguage: String): String? {
        val tag = normalizedLanguageTag(rawLanguage) ?: return null
        val locale = Locale.forLanguageTag(tag); val code = locale.language
        if (code.isBlank() || code.equals("und", ignoreCase = true)) return null
        val name = locale.getDisplayLanguage(Locale.ENGLISH).trim()
        if (name.isBlank() || name.equals(code, ignoreCase = true)) return null
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
    }

    private fun showAspectRatioMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add("Fit").setOnMenuItemClickListener {
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            prefs.edit().putString("aspect_ratio", "fit").apply()
            anchor.contentDescription = "Aspect ratio: Fit"
            (anchor as? TextView)?.text = "⛶"
            showControls()
            true
        }
        menu.menu.add("Fill").setOnMenuItemClickListener {
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            prefs.edit().putString("aspect_ratio", "fill").apply()
            anchor.contentDescription = "Aspect ratio: Fill"
            (anchor as? TextView)?.text = "⛶"
            showControls()
            true
        }
        menu.menu.add("Zoom").setOnMenuItemClickListener {
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            prefs.edit().putString("aspect_ratio", "zoom").apply()
            anchor.contentDescription = "Aspect ratio: Zoom"
            (anchor as? TextView)?.text = "⛶"
            showControls()
            true
        }
        menu.show()
        showControls()
    }

    private fun timeLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
    }

    private fun refreshPlayPause() {
        if (::playPauseButton.isInitialized) {
            val playing = player?.isPlaying == true
            playPauseButton.text = if (playing) "Ⅱ" else "▶"
            playPauseButton.contentDescription = if (playing) "Pause" else "Play"
        }
    }

    private fun updateProgress() {
        val p = player ?: return
        val duration = p.duration
        val position = p.currentPosition.coerceAtLeast(0L)
        if (!userSeeking) {
            if (duration > 0L) {
                val targetProgress = ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
                if (progress.progress != targetProgress) progress.progress = targetProgress
                val positionText = formatTime(position)
                if (positionLabel.text != positionText) positionLabel.text = positionText
                val remainingText = "-" + formatTime((duration - position).coerceAtLeast(0L))
                if (remainingLabel.text != remainingText) remainingLabel.text = remainingText
                progress.isEnabled = true
            } else {
                if (progress.progress != 0) progress.progress = 0
                val positionText = formatTime(position)
                if (positionLabel.text != positionText) positionLabel.text = positionText
                if (remainingLabel.text != "LIVE") remainingLabel.text = "LIVE"
                progress.isEnabled = false
            }
        }
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
