package com.memeturbo.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.ThumbnailUtils
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

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
        private val thumbSemaphore = Semaphore(3)
        private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        private val cacheSize = maxMemory / 10
        val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    override fun getItemId(position: Int): Long {
        return items[position].filePath.hashCode().toLong()
    }

    fun submitList(newItems: List<MemeVideo>, onCommitted: (() -> Unit)? = null) {
        diffJob?.cancel()
        val oldSnapshot = ArrayList(items)
        if (oldSnapshot.isEmpty() || newItems.isEmpty()) {
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

    override fun onViewRecycled(holder: MemeViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelSignal?.cancel()
        holder.thumbJob?.cancel()
        holder.boundPath = null
    }

    override fun onBindViewHolder(holder: MemeViewHolder, position: Int) {
        val meme = items[position]

        if (holder.boundPath != meme.filePath) {
            holder.boundPath = meme.filePath
            holder.cancelSignal?.cancel()
            holder.thumbJob?.cancel()

            val cached = memoryCache.get(meme.filePath)
            if (cached != null) {
                holder.thumbView.setImageBitmap(cached)
            } else {
                holder.thumbView.setImageBitmap(null)
                val targetPath = meme.filePath
                val isVid = meme.isVideo
                val sig = CancellationSignal()
                holder.cancelSignal = sig
                holder.thumbJob = adapterScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        thumbSemaphore.withPermit {
                            if (sig.isCanceled) return@withPermit null
                            try {
                                if (isVid) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        ThumbnailUtils.createVideoThumbnail(File(targetPath), Size(240, 240), sig)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        ThumbnailUtils.createVideoThumbnail(targetPath, MediaStore.Images.Thumbnails.MINI_KIND)
                                    }
                                } else {
                                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeFile(targetPath, bounds)
                                    var sample = 1
                                    while (bounds.outWidth / sample > 240 || bounds.outHeight / sample > 240) {
                                        sample *= 2
                                    }
                                    val opts = BitmapFactory.Options().apply {
                                        inSampleSize = sample.coerceAtLeast(2)
                                        inPreferredConfig = Bitmap.Config.RGB_565
                                    }
                                    BitmapFactory.decodeFile(targetPath, opts)
                                }
                            } catch (_: Exception) { null }
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

        holder.transcriptView.text = VideoIndexer.getCachedDisplaySnippet(meme)

        holder.thumbView.setOnClickListener { onPreviewClick(meme) }
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
        val editTagBtn: TextView,
        var thumbJob: Job? = null,
        var cancelSignal: CancellationSignal? = null,
        var boundPath: String? = null
    ) : RecyclerView.ViewHolder(view)
}
