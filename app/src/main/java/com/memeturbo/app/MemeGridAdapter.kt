package com.memeturbo.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MemeGridAdapter(
    private val onPreviewClick: (MemeVideo) -> Unit,
    private val onSendClick: (MemeVideo) -> Unit
) : RecyclerView.Adapter<MemeGridAdapter.MemeViewHolder>() {

    private val items = mutableListOf<MemeVideo>()

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
            setPadding(16, 16, 16, 16)
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(parent, 110f)
            )
        }

        val transcriptView = TextView(context).apply {
            setTextColor(Color.parseColor("#FDE047"))
            textSize = 12f
            maxLines = 2
            setPadding(4, 10, 4, 10)
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
            textSize = 12f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(parent, 36f), 1f).apply {
                rightMargin = dpToPx(parent, 6f)
            }
        }

        val sendBtn = Button(context).apply {
            text = "Paylaş 🚀"
            textSize = 12f
            setTextColor(Color.WHITE)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(Color.parseColor("#0284C7"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(parent, 36f), 1.2f)
        }

        actionRow.addView(playBtn)
        actionRow.addView(sendBtn)

        card.addView(thumbView)
        card.addView(transcriptView)
        card.addView(actionRow)

        return MemeViewHolder(card, thumbView, transcriptView, playBtn, sendBtn)
    }

    override fun onBindViewHolder(holder: MemeViewHolder, position: Int) {
        val meme = items[position]

        @Suppress("DEPRECATION")
        val thumb = if (meme.isVideo) {
            ThumbnailUtils.createVideoThumbnail(meme.filePath, MediaStore.Images.Thumbnails.MINI_KIND)
        } else {
            ThumbnailUtils.extractThumbnail(
                android.graphics.BitmapFactory.decodeFile(meme.filePath),
                200, 200
            )
        }
        holder.thumbView.setImageBitmap(thumb)

        val snippet = when {
            meme.spokenText.isNotBlank() -> "🗣️ ${meme.spokenText}"
            meme.screenText.isNotBlank() -> "📝 ${meme.screenText}"
            meme.visualLabels.isNotBlank() -> "👁️ ${meme.visualLabels.take(30)}..."
            else -> meme.fileName
        }
        holder.transcriptView.text = snippet

        holder.playBtn.visibility = if (meme.isVideo) View.VISIBLE else View.GONE
        holder.playBtn.setOnClickListener { onPreviewClick(meme) }
        holder.sendBtn.setOnClickListener { onSendClick(meme) }
    }

    override fun getItemCount(): Int = items.size

    class MemeViewHolder(
        view: View,
        val thumbView: ImageView,
        val transcriptView: TextView,
        val playBtn: Button,
        val sendBtn: Button
    ) : RecyclerView.ViewHolder(view)
}
