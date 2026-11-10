package com.mplayer

import android.content.Context

object PlayerSettings {
    private const val PREFS = "my_player_settings"
    fun hideDelay(context: Context) = prefs(context).getInt("hide_delay", 2)
    fun quality(context: Context) = prefs(context).getString("quality", "Original") ?: "Original"
    fun seekSeconds(context: Context) = prefs(context).getInt("seek_seconds", 10)
    fun audioLanguage(context: Context) = prefs(context).getString("audio_language", "hi") ?: "hi"
    fun aspectRatio(context: Context) = prefs(context).getString("aspect_ratio", "Fill") ?: "Fill"
    fun setHideDelay(context: Context, value: Int) = prefs(context).edit().putInt("hide_delay", value).apply()
    fun setQuality(context: Context, value: String) = prefs(context).edit().putString("quality", value).apply()
    fun setSeekSeconds(context: Context, value: Int) = prefs(context).edit().putInt("seek_seconds", value).apply()
    fun setAudioLanguage(context: Context, value: String) = prefs(context).edit().putString("audio_language", value).apply()
    fun setAspectRatio(context: Context, value: String) = prefs(context).edit().putString("aspect_ratio", value).apply()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
