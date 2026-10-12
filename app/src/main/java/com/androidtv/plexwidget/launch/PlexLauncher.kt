package com.androidtv.plexwidget.launch

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.androidtv.plexwidget.App
import com.androidtv.plexwidget.model.PlexItem
import com.androidtv.plexwidget.ui.MainActivity
import com.androidtv.plexwidget.ui.OpenItemActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens the official Plex app for a library item. Behaviour by type (all verified on
 * a real Android TV against the installed Plex app):
 *
 *  - MOVIE: a play deep link for the movie (see [playbackUri]) starts playback in Plex's
 *    player.
 *  - SHOW: we look up the show's **first unwatched episode** (`/allLeaves`, first with
 *    `viewCount == 0`) and play that episode with the same deep link — so a click resumes
 *    the series where the user left off, skipping watched episodes. If every episode is
 *    watched, the first episode plays; if the lookup fails, Plex just opens.
 *
 * Home-screen cards can't carry these cross-package intents directly (the Google TV
 * launcher rewrites the target package); they launch our [OpenItemActivity] trampoline,
 * which resolves + re-issues the intent from our process.
 */
object PlexLauncher {

    const val PLEX_PACKAGE = "com.plexapp.android"

    fun isPlexInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PLEX_PACKAGE, 0); true
    }.getOrDefault(false)

    /**
     * Direct-playback intent for a server item (movie or episode ratingKey).
     *
     * The Plex app v2026.19 (React Native rewrite) changed the launch format: the old
     * `plex://server://…/library/metadata/{id}` now just opens the app. The new app plays
     * via `plex://watch/video?uri=<url-encoded server item uri>` — the same scheme its own
     * home-screen (Watch Next) cards use. Verified on a real Android TV.
     */
    private fun playbackUri(machineId: String, ratingKey: String): Uri {
        val item = "server://$machineId/com.plexapp.plugins.library/library/metadata/$ratingKey"
        return Uri.parse("plex://watch/video?uri=" + Uri.encode(item))
    }

    /**
     * Resolve the Plex play URI for an item, or null (→ just open the Plex app) if we can't.
     * Movies resolve instantly; shows make a network call to find the episode to play, so
     * call this off the main thread.
     */
    suspend fun playUri(context: Context, item: PlexItem): Uri? = withContext(Dispatchers.IO) {
        val store = App.from(context).plexStore
        val machineId = store.serverMachineId ?: return@withContext null

        if (item.type != "show") {
            return@withContext playbackUri(machineId, item.ratingKey)
        }

        val base = store.serverBaseUri
        val token = store.serverToken
        if (base != null && token != null) {
            val episode = runCatching {
                App.from(context).plexClient.episodeToPlay(base, token, item.ratingKey)
            }.getOrNull()
            if (episode != null) return@withContext playbackUri(machineId, episode)
        }
        null
    }

    /*
     * Launching works around a cold-start race: a play deep link that arrives before Plex has
     * connected to the server is rejected — Plex v2026.19 shows "Can't Reach Server / Failed
     * to fetch play queue response" (not connected yet) or "Item not known when attempting to
     * build decision" (still connecting), and nothing plays. So the caller ([OpenItemActivity])
     * first brings Plex up with [startPlex], lets it connect for [warmupDelayMs], then fires
     * [openDeepLink]. Plex keeps connecting even while it isn't the top activity.
     *
     * The React-Native rewrite connects much slower than the old native app: a cold start
     * needs ~8-12s before a play link takes; a warm app (launched seconds ago, still
     * connected) needs only a few. That's longer than the ~10s an app gets to start activities
     * after leaving the foreground, so the trampoline keeps a (transparent) window on top
     * during the wait — see OpenItemActivity.
     */

    /**
     * True if Plex is likely still running and connected: we launched it recently, and it
     * hasn't been force-stopped since (FLAG_STOPPED is set by a force stop and cleared on the
     * next launch). A process the system silently evicted can't be detected; the short
     * [WARM_WINDOW_MS] keeps that risk low.
     */
    fun isPlexWarm(context: Context): Boolean {
        val recent = System.currentTimeMillis() - App.from(context).plexStore.lastPlexLaunchAt < WARM_WINDOW_MS
        if (!recent) return false
        val stopped = runCatching {
            context.packageManager.getApplicationInfo(PLEX_PACKAGE, 0).flags and
                android.content.pm.ApplicationInfo.FLAG_STOPPED != 0
        }.getOrDefault(true)
        return !stopped
    }

    /** How long to let Plex connect before firing the play deep link. */
    fun warmupDelayMs(warm: Boolean): Long = if (warm) WARM_DELAY_MS else COLD_DELAY_MS

    /** Bring Plex up (cold-start it, or surface it if running) so it starts connecting. */
    fun startPlex(context: Context) {
        context.packageManager.getLaunchIntentForPackage(PLEX_PACKAGE)?.let {
            runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        App.from(context).plexStore.lastPlexLaunchAt = System.currentTimeMillis()
    }

    /** Fire the play deep link at Plex. */
    fun openDeepLink(context: Context, uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(PLEX_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(view) }
        App.from(context).plexStore.lastPlexLaunchAt = System.currentTimeMillis()
    }

    /**
     * Intent to put on a home-screen preview program: targets our [OpenItemActivity]
     * trampoline, carrying the item identity so the trampoline can resolve + launch it.
     */
    fun homeCardIntent(context: Context, item: PlexItem): Intent =
        Intent(context, OpenItemActivity::class.java).apply {
            putExtra(OpenItemActivity.EXTRA_RATING_KEY, item.ratingKey)
            putExtra(OpenItemActivity.EXTRA_TYPE, item.type)
            putExtra(OpenItemActivity.EXTRA_SLUG, item.slug)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Intent for the home-screen "refresh" tile: triggers a background library sync. */
    fun refreshIntent(context: Context): Intent =
        Intent(context, OpenItemActivity::class.java).apply {
            putExtra(OpenItemActivity.EXTRA_REFRESH, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Fallback target so a card is never a dead end. */
    fun fallbackIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // Plex 2026.19 (React Native) connects slowly on a cold start: ~8-12s before a play deep
    // link takes (measured on a real Android TV; 6-9s intermittently landed on "can't reach
    // server"). 13s leaves margin for that variance and a slow server wake. A warm app
    // (launched within the window below, still connected) only needs a few seconds. The
    // window is deliberately short so we don't treat a long-backgrounded (possibly evicted)
    // Plex as warm.
    private const val COLD_DELAY_MS = 13000L
    private const val WARM_DELAY_MS = 4000L
    private const val WARM_WINDOW_MS = 2 * 60 * 1000L
}
