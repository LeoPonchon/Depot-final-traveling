package com.shimtraveling.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.shimtraveling.R
import com.shimtraveling.databinding.ItemPathDetailFooterBinding

class PathDetailFooterAdapter(
    private val onNavigate: () -> Unit,
    private val onToggleSave: () -> Unit,
    private val onToggleLike: () -> Unit,
    private val onShare: () -> Unit,
    private val onExportPdf: () -> Unit,
) : RecyclerView.Adapter<PathDetailFooterAdapter.VH>() {

    private var isSaved: Boolean = false
    private var isLiked: Boolean = false
    private var likesCount: Int = 0

    fun setSaveState(saved: Boolean) {
        isSaved = saved
        notifyDataSetChanged()
    }

    fun setLikeState(liked: Boolean, likes: Int) {
        isLiked = liked
        likesCount = likes
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPathDetailFooterBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VH(binding, onNavigate, onToggleSave, onToggleLike, onShare, onExportPdf)
    }

    override fun getItemCount(): Int = 1

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(isSaved, isLiked, likesCount)
    }

    class VH(
        private val binding: ItemPathDetailFooterBinding,
        onNavigate: () -> Unit,
        onToggleSave: () -> Unit,
        onToggleLike: () -> Unit,
        onShare: () -> Unit,
        onExportPdf: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.navigateButton.setOnClickListener { onNavigate() }
            binding.saveButton.setOnClickListener { onToggleSave() }
            binding.likeButton.setOnClickListener { onToggleLike() }
            binding.shareButton.setOnClickListener { onShare() }
            binding.exportPdfButton.setOnClickListener { onExportPdf() }
        }

        fun bind(isSaved: Boolean, isLiked: Boolean, likesCount: Int) {
            binding.saveButton.text =
                if (isSaved) itemView.context.getString(R.string.path_remove)
                else itemView.context.getString(R.string.path_save)
            binding.saveButton.setIconResource(
                if (isSaved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark
            )

            val likesText = if (likesCount > 0) " ($likesCount)" else ""
            binding.likeButton.text =
                if (isLiked) "Aimé$likesText" else "Liker le parcours$likesText"
            binding.likeButton.setIconResource(
                if (isLiked) R.drawable.ic_liked else R.drawable.ic_like
            )
        }
    }
}

