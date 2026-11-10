package com.memeturbo.app

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

class VideoIndexer(private val context: Context) {

    private val ocrRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val db = MemeDatabase.getInstance(context).memeDao()

    data class AlbumInfo(val name: String, val count: Int, val isAll: Boolean = false)

    // Sayfa isimlerini ve Instagram/TikTok filigranlarını kökten temizleyen regex listesi
    private val watermarkPatterns = listOf(
        Regex("@[a-zA-Z0-9._]+", RegexOption.IGNORE_CASE),
        Regex("[0-9]*galerim[a-zA-Z0-9._]*", RegexOption.IGNORE_CASE),
        Regex(".*rraene.*", RegexOption.IGNORE_CASE),
        Regex(".*humor.*", RegexOption.IGNORE_CASE),
        Regex(".*mizah.*", RegexOption.IGNORE_CASE),
        Regex(".*tv.*anime.*", RegexOption.IGNORE_CASE),
        Regex(".*instagram.*", RegexOption.IGNORE_CASE),
        Regex(".*tiktok.*", RegexOption.IGNORE_CASE),
        Regex(".*post.*", RegexOption.IGNORE_CASE),
        Regex(".*sayfa.*", RegexOption.IGNORE_CASE),
        Regex(".*arsiv.*", RegexOption.IGNORE_CASE),
        Regex(".*reels.*", RegexOption.IGNORE_CASE),
        Regex(".*kesfet.*", RegexOption.IGNORE_CASE),
        Regex(".*takip.*", RegexOption.IGNORE_CASE)
    )

    /**
     * APK içerisindeki Türkçe Vosk ses modelini ilk kullanımda dahili hafızaya çıkartır.
     * Yarım kalma hatalarına karşı .complete doğrulama dosyası kullanır.
     */
    fun getOrInitVoskModel(): Model? {
        val destDir = File(context.filesDir, "vosk-model-small-tr")
        val markerFile = File(destDir, ".complete")

        if (!destDir.exists() || !markerFile.exists()) {
            try {
                destDir.deleteRecursively()
                destDir.mkdirs()
                copyAssetsFolder("models/vosk-model-small-tr", destDir)
                markerFile.createNewFile()
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }
        return try {
            Model(destDir.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun copyAssetsFolder(assetPath: String, destDir: File) {
        val assets = context.assets.list(assetPath) ?: return
        if (!destDir.exists()) destDir.mkdirs()
        for (item in assets) {
            val subAssetPath = "$assetPath/$item"
            val subDest = File(destDir, item)
            val subAssets = context.assets.list(subAssetPath)
            if (subAssets != null && subAssets.isNotEmpty()) {
                copyAssetsFolder(subAssetPath, subDest)
            } else {
                context.assets.open(subAssetPath).use { inStream ->
                    FileOutputStream(subDest).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }
            }
        }
    }

    suspend fun getAvailableAlbums(): List<AlbumInfo> = withContext(Dispatchers.IO) {
        val albums = mutableMapOf<String, Int>()
        var totalAllCount = 0

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.BUCKET_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucketName = cursor.getString(bucketIdx) ?: continue
                albums[bucketName] = (albums[bucketName] ?: 0) + 1
                totalAllCount++
            }
        }

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            val bucketIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucketName = cursor.getString(bucketIdx) ?: continue
                albums[bucketName] = (albums[bucketName] ?: 0) + 1
                totalAllCount++
            }
        }

        val result = mutableListOf<AlbumInfo>()
        result.add(AlbumInfo("🌟 Tüm Galeri (Hepsi)", totalAllCount, isAll = true))
        result.addAll(albums.map { AlbumInfo(it.key, it.value) }.sortedByDescending { it.count })
        return@withContext result
    }

