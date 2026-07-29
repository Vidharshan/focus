package com.example.focus

import android.accessibilityservice.AccessibilityService
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

    // Cache the last event text for title-based detection
    private var lastEventTexts = mutableListOf<String>()

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
            // Capture event text (Chrome sends page title here even in fullscreen)
            event.text?.let { textList ->
                lastEventTexts.clear()
                for (cs in textList) {
                    cs?.toString()?.let { lastEventTexts.add(it) }
                }
            }
            // Also capture content description from the event
            event.contentDescription?.toString()?.let {
                if (it.isNotBlank()) lastEventTexts.add(it)
            }

            startPolling()
            checkCurrentScreen()
        } else {
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

            val blockedPatterns = FocusPreferences.getBlockedPatterns(this)

            // ── Signal 1: URL bar text (works when URL bar is visible) ──
            val url = extractChromeUrl(rootNode)
            if (url != null) {
                val matchedPattern = matchesBlockedPattern(url, blockedPatterns)
                if (matchedPattern != null) {
                    triggerBlock(url, matchedPattern)
                    return
                }
            }

            // ── Signal 2: Page title from event text (works in fullscreen) ──
            // Chrome broadcasts the page title in accessibility events even when
            // the URL bar is hidden (e.g. during fullscreen Shorts playback).
            for (eventText in lastEventTexts) {
                val matchedPattern = matchesBlockedPattern(eventText, blockedPatterns)
                if (matchedPattern != null) {
                    triggerBlock(eventText, matchedPattern)
                    return
                }
            }

            // ── Signal 3: Deep node tree scan for text & content descriptions ──
            // Searches ALL visible text and content descriptions across the
            // entire Chrome accessibility tree. This catches Shorts/Reels UI
            // elements, tab titles, page content, and any other signal that
            // contains the blocked pattern — even with the URL bar hidden.
            val allVisibleText = mutableListOf<String>()
            traverseNodeTreeSafe(rootNode) { node ->
                node.text?.toString()?.let { text ->
                    if (text.length > 3) allVisibleText.add(text)
                }
                node.contentDescription?.toString()?.let { desc ->
                    if (desc.length > 3) allVisibleText.add(desc)
                }
            }

            for (text in allVisibleText) {
                val matchedPattern = matchesBlockedPattern(text, blockedPatterns)
                if (matchedPattern != null) {
                    triggerBlock(text, matchedPattern)
                    return
                }
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

    private fun triggerBlock(detectedText: String, matchedPattern: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBlockTime < BLOCK_COOLDOWN_MS) {
            return
        }

        lastBlockTime = currentTime

        // Execute global back action to redirect user out of the blocked content
        performGlobalAction(GLOBAL_ACTION_BACK)

        // Log block event
        dbHelper.logBlockEvent(detectedText, matchedPattern)
    }

    private fun matchesBlockedPattern(text: String, patterns: Set<String>): String? {
        val cleanText = text.lowercase().trim()
        for (pattern in patterns) {
            val cleanPattern = pattern.lowercase().trim()
            if (cleanPattern.isNotEmpty() && cleanText.contains(cleanPattern)) {
                return pattern
            }
        }
        return null
    }
}
