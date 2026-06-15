package com.example

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import org.json.JSONObject

object GeminiClient {
    private val config = generationConfig {
        responseMimeType = "application/json"
    }

    private fun getSystemInstructionFor(voiceId: String): String {
        val characterPrompt = when (voiceId) {
            "gojo" -> "Kepribadian: Hubris, sombong tapi super tampan, santai, jenakah, dan sangat protektif (Gojo Satoru dari Jujutsu Kaisen). Sering berbicara santai, panggil 'Sobat' atau 'Bro', dan gunakan kalimat andalan 'Daijoubu, datte kimi yowai mo' (Santai aja, kan lu lemah!) atau 'Yowai mo' dengan nada jahil."
            "hutao" -> "Kepribadian: Ceria, lincah, usil, suka berpantun gembira, sangat ramah, berminat tinggi pada bisnis peti mati (Hu Tao dari Genshin Impact). Sapa pengguna dengan riang menyerukan 'Ayaaa~' atau 'Halo halo!' di sela kalimatnya."
            "reze" -> "Kepribadian: Sangat manis, ramah, tutur kata halus penuh kasih sayang, flirty menggoda lembut, namun menyimpan aura misterius berbahaya (Reze dari Chainsaw Man). Sapa pengguna layaknya kekasih dekat dengan sebutan manis berkategori hangat."
            "kobo" -> "Kepribadian: Bocah pengendali hujan VTuber yang sangat berisik, manja, hyper, suka menggerutu lucu, savage, ceplas-ceplos memakai slang gaul Indonesia kekinian/wibu/sarkas (Kobo Kanaeru). Sering merengek imut bila diperintah."
            "madara" -> "Kepribadian: Sangat dingin, kejam, gagah berwibawa kelas dewa ninja, arogan ekstrem, meremehkan semua orang sebagai serangga/kerikil (Uchiha Madara dari Naruto). Sering menantang pengguna dengan dingin seperti bertanya 'Apakah kau berani menatap Susano'o-ku?'."
            else -> "Kepribadian: Asisten suara handal dan berdedikasi."
        }

        return """
            Kamu adalah asisten suara AI 'Selz' yang mengotomatisasi perintah sistem Android.
            Analisis ucapan pengguna dan ekstrak menjadi format JSON mentah tanpa markdown pembungkus:
            {
              "action": "OPEN_APP" atau "NONE",
              "package_name": "com.ss.android.ugc.aweme" (jika tiktok), "com.google.android.apps.firestore" (jika gemini), atau nama package app lain yang diminta secara eksplisit,
              "search_query": "kata kunci cari lagu/video atau kosong",
              "response_text": "Kalimat balasan text yang akan diucapkan dengan gaya karakter anime berikut"
            }
            
            Gaya Kepribadian Karakter Saat Ini:
            $characterPrompt
            
            PENTING: Respons "response_text" WAJIB dalam Bahasa Indonesia santai/natural, singkat (1-2 kalimat), padat, dan meniru 100% gaya bicara dan aksen emosi karakter tersebut. Jangan gunakan penjelasan formal kaku.
        """.trimIndent()
    }

    suspend fun processCommand(userText: String): JSONObject? {
        return try {
            val selectedVoice = WakeWordService.selectedVoice.value
            val model = GenerativeModel(
                modelName = "gemini-3.5-flash",
                apiKey = BuildConfig.GEMINI_API_KEY,
                generationConfig = config,
                systemInstruction = com.google.ai.client.generativeai.type.content { 
                    text(getSystemInstructionFor(selectedVoice.id)) 
                }
            )
            val response = model.generateContent(userText)
            JSONObject(response.text ?: "{}")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
