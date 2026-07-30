package com.example.focus

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
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
            stopPolling()
        }
    }

    override fun onInterrupt() {
        // Required
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

            // --- 1. Direct URL check ---
            val url = extractChromeUrl(rootNode)
            if (url != null) {
                val matchedPattern = matchesBlockedPattern(url, blockedPatterns)
                if (matchedPattern != null) {
                    triggerBlock(url, matchedPattern)
                    return
                }
            }

            // --- 2. Score-based layout heuristics (if URL is hidden/missing) ---
            val score = calculateHeuristicScore(rootNode)
            if (score >= 4) {
                var logMsg = "Shorts/Reels detected (heuristic score: $score)"
                if (url != null) {
                    logMsg += " at $url"
                }
                triggerBlock(logMsg, "layout heuristics")
            }

        } catch (e: Exception) {
            // Graceful error isolation
        } finally {
            try {
                rootNode.recycle()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun calculateHeuristicScore(rootNode: AccessibilityNodeInfo): Int {
        var score = 0
        var hasRemix = false
        var hasReelUiNode = false
        var hasPortraitVideo = false
        val rightRailButtons = ArrayList<Rect>()

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        traverseNodeTreeSafe(rootNode) { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val text = node.text?.toString()?.lowercase() ?: ""
            val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            // Heuristic 1: DOM UI node identifiers
            if (viewId.contains("ytd-shorts") || viewId.contains("ytd-reel-video-renderer") ||
                viewId.contains("shorts-player") || viewId.contains("reel-video") ||
                viewId.contains("shorts-container") || viewId.contains("reel_") ||
                text.contains("ytd-shorts") || contentDesc.contains("ytd-shorts")) {
                hasReelUiNode = true
            }

            // Heuristic 2: "Remix" keyword (highly unique to Shorts)
            if (text == "remix" || contentDesc == "remix" || text.contains("remix") || contentDesc.contains("remix")) {
                hasRemix = true
            }

            // Heuristic 3: Portrait Video Surface
            val width = bounds.width()
            val height = bounds.height()
            if (width > 0 && height > 0) {
                val aspect = width.toFloat() / height.toFloat()
                val heightRatio = height.toFloat() / screenHeight.toFloat()
                // A vertical player occupying >70% of screen height
                if (aspect < 0.75f && heightRatio > 0.7f) {
                    // Exclude complete screen parent overlays if they are not the player
                    if (bounds.top > 0 || bounds.bottom < screenHeight || width < screenWidth) {
                        hasPortraitVideo = true
                    }
                }

                // Heuristic 4: Right-rail action buttons (Like, Dislike, Comments, Share, Remix, Audio)
                // Positioned on the right side (x > 75% of screen width)
                val rightThreshold = (screenWidth * 0.75f).toInt()
                if (bounds.left > rightThreshold && width < screenWidth * 0.22f && height < screenHeight * 0.15f) {
                    rightRailButtons.add(Rect(bounds))
                }
            }
        }

        if (hasRemix) score += 3
        if (hasReelUiNode) score += 2
        if (hasPortraitVideo) score += 2

        // Count vertically stacked icons in right rail
        if (rightRailButtons.size >= 3) {
            rightRailButtons.sortBy { it.top }
            var stackCount = 1
            var maxStack = 1
            for (i in 0 until rightRailButtons.size - 1) {
                val current = rightRailButtons[i]
                val next = rightRailButtons[i + 1]
                // Stacked if horizontal alignment is within 80px and next is below current
                if (Math.abs(current.left - next.left) < 80 && next.top > current.bottom) {
                    stackCount++
                    if (stackCount > maxStack) maxStack = stackCount
                } else {
                    stackCount = 1
                }
            }
            if (maxStack >= 3) {
                score += 2
            }
        }

        return score
    }

    private fun extractChromeUrl(rootNode: AccessibilityNodeInfo): String? {
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
            // Fallback
        }

        val foundUrls = ArrayList<String>()
        traverseNodeTreeSafe(rootNode) { node ->
            val viewId = node.viewIdResourceName
            if (viewId != null && viewId.endsWith("/url_bar")) {
                node.text?.toString()?.let { foundUrls.add(it) }
            }

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
            // Swallow
        }
    }

    private fun triggerBlock(detectedText: String, matchedPattern: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBlockTime < BLOCK_COOLDOWN_MS) {
            return
        }

        lastBlockTime = currentTime
        performGlobalAction(GLOBAL_ACTION_BACK)
        dbHelper.logBlockEvent(detectedText, matchedPattern)
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
