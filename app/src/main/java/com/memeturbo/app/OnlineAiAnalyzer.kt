package com.memeturbo.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * V14.0 Çoklu Ajan (Multi-Agent) Çevrimiçi Yapay Zeka Analiz Motoru:
 * - Fotoğraflarda: Yapı, eşya, yiyecek, doğa (gün batımı, çiçek), video oyunu (Bannerlord, LoL, Minecraft vb.)
 *   ve kullanıcının tanıttığı referans kişi fotoğrafı ("Mert / Ben" veya arkadaş) eşleştirmesi!
 * - Videolarda: ADTS AAC ses + GPT-OSS 20B Mantık Düzeltme Ajanı + Qwen 3.8 27B Vision.
 */
object OnlineAiAnalyzer {

    private val encodedGroqKey = intArrayOf(
        61, 79, 26, 70, 199, 38, 84, 63, 53, 117, 52, 47, 215, 57, 99, 27,
        45, 5, 0, 40, 253, 14, 17, 10, 13, 123, 21, 96, 236, 112, 97, 52,
        8, 112, 23, 75, 214, 8, 67, 89, 11, 74, 38, 83, 221, 51, 110, 8,
        52, 86, 62, 85, 246, 37, 87, 36
    )
    private val xorMask = intArrayOf(0x5A, 0x3C, 0x71, 0x19, 0x8E, 0x43, 0x27, 0x6D)

    @Volatile
    private var cachedVisionModels: List<String>? = null

    fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
        val custom = prefs.getString("custom_groq_key", null)?.trim()
        if (!custom.isNullOrEmpty() && custom.length > 20) return custom

