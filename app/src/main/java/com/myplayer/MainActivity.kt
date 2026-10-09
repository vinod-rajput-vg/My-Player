package com.myplayer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.CheckBox

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("player_settings", MODE_PRIVATE) }
    private val textColor = Color.WHITE
    private val mutedColor = Color.LTGRAY
    private val backgroundColor = Color.rgb(16, 17, 20)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!handleIncomingIntent(intent)) showSettings()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handleIncomingIntent(intent)) showSettings()
    }

    private fun showSettings() {
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(40), dp(24), dp(40), dp(24))
            setBackgroundColor(backgroundColor)
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        root.addView(TextView(this).apply {
            text = "SETTINGS"
            textSize = 26f
            setTextColor(textColor)
            letterSpacing = 0.08f
        }, matchWrap())

        var firstFocusableControl: View? = null
        root.addView(sectionTitle("Playback controls"), topMargin())
        root.addView(TextView(this).apply {
            text = "Hide controls after"
            textSize = 18f
            setTextColor(mutedColor)
        }, matchWrap())

        val timeoutGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val timeoutOptions = listOf(1, 3, 5)
        timeoutOptions.forEach { seconds ->
            val radio = android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = "$seconds sec"
                textSize = 17f
                setTextColor(textColor)
                buttonTintList = android.content.res.ColorStateList.valueOf(textColor)
                isFocusable = true
                tag = seconds
            }
            timeoutGroup.addView(radio, RadioGroup.LayoutParams(0, dp(52), 1f))
            if (firstFocusableControl == null) firstFocusableControl = radio
            if (prefs.getInt("hide_timeout", 3) == seconds) timeoutGroup.check(radio.id)
        }
        timeoutGroup.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<android.widget.RadioButton>(checkedId)
            (selected?.tag as? Int)?.let { prefs.edit().putInt("hide_timeout", it).apply() }
        }
        root.addView(timeoutGroup, matchWrap())

        root.addView(sectionTitle("Remote seek interval"), topMargin())
        root.addView(TextView(this).apply {
            text = "Seconds to skip with Left / Right"
            textSize = 18f
            setTextColor(mutedColor)
        }, matchWrap())

        val seekGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(5, 10, 15, 30, 60).forEach { seconds ->
            val radio = android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = "$seconds sec"
                textSize = 16f
                setTextColor(textColor)
                buttonTintList = android.content.res.ColorStateList.valueOf(textColor)
                isFocusable = true
                tag = seconds
            }
            seekGroup.addView(radio, RadioGroup.LayoutParams(0, dp(52), 1f))
            if (prefs.getInt("seek_interval", 10) == seconds) seekGroup.check(radio.id)
        }
        seekGroup.setOnCheckedChangeListener { group, checkedId ->
            (group.findViewById<android.widget.RadioButton>(checkedId)?.tag as? Int)?.let {
                prefs.edit().putInt("seek_interval", it).apply()
            }
        }
        root.addView(seekGroup, matchWrap())

        root.addView(sectionTitle("Buttons shown during playback"), topMargin())
        root.addView(TextView(this).apply {
            text = "Choose which transport buttons are visible."
            textSize = 16f
            setTextColor(mutedColor)
        }, matchWrap())

        val buttonOptions = listOf(
            "previous" to "Previous",
            "play_pause" to "Play / Pause",
            "next" to "Next"
        )
        buttonOptions.forEach { (key, label) ->
            val checkBox = CheckBox(this).apply {
                text = label
                textSize = 17f
                setTextColor(textColor)
                buttonTintList = android.content.res.ColorStateList.valueOf(textColor)
                isFocusable = true
                isChecked = prefs.getBoolean("button_$key", true)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("button_$key", checked).apply()
                }
            }
            root.addView(checkBox, matchWrap())
            if (firstFocusableControl == null) firstFocusableControl = checkBox
        }

        val note = TextView(this).apply {
            text = "Changes apply the next time a video is opened."
            textSize = 14f
            setTextColor(mutedColor)
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(note, matchWrap())

        val scrollView = android.widget.ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(root)
        }
        setContentView(scrollView)
        firstFocusableControl?.post { firstFocusableControl?.requestFocus() }
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 20f
        setTextColor(textColor)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun topMargin() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(18) }

    private fun handleIncomingIntent(incoming: Intent?): Boolean {
        if (incoming == null) return false

        val candidate = when (incoming.action) {
            Intent.ACTION_VIEW -> incoming.dataString
            Intent.ACTION_SEND -> incoming.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim()?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("https://", true) || it.startsWith("http://", true) }

        if (candidate.isNullOrBlank()) return false

        val uri = try {
            Uri.parse(candidate).takeIf {
                it.scheme in listOf("http", "https") && !it.host.isNullOrBlank()
            }
        } catch (_: Exception) { null }

        if (uri == null) {
            Toast.makeText(this, "Invalid video URL", Toast.LENGTH_SHORT).show()
            return false
        }

        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, uri.toString()))
        finish()
        return true
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
