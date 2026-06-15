package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

enum class VoiceProfile(val id: String, val displayName: String, val rvcCode: String, val description: String) {
    GOJO("gojo", "Gojo Satoru", "GojoSatoru", "Sombong, santai, jenaka. Yowai mo!"),
    HUTAO("hutao", "Hu Tao", "HuTao", "Ceria, usil, wangsheng parlor, Ayaaa~"),
    REZE("reze", "Reze", "Reze", "Manis, flirty, tapi agak berbahaya."),
    KOBO("kobo", "Kobo Kanaeru", "KoboKanaeru", "Bocah hujan VTuber berisik & manja."),
    MADARA("madara", "Uchiha Madara", "MadaraUchiha", "Sangat dingin, berwibawa, Susano'o.")
}

class WakeWordService : Service(), TextToSpeech.OnInitListener {

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechRecognizerIntent: Intent? = null
    private var textToSpeech: TextToSpeech? = null
    private var isListening = false
    private var mediaPlayer: android.media.MediaPlayer? = null

    companion object {
        var instance: WakeWordService? = null
        
        // State flow for UI monitoring
        val isServiceRunning = MutableStateFlow(false)
        val assistantStatus = MutableStateFlow("Standby (Mendengarkan...)")
        val parsedCommandsList = MutableStateFlow<List<CommandLog>>(emptyList())
        val lastRecognizedText = MutableStateFlow("")
        val lastAssistantResponse = MutableStateFlow("")
        val selectedVoice = MutableStateFlow(VoiceProfile.GOJO)
    }

    data class CommandLog(
        val timestamp: Long = System.currentTimeMillis(),
        val userSpeech: String,
        val assistantReply: String,
        val action: String,
        val packageName: String,
        val status: String
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceRunning.value = true
        
        // Initialize TextToSpeech
        textToSpeech = TextToSpeech(this, this)
        
        startForegroundService()
        initSpeechRecognizer()
    }

    private fun startForegroundService() {
        val channelId = "selz_assistant_channel"
        val channel = NotificationChannel(
            channelId, 
            "Selz Assistant Running", 
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Selz AI Aktif")
            .setContentText("Mendengarkan suara Anda...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun initSpeechRecognizer() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            assistantStatus.value = "Aktivasi Gagal: Izin Mikrofon Belum Diberikan"
            isServiceRunning.value = false
            stopSelf()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            assistantStatus.value = "Pengenal suara tidak didukung di perangkat ini"
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            e.printStackTrace()
            assistantStatus.value = "Inisialisasi Mic gagal: " + e.localizedMessage
            isServiceRunning.value = false
            stopSelf()
            return
        }

        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID") // Support Indonesian language
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                assistantStatus.value = "Mendengarkan..."
            }

            override fun onBeginningOfSpeech() {
                assistantStatus.value = "Mendeteksi ucapan..."
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                assistantStatus.value = "Memproses ucapan..."
            }

            override fun onError(error: Int) {
                isListening = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Error Audio"
                    SpeechRecognizer.ERROR_CLIENT -> "Error Client"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin Kurang"
                    SpeechRecognizer.ERROR_NETWORK -> "Error Jaringan"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Jaringan Timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "Tidak ada kecocokan"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mic Sibuk"
                    SpeechRecognizer.ERROR_SERVER -> "Error Server"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tidak ada suara"
                    else -> "Error tidak diketahui"
                }
                
                assistantStatus.value = "Standby (Retry: $errorMsg)"
                
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    assistantStatus.value = "Aktivasi Gagal: Izin Mikrofon Kurang"
                    isServiceRunning.value = false
                    stopSelf()
                } else {
                    // Automatically restart speech recognizer listening
                    restartListening()
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val recognizedText = matches[0]
                    lastRecognizedText.value = recognizedText
                    assistantStatus.value = "Terbaca: \"$recognizedText\""
                    handleSpeechToText(recognizedText)
                } else {
                    restartListening()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    lastRecognizedText.value = matches[0]
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startListening()
    }

