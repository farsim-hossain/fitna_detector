package com.example.fitna_detector

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.fitna_detector.model.DetectionSettings
import com.example.fitna_detector.model.SensitivityLevel
import com.example.fitna_detector.model.ShieldStatus
import com.example.fitna_detector.service.FitnaAccessibilityService
import com.example.fitna_detector.service.ScreenShieldService
import com.example.fitna_detector.ui.theme.Fitna_detectorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Fitna_detectorTheme {
                var showSplashScreen by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    delay(2600)
                    showSplashScreen = false
                }

                if (showSplashScreen) {
                    SplashScreen(onEnter = { showSplashScreen = false })
                } else {
                    FitnaDetectorDashboard(onShowSplash = { showSplashScreen = true })
                }
            }
        }
    }
}

@Composable
fun SplashScreen(onEnter: () -> Unit) {
    val alphaAnim = remember { Animatable(0f) }
    val scaleAnim = remember { Animatable(0.82f) }

    LaunchedEffect(Unit) {
        launch {
            alphaAnim.animateTo(1f, animationSpec = tween(1000, easing = FastOutSlowInEasing))
        }
        launch {
            scaleAnim.animateTo(1f, animationSpec = tween(1000, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070F14)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(32.dp)
                .graphicsLayer(
                    alpha = alphaAnim.value,
                    scaleX = scaleAnim.value,
                    scaleY = scaleAnim.value
                )
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = "Fitna Detector Logo",
                modifier = Modifier
                    .size(190.dp)
                    .clip(RoundedCornerShape(32.dp))
            )

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Fitna Detector",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE0C475), // Gold emblem tone
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Guarding Your Gaze & Ears",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFA5D6A7), // Soft emerald tone
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "\"Tell the believing men to lower their gaze and guard their modesty; that is purer for them.\" — Surah An-Nur 24:30",
                fontSize = 13.sp,
                fontStyle = FontStyle.Italic,
                color = Color(0xFFB0BEC5),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(36.dp))

            Button(
                onClick = onEnter,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "Enter Shield Dashboard", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

fun checkAccessibilityPermission(context: Context): Boolean {
    val expectedId = ComponentName(context, FitnaAccessibilityService::class.java).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServices)
    while (colonSplitter.hasNext()) {
        val component = colonSplitter.next()
        if (component.equals(expectedId, ignoreCase = true) || component.contains("FitnaAccessibilityService")) {
            return true
        }
    }
    return false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitnaDetectorDashboard(onShowSplash: () -> Unit = {}) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var isAccessibilityEnabled by remember {
        mutableStateOf(checkAccessibilityPermission(context))
    }

    // Refresh accessibility state when returning from system settings
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isAccessibilityEnabled = checkAccessibilityPermission(context)
    }

    val foregroundStatus by ScreenShieldService.shieldStatus.collectAsState()
    val accessibilityStatus by FitnaAccessibilityService.serviceStatus.collectAsState()

    // Combined active shield status
    val activeStatus = if (isAccessibilityEnabled) accessibilityStatus else foregroundStatus
    val isRunning = isAccessibilityEnabled || foregroundStatus.isRunning

    val prefs = remember { context.getSharedPreferences("fitna_detector_prefs", Context.MODE_PRIVATE) }
    var settings by remember {
        val savedAllowed = prefs.getStringSet("custom_allowed_keywords", emptySet()) ?: emptySet()
        val savedFlagged = prefs.getStringSet("custom_flagged_keywords", emptySet()) ?: emptySet()
        val sensitivityStr = prefs.getString("sensitivity", SensitivityLevel.BALANCED.name) ?: SensitivityLevel.BALANCED.name
        val sensitivity = runCatching { SensitivityLevel.valueOf(sensitivityStr) }.getOrDefault(SensitivityLevel.BALANCED)
        val allowSpeech = prefs.getBoolean("allow_speech", false)
        val visualEnabled = prefs.getBoolean("visual_enabled", true)
        val musicEnabled = prefs.getBoolean("music_enabled", true)
        val idolEnabled = prefs.getBoolean("is_idol_enabled", true)
        val opacity = prefs.getFloat("opacity", 0.93f)
        mutableStateOf(
            DetectionSettings(
                isVisualEnabled = visualEnabled,
                isMusicEnabled = musicEnabled,
                isIdolEnabled = idolEnabled,
                sensitivity = sensitivity,
                allowSpeechLectures = allowSpeech,
                overlayOpacity = opacity,
                customAllowedKeywords = savedAllowed,
                customFlaggedKeywords = savedFlagged
            )
        )
    }

    LaunchedEffect(Unit) {
        FitnaAccessibilityService.instance?.updateSettings(settings)
    }

    var hasOverlayPermission by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        Toast.makeText(context, "Find 'Fitna Detector' and toggle it ON", Toast.LENGTH_LONG).show()
    }

    fun updateSettings(newSettings: DetectionSettings) {
        settings = newSettings
        prefs.edit()
            .putStringSet("custom_allowed_keywords", HashSet(newSettings.customAllowedKeywords))
            .putStringSet("custom_flagged_keywords", HashSet(newSettings.customFlaggedKeywords))
            .putString("sensitivity", newSettings.sensitivity.name)
            .putBoolean("allow_speech", newSettings.allowSpeechLectures)
            .putBoolean("visual_enabled", newSettings.isVisualEnabled)
            .putBoolean("music_enabled", newSettings.isMusicEnabled)
            .putBoolean("is_idol_enabled", newSettings.isIdolEnabled)
            .putFloat("opacity", newSettings.overlayOpacity)
            .apply()

        FitnaAccessibilityService.instance?.updateSettings(newSettings)

        if (foregroundStatus.isRunning) {
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = null,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Column {
                            Text(
                                text = "Fitna Detector",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "Guarding Gaze & Ears",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onShowSplash) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = "About / Opening Screen")
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

            // Single Permission Primary Setup Card
            SinglePermissionCard(
                isAccessibilityEnabled = isAccessibilityEnabled,
                onEnableClick = { openAccessibilitySettings() }
            )

            // Live Shield Monitor HUD Card
            LiveMonitorCard(shieldStatus = activeStatus)

            // Personal Keyword Filters Card (Allow List & Flag List)
            CustomKeywordFilterCard(
                settings = settings,
                onSettingsChanged = { updateSettings(it) }
            )

            // Detection Preferences Card
            SettingsCard(
                settings = settings,
                onSettingsChanged = { updateSettings(it) }
            )

            // Test Simulation Card
            TestSimulationCard(
                onTestClick = {
                    if (FitnaAccessibilityService.instance != null) {
                        FitnaAccessibilityService.instance?.testShieldTemporarily()
                        Toast.makeText(context, "Testing 30-Second Red Shield", Toast.LENGTH_SHORT).show()
                    } else if (hasOverlayPermission) {
                        val serviceIntent = Intent(context, ScreenShieldService::class.java).apply {
                            action = ScreenShieldService.ACTION_TEST_SHIELD
                            putExtra(ScreenShieldService.EXTRA_OPACITY, settings.overlayOpacity)
                        }
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } else {
                        Toast.makeText(context, "Enable Fitna Detector in Accessibility to test", Toast.LENGTH_LONG).show()
                        openAccessibilitySettings()
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun SinglePermissionCard(
    isAccessibilityEnabled: Boolean,
    onEnableClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isAccessibilityEnabled) Color(0xFF1B5E20) else MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = if (isAccessibilityEnabled) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isAccessibilityEnabled) Icons.Default.CheckCircle else Icons.Default.Security,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Column {
                    Text(
                        text = if (isAccessibilityEnabled) "Shield Active (Single Permission Granted)" else "Single-Permission Setup",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isAccessibilityEnabled) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = if (isAccessibilityEnabled)
                            "Monitoring screen & audio with 30s lockout"
                        else
                            "Grant once in Accessibility Settings. No mic, overlay, or casting popups needed!",
                        fontSize = 13.sp,
                        color = if (isAccessibilityEnabled) Color(0xFFC8E6C9) else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            if (!isAccessibilityEnabled) {
                Button(
                    onClick = onEnableClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Enable Fitna Detector (1-Tap Grant)", fontWeight = FontWeight.Bold)
                }
            }
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
                Text(
                    text = "Auto-Unblocks on Stop",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Visual Indicator
                StatusBadge(
                    modifier = Modifier.weight(1f),
                    title = "Visual",
                    status = if (shieldStatus.isVisualProhibited) "Fitna!" else "Clean",
                    isWarning = shieldStatus.isVisualProhibited,
                    icon = Icons.Default.Visibility
                )

                // Idol / Statue Indicator
                StatusBadge(
                    modifier = Modifier.weight(1f),
                    title = "Idol/Statue",
                    status = if (shieldStatus.isIdolDetected) "Idol!" else "Clean",
                    isWarning = shieldStatus.isIdolDetected,
                    icon = Icons.Default.AccountBalance
                )

                // Audio Indicator
                StatusBadge(
                    modifier = Modifier.weight(1f),
                    title = "Audio",
                    status = if (shieldStatus.isMusicDetected) "Music!" else "Speech",
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
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color.White
                            )
                            Text(
                                text = "Fitna ! change the content.",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Watch something that Islam approves (Screen unblocks immediately once stopped)",
                            color = Color(0xFFFFCDD2),
                            fontSize = 12.sp
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

            // Idol & Statue Shield Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Idol & Statue Shield", fontWeight = FontWeight.Medium)
                    Text(
                        text = "Flags statues, idols, worship rituals & religious sculptures",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.isIdolEnabled,
                    onCheckedChange = { onSettingsChanged(settings.copy(isIdolEnabled = it)) }
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
                text = "Previews the red screen with 'Fitna ! change the content. Watch something that Islam approves'. The shield stays red while prohibited content/music is active and clears once stopped.",
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
                Text(text = "Test Red Shield (5s Preview)")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomKeywordFilterCard(
    settings: DetectionSettings,
    onSettingsChanged: (DetectionSettings) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Allow List, 1 = Flag List
    var inputKeyword by remember { mutableStateOf("") }

    val currentList = if (selectedTab == 0) settings.customAllowedKeywords else settings.customFlaggedKeywords
    val accentColor = if (selectedTab == 0) Color(0xFF2E7D32) else Color(0xFFC62828)

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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(color = accentColor.copy(alpha = 0.15f), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "Personal Keyword Filters",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "Custom whitelists & blocklists in any language",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Tab switch: Allow List vs Flag List
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Allow List Tab
                Button(
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedTab == 0) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selectedTab == 0) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Allow List (${settings.customAllowedKeywords.size})",
                        fontSize = 13.sp,
                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                    )
                }

                // Flag List Tab
                Button(
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedTab == 1) Color(0xFFB71C1C) else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selectedTab == 1) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Block,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Flag List (${settings.customFlaggedKeywords.size})",
                        fontSize = 13.sp,
                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            // Description info box
            Surface(
                color = accentColor.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (selectedTab == 0)
                        "✓ Titles containing these keywords will NEVER be blocked (overrides all detections). Supports English, Bengali, Arabic, Urdu, etc."
                    else
                        "✕ Titles containing these keywords will be immediately blocked by the red shield.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(10.dp)
                )
            }

            // Input field + Add button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputKeyword,
                    onValueChange = { inputKeyword = it },
                    placeholder = {
                        Text(
                            text = if (selectedTab == 0) "e.g. Nasheed, ওয়াজ, تلاوة" else "e.g. Dance, গান, رقص",
                            fontSize = 13.sp
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                )

                Button(
                    onClick = {
                        val trimmed = inputKeyword.trim()
                        if (trimmed.isNotBlank()) {
                            if (selectedTab == 0) {
                                val updated = settings.customAllowedKeywords + trimmed
                                onSettingsChanged(settings.copy(customAllowedKeywords = updated))
                            } else {
                                val updated = settings.customFlaggedKeywords + trimmed
                                onSettingsChanged(settings.copy(customFlaggedKeywords = updated))
                            }
                            inputKeyword = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Add", fontWeight = FontWeight.Bold)
                }
            }

            // Keyword items / chips
            if (currentList.isEmpty()) {
                Text(
                    text = if (selectedTab == 0)
                        "No allowed keywords yet. Add keywords to safely exempt specific titled content."
                    else
                        "No custom flagged keywords yet. Default music/romantic filters remain active.",
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    currentList.forEach { keyword ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = accentColor.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 6.dp)
                            ) {
                                Text(
                                    text = keyword,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                IconButton(
                                    onClick = {
                                        if (selectedTab == 0) {
                                            val updated = settings.customAllowedKeywords - keyword
                                            onSettingsChanged(settings.copy(customAllowedKeywords = updated))
                                        } else {
                                            val updated = settings.customFlaggedKeywords - keyword
                                            onSettingsChanged(settings.copy(customFlaggedKeywords = updated))
                                        }
                                    },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove $keyword",
                                        tint = accentColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}