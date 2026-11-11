package com.memeturbo.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var albumSpinner: Spinner
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var galleryRecycler: RecyclerView
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var searchInput: EditText
    private lateinit var clearSearchBtn: TextView
    private lateinit var adapter: MemeGridAdapter
    private var availableAlbums = listOf<VideoIndexer.AlbumInfo>()
    private var currentFilter = 0
    private var voskModel: Model? = null
    private var isScanningOrRepairing = false

    // Arama yapmadan önceki son kaydırma (scroll) konumunu hafızada tutar
    private var savedScrollPosition = 0
    private var savedScrollOffset = 0

    private lateinit var tabAllBtn: Button
    private lateinit var tabVideosBtn: Button
    private lateinit var tabImagesBtn: Button

    private var internalPlayerLayout: FrameLayout? = null
    private var internalVideoView: VideoView? = null
    private var internalImageView: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Üst bildirim çubuğu ve alttaki geri tuşu panelini uygulamanın koyu lacivert rengine uyarla (Bembeyaz kalmasın!)
        window.statusBarColor = Color.parseColor("#1E293B")
        window.navigationBarColor = Color.parseColor("#0F172A")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 0
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16f), dpToPx(16f), dpToPx(16f), dpToPx(12f))
            setBackgroundColor(Color.parseColor("#1E293B"))
        }

        val title = TextView(this).apply {
            text = "⚡ MemeTurbo Akıllı Galeri"
            textSize = 20f
            setTextColor(Color.WHITE)
        }

        val privacyBanner = TextView(this).apply {
            text = "🔒 %100 Çevrimdışı & Güvenli: Videolarınız cihazınızda işlenir.\n🎙️ 1. Öncelik Replik: Konuşmalar ve temiz başlıklar otomatik algılanır."
            textSize = 12f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, dpToPx(6f), 0, dpToPx(8f))
        }

        val setupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(4f) }
        }

        val overlayCheck = Button(this).apply {
            text = "1. Yüzen İzin"
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12f).toFloat()
                setColor(if (Settings.canDrawOverlays(this@MainActivity)) Color.parseColor("#16A34A") else Color.parseColor("#0284C7"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(34f), 1f).apply { rightMargin = dpToPx(6f) }
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this@MainActivity, "Yüzen pencere izni açık!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val accessibilityCheck = Button(this).apply {
            text = "2. Çift Tık İzni"
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12f).toFloat()
                setColor(Color.parseColor("#4F46E5"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(34f), 1f)
            setOnClickListener {
                showXiaomiPermissionGuide()
            }
        }

        setupRow.addView(overlayCheck)
        setupRow.addView(accessibilityCheck)
        header.addView(title)
        header.addView(privacyBanner)
        header.addView(setupRow)
        mainContainer.addView(header)

        val scanRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16f), dpToPx(10f), dpToPx(16f), dpToPx(8f))
        }

        albumSpinner = Spinner(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dpToPx(1f), Color.parseColor("#475569"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(40f), 1.4f).apply { rightMargin = dpToPx(6f) }
        }

        val scanBtn = Button(this).apply {
            text = "Yenileri Tara ⚡"
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12f).toFloat()
                setColor(Color.parseColor("#16A34A"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(40f), 1f).apply { rightMargin = dpToPx(6f) }
            setOnClickListener { startIncrementalScan() }
        }

        val repairBtn = Button(this).apply {
            text = "🔧 Sesleri Çöz"
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12f).toFloat()
                setColor(Color.parseColor("#D97706"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(40f), 1f)
            setOnClickListener { startQuickRepair() }
        }

        scanRow.addView(albumSpinner)
        scanRow.addView(scanBtn)
        scanRow.addView(repairBtn)
        mainContainer.addView(scanRow)

        statusText = TextView(this).apply {
            text = "🎙️ Ses tanıma motoru hazırlanıyor..."
            textSize = 12f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(dpToPx(16f), 0, dpToPx(16f), dpToPx(8f))
        }
        mainContainer.addView(statusText)

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(16f), dpToPx(4f), dpToPx(16f), dpToPx(8f))
        }

        tabAllBtn = createFilterTab("Tümü", 0)
        tabVideosBtn = createFilterTab("Videolar", 1)
        tabImagesBtn = createFilterTab("Fotoğraflar", 2)

        tabRow.addView(tabAllBtn)
        tabRow.addView(tabVideosBtn)
        tabRow.addView(tabImagesBtn)
        mainContainer.addView(tabRow)

        // Arama Kutusu + Hızlı Temizleme (✕) Butonu
        val searchBoxContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(16f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dpToPx(1f), Color.parseColor("#334155"))
            }
            setPadding(dpToPx(14f), dpToPx(2f), dpToPx(10f), dpToPx(2f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dpToPx(16f), 0, dpToPx(16f), dpToPx(8f))
            }
        }

        // Klavye açıkken bile Geri tuşuna basıldığında uygulamadan atmak yerine
        // aramayı temizleyip en son kaydırılan konuma döndüren özel EditText!
        searchInput = object : EditText(this) {
            override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK && event?.action == KeyEvent.ACTION_UP) {
                    if (internalPlayerLayout?.visibility == View.VISIBLE) {
                        hideInternalPlayer()
                        return true
                    }
                    if (!text.isNullOrEmpty()) {
                        clearSearchAndRestorePosition()
                        return true
                    }
                }
                return super.onKeyPreIme(keyCode, event)
            }
        }.apply {
            hint = "Galeri içinde ara (replikler, kelimeler)..."
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            background = null
            setPadding(0, dpToPx(10f), 0, dpToPx(10f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        clearSearchBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#94A3B8"))
            visibility = View.GONE
            setPadding(dpToPx(8f), dpToPx(6f), dpToPx(4f), dpToPx(6f))
            setOnClickListener {
                clearSearchAndRestorePosition()
            }
        }

        searchBoxContainer.addView(searchInput)
        searchBoxContainer.addView(clearSearchBtn)
        mainContainer.addView(searchBoxContainer)

        gridLayoutManager = GridLayoutManager(this, 2)
        galleryRecycler = RecyclerView(this).apply {
            layoutManager = gridLayoutManager
            setHasFixedSize(true)
            setItemViewCacheSize(30)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    // Sadece arama kutusu boşken kullanıcının gezindiği yeri kaydet
                    if (searchInput.text.isNullOrEmpty()) {
                        val pos = gridLayoutManager.findFirstVisibleItemPosition()
                        if (pos != RecyclerView.NO_POSITION) {
                            savedScrollPosition = pos
                            savedScrollOffset = gridLayoutManager.findViewByPosition(pos)?.top ?: 0
                        }
                    }
                }
            })
        }

        adapter = MemeGridAdapter(
            onPreviewClick = { meme -> showInternalPlayer(meme) },
            onSendClick = { meme -> shareMemeMedia(meme) },
            onEditTagsClick = { meme -> showEditTagsDialog(meme) }
        )
        galleryRecycler.adapter = adapter

        // Yukarıdan Aşağı Kaydırırken Sayfa Yenileme (Pull-to-Refresh)
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.parseColor("#38BDF8"), Color.parseColor("#16A34A"))
            setProgressBackgroundColorSchemeColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            ).apply { setMargins(dpToPx(8f), 0, dpToPx(8f), 0) }
            setOnRefreshListener {
                refreshGalleryOnPullDown()
            }
        }
        swipeRefresh.addView(galleryRecycler)
        mainContainer.addView(swipeRefresh)

        root.addView(mainContainer)
        setupInternalPlayerLayout(root)

        setContentView(root)
        setupBackButtonBehavior()
        checkPermissionAndLoadAlbums()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim() ?: ""
                clearSearchBtn.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                filterList(q, restoreScrollWhenEmpty = q.isEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        updateTabColors()
        startupCleanAndInitVosk()
    }

    /**
     * Geri tuşuna basıldığında uygulamadan atmak yerine:
     * 1. Video önizleme açıksa onu kapatır.
     * 2. Arama kutusunda yazı varsa yazıyı silip en son kaydırılan konuma geri götürür.
     * 3. Alt sekmedeyse Tümü sekmesine döner.
     */
    private fun setupBackButtonBehavior() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    internalPlayerLayout?.visibility == View.VISIBLE -> {
                        hideInternalPlayer()
                    }
                    !searchInput.text.isNullOrEmpty() -> {
                        clearSearchAndRestorePosition()
                    }
                    currentFilter != 0 -> {
                        currentFilter = 0
                        updateTabColors()
                        filterList("", restoreScrollWhenEmpty = true)
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun clearSearchAndRestorePosition() {
        searchInput.setText("")
        searchInput.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        filterList("", restoreScrollWhenEmpty = true)
    }

    /**
     * Uygulama açılır açılmaz mevcut veritabanındaki tüm Korece/Japonca ve İngilizce çöpleri
     * 0.2 saniyede temizler, ardından Türkçe Vosk ses modelini hazırlar.
     */
    private fun startupCleanAndInitVosk() {
        val indexer = VideoIndexer(this)
        lifecycleScope.launch {
            statusText.text = "🧹 Mevcut liste temizleniyor..."
            indexer.cleanExistingDatabaseRecords()
            filterList(searchInput.text.toString().trim())

            voskModel = withContext(Dispatchers.IO) {
                indexer.getOrInitVoskModel { msg ->
                    runOnUiThread { statusText.text = msg }
                }
            }
            val totalDb = MemeDatabase.getInstance(this@MainActivity).memeDao().getCount()
            if (voskModel != null) {
                statusText.text = "🎙️ Türkçe Ses Motoru Aktif! ($totalDb medya hazır)"
            } else {
                statusText.text = "Kayıtlı: $totalDb medya hazır"
            }
        }
    }

    /**
     * En üstteyken parmağı yukarıdan aşağı kaydırınca (Pull-to-Refresh)
     * sadece yeni eklenen medyaları tarar ve listeyi tazeler!
     */
    private fun refreshGalleryOnPullDown() {
        if (isScanningOrRepairing) {
            swipeRefresh.isRefreshing = false
            return
        }
        val indexer = VideoIndexer(this)
        lifecycleScope.launch {
            indexer.cleanExistingDatabaseRecords()
            if (availableAlbums.isNotEmpty()) {
                val selectedAlbum = availableAlbums[albumSpinner.selectedItemPosition]
                val addedCount = indexer.indexNewMediaOnly(selectedAlbum, voskModel) { current, total, name ->
                    runOnUiThread {
                        statusText.text = "Yeni eklenen taranıyor ($current/$total): $name"
                    }
                }
                val totalDb = MemeDatabase.getInstance(this@MainActivity).memeDao().getCount()
                statusText.text = if (addedCount > 0) {
                    "✅ $addedCount yeni medya eklendi! (Toplam: $totalDb)"
                } else {
                    "✨ Galeri güncel! ($totalDb medya hazır)"
                }
            }
            loadAlbumsIntoSpinner()
            filterList(searchInput.text.toString().trim(), restoreScrollWhenEmpty = false)
            swipeRefresh.isRefreshing = false
        }
    }

    /**
     * "Yenileri Tara ⚡": 800 videoyu baştan tarayıp şarjı bitirmek yerine,
     * sadece galeriye yeni eklenen videoları/fotoğrafları bulup tarar!
     */
    private fun startIncrementalScan() {
        if (availableAlbums.isEmpty() || isScanningOrRepairing) return
        val selectedAlbum = availableAlbums[albumSpinner.selectedItemPosition]
        val indexer = VideoIndexer(this)
        isScanningOrRepairing = true

        lifecycleScope.launch {
            try {
                if (voskModel == null) {
                    statusText.text = "🎙️ Türkçe ses modeli yükleniyor..."
                    voskModel = withContext(Dispatchers.IO) {
                        indexer.getOrInitVoskModel { msg -> runOnUiThread { statusText.text = msg } }
                    }
                }

                statusText.text = "${selectedAlbum.name} içinde yeni medyalar kontrol ediliyor..."
                val addedCount = indexer.indexNewMediaOnly(
                    albumInfo = selectedAlbum,
                    voskModel = voskModel
                ) { current, total, name ->
                    runOnUiThread {
                        statusText.text = "Yeni Medya Taranıyor ($current/$total): $name"
                    }
                }

                val totalDb = MemeDatabase.getInstance(this@MainActivity).memeDao().getCount()
                if (addedCount == 0) {
                    statusText.text = "✨ Yeni eklenen medya yok, tümü kayıtlı ($totalDb medya). Replikler için '🔧 Sesleri Çöz'e basın."
                    Toast.makeText(this@MainActivity, "Tüm medyalar zaten ekli! Replik onarımı için 'Sesleri Çöz' butonunu kullanabilirsin.", Toast.LENGTH_LONG).show()
                } else {
                    statusText.text = "✅ $addedCount yeni medya tarandı! (Toplam: $totalDb)"
                    Toast.makeText(this@MainActivity, "$addedCount yeni medya eklendi!", Toast.LENGTH_SHORT).show()
                }
                loadAlbumsIntoSpinner()
                filterList(searchInput.text.toString().trim())
            } finally {
                isScanningOrRepairing = false
            }
        }
    }

    /**
     * "🔧 Sesleri Çöz": Eski etiket çöplerini temizler ve henüz repliği (sesi)
     * çözülmemiş videoların konuşmalarını (ve sessizse Türkçe hayvan/sahne bilgisini) çıkarır!
     */
    private fun startQuickRepair() {
        if (isScanningOrRepairing) return
        val indexer = VideoIndexer(this)
        isScanningOrRepairing = true

        lifecycleScope.launch {
            try {
                val db = MemeDatabase.getInstance(this@MainActivity).memeDao()

                statusText.text = "🧹 Korece/Japonca ve eski etiket çöpleri temizleniyor..."
                indexer.cleanExistingDatabaseRecords()
                filterList(searchInput.text.toString().trim())

                if (voskModel == null) {
                    statusText.text = "📦 Türkçe ses modeli çıkartılıyor, lütfen bekleyin..."
                    voskModel = withContext(Dispatchers.IO) {
                        indexer.getOrInitVoskModel { msg -> runOnUiThread { statusText.text = msg } }
                    }
                }

                if (voskModel == null) {
                    statusText.text = "⚠️ Ses modeli yüklenemedi, ancak tüm başlıklar temizlendi!"
                    Toast.makeText(this@MainActivity, "Başlıklar temizlendi!", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val missingVideos = db.getVideosMissingVoice()
                val total = missingVideos.size

                if (total == 0) {
                    statusText.text = "✅ Tüm videoların ses/replik taraması tamamlanmış!"
                    filterList(searchInput.text.toString().trim())
                    Toast.makeText(this@MainActivity, "Tüm videolar hazır!", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                statusText.text = "🎙️ Replikler dinleniyor (0/$total)..."

                withContext(Dispatchers.IO) {
                    missingVideos.forEachIndexed { idx, item ->
                        val file = File(item.filePath)
                        if (file.exists()) {
                            try {
                                val text = indexer.transcribeSingleVideo(file, voskModel!!)
                                val cleanTitle = VideoIndexer.cleanFileNameForDisplay(item.fileName)
                                val cleanScreen = VideoIndexer.filterWatermarks(item.screenText)
                                var cleanVis = VideoIndexer.cleanVisualLabelsString(item.visualLabels)

                                // Eğer videoda konuşma yoksa, temiz dosya adı yoksa ve yazı yoksa:
                                // "🐱 Kedi • 💡 Işık / Lazer" gibi Saf Türkçe sahne etiketini bul!
                                if (text.isBlank() && cleanTitle.isBlank() && cleanScreen.isBlank() && cleanVis.isBlank()) {
                                    cleanVis = indexer.detectSceneForVideoFile(file)
                                }

                                // Tekrar tekrar tarayıp şarj tüketmemesi için sessiz videoları görünmez işaretle ("\u200B")
                                val savedSpoken = if (text.isNotBlank()) text else "\u200B"
                                db.updateFields(
                                    path = item.filePath,
                                    spokenText = savedSpoken,
                                    screenText = cleanScreen,
                                    visualLabels = cleanVis
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        runOnUiThread {
                            statusText.text = "🎙️ Replik Çözülüyor (${idx + 1}/$total): ${VideoIndexer.cleanFileNameForDisplay(item.fileName).ifEmpty { item.fileName.take(20) }}"
                        }
                        // Her 10 videoda bir ekranı canlı güncelle
                        if ((idx + 1) % 10 == 0) {
                            withContext(Dispatchers.Main) {
                                filterList(searchInput.text.toString().trim(), restoreScrollWhenEmpty = false)
                            }
                        }
                    }
                }

                statusText.text = "✅ Tüm replikler ve açıklamalar güncellendi!"
                filterList(searchInput.text.toString().trim(), restoreScrollWhenEmpty = false)
                Toast.makeText(this@MainActivity, "Replikler ve açıklamalar başarıyla güncellendi!", Toast.LENGTH_SHORT).show()
            } finally {
                isScanningOrRepairing = false
            }
        }
    }

    private fun filterList(query: String, restoreScrollWhenEmpty: Boolean = false) {
        lifecycleScope.launch {
            val db = MemeDatabase.getInstance(this@MainActivity).memeDao()
            val rawList = when (currentFilter) {
                1 -> db.getVideoMemes()
                2 -> db.getImageMemes()
                else -> db.getAllMemes()
            }

            val filtered = if (query.isEmpty()) {
                rawList
            } else {
                val searchWords = normalizeText(query).split("\\s+".toRegex()).filter { it.isNotBlank() }
                rawList.filter { item ->
                    val cleanTitle = VideoIndexer.cleanFileNameForDisplay(item.fileName)
                    val cleanScreen = VideoIndexer.filterWatermarks(item.screenText)
                    val cleanVis = VideoIndexer.cleanVisualLabelsString(item.visualLabels)
                    val combined = normalizeText("${item.fileName} $cleanTitle ${item.spokenText} $cleanScreen $cleanVis")
                    searchWords.all { word -> combined.contains(word) }
                }
            }
            adapter.submitList(filtered)

            if (query.isEmpty() && restoreScrollWhenEmpty && savedScrollPosition > 0) {
                galleryRecycler.post {
                    gridLayoutManager.scrollToPositionWithOffset(savedScrollPosition, savedScrollOffset)
                }
            }
        }
    }

    private fun normalizeText(text: String): String {
        return text.lowercase()
            .replace('ı', 'i')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ş', 's')
            .replace('ö', 'o')
            .replace('ç', 'c')
    }

    private fun createFilterTab(title: String, filterId: Int): Button {
        return Button(this).apply {
            text = title
            textSize = 12f
            setTextColor(Color.WHITE)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(34f), 1f).apply {
                setMargins(dpToPx(3f), 0, dpToPx(3f), 0)
            }
            setOnClickListener {
                currentFilter = filterId
                updateTabColors()
                filterList(searchInput.text.toString().trim())
            }
        }
    }

    private fun updateTabColors() {
        val activeColor = Color.parseColor("#0284C7")
        val inactiveColor = Color.parseColor("#1E293B")

        tabAllBtn.background = GradientDrawable().apply {
            cornerRadius = dpToPx(10f).toFloat()
            setColor(if (currentFilter == 0) activeColor else inactiveColor)
        }
        tabVideosBtn.background = GradientDrawable().apply {
            cornerRadius = dpToPx(10f).toFloat()
            setColor(if (currentFilter == 1) activeColor else inactiveColor)
        }
        tabImagesBtn.background = GradientDrawable().apply {
            cornerRadius = dpToPx(10f).toFloat()
            setColor(if (currentFilter == 2) activeColor else inactiveColor)
        }
    }

    private fun showXiaomiPermissionGuide() {
        AlertDialog.Builder(this)
            .setTitle("🔑 Erişilebilirlik İzni Rehberi")
            .setMessage("Xiaomi/Android güvenlik kilidini açmak için:\n\n1. Açılacak sayfada en alttaki 'Kısıtlanmış ayarlara izin ver' seçeneğini açın.\n2. Ardından Erişilebilirlik -> İndirilen Uygulamalar -> MemeTurbo'yu aktif edin.")
            .setPositiveButton("Ayarlara Git") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            .setNegativeButton("Doğrudan Erişilebilirlik") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    private fun showEditTagsDialog(meme: MemeVideo) {
        val input = EditText(this).apply {
            hint = "Örn: Arda Turan, lazere bakan kedi, çıldırma"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ Videoya Arama Kelimesi Ekle")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val newTags = input.text.toString().trim()
                if (newTags.isNotBlank()) {
                    lifecycleScope.launch {
                        MemeDatabase.getInstance(this@MainActivity).memeDao().appendCustomTags(meme.filePath, newTags)
                        Toast.makeText(this@MainActivity, "Etiket eklendi!", Toast.LENGTH_SHORT).show()
                        filterList(searchInput.text.toString().trim())
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun setupInternalPlayerLayout(root: FrameLayout) {
        val overlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#EE0B1329"))
            setOnClickListener { hideInternalPlayer() }
        }

        val contentBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { setMargins(dpToPx(20f), 0, dpToPx(20f), 0) }
        }

        internalVideoView = VideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(260f)
            )
        }

        internalImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(260f)
            )
        }

        val closeBtn = Button(this).apply {
            text = "✕ Kapat"
            setTextColor(Color.WHITE)
            textSize = 13f
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(16f).toFloat()
                setColor(Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(120f), dpToPx(38f)).apply {
                topMargin = dpToPx(14f)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { hideInternalPlayer() }
        }

        contentBox.addView(internalVideoView)
        contentBox.addView(internalImageView)
        contentBox.addView(closeBtn)
        overlay.addView(contentBox)

        internalPlayerLayout = overlay
        root.addView(overlay)
    }

    private fun showInternalPlayer(meme: MemeVideo) {
        val overlay = internalPlayerLayout ?: return
        val vView = internalVideoView ?: return
        val iView = internalImageView ?: return

        overlay.visibility = View.VISIBLE
        if (meme.isVideo) {
            iView.visibility = View.GONE
            vView.visibility = View.VISIBLE
            vView.setVideoPath(meme.filePath)
            vView.setOnPreparedListener { mp -> mp.isLooping = true }
            vView.start()
        } else {
            vView.stopPlayback()
            vView.visibility = View.GONE
            iView.visibility = View.VISIBLE
            val bmp = BitmapFactory.decodeFile(meme.filePath)
            iView.setImageBitmap(bmp)
        }
    }

    private fun hideInternalPlayer() {
        internalVideoView?.stopPlayback()
        internalPlayerLayout?.visibility = View.GONE
    }

    private fun shareMemeMedia(meme: MemeVideo) {
        val file = File(meme.filePath)
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val mime = if (meme.isVideo) "video/*" else "image/*"

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newUri(contentResolver, meme.fileName, uri)
        clipboard.setPrimaryClip(clip)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Meme Paylaş"))
    }

    private fun checkPermissionAndLoadAlbums() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        } else {
            loadAlbumsIntoSpinner()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            loadAlbumsIntoSpinner()
        }
    }

    private fun loadAlbumsIntoSpinner() {
        lifecycleScope.launch {
            val indexer = VideoIndexer(this@MainActivity)
            availableAlbums = indexer.getAvailableAlbums()

            val names = availableAlbums.map { "${it.name} (${it.count} öğe)" }
            val spinnerAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            albumSpinner.adapter = spinnerAdapter

            filterList(searchInput.text.toString().trim(), restoreScrollWhenEmpty = false)
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        internalVideoView?.stopPlayback()
    }
}
