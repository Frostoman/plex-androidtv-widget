package com.androidtv.plexwidget.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.androidtv.plexwidget.R
import com.androidtv.plexwidget.launch.PlexLauncher
import com.androidtv.plexwidget.model.PlexItem
import com.androidtv.plexwidget.sync.SyncWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Invisible trampoline for card clicks (home-screen channels and the in-app browser). The
 * Google TV launcher rewrites the package on a cross-package intent it fires directly, so
 * the card launches this (our own, always-launchable) activity instead.
 *
 * Flow: bring Plex up so it starts connecting → resolve the item's play URI meanwhile (for a
 * show, a network lookup of the episode to play) → wait for Plex to connect → fire the play
 * deep link → finish.
 *
 * The wait is the tricky part. Plex v2026.19 needs ~8-12s on a cold start, but once Plex
 * covers us, Android only lets our (now background) app start activities for ~10s — after
 * that the deep link is silently blocked ("Background activity start"). A foreground service
 * doesn't help (still blocked on Android 11). So as soon as Plex covers us we bring this
 * transparent activity back on top: having a visible window exempts us from that limit for
 * as long as we need. Plex keeps connecting underneath (verified on a real TV), and the user
 * sees Plex's home screen through our transparent window the whole time.
 *
 * The home-screen "refresh" tile also routes here: it kicks off a background sync and
 * returns immediately to the home screen.
 */
class OpenItemActivity : FragmentActivity() {

    private enum class Phase { IDLE, WARMING, DONE }

    private var phase = Phase.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.getBooleanExtra(EXTRA_REFRESH, false)) {
            SyncWorker.runOnce(applicationContext)
            Toast.makeText(applicationContext, getString(R.string.refresh_started), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val ratingKey = intent.getStringExtra(EXTRA_RATING_KEY)
        if (ratingKey == null) { finish(); return }
        val item = PlexItem(
            ratingKey = ratingKey,
            title = "",
            year = null,
            type = intent.getStringExtra(EXTRA_TYPE) ?: "movie",
            thumb = null,
            slug = intent.getStringExtra(EXTRA_SLUG),
            lastViewedAt = null,
        )
        val appCtx = applicationContext
        if (!PlexLauncher.isPlexInstalled(appCtx)) {
            runCatching { startActivity(PlexLauncher.fallbackIntent(appCtx)) }
            finish()
            return
        }

        val warm = PlexLauncher.isPlexWarm(appCtx) // read before startPlex() updates it
        val startedAt = System.currentTimeMillis()
        phase = Phase.WARMING
        PlexLauncher.startPlex(appCtx)

        lifecycleScope.launch {
            // Resolve while Plex warms up, so a show's episode lookup doesn't add to the wait.
            val uri = PlexLauncher.playUri(appCtx, item)
            if (uri != null) {
                val remaining = PlexLauncher.warmupDelayMs(warm) - (System.currentTimeMillis() - startedAt)
                if (remaining > 0) delay(remaining)
            }
            // Mark done first: the deep link puts Plex on top of us again, and onStop must
            // not bring us back this time.
            phase = Phase.DONE
            if (uri != null) PlexLauncher.openDeepLink(appCtx, uri)
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        // Plex just covered us (still well inside the background-start grace period).
        // Come back on top — transparently — to keep a visible window for the rest of the
        // warm-up, so the deep link fired later isn't blocked.
        if (phase == Phase.WARMING && !isFinishing) {
            runCatching {
                startActivity(
                    Intent(this, OpenItemActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }
    }

    // Re-fronting ourselves delivers a new intent; the launch flow is already running, so ignore it.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
    }

    companion object {
        const val EXTRA_RATING_KEY = "rating_key"
        const val EXTRA_TYPE = "type"
        const val EXTRA_SLUG = "slug"
        const val EXTRA_REFRESH = "refresh"
    }
}
