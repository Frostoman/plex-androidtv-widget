package com.androidtv.plexwidget.net

import com.androidtv.plexwidget.model.PlexConnection
import com.androidtv.plexwidget.model.PlexItem
import com.androidtv.plexwidget.model.PlexLibrary
import com.androidtv.plexwidget.model.PlexServer
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** org.json's optString returns the literal "null" for JSON null; this returns a real null. */
private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifBlank { null }

/**
 * Talks to plex.tv (auth + server discovery) and to a Plex Media Server
 * (libraries, items, posters). Stateless apart from the stable client identifier;
 * tokens are passed in per call.
 *
 * Auth uses the keyboard-free plex.tv/link PIN flow:
 *   1) [createPin] -> show code + "plex.tv/link"
 *   2) user enters the code on another device
 *   3) poll [checkPin] until it returns an account auth token
 *
 * TLS to *.plex.direct uses Plex's publicly-trusted certificate chain, so the
 * default OkHttp trust manager is sufficient — no pinning/custom trust needed.
 */
class PlexClient(
    private val clientId: String,
    private val deviceName: String,
    private val appVersion: String,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Shorter-fused client just for probing which server connection is reachable.
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    data class Pin(val id: Long, val code: String)

    // ---- plex.tv: authentication ----------------------------------------

    /**
     * Create a link PIN. The user types [Pin.code] at plex.tv/link.
     * No `strong=true`: that yields a long 25-char code, but plex.tv/link expects
     * the short 4-character code.
     */
    fun createPin(): Pin {
        val req = baseRequest("https://plex.tv/api/v2/pins")
            .post(FormBody.Builder().build())
            .build()
        val json = JSONObject(execute(req))
        return Pin(json.getLong("id"), json.getString("code"))
    }

    /** Poll a PIN; returns the account auth token once the user has linked it, else null. */
    fun checkPin(id: Long): String? {
        val req = baseRequest("https://plex.tv/api/v2/pins/$id").get().build()
        val json = JSONObject(execute(req))
        // NB: authToken is JSON null until linked; optString would return the
        // literal "null" string, so guard with isNull.
        if (json.isNull("authToken")) return null
        return json.optString("authToken").ifBlank { null }
    }

    // ---- plex.tv: server discovery --------------------------------------

    /** Servers ("provides" contains "server") reachable by this account token. */
    fun resources(accountToken: String): List<PlexServer> {
        val req = baseRequest("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1")
            .header(HEADER_TOKEN, accountToken)
            .get()
            .build()
        val arr = JSONArray(execute(req))
        val servers = mutableListOf<PlexServer>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!o.optString("provides").contains("server")) continue
            val conns = mutableListOf<PlexConnection>()
            val connArr = o.optJSONArray("connections") ?: JSONArray()
            for (j in 0 until connArr.length()) {
                val c = connArr.getJSONObject(j)
                conns.add(
                    PlexConnection(
                        uri = c.getString("uri"),
                        local = c.optBoolean("local", false),
                        relay = c.optBoolean("relay", false),
                    ),
                )
            }
            servers.add(
                PlexServer(
                    name = o.optString("name", "Plex Server"),
                    machineId = o.getString("clientIdentifier"),
                    accessToken = o.stringOrNull("accessToken") ?: accountToken,
                    owned = o.optBoolean("owned", false),
                    connections = conns,
                ),
            )
        }
        return servers
    }

    /**
     * Probe a server's connections (LAN first, relay last) and return the first
     * that answers /identity. Falls back to the first listed connection.
     */
    fun pickReachableConnection(server: PlexServer): String? {
        val ordered = server.connections.sortedWith(
            compareBy({ !it.local }, { it.relay }),
        )
        for (conn in ordered) {
            val ok = runCatching {
                val req = Request.Builder()
                    .url("${conn.uri.trimEnd('/')}/identity")
                    .header(HEADER_TOKEN, server.accessToken)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                probeClient.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) return conn.uri.trimEnd('/')
        }
        return ordered.firstOrNull()?.uri?.trimEnd('/')
    }

    // ---- Plex Media Server: libraries & items ---------------------------

    fun librarySections(baseUri: String, serverToken: String): List<PlexLibrary> {
        val req = serverRequest("$baseUri/library/sections", serverToken).build()
        val container = JSONObject(execute(req)).getJSONObject("MediaContainer")
        val dirs = container.optJSONArray("Directory") ?: return emptyList()
        return (0 until dirs.length()).map {
            val o = dirs.getJSONObject(it)
            PlexLibrary(o.getString("key"), o.optString("type"), o.optString("title"), o.optString("agent"))
        }
    }

    fun sectionItems(baseUri: String, serverToken: String, sectionKey: String): List<PlexItem> {
        val req = serverRequest("$baseUri/library/sections/$sectionKey/all", serverToken).build()
        val container = JSONObject(execute(req)).getJSONObject("MediaContainer")
        val meta = container.optJSONArray("Metadata") ?: return emptyList()
        return (0 until meta.length()).map {
            val o = meta.getJSONObject(it)
            PlexItem(
                ratingKey = o.getString("ratingKey"),
                title = o.optString("title"),
                year = if (o.has("year")) o.optInt("year") else null,
                type = o.optString("type"),
                thumb = o.stringOrNull("thumb"),
                slug = o.stringOrNull("slug"),
                lastViewedAt = if (o.has("lastViewedAt")) o.optLong("lastViewedAt") else null,
            )
        }
    }

    /**
     * The ratingKey of the episode to play for a show: the first **unwatched** episode
     * (in season/episode order), or — if every episode is watched — the very first
     * episode (so a finished show replays from the start). Null only if the show has no
     * episodes. `allLeaves` returns all episodes flattened and ordered; `viewCount == 0`
     * means unwatched.
     */
    fun episodeToPlay(baseUri: String, serverToken: String, showRatingKey: String): String? {
        val req = serverRequest("$baseUri/library/metadata/$showRatingKey/allLeaves", serverToken).build()
        val meta = JSONObject(execute(req)).getJSONObject("MediaContainer")
            .optJSONArray("Metadata") ?: return null
        if (meta.length() == 0) return null
        for (i in 0 until meta.length()) {
            val o = meta.getJSONObject(i)
            if (o.optInt("viewCount", 0) == 0) return o.getString("ratingKey")
        }
        return meta.getJSONObject(0).getString("ratingKey") // all watched → first episode
    }

    /** A resized poster URL via the server's photo transcoder. */
    fun posterUrl(baseUri: String, serverToken: String, thumb: String, width: Int, height: Int): String {
        val encoded = URLEncoder.encode(thumb, "UTF-8")
        return "$baseUri/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1" +
            "&url=$encoded&X-Plex-Token=$serverToken"
    }

    fun download(url: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    // ---- internals ------------------------------------------------------

    private fun baseRequest(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header(HEADER_PRODUCT, PRODUCT)
            .header(HEADER_VERSION, appVersion)
            .header(HEADER_CLIENT_ID, clientId)
            .header(HEADER_PLATFORM, "Android")
            .header(HEADER_DEVICE, "Android TV")
            .header(HEADER_DEVICE_NAME, deviceName)

    private fun serverRequest(url: String, serverToken: String): Request.Builder =
        baseRequest(url).header(HEADER_TOKEN, serverToken).get()

    private fun execute(req: Request): String {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(200)}")
            return body
        }
    }

    companion object {
        const val PRODUCT = "Android TV Plex Library"

        private const val HEADER_PRODUCT = "X-Plex-Product"
        private const val HEADER_VERSION = "X-Plex-Version"
        private const val HEADER_CLIENT_ID = "X-Plex-Client-Identifier"
        private const val HEADER_PLATFORM = "X-Plex-Platform"
        private const val HEADER_DEVICE = "X-Plex-Device"
        private const val HEADER_DEVICE_NAME = "X-Plex-Device-Name"
        private const val HEADER_TOKEN = "X-Plex-Token"
    }
}
