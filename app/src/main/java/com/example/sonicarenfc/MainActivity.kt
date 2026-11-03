package com.example.sonicarenfc

import android.nfc.NfcAdapter
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.os.Build

class MainActivity : ComponentActivity() {
    private var result by mutableStateOf<ScanResult?>(null)
    private var error by mutableStateOf<String?>(null)
    private var adapter: NfcAdapter? = null
    private var perDay by mutableIntStateOf(Usage.DEFAULT_SESSIONS_PER_DAY)
    /** User's colour pick per unrecognised head type code; recognised types are never overridden. */
    private val unknownChoices = mutableStateMapOf<Int, HeadColor>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        adapter = NfcAdapter.getDefaultAdapter(this)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        perDay = prefs.getInt("sessions_per_day", Usage.DEFAULT_SESSIONS_PER_DAY)
        prefs.edit().remove("head_color").apply() // old global setting, replaced by the per-type choice
        for ((key, value) in prefs.all) {
            val code = key.removePrefix("head_color_").toIntOrNull()?.takeIf { key.startsWith("head_color_") } ?: continue
            runCatching { HeadColor.valueOf(value as String) }.getOrNull()?.let { unknownChoices[code] = it }
        }
        applyFakeScan(intent)
        setContent {
            App(
                result, error, adapter?.isEnabled,
                perDay, { perDay = it; prefs.edit().putInt("sessions_per_day", it).apply() },
                unknownChoices,
                { type, color -> unknownChoices[type] = color; prefs.edit().putString("head_color_$type", color.name).apply() },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyFakeScan(intent)
    }

    /**
     * Debug-only: `adb shell am start -n .../.MainActivity --ei fake_seconds 5000 --es fake_kind white`
     * shows a fake scan. fake_kind: black (default) | white | rainbow (unknown head type) | blank (random tag, all zeroes).
     * Ignored in non-debuggable builds.
     */
    private fun applyFakeScan(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val secs = intent?.getIntExtra("fake_seconds", -1) ?: -1
        val kind = intent?.getStringExtra("fake_kind")
        if (secs < 0 && kind == null) return
        val pages = List(0x2B) { ByteArray(4) }.toMutableList()
        if (kind != "blank") {
            pages[3] = byteArrayOf(0xE1.toByte(), 0x10, 0x12, 0x00)
            "\u0003\u0020\u00D1\u0001U\u0004philips.com/sonicare".toByteArray(Charsets.ISO_8859_1)
                .toList().chunked(4).forEachIndexed { i, c ->
                    pages[4 + i] = (c + List(4 - c.size) { 0 }).toByteArray()
                }
            val type = when (kind) { "white" -> 0x10; "rainbow" -> 0x7F; else -> 0x11 } // 0x7F = unknown type
            pages[SonicareLayout.TYPE_PAGE] = byteArrayOf(0x00, 0x01, type.toByte(), 0x00)
            pages[SonicareLayout.TARGET_PAGE] = byteArrayOf(0x60, 0x54, 0x32, 0x36)
        }
        val sec = secs.coerceAtLeast(0)
        pages[SonicareLayout.USAGE_PAGE] = byteArrayOf(sec.toByte(), (sec shr 8).toByte(), 0x00, 0x02)
        result = scanResult("04:AA:BB:CC:DD:EE:80", "FAKE (debug)", pages)
        error = null
    }

    override fun onResume() {
        super.onResume()
        adapter?.enableReaderMode(
            this,
            { tag ->
                try { result = readTag(tag); error = null }
                catch (e: Exception) { error = e.message ?: "Read failed" }
            },
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    override fun onPause() {
        super.onPause()
        adapter?.disableReaderMode(this)
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF00696A), onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF1F1), onPrimaryContainer = Color(0xFF002020),
    secondaryContainer = Color(0xFFCCE8E7), onSecondaryContainer = Color(0xFF051F1F),
    tertiary = Color(0xFF8A5100), onTertiary = Color.White,
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5D5), onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F50), onPrimaryContainer = Color(0xFF9CF1F1),
    secondaryContainer = Color(0xFF334B4B), onSecondaryContainer = Color(0xFFCCE8E7),
    tertiary = Color(0xFFFFB86B), onTertiary = Color(0xFF4A2800),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    result: ScanResult?, error: String?, nfcOn: Boolean?,
    perDay: Int, onPerDay: (Int) -> Unit,
    unknownChoices: Map<Int, HeadColor>, onUnknownChoice: (Int, HeadColor) -> Unit,
) {
    AppTheme {
        Scaffold(
            topBar = { CenterAlignedTopAppBar(title = { Text("BrushLife") }) },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Hold your phone against the brush head",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val usage = result?.usage
                Gauge(usage)

                when {
                    error != null -> Notice(error)
                    result?.head == null && result != null ->
                        Notice("This tag isn't a Sonicare brush head, so there's no lifetime to show.")
                    usage != null -> LifetimeCard(usage, perDay, result!!.head!!, result.head!!.displayColor(unknownChoices[result.head!!.typeCode]))
                    nfcOn == null -> Notice("This device has no NFC.")
                    nfcOn == false -> Notice("NFC is turned off. Enable it in system settings.")
                    else -> Notice("Waiting for a tag…")
                }

                val head = result?.head
                Settings(
                    perDay, onPerDay,
                    unknownHead = head?.takeIf { !it.isRecognised },
                    selected = head?.let { unknownChoices[it.typeCode] } ?: HeadColor.RAINBOW,
                    onSelect = { c -> head?.let { onUnknownChoice(it.typeCode, c) } },
                )
                if (result != null) RawData(result)
            }
        }
    }
}

