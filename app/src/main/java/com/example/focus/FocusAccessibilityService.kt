package com.example.focus

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class FocusAccessibilityService : AccessibilityService() {

    companion object {
        var isServiceRunning = false
            private set
    }

    private lateinit var dbHelper: LogDbHelper
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private var lastBlockTime = 0L
    private val BLOCK_COOLDOWN_MS = 2000L
    private val POLLING_INTERVAL_MS = 400L

    private val pollingRunnable = object : Runnable {
        override fun run() {
            if (isBlockerActive()) {
                checkCurrentScreen()
                handler.postDelayed(this, POLLING_INTERVAL_MS)
            } else {
                stopPolling()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        dbHelper = LogDbHelper(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isBlockerActive()) {
            stopPolling()
            return
        }

        val packageName = event.packageName?.toString() ?: return
        if (packageName == "com.android.chrome") {
            startPolling()
            checkCurrentScreen()
        } else {
            // Chrome is no longer the active app sending events
            stopPolling()
        }
    }

    override fun onInterrupt() {
        // Required method
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        stopPolling()
    }

    private fun isBlockerActive(): Boolean {
        return FocusPreferences.isBlockerEnabled(this)
    }

    private fun startPolling() {
        if (!isPolling) {
            isPolling = true
            handler.removeCallbacks(pollingRunnable)
            handler.post(pollingRunnable)
        }
    }

    private fun stopPolling() {
        if (isPolling) {
            isPolling = false
            handler.removeCallbacks(pollingRunnable)
        }
    }

    private fun checkCurrentScreen() {
        val rootNode = rootInActiveWindow ?: return
        try {
            val packageName = rootNode.packageName?.toString()
            if (packageName != "com.android.chrome") {
                stopPolling()
                return
            }

            val url = extractChromeUrl(rootNode)
            if (url != null) {
                evaluateUrl(url)
            }
        } catch (e: Exception) {
            // Graceful no-op
        } finally {
            try {
                rootNode.recycle()
            } catch (e: Exception) {
                // Ignore recycle exceptions for already disposed nodes
            }
        }
    }

    private fun extractChromeUrl(rootNode: AccessibilityNodeInfo): String? {
        // Method 1: Find Chrome's URL bar by exact view ID (most common)
        try {
            val urlNodes = rootNode.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
            if (urlNodes != null && urlNodes.isNotEmpty()) {
                val urlText = urlNodes[0].text?.toString()
                urlNodes.forEach {
                    try { it.recycle() } catch (e: Exception) {}
                }
                if (!urlText.isNullOrBlank()) {
                    return urlText
                }
            }
        } catch (e: Exception) {
            // Silently fall back to traversal
        }

        // Method 2: Traverse node tree searching for standard URL bars and inputs
        val foundUrls = ArrayList<String>()
        traverseNodeTreeSafe(rootNode) { node ->
            val viewId = node.viewIdResourceName
            if (viewId != null && viewId.endsWith("/url_bar")) {
                node.text?.toString()?.let { foundUrls.add(it) }
            }

            // Fallback for inputs with domain names
            if (node.className == "android.widget.EditText") {
                val text = node.text?.toString()
                if (text != null && (text.contains("http") || text.contains("www.") || text.contains(".com") || text.contains(".org") || text.contains(".net"))) {
                    foundUrls.add(text)
                }
            }
        }

        return if (foundUrls.isNotEmpty()) foundUrls[0] else null
    }

    private fun traverseNodeTreeSafe(node: AccessibilityNodeInfo, callback: (AccessibilityNodeInfo) -> Unit) {
        try {
            callback(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverseNodeTreeSafe(child, callback)
                try {
                    child.recycle()
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            // Silently swallow errors during node traversal to remain resilient
        }
    }

    private fun evaluateUrl(url: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBlockTime < BLOCK_COOLDOWN_MS) {
            return
        }

        val blockedPatterns = FocusPreferences.getBlockedPatterns(this)
        val matchedPattern = matchesBlockedPattern(url, blockedPatterns)

        if (matchedPattern != null) {
            lastBlockTime = currentTime
            
            // Execute global back action to redirect user out of the blocked content
            performGlobalAction(GLOBAL_ACTION_BACK)
            
            // Log block event
            dbHelper.logBlockEvent(url, matchedPattern)
        }
    }

    private fun matchesBlockedPattern(url: String, patterns: Set<String>): String? {
        val cleanUrl = url.lowercase().trim()
        for (pattern in patterns) {
            val cleanPattern = pattern.lowercase().trim()
            if (cleanPattern.isNotEmpty() && cleanUrl.contains(cleanPattern)) {
                return pattern
            }
        }
        return null
    }
}
