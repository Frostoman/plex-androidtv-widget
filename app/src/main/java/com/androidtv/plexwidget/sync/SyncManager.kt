package com.androidtv.plexwidget.sync

import android.content.Context
import android.os.Build
import com.androidtv.plexwidget.App
import com.androidtv.plexwidget.data.PlexStore
import com.androidtv.plexwidget.model.MediaKind
import com.androidtv.plexwidget.model.PlexItem
import com.androidtv.plexwidget.net.PlexClient
import com.androidtv.plexwidget.tv.TvChannelPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches both libraries (movies + shows) + posters from the **chosen** server and
 * refreshes the local cache and the two home-screen channels.
 *
 * Server selection is a deliberate, manual choice (Settings → Server). Sync never
 * switches servers on its own: if the chosen server's connection is stale it only
 * re-resolves *that same server's* address, and if the server is unreachable the sync
 * simply fails and the previously cached library stays on screen — we never silently
 * fall back to a different server.
 */
class SyncManager(private val context: Context) {

    sealed interface Result {
        data class Success(val movies: Int, val shows: Int) : Result
        data object NotConfigured : Result
        data class Error(val message: String) : Result
    }

    suspend fun sync(): Result = withContext(Dispatchers.IO) {
        val app = App.from(context)
        val store = app.plexStore
        val client = app.plexClient
        val token = store.accountToken ?: return@withContext Result.NotConfigured
        // A server must have been chosen (Settings → Server / first link). We never pick one here.
        val machineId = store.serverMachineId ?: return@withContext Result.NotConfigured

        try {
            // First attempt with the stored connection; on failure, refresh the connection
            // to the SAME chosen server only — never switch to a different one.
            val counts = try {
                fetchAndPublish(client, store)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "sync: connection failed, refreshing chosen server", e)
                if (refreshConnection(client, store, token, machineId)) {
                    fetchAndPublish(client, store)
                } else {
                    // Chosen server unreachable — keep the existing data/channels untouched.
                    throw e
                }
            }
            Result.Success(counts.first, counts.second)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "sync failed — keeping previously synced library", e)
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Re-resolve a reachable address for the **chosen** server (matched by machineId) and
     * update the stored connection. Returns false if that server isn't currently available
     * — in which case the caller keeps the old data and does NOT switch servers.
     */
    private fun refreshConnection(client: PlexClient, store: PlexStore, token: String, machineId: String): Boolean {
        val server = client.resources(token).firstOrNull { it.machineId == machineId } ?: return false
        val uri = client.pickReachableConnection(server) ?: return false
        store.serverName = server.name
        store.serverToken = server.accessToken
        store.serverBaseUri = uri
        android.util.Log.i(TAG, "refreshConnection: ${server.name} -> $uri")
        return true
    }

    /** Returns (movieCount, showCount). */
    private fun fetchAndPublish(client: PlexClient, store: PlexStore): Pair<Int, Int> {
        val base = store.serverBaseUri ?: error("no server uri")
        val stoken = store.serverToken ?: error("no server token")

        val sections = client.librarySections(base, stoken)
        var movieCount = 0
        var showCount = 0

        for (kind in MediaKind.entries) {
            val items = sections
                // Exclude generic "Other Videos" libraries (unmatched home videos).
                .filter { it.type == kind.serverType && it.agent != AGENT_NONE }
                .flatMap { client.sectionItems(base, stoken, it.key) }
                .sortedWith(store.sortOrder.comparator())
            store.saveItems(kind, items)
            downloadPosters(client, store, base, stoken, items)
            if (kind == MediaKind.MOVIE) movieCount = items.size else showCount = items.size

            if (TvChannelPublisher.isSupported() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { TvChannelPublisher(context).publish(kind, store, items) }
                    .onFailure { android.util.Log.e(TAG, "publish $kind failed", it) }
            }
        }
        android.util.Log.i(TAG, "sync: $movieCount movies, $showCount shows")
        return movieCount to showCount
    }

    private fun downloadPosters(
        client: PlexClient,
        store: PlexStore,
        base: String,
        token: String,
        items: List<PlexItem>,
    ) {
        for (item in items) {
            val thumb = item.thumb ?: continue
            val file = store.posterFile(item.ratingKey)
            if (file.exists() && file.length() > 0) continue
            runCatching {
                val url = client.posterUrl(base, token, thumb, POSTER_W, POSTER_H)
                val bytes = client.download(url)
                if (bytes.isNotEmpty()) file.writeBytes(bytes)
            }
        }
    }

    private companion object {
        const val TAG = "PlexSync"
        const val POSTER_W = 300
        const val POSTER_H = 450
        const val AGENT_NONE = "com.plexapp.agents.none"
    }
}
