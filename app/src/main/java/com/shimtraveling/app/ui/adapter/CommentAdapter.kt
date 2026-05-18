package com.shimtraveling.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shimtraveling.R
import com.shimtraveling.data.model.Comment
import com.shimtraveling.databinding.ItemCommentBinding
import java.text.SimpleDateFormat
import java.util.*

class CommentAdapter : ListAdapter<Comment, CommentAdapter.CommentViewHolder>(CommentDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val binding = ItemCommentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CommentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class CommentViewHolder(private val binding: ItemCommentBinding) : RecyclerView.ViewHolder(binding.root) {
        private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

        fun bind(comment: Comment) {
            binding.commentAuthor.text = comment.authorName
            binding.commentContent.text = comment.content
            binding.commentDate.text = dateFormat.format(comment.createdAt)

            if (!comment.authorAvatar.isNullOrBlank()) {
                Glide.with(binding.root.context)
                    .load(comment.authorAvatar)
                    .placeholder(R.drawable.ic_person)
                    .circleCrop()
                    .into(binding.commentAvatar)
            } else {
                binding.commentAvatar.setImageResource(R.drawable.ic_person)
            }
        }
    }

    class CommentDiffCallback : DiffUtil.ItemCallback<Comment>() {
        override fun areItemsTheSame(oldItem: Comment, newItem: Comment): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Comment, newItem: Comment): Boolean {
            return oldItem == newItem
        }
    }
}
