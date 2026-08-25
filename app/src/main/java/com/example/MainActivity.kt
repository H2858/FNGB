package com.example

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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.bot.BotManager
import com.example.bot.BotStatistics
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NinjaAccent
import com.example.ui.theme.NinjaDark
import com.example.ui.theme.NinjaOnDark
import com.example.ui.theme.NinjaPrimary
import com.example.ui.theme.NinjaSecondary
import com.example.ui.theme.NinjaSurface
import com.example.ui.theme.NinjaSurfaceVariant
import com.example.ui.theme.NinjaTertiary

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                FruitNinjaBotScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FruitNinjaBotScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val botStats by BotManager.stats.collectAsState()
    val isBotActive by BotManager.isBotActive.collectAsState()
    val isAccessibilityConnected by FruitBotService.isServiceConnected.collectAsState()

    var isOverlayPermissionGranted by remember { mutableStateOf(checkOverlayPermission(context)) }
    var isNotificationPermissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var isMediaProjectionGranted by remember { mutableStateOf(ScreenCaptureService.projectionData != null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isOverlayPermissionGranted = checkOverlayPermission(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    isNotificationPermissionGranted =
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            isMediaProjectionGranted = true
            ScreenCaptureService.startService(context, result.resultCode, result.data!!)
            OverlayService.start(context)
            Toast.makeText(context, "Bot & Screen Capture Launched!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Screen capture permission is required for Fruit Ninja Bot", Toast.LENGTH_LONG).show()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isNotificationPermissionGranted = isGranted
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = NinjaDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "🍉 FRUIT NINJA BOT",
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp,
                            fontSize = 19.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isBotActive) NinjaSecondary else Color(0xFF555555))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isBotActive) "RUNNING" else "STANDBY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isBotActive) Color.Black else Color.White
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { BotManager.resetStats() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Stats", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NinjaSurface,
                    titleContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("bot_main_scroll"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ModelStatusCard(botStats)
            }

            item {
                PermissionsCard(
                    isAccessibility = isAccessibilityConnected,
                    isOverlay = isOverlayPermissionGranted,
                    isProjection = isMediaProjectionGranted,
                    isNotification = isNotificationPermissionGranted,
                    onOpenAccessibility = { openAccessibilitySettings(context) },
                    onOpenOverlay = { openOverlaySettings(context) },
                    onRequestProjection = {
                        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                    },
                    onRequestNotification = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }

            item {
                MasterControlSection(
                    isAllReady = isAccessibilityConnected && isOverlayPermissionGranted,
                    isCapturing = isMediaProjectionGranted && botStats.isRunning,
                    onLaunch = {
                        if (!isOverlayPermissionGranted) {
                            openOverlaySettings(context)
                            return@MasterControlSection
                        }
                        if (!isAccessibilityConnected) {
                            openAccessibilitySettings(context)
                            return@MasterControlSection
                        }
                        if (ScreenCaptureService.projectionData != null) {
                            ScreenCaptureService.startService(
                                context,
                                ScreenCaptureService.projectionResultCode,
                                ScreenCaptureService.projectionData!!
                            )
                            OverlayService.start(context)
                        } else {
                            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                        }
                    },
                    onStop = {
                        ScreenCaptureService.stopService(context)
                        OverlayService.stop(context)
                        BotManager.updateRunningState(false)
                        Toast.makeText(context, "Bot Stopped", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            item {
                LiveStatsCard(botStats)
            }

            item {
                BotConfigCard()
            }

            item {
                GestureTestCard(isAccessibilityConnected)
            }

            item {
                QuickStartGuideCard()
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun ModelStatusCard(stats: BotStatistics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NinjaSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(NinjaPrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "⚡", fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "YOLOv8 NCNN Detection Engine",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
                Text(
                    text = if (stats.isModelLoaded) "Model Active: best_ncnn_model (param + bin)" else "Loading assets/best_ncnn_model...",
                    fontSize = 12.sp,
                    color = if (stats.isModelLoaded) NinjaSecondary else NinjaTertiary
                )
                if (stats.modelParamInfo.isNotEmpty()) {
                    Text(
                        text = stats.modelParamInfo,
                        fontSize = 11.sp,
                        color = Color(0xFFAAAAAA)
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionsCard(
    isAccessibility: Boolean,
    isOverlay: Boolean,
    isProjection: Boolean,
    isNotification: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onRequestProjection: () -> Unit,
    onRequestNotification: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NinjaSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = NinjaAccent, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Required Permissions",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            PermissionRow(
                title = "Accessibility Gesture Engine",
                subtitle = "Enables automated high-speed swiping across fruits",
                isGranted = isAccessibility,
                buttonText = "Enable",
                onClick = onOpenAccessibility
            )

            Spacer(modifier = Modifier.height(8.dp))

            PermissionRow(
                title = "Floating Overlay Window",
                subtitle = "Displays draggable Start/Stop HUD above Fruit Ninja",
                isGranted = isOverlay,
                buttonText = "Grant",
                onClick = onOpenOverlay
            )

            Spacer(modifier = Modifier.height(8.dp))

            PermissionRow(
                title = "MediaProjection Screen Capture",
                subtitle = "Captures game frames for YOLOv8 real-time target detection",
                isGranted = isProjection,
                buttonText = "Authorize",
                onClick = onRequestProjection
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Spacer(modifier = Modifier.height(8.dp))
                PermissionRow(
                    title = "Foreground Notifications",
                    subtitle = "Maintains persistent background capture service",
                    isGranted = isNotification,
                    buttonText = "Allow",
                    onClick = onRequestNotification
                )
            }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    buttonText: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = NinjaSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isGranted) NinjaSecondary else NinjaTertiary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
                Text(text = subtitle, fontSize = 11.sp, color = Color(0xFFAAAAAA))
            }
            if (!isGranted) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NinjaAccent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = buttonText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }
    }
}

@Composable
fun MasterControlSection(
    isAllReady: Boolean,
    isCapturing: Boolean,
    onLaunch: () -> Unit,
    onStop: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onLaunch,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("launch_bot_button"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NinjaSecondary
            )
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "LAUNCH BOT & FLOATING OVERLAY",
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
                color = Color.Black
            )
        }

        if (isCapturing) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NinjaPrimary),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = Brush.horizontalGradient(listOf(NinjaPrimary, NinjaPrimary)))
            ) {
                Text(text = "🛑 STOP BOT SERVICES", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = NinjaPrimary)
            }
        }
    }
}