    fun startListening() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            assistantStatus.value = "Aktivasi Gagal: Izin Mikrofon Kurang"
            isServiceRunning.value = false
            stopSelf()
            return
        }
        if (!isListening) {
            try {
                speechRecognizer?.startListening(speechRecognizerIntent)
            } catch (e: Exception) {
                e.printStackTrace()
                assistantStatus.value = "Gagal memulai mic"
            }
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
    }

    private fun restartListening() {
        serviceScope.launch {
            kotlinx.coroutines.delay(1000)
            if (isServiceRunning.value) {
                startListening()
            }
        }
    }

    private fun handleSpeechToText(recognizedText: String) {
        serviceScope.launch {
            assistantStatus.value = "Meminta instruksi Gemini..."
            val jsonResult = GeminiClient.processCommand(recognizedText)
            if (jsonResult != null) {
                val action = jsonResult.optString("action", "NONE")
                val packageName = jsonResult.optString("package_name", "")
                val searchQuery = jsonResult.optString("search_query", "")
                val responseText = jsonResult.optString("response_text", "")

                lastAssistantResponse.value = responseText

                var statusInfo = "Dibalas"
                // 1. Execute Device Control
                if (action == "OPEN_APP" && packageName.isNotEmpty()) {
                    val accService = AssistantAccessibilityService.instance
                    if (accService != null) {
                        accService.executeSystemAction(packageName, searchQuery)
                        statusInfo = "Buka Aplikasi: $packageName"
                    } else {
                        // In case Accessibility Service is not enabled, try opening normally via context
                        statusInfo = "Membuka aplikasi (Normal)..."
                        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launchIntent)
                        } else {
                            statusInfo = "Gagal: App tidak terpasang"
                        }
                    }
                }

                // Append to logs
                val newLog = CommandLog(
                    userSpeech = recognizedText,
                    assistantReply = responseText,
                    action = action,
                    packageName = packageName,
                    status = statusInfo
                )
                parsedCommandsList.value = (listOf(newLog) + parsedCommandsList.value).take(20)

                // Play vocal response using RVC backend (falls back to TTS automatically if failing)
                speakOutText(responseText)
            } else {
                assistantStatus.value = "Gemini tidak memberikan response"
                val errorLog = CommandLog(
                    userSpeech = recognizedText,
                    assistantReply = "Gagal memproses data",
                    action = "NONE",
                    packageName = "",
                    status = "Error Gemini"
                )
                parsedCommandsList.value = (listOf(errorLog) + parsedCommandsList.value).take(20)
                restartListening()
            }
        }
    }

    fun speakOutText(text: String) {
        serviceScope.launch(Dispatchers.Main) {
            try {
                stopListening()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                // Pre-emptively stop and release any existing media player to prevent overlapping audio
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        it.stop()
                    }
                    it.release()
                }
                mediaPlayer = null
                
                // Pre-emptively stop any TextToSpeech to prevent overlapping audio
                textToSpeech?.stop()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            sendToRvcServer(text, selectedVoice.value.rvcCode)
        }
    }

    private fun speakOut(text: String) {
        if (text.isNotEmpty() && textToSpeech != null) {
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                
                // Ensure ringer mode is Normal so vocal output is permitted
                try {
                    if (audioManager.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) {
                        audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                    }
                } catch (re: Exception) {
                    re.printStackTrace()
                }

                val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val currentVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                // Boost volume to at least 75% if currently too low (showing system UI for confirmation)
                if (currentVol < (maxVol * 0.6f).toInt()) {
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (maxVol * 0.75f).toInt(), android.media.AudioManager.FLAG_SHOW_UI)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Direct output to the standard STREAM_MUSIC channel
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
            }
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "SelzResponseID")
        } else {
            restartListening()
        }
    }

    private fun fallbackToTts(text: String) {
        assistantStatus.value = "Koneksi RVC gagal. Mencoba suara alternatif..."
        val client = OkHttpClient()
        val url = "https://translate.google.com/translate_tts?ie=UTF-8&tl=id&client=tw-ob&q=${UriHelper.encode(text)}"
        
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                // Ultimate fallback: System local TTS speak out
                serviceScope.launch(Dispatchers.Main) {
                    speakOut(text)
                }
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val contentType = response.header("Content-Type") ?: ""
                    val responseBytes = response.body?.bytes()
                    // Make sure response is actually a sound file stream rather than a text/json/html error page 
                    if (responseBytes != null && responseBytes.isNotEmpty() && !contentType.contains("json") && !contentType.contains("text/html")) {
                        playRvcAudio(responseBytes)
                    } else {
                        response.close()
                        serviceScope.launch(Dispatchers.Main) {
                            speakOut(text)
                        }
                    }
                } else {
                    response.close()
                    serviceScope.launch(Dispatchers.Main) {
                        speakOut(text)
                    }
                }
            }
        })
    }

    private fun playRvcAudio(responseBytes: ByteArray) {
        serviceScope.launch(Dispatchers.Main) {
            try {
                // Ensure any previous MediaPlayer is stopped and fully released
                mediaPlayer?.let {
                    try {
                        if (it.isPlaying) {
                            it.stop()
                        }
                    } catch (ignore: Exception) {}
                    it.release()
                }
                mediaPlayer = null

                val tempFile = java.io.File.createTempFile("rvc_voice_", ".mp3", cacheDir)
                tempFile.deleteOnExit()
                
                java.io.FileOutputStream(tempFile).use { fos ->
                    fos.write(responseBytes)
                }

                mediaPlayer = android.media.MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    
                    // Set Audio Attributes to USAGE_MEDIA + CONTENT_TYPE_MUSIC for loud standard media playback
                    val audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    setAudioAttributes(audioAttributes)

                    setOnPreparedListener { mp ->
                        try {
                            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                            
                            // Safe Normal mode unmuting
                            try {
                                if (audioManager.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) {
                                    audioManager.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                                }
                            } catch (ignore: Exception) {}

                            val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            val currentVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                            // Boost volume to at least 75% for audible speech output
                            if (currentVol < (maxVol * 0.6f).toInt()) {
                                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (maxVol * 0.75f).toInt(), android.media.AudioManager.FLAG_SHOW_UI)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        assistantStatus.value = "Berbicara (${selectedVoice.value.displayName})..."
                        mp.start()
                    }
                    setOnCompletionListener { mp ->
                        mp.release()
                        mediaPlayer = null
                        assistantStatus.value = "Standby (Mendengarkan...)"
                        restartListening()
                    }
                    setOnErrorListener { mp, what, extra ->
                        mp.release()
                        mediaPlayer = null
                        // Fallback to local TTS on playback errors
                        fallbackToTts(lastAssistantResponse.value)
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                fallbackToTts(lastAssistantResponse.value)
            }
        }
    }

    private fun sendToRvcServer(text: String, characterVoice: String) {
        assistantStatus.value = "Mensintesis suara..."
        val client = OkHttpClient()
        val url = "https://api.rvc-server-lu.com/v1/tts-rvc?text=${UriHelper.encode(text)}&voice=$characterVoice" 

        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { 
                e.printStackTrace() 
                // Fallback to Google Translate TTS or System Local TTS
                fallbackToTts(text)
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val contentType = response.header("Content-Type") ?: ""
                    val responseBytes = response.body?.bytes()
                    // Make sure response contains real voice data instead of json or html errors
                    if (responseBytes != null && responseBytes.isNotEmpty() && !contentType.contains("json") && !contentType.contains("text/html")) {
                        playRvcAudio(responseBytes)
                    } else {
                        response.close()
                        fallbackToTts(text)
                    }
                } else {
                    response.close()
                    fallbackToTts(text)
                }
            }
        })
    }


    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.forLanguageTag("id-ID"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                textToSpeech?.setLanguage(Locale.US)
            }
            
            textToSpeech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    serviceScope.launch(Dispatchers.Main) {
                        assistantStatus.value = "Berbicara (TTS)..."
                    }
                }

                override fun onDone(utteranceId: String?) {
                    serviceScope.launch(Dispatchers.Main) {
                        assistantStatus.value = "Standby (Mendengarkan...)"
                        restartListening()
                    }
                }

                override fun onError(utteranceId: String?) {
                    serviceScope.launch(Dispatchers.Main) {
                        assistantStatus.value = "Standby (Mendengarkan...)"
                        restartListening()
                    }
                }
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning.value = false
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaPlayer = null
        
        instance = null
        super.onDestroy()
    }
}

object UriHelper {
    fun encode(s: String): String {
        return java.net.URLEncoder.encode(s, "UTF-8")
    }
}
