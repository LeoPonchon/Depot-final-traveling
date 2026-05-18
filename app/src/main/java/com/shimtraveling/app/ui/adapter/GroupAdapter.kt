package com.shimtraveling.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.shimtraveling.R
import com.shimtraveling.data.model.Group
import com.shimtraveling.databinding.ItemGroupBinding

class GroupAdapter(
    private var currentUserId: String?,
    private var userGroupIds: List<String> = emptyList(),
    private val onGroupClick: (Group) -> Unit,
    private val onJoinClick: (Group) -> Unit,
    private val onLeaveClick: (Group) -> Unit = {}
) : ListAdapter<Group, GroupAdapter.GroupViewHolder>(GroupDiffCallback()) {

    private var photoCounts: Map<String, Int> = emptyMap()

    fun updateData(groups: List<Group>, userId: String?, groupIds: List<String>, photoCounts: Map<String, Int> = emptyMap()) {
        currentUserId = userId
        userGroupIds = groupIds
        this.photoCounts = photoCounts
        submitList(groups)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemGroupBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return GroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class GroupViewHolder(private val binding: ItemGroupBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onGroupClick(getItem(position))
                }
            }

            binding.joinButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onJoinClick(getItem(position))
                }
            }
        }

        fun bind(group: Group) {
            binding.groupName.text = group.name

            if (!group.description.isNullOrBlank()) {
                binding.groupDescription.text = group.description
                binding.groupDescription.visibility = android.view.View.VISIBLE
            } else {
                binding.groupDescription.visibility = android.view.View.GONE
            }

            val memberCount = group.members.size
            binding.memberCount.text = binding.root.context.resources.getQuantityString(
                R.plurals.members_count, memberCount, memberCount
            )

            val photoCount = photoCounts[group.id] ?: 0
            binding.photoCount.text = binding.root.context.resources.getQuantityString(
                R.plurals.photos_count, photoCount, photoCount
            )

            val isOwner = currentUserId != null && group.ownerId == currentUserId
            val isMember = currentUserId != null && userGroupIds.contains(group.id)

            if (isOwner) {
                binding.ownerBadge.visibility = android.view.View.VISIBLE
                binding.joinButton.visibility = android.view.View.GONE
            } else if (isMember) {
                binding.ownerBadge.visibility = android.view.View.GONE
                binding.joinButton.visibility = android.view.View.VISIBLE
                binding.joinButton.text = binding.root.context.getString(R.string.leave_group)
                binding.joinButton.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onLeaveClick(getItem(position))
                    }
                }
            } else {
                binding.ownerBadge.visibility = android.view.View.GONE
                binding.joinButton.visibility = android.view.View.VISIBLE
                binding.joinButton.text = binding.root.context.getString(R.string.join_group)
                binding.joinButton.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onJoinClick(getItem(position))
                    }
                }
            }
        }
    }

    class GroupDiffCallback : DiffUtil.ItemCallback<Group>() {
        override fun areItemsTheSame(oldItem: Group, newItem: Group): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Group, newItem: Group): Boolean {
            return oldItem == newItem
        }
    }
}
