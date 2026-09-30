package com.example.fitna_detector

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.fitna_detector.model.DetectionSettings
import com.example.fitna_detector.model.SensitivityLevel
import com.example.fitna_detector.model.ShieldStatus
import com.example.fitna_detector.service.ScreenShieldService
import com.example.fitna_detector.ui.theme.Fitna_detectorTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Fitna_detectorTheme {
                FitnaDetectorDashboard()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitnaDetectorDashboard() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val shieldStatus by ScreenShieldService.shieldStatus.collectAsState()

    var settings by remember { mutableStateOf(DetectionSettings()) }

    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    // MediaProjection screen capture intent launcher
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, ScreenShieldService::class.java).apply {
                action = ScreenShieldService.ACTION_START
                putExtra(ScreenShieldService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenShieldService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenShieldService.EXTRA_SENSITIVITY, settings.sensitivity.name)
                putExtra(ScreenShieldService.EXTRA_ALLOW_SPEECH, settings.allowSpeechLectures)
                putExtra(ScreenShieldService.EXTRA_OPACITY, settings.overlayOpacity)
                putExtra(ScreenShieldService.EXTRA_VISUAL_ENABLED, settings.isVisualEnabled)
                putExtra(ScreenShieldService.EXTRA_MUSIC_ENABLED, settings.isMusicEnabled)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            Toast.makeText(context, "Fitna Shield Activated!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Screen capture permission required for visual protection", Toast.LENGTH_LONG).show()
        }
    }

    // Permission launcher for overlay
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
    }

    // Runtime permissions launcher (Mic + Notifications)
    val runtimePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasMicPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: hasMicPermission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: hasNotificationPermission
        }
    }

    fun startServiceFlow() {
        if (!hasOverlayPermission) {
            Toast.makeText(context, "Please allow 'Display over other apps'", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        val neededPermissions = mutableListOf<String>()
        if (!hasMicPermission) neededPermissions.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (neededPermissions.isNotEmpty()) {
            runtimePermissionsLauncher.launch(neededPermissions.toTypedArray())
            return
        }

        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    fun stopService() {
        val serviceIntent = Intent(context, ScreenShieldService::class.java).apply {
            action = ScreenShieldService.ACTION_STOP
        }
        context.startService(serviceIntent)
        Toast.makeText(context, "Fitna Shield Stopped", Toast.LENGTH_SHORT).show()
    }

    fun updateServiceSettings(newSettings: DetectionSettings) {
        settings = newSettings
        if (shieldStatus.isRunning) {
            val serviceIntent = Intent(context, ScreenShieldService::class.java).apply {
                action = ScreenShieldService.ACTION_UPDATE_SETTINGS
                putExtra(ScreenShieldService.EXTRA_SENSITIVITY, newSettings.sensitivity.name)
                putExtra(ScreenShieldService.EXTRA_ALLOW_SPEECH, newSettings.allowSpeechLectures)
                putExtra(ScreenShieldService.EXTRA_OPACITY, newSettings.overlayOpacity)
                putExtra(ScreenShieldService.EXTRA_VISUAL_ENABLED, newSettings.isVisualEnabled)
                putExtra(ScreenShieldService.EXTRA_MUSIC_ENABLED, newSettings.isMusicEnabled)
            }
            context.startService(serviceIntent)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "🛡️ Fitna Detector",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Islamic Screen & Music Shield",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Master Protection Toggle Card
            MasterSwitchCard(
                isRunning = shieldStatus.isRunning,
                onToggle = { enable ->
                    if (enable) {
                        startServiceFlow()
                    } else {
                        stopService()
                    }
                }
            )

            // Live Shield Monitor HUD Card
            LiveMonitorCard(shieldStatus = shieldStatus)

            // Permissions Checklist Card
            PermissionsCard(
                hasOverlay = hasOverlayPermission,
                hasMic = hasMicPermission,
                hasNotification = hasNotificationPermission,
                onRequestOverlay = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    overlayPermissionLauncher.launch(intent)
                },
                onRequestRuntime = {
                    val needed = mutableListOf<String>()
                    if (!hasMicPermission) needed.add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                        needed.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (needed.isNotEmpty()) {
                        runtimePermissionsLauncher.launch(needed.toTypedArray())
                    }
                }
            )

            // Detection Settings Card
            SettingsCard(
                settings = settings,
                onSettingsChanged = { updateServiceSettings(it) }
            )

            // Test Simulation Card
            TestSimulationCard(
                hasOverlay = hasOverlayPermission,
                onTestClick = {
                    if (!hasOverlayPermission) {
                        Toast.makeText(context, "Grant 'Display over other apps' to test", Toast.LENGTH_SHORT).show()
                    } else {
                        val serviceIntent = Intent(context, ScreenShieldService::class.java).apply {
                            action = ScreenShieldService.ACTION_TEST_SHIELD
                            putExtra(ScreenShieldService.EXTRA_OPACITY, settings.overlayOpacity)
                        }
                        ContextCompat.startForegroundService(context, serviceIntent)
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun MasterSwitchCard(
    isRunning: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = if (isRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Shield else Icons.Default.Security,
                        contentDescription = null,
                        tint = if (isRunning) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column {
                    Text(
                        text = if (isRunning) "Protection Active" else "Protection Paused",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRunning) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isRunning) "Scanning display & audio in background" else "Tap switch to start shield",
                        fontSize = 13.sp,
                        color = if (isRunning) Color(0xFFC8E6C9) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Switch(
                checked = isRunning,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
fun LiveMonitorCard(shieldStatus: ShieldStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Shield HUD",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                if (shieldStatus.isRunning) {
                    Text(
                        text = "Scan: ${"%.1f".format(shieldStatus.fps)} FPS",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Visual Indicator
                StatusBadge(
                    modifier = Modifier.weight(1f),
                    title = "Visual State",
                    status = if (shieldStatus.isVisualProhibited) "Prohibited!" else "Clean / Safe",
                    isWarning = shieldStatus.isVisualProhibited,
                    icon = Icons.Default.Visibility
                )

                // Audio Indicator
                StatusBadge(
                    modifier = Modifier.weight(1f),
                    title = "Audio State",
                    status = if (shieldStatus.isMusicDetected) "Music Detected!" else "Quiet / Speech",
                    isWarning = shieldStatus.isMusicDetected,
                    icon = Icons.Default.MusicNote
                )
            }

            if (shieldStatus.isShieldActive) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Text(
                            text = shieldStatus.activeTriggerReason.ifEmpty { "Red Shield Active" },
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(
    modifier: Modifier = Modifier,
    title: String,
    status: String,
    isWarning: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isWarning) Color(0xFFFFCDD2) else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isWarning) Color(0xFFC62828) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = title,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = status,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (isWarning) Color(0xFFC62828) else Color(0xFF2E7D32)
            )
        }
    }
}

@Composable
fun PermissionsCard(
    hasOverlay: Boolean,
    hasMic: Boolean,
    hasNotification: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestRuntime: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Required Permissions",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )

            PermissionRow(
                title = "Display over other apps (Red Shield)",
                isGranted = hasOverlay,
                onGrantClick = onRequestOverlay
            )

            PermissionRow(
                title = "Microphone (Acoustic Music vs Speech)",
                isGranted = hasMic,
                onGrantClick = onRequestRuntime
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionRow(
                    title = "Foreground Notification",
                    isGranted = hasNotification,
                    onGrantClick = onRequestRuntime
                )
            }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isGranted) Color(0xFF2E7D32) else Color(0xFFF57C00),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                fontSize = 13.sp
            )
        }

        if (!isGranted) {
            TextButton(onClick = onGrantClick) {
                Text(text = "Grant", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun SettingsCard(
    settings: DetectionSettings,
    onSettingsChanged: (DetectionSettings) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Protection Preferences",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )

            // Visual Shield Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Visual Shield", fontWeight = FontWeight.Medium)
                    Text(
                        text = "Flags adult content, romantic scenes & revealing attire",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.isVisualEnabled,
                    onCheckedChange = { onSettingsChanged(settings.copy(isVisualEnabled = it)) }
                )
            }

            // Music Shield Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Music & Video Audio Shield", fontWeight = FontWeight.Medium)
                    Text(
                        text = "Flags YouTube music videos & background beats",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.isMusicEnabled,
                    onCheckedChange = { onSettingsChanged(settings.copy(isMusicEnabled = it)) }
                )
            }

            // Speech & Lecture Filter Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Allow Spoken Lectures & Quran", fontWeight = FontWeight.Medium)
                    Text(
                        text = "Distinguishes human speech/talk from musical tracks",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.allowSpeechLectures,
                    onCheckedChange = { onSettingsChanged(settings.copy(allowSpeechLectures = it)) }
                )
            }

            HorizontalDivider()

            // Sensitivity Selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "Visual Detection Sensitivity: ${settings.sensitivity.displayName}", fontWeight = FontWeight.Medium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SensitivityLevel.values().forEach { level ->
                        val isSelected = settings.sensitivity == level
                        OutlinedButton(
                            onClick = { onSettingsChanged(settings.copy(sensitivity = level)) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                        ) {
                            Text(
                                text = level.displayName,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Overlay Opacity Slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Red Shield Opacity: ${(settings.overlayOpacity * 100).toInt()}%",
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = settings.overlayOpacity,
                    onValueChange = { onSettingsChanged(settings.copy(overlayOpacity = it)) },
                    valueRange = 0.70f..0.98f
                )
            }
        }
    }
}

@Composable
fun TestSimulationCard(
    hasOverlay: Boolean,
    onTestClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Test Shield Overlay",
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Text(
                text = "Previews the red screen for 3 seconds so you can verify touch and swipe passthrough behavior.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onTestClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Test Red Shield (3 Seconds)")
            }
        }
    }
}