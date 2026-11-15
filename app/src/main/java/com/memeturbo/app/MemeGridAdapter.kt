package com.memeturbo.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream

class MemeGridAdapter(
    private val onPreviewClick: (MemeVideo) -> Unit,
    private val onSendClick: (MemeVideo) -> Unit,
    private val onEditTagsClick: ((MemeVideo) -> Unit)? = null
) : RecyclerView.Adapter<MemeGridAdapter.MemeViewHolder>() {

    private val items = mutableListOf<MemeVideo>()
    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var diffJob: Job? = null

    init {
        setHasStableIds(true)
    }

    companion object {
        private val thumbSemaphore = Semaphore(4)
        private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        private val cacheSize = maxMemory / 8
        val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }

        private fun getDiskThumbFile(cacheDir: File, targetPath: String, lastMod: Long): File {
            val dir = File(cacheDir, "thumbs_v16")
            if (!dir.exists()) dir.mkdirs()
            val hash = "${targetPath}_${lastMod}".hashCode().toUInt().toString(16)
            return File(dir, "t_$hash.jpg")
        }

        private fun scaleDownBitmap(raw: Bitmap, maxDim: Int = 200): Bitmap {
            val w = raw.width
            val h = raw.height
            if (w <= 0 || h <= 0) return raw
            val largest = maxOf(w, h)
            if (largest <= maxDim) return raw
            val scale = maxDim.toFloat() / largest.toFloat()
            val nw = (w * scale).toInt().coerceAtLeast(1)
            val nh = (h * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(raw, nw, nh, true)
            if (scaled != raw) {
                try { raw.recycle() } catch (_: Exception) {}
            }
            return scaled
        }

        private fun extractFastVideoThumb(targetPath: String): Bitmap? {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(targetPath)
                val frame = retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    return scaleDownBitmap(frame, 200)
                }
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
            return try {
                @Suppress("DEPRECATION")
                val fallback = ThumbnailUtils.createVideoThumbnail(targetPath, MediaStore.Images.Thumbnails.MINI_KIND)
                if (fallback != null) scaleDownBitmap(fallback, 200) else null
            } catch (_: Exception) {
                null
            }
        }

        private fun extractFastImageThumb(targetPath: String): Bitmap? {
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(targetPath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (bounds.outWidth / sample > 240 || bounds.outHeight / sample > 240) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample.coerceAtLeast(1)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val decoded = BitmapFactory.decodeFile(targetPath, opts) ?: return null
                scaleDownBitmap(decoded, 200)
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun getItemId(position: Int): Long {
        return items[position].filePath.hashCode().toLong()
    }

    fun submitList(newItems: List<MemeVideo>, onCommitted: (() -> Unit)? = null) {
        diffJob?.cancel()
        val oldSnapshot = ArrayList(items)
        if (oldSnapshot.isEmpty() || newItems.isEmpty() || oldSnapshot.size > 400 || newItems.size > 400) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
            onCommitted?.invoke()
            return
        }

        diffJob = adapterScope.launch {
            val diffResult = withContext(Dispatchers.Default) {
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int = oldSnapshot.size
                    override fun getNewListSize(): Int = newItems.size

                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                        return oldSnapshot[oldItemPosition].filePath == newItems[newItemPosition].filePath
                    }

                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                        val o = oldSnapshot[oldItemPosition]
                        val n = newItems[newItemPosition]
                        return o.spokenText == n.spokenText &&
                            o.screenText == n.screenText &&
                            o.visualLabels == n.visualLabels &&
                            o.albumName == n.albumName
                    }
                }, false)
            }
            items.clear()
            items.addAll(newItems)
            diffResult.dispatchUpdatesTo(this@MemeGridAdapter)
            onCommitted?.invoke()
        }
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
        val theme = AppStrings.getTheme(context)
        val glassAlpha = AppStrings.getGlassAlpha(context)
        val cols = AppStrings.getGridColumns(context)
        val compact = AppStrings.isCompactGalleryCard(context) || cols == 3
        val strings = AppStrings.tr(context)

        // V18: Hantal kenar boşlukları kaldırıldı; görsel çerçeveye sıfıra yakın oturur
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = if (compact) dpToPx(parent, 3f) else dpToPx(parent, 4f)
            setPadding(pad, pad, pad, dpToPx(parent, 5f))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(parent, 14f).toFloat()
                setColor(AppStrings.withAlpha(theme.cardHex, glassAlpha))
                setStroke(dpToPx(parent, 1f), AppStrings.withAlpha(theme.cardStrokeHex, (glassAlpha + 0.12f).coerceAtMost(1f)))
            }
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = if (cols == 3) dpToPx(parent, 3f) else dpToPx(parent, 4f)
                setMargins(m, m, m, m)
            }
        }

        // Video/fotoğraf önizlemesi her zaman %100 net/opak kalır!
        val thumbContainer = FrameLayout(context).apply {
            val hDp = when {
                cols == 1 -> 200f
                cols == 3 -> 108f
                compact -> 142f
                else -> 136f
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(parent, hDp)
            )
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(parent, 11f).toFloat()
                setColor(Color.BLACK)
            }
            clipToOutline = true
        }

        val thumbView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val typeBadge = TextView(context).apply {
            textSize = 9.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(parent, 5f), dpToPx(parent, 2f), dpToPx(parent, 5f), dpToPx(parent, 2f))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(parent, 7f).toFloat()
                setColor(Color.parseColor("#B30F172A"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                setMargins(0, dpToPx(parent, 5f), dpToPx(parent, 5f), 0)
            }
        }

        thumbContainer.addView(thumbView)
        thumbContainer.addView(typeBadge)

        val transcriptView = TextView(context).apply {
            setTextColor(theme.textHighlightHex)
            textSize = if (compact) 10.5f else 11.5f
            maxLines = if (compact) 1 else 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpToPx(parent, 3f), dpToPx(parent, 4f), dpToPx(parent, 3f), dpToPx(parent, 4f))
        }

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(parent, 2f), 0, dpToPx(parent, 2f), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val btnHeight = if (compact) dpToPx(parent, 28f) else dpToPx(parent, 31f)
        val btnAlpha = if (glassAlpha < 0.99f) (glassAlpha + 0.1f).coerceAtMost(0.92f) else 1.0f

        val playBtn = Button(context).apply {
            text = if (compact) "▶" else strings.playBtn
            textSize = 10.5f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(parent, 10f).toFloat()
                setColor(AppStrings.withAlpha(theme.secondaryBtnHex, btnAlpha))
            }
            layoutParams = LinearLayout.LayoutParams(0, btnHeight, if (compact) 0.7f else 1f).apply {
                rightMargin = dpToPx(parent, 4f)
            }
        }

        val sendBtn = Button(context).apply {
            text = if (compact) "🚀" else strings.shareBtn
            textSize = 10.5f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(parent, 10f).toFloat()
                setColor(AppStrings.withAlpha(theme.accentHex, btnAlpha))
            }
            layoutParams = LinearLayout.LayoutParams(0, btnHeight, 1.25f)
        }

        val editTagBtn = TextView(context).apply {
            text = "✏️"
            textSize = if (compact) 13f else 14f
            setPadding(dpToPx(parent, 5f), dpToPx(parent, 2f), dpToPx(parent, 2f), dpToPx(parent, 2f))
            visibility = if (onEditTagsClick != null) View.VISIBLE else View.GONE
        }

        actionRow.addView(playBtn)
        actionRow.addView(sendBtn)
        if (onEditTagsClick != null) actionRow.addView(editTagBtn)

        card.addView(thumbContainer)
        card.addView(transcriptView)
        card.addView(actionRow)

        return MemeViewHolder(card, thumbView, typeBadge, transcriptView, playBtn, sendBtn, editTagBtn)
    }

    override fun onViewRecycled(holder: MemeViewHolder) {
        super.onViewRecycled(holder)
        holder.thumbJob?.cancel()
        holder.boundPath = null
    }

    override fun onBindViewHolder(holder: MemeViewHolder, position: Int) {
        val meme = items[position]

        if (holder.boundPath != meme.filePath) {
            holder.boundPath = meme.filePath
            holder.thumbJob?.cancel()

            val cached = memoryCache.get(meme.filePath)
            if (cached != null) {
                holder.thumbView.setImageBitmap(cached)
            } else {
                holder.thumbView.setImageBitmap(null)
                val targetPath = meme.filePath
                val isVid = meme.isVideo
                val lastMod = meme.lastModified
                val appCacheDir = holder.itemView.context.applicationContext.cacheDir

                holder.thumbJob = adapterScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        if (!isActive) return@withContext null
                        val diskFile = getDiskThumbFile(appCacheDir, targetPath, lastMod)
                        if (diskFile.exists() && diskFile.length() > 100L) {
                            try {
                                val diskBmp = BitmapFactory.decodeFile(diskFile.absolutePath)
                                if (diskBmp != null) return@withContext diskBmp
                            } catch (_: Exception) {}
                        }

                        thumbSemaphore.withPermit {
                            if (!isActive) return@withPermit null
                            val generated = if (isVid) {
                                extractFastVideoThumb(targetPath)
                            } else {
                                extractFastImageThumb(targetPath)
                            }
                            if (generated != null) {
                                try {
                                    FileOutputStream(diskFile).use { out ->
                                        generated.compress(Bitmap.CompressFormat.JPEG, 75, out)
                                    }
                                } catch (_: Exception) {}
                            }
                            generated
                        }
                    }
                    if (bitmap != null) {
                        memoryCache.put(targetPath, bitmap)
                        if (holder.boundPath == targetPath) {
                            holder.thumbView.setImageBitmap(bitmap)
                        }
                    }
                }
            }
        }

        holder.typeBadge.text = if (meme.isVideo) "🎬" else "🖼️"
        holder.transcriptView.text = VideoIndexer.getFastSnippetForUi(meme)

        holder.thumbView.setOnClickListener { onPreviewClick(meme) }
        holder.playBtn.visibility = if (meme.isVideo) View.VISIBLE else View.GONE
        holder.playBtn.setOnClickListener { onPreviewClick(meme) }
        holder.sendBtn.setOnClickListener { onSendClick(meme) }
        holder.editTagBtn.setOnClickListener { onEditTagsClick?.invoke(meme) }
    }

    override fun getItemCount(): Int = items.size

    class MemeViewHolder(
        val cardRoot: LinearLayout,
        val thumbView: ImageView,
        val typeBadge: TextView,
        val transcriptView: TextView,
        val playBtn: Button,
        val sendBtn: Button,
        val editTagBtn: TextView,
        var thumbJob: Job? = null,
        var boundPath: String? = null
    ) : RecyclerView.ViewHolder(cardRoot)
}
