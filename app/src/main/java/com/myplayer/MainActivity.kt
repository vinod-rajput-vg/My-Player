package com.myplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var urlInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 17, 20)
        window.navigationBarColor = Color.rgb(16, 17, 20)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(28), dp(48), dp(28))
            setBackgroundColor(Color.rgb(16, 17, 20))
            isFocusableInTouchMode = true
        }

        val title = TextView(this).apply {
            text = "MY PLAYER"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
        }
        root.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "Play a video from a URL"
            textSize = 18f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
        }
        root.addView(subtitle, matchWrap())

        urlInput = EditText(this).apply {
            hint = "https://example.com/video.m3u8"
            textSize = 18f
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setBackgroundColor(Color.rgb(38, 40, 46))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    playUrl()
                    true
                } else false
            }
        }
        root.addView(urlInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
        ))

        val playButton = makeButton("PLAY VIDEO") { playUrl() }
        val buttonParams = LinearLayout.LayoutParams(dp(260), dp(56)).apply {
            gravity = Gravity.CENTER
            topMargin = dp(20)
        }
        root.addView(playButton, buttonParams)

        val help = TextView(this).apply {
            text = "Supports compatible HLS (.m3u8), MP4, and other device-supported media URLs."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(help, matchWrap())

        setContentView(root)
        handleIncomingIntent(intent)
        if (urlInput.text.isNullOrBlank()) urlInput.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return

        val incomingUrl = when (incoming.action) {
            Intent.ACTION_VIEW -> incoming.dataString
            Intent.ACTION_SEND -> incoming.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim()?.let(::extractHttpUrl)

        if (!incomingUrl.isNullOrBlank()) {
            urlInput.setText(incomingUrl)
            urlInput.setSelection(urlInput.text.length)
            playUrl()
        }
    }

    private fun extractHttpUrl(text: String): String? {
        val candidate = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("https://", true) || it.startsWith("http://", true) }
            ?: return null

        return try {
            Uri.parse(candidate).takeIf {
                it.scheme in listOf("http", "https") && !it.host.isNullOrBlank()
            }?.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun playUrl() {
        val raw = urlInput.text.toString().trim()
        if (raw.isEmpty()) {
            Toast.makeText(this, "Enter a video URL", Toast.LENGTH_SHORT).show()
            urlInput.requestFocus()
            return
        }

        val uri = try {
            Uri.parse(raw).also {
                if (it.scheme !in listOf("http", "https") || it.host.isNullOrBlank()) {
                    throw IllegalArgumentException("Enter a valid HTTP or HTTPS URL")
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Enter a valid HTTP or HTTPS URL", Toast.LENGTH_LONG).show()
            urlInput.requestFocus()
            return
        }

        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(urlInput.windowToken, 0)

        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_URL, uri.toString()))
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
