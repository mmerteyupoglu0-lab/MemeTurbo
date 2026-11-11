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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var selectedAlbumFilter: String? = null
    private var searchJob: Job? = null
    private var lastObservedTick = -1
    private var cachedAllMemes: List<MemeVideo>? = null

    private lateinit var scanControlRow: LinearLayout
    private lateinit var pauseResumeBtn: Button
    private lateinit var stopScanBtn: Button

    private var savedScrollPosition = 0
    private var savedScrollOffset = 0
    private var isRestoringScroll = false

    private lateinit var tabAllBtn: Button
    private lateinit var tabVideosBtn: Button
    private lateinit var tabImagesBtn: Button

    private var internalPlayerLayout: FrameLayout? = null
    private var internalVideoView: VideoView? = null
    private var internalImageView: ImageView? = null
    private var currentPreviewMeme: MemeVideo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            text = "⚡ ReplikBul • MemeTurbo"
            textSize = 20f
            setTextColor(Color.WHITE)
        }

        val privacyBanner = TextView(this).apply {
            text = "🔒 %100 Çevrimdışı & Güvenli: Ekran kapalıyken bile arka planda tarar.\n🎙️ 1. Öncelik Replik: Konuşmalar dinlenir, yanlışsa ✏️ ile 1 dokunuşta düzeltilir."
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
            setOnClickListener { showXiaomiPermissionGuide() }
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
            setPadding(dpToPx(16f), dpToPx(10f), dpToPx(16f), dpToPx(6f))
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
            setOnClickListener { startIncrementalScanService() }
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
            setOnClickListener { startRepairService() }
        }

        scanRow.addView(albumSpinner)
        scanRow.addView(scanBtn)
        scanRow.addView(repairBtn)
        mainContainer.addView(scanRow)

        // Duraklat / Devam Et ve Durdur Kontrol Satırı (Tarama çalışırken görünür)
        scanControlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(dpToPx(16f), 0, dpToPx(16f), dpToPx(6f))
        }

        pauseResumeBtn = Button(this).apply {
            text = "⏸️ Duraklat"
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(10f).toFloat()
                setColor(Color.parseColor("#475569"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(34f), 1f).apply { rightMargin = dpToPx(6f) }
            setOnClickListener {
                startService(Intent(this@MainActivity, ScanForegroundService::class.java).apply {
                    action = ScanForegroundService.ACTION_PAUSE_RESUME
                })
            }
        }

        stopScanBtn = Button(this).apply {
            text = "⏹️ Durdur"
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(10f).toFloat()
                setColor(Color.parseColor("#DC2626"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(34f), 1f)
            setOnClickListener {
                startService(Intent(this@MainActivity, ScanForegroundService::class.java).apply {
                    action = ScanForegroundService.ACTION_STOP
                })
            }
        }

        scanControlRow.addView(pauseResumeBtn)
        scanControlRow.addView(stopScanBtn)
        mainContainer.addView(scanControlRow)

        statusText = TextView(this).apply {
            text = "Galeri hazırlanıyor..."
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

        searchInput = object : EditText(this) {
            override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK && event?.action == KeyEvent.ACTION_UP) {
                    if (internalPlayerLayout?.visibility == View.VISIBLE) {
                        hideInternalPlayer()
                        return true
                    }
                    // Klavye açıkken geri tuşuna basınca aramayı silmeden sadece klavyeyi indir
                    clearFocus()
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
            setOnClickListener { clearSearchAndRestorePosition() }
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
            // İlk kaydırma veya liste dokunuşunda klavyeyi anında gizle!
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    hideKeyboard()
                }
                false
            }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        hideKeyboard()
                    }
                }
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (searchInput.text.isNullOrEmpty() && !isRestoringScroll && (dx != 0 || dy != 0)) {
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
            onPreviewClick = { meme ->
                hideKeyboard()
                showInternalPlayer(meme)
            },
            onSendClick = { meme ->
                hideKeyboard()
                shareMemeMedia(meme)
            },
            onEditTagsClick = { meme ->
                hideKeyboard()
                showEditTagsDialog(meme)
            }
        )
        galleryRecycler.adapter = adapter

        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.parseColor("#38BDF8"), Color.parseColor("#16A34A"))
            setProgressBackgroundColorSchemeColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            ).apply { setMargins(dpToPx(8f), 0, dpToPx(8f), 0) }
            setOnRefreshListener { refreshGalleryOnPullDown() }
        }
        swipeRefresh.addView(galleryRecycler)
        mainContainer.addView(swipeRefresh)

        root.addView(mainContainer)
        setupInternalPlayerLayout(root)

        setContentView(root)
        try {
            window.statusBarColor = Color.parseColor("#1E293B")
            window.navigationBarColor = Color.parseColor("#0F172A")
            val insetsController = WindowCompat.getInsetsController(window, root)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false
        } catch (_: Throwable) {}

        setupBackButtonBehavior()
        checkPermissionAndLoadAlbums()
        observeScanServiceState()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim() ?: ""
                clearSearchBtn.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                filterList(q, restoreScrollWhenEmpty = q.isEmpty(), immediate = true)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        updateTabColors()
        startupClean()
    }

    private fun hideKeyboard() {
        searchInput.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun setupBackButtonBehavior() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val firstPos = gridLayoutManager.findFirstVisibleItemPosition()
                val firstTop = if (firstPos == 0) (gridLayoutManager.findViewByPosition(0)?.top ?: 0) else 0
                val isScrolledDown = firstPos > 0 || (firstPos == 0 && firstTop < -dpToPx(16f))

                when {
                    internalPlayerLayout?.visibility == View.VISIBLE -> hideInternalPlayer()
                    !searchInput.text.isNullOrEmpty() -> clearSearchAndRestorePosition()
                    currentFilter != 0 -> {
                        currentFilter = 0
                        updateTabColors()
                        filterList("", restoreScrollWhenEmpty = true, immediate = true)
                    }
                    isScrolledDown -> {
                        savedScrollPosition = 0
                        savedScrollOffset = 0
                        galleryRecycler.smoothScrollToPosition(0)
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
        hideKeyboard()
        filterList("", restoreScrollWhenEmpty = true, immediate = true)
    }

    private fun observeScanServiceState() {
        lifecycleScope.launch {
            ScanForegroundService.stateFlow.collect { st ->
                scanControlRow.visibility = if (st.isRunning) View.VISIBLE else View.GONE
                pauseResumeBtn.text = if (st.isPaused) "▶️ Devam Et" else "⏸️ Duraklat"
                if (st.statusMessage.isNotBlank()) {
                    statusText.text = st.statusMessage
                }
                if (st.refreshTick != lastObservedTick) {
                    lastObservedTick = st.refreshTick
                    if (st.refreshTick > 0) {
                        cachedAllMemes = null
                        VideoIndexer.clearAllCaches()
                        filterList(searchInput.text.toString().trim(), restoreScrollWhenEmpty = false, immediate = true)
                    }
                }
            }
        }
    }

    private fun startupClean() {
        lifecycleScope.launch {
            VideoIndexer(this@MainActivity).cleanExistingDatabaseRecords()
            cachedAllMemes = null
            VideoIndexer.clearAllCaches()
            filterList(searchInput.text.toString().trim(), restoreScrollWhenEmpty = false, immediate = true)
        }
    }

    private fun refreshGalleryOnPullDown() {
        lifecycleScope.launch {
            VideoIndexer(this@MainActivity).cleanExistingDatabaseRecords()
            cachedAllMemes = null
            VideoIndexer.clearAllCaches()
            filterList(searchInput.text.toString().trim(), restoreScrollWhenEmpty = false, immediate = true)
            swipeRefresh.isRefreshing = false
            if (!ScanForegroundService.stateFlow.value.isRunning) {
                loadAlbumsIntoSpinner()
                startIncrementalScanService()
                Toast.makeText(this@MainActivity, "🔄 Galeri yenilendi!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "🔄 Liste güncellendi (tarama sürüyor)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startIncrementalScanService() {
        if (availableAlbums.isEmpty()) return
        val selectedAlbum = availableAlbums[albumSpinner.selectedItemPosition]
        val intent = Intent(this, ScanForegroundService::class.java).apply {
            action = ScanForegroundService.ACTION_START_SCAN
            putExtra(ScanForegroundService.EXTRA_ALBUM_NAME, selectedAlbum.name)
            putExtra(ScanForegroundService.EXTRA_ALBUM_IS_ALL, selectedAlbum.isAll)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startRepairService() {
        val intent = Intent(this, ScanForegroundService::class.java).apply {
            action = ScanForegroundService.ACTION_START_REPAIR
            if (availableAlbums.isNotEmpty() && albumSpinner.selectedItemPosition in availableAlbums.indices) {
                val selectedAlbum = availableAlbums[albumSpinner.selectedItemPosition]
                putExtra(ScanForegroundService.EXTRA_ALBUM_NAME, selectedAlbum.name)
                putExtra(ScanForegroundService.EXTRA_ALBUM_IS_ALL, selectedAlbum.isAll)
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun filterList(query: String, restoreScrollWhenEmpty: Boolean = false, immediate: Boolean = false) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            if (!immediate && query.isNotEmpty()) {
                delay(80) // Anında tepki + akıcı yazma
            }

            val resultPair = withContext(Dispatchers.Default) {
                val allCached = cachedAllMemes ?: MemeDatabase.getInstance(this@MainActivity).memeDao().getAllMemes().also {
                    cachedAllMemes = it
                }
                val typeList = when (currentFilter) {
                    1 -> allCached.filter { it.isVideo }
                    2 -> allCached.filter { !it.isVideo }
                    else -> allCached
                }
                val albumFiltered = if (selectedAlbumFilter == null) {
                    typeList
                } else {
                    typeList.filter { it.albumName == selectedAlbumFilter }
                }
                val finalItems = if (query.isEmpty()) {
                    albumFiltered
                } else {
                    VideoIndexer.smartSearchFilter(albumFiltered, query)
                }
                Pair(finalItems, albumFiltered.size)
            }

            adapter.submitList(resultPair.first) {
                if (query.isNotEmpty()) {
                    // Yazarken arama tuşuna basmadan en alakalı örnek videoları anında en üstte göster!
                    galleryRecycler.scrollToPosition(0)
                }
            }

            if (!ScanForegroundService.stateFlow.value.isRunning && selectedAlbumFilter != null && query.isEmpty()) {
                statusText.text = if (resultPair.second == 0) {
                    "📁 $selectedAlbumFilter: Henüz taranmadı ('Yenileri Tara ⚡' ile tarayabilirsin)"
                } else {
                    "📁 $selectedAlbumFilter: ${resultPair.second} medya hazır"
                }
            }

            if (query.isEmpty() && restoreScrollWhenEmpty && savedScrollPosition > 0) {
                isRestoringScroll = true
                val targetPos = savedScrollPosition
                val targetOffset = savedScrollOffset
                galleryRecycler.post {
                    gridLayoutManager.scrollToPositionWithOffset(targetPos, targetOffset)
                    galleryRecycler.post { isRestoringScroll = false }
                }
            }
        }
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
                filterList(searchInput.text.toString().trim(), immediate = true)
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

    /**
     * Videonun doğru repliğini / arama kelimesini 1 dokunuşta düzeltir!
     * Eğer o an arama kutusunda yazı varsa (ör: "orospu çocukları"), otomatik kutuya hazır getirir.
     */
    private fun showEditTagsDialog(meme: MemeVideo, prefillFromSearch: Boolean = false) {
        val currentQuery = searchInput.text?.toString()?.trim() ?: ""
        val currentSpoken = meme.spokenText.replace("\u200B", "").trim()
        val initialText = when {
            prefillFromSearch && currentQuery.isNotBlank() -> currentQuery
            currentSpoken.isNotBlank() -> currentSpoken
            else -> ""
        }

        val input = EditText(this).apply {
            hint = "Doğru replik veya arama kelimesi (Örn: orospu çocukları)"
            setText(initialText)
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        AlertDialog.Builder(this)
            .setTitle("✏️ Doğru Repliği / Başlığı Kaydet")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val corrected = input.text.toString().trim()
                if (corrected.isNotBlank()) {
                    lifecycleScope.launch {
                        val db = MemeDatabase.getInstance(this@MainActivity).memeDao()
                        db.updateSpokenText(meme.filePath, corrected)
                        val prefs = getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
                        val editedSet = prefs.getStringSet("user_edited_paths", emptySet())?.toMutableSet() ?: mutableSetOf()
                        editedSet.add(meme.filePath)
                        prefs.edit().putStringSet("user_edited_paths", editedSet).apply()
                        cachedAllMemes = null
                        VideoIndexer.invalidateCacheFor(meme.filePath)
                        Toast.makeText(this@MainActivity, "✅ Replik güncellendi!", Toast.LENGTH_SHORT).show()
                        filterList(searchInput.text.toString().trim(), immediate = true)
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
            setOnClickListener {
                if (isPlaying) pause() else start()
            }
        }

        internalImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(260f)
            )
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(14f) }
        }

        val fixBtn = Button(this).apply {
            text = "✏️ Repliği Düzelt"
            setTextColor(Color.WHITE)
            textSize = 12f
            isAllCaps = false
            setPadding(dpToPx(14f), 0, dpToPx(14f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(16f).toFloat()
                setColor(Color.parseColor("#D97706"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(38f)
            ).apply { rightMargin = dpToPx(10f) }
            setOnClickListener {
                currentPreviewMeme?.let { m ->
                    hideInternalPlayer()
                    showEditTagsDialog(m, prefillFromSearch = true)
                }
            }
        }

        val closeBtn = Button(this).apply {
            text = "✕ Kapat"
            setTextColor(Color.WHITE)
            textSize = 12f
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(16f).toFloat()
                setColor(Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(110f), dpToPx(38f))
            setOnClickListener { hideInternalPlayer() }
        }

        btnRow.addView(fixBtn)
        btnRow.addView(closeBtn)

        contentBox.addView(internalVideoView)
        contentBox.addView(internalImageView)
        contentBox.addView(btnRow)
        overlay.addView(contentBox)

        internalPlayerLayout = overlay
        root.addView(overlay)
    }

    private fun showInternalPlayer(meme: MemeVideo) {
        currentPreviewMeme = meme
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

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Meme Paylaş"))
    }

    private fun checkPermissionAndLoadAlbums() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
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
        loadAlbumsIntoSpinner()
    }

    private fun loadAlbumsIntoSpinner() {
        lifecycleScope.launch {
            val prevAlbumName = if (availableAlbums.isNotEmpty() && albumSpinner.selectedItemPosition in availableAlbums.indices) {
                availableAlbums[albumSpinner.selectedItemPosition].name
            } else {
                null
            }

            val indexer = VideoIndexer(this@MainActivity)
            availableAlbums = indexer.getAvailableAlbums()

            val names = availableAlbums.map { "${it.name} (${it.count} öğe)" }
            val spinnerAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            albumSpinner.adapter = spinnerAdapter

            if (prevAlbumName != null) {
                val idx = availableAlbums.indexOfFirst { it.name == prevAlbumName }
                if (idx >= 0) albumSpinner.setSelection(idx)
            }

            albumSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position in availableAlbums.indices) {
                        val chosen = availableAlbums[position]
                        selectedAlbumFilter = if (chosen.isAll) null else chosen.name
                        filterList(searchInput.text.toString().trim(), restoreScrollWhenEmpty = false, immediate = true)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            filterList(searchInput.text.toString().trim(), restoreScrollWhenEmpty = false, immediate = true)
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
