package com.zionhuang.music.ui.fragments

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.transition.MaterialElevationScale
import com.google.android.material.transition.MaterialFadeThrough
import com.zionhuang.music.R
import com.zionhuang.music.databinding.LayoutRecyclerviewBinding
import com.zionhuang.music.extensions.addOnClickListener
import com.zionhuang.music.ui.adapters.ArtistsAdapter
import com.zionhuang.music.ui.fragments.base.BindingFragment
import com.zionhuang.music.viewmodels.ArtistsViewModel
import com.zionhuang.music.viewmodels.SongsViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ArtistsFragment : BindingFragment<LayoutRecyclerviewBinding>() {
    override fun getViewBinding() = LayoutRecyclerviewBinding.inflate(layoutInflater)

    private val songsViewModel by activityViewModels<SongsViewModel>()
    private val artistsViewModel by viewModels<ArtistsViewModel>()
    private val artistsAdapter = ArtistsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough().setDuration(resources.getInteger(R.integer.motion_duration_large).toLong())
        exitTransition = MaterialFadeThrough().setDuration(resources.getInteger(R.integer.motion_duration_large).toLong())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        artistsAdapter.popupMenuListener = artistsViewModel.popupMenuListener

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = artistsAdapter
            addOnClickListener { position, view ->
                exitTransition = MaterialElevationScale(false).setDuration(resources.getInteger(R.integer.motion_duration_large).toLong())
                reenterTransition = MaterialElevationScale(true).setDuration(resources.getInteger(R.integer.motion_duration_large).toLong())
                val transitionName = getString(R.string.artist_songs_transition_name)
                val extras = FragmentNavigatorExtras(view to transitionName)
                val directions = ArtistsFragmentDirections.actionArtistsFragmentToArtistSongsFragment(artistsAdapter.getItemByPosition(position)!!.id!!)
                findNavController().navigate(directions, extras)
            }
        }

        lifecycleScope.launch {
            songsViewModel.allArtistsFlow.collectLatest {
                artistsAdapter.submitData(it)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> findNavController().navigate(SettingsFragmentDirections.openSettingsFragment())
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.settings, menu)
    }

    companion object {
        const val TAG = "ArtistsFragment"
    }
}