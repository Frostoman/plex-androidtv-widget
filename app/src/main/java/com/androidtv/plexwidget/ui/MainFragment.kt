package com.androidtv.plexwidget.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.lifecycleScope
import com.androidtv.plexwidget.App
import com.androidtv.plexwidget.R
import com.androidtv.plexwidget.launch.PlexLauncher
import com.androidtv.plexwidget.model.MediaKind
import com.androidtv.plexwidget.model.PlexItem
import com.androidtv.plexwidget.sync.SyncManager
import kotlinx.coroutines.launch

class MainFragment : BrowseSupportFragment() {

    private lateinit var rowsAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.browse_title)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true

        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        adapter = rowsAdapter
        setupListeners()
        rebuildRows()
        syncOnOpen()
    }

    /** Refresh the library from the server once when the app opens (silent). */
    private fun syncOnOpen() {
        val store = App.from(requireContext()).plexStore
        if (!store.isLinked) return
        lifecycleScope.launch {
            if (SyncManager(requireContext().applicationContext).sync() is SyncManager.Result.Success) {
                rebuildRows()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rebuildRows()
    }

    private fun rebuildRows() {
        val store = App.from(requireContext()).plexStore
        rowsAdapter.clear()

        if (store.isLinked) {
            addLibraryRow(MediaKind.MOVIE, getString(R.string.row_movies))
            addLibraryRow(MediaKind.SHOW, getString(R.string.row_shows))
        }

        val actions = ArrayObjectAdapter(ActionCardPresenter()).apply {
            if (store.isLinked) {
                add(UiAction(UiAction.RESYNC, getString(R.string.action_resync)))
                add(UiAction(UiAction.SELECT_MOVIES, getString(R.string.action_select_movies)))
                add(UiAction(UiAction.SELECT_SHOWS, getString(R.string.action_select_shows)))
                add(UiAction(UiAction.SORT, getString(R.string.action_sort)))
                add(UiAction(UiAction.SERVER, getString(R.string.action_server)))
                add(UiAction(UiAction.RENAME, getString(R.string.action_rename)))
                add(UiAction(UiAction.UNLINK, getString(R.string.action_unlink)))
            } else {
                add(UiAction(UiAction.LINK, getString(R.string.action_link)))
            }
        }
        rowsAdapter.add(ListRow(HeaderItem(99, getString(R.string.row_settings)), actions))
    }

    private fun addLibraryRow(kind: MediaKind, header: String) {
        val store = App.from(requireContext()).plexStore
        val items = store.loadItems(kind)
        if (items.isEmpty()) return
        val adapter = ArrayObjectAdapter(ItemCardPresenter(store))
        items.forEach { adapter.add(it) }
        rowsAdapter.add(ListRow(HeaderItem(kind.ordinal.toLong(), header), adapter))
    }

    private fun setupListeners() {
        setOnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is PlexItem -> openItem(item)
                is UiAction -> handleAction(item)
            }
        }
    }

    private fun openItem(item: PlexItem) {
        if (!PlexLauncher.isPlexInstalled(requireContext())) {
            toast(getString(R.string.plex_missing)); return
        }
        // Same path as a home-screen card: the trampoline handles Plex warm-up + deep link.
        startActivity(PlexLauncher.homeCardIntent(requireContext(), item))
    }

    private fun handleAction(action: UiAction) {
        when (action.id) {
            UiAction.LINK -> startActivity(Intent(requireContext(), PlexLinkActivity::class.java))
            UiAction.UNLINK -> {
                App.from(requireContext()).plexStore.clear()
                rebuildRows()
                toast(getString(R.string.toast_unlinked))
            }
            UiAction.RESYNC -> resync()
            UiAction.RENAME -> startActivity(Intent(requireContext(), RenameChannelActivity::class.java))
            UiAction.SELECT_MOVIES -> startActivity(
                Intent(requireContext(), SelectItemsActivity::class.java)
                    .putExtra(SelectItemsActivity.EXTRA_KIND, MediaKind.MOVIE.key),
            )
            UiAction.SELECT_SHOWS -> startActivity(
                Intent(requireContext(), SelectItemsActivity::class.java)
                    .putExtra(SelectItemsActivity.EXTRA_KIND, MediaKind.SHOW.key),
            )
            UiAction.SORT -> startActivity(Intent(requireContext(), SortActivity::class.java))
            UiAction.SERVER -> startActivity(Intent(requireContext(), ServerPickerActivity::class.java))
        }
    }

    private fun resync() {
        toast(getString(R.string.action_resync))
        lifecycleScope.launch {
            when (val r = SyncManager(requireContext().applicationContext).sync()) {
                is SyncManager.Result.Success -> {
                    rebuildRows(); toast(getString(R.string.toast_synced, r.movies, r.shows))
                }
                is SyncManager.Result.Error -> toast(r.message)
                SyncManager.Result.NotConfigured -> toast(getString(R.string.not_linked))
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
}
