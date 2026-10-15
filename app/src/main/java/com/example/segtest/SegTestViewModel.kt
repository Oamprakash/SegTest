package com.example.segtest

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

enum class ShotCategory(val label: String) {
    WHITE_ON_WHITE("White on white"),
    HANGER("On hanger"),
    FRINGE("Fringe / fur / knit"),
    SHINY("Shiny / metallic"),
    DARK_ON_DARK("Black on dark"),
    BUSY_BG("Busy background"),
    FLAT_LAY("Flat lay"),
    CONTROL("Easy control"),
}

enum class Rating { NONE, PASS, FIXABLE, FAIL }

enum class Preview(val label: String) {
    DARK_CLEAN("Cleaned · dark"), DARK("Raw · dark"), WHITE_CLEAN("Cleaned · white"), WHITE("Raw · white"),
    UNCERTAIN("Edges"), ORIGINAL("Original"),
}

/** Is the edge-cleaned cutout better than the raw one? */
enum class CleanupVerdict { NONE, BETTER, SAME, WORSE }

/** Every run exports at this size, whatever size ML Kit segments at: that's the v1 pipeline. */
const val EXPORT_LONG_EDGE = 2048

data class SegItem(
    val id: Int,
    val fileStem: String,
    val category: ShotCategory,
    val longEdge: Int,
    val width: Int,
    val height: Int,
    val latencyMs: Long,
    val subjectCount: Int,
    val subjectBoxShares: List<Float>,
    val metrics: MaskMetrics,
    val inputInfo: String,
    val exportWidth: Int,
    val exportHeight: Int,
    /** Mask upscale + edge cleanup time at export size, on top of [latencyMs]. */
    val cleanupMs: Long,
    val previews: Map<Preview, Bitmap>,
    val rating: Rating = Rating.NONE,
    val cleanupVerdict: CleanupVerdict = CleanupVerdict.NONE,
)

data class SegTestState(
    val category: ShotCategory = ShotCategory.WHITE_ON_WHITE,
    val longEdge: Int = 1024,
    val status: String = "Pick photos for a category to start.",
    val busy: Boolean = false,
    val coldStartMs: Long? = null,
    val items: List<SegItem> = emptyList(),
    val sessionDir: String? = null,
)

class SegTestViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = SegmentationEngine(app)
    private val _state = MutableStateFlow(SegTestState())
    val state: StateFlow<SegTestState> = _state

    private val sessionDir: File by lazy {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        File(app.getExternalFilesDir("segtest"), "session_$stamp").apply { mkdirs() }
    }
    private var nextId = 1
    private var ready = false

    fun setCategory(c: ShotCategory) = _state.update { it.copy(category = c) }
    fun setLongEdge(px: Int) = _state.update { it.copy(longEdge = px) }

    fun rate(id: Int, r: Rating) {
        _state.update { s -> s.copy(items = s.items.map { if (it.id == id) it.copy(rating = r) else it }) }
        writeCsv()
    }

    fun judgeCleanup(id: Int, v: CleanupVerdict) {
        _state.update { s -> s.copy(items = s.items.map { if (it.id == id) it.copy(cleanupVerdict = v) else it }) }
        writeCsv()
    }

    fun process(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val category = _state.value.category
        val longEdge = _state.value.longEdge
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                if (!ready) {
                    engine.ensureModel { msg -> _state.update { it.copy(status = msg) } }
                    _state.update { it.copy(status = "Warming up…") }
                    val cold = engine.warmUp()
                    _state.update { it.copy(coldStartMs = cold) }
                    ready = true
                }
                uris.forEachIndexed { n, uri ->
                    _state.update { it.copy(status = "Segmenting ${n + 1}/${uris.size} (${category.label})…") }
                    val item = runCatching { processOne(uri, category, longEdge) }
                    item.onSuccess { i -> _state.update { it.copy(items = listOf(i) + it.items) } }
                    item.onFailure { e -> _state.update { it.copy(status = "Failed on one image: ${e.message}") } }
                }
                writeCsv()
                _state.update { it.copy(status = "Done. Rate each result, then pull the session folder.", sessionDir = sessionDir.absolutePath) }
            } catch (e: Exception) {
                _state.update { it.copy(status = "Error: ${e.message}") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun processOne(uri: Uri, category: ShotCategory, longEdge: Int): SegItem {
        val app = getApplication<Application>()
        // Decode once at export size; ML Kit gets a downscaled copy at the size being tested.
        val decoded = withContext(Dispatchers.IO) { SegmentationEngine.decode(app, uri, EXPORT_LONG_EDGE) }
        val inputInfo = SegmentationEngine.describe(decoded)
        val full = withContext(Dispatchers.Default) { SegmentationEngine.normalize(decoded) }
        val fw = full.width
        val fh = full.height
        val segInput = withContext(Dispatchers.Default) {
            val s = longEdge.toFloat() / max(fw, fh)
            if (s >= 1f) full
            else Bitmap.createScaledBitmap(full, (fw * s).roundToInt(), (fh * s).roundToInt(), true)
        }
        val out = engine.segment(segInput)

        return withContext(Dispatchers.Default) {
            val metrics = MaskAnalyzer.analyze(out.mask, segInput.width, segInput.height)

            // v1 pipeline: clean edges on the small mask (cheap), then scale both masks up to the export photo
            // with the native bitmap scaler. Cleaning at full size took ~2.3 s per photo in the first run.
            val t = SystemClock.elapsedRealtime()
            val sw = segInput.width
            val sh = segInput.height
            // ~2 px at export size, expressed in segmentation pixels (1 px at 1024, 2 px at 1600).
            val px = max(1, (2f * max(sw, sh) / max(fw, fh)).roundToInt())
            val segClean = MaskOps.cleanEdges(out.mask, sw, sh, erodePx = px, featherPx = px)
            val rawMask = MaskOps.resizeNative(out.mask, sw, sh, fw, fh)
            val cleanMask = MaskOps.resizeNative(segClean, sw, sh, fw, fh)
            val cleanupMs = SystemClock.elapsedRealtime() - t

            val id = nextId++
            val stem = "%03d_%s".format(Locale.US, id, category.name.lowercase())
            val darkBg = Color.rgb(24, 24, 28)

            val dark = Compositor.onColor(full, rawMask, darkBg)
            val darkClean = Compositor.onColor(full, cleanMask, darkBg)
            val white = Compositor.onColor(full, rawMask, Color.WHITE)
            val whiteClean = Compositor.onColor(full, cleanMask, Color.WHITE)
            val edges = Compositor.uncertainty(full, rawMask)

            withContext(Dispatchers.IO) {
                save(full, "${stem}_original.jpg")
                save(Compositor.cutout(full, rawMask), "${stem}_cutout_raw.png")
                save(Compositor.cutout(full, cleanMask), "${stem}_cutout_clean.png")
                save(dark, "${stem}_on_dark_raw.jpg")
                save(darkClean, "${stem}_on_dark_clean.jpg")
                save(edges, "${stem}_edges.jpg")
            }

            SegItem(
                id = id,
                fileStem = stem,
                category = category,
                longEdge = longEdge,
                width = segInput.width,
                height = segInput.height,
                latencyMs = out.latencyMs,
                subjectCount = out.subjectCount,
                subjectBoxShares = out.subjectBoxShares,
                metrics = metrics,
                inputInfo = inputInfo,
                exportWidth = fw,
                exportHeight = fh,
                cleanupMs = cleanupMs,
                previews = mapOf(
                    Preview.DARK_CLEAN to Compositor.scaledForDisplay(darkClean),
                    Preview.DARK to Compositor.scaledForDisplay(dark),
                    Preview.WHITE_CLEAN to Compositor.scaledForDisplay(whiteClean),
                    Preview.WHITE to Compositor.scaledForDisplay(white),
                    Preview.UNCERTAIN to Compositor.scaledForDisplay(edges),
                    Preview.ORIGINAL to Compositor.scaledForDisplay(full),
                ),
            )
        }
    }

    private fun save(b: Bitmap, name: String) {
        val png = name.endsWith(".png")
        File(sessionDir, name).outputStream().use {
            b.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 90, it)
        }
    }

    /** Rewritten on every change, so ratings are never lost if the app dies mid-session. */
    private fun writeCsv() {
        val s = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            val header = "file,category,long_edge,width,height,latency_ms,cold_start_ms,subjects,subject_box_shares," +
                "fg_fraction,soft_edge_ratio,fragments,largest_share,hole_fraction,touches_border,auto_flags,rating,input_info,export_w,export_h,cleanup_ms,cleanup_verdict"
            val rows = s.items.sortedBy { it.id }.map { i ->
                val m = i.metrics
                listOf(
                    i.fileStem, i.category.name, i.longEdge, i.width, i.height, i.latencyMs, s.coldStartMs ?: "",
                    i.subjectCount, i.subjectBoxShares.joinToString("|") { fmt(it, 3) },
                    fmt(m.fgFraction, 4), fmt(m.softEdgeRatio, 4), m.fragments,
                    fmt(m.largestShare, 3), fmt(m.holeFraction, 4), m.touchesBorder,
                    m.flags.joinToString("|"), i.rating.name, "\"${i.inputInfo}\"",
                    i.exportWidth, i.exportHeight, i.cleanupMs, i.cleanupVerdict.name,
                ).joinToString(",")
            }
            File(sessionDir, "results.csv").writeText((listOf(header) + rows).joinToString("\n"))
        }
    }

    // Locale.US so a German/French phone doesn't write "0,1234" into a CSV.
    private fun fmt(v: Float, digits: Int) = String.format(Locale.US, "%.${digits}f", v)

    override fun onCleared() {
        engine.close()
    }
}
