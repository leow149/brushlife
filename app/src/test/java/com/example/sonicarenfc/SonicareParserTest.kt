package com.example.sonicarenfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Parses an Intel HEX export of a tag into 4-byte NTAG pages. */
private fun pagesFromHex(text: String): List<ByteArray> {
    val mem = HashMap<Int, Int>()
    for (line in text.lines().map { it.trim() }.filter { it.startsWith(":") }) {
        val b = line.substring(1).chunked(2).map { it.toInt(16) }
        if (b[3] == 0) for (i in 0 until b[0]) mem[((b[1] shl 8) or b[2]) + i] = b[4 + i]
    }
    val size = (mem.keys.max() + 1 + 3) / 4
    return List(size) { p -> ByteArray(4) { i -> (mem[p * 4 + i] ?: 0).toByte() } }
}

/** The dump printed in Künzi's write-up (https://kuenzi.dev/toothbrush/), a white "Optimal Plaque Defence". */
private val kunziHead: List<ByteArray> = listOf(
    "04ECFC9C", "A2941090", "B648FFFF", "E1101200", "0320D101", "1C550270", "68696C69", "70732E63",
    "6F6D2F6E", "66636272", "75736868", "65616474", "6170FE00",
) .let { head ->
    val zeros = List(0x1F - head.size) { "00000000" }
    head + zeros + listOf("00010700", "00000002", "60543232", "31323134", "2031324B",
        "B3020200", "00000000", "00000000", "00000001", "000330BD", "04000010", "43000000", "00000000", "00000000")
}.map { h -> ByteArray(4) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }

class SonicareParserTest {
    private val blackHead = pagesFromHex(
        javaClass.getResource("/real_black_head.hex")!!.readText()
    )

    @Test fun realBlackHeadIsRecognisedAsABrush() {
        assertNotNull(identifyHead(blackHead))
    }

    @Test fun realBlackHeadUsageCounter() {
        val u = parseUsage(blackHead)!!
        assertEquals(0x337D, u.usedSeconds)          // 7D 33 on page 0x24, little endian
        assertEquals(21600, u.maxSeconds)            // 60 54 on page 0x21 = 0x5460
        assertEquals(38, u.remainingPercent)         // 38.98 %, the UI truncates
        assertEquals(35, u.daysLeft(2))              // 8,419 s left / (2 x 120 s) = 35 days
    }

    // Page 0x1F byte 2 = 0x11 = "Premium All-in-One", black (mbirth's type table).
    @Test fun realBlackHeadTypeAndColour() {
        val h = identifyHead(blackHead)!!
        assertEquals(0x11, h.typeCode)
        assertEquals("Premium All-in-One", h.model)
        assertEquals(HeadColor.BLACK, h.color)
        assertEquals(HeadColor.BLACK, h.displayColor(null))
    }

    @Test fun kunziHeadIsWhiteOptimalPlaqueDefence() {
        val h = identifyHead(kunziHead)!!
        assertEquals(0x07, h.typeCode)
        assertEquals("Optimal Plaque Defence", h.model)
        assertEquals(HeadColor.WHITE, h.color)
        assertEquals(0x02B3, parseUsage(kunziHead)!!.usedSeconds)   // B3 02 on page 0x24
    }

    // Page 0x22 is part of the production date, so differing values there must not change the colour.
    @Test fun page0x22IsNotAColour() {
        val pages = blackHead.map { it.copyOf() }.toMutableList()
        pages[0x22] = "1111".toByteArray()
        assertEquals(HeadColor.BLACK, identifyHead(pages)!!.color)
    }

    @Test fun unknownTypeIsRainbowUnlessTheUserPicks() {
        val pages = blackHead.map { it.copyOf() }
        pages[0x1F][2] = 0x7F
        val h = identifyHead(pages)!!
        assertEquals(HeadColor.UNKNOWN, h.color)
        assertEquals(false, h.isRecognised)
        assertNull(h.model)
        assertEquals(0x7F, h.typeCode)
        assertEquals(HeadColor.RAINBOW, h.displayColor(null))
        assertEquals(HeadColor.WHITE, h.displayColor(HeadColor.WHITE))
        assertEquals(HeadColor.BLACK, h.displayColor(HeadColor.BLACK))
    }

    // Recognised types are fixed by the tag; a stray user choice must not change them.
    @Test fun recognisedTypesIgnoreTheUsersChoice() {
        val black = identifyHead(blackHead)!!
        assertEquals(true, black.isRecognised)
        assertEquals(HeadColor.BLACK, black.displayColor(HeadColor.WHITE))
        assertEquals(HeadColor.BLACK, black.displayColor(HeadColor.RAINBOW))
        val white = identifyHead(kunziHead)!!
        assertEquals(HeadColor.WHITE, white.displayColor(HeadColor.BLACK))
    }

    // Every table entry must resolve to a real colour, and the table has the 22 documented types.
    @Test fun headTypeTableIsComplete() {
        assertEquals(22, SonicareLayout.HEAD_TYPES.size)
        assertEquals((0x01..0x16).toList(), SonicareLayout.HEAD_TYPES.keys.sorted())
    }

    @Test fun implausibleTargetFallsBackToDefault() {
        val pages = blackHead.map { it.copyOf() }
        pages[0x21][0] = 0; pages[0x21][1] = 0
        assertEquals(21600, parseUsage(pages)!!.maxSeconds)
    }

    @Test fun randomZeroTagIsRejected() {
        assertNull(identifyHead(List(0x2B) { ByteArray(4) }))
    }
}
