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
    private lateinit var audioButton: TextView
    private lateinit var aspectButton: TextView
    private lateinit var previousButton: TextView
    private lateinit var nextButton: TextView
    private val prefs by lazy { getSharedPreferences("player_settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private var controlsHideDelay = 3000L
    private var retryAttempt = 0
    private val maxAutoRetries = 3
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
            val activePlayer = player
            if (activePlayer == null) {
                Toast.makeText(this, "Player is not ready", Toast.LENGTH_SHORT).show()
            } else {
                showAudioTrackDialog()
            }
            showControls()
        }
        aspectButton = controlButton("⛶", "Aspect ratio") {
            showAspectRatioMenu(aspectButton)
        }
        utilityRow.addView(audioButton)
        utilityRow.addView(aspectButton)
        audioButton.visibility = View.GONE
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
            .setUserAgent("MyPlayer/1.0 (Android TV)")
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)

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
        prefs.getString("default_audio_language", "auto")
            ?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
            ?.let { normalizedLanguageTag(it) ?: it }
            ?.let { trackParameters.setPreferredAudioLanguage(it) }
        if (maxVideoSize == null) {
            trackParameters.clearVideoSizeConstraints()
        } else {
            trackParameters.setMaxVideoSize(maxVideoSize.first, maxVideoSize.second)
        }
        trackSelector.parameters = trackParameters.build()

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    handler.removeCallbacks(retryPlayback)
                    val retryable = isRetryablePlaybackError(error)
                    if (retryable && retryAttempt < maxAutoRetries) {
                        retryAttempt++
                        val delayMs = 1000L shl (retryAttempt - 1)
                        Toast.makeText(
                            this@PlayerActivity,
                            "Connection interrupted. Retrying ${retryAttempt}/$maxAutoRetries…",
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
                    updateAudioButtonVisibility()
                }
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    updateAudioButtonVisibility()
                    saveSelectedAudioLanguage(tracks)
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
                                val utilityButtons = listOfNotNull(
                                    audioButton.takeIf { it.visibility == View.VISIBLE },
                                    aspectButton.takeIf { it.visibility == View.VISIBLE }
                                )
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
                        val target = if (audioButton.visibility == View.VISIBLE) audioButton else aspectButton
                        target.requestFocus()
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
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return true
        }

        // Retry temporary server failures (HTTP 5xx), but not expired/denied URLs (401/403).
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode in 500..599
            }
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
        updateAudioButtonVisibility()
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


    private data class AudioLanguageOption(
        val label: String,
        val languageTag: String,
        val mediaTrackGroup: androidx.media3.common.TrackGroup,
        val trackIndex: Int,
        val selected: Boolean
    )

    private fun showAudioTrackDialog() {
        val activePlayer = player ?: return
        val options = activePlayer.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group ->
                (0 until group.length).mapNotNull { index ->
                    if (!group.isTrackSupported(index)) return@mapNotNull null
                    val rawLanguage = group.getTrackFormat(index).language
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
                        ?: return@mapNotNull null
                    val displayName = languageDisplayName(rawLanguage) ?: return@mapNotNull null
                    AudioLanguageOption(
                        label = displayName,
                        languageTag = rawLanguage,
                        mediaTrackGroup = group.mediaTrackGroup,
                        trackIndex = index,
                        selected = group.isTrackSelected(index)
                    )
                }
            }
            .groupBy { it.label.lowercase(Locale.ROOT) }
            .values
            .map { sameLanguage -> sameLanguage.firstOrNull { it.selected } ?: sameLanguage.first() }
            .sortedBy { it.label }

        if (options.isEmpty()) {
            Toast.makeText(this, "No language-tagged audio tracks available", Toast.LENGTH_SHORT).show()
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
                text = if (option.selected) "✓   ${option.label}" else "     ${option.label}"
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(58)
                setPadding(dp(18), dp(10), dp(18), dp(10))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true

                fun styleRow(focused: Boolean) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(10).toFloat()
                        setColor(
                            if (focused) Color.rgb(42, 69, 105)
                            else if (option.selected) Color.rgb(29, 43, 62)
                            else Color.rgb(25, 30, 40)
                        )
                        setStroke(
                            dp(if (focused) 2 else 1),
                            if (focused) Color.rgb(143, 190, 255)
                            else if (option.selected) Color.rgb(87, 132, 190)
                            else Color.rgb(52, 61, 76)
                        )
                    }
                }
                styleRow(false)
                setOnFocusChangeListener { _, focused -> styleRow(focused) }
                setOnClickListener {
                    trackSelector.parameters = trackSelector.buildUponParameters()
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .setOverrideForType(
                            androidx.media3.common.TrackSelectionOverride(
                                option.mediaTrackGroup,
                                option.trackIndex
                            )
                        )
                        .build()
                    prefs.edit().putString("audio_language", normalizedLanguageTag(option.languageTag) ?: option.languageTag).apply()
                    dialog?.dismiss()
                    showControls()
                }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, dp(62)).apply {
                bottomMargin = dp(6)
            })
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Audio language")
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

    private fun normalizedLanguageTag(rawLanguage: String): String? {
        val raw = rawLanguage.trim().replace('_', '-')
        if (raw.isBlank() || raw.equals("und", ignoreCase = true)) return null
        val primary = raw.substringBefore('-').lowercase(Locale.ROOT)
        val iso639Aliases = mapOf(
            "eng" to "en", "hin" to "hi", "tam" to "ta", "tel" to "te",
            "mal" to "ml", "kan" to "kn", "mar" to "mr", "ben" to "bn",
            "guj" to "gu", "pan" to "pa", "urd" to "ur", "ara" to "ar",
            "spa" to "es", "fre" to "fr", "fra" to "fr", "ger" to "de",
            "deu" to "de", "ita" to "it", "por" to "pt", "rus" to "ru",
            "jpn" to "ja", "kor" to "ko", "chi" to "zh", "zho" to "zh"
        )
        val normalizedPrimary = iso639Aliases[primary] ?: primary
        val suffix = raw.substringAfter('-', "").takeIf { it.isNotBlank() }
        return if (suffix == null) normalizedPrimary else "$normalizedPrimary-$suffix"
    }

    private fun languageDisplayName(rawLanguage: String): String? {
        val tag = normalizedLanguageTag(rawLanguage) ?: return null
        val locale = Locale.forLanguageTag(tag)
        val code = locale.language
        if (code.isBlank() || code.equals("und", ignoreCase = true)) return null
        val name = locale.getDisplayLanguage(Locale.ENGLISH).trim()
        if (name.isBlank() || name.equals(code, ignoreCase = true)) return null
        return name.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString()
        }
    }

    private fun updateAudioButtonVisibility() {
        if (!::audioButton.isInitialized) return
        val tracks = player?.currentTracks ?: run {
            audioButton.visibility = View.GONE
            return
        }
        val languages = tracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group ->
                (0 until group.length).mapNotNull { index ->
                    if (!group.isTrackSupported(index)) return@mapNotNull null
                    val language = group.getTrackFormat(index).language ?: return@mapNotNull null
                    languageDisplayName(language)?.lowercase(Locale.ROOT)
                }
            }
            .distinct()
        // Keep the audio control available even when the stream exposes only one
        // language-tagged track; a default language can still be configured in Settings.
        val shouldShow = languages.isNotEmpty()
        val hadFocus = currentFocus === audioButton
        audioButton.visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (!shouldShow && hadFocus && ::aspectButton.isInitialized) aspectButton.requestFocus()
    }

    private fun saveSelectedAudioLanguage(tracks: androidx.media3.common.Tracks) {
        val selectedLanguage = tracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group ->
                (0 until group.length).filter { group.isTrackSelected(it) }.mapNotNull { index ->
                    group.getTrackFormat(index).language
                        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
                }
            }
            .firstOrNull()
        val normalizedLanguage = selectedLanguage?.let { normalizedLanguageTag(it) }
        if (normalizedLanguage != null && prefs.getString("last_audio_language", null) != normalizedLanguage) {
            prefs.edit().putString("last_audio_language", normalizedLanguage).apply()
        }
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
