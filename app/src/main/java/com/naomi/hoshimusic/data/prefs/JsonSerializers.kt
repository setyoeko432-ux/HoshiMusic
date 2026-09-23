package com.naomi.hoshimusic.data.prefs

import com.naomi.hoshimusic.data.LocalPlaylist
import com.naomi.hoshimusic.data.PeqBand
import com.naomi.hoshimusic.data.PeqProfile
import com.naomi.hoshimusic.data.SavedQueue
import com.naomi.hoshimusic.playback.dsp.CustomDspPreset
import org.json.JSONArray
import org.json.JSONObject

internal object JsonSerializers {

    fun decodePlaylists(raw: String?): List<LocalPlaylist> = runCatching {
        val array = JSONArray(raw ?: "[]")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val ids = item.optJSONArray("songIds") ?: JSONArray()
            LocalPlaylist(item.optString("id"), item.optString("name", "Playlist"), List(ids.length()) { ids.getString(it) }.distinct(), item.optLong("createdAtMs", 0L))
        }
    }.getOrDefault(emptyList())

    fun encodePlaylists(items: List<LocalPlaylist>): String = JSONArray().apply {
        items.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("songIds", JSONArray(p.songIds)).put("createdAtMs", p.createdAtMs)) }
    }.toString()

    fun decodeQueues(raw: String?): List<SavedQueue> = runCatching {
        val array = JSONArray(raw ?: "[]")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val ids = item.optJSONArray("songIds") ?: JSONArray()
            SavedQueue(item.optString("id"), item.optString("name", "Antrean"), List(ids.length()) { ids.getString(it) }.distinct(), item.optLong("updatedAtMs", 0L))
        }
    }.getOrDefault(emptyList())

    fun encodeQueues(items: List<SavedQueue>): String = JSONArray().apply {
        items.forEach { q -> put(JSONObject().put("id", q.id).put("name", q.name).put("songIds", JSONArray(q.songIds)).put("updatedAtMs", q.updatedAtMs)) }
    }.toString()

    fun decodeCustomDspPresets(raw: String?): List<CustomDspPreset> = runCatching {
        val array = JSONArray(raw ?: "[]")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val params = item.optJSONArray("params") ?: JSONArray()
            CustomDspPreset(item.optString("id"), item.optString("title", "Preset"), List(params.length()) { params.optDouble(it).toFloat() })
        }
    }.getOrDefault(emptyList())

    fun encodeCustomDspPresets(items: List<CustomDspPreset>): String = JSONArray().apply {
        items.forEach { p -> put(JSONObject().put("id", p.id).put("title", p.title).put("params", JSONArray(p.params))) }
    }.toString()

    fun decodePeqProfiles(raw: String?): List<PeqProfile> = runCatching {
        val array = JSONArray(raw ?: "[]")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val bands = item.optJSONArray("bands") ?: JSONArray()
            PeqProfile(
                item.optString("id"),
                item.optString("name", "Profil"),
                List(bands.length()) { b ->
                    val band = bands.getJSONObject(b)
                    PeqBand(band.optInt("type", 0), band.optDouble("fc", 1000.0).toFloat(), band.optDouble("gain", 0.0).toFloat(), band.optDouble("q", 0.707).toFloat())
                }.take(10),
                item.optDouble("preamp", 0.0).toFloat()
            )
        }
    }.getOrDefault(emptyList())

    fun encodePeqProfiles(items: List<PeqProfile>): String = JSONArray().apply {
        items.forEach { profile ->
            val bands = JSONArray()
            profile.bands.forEach { b -> bands.put(JSONObject().put("type", b.type).put("fc", b.fc.toDouble()).put("gain", b.gain.toDouble()).put("q", b.q.toDouble())) }
            put(JSONObject().put("id", profile.id).put("name", profile.name).put("bands", bands).put("preamp", profile.preamp.toDouble()))
        }
    }.toString()

    fun decodeRouteProfiles(raw: String?): Map<String, String> = runCatching {
        val obj = JSONObject(raw ?: "{}")
        buildMap {
            obj.keys().forEachRemaining { key -> put(key, obj.optString(key)) }
        }
    }.getOrDefault(emptyMap())

    fun encodeRouteProfiles(items: Map<String, String>): String = JSONObject().apply {
        items.forEach { (key, value) -> put(key, value) }
    }.toString()
}