@Composable
private fun Gauge(usage: Usage?) {
    val progress by animateFloatAsState(usage?.remainingFraction ?: 0f, tween(900), label = "gauge")
    val scheme = MaterialTheme.colorScheme
    val color = when {
        usage == null || progress > .5f -> scheme.primary
        progress > .2f -> scheme.tertiary
        else -> scheme.error
    }
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxSize(),
            color = color,
            trackColor = scheme.surfaceContainerHighest,
            strokeWidth = 16.dp,
            strokeCap = StrokeCap.Round,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (usage == null) "–" else "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.displayMedium,
            )
            Text(
                "life remaining",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LifetimeCard(u: Usage, perDay: Int, head: HeadInfo, color: HeadColor) {
    val days = u.daysLeft(perDay)
    val status = when {
        u.remainingPercent > 50 -> "Looking good"
        u.remainingPercent > 20 -> "Wearing down"
        else -> "Time to replace"
    }
    // Recognised heads show their tag colour; unrecognised ones show the raw type code instead.
    val detail = if (head.isRecognised) head.color.name.lowercase().replaceFirstChar { it.uppercase() }
                 else "Type 0x%02X".format(head.typeCode)
    ElevatedCard(Modifier.fillMaxWidth()) {
      Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Text(head.model ?: "Unrecognised head", style = MaterialTheme.typography.titleLarge)
            Text("$detail · $status", style = MaterialTheme.typography.labelLarge, color = muted)
            Spacer(Modifier.height(4.dp))
            Text(
                "$days ${if (days == 1) "day" else "days"} left",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "at $perDay brushing${if (perDay == 1) "" else "s"} a day",
                style = MaterialTheme.typography.bodyMedium, color = muted,
            )
            Text(
                "About ${u.remainingMinutes} min of brushing left · used ${u.usedSeconds / 60} of ${u.maxSeconds / 60} min",
                style = MaterialTheme.typography.bodyMedium, color = muted,
            )
        }
        Spacer(Modifier.width(16.dp))
        BrushHead(color, u.remainingFraction, Modifier.size(width = 42.dp, height = 150.dp))
      }
    }
}

/**
 * Sonicare head drawn from traced vector art ([BrushArt]). The body is black, white or rainbow
 * depending on the head; the blue indicator tufts fade towards white as the head wears out.
 */
