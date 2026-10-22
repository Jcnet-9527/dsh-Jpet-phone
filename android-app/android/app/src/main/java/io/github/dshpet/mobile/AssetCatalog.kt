package io.github.dshpet.mobile

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

data class SourceBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
}

data class ClipSpec(
    val id: String,
    val group: String,
    val fps: Double,
    val frames: Int,
    val stride: Double = 180.0,
    val curve: List<Double> = emptyList(),
    val path: String = "",
    val crop: SourceBox = SourceBox(0.0, 0.0, 640.0, 360.0),
    /** The animation's own name (``点击回应-元气挥手``); empty for old catalogs. */
    val label: String = "",
    /** Menu category (待机 / 小动作 / 点击回应 / 移动 / 转向 / 拖拽 / 余额). */
    val category: String = "",
    /** True when the frames travel inside the APK; false means "download first". */
    val bundled: Boolean = true,
    /** Unpacked frame bytes, used for download progress and cache reporting. */
    val bytes: Long = 0L,
    /**
     * True for the extra-wide animations - butterflies, balloons, fireworks.
     * Their crops span most of the 640px canvas, and paying for them in the
     * stage would leave every other animation inside a box three bodies wide.
     * They are listed under 特效 and played when asked for, never at random.
     */
    val fx: Boolean = false,
) {
    val duration get() = frames / fps

    /** What the action menu shows: the clip's own name, never its hash id. */
    val title get() = label.ifBlank { id }

    fun framePath(index: Int): String =
        "pet/$path/f_${(index.coerceIn(0, frames - 1) + 1).toString().padStart(4, '0')}.webp"
}

data class AssetCatalog(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val body: SourceBox,
    val unionCrop: SourceBox,
    val clips: List<ClipSpec>,
    val phrases: List<String>,
    val packBaseUrls: List<String> = emptyList(),
) {
    /**
     * The one stage every clip is drawn on: the union of every crop, effects
     * included. It is deliberately the wider box - roughly 213dp x 122dp at the
     * default size - because the alternative was a second, untouchable window
     * for the extra-wide animations, and Android draws pass-through overlays
     * with a capped opacity. One window, one alpha, no overlap.
     */
    val stageBox: SourceBox = WindowGeometry.union(clips)

    fun clipsIn(group: String): List<ClipSpec> = clips.filter { it.group == group }

    /** Categories in the order the menu should show them, extras appended. */
    fun categories(): List<String> {
        val preferred = PREferred_ORDER.filter { wanted -> clips.any { it.category == wanted } }
        val rest = clips.map { it.category }.filter { it.isNotBlank() && it !in preferred }.distinct()
        return preferred + rest
    }

    fun clipsInCategory(category: String): List<ClipSpec> =
        clips.filter { it.category == category }.sortedBy { it.title }

    companion object {
        /** Matches the exporter's CATEGORY_ORDER so the menu reads naturally. */
        private val PREferred_ORDER =
            listOf("待机", "小动作", "点击回应", "移动", "转向", "拖拽", "余额", "特效")

        /** Crops wider than this, in source px, are effects: ~174dp at the default size. */
        private const val FX_MAX_WIDTH = 420.0

        /** The menu group holding the effects animations. */
        private const val FX_CATEGORY = "特效"

        private fun JSONArray.box() = SourceBox(getDouble(0), getDouble(1), getDouble(2), getDouble(3))
        fun load(assets: AssetManager): AssetCatalog {
            val root = assets.open("pet/manifest.json").bufferedReader(Charsets.UTF_8).use {
                JSONObject(it.readText())
            }
            require(root.getInt("schema_version") == 1) { "Unsupported asset schema" }
            val canvas = root.getJSONArray("canvas")
            val body = root.getJSONArray("body_box").box()
            require(body.width > 0 && body.height > 0)
            val entries = root.getJSONArray("clips")
            val clips = (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                val curve = entry.optJSONArray("curve") ?: JSONArray()
                val crop = entry.getJSONArray("crop_box").box()
                val fx = crop.width > FX_MAX_WIDTH
                ClipSpec(entry.getString("id"), entry.getString("group"), entry.getDouble("fps"),
                    entry.getInt("frames"), entry.getDouble("stride"),
                    (0 until curve.length()).map { curve.getDouble(it) },
                    entry.getString("path"), crop,
                    entry.optString("name", ""),
                    if (fx) FX_CATEGORY else entry.optString("category", ""),
                    entry.optBoolean("bundled", true), entry.optLong("bytes", 0L), fx).also {
                    require(it.frames in 1..10000 && it.fps.isFinite() && it.fps in 1.0..120.0)
                    require(it.path.matches(Regex("clips/[a-z]+_[a-f0-9]{12}")))
                    require(it.crop.width > 0 && it.crop.height > 0)
                }
            }
            require(clips.map { it.id }.distinct().size == clips.size)
            require(clips.map { it.group }.containsAll(listOf("idle", "turn", "move", "click", "drag")))
            val phrases = root.getJSONArray("phrases")
            val packs = root.optJSONObject("packs")
            val urls = packs?.optJSONArray("base_urls")?.let { array ->
                (0 until array.length()).map { array.getString(it) }.filter { it.isNotBlank() }
            } ?: emptyList()
            return AssetCatalog(canvas.getInt(0), canvas.getInt(1), body,
                WindowGeometry.union(clips), clips,
                (0 until phrases.length()).map { phrases.getString(it) }, urls)
        }
    }
}
