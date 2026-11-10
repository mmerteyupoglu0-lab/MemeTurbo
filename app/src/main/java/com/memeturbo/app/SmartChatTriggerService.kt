package com.memeturbo.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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

    private val allowedChatPackages = setOf(
        "com.instagram.android",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.discord",
        "com.facebook.orca"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in allowedChatPackages) return

        currentChatPackage = pkg

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val source = event.source
            val isInput = source?.isEditable == true || 
                          event.className?.toString()?.contains("EditText", ignoreCase = true) == true

            if (isInput) {
                val now = System.currentTimeMillis()
                if (now - lastClickTime in 100..550) {
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

        val container = object : LinearLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
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

        // Üst Bar: [🌟 Albüm Filtresi ▾] + Arama Kutusu + Kapatma (✕)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val albumFilterBtn = Button(this).apply {
            text = "📁 Albüm ▾"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11f
            isAllCaps = false
            setPadding(dpToPx(6f), 0, dpToPx(6f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12f).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dpToPx(1f), Color.parseColor("#475569"))
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(85f), dpToPx(38f)).apply {
                rightMargin = dpToPx(6f)
            }
        }

        val searchInput = EditText(this).apply {
            hint = "Meme ara (kedi, kar, replikler)..."
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

        // Video Önizleme Alanı
        val previewBox = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(0, dpToPx(6f), 0, dpToPx(6f))
        }
        val videoView = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dpToPx(150f),
                Gravity.CENTER
            )
        }
        previewBox.addView(videoView)
        container.addView(previewBox)

        val recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@SmartChatTriggerService, 2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(310f)
            ).apply { topMargin = dpToPx(8f) }
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
        serviceScope.launch {
            adapter.submitList(db.getRecentMemes())
        }

        // Akıllı Çoklu Kelime Arama Motoru
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim() ?: ""
                serviceScope.launch {
                    val results = if (q.isEmpty()) {
                        db.getRecentMemes()
                    } else {
                        val words = q.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
                        val all = db.getAllMemes()
                        all.filter { item ->
                            val fullText = "${item.fileName} ${item.spokenText} ${item.screenText} ${item.visualLabels}".lowercase()
                            words.all { w -> fullText.contains(w) }
                        }
                    }
                    adapter.submitList(results)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Alt Navigasyon Tuşlarını Kapatmayan Boyut & Konum Ayarı!
        val displayMetrics = resources.displayMetrics
        val panelHeight = (displayMetrics.heightPixels * 0.58).toInt() // Ekranın sadece %58'ini kaplar, alt tuşlar ASLA kaybolmaz!

        val params = WindowManager.LayoutParams(
            (displayMetrics.widthPixels * 0.92).toInt(),
            panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(36f) // Klavyenin ve sohbet kutusunun hemen üstüne oturur
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

    /**
     * Sohbetten hiç çıkmadan doğrudan sohbete yapıştırmayı dener, olmazsa yerel paylaşımı açar.
     */
    private fun sendMediaDirectlyToChat(meme: MemeVideo) {
        val file = File(meme.filePath)
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
        val mime = if (meme.isVideo) "video/*" else "image/*"

        // 1. Panoya kopyala
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newUri(contentResolver, meme.fileName, uri)
        clipboard.setPrimaryClip(clip)

        // 2. Paneli kapat ve aktif mesaj kutusuna OTOMATİK YAPIŞTIR (Accessibility Injection)
        closeSearchPanel()

        serviceScope.launch {
            delay(150)
            val focusedInput = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val pasted = focusedInput?.performAction(AccessibilityNodeInfo.ACTION_PASTE) == true

            if (!pasted) {
                // Eğer uygulama güvenlik gereği doğrudan yapıştırmaya izin vermezse yerel paylaşımı açar
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
            } else {
                Toast.makeText(this@SmartChatTriggerService, "Sohbete yapıştırıldı! 🚀", Toast.LENGTH_SHORT).show()
            }
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
