package com.myplayer

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable

class MainActivity : Activity() {
    private val bg = Color.rgb(9, 13, 19)
    private val cardColor = Color.rgb(20, 27, 36)
    private val accent = Color.rgb(77, 205, 235)
    private val muted = Color.rgb(133, 153, 171)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
        scroll.addView(body)

        addChoice(body, "◷", "Controls auto-hide", listOf("1 second", "2 seconds", "3 seconds"),
            (PlayerSettings.hideDelay(this) - 1).coerceIn(0, 2)) {
            PlayerSettings.setHideDelay(this, it + 1)
        }
        addChoice(body, "▣", "Preferred video quality", listOf("Original", "1080p", "720p"),
            listOf("Original", "1080p", "720p").indexOf(PlayerSettings.quality(this)).coerceAtLeast(0)) {
            PlayerSettings.setQuality(this, listOf("Original", "1080p", "720p")[it])
        }
        addChoice(body, "↔", "Seek interval", listOf("5 seconds", "10 seconds", "15 seconds"),
            when (PlayerSettings.seekSeconds(this)) { 5 -> 0; 15 -> 2; else -> 1 }) {
            PlayerSettings.setSeekSeconds(this, listOf(5, 10, 15)[it])
        }
        addChoice(body, "♫", "Default audio language", listOf("Hindi", "English", "Original"),
            when (PlayerSettings.audioLanguage(this)) { "en" -> 1; "und" -> 2; else -> 0 }) {
            PlayerSettings.setAudioLanguage(this, listOf("hi", "en", "und")[it])
        }
        addChoice(body, "▱", "Aspect ratio", listOf("Fill", "Fit", "Zoom"),
            listOf("Fill", "Fit", "Zoom").indexOf(PlayerSettings.aspectRatio(this)).coerceAtLeast(0)) {
            PlayerSettings.setAspectRatio(this, listOf("Fill", "Fit", "Zoom")[it])
        }
        setContentView(scroll)
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int = Color.TRANSPARENT): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != Color.TRANSPARENT) setStroke(dp(1), strokeColor)
        }
    }

    private fun addChoice(parent: LinearLayout, symbol: String, title: String, values: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(16), dp(12))
            background = rounded(cardColor, 16, Color.rgb(37, 49, 63))
            minimumHeight = dp(94)
        }

        val icon = TextView(this).apply {
            text = symbol
            textSize = 25f
            setTextColor(accent)
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(26, 48, 61), 13)
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            rightMargin = dp(16)
        })

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        details.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(selected)
            background = rounded(Color.rgb(15, 21, 29), 9)
            setPadding(dp(8), 0, dp(8), 0)
            setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected(position)
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            })
        }
        details.addView(spinner, LinearLayout.LayoutParams(-1, dp(42)).apply {
            topMargin = dp(7)
        })
        card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
