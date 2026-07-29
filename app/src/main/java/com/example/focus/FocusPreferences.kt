package com.example.focus

import android.content.Context
import android.content.SharedPreferences

object FocusPreferences {
    private const val PREFS_NAME = "focus_blocker_prefs"
    private const val KEY_ENABLED = "blocker_enabled"
    private const val KEY_PATTERNS = "blocked_patterns"

    private val DEFAULT_PATTERNS = setOf(
        "youtube.com/shorts",
        "instagram.com/reels"
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isBlockerEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun setBlockerEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getBlockedPatterns(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_PATTERNS, DEFAULT_PATTERNS) ?: DEFAULT_PATTERNS
    }

    fun setBlockedPatterns(context: Context, patterns: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_PATTERNS, patterns).apply()
    }

    fun addBlockedPattern(context: Context, pattern: String): Boolean {
        val trimmed = pattern.trim().lowercase()
        if (trimmed.isEmpty()) return false
        val current = getBlockedPatterns(context).toMutableSet()
        if (current.add(trimmed)) {
            setBlockedPatterns(context, current)
            return true
        }
        return false
    }

    fun removeBlockedPattern(context: Context, pattern: String): Boolean {
        val current = getBlockedPatterns(context).toMutableSet()
        if (current.remove(pattern)) {
            setBlockedPatterns(context, current)
            return true
        }
        return false
    }
}
