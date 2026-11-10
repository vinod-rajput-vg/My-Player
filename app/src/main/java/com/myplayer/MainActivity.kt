package com.myplayer

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface

class MainActivity : Activity() {
    private val bg = Color.rgb(8, 10, 13)
    private val cardColor = Color.rgb(20, 24, 30)
    private val accent = Color.rgb(85, 213, 242)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(32))
        }
        scroll.addView(body)
        body.addView(TextView(this).apply {
            text = "MY PLAYER"
            setTextColor(accent); textSize = 13f; letterSpacing = .18f
            typeface = Typeface.DEFAULT_BOLD
        })
        body.addView(TextView(this).apply {
            text = "Settings"; setTextColor(Color.WHITE); textSize = 30f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(4))
        })
        body.addView(TextView(this).apply {
            text = "Configure your playback experience"
            setTextColor(0xFF9BA6B2.toInt()); textSize = 14f
            setPadding(0, 0, 0, dp(22))
        })
        addChoice(body, "Controls auto-hide", listOf("1 second", "2 seconds", "3 seconds"),
            (PlayerSettings.hideDelay(this) - 1).coerceIn(0, 2)) {
            PlayerSettings.setHideDelay(this, it + 1)
        }
        addChoice(body, "Preferred video quality", listOf("Original", "1080p", "720p"),
            listOf("Original", "1080p", "720p").indexOf(PlayerSettings.quality(this)).coerceAtLeast(0)) {
            PlayerSettings.setQuality(this, listOf("Original", "1080p", "720p")[it])
        }
        addChoice(body, "Seek interval", listOf("5 seconds", "10 seconds", "15 seconds"),
            when (PlayerSettings.seekSeconds(this)) { 5 -> 0; 15 -> 2; else -> 1 }) {
            PlayerSettings.setSeekSeconds(this, listOf(5, 10, 15)[it])
        }
        addChoice(body, "Default audio language", listOf("Hindi", "English", "Original"),
            when (PlayerSettings.audioLanguage(this)) { "en" -> 1; "und" -> 2; else -> 0 }) {
            PlayerSettings.setAudioLanguage(this, listOf("hi", "en", "und")[it])
        }
        addChoice(body, "Aspect ratio", listOf("Fill", "Fit", "Zoom"),
            listOf("Fill", "Fit", "Zoom").indexOf(PlayerSettings.aspectRatio(this)).coerceAtLeast(0)) {
            PlayerSettings.setAspectRatio(this, listOf("Fill", "Fit", "Zoom")[it])
        }
        body.addView(TextView(this).apply {
            text = "My Player  •  Version 1.0.0"
            setTextColor(0xFF9BA6B2.toInt()); textSize = 12f
            setPadding(0, dp(26), 0, 0)
        })
        setContentView(scroll)
    }

    private fun addChoice(parent: LinearLayout, title: String, values: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackgroundColor(cardColor)
        }
        card.addView(TextView(this).apply {
            text = title; textSize = 15f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(selected)
            setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(position)
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            })
        })
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
