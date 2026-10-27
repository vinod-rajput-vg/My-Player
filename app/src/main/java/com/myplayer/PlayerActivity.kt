package com.myplayer

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale

class PlayerActivity : Activity() {
    companion object { const val EXTRA_URL = "com.myplayer.EXTRA_URL" }

    private var player: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var playerView: PlayerView
    private lateinit var controls: LinearLayout
    private lateinit var aspectButton: TextView
    private lateinit var audioButton: TextView
    private val prefs by lazy { getSharedPreferences("player_settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private var controlsHideDelay = 3000L
    private var retryAttempt = 0
    private val maxAutoRetries = 12
    private var playbackReleased = false
    private var isTsStream = false
    private var retryFromBeginning = false
    private var audioPreferenceApplied = false
    private val retryPlayback = Runnable {
        val activePlayer = player ?: return@Runnable
        if (playbackReleased || isFinishing || isDestroyed) return@Runnable
        if (retryFromBeginning) activePlayer.seekToDefaultPosition()
        retryFromBeginning = false
        activePlayer.prepare()
        activePlayer.playWhenReady = true
    }
    private val resetRetryCounter = Runnable { retryAttempt = 0 }

    private val hideControls = Runnable {
        if (::controls.isInitialized) controls.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) { finish(); return }

        // TextureView composes more reliably than SurfaceView in some desktop emulators.
        playerView = layoutInflater.inflate(R.layout.player_surface, null, false) as PlayerView
        playerView.apply {
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

        root.addView(controls, FrameLayout.LayoutParams(-1, dp(80), Gravity.BOTTOM))
        setContentView(root)

        val parsedUri = android.net.Uri.parse(url)
        val path = parsedUri.path.orEmpty()
        val isHlsEndpoint = path.endsWith(".m3u8", true) ||
            (path.endsWith(".php", true) && parsedUri.queryParameterNames.isNotEmpty())
        val isDashEndpoint = path.endsWith(".mpd", true)
        isTsStream = !isHlsEndpoint && !isDashEndpoint && (
            path.endsWith(".ts", true) ||
            path.endsWith(".mpegts", true) ||
            path.endsWith(".m2ts", true)
        )
        audioPreferenceApplied = false
        val mediaItem = MediaItem.Builder().setUri(url)
            .apply {
                when {
                    isHlsEndpoint -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    isDashEndpoint -> setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }.build()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setUserAgent("MyPlayer/1.0 (Android TV)")
        // Progressive MPEG-TS can contain multiple programs/PMTs. Use multi-PMT extraction
        // so Media3 has a chance to expose every audio PID/program instead of only one.
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorMode(TsExtractor.MODE_MULTI_PMT)
        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
            .setDataSourceFactory(httpFactory)
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
            // Do not hide audio renditions just because the device reports channel-count limits.
            // Let the selector attempt formats that exceed renderer capabilities when possible.
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .setExceedRendererCapabilitiesIfNecessary(true)
        if (maxVideoSize == null) {
            trackParameters.clearVideoSizeConstraints()
        } else {
            trackParameters.setMaxVideoSize(maxVideoSize.first, maxVideoSize.second)
        }
        trackSelector.parameters = trackParameters.build()

        val renderersFactory = DefaultRenderersFactory(this)
            // Keep Android's normal hardware-first decoder ordering, but fall back if needed.
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e(
                        "MyPlayerDASH",
                        "Playback error: code=${error.errorCode}, name=${error.errorCodeName}, " +
                            "message=${error.message}, cause=${error.cause?.javaClass?.name}: ${error.cause?.message}",
                        error
                    )
                    handler.removeCallbacks(retryPlayback)
                    val retryable = isRetryablePlaybackError(error)
                    if (retryable && retryAttempt < maxAutoRetries && !playbackReleased) {
                        retryAttempt++
                        // Reconnect immediately once, then back off to avoid hammering a failed endpoint.
                        val delayMs = if (retryAttempt == 1) 0L else (1000L shl (retryAttempt - 2).coerceAtMost(4)).coerceAtMost(15_000L)
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
                            if (retryable) "Reconnect limit reached. Restart playback to try again."
                            else "Playback failed: $detail",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                override fun onRenderedFirstFrame() {
                    android.util.Log.i("MyPlayerDASH", "First video frame rendered successfully")
                }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    android.util.Log.i(
                        "MyPlayerDASH",
                        "Video size=${videoSize.width}x${videoSize.height}; " +
                            "pixelRatio=${videoSize.pixelWidthHeightRatio}"
                    )
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {

                    handler.removeCallbacks(resetRetryCounter)
                    if (isPlaying) handler.postDelayed(resetRetryCounter, 30_000L)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    android.util.Log.d(
                        "MyPlayerDASH",
                        "Playback state=${when (playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> playbackState.toString()
                        }}; playWhenReady=${exo.playWhenReady}; isPlaying=${exo.isPlaying}"
                    )
                    updateProgress()
                    // A progressive .ts response may end normally when the server closes it.
                    // Re-open the same URL immediately while playback is still requested.
                    if (playbackState == Player.STATE_ENDED && isTsStream && exo.playWhenReady && !playbackReleased) {
                        handler.removeCallbacks(retryPlayback)
                        retryFromBeginning = true
                        if (retryAttempt < maxAutoRetries) {
                            retryAttempt++
                            val delayMs = if (retryAttempt == 1) 0L else (1000L shl (retryAttempt - 2).coerceAtMost(4)).coerceAtMost(15_000L)
                            handler.postDelayed(retryPlayback, delayMs)
                        } else {
                            Toast.makeText(this@PlayerActivity, "Reconnect limit reached. Restart playback to try again.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    android.util.Log.d(
                        "MyPlayerDASH",
                        "Video groups discovered: ${videoGroups.size}; " +
                            videoGroups.flatMap { group ->
                                (0 until group.length).map { index ->
                                    val format = group.getTrackFormat(index)
                                    "mime=${format.sampleMimeType}, codecs=${format.codecs}, " +
                                        "size=${format.width}x${format.height}, " +
                                        "selected=${group.isTrackSelected(index)}, " +
                                        "supported=${group.isTrackSupported(index)}, " +
                                        "support=${group.getTrackSupport(index)}"
                                }
                            }.joinToString(" | ")
                    )
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    android.util.Log.d(
                        "MyPlayerAudio",
                        "Audio groups discovered: ${audioGroups.size}; " +
                            audioGroups.flatMap { group ->
                                (0 until group.length).map { index ->
                                    val format = group.getTrackFormat(index)
                                    "language=${format.language}, mime=${format.sampleMimeType}, " +
                                        "channels=${format.channelCount}, supported=${group.isTrackSupported(index)}"
                                }
                            }.joinToString(" | ")
                    )
                    applySavedAudioPreference(audioGroups)
                }
            })
            // Route DASH playlists through Media3's DASH source explicitly. This avoids
            // relying on URL/content-type inference for .mpd streams served with generic headers.
            if (isDashEndpoint) {
                exo.setMediaSource(DashMediaSource.Factory(httpFactory).createMediaSource(mediaItem))
            } else {
                exo.setMediaItem(mediaItem)
            }
            exo.prepare()
            exo.playWhenReady = true
        }
        root.isFocusableInTouchMode = true
        root.requestFocus()
        playerView.setOnClickListener { togglePlayback(); showControls() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controlsVisible = ::controls.isInitialized && controls.visibility == View.VISIBLE
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!controlsVisible) {
                        togglePlayback()
                        showControls()
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    togglePlayback()
                    showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (controlsVisible) {
                        val left = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        val focus = currentFocus
                        when {
                            focus === audioButton || focus === aspectButton -> {
                                val utilityButtons = listOf(audioButton, aspectButton)
                                val index = utilityButtons.indexOfFirst { it === focus }
                                val target = index + if (left) -1 else 1
                                if (target in utilityButtons.indices) utilityButtons[target].requestFocus()
                            }
                            else -> {
                                // Reveal the two utility controls when focus is on the video surface.
                                audioButton.requestFocus()
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
                        audioButton.requestFocus()
                        return true
                    }
                    if (currentFocus === audioButton || currentFocus === aspectButton) {
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

    private fun togglePlayback() {
        player?.let { active ->
            if (active.isPlaying) active.pause() else active.play()
        }
    }

    private fun showControls() {
        if (!::controls.isInitialized) return
        val wasHidden = controls.visibility != View.VISIBLE
        controls.visibility = View.VISIBLE

        if (wasHidden) {
            // Focus the audio selector first; the aspect control is adjacent.
            audioButton.requestFocus()
        }
        handler.removeCallbacks(hideControls)
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


    private data class AudioTrackOption(
        val label: String,
        val group: androidx.media3.common.TrackGroup?,
        val trackIndex: Int,
        val selected: Boolean,
        val disabled: Boolean = false,
        val supported: Boolean = true
    )

    private fun showAudioTrackDialog() {
        val activePlayer = player ?: return
        // Expose only the requested user-facing languages. Keep the first available
        // rendition for each language so technical/duplicate tracks do not clutter the menu.
        val languageTracks = linkedMapOf<String, AudioTrackOption>()
        activePlayer.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .forEach { group ->
                for (index in 0 until group.length) {
                    val format = group.getTrackFormat(index)
                    val languageCode = normalizedLanguageTag(format.language.orEmpty())
                        ?.substringBefore('-')
                    val label = when (languageCode) {
                        "hi" -> "Hindi"
                        "en" -> "English"
                        else -> null
                    } ?: continue
                    if (languageTracks.containsKey(languageCode)) continue
                    languageTracks[languageCode] = AudioTrackOption(
                        label = label,
                        group = group.mediaTrackGroup,
                        trackIndex = index,
                        selected = group.isTrackSelected(index),
                        supported = group.isTrackSupported(index)
                    )
                }
            }

        val options = languageTracks.values.toList()
        if (options.isEmpty()) {
            Toast.makeText(
                this,
                "Hindi/English audio tracks are not available in this stream",
                Toast.LENGTH_LONG
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
                alpha = if (option.supported) 1f else 0.72f

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
                    val selectedGroup = option.group ?: return@setOnClickListener
                    val format = selectedGroup.getFormat(option.trackIndex)
                    val builder = trackSelector.buildUponParameters()
                        .setConstrainAudioChannelCountToDeviceCapabilities(false)
                        .setExceedRendererCapabilitiesIfNecessary(true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .setOverrideForType(
                            androidx.media3.common.TrackSelectionOverride(
                                selectedGroup,
                                option.trackIndex
                            )
                        )
                    trackSelector.parameters = builder.build()
                    prefs.edit()
                        .putBoolean("audio_track_disabled", false)
                        .putString("audio_track_language", format.language.orEmpty())
                        .putString("audio_track_mime", format.sampleMimeType.orEmpty())
                        .putInt("audio_track_channel_count", format.channelCount)
                        .putInt("audio_track_sample_rate", format.sampleRate)
                        .putInt("audio_track_index", option.trackIndex)
                        .apply()
                    audioPreferenceApplied = true
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

    private fun applySavedAudioPreference(
        audioGroups: List<androidx.media3.common.Tracks.Group>
    ) {
        if (audioPreferenceApplied || audioGroups.isEmpty()) return
        val savedLanguage = prefs.getString("audio_track_language", "").orEmpty()
        val savedMime = prefs.getString("audio_track_mime", "").orEmpty()
        val savedChannels = prefs.getInt("audio_track_channel_count", -1)
        val savedSampleRate = prefs.getInt("audio_track_sample_rate", -1)
        val disableAudio = prefs.getBoolean("audio_track_disabled", false)
        val builder = trackSelector.buildUponParameters()
            .setConstrainAudioChannelCountToDeviceCapabilities(false)
            .setExceedRendererCapabilitiesIfNecessary(true)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)

        if (disableAudio) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            trackSelector.parameters = builder.build()
            audioPreferenceApplied = true
            return
        }

        val savedTag = normalizedLanguageTag(savedLanguage)
        val candidates = audioGroups.flatMap { group ->
            (0 until group.length).map { index ->
                Triple(group, index, group.getTrackFormat(index))
            }
        }
        val match = candidates.firstOrNull { (_, _, format) ->
            savedTag != null &&
                normalizedLanguageTag(format.language.orEmpty()) == savedTag &&
                (savedMime.isBlank() || format.sampleMimeType == savedMime) &&
                (savedChannels < 0 || format.channelCount == savedChannels) &&
                (savedSampleRate < 0 || format.sampleRate == savedSampleRate)
        } ?: candidates.firstOrNull { (_, _, format) ->
            savedTag != null && normalizedLanguageTag(format.language.orEmpty()) == savedTag
        } ?: candidates.firstOrNull { (_, _, format) ->
            savedMime.isNotBlank() && format.sampleMimeType == savedMime &&
                (savedChannels < 0 || format.channelCount == savedChannels) &&
                (savedSampleRate < 0 || format.sampleRate == savedSampleRate)
        }

        if (match != null) {
            val (group, index, _) = match
            builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            builder.setOverrideForType(
                androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index)
            )
            trackSelector.parameters = builder.build()
            audioPreferenceApplied = true
        }
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
        // Keep the player instance so returning to this activity does not leave a blank surface.
        // Pause playback while the activity is not visible; release it in onDestroy.
        handler.removeCallbacks(hideControls)
        handler.removeCallbacks(retryPlayback)
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        playbackReleased = true
        handler.removeCallbacksAndMessages(null)
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
