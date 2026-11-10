package com.memeturbo.app

import android.content.Context
import android.graphics.BitmapFactory
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
import java.io.File

class VideoIndexer(private val context: Context) {

    private val ocrRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val imageLabeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    private val db = MemeDatabase.getInstance(context).memeDao()

    data class AlbumInfo(val name: String, val count: Int, val isAll: Boolean = false)

    // Çok kapsamlı Türkçe eşleme ve grup/özçekim sözlüğü
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
        "ape" to "maymun şempanze"
    )

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

        val unindexed = mediaList.filter { it.first !in existingPaths }
        val total = unindexed.size

        unindexed.forEachIndexed { index, item ->
            val file = File(item.first)
            onProgress(index + 1, total, item.second)
            if (file.exists()) {
                try {
                    val metadata = if (item.third) {
                        extractVideoData(file)
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
        val labels = mutableSetOf<String>()

        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                val inputImage = InputImage.fromBitmap(bitmap, 0)

                val ocrResult = ocrRecognizer.process(inputImage).await()
                val cleanText = ocrResult.text.replace("\n", " ").trim()
                if (cleanText.isNotEmpty()) screenTexts.add(cleanText)

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

    private suspend fun extractVideoData(file: File): MemeVideo {
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
                    val cleanText = result.text.replace("\n", " ").trim()
                    if (cleanText.isNotEmpty()) screenTexts.add(cleanText)

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

        return MemeVideo(
            filePath = file.absolutePath,
            fileName = file.nameWithoutExtension,
            durationMs = durationMs,
            spokenText = "",
            screenText = screenTexts.joinToString(" | "),
            visualLabels = labels.joinToString(" "),
            isVideo = true,
            lastModified = file.lastModified()
        )
    }
}
