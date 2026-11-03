package com.example.sonicarenfc

/** Result of one tag scan. [pages] is indexed by NTAG page number (4 bytes each). */
data class ScanResult(
    val uid: String,
    val tech: String,
    val pages: List<ByteArray>,
    val head: HeadInfo?,   // null = tag is not recognised as a Sonicare brush head
    val usage: Usage?,     // only set when [head] is
)

/** UNKNOWN = a head type that is not in [SonicareLayout.HEAD_TYPES]; it shows as the rainbow unless the user picks. */
enum class HeadColor { BLACK, WHITE, RAINBOW, UNKNOWN }

/** [model] is null for a type code we don't know. [targetSeconds] is the head's own end-of-life value. */
data class HeadInfo(val color: HeadColor, val model: String?, val typeCode: Int, val targetSeconds: Int?) {
    val isRecognised get() = color != HeadColor.UNKNOWN

    /**
     * Recognised head types are fixed by the tag. Only an unrecognised type honours the user's [choice];
     * without one it gets the rainbow.
     */
    fun displayColor(choice: HeadColor?): HeadColor =
        if (isRecognised) color else (choice ?: HeadColor.RAINBOW)
}

fun scanResult(uid: String, tech: String, pages: List<ByteArray>): ScanResult {
    val head = identifyHead(pages)
    return ScanResult(uid, tech, pages, head, if (head != null) parseUsage(pages) else null)
}

data class Usage(val usedSeconds: Int, val maxSeconds: Int) {
    val remainingFraction get() = (1f - usedSeconds.toFloat() / maxSeconds).coerceIn(0f, 1f)
    val remainingPercent get() = (remainingFraction * 100).toInt()
    val remainingMinutes get() = ((maxSeconds - usedSeconds) / 60).coerceAtLeast(0)

    /** Whole days of brushing left at [sessionsPerDay] sessions of [SESSION_SECONDS] each. */
    fun daysLeft(sessionsPerDay: Int): Int =
        ((maxSeconds - usedSeconds).coerceAtLeast(0) / (sessionsPerDay.coerceAtLeast(1) * SESSION_SECONDS))

    companion object {
        const val SESSION_SECONDS = 120 // Sonicare's standard 2-minute brushing cycle
        const val DEFAULT_SESSIONS_PER_DAY = 2
    }
}

/**
 * Sonicare brush heads carry an NXP NTAG213. Layout from C. Künzi (https://kuenzi.dev/toothbrush/) and,
 * for the head type table, M. Birth (https://blog.mbirth.uk/2026/03/29/sonicare-brush-head-nfc-data.html).
 * Both agree with a real "Premium All-in-One, Black" head dumped on 2026-10-13.
 *  - page 0x1F, byte 2: head type = model + colour (table below)
 *  - page 0x21, bytes 0-1: the head's end-of-life target in seconds, little-endian (usually 0x5460 = 21,600 s)
 *  - pages 0x21-0x23: production code as ASCII, e.g. "T260410 33K" = 2026-04-10, line 33K
 *  - page 0x24, bytes 0-1: total brushing time in seconds, little-endian (read needs no password;
 *    writing requires PWD_AUTH). Byte 2 = last intensity, byte 3 = last mode.
 *  - the handle's replace-head LED triggers once the counter exceeds the target
 *
 * NOTE: page 0x22 is NOT a colour code. Künzi's write-up suggested "1214" = black / "1111" = white,
 * but those are the production-date digits of his two heads (2022-12-14 / 2022-11-11).
 */
object SonicareLayout {
    const val TYPE_PAGE = 0x1F
    const val TYPE_BYTE = 2
    const val TARGET_PAGE = 0x21
    const val USAGE_PAGE = 0x24
    const val DEFAULT_TARGET_SECONDS = 0x5460
    const val NDEF_FIRST_PAGE = 0x04     // NDEF record with the Philips product link
    const val NDEF_LAST_PAGE = 0x0C

    private val W = HeadColor.WHITE
    private val K = HeadColor.BLACK
    /** Page 0x1F byte 2 -> (model, colour). 22 known types per mbirth's map. */
    val HEAD_TYPES: Map<Int, Pair<String, HeadColor>> = mapOf(
        0x01 to ("Premium Plaque Defence" to W), 0x02 to ("Premium Plaque Defence" to K),
        0x03 to ("Premium Gum Care" to W), 0x04 to ("Premium Gum Care" to K),
        0x05 to ("Premium White" to W), 0x06 to ("Premium White" to K),
        0x07 to ("Optimal Plaque Defence" to W), 0x08 to ("Optimal Gum Care" to W),
        0x09 to ("Optimal White" to W), 0x0A to ("Optimal White" to K),
        0x0B to ("Optimal White (small)" to W), 0x0C to ("InterCare" to W),
        0x0D to ("InterCare (small)" to W), 0x0E to ("TongueCare+" to W),
        0x0F to ("TongueCare+" to K), 0x10 to ("Premium All-in-One" to W),
        0x11 to ("Premium All-in-One" to K), 0x12 to ("SimplyClean" to W),
        0x13 to ("ProResults" to W), 0x14 to ("Sensitive" to W),
        0x15 to ("Sensitive" to K), 0x16 to ("Gentle Clean" to W),
    )
}

/**
 * A tag counts as a brush head only if it is an NTAG (capability container magic 0xE1 on page 3)
 * AND its NDEF area contains the Philips/Sonicare link. Anything else (random tags, blank tags)
 * is rejected so we never show a lifetime computed from unrelated bytes.
 */
fun identifyHead(pages: List<ByteArray>): HeadInfo? {
    if (pages.size <= SonicareLayout.USAGE_PAGE) return null
    if ((pages[3][0].toInt() and 0xFF) != 0xE1) return null
    val ndef = (SonicareLayout.NDEF_FIRST_PAGE..SonicareLayout.NDEF_LAST_PAGE)
        .flatMap { pages[it].toList() }.toByteArray()
        .toString(Charsets.ISO_8859_1).lowercase()
    if ("philips" !in ndef && "sonicare" !in ndef) return null
    val typeCode = pages[SonicareLayout.TYPE_PAGE][SonicareLayout.TYPE_BYTE].toInt() and 0xFF
    val type = SonicareLayout.HEAD_TYPES[typeCode]
    return HeadInfo(type?.second ?: HeadColor.UNKNOWN, type?.first, typeCode, targetSeconds(pages))
}

/** The head's own end-of-life target (page 0x21, little endian); null if it looks implausible. */
fun targetSeconds(pages: List<ByteArray>): Int? {
    val p = pages.getOrNull(SonicareLayout.TARGET_PAGE) ?: return null
    val v = (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8)
    return v.takeIf { it in 3_600..60_000 }
}

fun parseUsage(pages: List<ByteArray>): Usage? {
    val p = pages.getOrNull(SonicareLayout.USAGE_PAGE) ?: return null
    if (p.size < 2) return null
    val seconds = (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8)
    return Usage(seconds, targetSeconds(pages) ?: SonicareLayout.DEFAULT_TARGET_SECONDS)
}
