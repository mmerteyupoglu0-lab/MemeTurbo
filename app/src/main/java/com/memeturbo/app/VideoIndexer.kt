package com.memeturbo.app

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
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
    private val imageLabeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    private val db = MemeDatabase.getInstance(context).memeDao()

    data class AlbumInfo(val name: String, val count: Int, val isAll: Boolean = false)

    // Sayfa isimlerini, Instagram/TikTok filigranlarını temizleme filtresi
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
        Regex(".*sayfa.*", RegexOption.IGNORE_CASE)
    )

    private val trDictionary = mapOf(
        "cat" to "kedi pisi kedicik",
        "dog" to "köpek it kuçu azgın köpek",
        "carnivore" to "kedi köpek hayvan",
        "pet" to "evcil hayvan kedi köpek",
        "mammal" to "hayvan",
        "snow" to "kar karlı kış",
        "car" to "araba otomobil araç",
        "vehicle" to "araba araç",
        "smile" to "gülümseme gülme komik",
        "baby" to "bebek çocuk",
        "water" to "su deniz göl havuz h2o",
        "bottle" to "şişe su h2o",
        "food" to "yemek aş gıda",
        "person" to "adam insan kişi erkek kadın",
        "man" to "adam erkek kişi",
        "woman" to "kadın kız",
        "tree" to "ağaç orman doğa",
        "crowd" to "kalabalık grup arkadaşlar ortam",
        "people" to "insanlar arkadaşlar grup ortam kalabalık",
        "selfie" to "özçekim selfie ben kendim",
        "monkey" to "maymun şempanze su veren maymun",
        "ape" to "maymun şempanze",
        "place of worship" to "cami kilise ibadethane mimari ayasofya",
        "building" to "bina mimari yapı",
        "architecture" to "mimari tarihi yapı"
    )

    /**
     * APK içine gömülen Türkçe Vosk ses modelini ilk kullanımda dahili hafızaya açar.
     */
    fun getOrInitVoskModel(): Model? {
        val destDir = File(context.filesDir, "vosk-model-small-tr")
        if (!destDir.exists()) {
            try {
                copyAssetsFolder("models/vosk-model-small-tr", destDir)
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

    /**
     * Tarama Çeşitleri:
     * mode 0: Sadece henüz taranmamış yeni medyaları tara (Kalanları tara)
     * mode 1: Mevcut taranmışların üzerine yeniden derin analiz yap
     * mode 2: Tüm albümü baştan sona tara
     */
    suspend fun indexAlbum(
        albumInfo: AlbumInfo,
        voskModel: Model?,
        isDeepScan: Boolean,
        scanMode: Int,
        onProgress: (current: Int, total: Int, name: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val existingPaths = db.getAllIndexedPaths().toSet()
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

        val targetList = when (scanMode) {
            0 -> mediaList.filter { it.first !in existingPaths } // Sadece yeniler
            else -> mediaList // Tüm liste
        }
        val total = targetList.size

        targetList.forEachIndexed { index, item ->
            val file = File(item.first)
            onProgress(index + 1, total, item.second)
            if (file.exists()) {
                try {
                    val metadata = if (item.third) {
                        extractVideoData(file, voskModel, isDeepScan)
                    } else {
                        extractImageData(file, isDeepScan)
                    }
                    db.insertMeme(metadata)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private suspend fun extractImageData(file: File, isDeepScan: Boolean): MemeVideo {
        val screenTexts = mutableSetOf<String>()
        val labels = mutableSetOf<String>()

        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                val inputImage = InputImage.fromBitmap(bitmap, 0)

                val ocrResult = ocrRecognizer.process(inputImage).await()
                val cleanLines = filterWatermarks(ocrResult.text)
                if (cleanLines.isNotEmpty()) screenTexts.add(cleanLines)

                val labelResults = imageLabeler.process(inputImage).await()
                for (lbl in labelResults) {
                    val englishName = lbl.text.lowercase()
                    labels.add(englishName)
                    trDictionary[englishName]?.let { labels.add(it) }
                }

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
            visualLabels = labels.joinToString(" "),
            isVideo = false,
            lastModified = file.lastModified()
        )
    }

    private suspend fun extractVideoData(file: File, voskModel: Model?, isDeepScan: Boolean): MemeVideo {
        val retriever = MediaMetadataRetriever()
        var durationMs = 0L
        val screenTexts = mutableSetOf<String>()
        val labels = mutableSetOf<String>()

        try {
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val samplePointsUs = mutableListOf<Long>()
            val durationUs = durationMs * 1000L
            val stepUs = 1_500_000L
            var currentUs = 500_000L
            while (currentUs < durationUs && samplePointsUs.size < 12) {
                samplePointsUs.add(currentUs)
                currentUs += stepUs
            }
            if (samplePointsUs.isEmpty()) samplePointsUs.add(500_000L)

            for (timeUs in samplePointsUs) {
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    val inputImage = InputImage.fromBitmap(bitmap, 0)

                    val result = ocrRecognizer.process(inputImage).await()
                    val cleanLines = filterWatermarks(result.text)
                    if (cleanLines.isNotEmpty()) screenTexts.add(cleanLines)

                    val labelResults = imageLabeler.process(inputImage).await()
                    for (lbl in labelResults) {
                        val englishName = lbl.text.lowercase()
                        labels.add(englishName)
                        trDictionary[englishName]?.let { labels.add(it) }
                    }

                    bitmap.recycle()
                }
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        // 1. ÖNCELİK: TÜRKÇE SES VE REPLİK TANIMA!
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
            visualLabels = labels.joinToString(" "),
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
            watermarkPatterns.none { pattern -> pattern.containsMatchIn(line) }
        }
        return filtered.joinToString(" ").trim()
    }

    private fun transcribeVideoAudio(videoFile: File, voskModel: Model): String {
        val extractor = MediaExtractor()
        val transcriptParts = mutableListOf<String>()
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
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val recognizer = Recognizer(voskModel, sampleRate.toFloat())

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false

            while (!isEOS) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex) ?: continue
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val pcmBytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(pcmBytes)
                        outputBuffer.clear()
                        if (recognizer.acceptWaveForm(pcmBytes, pcmBytes.size)) {
                            val resJson = JSONObject(recognizer.result)
                            val text = resJson.optString("text", "")
                            if (text.isNotBlank()) transcriptParts.add(text)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }

            val finalJson = JSONObject(recognizer.finalResult)
            val finalText = finalJson.optString("text", "")
            if (finalText.isNotBlank()) transcriptParts.add(finalText)

            codec.stop()
            codec.release()
            recognizer.close()
        } catch (_: Exception) {
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
        return transcriptParts.joinToString(" ")
    }
}
