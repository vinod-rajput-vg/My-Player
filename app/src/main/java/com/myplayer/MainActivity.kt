package com.myplayer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSettingsPlaceholder()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun showSettingsPlaceholder() {
        window.statusBarColor = Color.rgb(16, 17, 20)
        window.navigationBarColor = Color.rgb(16, 17, 20)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(32), dp(48), dp(32))
            setBackgroundColor(Color.rgb(16, 17, 20))
            isFocusableInTouchMode = true
        }

        val title = TextView(this).apply {
            text = "SETTINGS"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        root.addView(title, matchWrap())

        val placeholder = TextView(this).apply {
            text = "Settings will be added here"
            textSize = 18f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        root.addView(placeholder, matchWrap())

        setContentView(root)
        root.requestFocus()
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return

        val candidate = when (incoming.action) {
            Intent.ACTION_VIEW -> incoming.dataString
            Intent.ACTION_SEND -> incoming.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim()?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("https://", true) || it.startsWith("http://", true) }

        if (candidate.isNullOrBlank()) return

        val uri = try {
            Uri.parse(candidate).takeIf {
                it.scheme in listOf("http", "https") && !it.host.isNullOrBlank()
            }
        } catch (_: Exception) {
            null
        }

        if (uri == null) {
            Toast.makeText(this, "Invalid video URL", Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, uri.toString()))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
