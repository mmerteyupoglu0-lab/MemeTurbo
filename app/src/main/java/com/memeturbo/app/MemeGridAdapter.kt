package com.memeturbo.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class MemeGridAdapter(
    private val onPreviewClick: (MemeVideo) -> Unit,
    private val onSendClick: (MemeVideo) -> Unit,
    private val onEditTagsClick: ((MemeVideo) -> Unit)? = null
) : RecyclerView.Adapter<MemeGridAdapter.MemeViewHolder>() {

    private val items = mutableListOf<MemeVideo>()
    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 120 FPS akıcı kaydırma için Bellek Önbelleği (LruCache)
    companion object {
        private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        private val cacheSize = maxMemory / 8
        val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    fun submitList(newItems: List<MemeVideo>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    private fun dpToPx(view: View, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            view.resources.displayMetrics
        ).toInt()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemeViewHolder {
        val context = parent.context
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 14, 14, 14)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#1E293B"))
                setStroke(2, Color.parseColor("#334155"))
            }
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(8, 8, 8, 8)
            }
        }

        val thumbView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(parent, 110f)
            )
        }

        val transcriptView = TextView(context).apply {
            setTextColor(Color.parseColor("#FDE047"))
            textSize = 12f
            maxLines = 2
            setPadding(4, 8, 4, 8)
        }

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val playBtn = Button(context).apply {
            text = "▶ İzle"
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(parent, 34f), 1f).apply {
                rightMargin = dpToPx(parent, 4f)
            }
        }

        val sendBtn = Button(context).apply {
            text = "Paylaş 🚀"
            textSize = 11f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(Color.parseColor("#0284C7"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(parent, 34f), 1.2f)
        }

        val editTagBtn = TextView(context).apply {
            text = "✏️"
            textSize = 15f
            setPadding(dpToPx(parent, 6f), dpToPx(parent, 4f), dpToPx(parent, 6f), dpToPx(parent, 4f))
            visibility = if (onEditTagsClick != null) View.VISIBLE else View.GONE
        }

        actionRow.addView(playBtn)
        actionRow.addView(sendBtn)
        if (onEditTagsClick != null) actionRow.addView(editTagBtn)

        card.addView(thumbView)
        card.addView(transcriptView)
        card.addView(actionRow)

        return MemeViewHolder(card, thumbView, transcriptView, playBtn, sendBtn, editTagBtn)
    }

    override fun onBindViewHolder(holder: MemeViewHolder, position: Int) {
        val meme = items[position]

        val cached = memoryCache.get(meme.filePath)
        if (cached != null) {
            holder.thumbView.setImageBitmap(cached)
        } else {
            holder.thumbView.setImageBitmap(null)
            adapterScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        if (meme.isVideo) {
                            @Suppress("DEPRECATION")
                            ThumbnailUtils.createVideoThumbnail(meme.filePath, MediaStore.Images.Thumbnails.MINI_KIND)
                        } else {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                            BitmapFactory.decodeFile(meme.filePath, opts)
                        }
                    } catch (_: Exception) { null }
                }
                if (bitmap != null) {
                    memoryCache.put(meme.filePath, bitmap)
                    if (holder.adapterPosition == position) {
                        holder.thumbView.setImageBitmap(bitmap)
                    }
                }
            }
        }

        val cleanSpoken = meme.spokenText.replace("\u200B", "").trim()
        val cleanTitle = VideoIndexer.cleanFileNameForDisplay(meme.fileName)
        val cleanScreen = VideoIndexer.filterWatermarks(meme.screenText)
        val cleanVisual = VideoIndexer.cleanVisualLabelsString(meme.visualLabels)

        // KART BAŞLIĞI ÖNCELİK SIRALAMASI:
        // 1. Ses / Replik (🗣️) -> Videodaki konuşma her zaman 1. öncelik!
        // 2. Temiz Dosya Başlığı (🎬) -> "Ben mi demissim onu", "Golem edit", "savaş cebeci bekliyoruz" vb.
        // 3. Temiz Ekran Yazısı (📝) -> "Hz Adem Allaha hesap verirken", "Keşke anlatmasaydım" vb.
        // 4. Saf Türkçe Sahne/Hayvan veya Özel Etiket (🐱 Kedi • 💡 Işık / Lazer, 🐵 Maymun, 🏷️ Arda Turan)
        val snippet = when {
            cleanSpoken.isNotBlank() -> "🗣️ $cleanSpoken"
            meme.isVideo && cleanTitle.isNotBlank() -> "🎬 $cleanTitle"
            cleanScreen.isNotBlank() -> "📝 $cleanScreen"
            cleanTitle.isNotBlank() -> "🖼️ $cleanTitle"
            cleanVisual.isNotBlank() -> if (cleanVisual.any { !it.isLetterOrDigit() && !it.isWhitespace() }) cleanVisual else "🏷️ $cleanVisual"
            else -> if (meme.isVideo) "🎬 Video #${position + 1}" else "🖼️ Görsel #${position + 1}"
        }
        holder.transcriptView.text = snippet

        holder.playBtn.visibility = if (meme.isVideo) View.VISIBLE else View.GONE
        holder.playBtn.setOnClickListener { onPreviewClick(meme) }
        holder.sendBtn.setOnClickListener { onSendClick(meme) }
        holder.editTagBtn.setOnClickListener { onEditTagsClick?.invoke(meme) }
    }

    override fun getItemCount(): Int = items.size

    class MemeViewHolder(
        view: View,
        val thumbView: ImageView,
        val transcriptView: TextView,
        val playBtn: Button,
        val sendBtn: Button,
        val editTagBtn: TextView
    ) : RecyclerView.ViewHolder(view)
}