@Composable
private fun BrushHead(color: HeadColor, remaining: Float, modifier: Modifier = Modifier) {
    val parser = remember { PathParser() }
    val paths = remember {
        listOf(BrushArt.BODY, BrushArt.WHITE, BrushArt.GREEN, BrushArt.BLUE).map {
            parser.parsePathString(it).toPath()
        }
    }
    val (body, whiteTufts, greenTufts, blueTufts) = paths
    val onWhite = color == HeadColor.WHITE
    val bodyFill: Brush = when (color) {
        HeadColor.BLACK, HeadColor.UNKNOWN -> SolidColor(Color(0xFF26282B))
        HeadColor.WHITE -> SolidColor(Color(0xFFF3F4F6))
        HeadColor.RAINBOW -> Brush.verticalGradient(
            listOf(
                Color(0xFFE53935), Color(0xFFFB8C00), Color(0xFFFDD835),
                Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA),
            ),
            startY = 0f, endY = BrushArt.HEIGHT,
        )
    }
    val outline = when (color) {
        HeadColor.BLACK, HeadColor.UNKNOWN -> Color(0xFF0E0F10)
        HeadColor.WHITE -> Color(0xFFB8BCC2)
        HeadColor.RAINBOW -> Color(0xFF3A3D42)
    }
    val tuftOutline = Color(0xFFC9CDD3)
    val blue = androidx.compose.ui.graphics.lerp(Color.White, Color(0xFF3B8EE8), remaining.coerceIn(0f, 1f))
    Canvas(modifier) {
        val scale = minOf(size.width / BrushArt.WIDTH, size.height / BrushArt.HEIGHT)
        val dx = (size.width - BrushArt.WIDTH * scale) / 2
        val dy = (size.height - BrushArt.HEIGHT * scale) / 2
        withTransform({ translate(dx, dy); scale(scale, scale, pivot = Offset.Zero) }) {
            drawPath(body, bodyFill)
            drawPath(body, outline, style = Stroke(width = 1.5f))
            drawPath(whiteTufts, Color.White)
            if (onWhite) drawPath(whiteTufts, tuftOutline, style = Stroke(width = 1.2f))
            drawPath(greenTufts, Color(0xFF84CF3A))
            drawPath(blueTufts, blue)
            if (onWhite) drawPath(blueTufts, Color(0xFF9CB8D8), style = Stroke(width = 1.2f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Settings(
    perDay: Int, onChange: (Int) -> Unit,
    unknownHead: HeadInfo?, selected: HeadColor, onSelect: (HeadColor) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("Brushings per day") },
            supportingContent = { Text("2 min each") },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { if (perDay > 1) onChange(perDay - 1) }, enabled = perDay > 1) {
                        Text("−", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        "$perDay",
                        Modifier.widthIn(min = 40.dp),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    FilledTonalIconButton(onClick = { if (perDay < 6) onChange(perDay + 1) }, enabled = perDay < 6) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                }
            },
        )
        // Only for head types we don't recognise; recognised types are fixed by the tag.
        if (unknownHead != null) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Head colour") },
                supportingContent = {
                    Text("Head type 0x%02X isn't in the known list. Pick how it should look.".format(unknownHead.typeCode))
                },
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                val options = listOf(HeadColor.BLACK, HeadColor.WHITE, HeadColor.RAINBOW)
                options.forEachIndexed { i, opt ->
                    SegmentedButton(
                        selected = opt == selected,
                        onClick = { onSelect(opt) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        label = { Text(opt.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RawData(r: ScanResult) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide raw data" else "Show raw data") }
        if (open) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    Text("UID  ${r.uid}", style = mono)
                    Text("Tech ${r.tech}", style = mono)
                    Spacer(Modifier.height(8.dp))
                    r.pages.forEachIndexed { i, b ->
                        Text(
                            "0x%02X: %s".format(i, b.toHex()),
                            style = mono,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = if (i == SonicareLayout.USAGE_PAGE) FontWeight.Bold else null,
                        )
                    }
                }
            }
        }
    }
}
