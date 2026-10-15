package com.example.segtest

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Launch with: adb shell am start -n <your.app.id>/com.example.segtest.SegTestActivity */
class SegTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SegTestScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegTestScreen(vm: SegTestViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris -> vm.process(uris) }

    Scaffold(topBar = { TopAppBar(title = { Text("Segmentation test · build ${BuildConfig.BUILD_ID}") }) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
            item {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    Text("This harness needs Android 9+ (ImageDecoder).", color = MaterialTheme.colorScheme.error)
                }
                Text("1. Category for the next batch", style = MaterialTheme.typography.labelLarge)
                ChipRow(ShotCategory.entries, s.category, { it.label }, vm::setCategory)
                Text("2. Size ML Kit segments at (export is always $EXPORT_LONG_EDGE px)", style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf(1024, 1600, 2048), s.longEdge, { "$it px" }, vm::setLongEdge)
                Button(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !s.busy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) { Text("3. Pick photos (${s.category.label})") }
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(s.status, style = MaterialTheme.typography.bodySmall)
                s.coldStartMs?.let { Text("Cold start (first inference): $it ms", style = MaterialTheme.typography.bodySmall) }
                s.sessionDir?.let { Text("Saved to: $it", style = MaterialTheme.typography.bodySmall) }
                if (s.items.isNotEmpty()) Summary(s.items)
            }
            items(s.items, key = { it.id }) { ResultCard(it, vm::rate, vm::judgeCleanup) }
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

/** Per-category tally so you can see at a glance which shot types break. */
@Composable
private fun Summary(items: List<SegItem>) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Summary", style = MaterialTheme.typography.titleSmall)
            items.groupBy { it.category to it.longEdge }.forEach { (key, list) ->
                val (cat, edge) = key
                val rated = list.filter { it.rating != Rating.NONE }
                val pass = rated.count { it.rating == Rating.PASS }
                val fix = rated.count { it.rating == Rating.FIXABLE }
                val medianMs = list.map { it.latencyMs }.sorted()[list.size / 2]
                val medianTotal = list.map { it.latencyMs + it.cleanupMs }.sorted()[list.size / 2]
                val better = list.count { it.cleanupVerdict == CleanupVerdict.BETTER }
                val worse = list.count { it.cleanupVerdict == CleanupVerdict.WORSE }
                Text(
                    "${cat.label} @${edge}px: ${list.size} shots · pass $pass · fixable $fix of ${rated.size} · " +
                        "seg $medianMs ms, total $medianTotal ms · cleanup better $better / worse $worse",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(item: SegItem, onRate: (Int, Rating) -> Unit, onCleanup: (Int, CleanupVerdict) -> Unit) {
    var preview by remember { mutableStateOf(Preview.DARK_CLEAN) }
    val m = item.metrics
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${item.fileStem} · seg ${item.width}×${item.height} ${item.latencyMs} ms · " +
                    "export ${item.exportWidth}×${item.exportHeight} +${item.cleanupMs} ms",
                style = MaterialTheme.typography.titleSmall,
            )
            ChipRow(Preview.entries, preview, { it.label }) { preview = it }
            item.previews[preview]?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = preview.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                )
            }
            Text(
                "fg ${pct(m.fgFraction)} · soft edges ${pct(m.softEdgeRatio)} · holes ${pct(m.holeFraction)} · " +
                    "pieces ${m.fragments} (largest ${pct(m.largestShare)}) · subjects ${item.subjectCount}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "decoded as: ${item.inputInfo}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (m.flags.isNotEmpty()) {
                Text("Auto-flags: ${m.flags.joinToString()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text("Cleaned cutout: would you post it as-is?", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            ChipRow(listOf(Rating.PASS, Rating.FIXABLE, Rating.FAIL), item.rating, { it.name.lowercase() }) { onRate(item.id, it) }
            Text("Edge cleanup vs raw (compare the Cleaned and Raw previews)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
            ChipRow(listOf(CleanupVerdict.BETTER, CleanupVerdict.SAME, CleanupVerdict.WORSE), item.cleanupVerdict, { it.name.lowercase() }) { onCleanup(item.id, it) }
        }
    }
}

private fun pct(v: Float) = "${(v * 100).toInt()}%"
