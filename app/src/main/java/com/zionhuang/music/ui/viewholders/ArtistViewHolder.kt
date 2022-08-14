package com.zionhuang.music.ui.viewholders

import androidx.recyclerview.widget.RecyclerView
import com.zionhuang.music.R
import com.zionhuang.music.databinding.ItemArtistBinding
import com.zionhuang.music.db.entities.ArtistEntity
import com.zionhuang.music.extensions.context
import com.zionhuang.music.extensions.show
import com.zionhuang.music.ui.fragments.MenuBottomSheetDialogFragment
import com.zionhuang.music.ui.listeners.ArtistPopupMenuListener

class ArtistViewHolder(
    val binding: ItemArtistBinding,
    private val popupMenuListener: ArtistPopupMenuListener?,
) : RecyclerView.ViewHolder(binding.root) {
    fun bind(artist: ArtistEntity) {
        binding.artist = artist
        binding.btnMoreAction.setOnClickListener {
            MenuBottomSheetDialogFragment
                .newInstance(R.menu.artist)
                .setOnMenuItemClickListener {
                    when (it.itemId) {
                        R.id.action_edit -> popupMenuListener?.editArtist(artist, binding.context)
                        R.id.action_delete -> popupMenuListener?.deleteArtist(artist)
                    }
                }
                .show(binding.context)
        }
    }
}