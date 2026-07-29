package com.example.focus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var dbHelper: LogDbHelper

    private var isServicePermissionGranted by mutableStateOf(false)
    private var isBlockerEnabled by mutableStateOf(true)
    private var blockedPatterns by mutableStateOf(setOf<String>())
    private var blockLogs by mutableStateOf(listOf<BlockLog>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = LogDbHelper(this)
        
        setContent {
            FocusTheme {
                MainScreen(
                    isServicePermissionGranted = isServicePermissionGranted,
                    isBlockerEnabled = isBlockerEnabled,
                    blockedPatterns = blockedPatterns,
                    blockLogs = blockLogs,
                    onToggleBlocker = { enabled ->
                        FocusPreferences.setBlockerEnabled(this, enabled)
                        isBlockerEnabled = enabled
                    },
                    onRequestPermission = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    },
                    onAddPattern = { pattern ->
                        if (FocusPreferences.addBlockedPattern(this, pattern)) {
                            refreshData()
                        }
                    },
                    onRemovePattern = { pattern ->
                        if (FocusPreferences.removeBlockedPattern(this, pattern)) {
                            refreshData()
                        }
                    },
                    onClearLogs = {
                        dbHelper.clearAllLogs()
                        refreshData()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    private fun refreshData() {
        isServicePermissionGranted = isAccessibilityServiceEnabled(this)
        isBlockerEnabled = FocusPreferences.isBlockerEnabled(this)
        blockedPatterns = FocusPreferences.getBlockedPatterns(this)
        blockLogs = dbHelper.getRecentLogs()
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = ComponentName(context, FocusAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }
}

// Sleek Dark Theme Color Palette
val DarkBg = Color(0xFF0B0C10)
val SurfaceCard = Color(0xFF14161F)
val BorderColor = Color(0xFF1E212E)
val PrimaryIndigo = Color(0xFF6366F1)
val AccentGreen = Color(0xFF10B981)
val AlertRed = Color(0xFFEF4444)
val PausedYellow = Color(0xFFF59E0B)
val TextPrimary = Color(0xFFF3F4F6)
val TextSecondary = Color(0xFF9CA3AF)

@Composable
fun FocusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = DarkBg,
            surface = SurfaceCard,
            primary = PrimaryIndigo,
            secondary = AccentGreen
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isServicePermissionGranted: Boolean,
    isBlockerEnabled: Boolean,
    blockedPatterns: Set<String>,
    blockLogs: List<BlockLog>,
    onToggleBlocker: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onAddPattern: (String) -> Unit,
    onRemovePattern: (String) -> Unit,
    onClearLogs: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "F O C U S",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 2.sp,
                        color = TextPrimary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBg
                )
            )
        },
        containerColor = DarkBg
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Status Card
            item {
                StatusCard(
                    isServicePermissionGranted = isServicePermissionGranted,
                    isBlockerEnabled = isBlockerEnabled,
                    onRequestPermission = onRequestPermission,
                    onToggleBlocker = onToggleBlocker
                )
            }

            // Patterns Configuration Card
            item {
                PatternsCard(
                    patterns = blockedPatterns,
                    onRemovePattern = onRemovePattern,
                    onAddPatternClick = { showAddDialog = true }
                )
            }

            // Log Header with Clear Button
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Activity Log",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    if (blockLogs.isNotEmpty()) {
                        Text(
                            "Clear History",
                            color = AlertRed,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable { onClearLogs() }
                                .padding(4.dp)
                        )
                    }
                }
            }

            // Timeline logs
            if (blockLogs.isEmpty()) {
                item {
                    EmptyLogView()
                }
            } else {
                items(blockLogs) { log ->
                    LogItemView(log = log)
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        // Dialog for adding a pattern
        if (showAddDialog) {
            AddPatternDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { pattern ->
                    onAddPattern(pattern)
                    showAddDialog = false
                }
            )
        }
    }
}