@Composable
fun LiveStatsCard(stats: BotStatistics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NinjaSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "⚡", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Live Bot Telemetry",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBadge(label = "FPS", value = "${stats.fps}", color = NinjaAccent, modifier = Modifier.weight(1f))
                StatBadge(label = "FRUITS SLICED", value = "${stats.totalSlices}", color = NinjaSecondary, modifier = Modifier.weight(1f))
                StatBadge(label = "BOMBS IGNORED", value = "${stats.bombsAvoided}", color = NinjaPrimary, modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Total Frames: ${stats.totalFramesProcessed}", fontSize = 12.sp, color = Color(0xFFAAAAAA))
                Text(text = "Inference: ${stats.lastInferenceTimeMs}ms", fontSize = 12.sp, color = Color(0xFFAAAAAA))
            }
        }
    }
}

@Composable
fun StatBadge(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = NinjaSurfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                color = color
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFB0B0B0),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun BotConfigCard() {
    var confidence by remember { mutableFloatStateOf(BotManager.confidenceThreshold) }
    var swipeDuration by remember { mutableFloatStateOf(BotManager.swipeDurationMs.toFloat()) }
    var sliceLength by remember { mutableFloatStateOf(BotManager.sliceLengthPx) }
    var angleMode by remember { mutableFloatStateOf(BotManager.sliceAngleDegrees) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NinjaSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = NinjaSecondary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Bot Fine-Tuning",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "YOLOv8 Detection Confidence: ${(confidence * 100).toInt()}%",
                fontSize = 13.sp,
                color = Color.White
            )
            Slider(
                value = confidence,
                onValueChange = {
                    confidence = it
                    BotManager.confidenceThreshold = it
                },
                valueRange = 0.20f..0.85f,
                colors = SliderDefaults.colors(
                    thumbColor = NinjaSecondary,
                    activeTrackColor = NinjaSecondary
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Swipe Duration: ${swipeDuration.toInt()} ms (Faster = Cleaner Slices)",
                fontSize = 13.sp,
                color = Color.White
            )
            Slider(
                value = swipeDuration,
                onValueChange = {
                    swipeDuration = it
                    BotManager.swipeDurationMs = it.toLong()
                },
                valueRange = 15f..80f,
                colors = SliderDefaults.colors(
                    thumbColor = NinjaAccent,
                    activeTrackColor = NinjaAccent
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Slice Stroke Length: ${sliceLength.toInt()} px",
                fontSize = 13.sp,
                color = Color.White
            )
            Slider(
                value = sliceLength,
                onValueChange = {
                    sliceLength = it
                    BotManager.sliceLengthPx = it
                },
                valueRange = 140f..360f,
                colors = SliderDefaults.colors(
                    thumbColor = NinjaTertiary,
                    activeTrackColor = NinjaTertiary
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Slice Angle Mode",
                fontSize = 13.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "45° Diagonal" to 45f,
                    "135° Anti" to 135f,
                    "0° Horizontal" to 0f,
                    "90° Vertical" to 90f
                ).forEach { (label, deg) ->
                    val isSelected = angleMode == deg
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            angleMode = deg
                            BotManager.sliceAngleDegrees = deg
                        },
                        label = { Text(text = label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NinjaPrimary,
                            selectedLabelColor = Color.White,
                            containerColor = NinjaSurfaceVariant,
                            labelColor = Color(0xFFAAAAAA)
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun GestureTestCard(isAccessibilityConnected: Boolean) {
    val context = LocalContext.current
    var testFeedback by remember { mutableStateOf("Tap target below to test gesture engine") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NinjaSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "👆", fontSize = 18.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Accessibility Gesture Playground",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = testFeedback, fontSize = 12.sp, color = NinjaTertiary)

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, NinjaSurfaceVariant, RoundedCornerShape(12.dp))
                    .clickable {
                        if (!isAccessibilityConnected) {
                            testFeedback = "⚠️ Accessibility Service not enabled. Please enable in Settings."
                            openAccessibilitySettings(context)
                        } else {
                            val metrics = context.resources.displayMetrics
                            val centerX = metrics.widthPixels / 2f
                            val centerY = metrics.heightPixels / 2f
                            val dispatched = FruitBotService.performSwipe(
                                startX = centerX - 120f,
                                startY = centerY - 120f,
                                endX = centerX + 120f,
                                endY = centerY + 120f,
                                durationMs = BotManager.swipeDurationMs
                            ) {
                                testFeedback = "⚡ Fast diagonal slice dispatched successfully!"
                            }
                            if (dispatched) {
                                testFeedback = "Dispatching test swipe across screen..."
                            }
                        }
                    },
                color = Color(0xFF16171B)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "🍉", fontSize = 28.sp)
                        Text(
                            text = "Tap to simulate fruit slice swipe",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NinjaOnDark
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QuickStartGuideCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NinjaSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📖 How to use Fruit Ninja Bot",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(10.dp))

            val steps = listOf(
                "1. Enable Accessibility Service so the bot can perform swipe gestures.",
                "2. Grant Draw Over Other Apps so the floating control pill appears on top.",
                "3. Tap 'LAUNCH BOT & FLOATING OVERLAY' and accept screen capture.",
                "4. Open the Fruit Ninja game on your device.",
                "5. Tap 'START BOT' on the floating overlay HUD.",
                "6. Watch the YOLOv8 model detect fruits, perform instant diagonal slices, and ignore bombs!"
            )

            steps.forEach { step ->
                Text(
                    text = step,
                    fontSize = 12.sp,
                    color = Color(0xFFCCCCCC),
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

private fun checkOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else true
}

private fun openOverlaySettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }
}

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    context.startActivity(intent)
}
