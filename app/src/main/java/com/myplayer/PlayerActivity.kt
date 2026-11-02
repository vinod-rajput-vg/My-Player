package com.myplayer

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

class PlayerActivity : Activity() {
    companion object { const val EXTRA_URL = "com.myplayer.EXTRA_URL" }

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private lateinit var playerView: VLCVideoLayout
    private lateinit var root: FrameLayout
    private lateinit var controls: LinearLayout
    private lateinit var aspectButton: TextView
    private lateinit var audioButton: TextView
    private lateinit var timeline: SeekBar
    private lateinit var positionLabel: TextView
    private lateinit var durationLabel: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var loadingLabel: TextView
    private val prefs by lazy { getSharedPreferences("player_settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private var controlsHideDelay = 3000L
    private var retryAttempt = 0
    private val maxAutoRetries = 12
    private var playbackReleased = false
    private var isLiveStream = true
    private var currentUrl: String = ""
    private val retryPlayback = Runnable {
        if (playbackReleased || isFinishing || isDestroyed || currentUrl.isBlank()) return@Runnable
        startPlayback(currentUrl)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) { finish(); return }

        val quality = prefs.getString("video_quality", "original") ?: "original"
        val options = mutableListOf(
            "--network-caching=3000",
            "--http-reconnect",
            "--avcodec-hw=any",
            "--no-video-title-show"
        )
        when (quality) {
            "1080" -> options.add("--preferred-resolution=1080")
            "720" -> options.add("--preferred-resolution=720")
            "480" -> options.add("--preferred-resolution=480")
            "360" -> options.add("--preferred-resolution=360")
            "240" -> options.add("--preferred-resolution=240")
        }
        libVlc = LibVLC(this, options)
        player = MediaPlayer(libVlc)
        playerView = VLCVideoLayout(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        player?.attachViews(playerView, null, false, false)

        controlsHideDelay = prefs.getInt("hide_timeout", 3).coerceIn(1, 5) * 1000L
        root = FrameLayout(this).apply {
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
        audioButton = controlButton("♫", "Audio track") { showAudioTrackDialog() }
        aspectButton = controlButton("⛶", "Aspect ratio") { showAspectRatioMenu(aspectButton) }
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
                    if (fromUser) positionLabel.text = formatTime((player?.length ?: 0L) * progress / 1000L)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    userSeeking = true
                    handler.removeCallbacks(progressUpdater)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val active = player
                    if (active != null && active.length > 0L) active.time = active.length * seekBar.progress / 1000L
                    userSeeking = false
                    updateProgress()
                    if (controls.visibility == View.VISIBLE) handler.postDelayed(progressUpdater, 500L)
                }
            })
        }
        timelineRow.addView(positionLabel, LinearLayout.LayoutParams(dp(48), -2))
        timelineRow.addView(timeline, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
            marginStart = dp(8); marginEnd = dp(8)
        })
        timelineRow.addView(durationLabel, LinearLayout.LayoutParams(dp(48), -2))
        utilityRow.addView(audioButton)
        utilityRow.addView(aspectButton)
        controls.addView(utilityRow, LinearLayout.LayoutParams(-1, dp(54)))
        controls.addView(timelineRow, LinearLayout.LayoutParams(-1, dp(38)))
        root.addView(controls, FrameLayout.LayoutParams(-1, dp(124), Gravity.BOTTOM))

        val loadingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(0xB8000000.toInt())
            }
            isClickable = false
            isFocusable = false
        }
        loadingIndicator = ProgressBar(this).apply {
            isIndeterminate = true
        }
        loadingLabel = TextView(this).apply {
            text = "Loading…"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        loadingContainer.addView(loadingIndicator, LinearLayout.LayoutParams(dp(44), dp(44)))
        loadingContainer.addView(loadingLabel, LinearLayout.LayoutParams(-2, -2))
        root.addView(loadingContainer, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        setLoading(true, "Connecting…")

        currentUrl = url
        startPlayback(url)
        root.isFocusableInTouchMode = true
        root.requestFocus()
        playerView.setOnClickListener {
            togglePlayback()
            showControls()
        }
        showControls()
        timeline.requestFocus()
    }

    private fun startPlayback(url: String) {
        val activePlayer = player ?: return
        if (playbackReleased) return
        try {
            handler.removeCallbacks(retryPlayback)
            activePlayer.stop()
            activePlayer.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Opening, MediaPlayer.Event.Buffering -> runOnUiThread {
                        setLoading(true, "Buffering…")
                    }
                    MediaPlayer.Event.Playing -> runOnUiThread {
                        setLoading(false)
                        handler.removeCallbacks(resetRetryCounter)
                        handler.postDelayed(resetRetryCounter, 30_000L)
                        Log.i("MyPlayerVLC", "Playback started")
                    }
                    MediaPlayer.Event.EncounteredError -> runOnUiThread {
                        setLoading(true, "Reconnecting…")
                        Log.e("MyPlayerVLC", "LibVLC playback error")
                        scheduleRetry()
                    }
                    MediaPlayer.Event.EndReached -> {
                        if (isLiveStream) scheduleRetry()
                    }
                    MediaPlayer.Event.TimeChanged -> runOnUiThread { updateProgress() }
                    MediaPlayer.Event.LengthChanged -> runOnUiThread {
                        isLiveStream = activePlayer.length <= 0L
                        updateProgress()
                    }
                    MediaPlayer.Event.Vout -> Log.i("MyPlayerVLC", "Video output changed")
                }
            }
            val media = Media(libVlc, Uri.parse(url)).apply {
                setHWDecoderEnabled(true, false)
                addOption(":http-reconnect")
                addOption(":network-caching=3000")
                addOption(":http-user-agent=MyPlayer/1.0 (Android TV)")
            }
            activePlayer.media = media
            media.release()
            activePlayer.play()
        } catch (error: Exception) {
            Log.e("MyPlayerVLC", "Unable to start playback", error)
            Toast.makeText(this, "Unable to start playback: ${error.message}", Toast.LENGTH_LONG).show()
            scheduleRetry()
        }
    }

    private fun setLoading(visible: Boolean, message: String = "Loading…") {
        if (!::loadingIndicator.isInitialized || isFinishing || isDestroyed) return
        loadingIndicator.visibility = if (visible) View.VISIBLE else View.GONE
        loadingLabel.visibility = if (visible) View.VISIBLE else View.GONE
        loadingLabel.text = message
        loadingIndicator.isIndeterminate = true
    }

    private fun scheduleRetry() {
        if (playbackReleased || retryAttempt >= maxAutoRetries) {
            setLoading(false)
            Toast.makeText(this, "Reconnect limit reached. Restart playback to try again.", Toast.LENGTH_LONG).show()
            return
        }
        handler.removeCallbacks(retryPlayback)
        retryAttempt++
        val delay = if (retryAttempt == 1) 1000L else (1000L shl (retryAttempt - 2).coerceAtMost(4)).coerceAtMost(15_000L)
        Toast.makeText(this, "Connection interrupted. Reconnecting…", Toast.LENGTH_SHORT).show()
        handler.postDelayed(retryPlayback, delay)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controlsVisible = ::controls.isInitialized && controls.visibility == View.VISIBLE
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!controlsVisible) {
                        showControls()
                        timeline.requestFocus()
                        return true
                    }
                    if (currentFocus === timeline) {
                        togglePlayback()
                        showControls()
                        timeline.requestFocus()
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
                    val left = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    val focus = currentFocus
                    if (controlsVisible && focus === timeline) {
                        seekByRemote(if (left) -prefs.getInt("seek_interval", 10) * 1000L
                            else prefs.getInt("seek_interval", 10) * 1000L)
                        showControls()
                        timeline.requestFocus()
                        return true
                    }
                    if (controlsVisible) {
                        val buttons = listOf(audioButton, aspectButton)
                        val index = buttons.indexOfFirst { it === focus }
                        if (index >= 0) {
                            val target = index + if (left) -1 else 1
                            if (target in buttons.indices) buttons[target].requestFocus()
                        } else timeline.requestFocus()
                        showControls()
                        return true
                    }
                    seekByRemote(if (left) -prefs.getInt("seek_interval", 10) * 1000L
                        else prefs.getInt("seek_interval", 10) * 1000L)
                    showControls()
                    timeline.requestFocus()
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
                    } else finishAffinity()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

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
                marginStart = dp(8); marginEnd = dp(8)
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
        val options = tracks.map { track ->
            AudioTrackOption(
                id = track.id,
                label = track.name?.takeIf { it.isNotBlank() } ?: languageDisplayName(track.name ?: "") ?: "Audio track",
                selected = activePlayer.audioTrack == track.id
            )
        }
        if (options.isEmpty()) {
            Toast.makeText(this, "Audio Tracks Are Not Available In This Stream", Toast.LENGTH_LONG).show()
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
                        setColor(if (focused) Color.rgb(42,112,174) else if (option.selected) Color.rgb(35,57,78) else Color.rgb(28,30,34))
                        setStroke(dp(if (focused) 2 else 1), if (focused) Color.rgb(143,190,255) else Color.rgb(70,75,82))
                    }
                }
                styleRow(false)
                setOnFocusChangeListener { _, focused -> styleRow(focused) }
                setOnClickListener {
                    activePlayer.setAudioTrack(option.id)
                    dialog?.dismiss()
                    showControls()
                }
            }
            list.addView(row, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(4) })
        }
        dialog = AlertDialog.Builder(this).setTitle("Audio Language").setView(scroll).setNegativeButton("Close", null).create()
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
        val aliases = mapOf("eng" to "en", "hin" to "hi", "tam" to "ta", "tel" to "te", "mal" to "ml", "kan" to "kn", "mar" to "mr", "ben" to "bn", "urd" to "ur", "guj" to "gu", "pan" to "pa", "ara" to "ar", "spa" to "es", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de", "ita" to "it", "por" to "pt", "rus" to "ru", "jpn" to "ja", "kor" to "ko", "chi" to "zh", "zho" to "zh")
        val normalized = aliases[primary] ?: primary
        val suffix = raw.substringAfter('-', "").takeIf { it.isNotBlank() }
        return if (suffix == null) normalized else "$normalized-$suffix"
    }

    private fun languageDisplayName(rawLanguage: String): String? {
        val tag = normalizedLanguageTag(rawLanguage) ?: return null
        val code = Locale.forLanguageTag(tag).language
        if (code.isBlank() || code.equals("und", ignoreCase = true)) return null
        val name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).trim()
        if (name.isBlank() || name.equals(code, ignoreCase = true)) return null
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
    }

    private fun showAspectRatioMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        listOf("Fit", "Fill", "Zoom").forEach { mode ->
            menu.menu.add(mode).setOnMenuItemClickListener {
                prefs.edit().putString("aspect_ratio", mode.lowercase(Locale.ROOT)).apply()
                applyAspectRatio()
                anchor.contentDescription = "Aspect ratio: $mode"
                showControls()
                true
            }
        }
        menu.show()
        showControls()
    }

    private fun applyAspectRatio() {
        if (!::playerView.isInitialized) return
        player?.setVideoScale(when (prefs.getString("aspect_ratio", "fit")) {
            "fill" -> MediaPlayer.ScaleType.SURFACE_FILL
            "zoom" -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        })
    }

    private fun seekByRemote(offsetMs: Long) {
        val active = player ?: return
        if (isLiveStream) return
        val duration = active.length
        val target = (active.time + offsetMs).coerceAtLeast(0L)
        active.time = if (duration > 0L) target.coerceAtMost(duration) else target
    }

    private fun updateProgress() {
        val active = player ?: return
        if (!::timeline.isInitialized || userSeeking) return
        val duration = active.length
        isLiveStream = duration <= 0L
        timeline.isEnabled = duration > 0L
        val position = active.time.coerceAtLeast(0L)
        if (duration > 0L) {
            timeline.progress = (position * 1000L / duration).toInt().coerceIn(0, 1000)
            durationLabel.text = formatTime(duration)
        } else {
            timeline.progress = 0
            durationLabel.text = if (isLiveStream) "LIVE" else "--:--"
        }
        positionLabel.text = formatTime(position)
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() { finishAffinity() }

    override fun onStop() {
        handler.removeCallbacks(hideControls)
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(retryPlayback)
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        playbackReleased = true
        handler.removeCallbacksAndMessages(null)
        player?.let { active ->
            try {
                active.stop()
                active.detachViews()
                active.release()
            } catch (error: Exception) {
                Log.w("MyPlayerVLC", "Player release failed", error)
            }
        }
        player = null
        libVlc?.release()
        libVlc = null
        super.onDestroy()
    }
}
