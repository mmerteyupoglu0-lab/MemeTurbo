package com.memeturbo.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.FileOutputStream
import kotlin.math.abs

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
    private var photoAutoAdvanceJob: Job? = null
    private var lastObservedTick = -1
    private var cachedAllMemes: List<MemeVideo>? = null
    private var currentFilteredList: List<MemeVideo> = emptyList()
    private var suppressNextSpinnerSelection = false

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
    private var sequentialToggleBtn: Button? = null
    private var currentPreviewMeme: MemeVideo? = null
    private var currentPreviewIndex: Int = -1
    private var isSequentialPlayEnabled: Boolean = false
    private var pendingPersonName: String = ""

    private val pickSelfPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && pendingPersonName.isNotBlank()) {
            lifecycleScope.launch {
                val savedPath = withContext(Dispatchers.IO) {
                    try {
                        val dest = File(filesDir, "self_person_ref.jpg")
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(dest).use { output ->
                                input.copyTo(output)
                            }
                        }
                        dest.absolutePath
                    } catch (_: Exception) { "" }
                }
                if (savedPath.isNotBlank()) {
                    getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("self_person_name", pendingPersonName)
                        .putString("self_person_photo", savedPath)
                        .apply()
                    Toast.makeText(
                        this@MainActivity,
                        "✅ '$pendingPersonName' kaydedildi! Fotoğraf taramalarında tanınacak.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private var pendingGPhotosAlbumName: String = "Google Foto Memeler"

    private val pickGPhotosLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { uris.add(it) }
            }
        }
        if (uris.isEmpty()) {
            data.data?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) {
            importUrisFromGooglePhotos(uris, pendingGPhotosAlbumName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val theme = AppStrings.getTheme(this)
        val strings = AppStrings.tr(this)

        val root = FrameLayout(this).apply {
            setBackgroundColor(theme.bgHex)
        }

        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16f), dpToPx(14f), dpToPx(16f), dpToPx(10f))
            setBackgroundColor(theme.headerHex)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "⚡ ReplikBul • MemeTurbo V17"
            textSize = 19f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val modeBadge = TextView(this).apply {
            val isGp = AppStrings.getGalleryMode(this@MainActivity) == "gphotos"
            text = if (isGp) "🌈 G.Foto Modu" else "📱 Klasör Modu"
            textSize = 10.5f
            setTextColor(Color.WHITE)
            setPadding(dpToPx(8f), dpToPx(4f), dpToPx(8f), dpToPx(4f))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(10f).toFloat()
                setColor(if (isGp) Color.parseColor("#EA580C") else theme.accentHex)
            }
            setOnClickListener { showOnboardingGalleryChoiceDialog(forceShow = true) }
        }

        titleRow.addView(title)
        titleRow.addView(modeBadge)

        val privacyBanner = TextView(this).apply {
            text = strings.subtitleBanner
            textSize = 11f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, dpToPx(4f), 0, dpToPx(6f))
        }

        val setupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(2f) }
        }

        val aiAlbumBtn = Button(this).apply {
            text = strings.btnAiAlbum
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12f).toFloat()
                setColor(Color.parseColor("#8B5CF6"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(35f), 1.15f).apply { rightMargin = dpToPx(5f) }
            setOnClickListener { showCreateAiAlbumDialog() }
        }

        val personTrainBtn = Button(this).apply {
            text = strings.btnPerson
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12f).toFloat()
                setColor(Color.parseColor("#0D9488"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(35f), 0.9f).apply { rightMargin = dpToPx(5f) }
            setOnClickListener { showPersonTrainDialog() }
        }

        val gPhotosBtn = Button(this).apply {
            text = strings.btnGPhotos
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12f).toFloat()
                setColor(Color.parseColor("#EA580C"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(35f), 0.95f).apply { rightMargin = dpToPx(5f) }
            setOnClickListener { showGooglePhotosImportDialog() }
        }

        val settingsBtn = Button(this).apply {
            text = strings.btnSettings
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12f).toFloat()
                setColor(Color.parseColor("#475569"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(35f), 1.0f)
            setOnClickListener { showSettingsDialog() }
        }

        setupRow.addView(aiAlbumBtn)
        setupRow.addView(personTrainBtn)
        setupRow.addView(gPhotosBtn)
        setupRow.addView(settingsBtn)
        header.addView(titleRow)
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
            text = strings.btnScanNew
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
            text = strings.btnAiAnalyze
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12f).toFloat()
                setColor(Color.parseColor("#7C3AED"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(40f), 1f)
            setOnClickListener { startRepairService() }
        }

        scanRow.addView(albumSpinner)
        scanRow.addView(scanBtn)
        scanRow.addView(repairBtn)
        mainContainer.addView(scanRow)

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

        tabAllBtn = createFilterTab(strings.tabAll, 0)
        tabVideosBtn = createFilterTab(strings.tabVideos, 1)
        tabImagesBtn = createFilterTab(strings.tabPhotos, 2)

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
                    clearFocus()
                }
                return super.onKeyPreIme(keyCode, event)
            }
        }.apply {
            hint = strings.searchHint
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            setPadding(0, dpToPx(10f), 0, dpToPx(10f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    filterList(text.toString().trim(), immediate = true)
                    hideKeyboard()
                    true
                } else false
            }
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

        val tabSwipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > dpToPx(75f) && abs(dx) > abs(dy) * 1.4f && abs(velocityX) > 250f) {
                    if (dx < 0 && currentFilter < 2) {
                        switchTab(currentFilter + 1)
                        return true
                    } else if (dx > 0 && currentFilter > 0) {
                        switchTab(currentFilter - 1)
                        return true
                    }
                }
                return false
            }
        })

        gridLayoutManager = GridLayoutManager(this, AppStrings.getGridColumns(this))
        galleryRecycler = RecyclerView(this).apply {
            layoutManager = gridLayoutManager
            setHasFixedSize(true)
            itemAnimator = null
            setItemViewCacheSize(18)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    hideKeyboard()
                }
                tabSwipeDetector.onTouchEvent(event)
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
            window.statusBarColor = theme.headerHex
            window.navigationBarColor = theme.bgHex
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
                filterList(q, restoreScrollWhenEmpty = q.isEmpty(), immediate = q.isEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        updateTabColors()
        startupClean()
        showOnboardingGalleryChoiceDialog(forceShow = false)
    }

    private fun showOnboardingGalleryChoiceDialog(forceShow: Boolean) {
        val prefs = getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
        if (!forceShow && prefs.getBoolean("onboarding_done_v17", false)) return

        val strings = AppStrings.tr(this)
        val currentMode = AppStrings.getGalleryMode(this)
        val theme = AppStrings.getTheme(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(18f), dpToPx(12f), dpToPx(18f), dpToPx(8f))
        }

        val infoText = TextView(this).apply {
            text = strings.onboardingMsg
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 0, 0, dpToPx(12f))
        }
        container.addView(infoText)

        var dialogRef: AlertDialog? = null

        fun buildChoiceCard(iconTitle: String, desc: String, modeValue: String, accentColor: Int): LinearLayout {
            val isSelected = currentMode == modeValue
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(14f), dpToPx(12f), dpToPx(14f), dpToPx(12f))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(16f).toFloat()
                    setColor(theme.cardHex)
                    setStroke(dpToPx(if (isSelected) 2.5f else 1.2f), if (isSelected) accentColor else theme.cardStrokeHex)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpToPx(10f) }

                val tView = TextView(this@MainActivity).apply {
                    text = if (isSelected) "$iconTitle  ✅" else iconTitle
                    textSize = 14.5f
                    setTextColor(Color.WHITE)
                }
                val dView = TextView(this@MainActivity).apply {
                    text = desc
                    textSize = 12f
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(0, dpToPx(4f), 0, 0)
                }
                addView(tView)
                addView(dView)

                setOnClickListener {
                    prefs.edit()
                        .putString("gallery_source_mode", modeValue)
                        .putBoolean("onboarding_done_v17", true)
                        .apply()
                    dialogRef?.dismiss()
                    if (modeValue == "gphotos") {
                        selectedAlbumFilter = VideoIndexer.AI_ALBUM_FUNNY
                        Toast.makeText(
                            this@MainActivity,
                            "🌈 Google Fotoğraflar & Akıllı AI Albüm modu aktif! Karışık videolar otomatik gruplandı.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        selectedAlbumFilter = null
                        Toast.makeText(
                            this@MainActivity,
                            "📱 Telefon Galerisi (Klasör Modu) aktif!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    recreate()
                }
            }
        }

        container.addView(
            buildChoiceCard(
                "📱 Telefonun Kendi Galerisi (Klasör Modu)",
                "WhatsApp, Download, Snaptube, kart gibi klasörleri ayrı ayrı albüm olarak gösterir.",
                "device",
                Color.parseColor("#0284C7")
            )
        )

        container.addView(
            buildChoiceCard(
                "🌈 Google Fotoğraflar (Akıllı AI & Bulut Modu)",
                "Normal galerisinde ayrı klasörler olmayanlar için karışık videoları '😂 Komik & Meme', '🗣️ Replikli', '🎮 Oyun' diye otomatik albümler + Google Fotoğraflar bulut seçicisini bağlar.",
                "gphotos",
                Color.parseColor("#EA580C")
            )
        )

        dialogRef = AlertDialog.Builder(this)
            .setTitle(strings.onboardingTitle)
            .setView(container)
            .setCancelable(forceShow)
            .apply {
                if (forceShow) {
                    setNegativeButton("Kapat", null)
                }
            }
            .create()

        dialogRef.show()
    }

    private fun showCreateAiAlbumDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(18f), dpToPx(10f), dpToPx(18f), dpToPx(6f))
        }

        val desc = TextView(this).apply {
            text = "Karman çorman galerideki taranmış videoları ve fotoğrafları amacına göre filtreleyip sana özel kalıcı bir AI albümü oluşturur.\n\nHazır bir şablon seç veya istediğin albümü aşağıya yaz:"
            textSize = 12.5f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, 0, 0, dpToPx(8f))
        }
        container.addView(desc)

        val nameInput = EditText(this).apply {
            hint = "Albüm Adı (Örn: Komik & Küfürlü Memeler)"
            setText("Komik & Küfürlü Memeler")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 13.5f
        }

        val goalInput = EditText(this).apply {
            hint = "Ne tür videolar/fotoğraflar olsun? (Örn: komik, küfürlü, saçma, indirilen memeler)"
            setText("komik küfürlü saçma eğlenceli indirilen meme videoları")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 13f
        }

        val presetsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(4f), 0, dpToPx(8f))
        }

        val presets = listOf(
            "😂 Komik, Küfürlü & İndirilen Memeler" to ("Komik & Küfürlü Memeler" to "komik küfürlü saçma eğlenceli indirilen meme videoları"),
            "🎮 Oyun & Edit Kesitleri" to ("Oyun & Editler" to "oyun game bannerlord lol minecraft gta edit kesit"),
            "🤳 Ben, Arkadaşlar & Ortam" to ("Ben ve Arkadaşlar" to "kişi arkadaş mert ben selfie özçekim portre grup"),
            "📚 Ders Notları, Sınav & Belgeler" to ("Ders & Belgeler" to "ders not sınav belge kağıt pdf kitap")
        )

        for ((btnTitle, pair) in presets) {
            val b = Button(this).apply {
                text = btnTitle
                textSize = 11f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(10f).toFloat()
                    setColor(Color.parseColor("#334155"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(34f)
                ).apply { bottomMargin = dpToPx(5f) }
                setOnClickListener {
                    nameInput.setText(pair.first)
                    goalInput.setText(pair.second)
                }
            }
            presetsLayout.addView(b)
        }

        container.addView(presetsLayout)
        container.addView(nameInput)
        container.addView(goalInput)

        val customAlbums = AppStrings.getSavedCustomAiAlbums(this)

        AlertDialog.Builder(this)
            .setTitle("✨ Bana Özel AI Albüm Oluştur")
            .setView(container)
            .setPositiveButton("🤖 AI Albümü Oluştur") { _, _ ->
                val albTitle = nameInput.text.toString().trim().ifBlank { "Komik Memeler" }
                val goal = goalInput.text.toString().trim().ifBlank { albTitle }
                buildCustomAiAlbumNow(albTitle, goal)
            }
            .apply {
                if (customAlbums.isNotEmpty()) {
                    setNeutralButton("🗑️ Özel Albüm Sil") { _, _ ->
                        val titles = customAlbums.keys.toTypedArray()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Silinecek AI Albümü Seç")
                            .setItems(titles) { _, which ->
                                AppStrings.deleteCustomAiAlbum(this@MainActivity, titles[which])
                                selectedAlbumFilter = null
                                loadAlbumsIntoSpinner()
                                filterList("", immediate = true)
                                Toast.makeText(this@MainActivity, "🗑️ ${titles[which]} silindi.", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    }
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun buildCustomAiAlbumNow(albTitle: String, goalPrompt: String) {
        lifecycleScope.launch {
            statusText.text = "🤖 '$albTitle' için taranmış medyalar AI ile filtreleniyor..."
            val all = cachedAllMemes ?: withContext(Dispatchers.IO) {
                MemeDatabase.getInstance(this@MainActivity).memeDao().getAllMemes()
            }.also { cachedAllMemes = it }

            if (all.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "⚠️ Henüz taranmış medya yok! Önce 'Yenileri Tara ⚡' butonuna bas.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val matchedPaths = OnlineAiAnalyzer.selectItemsForCustomAiAlbum(
                context = this@MainActivity,
                userGoal = goalPrompt,
                allMemes = all
            )

            if (matchedPaths.isEmpty()) {
                statusText.text = "⚠️ '$goalPrompt' kriterine uygun medya bulunamadı."
                Toast.makeText(this@MainActivity, "⚠️ Eşleşen medya bulunamadı, farklı kelimeler dene.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val fullTitle = if (albTitle.startsWith("🤖 ")) albTitle else "🤖 $albTitle"
            AppStrings.saveCustomAiAlbum(this@MainActivity, fullTitle, matchedPaths)
            selectedAlbumFilter = fullTitle
            loadAlbumsIntoSpinner()
            filterList("", restoreScrollWhenEmpty = false, immediate = true)
            statusText.text = "✨ '$fullTitle' hazır! (${matchedPaths.size} medya seçildi)"
            Toast.makeText(
                this@MainActivity,
                "✅ '$fullTitle' oluşturuldu (${matchedPaths.size} medya)!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
        val strings = AppStrings.tr(this)
        val curTheme = AppStrings.getTheme(this)
        val curCols = AppStrings.getGridColumns(this)
        val curCompact = AppStrings.isCompactGalleryCard(this)
        val curMode = if (AppStrings.getGalleryMode(this) == "gphotos") "🌈 Google Fotoğraflar Modu" else "📱 Telefon Galerisi Modu"
        val curLang = AppStrings.getLang(this).uppercase()
        val autoMultiLang = AppStrings.isAutoMultiLangAi(this)
        val overlayOk = Settings.canDrawOverlays(this)

        val options = arrayOf(
            "1. Galeri Kaynağı: $curMode (Değiştir)",
            "2. Arayüz Teması: ${curTheme.nameTr}",
            "3. Izgara Sütun Sayısı: $curCols Sütun (1 / 2 / 3 Sütun)",
            "4. Kart Görünüm Stili: ${if (curCompact) "Kompakt Galeri" else "Modern Rozetli Kart"}",
            "5. Uygulama Dili (8 Global Dil): $curLang",
            "6. AI Ses Dil Algılama: ${if (autoMultiLang) "🌍 Otomatik Çoklu Dil (TR+EN+Global)" else "🇹🇷 Sadece Seçili Dil"}",
            "7. Yüzen Pencere İzni: ${if (overlayOk) "✅ Açık" else "⚠️ İzin Ver"}",
            "8. Çift Tık Klavye İzni (Erişilebilirlik Ayarı)",
            "9. ☁️ Google Fotoğraflar'dan Medya / Albüm İçe Aktar"
        )

        AlertDialog.Builder(this)
            .setTitle(strings.settingsTitle)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showOnboardingGalleryChoiceDialog(forceShow = true)
                    1 -> {
                        val themeNames = AppStrings.themes.map { it.nameTr }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("🎨 Arayüz Teması Seç")
                            .setItems(themeNames) { _, idx ->
                                prefs.edit().putInt("ui_theme_id", idx).apply()
                                recreate()
                            }
                            .show()
                    }
                    2 -> {
                        val colOptions = arrayOf(
                            "1 Sütun (Sinematik Büyük Görünüm)",
                            "2 Sütun (Standart Modern Meme Izgarası)",
                            "3 Sütun (Google Fotoğraflar / Kompakt Galeri Izgarası)"
                        )
                        AlertDialog.Builder(this)
                            .setTitle("📐 Sütun Sayısı Seç")
                            .setItems(colOptions) { _, idx ->
                                prefs.edit().putInt("ui_grid_columns", idx + 1).apply()
                                recreate()
                            }
                            .show()
                    }
                    3 -> {
                        prefs.edit().putBoolean("ui_compact_card", !curCompact).apply()
                        recreate()
                    }
                    4 -> {
                        val langs = arrayOf(
                            "🇹🇷 Türkçe (TR)" to "tr",
                            "🇬🇧 English (EN)" to "en",
                            "🇫🇷 Français (FR)" to "fr",
                            "🇮🇹 Italiano (IT)" to "it",
                            "🇪🇸 Español (ES)" to "es",
                            "🇷🇺 Русский (RU)" to "ru",
                            "🇩🇪 Deutsch (DE)" to "de",
                            "🇸🇦 العربية (AR)" to "ar"
                        )
                        AlertDialog.Builder(this)
                            .setTitle("🌍 Global Dil Seçimi")
                            .setItems(langs.map { it.first }.toTypedArray()) { _, idx ->
                                prefs.edit().putString("app_lang", langs[idx].second).apply()
                                recreate()
                            }
                            .show()
                    }
                    5 -> {
                        val nextVal = !autoMultiLang
                        prefs.edit().putBoolean("ai_auto_multilang", nextVal).apply()
                        val msg = if (nextVal) {
                            "🌍 Otomatik Çoklu Dil aktif! Türkçe, İngilizce ve diğer dillerdeki videolar otomatik algılanır."
                        } else {
                            "🇹🇷 Sabit dil modu aktif!"
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                    6 -> {
                        if (!Settings.canDrawOverlays(this)) {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        } else {
                            Toast.makeText(this, "✅ Yüzen pencere izni zaten açık!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    7 -> showXiaomiPermissionGuide()
                    8 -> showGooglePhotosImportDialog()
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showPersonTrainDialog() {
        val prefs = getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("self_person_name", "") ?: ""
        val savedPhoto = prefs.getString("self_person_photo", "") ?: ""

        val input = EditText(this).apply {
            hint = "Adın (Örn: Mert veya Ben)"
            setText(savedName.ifBlank { "Mert" })
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val statusInfo = if (savedName.isNotBlank() && savedPhoto.isNotBlank()) {
            "Kayıtlı kişi: $savedName ✅\nFotoğrafını güncellemek veya değiştirmek için aşağıdan yeni fotoğraf seçebilirsin."
        } else {
            "Kendi yüzünün net göründüğü 1 fotoğraf seçersen, AI taraması seni ve arkadaşlarını ('Mert ve arkadaşları...') tüm fotoğraflarda tanır!"
        }

        AlertDialog.Builder(this)
            .setTitle("👤 Kişi / Yüz Tanıt")
            .setMessage(statusInfo)
            .setView(input)
            .setPositiveButton("📷 Galeriden Fotoğraf Seç") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    pendingPersonName = name
                    pickSelfPhotoLauncher.launch("image/*")
                } else {
                    Toast.makeText(this, "Lütfen bir isim yaz!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("🗑️ Temizle") { _, _ ->
                prefs.edit().remove("self_person_name").remove("self_person_photo").apply()
                Toast.makeText(this, "Kayıtlı kişi temizlendi.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun switchTab(newFilter: Int) {
        currentFilter = newFilter
        updateTabColors()
        filterList(searchInput.text.toString().trim(), immediate = true)
    }

    private fun showGooglePhotosImportDialog() {
        val input = EditText(this).apply {
            hint = "Albüm adı (Örn: Google Foto Memeler)"
            setText(pendingGPhotosAlbumName)
            setSelection(text.length)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        AlertDialog.Builder(this)
            .setTitle("☁️ Google Fotoğraflar & AI Albüm")
            .setMessage(
                "• Google Fotoğraflar'ın bulutta yedeklediği veya kendi AI'ı ile grupladığı video/fotoğrafları seçip MemeTurbo'ya özel albüm olarak aktarabilirsin (çoklu seçim yapılabilir).\n\n" +
                "• Ayrıca normal galerisinde ayrı klasörler olmayanlar için albüm listesine otomatik:\n" +
                "  🗣️ [AI Albüm] Replikli Videolar\n" +
                "  📝 [AI Albüm] Yazılı & Altyazılılar\n" +
                "  🏷️ [AI Albüm] Kişi / Oyun / Sahne\n" +
                "sanal albümleri eklendi!"
            )
            .setView(input)
            .setPositiveButton("☁️ Google Fotoğraflar'dan Seç") { _, _ ->
                val rawName = input.text.toString().trim().ifBlank { "Google Foto Memeler" }
                pendingGPhotosAlbumName = rawName.replace(Regex("[\\/:*?\"<>|]"), "_").take(32)
                launchGooglePhotosPicker(preferGooglePhotosApp = true)
            }
            .setNeutralButton("📂 Sistem Bulut Seçici") { _, _ ->
                val rawName = input.text.toString().trim().ifBlank { "Google Foto Memeler" }
                pendingGPhotosAlbumName = rawName.replace(Regex("[\\/:*?\"<>|]"), "_").take(32)
                launchGooglePhotosPicker(preferGooglePhotosApp = false)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun launchGooglePhotosPicker(preferGooglePhotosApp: Boolean) {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
                if (preferGooglePhotosApp) {
                    setPackage("com.google.android.apps.photos")
                }
            }
            pickGPhotosLauncher.launch(intent)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            pickGPhotosLauncher.launch(Intent.createChooser(fallback, "Medyaları Seç (Uzun basarak çoklu seçebilirsin)"))
        }
    }

    private fun importUrisFromGooglePhotos(uris: List<Uri>, albumFolderName: String) {
        lifecycleScope.launch {
            statusText.text = "☁️ Google Fotoğraflar'dan ${uris.size} medya aktarılıyor..."
            val importedItems = withContext(Dispatchers.IO) {
                val destDir = File(File(filesDir, "gphotos_albums"), albumFolderName)
                if (!destDir.exists()) destDir.mkdirs()
                val db = MemeDatabase.getInstance(this@MainActivity).memeDao()
                val list = mutableListOf<MemeVideo>()

                for ((idx, uri) in uris.withIndex()) {
                    try {
                        val mime = contentResolver.getType(uri) ?: ""
                        val isVid = mime.startsWith("video") || uri.toString().contains("video", ignoreCase = true)
                        val ext = if (isVid) "mp4" else "jpg"
                        val fileName = "gphoto_${System.currentTimeMillis()}_$idx.$ext"
                        val destFile = File(destDir, fileName)
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (destFile.exists() && destFile.length() > 0L) {
                            list.add(
                                MemeVideo(
                                    filePath = destFile.absolutePath,
                                    fileName = destFile.nameWithoutExtension,
                                    durationMs = 0L,
                                    spokenText = "",
                                    screenText = "",
                                    visualLabels = "",
                                    isVideo = isVid,
                                    lastModified = System.currentTimeMillis(),
                                    albumName = "☁️ $albumFolderName"
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }
                if (list.isNotEmpty()) {
                    db.insertMemesBatch(list)
                }
                list
            }

            if (importedItems.isNotEmpty()) {
                cachedAllMemes = null
                selectedAlbumFilter = "☁️ $albumFolderName"
                loadAlbumsIntoSpinner()
                filterList("", restoreScrollWhenEmpty = false, immediate = true)
                statusText.text = "✅ ${importedItems.size} medya '☁️ $albumFolderName' albümüne eklendi! ('Yenileri Tara ⚡' ile çözümleyebilirsin)."
                Toast.makeText(
                    this@MainActivity,
                    "✅ ${importedItems.size} medya Google Fotoğraflar'dan aktarıldı!",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                statusText.text = "⚠️ Medya aktarılamadı, tekrar deneyin."
            }
        }
    }

    private fun hideKeyboard() {
        if (!searchInput.hasFocus()) return
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
                    currentFilter != 0 -> switchTab(0)
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
                        val currentQuery = searchInput.text?.toString()?.trim() ?: ""
                        if (!searchInput.hasFocus() || !st.isRunning) {
                            filterList(currentQuery, restoreScrollWhenEmpty = false, immediate = false)
                        }
                    }
                }
            }
        }
    }

    private fun startupClean() {
        lifecycleScope.launch {
            val prefs = getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
            val alreadyCleaned = prefs.getBoolean("db_cleaned_v16", false)
            if (!alreadyCleaned) {
                withContext(Dispatchers.IO) {
                    VideoIndexer(this@MainActivity).cleanExistingDatabaseRecordsFast()
                }
                prefs.edit().putBoolean("db_cleaned_v16", true).apply()
                cachedAllMemes = null
            }
            filterList(searchInput.text.toString().trim(), restoreScrollWhenEmpty = false, immediate = true)
        }
    }

    /**
     * V15.0 Yenileme:
     * Aşağı çekince taramayı OTOMATİK BAŞLATMAZ!
     * Listeyi günceller ve seçili albümde taranacak kaç yeni medya kaldığını yazar.
     */
    private fun refreshGalleryOnPullDown() {
        swipeRefresh.isRefreshing = false
        lifecycleScope.launch {
            cachedAllMemes = null
            VideoIndexer.clearAllCaches()
            loadAlbumsIntoSpinner()
            val selectedAlbum = if (availableAlbums.isNotEmpty() && albumSpinner.selectedItemPosition in availableAlbums.indices) {
                availableAlbums[albumSpinner.selectedItemPosition]
            } else {
                VideoIndexer.AlbumInfo("🌟 Tüm Galeri (Hepsi)", 0, isAll = true)
            }

            val pendingCount = withContext(Dispatchers.IO) {
                val indexer = VideoIndexer(this@MainActivity)
                val db = MemeDatabase.getInstance(this@MainActivity).memeDao()
                val existing = db.getAllIndexedPaths().toHashSet()
                val media = indexer.queryGalleryMediaPublic(selectedAlbum)
                media.count { it.path !in existing }
            }

            filterList(searchInput.text.toString().trim(), restoreScrollWhenEmpty = false, immediate = true)

            if (!ScanForegroundService.stateFlow.value.isRunning) {
                statusText.text = if (pendingCount > 0) {
                    "🔄 Yenilendi! Taranacak $pendingCount yeni medya var ('Yenileri Tara ⚡' ile tarayabilirsin)."
                } else {
                    "✨ Galeri yenilendi! Taranacak yeni medya yok."
                }
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
        val albumLabel = if (availableAlbums.isNotEmpty() && albumSpinner.selectedItemPosition in availableAlbums.indices) {
            availableAlbums[albumSpinner.selectedItemPosition].name
        } else {
            "Tüm Galeri"
        }

        val onlineStatus = if (OnlineAiAnalyzer.isOnline(this)) {
            "🟢 İnternet bağlı: Çoklu Ajan (Whisper AAC + Mantık Düzeltme + Qwen 3.8 Görsel/Kişi Tanıma) aktif!"
        } else {
            "🟠 İnternet bağlı değil: Çevrimdışı 3-Kare Altyazı OCR + Vosk motoru kullanılacak."
        }

        AlertDialog.Builder(this)
            .setTitle("🌐 V15 Çoklu Ajan & Fotoğraf Tanıma")
            .setMessage(
                "Seçili albüm: $albumLabel\n\n" +
                "$onlineStatus\n\n" +
                "• Daha önce AI ile taranmış olanlara TEKRAR dokunmaz, sadece eksik kalanları çözer.\n" +
                "• Ücretsiz Groq Vision kotası dakikada ~3–4 fotoğraf işlediği için fotoğraflar biraz yavaş ama tam detaylı taranır.\n" +
                "• ✏️ ile elle düzelttiğin kayıtlara kesinlikle dokunulmaz."
            )
            .setPositiveButton("⚡ Eksik & Fotoğrafları Çöz") { _, _ ->
                launchRepairIntent(forceFullAlbum = false)
            }
            .setNeutralButton("🔥 Tüm Albümü Baştan Yenile") { _, _ ->
                launchRepairIntent(forceFullAlbum = true)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun launchRepairIntent(forceFullAlbum: Boolean) {
        val intent = Intent(this, ScanForegroundService::class.java).apply {
            action = ScanForegroundService.ACTION_START_REPAIR
            putExtra(ScanForegroundService.EXTRA_FORCE_FULL_ALBUM, forceFullAlbum)
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
                delay(110)
            }

            val allCached = cachedAllMemes ?: withContext(Dispatchers.IO) {
                MemeDatabase.getInstance(this@MainActivity).memeDao().getAllMemes()
            }.also {
                cachedAllMemes = it
            }

            val resultPair = withContext(Dispatchers.Default) {
                val typeList = when (currentFilter) {
                    1 -> allCached.filter { it.isVideo }
                    2 -> allCached.filter { !it.isVideo }
                    else -> allCached
                }
                val customAlbumsMap = AppStrings.getSavedCustomAiAlbums(this@MainActivity)
                val albumFiltered = when {
                    selectedAlbumFilter == null -> typeList
                    selectedAlbumFilter == VideoIndexer.AI_ALBUM_FUNNY -> typeList.filter {
                        AppStrings.isFunnyOrDownloadedMeme(it)
                    }
                    selectedAlbumFilter == VideoIndexer.AI_ALBUM_SPEECH -> typeList.filter {
                        it.isVideo && it.spokenText.isNotBlank() && !OnlineAiAnalyzer.isMusicOrEmptyTranscript(it.spokenText)
                    }
                    selectedAlbumFilter == VideoIndexer.AI_ALBUM_SUBTITLE -> typeList.filter {
                        it.screenText.isNotBlank()
                    }
                    selectedAlbumFilter == VideoIndexer.AI_ALBUM_VISUAL -> typeList.filter {
                        it.visualLabels.isNotBlank()
                    }
                    selectedAlbumFilter in customAlbumsMap -> {
                        val pathSet = customAlbumsMap[selectedAlbumFilter] ?: emptySet()
                        typeList.filter { it.filePath in pathSet }
                    }
                    else -> typeList.filter { it.albumName == selectedAlbumFilter }
                }
                val finalItems = if (query.isEmpty()) {
                    albumFiltered
                } else {
                    VideoIndexer.smartSearchFilter(albumFiltered, query)
                }
                VideoIndexer.prewarmDisplaySnippets(finalItems)
                Pair(finalItems, albumFiltered.size)
            }

            currentFilteredList = resultPair.first
            adapter.submitList(resultPair.first) {
                if (query.isNotEmpty()) {
                    galleryRecycler.scrollToPosition(0)
                } else if (restoreScrollWhenEmpty && savedScrollPosition > 0) {
                    isRestoringScroll = true
                    val targetPos = savedScrollPosition
                    val targetOffset = savedScrollOffset
                    galleryRecycler.post {
                        gridLayoutManager.scrollToPositionWithOffset(targetPos, targetOffset)
                        galleryRecycler.post { isRestoringScroll = false }
                    }
                }
            }

            if (!ScanForegroundService.stateFlow.value.isRunning && selectedAlbumFilter != null && query.isEmpty()) {
                statusText.text = if (resultPair.second == 0) {
                    "📁 $selectedAlbumFilter: Henüz taranmadı ('Yenileri Tara ⚡' ile tarayabilirsin)"
                } else {
                    "📁 $selectedAlbumFilter: ${resultPair.second} medya hazır"
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
            setOnClickListener { switchTab(filterId) }
        }
    }

    private fun updateTabColors() {
        val theme = AppStrings.getTheme(this)
        val activeColor = theme.accentHex
        val inactiveColor = theme.cardHex

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
                        withContext(Dispatchers.IO) {
                            val db = MemeDatabase.getInstance(this@MainActivity).memeDao()
                            db.updateSpokenText(meme.filePath, corrected)
                        }
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
            ).apply { setMargins(dpToPx(16f), 0, dpToPx(16f), 0) }
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

        val btnRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(12f) }
        }

        val btnRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(8f) }
        }

        val prevBtn = Button(this).apply {
            text = "⏮️ Önceki"
            setTextColor(Color.WHITE)
            textSize = 11f
            isAllCaps = false
            setPadding(dpToPx(10f), 0, dpToPx(10f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(16f).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(36f)
            ).apply { rightMargin = dpToPx(6f) }
            setOnClickListener { playAdjacentMedia(-1) }
        }

        sequentialToggleBtn = Button(this).apply {
            text = "🔁 Sırayla: KAPALI"
            setTextColor(Color.WHITE)
            textSize = 11f
            isAllCaps = false
            setPadding(dpToPx(10f), 0, dpToPx(10f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(16f).toFloat()
                setColor(Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(36f)
            ).apply { rightMargin = dpToPx(6f) }
            setOnClickListener {
                isSequentialPlayEnabled = !isSequentialPlayEnabled
                updateSequentialButtonUi()
                currentPreviewMeme?.let { showInternalPlayer(it) }
            }
        }

        val nextBtn = Button(this).apply {
            text = "⏭️ Sıradaki"
            setTextColor(Color.WHITE)
            textSize = 11f
            isAllCaps = false
            setPadding(dpToPx(10f), 0, dpToPx(10f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(16f).toFloat()
                setColor(Color.parseColor("#0284C7"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(36f)
            )
            setOnClickListener { playAdjacentMedia(+1) }
        }

        val aiSingleBtn = Button(this).apply {
            text = "🌐 AI ile Bul"
            setTextColor(Color.WHITE)
            textSize = 11f
            isAllCaps = false
            setPadding(dpToPx(12f), 0, dpToPx(12f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(16f).toFloat()
                setColor(Color.parseColor("#7C3AED"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(36f)
            ).apply { rightMargin = dpToPx(8f) }
            setOnClickListener {
                val target = currentPreviewMeme ?: return@setOnClickListener
                if (!OnlineAiAnalyzer.isOnline(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "⚠️ İnternet bağlantısı gerekli!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Toast.makeText(this@MainActivity, "🌐 AI + Görsel Tanıma çözümlüyor...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val file = File(target.filePath)
                    val indexer = VideoIndexer(this@MainActivity)
                    val aiRes = if (target.isVideo) {
                        val (_, localOcr, _) = indexer.extractLocalVideoOcrAndScene(file)
                        OnlineAiAnalyzer.analyzeVideoWithAi(
                            this@MainActivity,
                            file,
                            indexer,
                            localOcrHint = localOcr,
                            forceVision = true
                        )
                    } else {
                        OnlineAiAnalyzer.analyzeImageWithAi(this@MainActivity, file, localOcrHint = target.screenText)
                    }
                    if (aiRes != null) {
                        withContext(Dispatchers.IO) {
                            val db = MemeDatabase.getInstance(this@MainActivity).memeDao()
                            db.updateFields(
                                path = target.filePath,
                                spokenText = aiRes.spokenText,
                                screenText = aiRes.screenText,
                                visualLabels = aiRes.visualLabels
                            )
                        }
                        val prefs = getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
                        val editedSet = prefs.getStringSet("user_edited_paths", emptySet())?.toMutableSet() ?: mutableSetOf()
                        editedSet.add(target.filePath)
                        prefs.edit().putStringSet("user_edited_paths", editedSet).apply()
                        cachedAllMemes = null
                        VideoIndexer.invalidateCacheFor(target.filePath)
                        val summary = aiRes.spokenText.ifBlank { aiRes.screenText.ifBlank { aiRes.visualLabels } }
                        Toast.makeText(this@MainActivity, "✅ Bulundu: $summary", Toast.LENGTH_LONG).show()
                        filterList(searchInput.text.toString().trim(), immediate = true)
                    } else {
                        Toast.makeText(this@MainActivity, "⚠️ Sonuç çıkarılamadı, tekrar deneyin.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val fixBtn = Button(this).apply {
            text = "✏️ Elle Düzelt"
            setTextColor(Color.WHITE)
            textSize = 11f
            isAllCaps = false
            setPadding(dpToPx(12f), 0, dpToPx(12f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(16f).toFloat()
                setColor(Color.parseColor("#D97706"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(36f)
            ).apply { rightMargin = dpToPx(8f) }
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
            textSize = 11f
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(16f).toFloat()
                setColor(Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(86f), dpToPx(36f))
            setOnClickListener { hideInternalPlayer() }
        }

        btnRow1.addView(prevBtn)
        sequentialToggleBtn?.let { btnRow1.addView(it) }
        btnRow1.addView(nextBtn)

        btnRow2.addView(aiSingleBtn)
        btnRow2.addView(fixBtn)
        btnRow2.addView(closeBtn)

        contentBox.addView(internalVideoView)
        contentBox.addView(internalImageView)
        contentBox.addView(btnRow1)
        contentBox.addView(btnRow2)
        overlay.addView(contentBox)

        internalPlayerLayout = overlay
        root.addView(overlay)
    }

    private fun updateSequentialButtonUi() {
        sequentialToggleBtn?.apply {
            text = if (isSequentialPlayEnabled) "🔁 Sırayla: AÇIK" else "🔁 Sırayla: KAPALI"
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(16f).toFloat()
                setColor(if (isSequentialPlayEnabled) Color.parseColor("#16A34A") else Color.parseColor("#334155"))
            }
        }
    }

    private fun playAdjacentMedia(offset: Int) {
        if (currentFilteredList.isEmpty()) return
        val curIdx = if (currentPreviewIndex in currentFilteredList.indices) {
            currentPreviewIndex
        } else {
            currentFilteredList.indexOfFirst { it.filePath == currentPreviewMeme?.filePath }
        }
        if (curIdx == -1) return
        val nextIdx = (curIdx + offset + currentFilteredList.size) % currentFilteredList.size
        showInternalPlayer(currentFilteredList[nextIdx])
    }

    private fun showInternalPlayer(meme: MemeVideo) {
        photoAutoAdvanceJob?.cancel()
        currentPreviewMeme = meme
        currentPreviewIndex = currentFilteredList.indexOfFirst { it.filePath == meme.filePath }
        val overlay = internalPlayerLayout ?: return
        val vView = internalVideoView ?: return
        val iView = internalImageView ?: return

        overlay.visibility = View.VISIBLE
        if (meme.isVideo) {
            iView.visibility = View.GONE
            vView.visibility = View.VISIBLE
            vView.setVideoPath(meme.filePath)
            vView.setOnPreparedListener { mp ->
                mp.isLooping = !isSequentialPlayEnabled
            }
            vView.setOnCompletionListener {
                if (isSequentialPlayEnabled) {
                    playAdjacentMedia(+1)
                }
            }
            vView.start()
        } else {
            vView.stopPlayback()
            vView.visibility = View.GONE
            iView.visibility = View.VISIBLE
            iView.setImageBitmap(null)
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(meme.filePath, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) {
                            sample *= 2
                        }
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        BitmapFactory.decodeFile(meme.filePath, opts)
                    } catch (_: Exception) { null }
                }
                if (bmp != null && currentPreviewMeme?.filePath == meme.filePath) {
                    iView.setImageBitmap(bmp)
                }
            }
            if (isSequentialPlayEnabled) {
                photoAutoAdvanceJob = lifecycleScope.launch {
                    delay(3000L)
                    if (isSequentialPlayEnabled && internalPlayerLayout?.visibility == View.VISIBLE) {
                        playAdjacentMedia(+1)
                    }
                }
            }
        }
    }

    private fun hideInternalPlayer() {
        photoAutoAdvanceJob?.cancel()
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
            suppressNextSpinnerSelection = true
            albumSpinner.adapter = spinnerAdapter

            if (prevAlbumName != null) {
                val idx = availableAlbums.indexOfFirst { it.name == prevAlbumName }
                if (idx >= 0) albumSpinner.setSelection(idx)
            }

            albumSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (suppressNextSpinnerSelection) {
                        suppressNextSpinnerSelection = false
                        return
                    }
                    if (position in availableAlbums.indices) {
                        val chosen = availableAlbums[position]
                        selectedAlbumFilter = if (chosen.isAll) null else chosen.name
                        filterList(searchInput.text.toString().trim(), restoreScrollWhenEmpty = false, immediate = true)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        photoAutoAdvanceJob?.cancel()
        internalVideoView?.stopPlayback()
    }
}