@Composable
fun StatusCard(
    isServicePermissionGranted: Boolean,
    isBlockerEnabled: Boolean,
    onRequestPermission: () -> Unit,
    onToggleBlocker: (Boolean) -> Unit
) {
    val statusText: String
    val statusColor: Color
    val statusDesc: String

    if (!isServicePermissionGranted) {
        statusText = "SERVICE DISABLED"
        statusColor = AlertRed
        statusDesc = "Accessibility permission is required for the blocker to monitor and close addictive apps."
    } else if (isBlockerEnabled) {
        statusText = "BLOCKER ACTIVE"
        statusColor = AccentGreen
        statusDesc = "Currently running in the background and monitoring for Shorts/Reels links in Chrome."
    } else {
        statusText = "BLOCKER PAUSED"
        statusColor = PausedYellow
        statusDesc = "Accessibility service is running but URL interception is currently paused."
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Animated-like glowing orb
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = statusText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    letterSpacing = 1.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = statusDesc,
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (!isServicePermissionGranted) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Enable in Settings", color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Blocker Interception",
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Switch(
                        checked = isBlockerEnabled,
                        onCheckedChange = onToggleBlocker,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AccentGreen,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = BorderColor
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun PatternsCard(
    patterns: Set<String>,
    onRemovePattern: (String) -> Unit,
    onAddPatternClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Interception Rules",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    "+ Add Pattern",
                    color = PrimaryIndigo,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { onAddPatternClick() }
                        .padding(4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (patterns.isEmpty()) {
                Text(
                    "No active patterns. App will not intercept anything.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            } else {
                // Render patterns as a flow grid of chips
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    patterns.forEach { pattern ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DarkBg, RoundedCornerShape(8.dp))
                                .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pattern,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Icon(
                                imageVector = androidx.compose.material3.assist.defaultDeleteIcon(), // fallback or standard representation
                                contentDescription = "Delete Pattern",
                                tint = AlertRed.copy(alpha = 0.8f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onRemovePattern(pattern) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// Fallback extension to get a delete icon without importing complex vector asset packs
fun androidx.compose.material3.assist.defaultDeleteIcon(): androidx.compose.ui.graphics.vector.ImageVector {
    return androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = "DeleteIcon",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = androidx.compose.ui.graphics.vector.SolidColor(Color.White),
            strokeLineWidth = 2f
        ) {
            moveTo(3f, 6f)
            lineTo(21f, 6f)
            moveTo(19f, 6f)
            lineTo(19f, 19f)
            arcTo(2f, 2f, 0f, false, true, 17f, 21f)
            lineTo(7f, 21f)
            arcTo(2f, 2f, 0f, false, true, 5f, 19f)
            lineTo(5f, 6f)
            moveTo(8f, 6f)
            lineTo(8f, 4f)
            arcTo(2f, 2f, 0f, false, true, 10f, 2f)
            lineTo(14f, 2f)
            arcTo(2f, 2f, 0f, false, true, 16f, 4f)
            lineTo(16f, 6f)
        }
    }.build()
}

@Composable
fun LogItemView(log: BlockLog) {
    val formatter = SimpleDateFormat("hh:mm:ss a (dd MMM)", Locale.getDefault())
    val formattedTime = formatter.format(Date(log.timestamp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Blocked",
                    color = AlertRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = formattedTime,
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = log.url,
                color = TextPrimary,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(6.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(PrimaryIndigo)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Matched rule: ${log.pattern}",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun EmptyLogView() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 30.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "✨",
                fontSize = 32.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "No blocks recorded yet.",
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                "Stay focused and browse productively!",
                color = TextSecondary.copy(alpha = 0.6f),
                fontSize = 12.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPatternDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderColor, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Add Block Rule",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Text(
                    "Enter a URL substring or domain to intercept (e.g. tiktok.com or youtube.com/shorts). Matching is case-insensitive.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        isError = false
                    },
                    isError = isError,
                    placeholder = { Text("e.g. twitter.com", color = TextSecondary.copy(alpha = 0.5f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = PrimaryIndigo,
                        unfocusedBorderColor = BorderColor,
                        errorBorderColor = AlertRed
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (text.trim().isNotEmpty()) {
                                onAdd(text.trim())
                            } else {
                                isError = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo)
                    ) {
                        Text("Add", color = Color.White)
                    }
                }
            }
        }
    }
}
