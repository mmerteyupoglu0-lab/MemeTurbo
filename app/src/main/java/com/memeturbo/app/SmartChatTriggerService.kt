package com.memeturbo.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.File

class SmartChatTriggerService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var searchPanelView: View? = null
    private var currentChatPackage: String? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var lastClickTime = 0L
    private var overlaySavedScrollPos = 0
    private var overlaySavedScrollOffset = 0
    private var isOverlayRestoringScroll = false
    private var selectedOverlayAlbum: String? = null

    private val allowedChatPackages = setOf(
        "com.instagram.android",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.discord",
        "com.facebook.orca",
        "com.twitter.android",
        "com.zhiliaoapp.musically"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("overlay_album", null)
        selectedOverlayAlbum = if (saved.isNullOrBlank()) null else saved
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in allowedChatPackages) return

        currentChatPackage = pkg

        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            val source = event.source
            val clsName = event.className?.toString() ?: ""
            val isInput = source?.isEditable == true ||
                clsName.contains("EditText", ignoreCase = true) ||
                clsName.contains("AutoComplete", ignoreCase = true)

            if (isInput) {
                val now = System.currentTimeMillis()
                // İlk odaklanma veya tıklamadan sonra 850ms içinde ikinci tıklama gelirse paneli aç!
                if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && now - lastClickTime in 50..850) {
                    showGameTurboSearchPanel()
                    lastClickTime = 0L
                } else {
                    lastClickTime = now
                }
            }
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
    }

    private fun showGameTurboSearchPanel() {
        if (searchPanelView != null) return

        var previewBoxRef: FrameLayout? = null
        var videoViewRef: VideoView? = null
        var searchInputRef: EditText? = null
        var albumDropdownRef: ScrollView? = null

        val container = object : LinearLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (albumDropdownRef?.visibility == View.VISIBLE) {
                        albumDropdownRef?.visibility = View.GONE
                        return true
                    }
                    if (previewBoxRef?.visibility == View.VISIBLE) {
                        videoViewRef?.stopPlayback()
                        previewBoxRef?.visibility = View.GONE
                        return true
                    }
                    if (!searchInputRef?.text.isNullOrEmpty()) {
                        searchInputRef?.setText("")
                        return true
                    }
                    closeSearchPanel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(14f), dpToPx(12f), dpToPx(14f), dpToPx(12f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24f).toFloat()
                setColor(Color.parseColor("#E60F172A"))
                setStroke(dpToPx(2f), Color.parseColor("#8838BDF8"))
            }
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val albumFilterBtn = Button(this).apply {
            text = if (selectedOverlayAlbum == null) "📁 Tümü ▾" else "📁 ${selectedOverlayAlbum!!.take(8)} ▾"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11f
            isAllCaps = false
            maxLines = 1
            setPadding(dpToPx(8f), 0, dpToPx(8f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(14f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dpToPx(1f), Color.parseColor("#475569"))
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(38f)).apply {
                rightMargin = dpToPx(6f)
            }
        }

        val searchInput = EditText(this).apply {
            hint = "Meme ara (replik, kelime)..."
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(16f).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
            setPadding(dpToPx(12f), dpToPx(8f), dpToPx(12f), dpToPx(8f))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchInputRef = searchInput

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dpToPx(10f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
            setOnClickListener { closeSearchPanel() }
        }

        topBar.addView(albumFilterBtn)
        topBar.addView(searchInput)
        topBar.addView(closeBtn)
        container.addView(topBar)

        val albumDropdownScroll = ScrollView(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(14f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dpToPx(1f), Color.parseColor("#38BDF8"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(150f)
            ).apply { topMargin = dpToPx(6f) }
        }
        albumDropdownRef = albumDropdownScroll

        val albumDropdownList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(8f), dpToPx(6f), dpToPx(8f), dpToPx(6f))
        }
        albumDropdownScroll.addView(albumDropdownList)
        container.addView(albumDropdownScroll)

        val previewBox = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(0, dpToPx(6f), 0, dpToPx(6f))
        }
        previewBoxRef = previewBox

        val videoView = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(150f),
                Gravity.CENTER
            )
            setOnPreparedListener { mp -> mp.isLooping = true }
            setOnClickListener {
                if (isPlaying) pause() else start()
            }
        }
        videoViewRef = videoView
        previewBox.addView(videoView)
        container.addView(previewBox)

        val gridLayoutManager = GridLayoutManager(this@SmartChatTriggerService, 2)
        val recyclerView = RecyclerView(this).apply {
            layoutManager = gridLayoutManager
            setHasFixedSize(true)
            itemAnimator = null
            setItemViewCacheSize(24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(310f)
            ).apply { topMargin = dpToPx(8f) }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (searchInput.text.isNullOrEmpty() && !isOverlayRestoringScroll && (dx != 0 || dy != 0)) {
                        val pos = gridLayoutManager.findFirstVisibleItemPosition()
                        if (pos != RecyclerView.NO_POSITION) {
                            overlaySavedScrollPos = pos
                            overlaySavedScrollOffset = gridLayoutManager.findViewByPosition(pos)?.top ?: 0
                        }
                    }
                }
            })
        }

        val adapter = MemeGridAdapter(
            onPreviewClick = { meme ->
                previewBox.visibility = View.VISIBLE
                videoView.setVideoPath(meme.filePath)
                videoView.start()
            },
            onSendClick = { meme ->
                sendMediaDirectlyToChat(meme)
            }
        )
        recyclerView.adapter = adapter
        container.addView(recyclerView)

        val db = MemeDatabase.getInstance(this).memeDao()

        var searchJob: Job? = null
        fun refreshOverlayList(query: String, immediate: Boolean = false) {
            searchJob?.cancel()
            searchJob = serviceScope.launch {
                if (!immediate && query.isNotEmpty()) delay(60)
                val results = withContext(Dispatchers.IO) {
                    val all = db.getAllMemes()
                    val albumFiltered = if (selectedOverlayAlbum == null) {
                        all
                    } else {
                        all.filter { it.albumName == selectedOverlayAlbum }
                    }
                    if (query.isEmpty()) {
                        albumFiltered
                    } else {
                        VideoIndexer.smartSearchFilter(albumFiltered, query)
                    }
                }
                adapter.submitList(results) {
                    if (query.isNotEmpty()) {
                        recyclerView.scrollToPosition(0)
                    } else if (overlaySavedScrollPos > 0) {
                        isOverlayRestoringScroll = true
                        val targetPos = overlaySavedScrollPos
                        val targetOffset = overlaySavedScrollOffset
                        recyclerView.post {
                            gridLayoutManager.scrollToPositionWithOffset(targetPos, targetOffset)
                            recyclerView.post { isOverlayRestoringScroll = false }
                        }
                    }
                }
            }
        }

        albumFilterBtn.setOnClickListener {
            if (albumDropdownScroll.visibility == View.VISIBLE) {
                albumDropdownScroll.visibility = View.GONE
            } else {
                serviceScope.launch {
                    val albums = withContext(Dispatchers.IO) { db.getIndexedAlbumNames() }
                    albumDropdownList.removeAllViews()

                    fun addAlbumOption(label: String, albumValue: String?) {
                        val row = TextView(this@SmartChatTriggerService).apply {
                            text = label
                            textSize = 13f
                            setTextColor(if (selectedOverlayAlbum == albumValue) Color.parseColor("#38BDF8") else Color.WHITE)
                            setPadding(dpToPx(10f), dpToPx(8f), dpToPx(10f), dpToPx(8f))
                            setOnClickListener {
                                selectedOverlayAlbum = albumValue
                                getSharedPreferences("memeturbo_prefs", Context.MODE_PRIVATE)
                                    .edit()
                                    .putString("overlay_album", albumValue ?: "")
                                    .apply()
                                albumFilterBtn.text = if (albumValue == null) "📁 Tümü ▾" else "📁 ${albumValue.take(8)} ▾"
                                albumDropdownScroll.visibility = View.GONE
                                overlaySavedScrollPos = 0
                                overlaySavedScrollOffset = 0
                                refreshOverlayList(searchInput.text?.toString()?.trim() ?: "", immediate = true)
                            }
                        }
                        albumDropdownList.addView(row)
                    }

                    addAlbumOption("🌟 Tüm Taranmış Albümler", null)
                    for (alb in albums) {
                        addAlbumOption("📁 $alb", alb)
                    }
                    albumDropdownScroll.visibility = View.VISIBLE
                }
            }
        }

        refreshOverlayList("", immediate = true)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshOverlayList(s?.toString()?.trim() ?: "", immediate = true)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val displayMetrics = resources.displayMetrics
        val panelHeight = (displayMetrics.heightPixels * 0.58).toInt()

        val params = WindowManager.LayoutParams(
            (displayMetrics.widthPixels * 0.92).toInt(),
            panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(36f)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }

        searchPanelView = container
        windowManager.addView(container, params)
        searchInput.requestFocus()
    }

    private fun closeSearchPanel() {
        searchPanelView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            searchPanelView = null
        }
    }

    private fun sendMediaDirectlyToChat(meme: MemeVideo) {
        val file = File(meme.filePath)
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
        val mime = if (meme.isVideo) "video/*" else "image/*"

        closeSearchPanel()

        val isWhatsApp = currentChatPackage?.contains("whatsapp", ignoreCase = true) == true
        if (isWhatsApp) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newUri(contentResolver, meme.fileName, uri)
            clipboard.setPrimaryClip(clip)

            serviceScope.launch {
                delay(200)
                val focusedInput = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                val pasted = focusedInput?.performAction(AccessibilityNodeInfo.ACTION_PASTE) == true
                if (!pasted) {
                    launchShareIntent(mime, uri)
                }
            }
        } else {
            launchShareIntent(mime, uri)
        }
    }

    private fun launchShareIntent(mime: String, uri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            currentChatPackage?.let { setPackage(it) }
        }

        try {
            startActivity(shareIntent)
        } catch (_: Exception) {
            val chooser = Intent.createChooser(shareIntent, "Meme Gönder").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
        }
    }

    override fun onInterrupt() {
        closeSearchPanel()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
