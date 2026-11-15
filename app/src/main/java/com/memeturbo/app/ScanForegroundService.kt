package com.memeturbo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

class ScanForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    data class ScanState(
        val isRunning: Boolean = false,
        val isPaused: Boolean = false,
        val statusMessage: String = "",
        val current: Int = 0,
        val total: Int = 0,
        val refreshTick: Int = 0
    )

    companion object {
        const val CHANNEL_ID = "memeturbo_scan_channel"
        const val NOTIF_ID = 4041
        const val ACTION_START_SCAN = "com.memeturbo.START_SCAN"
        const val ACTION_START_REPAIR = "com.memeturbo.START_REPAIR"
        const val ACTION_PAUSE_RESUME = "com.memeturbo.PAUSE_RESUME"
        const val ACTION_STOP = "com.memeturbo.STOP"

        const val EXTRA_ALBUM_NAME = "album_name"
        const val EXTRA_ALBUM_IS_ALL = "album_is_all"
        const val EXTRA_FORCE_FULL_ALBUM = "force_full_album"

        val stateFlow = MutableStateFlow(ScanState())
        @Volatile
        var isPausedFlag = false
        @Volatile
        var isStoppedFlag = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCAN -> {
                val albumName = intent.getStringExtra(EXTRA_ALBUM_NAME) ?: "🌟 Tüm Galeri (Hepsi)"
                val isAll = intent.getBooleanExtra(EXTRA_ALBUM_IS_ALL, true)
                startForegroundSafe("Yeni medyalar taranıyor...")
                runIncrementalScan(VideoIndexer.AlbumInfo(albumName, 0, isAll))
            }
            ACTION_START_REPAIR -> {
                val albumName = intent.getStringExtra(EXTRA_ALBUM_NAME) ?: "🌟 Tüm Galeri (Hepsi)"
                val isAll = intent.getBooleanExtra(EXTRA_ALBUM_IS_ALL, true)
                val forceFull = intent.getBooleanExtra(EXTRA_FORCE_FULL_ALBUM, false)
                startForegroundSafe("🌐 4x Hızlı AI Çözümleme çalışıyor...")
                runVoiceRepair(VideoIndexer.AlbumInfo(albumName, 0, isAll), forceFull)
            }
            ACTION_PAUSE_RESUME -> {
                isPausedFlag = !isPausedFlag
                val cur = stateFlow.value
                val msg = if (isPausedFlag) "⏸️ Duraklatıldı (${cur.current}/${cur.total})" else "▶️ Devam ediliyor (${cur.current}/${cur.total})..."
                stateFlow.value = cur.copy(isPaused = isPausedFlag, statusMessage = msg)
                updateNotification(msg)
            }
            ACTION_STOP -> {
                isStoppedFlag = true
                isPausedFlag = false
                currentJob?.cancel()
                releaseWakeLock()
                stateFlow.value = ScanState(
                    isRunning = false,
                    isPaused = false,
                    statusMessage = "⏹️ Tarama durduruldu.",
                    refreshTick = stateFlow.value.refreshTick + 1
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafe(initialText: String) {
        val notif = buildNotification(initialText)
        startForeground(NOTIF_ID, notif)
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MemeTurbo::ScanWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(120 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private suspend fun waitIfPaused() {
        while (isPausedFlag && !isStoppedFlag) {
            delay(300)
        }
    }

    private fun runIncrementalScan(albumInfo: VideoIndexer.AlbumInfo) {
        currentJob?.cancel()
        isPausedFlag = false
        isStoppedFlag = false

        currentJob = serviceScope.launch {
            val indexer = VideoIndexer(this@ScanForegroundService)
            val db = MemeDatabase.getInstance(this@ScanForegroundService).memeDao()
            val prefs = getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
            val aiCheckedSet = prefs.getStringSet("ai_checked_paths_v15", emptySet())?.toMutableSet() ?: mutableSetOf()
            try {
                stateFlow.value = ScanState(isRunning = true, statusMessage = "${albumInfo.name} kontrol ediliyor...")

                val existingPaths = db.getAllIndexedPaths().toHashSet()
                val mediaList = indexer.queryGalleryMediaPublic(albumInfo)
                val newItems = mediaList.filter { it.path !in existingPaths }
                val total = newItems.size

                if (total == 0) {
                    stateFlow.value = ScanState(
                        isRunning = false,
                        statusMessage = "✨ Yeni medya yok, tümü zaten kayıtlı!",
                        refreshTick = stateFlow.value.refreshTick + 1
                    )
                    return@launch
                }

                stateFlow.value = stateFlow.value.copy(
                    statusMessage = "⚡ $total medya hızlı listeye ekleniyor..."
                )
                val preIndexedBatch = ArrayList<MemeVideo>(newItems.size)
                for (item in newItems) {
                    val f = File(item.path)
                    if (f.exists()) {
                        preIndexedBatch.add(
                            MemeVideo(
                                filePath = f.absolutePath,
                                fileName = f.nameWithoutExtension,
                                durationMs = 0L,
                                spokenText = "",
                                screenText = "",
                                visualLabels = "",
                                isVideo = item.isVideo,
                                lastModified = f.lastModified(),
                                albumName = item.albumName
                            )
                        )
                    }
                }
                if (preIndexedBatch.isNotEmpty()) {
                    db.insertMemesBatch(preIndexedBatch)
                }
                stateFlow.value = stateFlow.value.copy(refreshTick = stateFlow.value.refreshTick + 1)

                newItems.forEachIndexed { index, item ->
                    if (isStoppedFlag) return@forEachIndexed
                    waitIfPaused()
                    if (isStoppedFlag) return@forEachIndexed

                    val file = File(item.path)
                    val curNum = index + 1
                    val msg = "⚡ Turbo Analiz ($curNum/$total): ${item.name.take(22)}"
                    stateFlow.value = stateFlow.value.copy(
                        isRunning = true,
                        current = curNum,
                        total = total,
                        statusMessage = msg
                    )
                    if (curNum == 1 || curNum % 6 == 0) {
                        updateNotification(msg)
                    }

                    if (file.exists()) {
                        try {
                            val metadata = if (item.isVideo) {
                                indexer.extractVideoData(file, item.albumName)
                            } else {
                                indexer.extractImageData(file, item.albumName)
                            }
                            db.insertMeme(metadata)
                            aiCheckedSet.add(file.absolutePath)
                            VideoIndexer.invalidateCacheFor(file.absolutePath)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (curNum % 16 == 0) {
                        prefs.edit().putStringSet("ai_checked_paths_v15", aiCheckedSet).apply()
                        stateFlow.value = stateFlow.value.copy(refreshTick = stateFlow.value.refreshTick + 1)
                    }
                    delay(15)
                }

                prefs.edit().putStringSet("ai_checked_paths_v15", aiCheckedSet).apply()
                val totalDb = db.getCount()
                stateFlow.value = ScanState(
                    isRunning = false,
                    statusMessage = "✅ $total medya tamamlandı! (Toplam: $totalDb)",
                    refreshTick = stateFlow.value.refreshTick + 1
                )
            } catch (_: CancellationException) {
            } finally {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun runVoiceRepair(albumInfo: VideoIndexer.AlbumInfo, forceFullAlbum: Boolean = false) {
        currentJob?.cancel()
        isPausedFlag = false
        isStoppedFlag = false

        currentJob = serviceScope.launch {
            val indexer = VideoIndexer(this@ScanForegroundService)
            val db = MemeDatabase.getInstance(this@ScanForegroundService).memeDao()
            val prefs = getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
            val aiCheckedSet = prefs.getStringSet("ai_checked_paths_v15", emptySet())?.toMutableSet() ?: mutableSetOf()
            try {
                stateFlow.value = ScanState(isRunning = true, statusMessage = "🌐 AI çözümleme listesi hazırlanıyor...")

                val editedPaths = prefs.getStringSet("user_edited_paths", emptySet()) ?: emptySet()

                val allItems = db.getAllMemes().let { list ->
                    if (albumInfo.isAll) list else list.filter { it.albumName == albumInfo.name }
                }

                val targetItems = allItems.filter { item ->
                    if (item.filePath in editedPaths) return@filter false
                    if (forceFullAlbum) return@filter true
                    if (item.filePath in aiCheckedSet) return@filter false

                    val spoken = item.spokenText.replace("\u200B", "").trim()
                    val screen = VideoIndexer.filterWatermarks(item.screenText)
                    val visual = VideoIndexer.cleanVisualLabelsString(item.visualLabels)

                    if (item.isVideo) {
                        val hasGoodSpeech = spoken.isNotBlank() &&
                            !OnlineAiAnalyzer.isMusicOrEmptyTranscript(spoken) &&
                            !VideoIndexer.isWeakSpokenFiller(spoken)
                        val hasGoodScreen = screen.length >= 6
                        !hasGoodSpeech && !hasGoodScreen
                    } else {
                        val hasAiPhotoScene = visual.startsWith("🖼️")
                        !hasAiPhotoScene
                    }
                }

                val total = targetItems.size
                if (total == 0) {
                    stateFlow.value = ScanState(
                        isRunning = false,
                        statusMessage = "✅ Seçili albümde eksik medya kalmadı!",
                        refreshTick = stateFlow.value.refreshTick + 1
                    )
                    return@launch
                }

                val targetVideos = targetItems.filter { it.isVideo }
                val targetImages = targetItems.filter { !it.isVideo }
                var processedCount = 0

                // 1) Videoları 3'lü paralel motorla (3x hızlı) işle!
                val videoSem = Semaphore(3)
                val videoDoneCounter = AtomicInteger(0)
                val checkedLock = Any()

                coroutineScope {
                    targetVideos.map { item ->
                        async(Dispatchers.IO) {
                            if (isStoppedFlag) return@async
                            waitIfPaused()
                            if (isStoppedFlag) return@async

                            videoSem.withPermit {
                                if (isStoppedFlag) return@withPermit
                                waitIfPaused()
                                if (isStoppedFlag) return@withPermit

                                val file = File(item.filePath)
                                val shortName = VideoIndexer.cleanFileNameForDisplay(item.fileName).ifEmpty { item.fileName.take(20) }
                                val curDone = videoDoneCounter.incrementAndGet()
                                val msg = "⚡ 3x AI Video ($curDone/$total): $shortName"
                                stateFlow.value = stateFlow.value.copy(
                                    isRunning = true,
                                    current = curDone,
                                    total = total,
                                    statusMessage = msg
                                )
                                if (curDone == 1 || curDone % 5 == 0) updateNotification(msg)

                                if (file.exists()) {
                                    try {
                                        val oldSpoken = item.spokenText.replace("\u200B", "").trim()
                                        val oldHasGoodSpeech = oldSpoken.isNotBlank() &&
                                            !OnlineAiAnalyzer.isMusicOrEmptyTranscript(oldSpoken) &&
                                            !VideoIndexer.isWeakSpokenFiller(oldSpoken)

                                        val fresh = indexer.extractVideoData(file, item.albumName)
                                        val newSpoken = fresh.spokenText.replace("\u200B", "").trim()
                                        val finalSpoken = when {
                                            !OnlineAiAnalyzer.isMusicOrEmptyTranscript(newSpoken) && !VideoIndexer.isWeakSpokenFiller(newSpoken) -> newSpoken
                                            oldHasGoodSpeech -> oldSpoken
                                            !OnlineAiAnalyzer.isMusicOrEmptyTranscript(newSpoken) && newSpoken.isNotBlank() -> newSpoken
                                            !OnlineAiAnalyzer.isMusicOrEmptyTranscript(oldSpoken) && oldSpoken.isNotBlank() -> oldSpoken
                                            else -> ""
                                        }
                                        val oldScreen = VideoIndexer.filterWatermarks(item.screenText)
                                        val finalScreen = fresh.screenText.ifBlank { oldScreen }
                                        val finalVisual = fresh.visualLabels.ifBlank { VideoIndexer.cleanVisualLabelsString(item.visualLabels) }

                                        db.updateFields(item.filePath, finalSpoken, finalScreen, finalVisual)
                                        synchronized(checkedLock) {
                                            aiCheckedSet.add(item.filePath)
                                        }
                                        VideoIndexer.invalidateCacheFor(item.filePath)
                                    } catch (_: Exception) {}
                                }
                                if (curDone % 6 == 0) {
                                    synchronized(checkedLock) {
                                        prefs.edit().putStringSet("ai_checked_paths_v15", aiCheckedSet).apply()
                                    }
                                    stateFlow.value = stateFlow.value.copy(refreshTick = stateFlow.value.refreshTick + 1)
                                }
                            }
                        }
                    }.awaitAll()
                }
                processedCount = videoDoneCounter.get()

                // 2) Fotoğrafları 4'lü kolajlar halinde 4x hızlı çöz!
                val imgChunks = targetImages.chunked(4)
                for (chunk in imgChunks) {
                    if (isStoppedFlag) break
                    waitIfPaused()
                    if (isStoppedFlag) break

                    val validFiles = chunk.map { File(it.filePath) }.filter { it.exists() }
                    val hints = chunk.associate { it.filePath to VideoIndexer.filterWatermarks(it.screenText) }

                    val msg = "🌐 4x AI Fotoğraf (${processedCount + 1}..${minOf(processedCount + chunk.size, total)}/$total)"
                    stateFlow.value = stateFlow.value.copy(
                        isRunning = true,
                        current = processedCount,
                        total = total,
                        statusMessage = msg
                    )
                    updateNotification(msg)

                    val batchRes = if (validFiles.isNotEmpty()) {
                        OnlineAiAnalyzer.analyzeImageBatchWithAi(this@ScanForegroundService, validFiles, hints)
                    } else {
                        emptyMap()
                    }

                    for (item in chunk) {
                        processedCount++
                        val res = batchRes[item.filePath]
                        if (res != null) {
                            val finalScreen = res.screenText.ifBlank { VideoIndexer.filterWatermarks(item.screenText) }
                            val finalVisual = res.visualLabels.ifBlank { VideoIndexer.cleanVisualLabelsString(item.visualLabels) }
                            db.updateFields(item.filePath, item.spokenText, finalScreen, finalVisual)
                        }
                        aiCheckedSet.add(item.filePath)
                        VideoIndexer.invalidateCacheFor(item.filePath)
                    }

                    prefs.edit().putStringSet("ai_checked_paths_v15", aiCheckedSet).apply()
                    stateFlow.value = stateFlow.value.copy(refreshTick = stateFlow.value.refreshTick + 1)
                    delay(30)
                }

                prefs.edit().putStringSet("ai_checked_paths_v15", aiCheckedSet).apply()
                stateFlow.value = ScanState(
                    isRunning = false,
                    statusMessage = "✅ $total medya AI ile güncellendi!",
                    refreshTick = stateFlow.value.refreshTick + 1
                )
            } catch (_: CancellationException) {
            } finally {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MemeTurbo Arka Plan Taraması",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScanForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚡ MemeTurbo Arka Plan")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Durdur", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
    }
}
