package com.example.sonicarenfc

import android.nfc.Tag
import android.nfc.tech.MifareUltralight

fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }

/** Reads the NTAG213 user + config area (pages 0x00..0x2A). No password needed for reading. */
fun readTag(tag: Tag): ScanResult {
    val uid = tag.id.toHex().replace(" ", ":")
    val ul = MifareUltralight.get(tag)
        ?: throw IllegalStateException("Not an NTAG/Ultralight tag — is this a Sonicare brush head?")
    val pages = mutableListOf<ByteArray>()
    ul.connect()
    try {
        var page = 0
        while (page <= 0x2A) {
            // readPages returns 4 pages (16 bytes) per call
            ul.readPages(page).toList().chunked(4).forEach { pages += it.toByteArray() }
            page += 4
        }
    } finally {
        runCatching { ul.close() }
    }
    return scanResult(uid, "NTAG213 (NFC-A)", pages.take(0x2B))
}
