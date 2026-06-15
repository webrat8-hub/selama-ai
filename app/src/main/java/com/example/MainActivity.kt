package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    // Permission request launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordGranted) {
            startAssistantService()
        } else {
            Toast.makeText(this, "Izin Audio dan Mikrofon diperlukan untuk asisten suara Selz", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request initial permissions on launch
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        }

        setContent {
            MyApplicationTheme {
                val defaultClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                val safeClipboard = remember(defaultClipboard) {
                    object : androidx.compose.ui.platform.ClipboardManager {
                        override fun getText(): androidx.compose.ui.text.AnnotatedString? {
                            return if (hasWindowFocus()) {
                                try {
                                    defaultClipboard.getText()
                                } catch (e: Exception) {
                                    null
                                }
                            } else {
                                null
                            }
                        }

                        override fun setText(annotatedString: androidx.compose.ui.text.AnnotatedString) {
                            try {
                                defaultClipboard.setText(annotatedString)
                            } catch (e: Exception) {
                                // Ignore exceptions
                            }
                        }
                    }
                }

                CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalClipboardManager provides safeClipboard
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color(0xFF0F0F12) // Immersive background matching mockup
                    ) { innerPadding ->
                        AssistantDashboard(
                            modifier = Modifier.padding(innerPadding),
                            onToggleService = { handleServiceToggle() },
                            onLaunchAccessibilitySettings = { launchAccessibilitySettings() }
                        )
                    }
                }
            }
        }
    }

    private fun handleServiceToggle() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            val isRunning = WakeWordService.isServiceRunning.value
            if (isRunning) {
                stopService(Intent(this, WakeWordService::class.java))
                Toast.makeText(this, "Selz Assistant dihentikan", Toast.LENGTH_SHORT).show()
            } else {
                startAssistantService()
            }
        } else {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startAssistantService() {
        try {
            startForegroundService(Intent(this, WakeWordService::class.java))
            Toast.makeText(this, "Mulai mendengarkan...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Fallback for older api
            startService(Intent(this, WakeWordService::class.java))
        }
    }

    private fun launchAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Aktifkan 'Woi Selz Assistant' di list Aksesibilitas Anda", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun AssistantDashboard(
    modifier: Modifier = Modifier,
    onToggleService: () -> Unit = {},
    onLaunchAccessibilitySettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Observe background states
    val isRunning by WakeWordService.isServiceRunning.collectAsState()
    val statusText by WakeWordService.assistantStatus.collectAsState()
    val lastSpeech by WakeWordService.lastRecognizedText.collectAsState()
    val lastReply by WakeWordService.lastAssistantResponse.collectAsState()
    val historyLogs by WakeWordService.parsedCommandsList.collectAsState()
    val selectedVoice by WakeWordService.selectedVoice.collectAsState()

    var isAccessibilityEnabled by remember { mutableStateOf(AssistantAccessibilityService.instance != null) }
    var testCommandText by remember { mutableStateOf("") }
    var isSimulating by remember { mutableStateOf(false) }
    var showVoiceSelectorDialog by remember { mutableStateOf(false) }

    // Periodically poll accessibility list to auto refresh
    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityEnabled = AssistantAccessibilityService.instance != null
            kotlinx.coroutines.delay(1000)
        }
    }

    // "Immersive UI" Design palette
    val bgBlack = Color(0xFF0F0F12)
    val cardBg = Color(0xFF16161D)
    val textLight = Color(0xFFF1F5F9)
    val textMuted = Color(0xFF94A3B8)
    val neonBlue = Color(0xFF38BDF8)
    val neonPurple = Color(0xFFC084FC)
    val accentIndigo = Color(0xFF6366F1)
    val emeraldStatus = Color(0xFF34D399)

    // Visualizer pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val waveScale1 by infiniteTransition.animateFloat(
        initialValue = 12.dp.value,
        targetValue = 38.dp.value,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w1"
    )
    val waveScale2 by infiniteTransition.animateFloat(
        initialValue = 28.dp.value,
        targetValue = 64.dp.value,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w2"
    )
    val waveScale3 by infiniteTransition.animateFloat(
        initialValue = 16.dp.value,
        targetValue = 44.dp.value,
        animationSpec = infiniteRepeatable(
            animation = tween(720, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "w3"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgBlack)
    ) {
        // Atmospheric Radiant Glow Backgrounds (blur-[80px])
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(480.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(260.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                accentIndigo.copy(alpha = 0.18f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(190.dp)
                    .offset(y = 50.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                neonPurple.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 120.dp, top = 8.dp)
        ) {
            // Immersive Header Bar (Mockup Style)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Selz AI",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = textLight,
                                letterSpacing = (-0.5).sp
                            ),
                            modifier = Modifier.testTag("app_title")
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "SYSTEM CORE V1.0",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = accentIndigo,
                                letterSpacing = 2.sp
                            )
                        )
                    }

                    // Avatar Style status node with light indicator
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isRunning) emeraldStatus else Color.DarkGray
                                )
                        )
                    }
                }
            }

            // Big Central Immersive Visualizer Card
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Main Immersive Nested Circle Button
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(170.dp)
                                .clip(CircleShape)
                                .clickable { onToggleService() }
                        ) {
                            // Outer Frame (border border-slate-700)
                            Box(
                                modifier = Modifier
                                    .size(160.dp)
                                    .border(1.dp, Color(0xFF334155), CircleShape)
                                    .align(Alignment.Center)
                            )

                            // Middle Ring (border-2 border-indigo-500/30)
                            Box(
                                modifier = Modifier
                                    .size(128.dp)
                                    .border(2.dp, accentIndigo.copy(alpha = 0.35f), CircleShape)
                                    .align(Alignment.Center)
                            )

                            // Inner Core Gradient with active shadow
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            colors = if (isRunning) listOf(accentIndigo, neonPurple) else listOf(Color(0xFF2E2E38), Color(0xFF1E1E26))
                                        )
                                    )
                                    .align(Alignment.Center)
                            ) {
                                // Dynamic Soundwave Pill Animations
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(5.dp)
                                            .height(if (isRunning) waveScale1.dp else 10.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(Color.White.copy(alpha = 0.5f))
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(5.dp)
                                            .height(if (isRunning) waveScale2.dp else 16.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(Color.White)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(5.dp)
                                            .height(if (isRunning) waveScale3.dp else 10.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(Color.White.copy(alpha = 0.5f))
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = if (isRunning) "Mendengarkan pemicu" else "Tekan lingkaran untuk mengaktifkan",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                color = accentIndigo.copy(alpha = 0.85f),
                                letterSpacing = 0.5.sp
                            )
                        )
                        Text(
                            text = "\"Woi Selz\"",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = textLight,
                                letterSpacing = 0.5.sp
                            ),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // Bento-Style Configuration Grid (2-Column row + Full Width)
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Bento Box 1: Accessibility Status
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBg),
                            border = BorderStroke(1.dp, Color(0xFF2E2E38)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onLaunchAccessibilitySettings() }
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .heightIn(min = 100.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (isAccessibilityEnabled) emeraldStatus else Color(0xFFF59E0B))
                                    )
                                    Text(
                                        text = "ACCESSIBILITY",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = textMuted,
                                            letterSpacing = 1.sp
                                        )
                                    )
                                }

                                Column {
                                    Text(
                                        text = if (isAccessibilityEnabled) "Service Active" else "Service Inactive",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = textLight
                                    )
                                    Text(
                                        text = if (isAccessibilityEnabled) "Automator engaged" else "Click to activate",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = textMuted,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }

                        // Bento Box 2: Voice Personality Status
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBg),
                            border = BorderStroke(1.dp, Color(0xFF2E2E38)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showVoiceSelectorDialog = true }
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .heightIn(min = 100.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(accentIndigo)
                                    )
                                    Text(
                                        text = "VOICE ENGINE",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = textMuted,
                                            letterSpacing = 1.sp
                                        )
                                    )
                                }

                                Column {
                                    Text(
                                        text = selectedVoice.displayName,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = textLight
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 2.dp)
                                    ) {
                                        Text(
                                            text = "Ubah",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = accentIndigo
                                        )
                                        Text(
                                            text = "•",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = textMuted
                                        )
                                        Text(
                                            text = "Uji Suara",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = neonPurple,
                                            modifier = Modifier.clickable {
                                                val serviceInstance = WakeWordService.instance
                                                if (serviceInstance != null) {
                                                    val testGreeting = when (selectedVoice.id) {
                                                        "gojo" -> "Yowai mo! Santai bro, suara gua mantap kan?"
                                                        "hutao" -> "Ayaaa! Wansheng parlor siap melayani, hehe!"
                                                        "reze" -> "Halo manis, senang bisa mendengar suaramu lagi."
                                                        "kobo" -> "Kobo di sini! Berisik banget sih, ada apa?!"
                                                        "madara" -> "Gemetarlah di hadapan dewa ninja!"
                                                        else -> "Halo, asisten suara Anda siap digunakan."
                                                    }
                                                    serviceInstance.speakOutText(testGreeting)
                                                } else {
                                                    Toast.makeText(context, "Mulai asisten terlebih dahulu untuk uji suara!", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Bento Box 3: Full Width Intelligence Hub
                    Card(
                        shape = RoundedCornerShape(26.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = BorderStroke(1.dp, accentIndigo.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "GEMINI 3.5 FLASH",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = accentIndigo,
                                        letterSpacing = 1.sp
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Semantic Engine Ready",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                    color = textLight
                                )
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textMuted,
                                    modifier = Modifier.testTag("service_status_text")
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(accentIndigo.copy(alpha = 0.15f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "98ms LATENCY",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = accentIndigo
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Realtime Voice recognition Bubble
            if (isRunning && (lastSpeech.isNotEmpty() || lastReply.isNotEmpty())) {
                item {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = BorderStroke(1.dp, Color(0xFF2E2E38)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Aktivitas Terkini",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = neonBlue
                            )
                            if (lastSpeech.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Hearing,
                                        contentDescription = "Suara Masuk",
                                        tint = neonBlue,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "\"$lastSpeech\"",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textLight
                                    )
                                }
                            }
                            if (lastReply.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ChatBubble,
                                        contentDescription = "Respons Selz",
                                        tint = neonPurple,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Selz (${selectedVoice.displayName}):",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = neonPurple
                                        )
                                        Text(
                                            text = "\"$lastReply\"",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = textLight
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Simulation Console (Awesome dark input + neon border aesthetics)
            item {
                Card(
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = BorderStroke(1.dp, Color(0xFF2E2E38)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp)
                    ) {
                        Text(
                            text = "Simulasi Perintah Teks",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = textLight
                        )
                        Text(
                            text = "Simulasikan input mic langsung ke asisten cerdas Gemini",
                            style = MaterialTheme.typography.bodySmall,
                            color = textMuted,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = testCommandText,
                            onValueChange = { testCommandText = it },
                            placeholder = { Text("Contoh: buka tiktok dan putar musik", color = textMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accentIndigo,
                                unfocusedBorderColor = Color(0xFF2E2E38),
                                focusedTextColor = textLight,
                                unfocusedTextColor = textLight,
                                focusedContainerColor = bgBlack,
                                unfocusedContainerColor = bgBlack
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("simulate_input_field"),
                            maxLines = 1,
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (testCommandText.trim().isNotEmpty()) {
                                    isSimulating = true
                                    coroutineScope.launch {
                                        val command = testCommandText
                                        testCommandText = ""
                                        val jsonResult = GeminiClient.processCommand(command)
                                        isSimulating = false
                                        if (jsonResult != null) {
                                            val action = jsonResult.optString("action", "NONE")
                                            val packageName = jsonResult.optString("package_name", "")
                                            val searchQuery = jsonResult.optString("search_query", "")
                                            val responseText = jsonResult.optString("response_text", "")

                                            var runStatus = "Simulasi Sukses"
                                            if (action == "OPEN_APP" && packageName.isNotEmpty()) {
                                                val accService = AssistantAccessibilityService.instance
                                                if (accService != null) {
                                                    accService.executeSystemAction(packageName, searchQuery)
                                                    runStatus = "Sukses Buka: $packageName"
                                                } else {
                                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                                                    if (launchIntent != null) {
                                                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        context.startActivity(launchIntent)
                                                        runStatus = "Membuka (No Acc.)"
                                                    } else {
                                                        runStatus = "Aplikasi tidak ditemukan"
                                                    }
                                                }
                                            }

                                            val manualLog = WakeWordService.CommandLog(
                                                userSpeech = "[Simulasi] $command",
                                                assistantReply = responseText,
                                                action = action,
                                                packageName = packageName,
                                                status = runStatus
                                            )
                                            WakeWordService.parsedCommandsList.value = (listOf(manualLog) + WakeWordService.parsedCommandsList.value).take(20)
                                            WakeWordService.lastAssistantResponse.value = responseText
                                            WakeWordService.lastRecognizedText.value = command

                                            val serviceInstance = WakeWordService.instance
                                            if (serviceInstance != null) {
                                                serviceInstance.speakOutText(responseText)
                                            } else {
                                                Toast.makeText(context, "Selesai! Aktifkan Asisten (klik tombol mic besar) agar suara dapat diputar.", Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "Koneksi Gemini bermasalah", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentIndigo),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isSimulating && testCommandText.trim().isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("simulate_cmd_btn")
                        ) {
                            if (isSimulating) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                Text("Kirim Perintah", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }

            // Historic Logs Section
            if (historyLogs.isNotEmpty()) {
                item {
                    Text(
                        text = "Riwayat Aktivitas",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = textLight,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(historyLogs) { log ->
                    LogItem(log, neonBlue, neonPurple)
                }
            } else {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassEmpty,
                            contentDescription = "Belum Ada Riwayat",
                            tint = textMuted,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Belum ada riwayat perintah terkini",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textMuted
                        )
                    }
                }
            }
        }

        // Deep Immersive Custom Bottom Bar overlay (as in template)
        Card(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            colors = CardDefaults.cardColors(containerColor = bgBlack),
            border = BorderStroke(1.dp, Color(0xFF2E2E38)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Dashboard action button
                IconButton(
                    onClick = { Toast.makeText(context, "Dashboard Utama", Toast.LENGTH_SHORT).show() },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Dashboard,
                            contentDescription = "Menu Utama",
                            tint = neonBlue,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Home",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = neonBlue
                        )
                    }
                }

                // Core Floating Action Launcher overlay (translates -y-6)
                Box(
                    modifier = Modifier
                        .offset(y = (-18).dp)
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(colors = listOf(accentIndigo, neonPurple))
                        )
                        .clickable(onClick = onToggleService)
                        .testTag("toggle_voice_service_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Toggle Service",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Settings accessibility triggers
                IconButton(
                    onClick = onLaunchAccessibilitySettings,
                    modifier = Modifier
                        .testTag("request_accessibility_btn")
                        .minimumInteractiveComponentSize()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Sistem Settings",
                            tint = textMuted,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.labelSmall,
                            color = textMuted
                        )
                    }
                }
            }
        }

        if (showVoiceSelectorDialog) {
            Dialog(onDismissRequest = { showVoiceSelectorDialog = false }) {
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = BorderStroke(1.dp, accentIndigo.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Pilih Karakter Voice",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = textLight
                            )
                        )
                        Text(
                            text = "Gemini AI & RVC Suara akan berubah meniru model kepribadian dan suara karakter anime tersebut.",
                            style = MaterialTheme.typography.bodySmall,
                            color = textMuted
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            VoiceProfile.values().forEach { profile ->
                                val isSelected = selectedVoice == profile
                                val borderColored = if (isSelected) accentIndigo else Color(0xFF2E2E38)
                                val bgColored = if (isSelected) accentIndigo.copy(alpha = 0.12f) else bgBlack

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(bgColored)
                                        .border(BorderStroke(1.dp, borderColored), RoundedCornerShape(16.dp))
                                        .clickable {
                                            WakeWordService.selectedVoice.value = profile
                                            showVoiceSelectorDialog = false
                                            Toast.makeText(context, "${profile.displayName} Terpilih!", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = profile.displayName,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = if (isSelected) accentIndigo else textLight
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = profile.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = textMuted
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Terpilih",
                                            tint = accentIndigo,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { showVoiceSelectorDialog = false },
                                colors = ButtonDefaults.textButtonColors(contentColor = accentIndigo)
                            ) {
                                Text("Batal")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogItem(
    log: WakeWordService.CommandLog,
    blueColor: Color,
    purpleColor: Color
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16161D)),
        border = BorderStroke(1.dp, Color(0xFF2E2E38)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = log.userSpeech,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = log.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (log.status.contains("Gagal") || log.status.contains("Error")) Color(0xFFEF4444) else blueColor
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Tutup Detail" else "Buka Detail",
                    tint = Color.LightGray
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF2E2E38))
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Jawaban Karakter/Vokal:",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = purpleColor
                )
                Text(
                    text = log.assistantReply,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray,
                    modifier = Modifier.padding(vertical = 2.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column {
                        Text("Action", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(
                            log.action,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = Color.White
                        )
                    }
                    if (log.packageName.isNotEmpty()) {
                        Column {
                            Text("Package Target", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text(
                                log.packageName,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}