        val sb = StringBuilder(encodedGroqKey.size)
        for (i in encodedGroqKey.indices) {
            sb.append((encodedGroqKey[i] xor xorMask[i % xorMask.size]).toChar())
        }
        return sb.toString()
    }

    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    data class AiMediaResult(
        val spokenText: String,
        val screenText: String,
        val visualLabels: String,
        val durationMs: Long
    )

    private val whisperHallucinations = listOf(
        "altyazı m.k", "altyazi m.k", "abone olmayı unutmayın", "izlediğiniz için teşekkürler",
        "teşekkür ederim", "kanalımıza abone", "beğenmeyi unutmayın", "www.", ".com",
        "amara.org", "türkçe altyazı", "alttaki zil", "müzik çalıyor", "alkışlar",
        "şarkı sözleri, rap", "türkçe video repliği", "rap ve diyalog", "rap diyalog"
    )

    private val musicOnlyOutputs = setOf(
        "müzik", "muzik", "music", "[müzik]", "(müzik)", "♪", "♫",
        "şarkı", "sarki", "müzik sesi", "fon müziği", "altyazı", "altyazi",
        "video", "görsel", "gorsel", "diyalog"
    )

    fun isMusicOrEmptyTranscript(raw: String): Boolean {
        val clean = raw.replace("\u200B", "")
            .trim()
            .lowercase()
            .trim('.', ',', '!', '?', '-', '_', '[', ']', '(', ')', '♪', '♫', ' ')
        if (clean.isBlank()) return true
        if (clean in musicOnlyOutputs) return true
        if (clean.contains("şarkı sözleri") && clean.contains("diyalog")) return true
        return false
    }

    private fun cleanWhisperText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        if (isMusicOrEmptyTranscript(trimmed)) return ""
        val lower = trimmed.lowercase()
        for (h in whisperHallucinations) {
            if (lower.contains(h) && trimmed.length < 60) return ""
        }
        return VideoIndexer.autoCorrectTurkishReplik(trimmed)
    }

    suspend fun analyzeVideoWithAi(
        context: Context,
        videoFile: File,
        indexer: VideoIndexer,
        localOcrHint: String = "",
        forceVision: Boolean = false
    ): AiMediaResult? = withContext(Dispatchers.IO) {
        if (!isOnline(context)) return@withContext null
        val apiKey = getApiKey(context)

        var whisperTranscript = ""
        val aacBytes = indexer.extractCompressedAacForAi(videoFile, maxSeconds = 12)
        if (aacBytes.size > 1200) {
            whisperTranscript = callGroqWhisper(apiKey, aacBytes, "audio.m4a", "audio/mp4")
        } else {
            val pcm16k = indexer.extractPcm16kMonoForAi(videoFile, maxSeconds = 12)
            if (pcm16k.size > 6400) {
                val wavBytes = pcm16kToWavBytes(pcm16k, 16000)
                whisperTranscript = callGroqWhisper(apiKey, wavBytes, "audio.wav", "audio/wav")
            }
        }

        if (whisperTranscript.isNotBlank() || localOcrHint.isNotBlank()) {
            val refined = refineWithTextLogicAgent(apiKey, whisperTranscript, localOcrHint)
            if (refined != null) {
                val rSpoken = refined.first
                val rScreen = VideoIndexer.filterWatermarks(refined.second)
                if (rSpoken.isNotBlank() || rScreen.isNotBlank()) {
                    whisperTranscript = rSpoken
                    if (!forceVision && (rSpoken.isNotBlank() && !VideoIndexer.isWeakSpokenFiller(rSpoken))) {
                        return@withContext AiMediaResult(
                            spokenText = rSpoken,
                            screenText = rScreen.ifBlank { localOcrHint },
                            visualLabels = "",
                            durationMs = 0L
                        )
                    }
                }
            }
        }

        val hasStrongSpeech = whisperTranscript.isNotBlank() &&
            !isMusicOrEmptyTranscript(whisperTranscript) &&
            !VideoIndexer.isWeakSpokenFiller(whisperTranscript)

        val (durationMs, collageBase64) = buildVideoFourFrameCollageBase64(videoFile)
        var visionSpoken = ""
        var visionSubtitle = ""
        var visionScene = ""

        if (collageBase64.isNotEmpty() && (forceVision || !hasStrongSpeech)) {
            val prompt = buildString {
                append("Bu görsel bir Türk meme veya müzik videosunun 4 farklı saniyesinden alınmış 2x2 kare kolajıdır.\n")
                if (whisperTranscript.isNotBlank()) {
                    append("Ses Ajanının duyduğu sözler: \"$whisperTranscript\"\n")
                }
                if (localOcrHint.isNotBlank()) {
                    append("Ön OCR metni: \"$localOcrHint\"\n")
                }
                append("Görevlerin:\n")
                append("1. Karelerin üzerindeki anlamlı Türkçe altyazıları veya ekran yazılarını (Instagram/TikTok kullanıcı adı, '@hesap', TV logosu HARİÇ) tam Türkçe cümle olarak 'subtitle' alanına yaz.\n")
                append("2. Ses Ajanının duyduğu sözlerde yanlış/anlamsız kelime varsa altyazıya ve Türkçe mantığa göre düzeltip 'spoken' alanına yaz (sadece müzik/sessizlik varsa boş bırak).\n")
                append("3. Görseldeki sahneyi, ünlü kişiyi, video oyununu veya olayı Türkçe 3-6 kelimeyle 'scene' alanına yaz.\n")
                append("SADECE geçerli bir JSON döndür:\n")
                append("{\"spoken\":\"...\",\"subtitle\":\"...\",\"scene\":\"...\"}")
            }
            val parsed = callGroqVisionJson(apiKey, listOf(collageBase64), prompt)
            if (parsed != null) {
                visionSpoken = parsed.optString("spoken", "").trim()
                visionSubtitle = VideoIndexer.filterWatermarks(parsed.optString("subtitle", "").trim())
                visionScene = parsed.optString("scene", "").trim()
            }
        }

        val finalSpoken = when {
            visionSpoken.isNotBlank() && !isMusicOrEmptyTranscript(visionSpoken) && !VideoIndexer.isWeakSpokenFiller(visionSpoken) ->
                VideoIndexer.autoCorrectTurkishReplik(visionSpoken)
            hasStrongSpeech -> whisperTranscript
            else -> ""
        }

        val finalScreen = if (visionSubtitle.isNotBlank()) visionSubtitle else localOcrHint
        val finalScene = if (visionScene.isNotBlank()) "🏷️ $visionScene" else ""

        if (finalSpoken.isBlank() && finalScreen.isBlank() && finalScene.isBlank()) {
            return@withContext null
        }

        AiMediaResult(
            spokenText = finalSpoken,
            screenText = finalScreen,
            visualLabels = finalScene,
            durationMs = durationMs
        )
    }

    suspend fun refineWithTextLogicAgent(
        apiKey: String,
        rawSpoken: String,
        rawScreen: String
    ): Pair<String, String>? {
        if (rawSpoken.isBlank() && rawScreen.isBlank()) return null
        val prompt = buildString {
            append("Sen Türkçe meme/video metin düzeltme uzmanısın. Aşağıdaki ses ve ekran yazılarındaki:\n")
            append("- Fonetik/OCR yazım hatalarını Türkçe mantığa göre düzelt (ör: 'tahvimler'->'takvimler', 'burda'->'burada').\n")
            append("- Anlamsız harf yığınlarını ('OlmikzSzesler', 'GOKSJXKCJ', 'Braten', 'Therapy richest') ve Instagram/TikTok sayfa/hesap adlarını ('BDAMLADILA', '6GALERIKKOO', 'KRALINYGARŞIV', '@hesap') TAMAMEN SİL.\n")
            append("- Cümle mantıklıysa anlamını bozma, sadece hatalı kelimeleri düzelt.\n")
            append("Ses: \"$rawSpoken\"\n")
            append("Ekran: \"$rawScreen\"\n")
            append("SADECE JSON döndür: {\"spoken\":\"...\",\"screen\":\"...\"}")
        }

        var conn: HttpURLConnection? = null
        try {
            val url = URL("https://api.groq.com/openai/v1/chat/completions")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 12000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            val bodyJson = JSONObject().apply {
                put("model", "openai/gpt-oss-20b")
                put("temperature", 0.0)
                put("max_completion_tokens", 180)
                put("response_format", JSONObject().apply { put("type", "json_object") })
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            conn.outputStream.use { os ->
                os.write(bodyJson.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val content = JSONObject(resp).optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "") ?: ""
                val parsed = extractJsonFromText(content)
                if (parsed != null) {
                    val s = cleanWhisperText(parsed.optString("spoken", ""))
                    val sc = VideoIndexer.filterWatermarks(parsed.optString("screen", ""))
                    return Pair(s, sc)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
        return null
    }

    /**
     * V14.0 Gelişmiş Fotoğraf & Kişi Tanıma Motoru:
     * - Kullanıcının tanıttığı referans kişi ("Mert / Ben") fotoğrafı varsa 2. görsel olarak ekler
     *   ve "Mert ve arkadaşları..." şeklinde etiketler.
     * - Yapı (cami, yurt, konferans salonu), yiyecek, doğa (gün batımı, çiçek), eşya ve video oyunu
     *   (Bannerlord, LoL, Minecraft vb.) tespiti yapar.
     */
    suspend fun analyzeImageWithAi(
        context: Context,
        imageFile: File,
        localOcrHint: String = ""
    ): AiMediaResult? = withContext(Dispatchers.IO) {
        if (!isOnline(context)) return@withContext null
        val apiKey = getApiKey(context)

        val targetBase64 = encodeImageFileToBase64Jpeg(imageFile, maxDim = 640)
        if (targetBase64.isEmpty()) return@withContext null

        val prefs = context.getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
        val selfName = prefs.getString("self_person_name", "")?.trim() ?: ""
        val selfPhotoPath = prefs.getString("self_person_photo", "")?.trim() ?: ""
        var refBase64 = ""
        if (selfName.isNotBlank() && selfPhotoPath.isNotBlank()) {
            val refFile = File(selfPhotoPath)
            if (refFile.exists() && refFile.absolutePath != imageFile.absolutePath) {
                refBase64 = encodeImageFileToBase64Jpeg(refFile, maxDim = 420)
            }
        }

        val imagesList = if (refBase64.isNotEmpty()) listOf(targetBase64, refBase64) else listOf(targetBase64)

        val prompt = buildString {
            if (refBase64.isNotEmpty()) {
                append("1. görsel analiz edilecek ana fotoğraftır. 2. görsel ise '$selfName' (Ben) adlı kişinin referans fotoğrafıdır.\n")
            } else {
                append("Bu fotoğrafı Türkçe galeri araması için detaylı analiz et:\n")
            }
            if (localOcrHint.isNotBlank()) {
                append("Görseldeki olası yazı ipucu: \"$localOcrHint\"\n")
            }
            append("1. Ana fotoğrafta anlamlı yazı, mesajlaşma, tweet, başlık veya tahta yazısı varsa düzgün Türkçe metnini 'subtitle' alanına yaz (filigran/sayfa adı hariç; yoksa boş bırak).\n")
            append("2. Ana fotoğraftaki ortamı, yapıyı (cami, tiyatro salonu, park, yurt odası), nesneyi, yiyeceği, doğayı (gün batımı, çiçek, deniz), video oyununu (Bannerlord, LoL, Minecraft, GTA, ETS2 vb.) ve kişileri 4-9 Türkçe kelimeyle 'scene' alanına yaz.\n")
            if (refBase64.isNotEmpty()) {
                append("3. ÖNEMLİ: 2. görseldeki '$selfName' adlı kişi 1. görselde de görünüyorsa açıklamaya mutlaka '$selfName' (ör: '$selfName ve arkadaşları parkta', '$selfName özçekimi') adını ekle!\n")
            }
            append("SADECE geçerli bir JSON döndür:\n")
            append("{\"subtitle\":\"...\",\"scene\":\"...\"}")
        }

        val parsed = callGroqVisionJson(apiKey, imagesList, prompt) ?: return@withContext null
        val aiSubtitle = VideoIndexer.filterWatermarks(parsed.optString("subtitle", "").trim())
        val scene = parsed.optString("scene", "").trim()

        val finalSubtitle = if (aiSubtitle.isNotBlank()) aiSubtitle else localOcrHint
        if (finalSubtitle.isBlank() && scene.isBlank()) return@withContext null

        AiMediaResult(
            spokenText = "",
            screenText = finalSubtitle,
            visualLabels = if (scene.isNotBlank()) "🖼️ $scene" else "",
            durationMs = 0L
        )
    }

    private suspend fun callGroqWhisper(
        apiKey: String,
        audioBytes: ByteArray,
        fileName: String,
        mimeType: String
    ): String {
        val boundary = "----MemeTurboBoundary" + System.currentTimeMillis()
        for (attempt in 1..3) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://api.groq.com/openai/v1/audio/transcriptions")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 25000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }

                DataOutputStream(conn.outputStream).use { out ->
                    fun writeField(name: String, value: String) {
                        out.writeBytes("--$boundary\r\n")
                        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                        out.write(value.toByteArray(Charsets.UTF_8))
                        out.writeBytes("\r\n")
                    }

                    writeField("model", "whisper-large-v3-turbo")
                    writeField("language", "tr")
                    writeField("response_format", "verbose_json")
                    writeField("temperature", "0.0")

                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
                    out.writeBytes("Content-Type: $mimeType\r\n\r\n")
                    out.write(audioBytes)
                    out.writeBytes("\r\n")
                    out.writeBytes("--$boundary--\r\n")
                    out.flush()
                }

                val code = conn.responseCode
                if (code == 200) {
                    val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val json = JSONObject(resp)

                    val segments = json.optJSONArray("segments")
                    if (segments != null && segments.length() > 0) {
                        val validParts = mutableListOf<String>()
                        for (i in 0 until segments.length()) {
                            val seg = segments.optJSONObject(i) ?: continue
                            val noSpeechProb = seg.optDouble("no_speech_prob", 0.0)
                            val segText = seg.optString("text", "").trim()
                            if (noSpeechProb > 0.65 && segText.length < 22) continue
                            if (!isMusicOrEmptyTranscript(segText)) {
                                validParts.add(segText)
                            }
                        }
                        val joined = validParts.joinToString(" ").trim()
                        return cleanWhisperText(joined)
                    }

                    return cleanWhisperText(json.optString("text", ""))
                } else if (code == 429) {
                    val retrySec = conn.getHeaderField("retry-after")?.toLongOrNull() ?: (3L * attempt)
                    delay((retrySec.coerceIn(2L, 12L)) * 1000L)
                } else {
                    return ""
                }
            } catch (_: Exception) {
                if (attempt == 3) return ""
                delay(1000L)
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        return ""
    }

    private fun resolveActiveVisionModels(apiKey: String): List<String> {
        cachedVisionModels?.let { if (it.isNotEmpty()) return it }

        val defaultModels = mutableListOf(
            "qwen/qwen3.8-27b",
            "meta-llama/llama-4-scout-17b-16e-instruct",
            "meta-llama/llama-4-maverick-17b-128e-instruct"
        )
        var conn: HttpURLConnection? = null
        try {
            val url = URL("https://api.groq.com/openai/v1/models")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val data = JSONObject(resp).optJSONArray("data")
                if (data != null) {
                    val availableIds = mutableSetOf<String>()
                    for (i in 0 until data.length()) {
                        val id = data.optJSONObject(i)?.optString("id", "") ?: ""
                        if (id.isNotBlank()) availableIds.add(id)
                    }
                    val prioritized = mutableListOf<String>()
                    for (candidate in defaultModels) {
                        if (candidate in availableIds) prioritized.add(candidate)
                    }
                    for (id in availableIds) {
                        val low = id.lowercase()
                        if ((low.contains("qwen") || low.contains("vision") || low.contains("scout") || low.contains("maverick") || low.contains("muse")) && id !in prioritized) {
                            prioritized.add(id)
                        }
                    }
                    if (prioritized.isNotEmpty()) {
                        cachedVisionModels = prioritized
                        return prioritized
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
        cachedVisionModels = defaultModels
        return defaultModels
    }

    private suspend fun callGroqVisionJson(apiKey: String, base64JpegList: List<String>, prompt: String): JSONObject? {
        val models = resolveActiveVisionModels(apiKey)
        for (modelName in models) {
            for (attempt in 1..3) {
                var conn: HttpURLConnection? = null
                try {
                    val url = URL("https://api.groq.com/openai/v1/chat/completions")
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("Authorization", "Bearer $apiKey")
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    }

                    val contentArr = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        for (b64 in base64JpegList.take(3)) {
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$b64")
                                })
                            })
                        }
                    }

                    val bodyJson = JSONObject().apply {
                        put("model", modelName)
                        put("temperature", 0.1)
                        put("max_completion_tokens", 280)
                        put("response_format", JSONObject().apply {
                            put("type", "json_object")
                        })
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", contentArr)
                            })
                        })
                    }

                    conn.outputStream.use { os ->
                        os.write(bodyJson.toString().toByteArray(Charsets.UTF_8))
                    }

                    val code = conn.responseCode
                    if (code == 200) {
                        val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val root = JSONObject(resp)
                        val content = root.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.optString("content", "") ?: ""
                        val parsed = extractJsonFromText(content)
                        if (parsed != null) return parsed
                        break
                    } else if (code == 429) {
                        val retrySec = conn.getHeaderField("retry-after")?.toLongOrNull() ?: (12L * attempt)
                        delay((retrySec.coerceIn(4L, 35L)) * 1000L)
                    } else {
                        break
                    }
                } catch (_: Exception) {
                    delay(1000L)
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
            }
        }
        return null
    }

    private fun extractJsonFromText(text: String): JSONObject? {
        val withoutThink = text.replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "").trim()
        val start = withoutThink.indexOf('{')
        val end = withoutThink.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return try {
                JSONObject(withoutThink.substring(start, end + 1))
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun buildVideoFourFrameCollageBase64(videoFile: File): Pair<Long, String> {
        val retriever = MediaMetadataRetriever()
        var durationMs = 0L
        val frames = mutableListOf<Bitmap>()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val effectiveDurUs = ((durationMs.coerceIn(2000L, 14000L)) * 1000L)
            val timesUs = listOf(
                (effectiveDurUs * 0.14).toLong(),
                (effectiveDurUs * 0.38).toLong(),
                (effectiveDurUs * 0.64).toLong(),
                (effectiveDurUs * 0.86).toLong()
            )
            for (t in timesUs) {
                val bmp = retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) frames.add(bmp)
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        if (frames.isEmpty()) return Pair(durationMs, "")

        val cellW = 280
        val cellH = 280
        val collage = Bitmap.createBitmap(cellW * 2, cellH * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(collage)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        for (i in 0 until minOf(4, frames.size)) {
            val col = i % 2
            val row = i / 2
            val dst = Rect(col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH)
            canvas.drawBitmap(frames[i], null, dst, paint)
        }
        for (f in frames) {
            try { f.recycle() } catch (_: Exception) {}
        }

        val out = ByteArrayOutputStream()
        collage.compress(Bitmap.CompressFormat.JPEG, 72, out)
        collage.recycle()
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return Pair(durationMs, b64)
    }

    private fun encodeImageFileToBase64Jpeg(imageFile: File, maxDim: Int = 640): String {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ""

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxDim * 2 || bounds.outHeight / sampleSize > maxDim * 2) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val raw = BitmapFactory.decodeFile(imageFile.absolutePath, opts) ?: return ""
            val largest = maxOf(raw.width, raw.height)
            val finalBmp = if (largest > maxDim) {
                val scale = maxDim.toFloat() / largest.toFloat()
                val scaled = Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * scale).toInt().coerceAtLeast(1),
                    (raw.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled != raw) raw.recycle()
                scaled
            } else {
                raw
            }
            val out = ByteArrayOutputStream()
            finalBmp.compress(Bitmap.CompressFormat.JPEG, 74, out)
            finalBmp.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    private fun pcm16kToWavBytes(pcmData: ByteArray, sampleRate: Int = 16000): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 2

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)

        val out = ByteArray(44 + pcmData.size)
        System.arraycopy(header.array(), 0, out, 0, 44)
        System.arraycopy(pcmData, 0, out, 44, pcmData.size)
        return out
    }
}
