package com.memeturbo.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var albumSpinner: Spinner
    private lateinit var galleryRecycler: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var adapter: MemeGridAdapter
    private var availableAlbums = listOf<VideoIndexer.AlbumInfo>()
    private var currentFilter = 0

    private lateinit var tabAllBtn: Button
    private lateinit var tabVideosBtn: Button
    private lateinit var tabImagesBtn: Button

    private var internalPlayerLayout: FrameLayout? = null
    private var internalVideoView: VideoView? = null
    private var internalImageView: ImageView? = null

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
            setPadding(dpToPx(16f), dpToPx(24f), dpToPx(16f), dpToPx(12f))
            setBackgroundColor(Color.parseColor("#1E293B"))
        }

        val title = TextView(this).apply {
            text = "⚡ MemeTurbo Akıllı Galeri"
            textSize = 20f
            setTextColor(Color.WHITE)
        }

        // Güven veren Açık Gizlilik & Tarama Bildirimi
        val privacyBanner = TextView(this).apply {
            text = "🔒 %100 Çevrimdışı ve Gizli: Videolarınız indirilmez, internete gitmez. İlk tarama arşiv büyüklüğünüze göre 2-3 dakika sürebilir (sadece 1 kez yapılır). Lütfen kapatmayın."
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

        // İki Basamaklı Akıllı Erişilebilirlik Yönlendiricisi
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
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(40f), 1.6f).apply { rightMargin = dpToPx(8f) }
        }

        val scanBtn = Button(this).apply {
            text = "Tara ⚡"
            textSize = 13f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12f).toFloat()
                setColor(Color.parseColor("#16A34A"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(40f), 1f)
            setOnClickListener { startScanSelectedAlbum() }
        }

        scanRow.addView(albumSpinner)
        scanRow.addView(scanBtn)
        mainContainer.addView(scanRow)

        statusText = TextView(this).apply {
            text = "Albümler kontrol ediliyor..."
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

        searchInput = EditText(this).apply {
            hint = "Galeri içinde ara (kedi, kar, replikler)..."
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(16f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dpToPx(1f), Color.parseColor("#334155"))
            }
            setPadding(dpToPx(16f), dpToPx(12f), dpToPx(16f), dpToPx(12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dpToPx(16f), 0, dpToPx(16f), dpToPx(8f))
            }
        }
        mainContainer.addView(searchInput)

        galleryRecycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            setHasFixedSize(true)
            setItemViewCacheSize(20) // Yağ gibi kaydırma için önbellek boyutu
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            ).apply { setMargins(dpToPx(8f), 0, dpToPx(8f), 0) }
        }

        adapter = MemeGridAdapter(
            onPreviewClick = { meme ->
                showInternalPlayer(meme)
            },
            onSendClick = { meme ->
                shareMemeMedia(meme)
            },
            onEditTagsClick = { meme ->
                showEditTagsDialog(meme)
            }
        )
        galleryRecycler.adapter = adapter
        mainContainer.addView(galleryRecycler)

        root.addView(mainContainer)
        setupInternalPlayerLayout(root)

        setContentView(root)
        checkPermissionAndLoadAlbums()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        updateTabColors()
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
            hint = "Örn: Kaan Özdil, çıldırma, Hasan Arda"
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
                        Toast.makeText(this@MainActivity, "Etiketler eklendi!", Toast.LENGTH_SHORT).show()
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
        internalPlayerLayout?.visibility = View.VISIBLE
        if (meme.isVideo) {
            internalImageView?.visibility = View.GONE
            internalVideoView?.visibility = View.VISIBLE
            internalVideoView?.setVideoPath(meme.filePath)
            internalVideoView?.start()
        } else {
            internalVideoView?.visibility = View.GONE
            internalImageView?.visibility = View.VISIBLE
            internalImageView?.setImageURI(Uri.fromFile(File(meme.filePath)))
        }
    }

    private fun hideInternalPlayer() {
        internalVideoView?.stopPlayback()
        internalPlayerLayout?.visibility = View.GONE
    }

    override fun onBackPressed() {
        if (internalPlayerLayout?.visibility == View.VISIBLE) {
            hideInternalPlayer()
        } else {
            super.onBackPressed()
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }

    private fun createFilterTab(label: String, type: Int): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(34f), 1f).apply {
                setMargins(dpToPx(3f), 0, dpToPx(3f), 0)
            }
            setOnClickListener {
                currentFilter = type
                updateTabColors()
                filterList(searchInput.text.toString().trim())
            }
        }
    }

    private fun updateTabColors() {
        fun setBg(btn: Button, selected: Boolean) {
            btn.background = GradientDrawable().apply {
                cornerRadius = dpToPx(14f).toFloat()
                setColor(if (selected) Color.parseColor("#0284C7") else Color.parseColor("#1E293B"))
            }
        }
        setBg(tabAllBtn, currentFilter == 0)
        setBg(tabVideosBtn, currentFilter == 1)
        setBg(tabImagesBtn, currentFilter == 2)
    }

    private fun filterList(query: String) {
        lifecycleScope.launch {
            val db = MemeDatabase.getInstance(this@MainActivity).memeDao()
            val baseList = if (query.isEmpty()) {
                when (currentFilter) {
                    1 -> db.getVideoMemes()
                    2 -> db.getImageMemes()
                    else -> db.getAllMemes()
                }
            } else {
                val words = query.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
                val all = db.getAllMemes()
                all.filter { item ->
                    val fullText = "${item.fileName} ${item.spokenText} ${item.screenText} ${item.visualLabels}".lowercase()
                    val matches = words.all { w -> fullText.contains(w) }
                    matches && when (currentFilter) {
                        1 -> item.isVideo
                        2 -> !item.isVideo
                        else -> true
                    }
                }
            }
            adapter.submitList(baseList)
        }
    }

    private fun checkPermissionAndLoadAlbums() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val ungranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, ungranted.toTypedArray(), 101)
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
            val indexer = VideoIndexer(this@MainActivity)
            availableAlbums = indexer.getAvailableAlbums()

            if (availableAlbums.isEmpty()) {
                statusText.text = "Galeride albüm bulunamadı."
                return@launch
            }

            val albumNames = availableAlbums.map { "${it.name} (${it.count} öğe)" }
            val spinnerAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, albumNames)
            albumSpinner.adapter = spinnerAdapter

            val defaultIdx = availableAlbums.indexOfFirst { it.name.contains("Garip Galerim", ignoreCase = true) }
            if (defaultIdx != -1) albumSpinner.setSelection(defaultIdx)

            val db = MemeDatabase.getInstance(this@MainActivity).memeDao()
            val total = db.getCount()
            val videos = db.getVideoCount()
            val images = db.getImageCount()
            statusText.text = "Toplam $total öğe ($videos video, $images fotoğraf) hazır."
            filterList("")
        }
    }

    private fun startScanSelectedAlbum() {
        if (availableAlbums.isEmpty()) return
        val selectedAlbum = availableAlbums[albumSpinner.selectedItemPosition]
        val indexer = VideoIndexer(this)

        lifecycleScope.launch {
            statusText.text = "${selectedAlbum.name} taranıyor..."
            indexer.indexAlbum(selectedAlbum) { current, total, name ->
                runOnUiThread {
                    statusText.text = "Taranıyor ($current/$total): $name"
                }
            }
            loadAlbumsIntoSpinner()
            Toast.makeText(this@MainActivity, "Tarama tamamlandı!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareMemeMedia(meme: MemeVideo) {
        val file = File(meme.filePath)
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val mime = if (meme.isVideo) "video/*" else "image/*"

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newUri(contentResolver, meme.fileName, uri)
        clipboard.setPrimaryClip(clip)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Paylaş"))
    }
}