    suspend fun indexAlbum(
        albumInfo: AlbumInfo,
        voskModel: Model?,
        onProgress: (current: Int, total: Int, name: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<Triple<String, String, Boolean>>()

        val isAll = albumInfo.isAll
        val selection = if (isAll) null else "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ?"
        val selectionArgs = if (isAll) null else arrayOf(albumInfo.name)

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.DISPLAY_NAME),
            selection, selectionArgs, null
        )?.use { cursor ->
            val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: "video"
                mediaList.add(Triple(path, name, true))
            }
        }

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.DISPLAY_NAME),
            selection, selectionArgs, null
        )?.use { cursor ->
            val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: "image"
                mediaList.add(Triple(path, name, false))
            }
        }

        val total = mediaList.size

        mediaList.forEachIndexed { index, item ->
            val file = File(item.first)
            onProgress(index + 1, total, item.second)
            if (file.exists()) {
                try {
                    val metadata = if (item.third) {
                        extractVideoData(file, voskModel)
                    } else {
                        extractImageData(file)
                    }
                    db.insertMeme(metadata)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private suspend fun extractImageData(file: File): MemeVideo {
        val screenTexts = mutableSetOf<String>()

        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val ocrResult = ocrRecognizer.process(inputImage).await()
                val cleanLines = filterWatermarks(ocrResult.text)
                if (cleanLines.isNotEmpty()) screenTexts.add(cleanLines)
                bitmap.recycle()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return MemeVideo(
            filePath = file.absolutePath,
            fileName = file.nameWithoutExtension,
            durationMs = 0L,
            spokenText = "",
            screenText = screenTexts.joinToString(" | "),
            visualLabels = "",
            isVideo = false,
            lastModified = file.lastModified()
        )
    }

    private suspend fun extractVideoData(file: File, voskModel: Model?): MemeVideo {
        val retriever = MediaMetadataRetriever()
        var durationMs = 0L
        val screenTexts = mutableSetOf<String>()

        try {
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            // Video içinde 3 farklı noktadan ekran yazısı (alt yazı / meme yazısı) kontrolü
            val sampleTimesUs = listOf(500_000L, 2_000_000L, 5_000_000L)
            for (timeUs in sampleTimesUs) {
                if (timeUs > durationMs * 1000L && timeUs > 500_000L) continue
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val result = ocrRecognizer.process(inputImage).await()
                    val cleanLines = filterWatermarks(result.text)
                    if (cleanLines.isNotEmpty()) screenTexts.add(cleanLines)
                    bitmap.recycle()
                }
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        // 1. ÖNCELİK: TÜRKÇE SES VE REPLİK TANIMA (Vosk Kaldi 16kHz Mono)
        val spokenText = if (voskModel != null) {
            transcribeVideoAudio(file, voskModel)
        } else {
            ""
        }

        return MemeVideo(
            filePath = file.absolutePath,
            fileName = file.nameWithoutExtension,
            durationMs = durationMs,
            spokenText = spokenText,
            screenText = screenTexts.joinToString(" | "),
            visualLabels = "",
            isVideo = true,
            lastModified = file.lastModified()
        )
    }

    /**
     * Köşelerdeki gereksiz Instagram/TikTok filigranlarını ve sayfa kullanıcı adlarını temizler.
     */
    private fun filterWatermarks(rawText: String): String {
        val lines = rawText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        val filtered = lines.filter { line ->
            line.length > 3 && watermarkPatterns.none { pattern -> pattern.containsMatchIn(line) }
        }
        return filtered.joinToString(" ").trim()
    }

    /**
     * Videodaki konuşmaları / replikleri 16kHz Mono PCM'e dönüştürerek Vosk motoruna iletir.
     * İlk 20 saniyelik ses parçasını çözmesi hem çok hızlıdır hem de repliği tam yakalar.
     */
    private fun transcribeVideoAudio(videoFile: File, voskModel: Model): String {
        val extractor = MediaExtractor()
        val transcriptParts = mutableListOf<String>()
        var codec: MediaCodec? = null
        var recognizer: Recognizer? = null

        try {
            extractor.setDataSource(videoFile.absolutePath)
            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null) return ""

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return ""
            val inSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            // Vosk Türkçe küçük modeli her zaman 16000 Hz Mono ses bekler!
            recognizer = Recognizer(voskModel, 16000f)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false
            var totalProcessedTimeUs = 0L
            val maxAudioDurationUs = 20_000_000L // İlk 20 saniye

            while (!isEOS && totalProcessedTimeUs < maxAudioDurationUs) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            totalProcessedTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, totalProcessedTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val pcmBytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(pcmBytes)
                        outputBuffer.clear()

                        // Stereo ve 44.1/48kHz sesi Vosk'a uygun 16kHz Mono 16-bit PCM'e dönüştür
                        val resampled16k = convertTo16kMono(pcmBytes, inSampleRate, channelCount)
                        if (resampled16k.isNotEmpty()) {
                            if (recognizer.acceptWaveForm(resampled16k, resampled16k.size)) {
                                val resJson = JSONObject(recognizer.result)
                                val text = resJson.optString("text", "").trim()
                                if (text.isNotBlank()) transcriptParts.add(text)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            val finalJson = JSONObject(recognizer.finalResult)
            val finalText = finalJson.optString("text", "").trim()
            if (finalText.isNotBlank()) transcriptParts.add(finalText)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { recognizer?.close() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
        return transcriptParts.joinToString(" ").trim()
    }

    /**
     * Stereo (veya çok kanallı) ve yüksek örnekleme hızlı (44.1k/48k) PCM sesini
     * Vosk modelinin kabul ettiği 16 kHz Mono 16-bit PCM formatına dönüştürür.
     */
    private fun convertTo16kMono(rawPcm: ByteArray, inSampleRate: Int, channels: Int): ByteArray {
        val totalShorts = rawPcm.size / 2
        if (totalShorts == 0) return ByteArray(0)

        // 1. Mono Shorts çıkarımı (Kanal ortalaması)
        val monoShorts: ShortArray
        if (channels >= 2) {
            val frames = totalShorts / channels
            monoShorts = ShortArray(frames)
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) {
                    val byteIdx = (i * channels + c) * 2
                    val s = (rawPcm[byteIdx].toInt() and 0xFF) or (rawPcm[byteIdx + 1].toInt() shl 8)
                    sum += s.toShort().toInt()
                }
                monoShorts[i] = (sum / channels).toShort()
            }
        } else {
            monoShorts = ShortArray(totalShorts)
            for (i in 0 until totalShorts) {
                val byteIdx = i * 2
                val s = (rawPcm[byteIdx].toInt() and 0xFF) or (rawPcm[byteIdx + 1].toInt() shl 8)
                monoShorts[i] = s.toShort()
            }
        }

        // 2. 16000 Hz'e yeniden örnekleme (Resampling)
        val targetRate = 16000
        val ratio = inSampleRate.toDouble() / targetRate.toDouble()
        val outLen = (monoShorts.size / ratio).toInt()
        if (outLen <= 0) return ByteArray(0)

        val outBytes = ByteArray(outLen * 2)
        for (i in 0 until outLen) {
            val srcIdx = (i * ratio).toInt().coerceIn(0, monoShorts.size - 1)
            val sample = monoShorts[srcIdx].toInt()
            outBytes[i * 2] = (sample and 0xFF).toByte()
            outBytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return outBytes
    }
}
