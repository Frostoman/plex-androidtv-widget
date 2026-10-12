package com.androidtv.plexwidget.tv

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.androidtv.plexwidget.R
import com.androidtv.plexwidget.data.PlexStore
import com.androidtv.plexwidget.launch.PlexLauncher
import com.androidtv.plexwidget.model.MediaKind
import com.androidtv.plexwidget.model.PlexItem
import com.androidtv.plexwidget.ui.MainActivity
import java.net.URLEncoder

/**
 * Publishes each library as its own preview channel + program cards on the
 * Android TV home screen — one channel for Movies, one for TV Shows. Requires
 * Android O (API 26) and a launcher that supports channels.
 *
 * NOTE: androidx.tvprovider 1.0.0's updatePreviewChannel()/updatePreviewProgram()
 * crash with an NPE when the stored row has a null description (they read it back
 * via fromCursor() -> setDescription(null)). We therefore avoid those entirely:
 * the channel name is updated directly through ContentResolver, and programs are
 * wiped + republished each sync (also prevents duplicate cards). Stray channels left
 * behind when our app data was cleared are removed on every sync (see reconcileChannels).
 */
class TvChannelPublisher(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val helper = PreviewChannelHelper(context)

    @RequiresApi(Build.VERSION_CODES.O)
    fun publish(kind: MediaKind, store: PlexStore, items: List<PlexItem>) {
        val channelId = ensureChannel(kind)

        // Only items the user kept checked appear in the home-screen widget.
        val visible = items.filter { store.isVisible(kind, it.ratingKey) }

        // Clear existing programs before republishing, deleting per id (launchers reject a
        // selection clause on a preview_program delete):
        // 1) the programs we published last time;
        val oldIds = readProgramIds(kind)
        for (id in oldIds) runCatching { helper.deletePreviewProgram(id) }
        // 2) anything else of ours still on the channel (e.g. the channel was re-adopted
        //    after our app data was cleared).
        for (id in ownProgramIds(channelId)) runCatching { helper.deletePreviewProgram(id) }
        android.util.Log.i(TAG, "publish[$kind]: removed ${oldIds.size} tracked, adding ${visible.size}/${items.size}")

        val type = if (kind == MediaKind.SHOW) {
            TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
        } else {
            TvContractCompat.PreviewPrograms.TYPE_MOVIE
        }

        val newIds = mutableListOf<Long>()
        for (item in visible) {
            val intent = PlexLauncher.homeCardIntent(context, item)
            val posterUri = posterHttpUri(store, item)

            val builder = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(type)
                .setTitle(item.title)
                .setDescription(item.year?.toString() ?: "") // non-null avoids fromCursor NPE
                .setIntent(intent)
                .setInternalProviderId(item.ratingKey)
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)

            if (posterUri != null) builder.setPosterArtUri(posterUri)

            runCatching { helper.publishPreviewProgram(builder.build()) }
                .onSuccess { newIds.add(it) }
                .onFailure { android.util.Log.e(TAG, "publish program '${item.title}' failed", it) }
        }

        // A "refresh" tile at the end of the channel: clicking it triggers a sync.
        val refreshPoster = refreshPosterUri()
        val refresh = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
            .setTitle("") // no caption — the icon alone identifies the refresh tile
            .setDescription("")
            .setIntent(PlexLauncher.refreshIntent(context))
            .setInternalProviderId("refresh_$kind")
            .setPosterArtUri(refreshPoster)
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
            .build()
        runCatching { helper.publishPreviewProgram(refresh) }
            .onSuccess { newIds.add(it) }
            .onFailure { android.util.Log.w(TAG, "publish refresh tile failed", it) }

        writeProgramIds(kind, newIds)
        android.util.Log.i(TAG, "publish[$kind]: published ${newIds.size} programs")
    }

    private fun readProgramIds(kind: MediaKind): List<Long> = runCatching {
        val arr = org.json.JSONArray(prefs.getString(programsKey(kind), "[]"))
        (0 until arr.length()).map { arr.getLong(it) }
    }.getOrDefault(emptyList())

    private fun writeProgramIds(kind: MediaKind, ids: List<Long>) {
        val arr = org.json.JSONArray()
        ids.forEach { arr.put(it) }
        prefs.edit().putString(programsKey(kind), arr.toString()).apply()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureChannel(kind: MediaKind): Long {
        reconcileChannels()
        val displayName = channelName(kind)
        val existing = prefs.getLong(channelIdKey(kind), -1L)

        fun writeName(id: Long): Int {
            val values = ContentValues().apply {
                put(TvContractCompat.Channels.COLUMN_DISPLAY_NAME, displayName)
                // Tag (or re-tag channels from older versions) so we can recognise it later.
                put(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID, channelTag(kind))
            }
            return runCatching {
                context.contentResolver.update(TvContractCompat.buildChannelUri(id), values, null, null)
            }.onFailure { android.util.Log.e(TAG, "channel rename failed", it) }.getOrDefault(0)
        }

        if (existing >= 0) {
            val rows = writeName(existing)
            if (rows > 0) return existing
            android.util.Log.w(TAG, "ensureChannel[$kind]: stored channel missing, recreating")
        }

        val channel = PreviewChannel.Builder()
            .setDisplayName(displayName)
            .setDescription("") // non-null avoids fromCursor NPE on later reads
            .setInternalProviderId(channelTag(kind))
            .setAppLinkIntent(Intent(context, MainActivity::class.java))
            .setLogo(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888))
            .build()

        val id = helper.publishDefaultChannel(channel)
        prefs.edit().putLong(channelIdKey(kind), id).apply()
        writeName(id)
        android.util.Log.i(TAG, "ensureChannel[$kind]: published new channel id=$id name=$displayName")
        runCatching { TvContractCompat.requestChannelBrowsable(context, id) }
            .onFailure { android.util.Log.w(TAG, "requestChannelBrowsable not handled", it) }
        return id
    }

    /**
     * Our channels live in the system TvProvider, but their ids live in our own prefs. If our
     * app data is cleared, we lose track of them and create new ones on the next sync — the
     * old channels stay on the home screen (and in the launcher's channel list) with their
     * last, never-updated content, so titles appear duplicated and long-deleted titles linger.
     * Uninstalling removes them; clearing data does not.
     *
     * So before every publish: keep one channel per kind — the tracked one, else adopt an
     * existing channel tagged with that kind — and delete every other channel we own.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun reconcileChannels() {
        val owned = ownChannels() ?: return // query failed: leave everything as is
        val keep = mutableSetOf<Long>()
        for (kind in MediaKind.entries) {
            val tracked = prefs.getLong(channelIdKey(kind), -1L)
            val id = owned.firstOrNull { it.first == tracked }?.first
                ?: owned.firstOrNull { it.second == channelTag(kind) && it.first !in keep }?.first
            if (id == null) {
                prefs.edit().remove(channelIdKey(kind)).remove(programsKey(kind)).apply()
                continue
            }
            keep += id
            if (id != tracked) {
                // Its programs aren't in our tracked list; publish() sweeps them by channel.
                prefs.edit().putLong(channelIdKey(kind), id).remove(programsKey(kind)).apply()
                android.util.Log.i(TAG, "reconcile[$kind]: adopted existing channel id=$id")
            }
        }
        for ((id, _) in owned) {
            if (id in keep) continue
            runCatching { context.contentResolver.delete(TvContractCompat.buildChannelUri(id), null, null) }
                .onSuccess { android.util.Log.i(TAG, "reconcile: deleted orphan channel id=$id") }
                .onFailure { android.util.Log.w(TAG, "reconcile: delete channel id=$id failed", it) }
        }
    }

    /** (id, internal provider id) of every channel we own; TvProvider only returns our own. */
    private fun ownChannels(): List<Pair<Long, String?>>? = runCatching {
        context.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(TvContractCompat.Channels._ID, TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID),
            null, null, null,
        )?.use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to (if (c.isNull(1)) null else c.getString(1))) }
        }
    }.onFailure { android.util.Log.w(TAG, "channel query failed", it) }.getOrNull()

    /** Ids of our preview programs on [channelId] (filtered here: some launchers reject selections). */
    private fun ownProgramIds(channelId: Long): List<Long> = runCatching {
        context.contentResolver.query(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            arrayOf(TvContractCompat.PreviewPrograms._ID, TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID),
            null, null, null,
        )?.use { c ->
            buildList { while (c.moveToNext()) if (c.getLong(1) == channelId) add(c.getLong(0)) }
        } ?: emptyList()
    }.onFailure { android.util.Log.w(TAG, "program query failed", it) }.getOrDefault(emptyList())

    /** User-defined channel name (persisted); falls back to the per-kind default string. */
    private fun channelName(kind: MediaKind): String =
        prefs.getString(channelNameKey(kind), null)?.ifBlank { null }
            ?: context.getString(defaultNameRes(kind))

    /** Apply just a channel's display name (fast, no network). No-op if not yet created. */
    @RequiresApi(Build.VERSION_CODES.O)
    fun applyChannelName(kind: MediaKind) {
        val id = prefs.getLong(channelIdKey(kind), -1L)
        if (id < 0) return
        val values = ContentValues().apply {
            put(TvContractCompat.Channels.COLUMN_DISPLAY_NAME, channelName(kind))
        }
        runCatching {
            context.contentResolver.update(TvContractCompat.buildChannelUri(id), values, null, null)
        }.onFailure { android.util.Log.e(TAG, "applyChannelName failed", it) }
    }

    /**
     * Poster art for the home-screen card. We hand the launcher a direct HTTPS URL
     * to the Plex photo transcoder rather than a local content:// file: launchers
     * download http(s) art reliably, whereas content-URI support is spotty (e.g.
     * the Mi TV launcher renders only grey placeholders for content:// art).
     */
    /**
     * The refresh tile's poster as a self-contained `data:` URI. This launcher renders
     * only http(s) / data art (not content:// or android.resource://), so we rasterise
     * our bundled icon to a PNG and base64-embed it — no server, no file provider.
     */
    private fun refreshPosterUri(): Uri {
        val w = 180
        val h = 270
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_refresh_tile)?.apply {
            setBounds(0, 0, w, h)
            draw(canvas)
        }
        val bytes = java.io.ByteArrayOutputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bmp.recycle()
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return Uri.parse("data:image/png;base64,$b64")
    }

    private fun posterHttpUri(store: PlexStore, item: PlexItem): Uri? {
        val base = store.serverBaseUri ?: return null
        val token = store.serverToken ?: return null
        val thumb = item.thumb ?: return null
        val encoded = URLEncoder.encode(thumb, "UTF-8")
        return Uri.parse(
            "$base/photo/:/transcode?width=$POSTER_W&height=$POSTER_H&minSize=1&upscale=1" +
                "&url=$encoded&X-Plex-Token=$token",
        )
    }

    private fun defaultNameRes(kind: MediaKind): Int = when (kind) {
        MediaKind.MOVIE -> R.string.channel_name_movies
        MediaKind.SHOW -> R.string.channel_name_shows
    }

    private fun channelTag(kind: MediaKind) = "plexwidget_${kind.key}"
    private fun channelIdKey(kind: MediaKind) = "channel_id_${kind.key}"
    private fun programsKey(kind: MediaKind) = "programs_${kind.key}"
    fun channelNameKey(kind: MediaKind) = "channel_name_${kind.key}"

    companion object {
        private const val TAG = "PlexChannel"
        private const val POSTER_W = 300
        private const val POSTER_H = 450
        const val PREFS = "channel"

        fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }
}
